/**
 * P3 FE-W1 · 入库异常链 E2E（WA 确认 / WA 异议 / TA 仲裁 decide）
 *
 * 造数走 seedSellChain（TA 建仓→OPS 审核→WA 绑定→SKU→WK 代建入库 N 件），
 * BE-W1 后 WK 代建入库单落 PENDING_WA_CONFIRM + 72h deadline，正好是本链起点。
 *
 * 前置：后端 8080（BE-W1 已合 main）+ 前端 dev server（E2E_BASE_URL 指向）。
 * 截图：test-results/screens/*.png（供视觉目检，00-overview §3.5/§3.6）。
 */

import { test, expect, type Page } from '@playwright/test'
import { seedSellChain, type SellSeed } from './helpers/sell'

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'
const SCREEN_DIR = 'test-results/screens'

/** 1×1 透明 PNG（后端魔数校验通过） */
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

/** 注入登录态（pinia-persist localStorage）并直达目标页 */
async function enterWithAuth(
  page: Page,
  login: SellSeed['waLogin'],
  primaryRole: 'WA' | 'TA',
  path: string,
  expectTitle: string,
): Promise<void> {
  const roleEntry = login.roles.find((r) => r.role === primaryRole) ?? login.roles[0]
  const authState = {
    token: login.token,
    userId: login.userId,
    primaryRole,
    roles: login.roles,
    primaryRouter: path,
    expireAt: null,
    tenantInfo: roleEntry?.tenantId
      ? { tenantId: roleEntry.tenantId, tenantName: primaryRole === 'TA' ? '测试仓' : '我的商户' }
      : null,
  }
  await page.goto('/login')
  await page.evaluate((s) => {
    localStorage.clear()
    localStorage.setItem('cangchu-admin-auth', JSON.stringify(s))
  }, authState)
  await page.goto(path)
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.page-head__title')).toContainText(expectTitle, { timeout: 15_000 })
}

const enterWaInbound = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, seed.waLogin, 'WA', '/wa/inbound', '入库确认')

const enterTaApprovals = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, seed.taLogin, 'TA', '/ta/approvals', '审批中心')

/** API 旁路：WA 队列取首单 id（造 TA 仲裁用例时用） */
async function apiFirstPendingInboundId(waToken: string): Promise<string> {
  const res = await fetch(
    `${API}/api/v1/wholesaler/inbound-requests?status=PENDING_WA_CONFIRM&page=1&size=10`,
    { headers: { Authorization: waToken } },
  )
  const text = await res.text()
  // 雪花 id 防精度丢失：直接从原文正则抽首个 "id":"?(\d+)"?
  const m = text.match(/"id":\s*"?(\d{10,})"?/)
  if (!m) throw new Error(`WA 队列为空或解析失败：${text.slice(0, 200)}`)
  return m[1]
}

/** API 旁路：WA 异议（返回 YY- 仲裁单号） */
async function apiDispute(waToken: string, inboundId: string, reason: string): Promise<string> {
  const res = await fetch(`${API}/api/v1/wholesaler/inbound-requests/${inboundId}/dispute`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: waToken },
    body: JSON.stringify({ reason }),
  })
  const text = await res.text()
  const doc = text.match(/"arbitrationDocNo":\s*"(YY-[^"]+)"/)
  if (!doc) throw new Error(`异议失败：${text.slice(0, 300)}`)
  return doc[1]
}

test.describe('P3 入库异常链', () => {
  test('INB-01 WA 确认链：队列/倒计时/来源/铃铛 → 确认转已确认', async ({ page }) => {
    const seed = await seedSellChain(30)
    await enterWaInbound(page, seed)

    // 09 §6.1 全局口径文案在队列头部
    await expect(page.locator('[data-test="policy-copy"]')).toContainText('差额定责')

    // 待确认队列行：来源=仓库代建、状态=待确认、倒计时 hh:mm:ss（72h 内）
    const row = page.locator('[data-test="inbound-table"] .el-table__row').first()
    await expect(row).toContainText('WK-')
    await expect(row).toContainText('仓库代建')
    await expect(row).toContainText('待确认')
    await expect(row).toContainText(String(30))
    const countdown = row.locator('[data-test="countdown"]')
    await expect(countdown).toHaveText(/^\d{2}:\d{2}:\d{2}$/)
    const hours = Number((await countdown.innerText()).split(':')[0])
    expect(hours).toBeLessThanOrEqual(72)
    expect(hours).toBeGreaterThanOrEqual(70) // 刚登记，倒计时应在 70-72h 区间

    // 站内信铃铛：入口可用、抽屉可开（⚠️ 契约偏差：SELF_OPERATED 商户 owner_user_id=TA，
    // 后端把「通知归属 WA」发给了 TA 账号，绑定 WA 账号收不到 → WA 侧不断言角标/条目，
    // 收件与标记已读链路在 INB-03 TA 侧全量断言）
    await page.locator('[data-test="notification-bell"] button').click()
    await expect(page.locator('.cc-bell-drawer__toolbar')).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/01-wa-queue-with-bell.png`, fullPage: true })
    await page.keyboard.press('Escape') // 关抽屉

    // 确认 → 二次弹窗 → 成功 → 「全部」页签中该单转已确认
    await row.locator('[data-test="confirm-btn"]').click()
    await expect(page.locator('.el-message-box')).toContainText('确认代建入库')
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success')).toContainText('已确认', { timeout: 15_000 })

    await page.locator('[data-test="inbound-tabs"] .el-tabs__item', { hasText: '全部' }).click()
    const allRow = page.locator('[data-test="inbound-table"] .el-table__row').first()
    await expect(allRow).toContainText('已确认', { timeout: 15_000 })
    // 手动确认不应带 72h 自动确认标记
    await expect(allRow.locator('[data-test="auto-accepted-tag"]')).toHaveCount(0)
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/02-wa-confirmed.png`, fullPage: true })
  })

  test('INB-02 WA 异议链：预设理由+附件上传 → 冲销回显 YY- 单号', async ({ page }) => {
    const seed = await seedSellChain(30)
    await enterWaInbound(page, seed)

    const row = page.locator('[data-test="inbound-table"] .el-table__row').first()
    await expect(row).toContainText('待确认')
    await row.locator('[data-test="dispute-btn"]').click()

    const dialog = page.locator('[data-test="dispute-dialog"]')
    await expect(dialog).toBeVisible()
    // 弹窗内必须展示口径文案（09 §6.1）
    await expect(dialog).toContainText('异议仅覆盖仍在库部分')
    await expect(dialog.locator('[data-test="dispute-facts"]')).toContainText('30 件')
    // M3 stock-preview 三数字（未售出：在库 30 / 预计冲销 30 / 差额 0）
    await expect(dialog.locator('[data-test="preview-onhand"]')).toContainText('30 件', {
      timeout: 15_000,
    })
    await expect(dialog.locator('[data-test="preview-reversal"]')).toContainText('30 件')
    await expect(dialog.locator('[data-test="preview-shortfall"]')).toContainText('0 件')

    // 预设理由 + 补充说明
    await dialog.locator('[data-test="dispute-preset"] .el-radio', { hasText: '数量不符' }).click()
    await dialog.locator('textarea').fill('实收 0 件，E2E 异议用例')

    // 附件上传（POST /files → 缩略图回显）
    await dialog
      .locator('[data-test="attachment-input"]')
      .setInputFiles({ name: 'evidence.png', mimeType: 'image/png', buffer: PNG_1PX })
    await expect(dialog.locator('.cc-attach__item img')).toHaveCount(1, { timeout: 15_000 })
    await expect(dialog.locator('.cc-attach__hint')).toContainText('1/5')
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/03-dispute-form.png`, fullPage: true })

    // 提交 → 结果回显：登记 30 / 冲销 30（未售出）/ 差额 0 / YY- 仲裁单号
    await dialog.locator('[data-test="dispute-submit"]').click()
    const result = page.locator('[data-test="dispute-result-dialog"]')
    await expect(result).toBeVisible({ timeout: 15_000 })
    await expect(result).toContainText('30 件')
    await expect(result.locator('[data-test="result-reversed"]')).toHaveText('30 件')
    await expect(result.locator('[data-test="result-shortfall"]')).toHaveText(/^\s*0 件\s*$/)
    await expect(result.locator('[data-test="result-arbitration-doc"]')).toContainText('YY-')
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/04-dispute-result.png`, fullPage: true })

    // 关闭 → 全部页签中该单转争议中
    await result.getByRole('button', { name: '知道了' }).click()
    await page.locator('[data-test="inbound-tabs"] .el-tabs__item', { hasText: '全部' }).click()
    await expect(page.locator('[data-test="inbound-table"] .el-table__row').first()).toContainText(
      '争议中',
      { timeout: 15_000 },
    )
  })

  test('INB-03 TA 仲裁 decide：通过·恢复流水 → 已裁决', async ({ page }) => {
    const seed = await seedSellChain(30)
    // API 旁路造异议（UI 异议链已在 INB-02 覆盖）
    const inboundId = await apiFirstPendingInboundId(seed.waLogin.token)
    const arbDocNo = await apiDispute(seed.waLogin.token, inboundId, '[未到货] E2E 仲裁用例')

    await enterTaApprovals(page, seed)

    // 站内信（TA 侧收 DISPUTE_CREATED）：角标 ≥1 → 开抽屉 → 有条目 → 点击标记已读
    const badge = page.locator('[data-test="notification-badge"] .el-badge__content')
    await expect(badge).toBeVisible({ timeout: 15_000 })
    await page.locator('[data-test="notification-bell"] button').click()
    const noticeItem = page.locator('[data-test="notification-item"]').first()
    await expect(noticeItem).toBeVisible()
    await expect(page.locator('[data-test="notification-list"]')).toContainText(/异议|仲裁|入库/)
    await noticeItem.click() // 标记已读（幂等）
    await page.keyboard.press('Escape')

    // 待仲裁列表：YY- 单号 + 理由 + 冲销/差额（30/0）+ 无超时标（刚发起）
    const row = page.locator('[data-test="arbitration-table"] .el-table__row', {
      hasText: arbDocNo,
    })
    await expect(row).toBeVisible({ timeout: 15_000 })
    await expect(row).toContainText('未到货')
    await expect(row.locator('[data-test="overdue-tag"]')).toHaveCount(0)

    // 打开裁决弹窗（09 §4.1 线框）
    await row.locator('[data-test="decide-btn"]').click()
    const dialog = page.locator('[data-test="decide-dialog"]')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('WK-') // 关联入库单
    await expect(dialog).toContainText('30 件') // 已冲销

    // 选「通过·恢复流水」：差额=0 → 不出现定责单选
    await dialog.locator('[data-test="conclusion-radio"] .el-radio', { hasText: '通过' }).click()
    await expect(dialog.locator('[data-test="liability-radio"]')).toHaveCount(0)
    await expect(dialog).toContainText('库存 +30') // 联动提示
    await dialog.locator('textarea').fill('货已核实到仓，异议不成立，恢复流水。')
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/05-ta-decide-dialog.png`, fullPage: true })

    await dialog.locator('[data-test="decide-submit"]').click()
    await expect(page.locator('.el-message--success')).toContainText('已裁决', { timeout: 15_000 })

    // 已裁决页签：结论=通过·恢复流水；详情弹窗可回看备注
    await page.locator('[data-test="arbitration-tabs"] .el-tabs__item', { hasText: '已裁决' }).click()
    const decidedRow = page.locator('[data-test="arbitration-table"] .el-table__row', {
      hasText: arbDocNo,
    })
    await expect(decidedRow).toBeVisible({ timeout: 15_000 })
    await expect(decidedRow).toContainText('通过 · 恢复流水')
    await decidedRow.locator('[data-test="detail-btn"]').click()
    const detail = page.locator('[data-test="arbitration-detail-dialog"]')
    await expect(detail.locator('[data-test="detail-conclusion"]')).toContainText('通过 · 恢复流水')
    await expect(detail).toContainText('货已核实到仓')
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/06-ta-decided-detail.png`, fullPage: true })
  })
})
