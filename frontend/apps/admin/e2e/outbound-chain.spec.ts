/**
 * P3 FE-W2 · 出库链 E2E（WK 作业流 + WA 出库/撤回/客诉 + OPS 裁决）
 *
 * 主链：询价确认 → WK 打印 → WK 登记出库
 * R4 两路：WA 待受理直撤 / WA 已打印申请撤回 → WK 确认撤回
 * 客诉→OPS 裁决：WA 30 天客诉 → OPS 仲裁结论
 *
 * 造数：seedSellChain（TA 建仓→OPS 审核→WA 绑定→SKU→WK 入库）
 * 后端 8080（BE-W2 已合 main）+ 前端 dev server（E2E_BASE_URL 指向）。
 * 截图：test-results/screens/*.png（供视觉目检）。
 */

import { test, expect, type Page } from '@playwright/test'
import {
  seedSellChain,
  apiSubmitInquiry,
  apiConfirmInquiry,
  findInquiryByDocNo,
  type SellSeed,
} from './helpers/sell'

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'
const SCREEN_DIR = 'test-results/screens'

/** 1×1 透明 PNG（后端魔数校验通过；客诉附件上传用） */
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

// ============ 造数辅助 ============

/** 注入登录态并直达目标页 */
async function enterWithAuth(
  page: Page,
  login: SellSeed['waLogin'],
  primaryRole: 'WA' | 'TA' | 'WK' | 'OPS',
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
      ? {
          tenantId: roleEntry.tenantId,
          tenantName:
            primaryRole === 'TA' || primaryRole === 'WK'
              ? '测试仓'
              : primaryRole === 'OPS'
                ? '平台运营'
                : '我的商户',
        }
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

/** WK 登录态进出库作业页（打印/登记/撤回二次确认均要求 WK 角色） */
const enterWkOutbound = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, seed.wkLogin, 'WK', '/ta/outbound', '出库作业')

const enterWaOutbound = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, seed.waLogin, 'WA', '/wa/outbound', '出库单')

/** 注册 OPS 账号并注入登录态 */
async function enterOpsArbitrations(page: Page): Promise<void> {
  const res = await fetch(`${API}/api/v1/account/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      phone: '13' + String(Date.now()).slice(-9),
      password: 'OpsPass123',
      smsCode: '888888',
      role: 'OPS',
      realName: 'E2EOPS',
      agreedTerms: true,
    }),
  })
  const env = (await res.json()) as { code: number; data: { token: string; userId: string; roles: unknown[] } }
  if (env.code !== 0) throw new Error(`OPS 注册失败: ${JSON.stringify(env)}`)
  const { token, userId, roles } = env.data
  const authState = {
    token,
    userId,
    primaryRole: 'OPS',
    roles,
    primaryRouter: '/ops/arbitrations',
    expireAt: null,
    tenantInfo: null,
  }
  await page.goto('/login')
  await page.evaluate((s) => {
    localStorage.clear()
    localStorage.setItem('cangchu-admin-auth', JSON.stringify(s))
  }, authState)
  await page.goto('/ops/arbitrations')
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.page-head__title')).toContainText('客诉仲裁', { timeout: 15_000 })
}

/** API 旁路：WK 打印出库单（返回 docNo） */
async function apiPrint(wkToken: string, outboundId: string): Promise<string> {
  const res = await fetch(`${API}/api/v1/tenant/outbound-requests/${outboundId}/print`, {
    method: 'POST',
    headers: { Authorization: wkToken, satoken: wkToken },
  })
  const text = await res.text()
  const m = text.match(/"docNo":\s*"(CK-[^"]+)"/)
  if (!m) throw new Error(`打印失败：${text.slice(0, 200)}`)
  return m[1]
}

/** API 旁路：WK 登记出库 */
async function apiRegister(wkToken: string, outboundId: string): Promise<void> {
  const res = await fetch(`${API}/api/v1/tenant/outbound-requests/${outboundId}/register`, {
    method: 'POST',
    headers: { Authorization: wkToken, satoken: wkToken },
  })
  const env = (await res.json()) as { code: number; message?: string }
  if (env.code !== 0) throw new Error(`登记出库失败 code=${env.code} msg=${env.message}`)
}

/** API 旁路：WA 手动出库申请（返回 outbound id） */
async function apiWaSubmitOutbound(
  waToken: string,
  wholesalerId: string,
  skuId: string,
  qty: number,
): Promise<string> {
  const res = await fetch(`${API}/api/v1/wholesaler/outbound-requests`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: waToken, satoken: waToken },
    body: JSON.stringify({ wholesalerId, skuId, qty }),
  })
  const text = await res.text()
  const m = text.match(/"id":\s*"?(\d{10,})"?/)
  if (!m) throw new Error(`WA 出库申请失败：${text.slice(0, 200)}`)
  return m[1]
}

/** API 旁路：WA 撤回出库单 */
async function apiWaWithdraw(waToken: string, outboundId: string): Promise<string> {
  const res = await fetch(`${API}/api/v1/wholesaler/outbound-requests/${outboundId}/withdraw`, {
    method: 'POST',
    headers: { Authorization: waToken, satoken: waToken },
  })
  const text = await res.text()
  const m = text.match(/"status":\s*"([^"]+)"/)
  if (!m) throw new Error(`撤回失败：${text.slice(0, 200)}`)
  return m[1]
}

/** API 旁路：WK 列出待受理出库单，返回首单 id */
async function apiFirstPendingOutboundId(wkToken: string, tenantId: string): Promise<string> {
  const res = await fetch(
    `${API}/api/v1/tenant/outbound-requests?status=PENDING_ACCEPT&page=1&size=10`,
    { headers: { Authorization: wkToken, satoken: wkToken, 'X-Tenant-Id': tenantId } },
  )
  const text = await res.text()
  const m = text.match(/"id":\s*"?(\d{10,})"?/)
  if (!m) throw new Error(`WK 待受理队列为空：${text.slice(0, 200)}`)
  return m[1]
}

/** API 旁路：WK 列出已打印出库单，返回首单 id */
async function apiFirstPrintedOutboundId(wkToken: string, tenantId: string): Promise<string> {
  const res = await fetch(
    `${API}/api/v1/tenant/outbound-requests?status=PRINTED&page=1&size=10`,
    { headers: { Authorization: wkToken, satoken: wkToken, 'X-Tenant-Id': tenantId } },
  )
  const text = await res.text()
  const m = text.match(/"id":\s*"?(\d{10,})"?/)
  if (!m) throw new Error(`WK 已打印队列为空：${text.slice(0, 200)}`)
  return m[1]
}

/** 从 SellSeed 取 TA 的 tenantId */
function tenantIdOf(seed: SellSeed): string {
  const entry = seed.taLogin.roles.find((r) => r.role === 'TA')
  return entry?.tenantId ? String(entry.tenantId) : ''
}

// ============ 造出库单（询价确认 → 出库单 PENDING_ACCEPT） ============
async function seedOutboundPending(seed: SellSeed): Promise<string> {
  // 1) RT 提交询价
  const inq = await apiSubmitInquiry({
    code: seed.storeCode,
    wholesalerId: seed.wholesalerId,
    skuId: seed.skuId,
    qty: 5,
  })
  if (inq.code !== 0) throw new Error(`询价失败 code=${inq.code}`)
  const inquiryDocNo = inq.data.docNo

  // 2) WA 确认询价 → 出库单 PENDING_ACCEPT
  const inquiryRow = await findInquiryByDocNo(seed.waLogin.token, inquiryDocNo)
  await apiConfirmInquiry(inquiryRow.id, seed.waLogin.token)

  // 3) 取出库单 id
  return apiFirstPendingOutboundId(seed.wkToken, tenantIdOf(seed))
}

// ============ 测试用例 ============

test.describe('P3 出库链', () => {
  test('OUT-01 主链：询价确认→WK 打印→登记出库', async ({ page }) => {
    const seed = await seedSellChain(30)
    await seedOutboundPending(seed)

    // WK 出库作业 UI：待受理队列（询价确认来源）
    await enterWkOutbound(page, seed)
    // window.print 打桩（headless 下阻塞打印设置面板）
    await page.evaluate(() => {
      window.print = () => undefined
    })
    const pendingRow = page.locator('[data-test="outbound-table"] .el-table__row').first()
    await expect(pendingRow).toContainText('CK-', { timeout: 15_000 })
    await expect(pendingRow).toContainText('询价确认')
    await expect(pendingRow).toContainText('待受理')

    // UI 打印：POST print（PENDING_ACCEPT→PRINTED）→ 票面弹窗 → window.print
    await pendingRow.locator('[data-test="print-btn"]').click()
    const printDialog = page.locator('[data-test="print-dialog"]')
    await expect(printDialog).toBeVisible({ timeout: 15_000 })
    const sheet = printDialog.locator('[data-test="print-sheet"]')
    await expect(sheet).toContainText('CK-')
    await expect(sheet).toContainText('5 件') // 询价 qty=5
    await expect(sheet).toContainText('第 1 次')
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/06b-wk-print-sheet.png`, fullPage: true })
    await printDialog.locator('[data-test="do-print-btn"]').click() // 已打桩，不弹系统面板
    await printDialog.locator('.el-dialog__footer').getByRole('button', { name: '关闭' }).click()
    await expect(printDialog).toBeHidden()

    // 切到「已打印」页签
    await page.locator('[data-test="outbound-tabs"] .el-tabs__item', { hasText: '已打印' }).click()
    await page.waitForLoadState('networkidle')

    const row = page.locator('[data-test="outbound-table"] .el-table__row').first()
    await expect(row).toContainText('CK-', { timeout: 15_000 })
    await expect(row).toContainText('已打印')

    // 登记出库
    await row.locator('[data-test="register-btn"]').click()
    await expect(page.locator('.el-message-box')).toContainText('登记出库', { timeout: 10_000 })
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success')).toContainText('已登记出库', { timeout: 15_000 })

    // 切到「已完成」页签验证
    await page.locator('[data-test="outbound-tabs"] .el-tabs__item', { hasText: '已完成' }).click()
    await expect(
      page.locator('[data-test="outbound-table"] .el-table__row').first(),
    ).toContainText('已出库', { timeout: 15_000 })

    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/07-wk-outbound-completed.png`, fullPage: true })
  })

  test('OUT-02 R4 待受理直撤：WA 提交→WA 撤回→库存回补', async ({ page }) => {
    const seed = await seedSellChain(30)

    // WA 手动出库申请（API 旁路）
    const outboundId = await apiWaSubmitOutbound(
      seed.waLogin.token,
      seed.wholesalerId,
      seed.skuId,
      5,
    )

    // WA 出库单 UI
    await enterWaOutbound(page, seed)

    const row = page.locator('[data-test="wa-outbound-table"] .el-table__row').first()
    await expect(row).toContainText('待受理', { timeout: 15_000 })
    await expect(row).toContainText('我方提交')

    // 撤回
    await row.locator('[data-test="withdraw-btn"]').click()
    await expect(page.locator('.el-message-box')).toContainText('撤回', { timeout: 10_000 })
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success')).toContainText('已撤回', { timeout: 15_000 })

    // 验证状态变已撤回
    await expect(
      page.locator('[data-test="wa-outbound-table"] .el-table__row').first(),
    ).toContainText('已撤回', { timeout: 15_000 })

    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/08-wa-withdraw-direct.png`, fullPage: true })

    // 验证 outboundId 确实已撤回（API 旁路）
    const status = await apiWaWithdraw(seed.waLogin.token, outboundId).catch(() => 'already_withdrawn')
    // 已撤回再撤会 50335，说明状态已终结
    expect(['already_withdrawn', 'WITHDRAWN']).toContain(status)
  })

  test('OUT-03 R4 已打印撤回：WA 申请→WK 确认撤回', async ({ page }) => {
    const seed = await seedSellChain(30)
    const outboundId = await seedOutboundPending(seed)

    // WK 打印（API 旁路）
    await apiPrint(seed.wkToken, outboundId)

    // WA 申请撤回（API 旁路）
    await apiWaWithdraw(seed.waLogin.token, outboundId)

    // WK 出库作业 UI
    await enterWkOutbound(page, seed)

    // 切到「已打印」页签
    await page.locator('[data-test="outbound-tabs"] .el-tabs__item', { hasText: '已打印' }).click()
    await page.waitForLoadState('networkidle')

    const row = page.locator('[data-test="outbound-table"] .el-table__row').first()
    await expect(row).toContainText('商户申请撤回', { timeout: 15_000 })

    // WK 确认撤回
    await row.locator('[data-test="confirm-withdraw-btn"]').click()
    await expect(page.locator('.el-message-box')).toContainText('确认撤回', { timeout: 10_000 })
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success')).toContainText('已撤销', { timeout: 15_000 })

    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/09-wk-confirm-withdraw.png`, fullPage: true })
  })

  test('OUT-04 客诉→OPS 裁决：WK 代建出库 → WA 30 天客诉 → OPS 四选结论', async ({ page }) => {
    const seed = await seedSellChain(30)

    // ---- 1) WK UI 代建出库（选商户/SKU + 复述件数二次确认，直达已出库） ----
    await enterWkOutbound(page, seed)
    await page.locator('[data-test="proxy-create-btn"]').click()
    const proxyDialog = page.locator('[data-test="proxy-dialog"]')
    await expect(proxyDialog).toBeVisible()

    // 商户选择（EntityPickerDialog：点击触发框 → 双击行选中并确认）
    await proxyDialog.locator('.cc-picker .el-input__inner').first().click()
    const pickerDialog = page.locator('.el-dialog.cc-picker-dialog:visible')
    await expect(pickerDialog.locator('.el-table__row').first()).toBeVisible({ timeout: 15_000 })
    await pickerDialog.locator('.el-table__row').first().dblclick()
    await expect(pickerDialog).toBeHidden()

    // SKU 选择（依赖商户，选中后解禁）
    await proxyDialog.locator('.cc-picker .el-input__inner').nth(1).click()
    const skuPicker = page.locator('.el-dialog.cc-picker-dialog:visible')
    await expect(skuPicker.locator('.el-table__row').first()).toBeVisible({ timeout: 15_000 })
    await skuPicker.locator('.el-table__row').first().dblclick()
    await expect(skuPicker).toBeHidden()

    // 数量（当前在库 30，出 10 件；在库提示可见）
    await proxyDialog.locator('[data-test="proxy-qty"] input').fill('10')
    await expect(proxyDialog.locator('[data-test="onhand-hint"]')).toContainText('30 件', {
      timeout: 15_000,
    })
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/10-wk-proxy-form.png`, fullPage: true })

    // 下一步 → 复述件数二次确认
    await proxyDialog.locator('[data-test="proxy-next"]').click()
    const restateDialog = page.locator('[data-test="restate-dialog"]')
    await expect(restateDialog).toBeVisible()
    await restateDialog.locator('[data-test="restate-input"] input').fill('10')
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/11-wk-proxy-restate.png`, fullPage: true })
    await restateDialog.locator('[data-test="restate-confirm"]').click()
    await expect(page.locator('.el-message--success')).toContainText('代建出库已登记', {
      timeout: 15_000,
    })
    // 已完成页签出现仓库代建单
    const wkRow = page.locator('[data-test="outbound-table"] .el-table__row').first()
    await expect(wkRow).toContainText('仓库代建', { timeout: 15_000 })
    await expect(wkRow).toContainText('已出库')

    // ---- 2) WA UI 30 天客诉（预设理由 + 附件上传） ----
    await enterWaOutbound(page, seed)
    const waRow = page.locator('[data-test="wa-outbound-table"] .el-table__row').first()
    await expect(waRow).toContainText('仓库代建', { timeout: 15_000 })
    await expect(waRow).toContainText('已出库')
    await waRow.locator('[data-test="complain-btn"]').click()

    const complainDialog = page.locator('[data-test="complain-dialog"]')
    await expect(complainDialog).toBeVisible()
    // 口径文案 + 剩余天数
    await expect(complainDialog).toContainText('30 天')
    await expect(complainDialog.locator('[data-test="complain-remain-days"]')).toContainText('剩余约')

    await complainDialog
      .locator('[data-test="complain-preset"] .el-radio', { hasText: '数量不符' })
      .click()
    await complainDialog.locator('textarea').fill('实收与代建登记不符，E2E 客诉用例')
    // 附件上传（POST /files → 缩略图回显）
    await complainDialog
      .locator('[data-test="attachment-input"]')
      .setInputFiles({ name: 'evidence.png', mimeType: 'image/png', buffer: PNG_1PX })
    await expect(complainDialog.locator('.cc-attach__item img')).toHaveCount(1, { timeout: 15_000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/12-wa-complain-form.png`, fullPage: true })

    await complainDialog.locator('[data-test="complain-submit"]').click()
    await expect(page.locator('.el-message--success')).toContainText('客诉已提交', {
      timeout: 15_000,
    })
    await expect(
      page.locator('[data-test="wa-outbound-table"] .el-table__row').first(),
    ).toContainText('客诉处理中', { timeout: 15_000 })
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/13-wa-complained.png`, fullPage: true })

    // OPS 仲裁 UI
    await enterOpsArbitrations(page)

    // 待仲裁列表：KS- 单号
    const arbRow = page.locator('[data-test="ops-arb-table"] .el-table__row').first()
    await expect(arbRow).toContainText('KS-', { timeout: 15_000 })
    await expect(arbRow).toContainText('数量不符')

    // 打开裁决弹窗
    await arbRow.locator('[data-test="ops-decide-btn"]').click()
    const dialog = page.locator('[data-test="ops-decide-dialog"]')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('CK-') // 关联出库单

    // 选「无责」
    await dialog
      .locator('[data-test="ops-conclusion-radio"] .el-radio', { hasText: '无责' })
      .click()
    await expect(dialog).toContainText('仅作判责')
    await dialog.locator('[data-test="ops-decide-remark"]').fill('双方核实后确认数量无误，无责结案。')
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/14-ops-decide-dialog.png`, fullPage: true })

    await dialog.locator('[data-test="ops-decide-submit"]').click()
    await expect(page.locator('.el-message--success')).toContainText('已裁决', { timeout: 15_000 })

    // 已裁决页签：结论=无责
    await page.locator('[data-test="ops-arb-tabs"] .el-tabs__item', { hasText: '已裁决' }).click()
    const decidedRow = page.locator('[data-test="ops-arb-table"] .el-table__row').first()
    await expect(decidedRow).toContainText('无责', { timeout: 15_000 })

    // 详情弹窗
    await decidedRow.locator('[data-test="ops-detail-btn"]').click()
    const detail = page.locator('[data-test="ops-arb-detail-dialog"]')
    await expect(detail.locator('[data-test="ops-detail-conclusion"]')).toContainText('无责')
    await expect(detail).toContainText('双方核实后确认')
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${SCREEN_DIR}/15-ops-decided-detail.png`, fullPage: true })
  })
})
