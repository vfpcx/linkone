/**
 * P5-A W5c 终验收 · 视觉矩阵截图（供 Team Lead / 用户亲检，非断言型用例）
 *
 * 覆盖（对照 shared/test-plan/14-p5a-e2e-report.md §3 截图清单）：
 *  1) 登录公告弹窗可见态（TA 首次登录即弹，data-test=login-announcement-dialog）
 *  2) TA 消息中心页（分组 Tab：全部/业务/公告 + 未读态行）
 *  3) OPS 公告管理页（列表含「已发布」+「已下架」中文状态）
 *  4) TA 店铺设置「撮合运营」卡（主推商品/置顶批发商已选态 + 计数 1/20、1/5）
 *  5) RT 店铺页（「主推」「置顶」标识 + 置顶商户/主推商品前置排序态）
 *  6) 降档验证 @375×667：RT 店铺页、登录公告弹窗、TA 消息中心
 *
 * 产物：<repo>/.e2e-tmp/p5a-w5-visual/*.png（未跟踪，不进 git）
 * 前置：后端 8080（dev,local）+ 前端 dev server 5173，同 e2e/README。
 * 造数：复用 helpers/onboarding.ts（seedActiveTenant + WA 直申 + TA 审批 +
 *       seedStockForWholesaler + confirmPendingInbound），撮合配置走
 *       PUT /tenant/storefront/featured；公告走 OPS 创建/发布/下架接口。
 *
 * 截图策略（沿 p4-w5-visual.spec.ts 的 shot() 模式）：
 *  - 整页截图 fullPage（networkidle + 900ms 静置）；
 *  - fixed 遮罩型弹窗（登录公告）用视口截图：fullPage 会把 fixed 定位
 *    画在整页高度上造成「弹窗重复/错位」伪影（P4 先例同款教训），
 *    视口截图即真实用户视野，用于甄别弹窗本体布局/溢出。
 */

import { test, expect, type Page } from '@playwright/test'
import path from 'node:path'
import fs from 'node:fs'
import {
  seedActiveTenant,
  registerWaWithTarget,
  listTaApplications,
  seedStockForWholesaler,
  confirmPendingInbound,
  apiPost,
  apiGet,
  ok,
  loginAs,
  injectAuthAndGoto,
  SEED_PWD,
  type LoginData,
  type WaSeed,
} from './helpers/onboarding'

// cwd = frontend/apps/admin（pnpm --filter @cangchu/admin exec playwright test）
const OUT_DIR = path.resolve(process.cwd(), '../../../.e2e-tmp/p5a-w5-visual')

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'

/** PUT helper（onboarding 未导出 apiPut，按 call 同款契约本地实现） */
async function apiPut<T>(
  path: string,
  body: unknown,
  token: string,
): Promise<{ code: number; message?: string; data: T }> {
  const res = await fetch(`${API}/api/v1${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Authorization: token, satoken: token },
    body: JSON.stringify(body),
  })
  return (await res.json()) as { code: number; message?: string; data: T }
}

/** 动画/骨架静置后截图（fullPage 默认；弹窗态传 fullPage:false 截视口） */
async function shot(page: Page, name: string, opts: { fullPage?: boolean } = {}): Promise<void> {
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(900) // 入场动画静置（FE-W1 教训）
  await page.screenshot({ path: path.join(OUT_DIR, `${name}.png`), fullPage: opts.fullPage ?? true })
}

/**
 * 登录公告弹窗可能因前序用例残留的未读公告再次弹出（弹窗只拦挂载时的一次检查，
 * 视觉矩阵造数会持续发布公告）。非弹窗主题用例统一在此关闭，避免遮罩盖住断言目标。
 */
async function dismissAnnouncementDialogIfShown(page: Page): Promise<void> {
  const dlg = page.locator('[data-test="login-announcement-dialog"]')
  await dlg.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => undefined)
  if (await dlg.isVisible().catch(() => false)) {
    await dlg.locator('[data-test="announcement-confirm"]').click()
    await expect(dlg).toBeHidden()
  }
}

test.beforeAll(() => {
  fs.mkdirSync(OUT_DIR, { recursive: true })
})

// ============ 公告造数 helpers（对齐 p5a-announcement.spec.ts） ============

interface AnnouncementRow {
  id: string
  title: string
  status: string
}

async function listAnnouncements(opsToken: string): Promise<AnnouncementRow[]> {
  const env = await apiGet<{ records: AnnouncementRow[] }>('/ops/announcements', opsToken, {
    page: 1,
    size: 50,
  })
  return ok(env, '公告列表').records ?? []
}

async function createAnnouncement(opsToken: string, title: string, targetRoles: string[]): Promise<string> {
  const env = await apiPost(
    '/ops/announcements',
    { title, content: 'P5A W5 视觉验收：平台公告正文（全中文展示），确认或关闭后即标已读，刷新不再重复弹出。', targetRoles },
    opsToken,
  )
  expect(env.code, env.message).toBe(0)
  const created = (await listAnnouncements(opsToken)).find((r) => r.title === title)
  if (!created) throw new Error(`[p5a-w5-visual] 创建后列表回查未找到公告: ${title}`)
  return created.id
}

// ============ 全局造数（串行共享：租户/WA/库存/撮合配置/公告） ============

test.describe.serial('P5-A W5 视觉矩阵 · P5-A 新页', () => {
  let ta: LoginData
  let ops: LoginData
  let taPhone = ''
  let wa1: WaSeed
  let w1Id = ''
  let sku1Id = ''
  let sku1Name = ''
  let storeCode = ''
  // 公告分工：1 号供登录弹窗 @1280、2 号供消息中心未读态、3 号供 OPS 列表「已下架」、4 号供弹窗 @375
  let annId2 = ''
  let annId3 = ''
  let annId4 = ''
  const annTitle1 = `P5A-VIS-1-${String(Date.now()).slice(-6)}`
  const annTitle2 = `P5A-VIS-2-${String(Date.now()).slice(-6)}`
  const annTitle3 = `P5A-VIS-3-${String(Date.now()).slice(-6)}`
  const annTitle4 = `P5A-VIS-4-${String(Date.now()).slice(-6)}`

  /** 审批 WA 入驻并回查 wholesalerId（audit 响应可能不含该字段，以列表回查为准） */
  async function approveWa(waSeed: WaSeed): Promise<string> {
    const apps = await listTaApplications(ta.token, 'PENDING')
    const app = apps.find((a) => a.name === waSeed.wholesalerName)
    if (!app) throw new Error(`[p5a-w5-visual] 未找到入驻申请: ${waSeed.wholesalerName}`)
    ok(
      await apiPost(`/tenant/wholesaler-applications/${app.id}/audit`, { action: 'APPROVED' }, ta.token),
      'TA 审批入驻',
    )
    const ws = ok(
      await apiGet<Array<{ id: string; name: string; status: string }>>('/tenant/wholesalers', ta.token),
      '批发商列表',
    )
    const hit = ws.find((w) => w.name === waSeed.wholesalerName)
    if (!hit) throw new Error(`[p5a-w5-visual] 审批后批发商列表未找到: ${waSeed.wholesalerName}`)
    return String(hit.id)
  }

  test.beforeAll(async () => {
    test.setTimeout(240_000)
    const seed = await seedActiveTenant()
    ta = seed.ta.login
    ops = seed.ops.login
    taPhone = seed.ta.phone

    // WA1（置顶目标）：2 条在售 SKU（验证商户内主推前置）；再建 WA2（普通对比商户，不置顶）
    wa1 = await registerWaWithTarget(seed.tenantId)
    w1Id = await approveWa(wa1)
    const s1 = await seedStockForWholesaler(ta.token, w1Id, 4)
    const s2 = await seedStockForWholesaler(ta.token, w1Id, 4)
    const wa2 = await registerWaWithTarget(seed.tenantId)
    const w2Id = await approveWa(wa2)
    const s3 = await seedStockForWholesaler(ta.token, w2Id, 4)
    await confirmPendingInbound(wa1.login.token)
    await confirmPendingInbound(wa2.login.token)
    void s2
    void s3

    storeCode = s1.storeCode
    sku1Id = s1.skuId

    // 撮合配置：主推 sku1 + 置顶 wa1（覆盖写幂等）
    const cfg = await apiPut<never>(
      '/tenant/storefront/featured',
      { mainSkuIds: [sku1Id], pinWaIds: [w1Id] },
      ta.token,
    )
    if (cfg.code !== 0) {
      throw new Error(`[p5a-w5-visual] 撮合配置失败 code=${cfg.code} msg=${cfg.message ?? ''}`)
    }

    // SKU 名称回查（设置页已选项断言用）
    const skus1 = ok(
      await apiGet<Array<{ id: string; name: string; listed: boolean }>>('/tenant/skus', ta.token, {
        wholesalerId: w1Id,
      }),
      'SKU 列表1',
    )
    sku1Name = skus1.find((s) => String(s.id) === sku1Id)?.name ?? ''
    if (!sku1Name) throw new Error('[p5a-w5-visual] SKU 名称回查失败')

    // 公告四态（全部先建草稿，按用例时序发布——登录弹窗取「最新未读公告」，
    // beforeAll 抢先发布会让后续公告抢占弹窗断言，下架也不删通知仍进 TA 站内信）：
    // 1 号 beforeAll 即发布（登录弹窗 @1280 源）；2 号消息中心用例挂载后发布；
    // 3 号 OPS 列表用例内发布+下架（「已下架」行）；4 号弹窗 @375 用例发布
    const annId1 = await createAnnouncement(ops.token, annTitle1, ['TA'])
    annId2 = await createAnnouncement(ops.token, annTitle2, ['TA'])
    annId3 = await createAnnouncement(ops.token, annTitle3, ['TA'])
    annId4 = await createAnnouncement(ops.token, annTitle4, ['TA'])
    ok(await apiPost(`/ops/announcements/${annId1}/publish`, undefined, ops.token), '发布公告1')
  })

  // ---- 关键页 @1280×800 ----

  test('登录公告弹窗 @1280（TA 首次登录即弹可见态）', async ({ page }) => {
    // 场景：OPS 已发布目标 TA 的公告 → TA 登录成功进入工作台即弹（B3 修复后首次登录必弹）
    await page.setViewportSize({ width: 1280, height: 800 })
    await loginAs(page, taPhone, SEED_PWD, /\/ta\/dashboard/)
    const dialog = page.locator('[data-test="login-announcement-dialog"]')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText(annTitle1)
    // 弹窗为 fixed 遮罩：fullPage 会把弹窗画在整页高度上造成伪影，故截视口（真实用户视野）
    await shot(page, 'p5a-w5-login-announcement-dialog-1280', { fullPage: false })
    // 关闭即 markRead（readAt 幂等），为后续消息中心/设置页扫清弹窗干扰
    await dialog.locator('[data-test="announcement-confirm"]').click()
    await expect(dialog).toBeHidden()
  })

  test('TA 消息中心 @1280（分组 Tab + 未读态行）', async ({ page }) => {
    // 注入进入消息中心：此刻公告 1 已读、公告 2 未发布 → 挂载时无弹窗
    await page.setViewportSize({ width: 1280, height: 800 })
    await injectAuthAndGoto(page, ta, 'TA', '/ta/messages')
    const center = page.locator('[data-test="notification-list"]')
    await expect(center).toBeVisible()

    // 挂载后发布公告 2（弹窗只在挂载时检查一次，checkedForSession 不介入）
    ok(await apiPost(`/ops/announcements/${annId2}/publish`, undefined, ops.token), '发布公告2')

    // 公告分组 Tab → 未读行（浅色背景 + 蓝点 + 「标为已读」按钮）
    await center.getByRole('button', { name: '公告' }).click()
    const item = center.locator('.cc-notif__item', { hasText: annTitle2 })
    await expect(item).toBeVisible()
    await expect(item).toHaveClass(/is-unread/)
    await shot(page, 'p5a-w5-ta-messages-unread-1280')
  })

  test('OPS 公告管理 @1280（列表含「已发布」+「已下架」中文态）', async ({ page }) => {
    // 发布公告 3 再下架：下架不删 TA 站内信通知，过早发布会抢占登录弹窗「最新未读」，
    // 故延至本用例发布（列表此时：公告1/2 已发布、公告3 已下架）
    ok(await apiPost(`/ops/announcements/${annId3}/publish`, undefined, ops.token), '发布公告3')
    ok(await apiPost(`/ops/announcements/${annId3}/inactivate`, undefined, ops.token), '下架公告3')
    await page.setViewportSize({ width: 1280, height: 800 })
    await injectAuthAndGoto(page, ops, 'OPS', '/ops/announcements')
    const row1 = page.locator('.ann-table .el-table__row', { hasText: annTitle1 })
    await expect(row1).toBeVisible()
    await expect(row1).toContainText('已发布')
    const row3 = page.locator('.ann-table .el-table__row', { hasText: annTitle3 })
    await expect(row3).toBeVisible()
    await expect(row3).toContainText('已下架')
    await shot(page, 'p5a-w5-ops-announcements-1280')
  })

  test('TA 店铺设置·撮合运营卡 @1280（主推/置顶已选态）', async ({ page }) => {
    // 前序用例仍有未读公告 → 注入即弹，先关闭再继续（避免遮罩盖住设置页）
    await page.setViewportSize({ width: 1280, height: 800 })
    await injectAuthAndGoto(page, ta, 'TA', '/ta/settings')
    await dismissAnnouncementDialogIfShown(page)
    const card = page.locator('[data-test="featured-card"]')
    await expect(card).toBeVisible({ timeout: 15_000 })
    // 主推/置顶分 block 断言（FE-01 教训：SKU 副标题含商户名会串名）
    const mainBlock = card.locator('.featured-block').first()
    const waBlock = card.locator('.featured-block').nth(1)
    await expect(mainBlock.locator('.featured-item', { hasText: sku1Name })).toBeVisible({ timeout: 15_000 })
    await expect(waBlock.locator('.featured-item', { hasText: wa1.wholesalerName })).toBeVisible()
    await expect(mainBlock.locator('.featured-block__count')).toContainText('1/20')
    await expect(waBlock.locator('.featured-block__count')).toContainText('1/5')
    await card.scrollIntoViewIfNeeded()
    await shot(page, 'p5a-w5-ta-settings-featured-1280')
  })

  test('RT 店铺页 @1280（主推/置顶标识 + 前置排序态）', async ({ page }) => {
    // 公开路由进店（无需登录）；置顶商户前置 + 商户内主推商品前置
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto(`/rt/store?code=${storeCode}`)
    await expect(page.locator('.rt-header__title')).toBeVisible()
    const ws = page.locator('.rt-wholesaler')
    await expect(ws).toHaveCount(2)
    await expect(ws.first()).toHaveClass(/rt-wholesaler--pinned/)
    await expect(ws.first().locator('.rt-tag--pinned')).toContainText('置顶')
    await expect(ws.first().locator('.rt-sku').first().locator('.rt-tag--featured')).toContainText('主推')
    await shot(page, 'p5a-w5-rt-store-featured-1280')
  })

  // ---- 降档验证 @375×667（至少两页） ----

  test('RT 店铺页 @375（移动优先降档）', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 })
    await page.goto(`/rt/store?code=${storeCode}`)
    await expect(page.locator('.rt-header__title')).toBeVisible()
    await expect(page.locator('.rt-wholesaler').first().locator('.rt-tag--pinned')).toContainText('置顶')
    await shot(page, 'p5a-w5-rt-store-featured-375')
  })

  test('登录公告弹窗 @375（窄屏降档：弹窗溢出/截断检查）', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 })
    // 公告 4 发布（此时 1/2 已读、4 未读）→ 注入落地工作台即弹
    ok(await apiPost(`/ops/announcements/${annId4}/publish`, undefined, ops.token), '发布公告4')
    await injectAuthAndGoto(page, ta, 'TA', '/ta/dashboard')
    const dialog = page.locator('[data-test="login-announcement-dialog"]')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText(annTitle4)
    await shot(page, 'p5a-w5-login-announcement-dialog-375', { fullPage: false })
    // 关闭即 markRead（readAt 幂等），为消息中心 @375 扫清弹窗干扰
    await dialog.locator('[data-test="announcement-confirm"]').click()
  })

  test('TA 消息中心 @375（后台壳降档：侧栏隐藏、单列流式）', async ({ page }) => {
    // 公告 4 已在上例关闭（readAt），但公告 2 仍残留未读 → 注入即弹，先关闭再继续
    await page.setViewportSize({ width: 375, height: 667 })
    await injectAuthAndGoto(page, ta, 'TA', '/ta/messages')
    await dismissAnnouncementDialogIfShown(page)
    const center = page.locator('[data-test="notification-list"]')
    await expect(center).toBeVisible()
    await expect(center.locator('.cc-notif__item').first()).toBeVisible()
    await shot(page, 'p5a-w5-ta-messages-375')
  })
})
