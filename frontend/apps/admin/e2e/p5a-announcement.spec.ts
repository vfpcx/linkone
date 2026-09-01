/**
 * P5-A W4/W5 · 平台公告 + 消息中心 E2E（p5a-announcement.spec.ts）
 *
 * 覆盖（14-p5a-e2e-cases.md §3 链 1/链 2）：
 *  1) 公告全链：OPS 创建并发布(TA 目标) → TA 收到站内信 + 公告弹窗只弹一次 →
 *     消息中心「公告」分组可见 → 全部已读 → 角标清零
 *  2) 非目标角色（WA）收不到该公告
 *  3) OPS 下架公告 → 状态 INACTIVE，已发通知保留
 *  4) 消息中心：分组 Tab（全部/业务/公告）、单条已读（幂等）、全部已读（幂等）、空态
 *  5) 边界/异常：重复发布 50502、DRAFT 下架 50502、缺字段拦截、超长校验、非 OPS 越权
 *
 * 前置：后端 8080（main ≥ 4fc717b：B1 修复 + W4 撮合）+ 前端 dev server（W4 6f0ca67 + B3 修复）。
 * 造数：复用 helpers/onboarding.ts（真实注册 TA/OPS/WA，SMS mock 888888）。
 *
 * 注：B3（登录即弹失效）已由 frontend-dev 修复（route.path 离开认证页补触发 checkAndShow，
 *     单角色直跳与多角色切换器两路径均覆盖）；AN-02 现按「首次登录成功即弹」直接断言。
 *
 * 撮合运营链（P5A-FE-*）在 p5a-storefront-featured.spec.ts。
 */

import { test, expect } from '@playwright/test'
import path from 'node:path'
import fs from 'node:fs'
import {
  registerWithRetry,
  apiPost,
  apiGet,
  ok,
  uniqPhone,
  SMS_CODE,
  SEED_PWD,
  type LoginData,
  loginAs,
  injectAuthAndGoto,
} from './helpers/onboarding'

// ============ 路由与选择器（对齐 frontend-dev W4 实际实现） ============
const ROUTE_MESSAGE_CENTER = '/ta/messages'
const ROUTE_OPS_ANNOUNCEMENTS = '/ops/announcements'

const SCREEN_DIR = path.resolve(process.cwd(), 'test-results/screens')
test.beforeAll(() => {
  fs.mkdirSync(SCREEN_DIR, { recursive: true })
})

// ============ API 断言 helpers（后端已就绪，可脱离 UI 验证） ============

interface NotificationRow {
  id: string
  type: string
  title: string
  content: string
  refType: string | null
  refId: string | null
  readAt: string | null
  createdAt: string
}

/** 本人消息列表（可按 group 过滤），group 传 undefined 即全部 */
async function listNotifs(token: string, group?: 'ANNOUNCE' | 'BIZ' | 'SYS') {
  const env = await apiGet<{ records: NotificationRow[]; total: number }>('/notifications', token, {
    page: 1,
    size: 50,
    group: group ?? '',
  })
  return ok(env, '消息列表').records ?? []
}

async function unreadCount(token: string): Promise<number> {
  const env = await apiGet<{ count: number }>('/notifications/unread-count', token)
  return Number(ok(env, '未读数').count ?? 0)
}

interface AnnouncementRow {
  id: string
  title: string
  content: string
  targetRoles: string[]
  status: string
  publishedAt: string | null
}

async function listAnnouncements(opsToken: string, status?: string): Promise<AnnouncementRow[]> {
  const env = await apiGet<{ records: AnnouncementRow[] }>('/ops/announcements', opsToken, {
    page: 1,
    size: 50,
    status: status ?? '',
  })
  return ok(env, '公告列表').records ?? []
}

/** 创建公告（DRAFT）并返回列表回查出的字符串 id（create 响应雪花 id 走 JSON number 会丢精度） */
async function createAnnouncement(opsToken: string, title: string, targetRoles: string[]): Promise<string> {
  const env = await apiPost('/ops/announcements', { title, content: 'P5A E2E 公告正文', targetRoles }, opsToken)
  expect(env.code, env.message).toBe(0)
  const created = (await listAnnouncements(opsToken)).find((r) => r.title === title)
  if (!created) throw new Error(`[p5a] 创建后列表回查未找到公告: ${title}`)
  return created.id
}

// ============ 全局造数（串行共享：OPS + TA + WA + 公告草稿/发布） ============

test.describe.serial('P5-A 公告+消息中心', () => {
  let ops: LoginData
  let ta: LoginData
  let wa: LoginData
  let taPhone = ''
  let waPhone = ''
  let announcementId = '' // annTitle（主公告，链 1/2 全用）
  let secondId = '' // secondTitle（NC-02 单条已读专用，NC-02 内再发布避免弹窗介入）

  const annTitle = `P5A-E2E-${String(Date.now()).slice(-6)}`
  const secondTitle = `P5A-E2E-2-${String(Date.now()).slice(-6)}`

  test.beforeAll(async () => {
    test.setTimeout(180_000)
    taPhone = uniqPhone()
    waPhone = uniqPhone()
    ops = await registerWithRetry(
      {
        phone: uniqPhone(),
        password: SEED_PWD,
        smsCode: SMS_CODE,
        role: 'OPS',
        realName: 'P5AOps',
        agreedTerms: true,
      },
      'OPS 注册',
    )
    ta = await registerWithRetry(
      {
        phone: taPhone,
        password: SEED_PWD,
        smsCode: SMS_CODE,
        role: 'TA',
        realName: 'P5ATA',
        tenantName: 'P5A仓' + String(Date.now()).slice(-4),
        agreedTerms: true,
      },
      'TA 注册',
    )
    wa = await registerWithRetry(
      {
        phone: waPhone,
        password: SEED_PWD,
        smsCode: SMS_CODE,
        role: 'WA',
        realName: 'P5AWA',
        agreedTerms: true,
      },
      'WA 注册',
    )

    // 两条公告先建草稿：主公告直接发布；secondTitle 留到 NC-02 再发布（避免弹窗介入）
    announcementId = await createAnnouncement(ops.token, annTitle, ['TA'])
    secondId = await createAnnouncement(ops.token, secondTitle, ['TA'])
    ok(await apiPost(`/ops/announcements/${announcementId}/publish`, undefined, ops.token), '发布主公告')
  })

  // ============ 链 1 · 公告全链 ============

  test('AN-01 发布 TA 目标公告 → TA 收到站内信（API+UI 铃铛）', async ({ page }) => {
    // API：TA 未读 ≥1，且「公告」分组含本条
    expect(await unreadCount(ta.token)).toBeGreaterThanOrEqual(1)
    const announce = await listNotifs(ta.token, 'ANNOUNCE')
    expect(announce.some((n) => n.title === annTitle && n.refType === 'ANNOUNCEMENT')).toBe(true)

    // OPS 侧状态
    const rows = await listAnnouncements(ops.token)
    const mine = rows.find((r) => r.id === announcementId)
    expect(mine?.status).toBe('PUBLISHED')
    expect(mine?.publishedAt).toBeTruthy()

    // UI：TA 登录 → 工作台铃铛角标可见
    await loginAs(page, taPhone, SEED_PWD, /\/ta\/dashboard/)
    await expect(page.locator('[data-test="open-messages"]')).toBeVisible()
    await expect(page.locator('.cc-topbar__bell .el-badge__content')).toContainText(/\d/)
    await page.screenshot({ path: path.join(SCREEN_DIR, 'p5a-ta-dashboard-bell.png'), fullPage: true })
  })

  test('AN-02 登录公告弹窗只弹一次（readAt 去重）', async ({ page }) => {
    // B3 已修复：首次登录成功进入工作台即弹（route.path 离开认证页补触发 checkAndShow）
    await loginAs(page, taPhone, SEED_PWD, /\/ta\/dashboard/)

    const dialog = page.locator('[data-test="login-announcement-dialog"]')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText(annTitle)
    await page.screenshot({ path: path.join(SCREEN_DIR, 'p5a-announcement-dialog.png'), fullPage: true })
    await dialog.locator('[data-test="announcement-confirm"]').click()

    // 确认即 markRead：API 断言该通知已读（readAt 非空）
    // 等待 markRead 响应（弹窗 markRead 与断言查询并发，await 通知完成）
    const markReadResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/notifications/') && r.url().match(/\/read$/),
      { timeout: 5_000 },
    ).catch(() => null)
    await markReadResp
    const announce = await listNotifs(ta.token, 'ANNOUNCE')
    const target = announce.find((n) => n.title === annTitle)
    expect(target?.readAt).toBeTruthy()

    // 刷新重进不再弹（readAt 幂等）
    await page.reload()
    await page.waitForLoadState('networkidle')
    await expect(page.locator('[data-test="login-announcement-dialog"]')).toBeHidden()
  })

  test('NC-01 消息中心分组 Tab（全部/业务/公告）', async ({ page }) => {
    // 此时 annTitle 已读（AN-02 确认）→ 注入跳转不会弹窗
    await injectAuthAndGoto(page, ta, 'TA', ROUTE_MESSAGE_CENTER)
    const center = page.locator('[data-test="notification-list"]')
    await expect(center).toBeVisible()

    await center.getByRole('button', { name: '公告' }).click()
    await expect(center.locator('.cc-notif__item', { hasText: annTitle })).toHaveCount(1)
    await expect(center.locator('.cc-notif__item').first()).toContainText('公告')

    await center.getByRole('button', { name: '业务' }).click()
    await expect(center.locator('.cc-notif__item', { hasText: annTitle })).toHaveCount(0)

    await center.getByRole('button', { name: '全部', exact: true }).click()
    await expect(center.locator('.cc-notif__item').first()).toBeVisible()
    await page.screenshot({ path: path.join(SCREEN_DIR, 'p5a-notification-center.png'), fullPage: true })
  })

  test('NC-02 单条已读 + 已读幂等', async ({ page }) => {
    // 挂载消息中心后再发布第二条公告 → 该条未读；弹窗只在挂载时拉取（checkedForSession），不介入
    await injectAuthAndGoto(page, ta, 'TA', ROUTE_MESSAGE_CENTER)
    const center = page.locator('[data-test="notification-list"]')
    ok(await apiPost(`/ops/announcements/${secondId}/publish`, undefined, ops.token), '发布第二条公告')

    await center.getByRole('button', { name: '公告' }).click()
    await center.locator('.cc-notif__unread').click() // 只看未读 → 触发重查
    const item = center.locator('.cc-notif__item', { hasText: secondTitle })
    await expect(item).toBeVisible()
    await expect(item).toHaveClass(/is-unread/)

    await item.locator('.cc-notif__read-one').click()
    await expect(item).not.toHaveClass(/is-unread/)

    // 幂等：已读后按钮消失；再次点击行（展开正文）不报错
    await item.click()
    await expect(center.locator('.el-message--error')).toHaveCount(0)
  })

  test('NC-03 全部已读 → 角标清零（重复点击幂等）', async ({ page }) => {
    // API 侧先验幂等 + 清零
    ok(await apiPost('/notifications/read-all', undefined, ta.token), '全部已读')
    ok(await apiPost('/notifications/read-all', undefined, ta.token), '全部已读幂等')
    expect(await unreadCount(ta.token)).toBe(0)
    expect((await listNotifs(ta.token, 'ANNOUNCE')).filter((n) => !n.readAt).length).toBe(0)

    // UI：工作台角标隐藏
    await injectAuthAndGoto(page, ta, 'TA', '/ta/dashboard')
    await expect(page.locator('.cc-topbar__bell .el-badge__content')).toBeHidden()
  })

  test('NC-04 公告分组未读空态（只看未读）', async ({ page }) => {
    await injectAuthAndGoto(page, ta, 'TA', ROUTE_MESSAGE_CENTER)
    const center = page.locator('[data-test="notification-list"]')
    await center.getByRole('button', { name: '公告' }).click()
    await center.locator('.cc-notif__unread').click()
    await expect(center.locator('.cc-notif__empty')).toContainText(/暂无未读消息/)
    await page.screenshot({ path: path.join(SCREEN_DIR, 'p5a-notification-empty.png'), fullPage: true })
  })

  test('AN-03 非目标角色 WA 收不到 TA 目标公告', async ({ page }) => {
    const waAnnounce = await listNotifs(wa.token, 'ANNOUNCE')
    expect(waAnnounce.some((n) => n.title === annTitle)).toBe(false)
    expect(waAnnounce.some((n) => n.title === secondTitle)).toBe(false)

    // WA 登录无该公告弹窗
    await loginAs(page, waPhone, SEED_PWD, /\/wa\/inquiry/)
    await expect(page.locator('[data-test="login-announcement-dialog"]')).toBeHidden()
  })

  test('AN-04 OPS 下架公告 → 状态更新 + 通知保留', async ({ page }) => {
    ok(await apiPost(`/ops/announcements/${announcementId}/inactivate`, undefined, ops.token), '下架公告')
    const rows = await listAnnouncements(ops.token)
    const mine = rows.find((r) => r.id === announcementId)
    expect(mine?.status).toBe('INACTIVE')
    // 已发通知保留（收件人侧仍可见）
    const still = await listNotifs(ta.token, 'ANNOUNCE')
    expect(still.some((n) => n.title === annTitle)).toBe(true)

    // UI：OPS 公告管理页状态「已下架」中文展示
    await injectAuthAndGoto(page, ops, 'OPS', ROUTE_OPS_ANNOUNCEMENTS)
    const row = page.locator('.ann-table .el-table__row', { hasText: annTitle })
    await expect(row).toBeVisible()
    await expect(row).toContainText('已下架')
    await page.screenshot({ path: path.join(SCREEN_DIR, 'p5a-ops-announcements.png'), fullPage: true })
  })

  // ============ 链 2 · 边界/异常 ============

  test('AN-05 重复发布已发布公告 → 50502 且不重复发信', async () => {
    const before = await unreadCount(ta.token)
    const env = await apiPost(`/ops/announcements/${announcementId}/publish`, undefined, ops.token)
    expect(env.code).toBe(50502)
    // 未读数不因重复发布增加
    expect(await unreadCount(ta.token)).toBe(before)
  })

  test('AN-06 DRAFT 公告直接下架 → 50502', async () => {
    const draftTitle = `P5A-DRAFT-${String(Date.now()).slice(-6)}`
    const draftId = await createAnnouncement(ops.token, draftTitle, ['OPS'])
    const env = await apiPost(`/ops/announcements/${draftId}/inactivate`, undefined, ops.token)
    expect(env.code).toBe(50502)
  })

  test('AN-07 创建公告必填缺失 → 40001', async () => {
    const noTitle = await apiPost('/ops/announcements', { content: 'x', targetRoles: ['TA'] }, ops.token)
    expect(noTitle.code).toBe(40001)
    const noRoles = await apiPost('/ops/announcements', { title: 'x', content: 'y' }, ops.token)
    expect(noRoles.code).toBe(40001)
  })

  test('AN-08 公告超长 → 40001', async () => {
    const tooLongTitle = await apiPost(
      '/ops/announcements',
      { title: 'x'.repeat(129), content: 'y', targetRoles: ['TA'] },
      ops.token,
    )
    expect(tooLongTitle.code).toBe(40001)
    const tooLongContent = await apiPost(
      '/ops/announcements',
      { title: 'x', content: 'y'.repeat(513), targetRoles: ['TA'] },
      ops.token,
    )
    expect(tooLongContent.code).toBe(40001)
  })

  test('AN-09 非 OPS 访问公告管理 → 前端守卫 + 后端 42002', async ({ page }) => {
    const env = await apiGet('/ops/announcements', ta.token)
    expect(env.code).toBe(42002)

    // 前端角色守卫：TA 登录态直达 /ops/announcements → 弹回主路由 + 提示
    // （先落地主路由再 goto，避免 injectAuthAndGoto 把 primaryRouter 设为被守卫路径造成回环）
    await injectAuthAndGoto(page, ta, 'TA', '/ta/dashboard')
    await page.goto(ROUTE_OPS_ANNOUNCEMENTS)
    await expect(page).toHaveURL(/\/ta\//)
    await expect(page.getByText('无权访问平台运营页面').first()).toBeVisible()
  })
})
