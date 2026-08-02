/**
 * P3b T1-FE · 正向入库申请链 E2E（WA 提交 → WK 受理/驳回/登记 → R3 纠错 → TA 审批）
 *
 * 三链（13 §6 T1-FE 关卡）：
 *  FWD-01 主链：WA UI 多行表单提交 → WK 受理 → 登记（5% 边界：>5% 置灰 / ≤5% 黄条+备注）→ 双端「已入库」
 *  FWD-02 驳回链：同批 2 单（同批标识）→ WA 撤回其一 → WK 四选原因+备注+附件驳回 → WA 复制重建预填
 *  FWD-03 纠错链：API 造已入库单 → WK 发起纠错（封顶预览）→ TA 审批通过 → 库存联动断言
 *
 * 造数走 seedSellChain（TA 建仓→OPS 审核→WA 绑定→SKU→WK 代建入库 N 件）；
 * 正向链单据由 WA 提交产生（source=WA_SUBMIT），与代建链队列互不干扰。
 * 前置：后端 8080（T1-BE 已合 main）+ 前端 dev server（E2E_BASE_URL 指向）。
 * 截图：test-results/screens/p3b-*.png（供 Team Lead 视觉目检，00-overview §3.5/§3.6）。
 */

import { test, expect, type Page } from '@playwright/test'
import { seedSellChain, type SellSeed } from './helpers/sell'

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'
const SCREEN_DIR = 'test-results/screens'

/** 1×1 透明 PNG（后端魔数校验通过；驳回举证附件上传用） */
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

// ============ 造数辅助 ============

/** 注入登录态并直达目标页（复刻 outbound-chain.spec 先例） */
async function enterWithAuth(
  page: Page,
  login: SellSeed['waLogin'],
  primaryRole: 'WA' | 'TA' | 'WK',
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
          tenantName: primaryRole === 'WA' ? '我的商户' : '测试仓',
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

/**
 * WA 登录态注入：seedSellChain 的 waLogin.roles 是注册时快照（仅 RT，早于 TA 绑定 WA），
 * 前端 myWholesalerId 从 roles 推导 → 手动补 WA 条目（后端归属校验仍以服务端推导为准）。
 */
const waLoginWithRole = (seed: SellSeed): SellSeed['waLogin'] => ({
  ...seed.waLogin,
  roles: [
    ...seed.waLogin.roles,
    { role: 'WA', wholesalerId: seed.wholesalerId, priority: 40 } as SellSeed['waLogin']['roles'][number],
  ],
})

const enterWaInbound = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, waLoginWithRole(seed), 'WA', '/wa/inbound', '入库确认')

const enterWkInbound = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, seed.wkLogin, 'WK', '/ta/inbound', '入库')

const enterTaApprovals = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, seed.taLogin, 'TA', '/ta/approvals', '审批中心')

/** WA 端切到「我的入库申请」视图 */
async function switchToMineView(page: Page): Promise<void> {
  await page
    .locator('[data-test="view-switch"] .el-radio-button', { hasText: '我的入库申请' })
    .click()
  await expect(page.locator('[data-test="mine-policy-copy"]')).toContainText(
    '提交与受理不影响库存与计费',
  )
}

interface ApiHeaders {
  [k: string]: string
}
const authHeaders = (token: string): ApiHeaders => ({
  'Content-Type': 'application/json',
  Authorization: token,
  satoken: token,
})

/** API 旁路：WA 提交入库申请（多行拆单），返回新单 id 列表（保序） */
async function apiWaSubmit(
  waToken: string,
  wholesalerId: string,
  items: Array<{ skuId: string; qty: number; palletQty?: number; remark?: string }>,
): Promise<Array<{ id: string; docNo: string }>> {
  const res = await fetch(`${API}/api/v1/wholesaler/inbound-requests`, {
    method: 'POST',
    headers: authHeaders(waToken),
    body: JSON.stringify({ wholesalerId, items }),
  })
  const env = (await res.json()) as {
    code: number
    message?: string
    data: Array<{ id: string; docNo: string }>
  }
  if (env.code !== 0) throw new Error(`WA 提交失败 code=${env.code} msg=${env.message ?? ''}`)
  return env.data
}

/** API 旁路：WK 受理 + 登记（纠错链造数用） */
async function apiWkAcceptAndRegister(
  wkToken: string,
  id: string,
  actualQty: number,
): Promise<void> {
  const accept = await fetch(`${API}/api/v1/tenant/inbound/${id}/accept`, {
    method: 'POST',
    headers: authHeaders(wkToken),
  })
  const acceptEnv = (await accept.json()) as { code: number; message?: string }
  if (acceptEnv.code !== 0) throw new Error(`受理失败 code=${acceptEnv.code}`)
  const reg = await fetch(`${API}/api/v1/tenant/inbound/${id}/register`, {
    method: 'POST',
    headers: authHeaders(wkToken),
    body: JSON.stringify({ actualQty }),
  })
  const regEnv = (await reg.json()) as { code: number; message?: string }
  if (regEnv.code !== 0) throw new Error(`登记失败 code=${regEnv.code} msg=${regEnv.message ?? ''}`)
}

/** API 旁路：查该商户 SKU 在库件数 */
async function apiOnhand(wkToken: string, wholesalerId: string, skuId: string): Promise<number> {
  const res = await fetch(
    `${API}/api/v1/tenant/inventories?wholesalerId=${wholesalerId}&skuId=${skuId}`,
    { headers: authHeaders(wkToken) },
  )
  const env = (await res.json()) as { code: number; data: Array<{ qty: number }> }
  if (env.code !== 0) throw new Error(`库存查询失败 code=${env.code}`)
  return env.data.length ? Number(env.data[0].qty) : 0
}

test.describe('P3b 正向入库申请链', () => {
  test('FWD-01 主链：WA 表单提交 → WK 受理 → 5% 边界登记 → 双端已入库', async ({ page }) => {
    const seed = await seedSellChain(30)

    // ---------- WA 提交（UI 多行表单） ----------
    await enterWaInbound(page, seed)
    await switchToMineView(page)
    await page.locator('[data-test="new-submit-btn"]').click()
    const submitDialog = page.locator('[data-test="inbound-submit-dialog"]')
    await expect(submitDialog).toBeVisible()

    // 商品行 0：EntityPickerDialog 选 SKU（双击行 = 选中并确认）
    await submitDialog.locator('.cc-picker__trigger').click()
    const pickerDialog = page.locator('.cc-picker-dialog').last()
    await expect(pickerDialog).toBeVisible()
    await pickerDialog
      .locator('.el-table__row', { hasText: seed.skuName })
      .first()
      .dblclick()
    await expect(pickerDialog).toBeHidden()

    await submitDialog.locator('[data-test="row-qty"] input').fill('20')
    await submitDialog.locator('[data-test="row-qty"] input').press('Enter')
    await submitDialog.locator('[data-test="row-pallet"] input').fill('1')
    await submitDialog.locator('[data-test="row-pallet"] input').press('Enter')
    await expect(submitDialog.locator('[data-test="row-error"]')).toHaveCount(0)
    await expect(submitDialog.locator('[data-test="submit-btn"]')).toBeEnabled()
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-01-wa-submit-form.png`, fullPage: true })

    await submitDialog.locator('[data-test="submit-btn"]').click()
    // 拆单确认弹窗（线框 A）
    await expect(page.locator('.el-message-box')).toContainText('生成 1 张入库申请单')
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已提交 1 张入库申请', {
      timeout: 15_000,
    })

    // 我的申请列表：待受理 Tab 出现 WK- 单据
    const mineRow = page.locator('[data-test="mine-table"] .el-table__row').first()
    await expect(mineRow).toContainText('WK-', { timeout: 15_000 })
    await expect(mineRow).toContainText('待受理')
    await expect(mineRow).toContainText('20')
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-02-wa-submitted-list.png`, fullPage: true })

    // ---------- WK 受理（UI） ----------
    await enterWkInbound(page, seed)
    const wbRow = page.locator('[data-test="wb-table"] .el-table__row').first()
    await expect(wbRow).toContainText('WK-', { timeout: 15_000 })
    await expect(wbRow).toContainText(seed.skuName)
    await expect(wbRow).toContainText('20')
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-03-wk-pending-queue.png`, fullPage: true })

    await wbRow.locator('[data-test="accept-btn"]').click()
    await expect(page.locator('.el-message-box')).toContainText('受理后批发商将不可撤回')
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已受理', { timeout: 15_000 })

    // ---------- WK 登记（5% 边界：22 件=10% 置灰 → 19 件=5% 黄条+备注） ----------
    await page.locator('[data-test="wb-tabs"] .el-tabs__item', { hasText: '已受理待登记' }).click()
    const acceptedRow = page.locator('[data-test="wb-table"] .el-table__row').first()
    await expect(acceptedRow).toContainText('WK-', { timeout: 15_000 })
    await acceptedRow.locator('[data-test="register-btn"]').click()

    const regDialog = page.locator('[data-test="register-dialog"]')
    await expect(regDialog).toBeVisible()
    // >5%：红条 + 登记按钮置灰（前端与后端同口径整型算式）
    await regDialog.locator('[data-test="register-actual-qty"] input').fill('22')
    await regDialog.locator('[data-test="register-actual-qty"] input').press('Enter')
    await expect(regDialog.locator('[data-test="diff-exceeded-alert"]')).toContainText('超过 5%')
    await expect(regDialog.locator('[data-test="register-submit"]')).toBeDisabled()
    // =5%（边界含等于放行）：黄条 + 差异备注必填
    await regDialog.locator('[data-test="register-actual-qty"] input').fill('19')
    await regDialog.locator('[data-test="register-actual-qty"] input').press('Enter')
    await expect(regDialog.locator('[data-test="diff-warn-alert"]')).toContainText('差 1 件')
    await expect(regDialog.locator('[data-test="register-submit"]')).toBeDisabled()
    await regDialog.locator('[data-test="register-remark"]').fill('1 件运输破损，已按实收登记')
    await expect(regDialog.locator('[data-test="register-submit"]')).toBeEnabled()
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-04-wk-register-5pct.png`, fullPage: true })

    await regDialog.locator('[data-test="register-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('实登 19 件', {
      timeout: 15_000,
    })

    // WK 已入库 Tab：实登 19、纠错入口可用
    await page.locator('[data-test="wb-tabs"] .el-tabs__item', { hasText: '已入库' }).click()
    const confirmedRow = page.locator('[data-test="wb-table"] .el-table__row').first()
    await expect(confirmedRow).toContainText('WK-', { timeout: 15_000 })
    await expect(confirmedRow).toContainText('19')
    await expect(confirmedRow.locator('[data-test="corr-btn"]')).toBeEnabled()
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-05-wk-confirmed.png`, fullPage: true })

    // ---------- WA 端回看：已入库 + 申请/实登双值 ----------
    await enterWaInbound(page, seed)
    await switchToMineView(page)
    await page.locator('[data-test="mine-tabs"] .el-tabs__item', { hasText: '已入库' }).click()
    const waConfirmed = page.locator('[data-test="mine-table"] .el-table__row').first()
    await expect(waConfirmed).toContainText('已入库', { timeout: 15_000 })
    await expect(waConfirmed).toContainText('20') // 申请件数
    await expect(waConfirmed).toContainText('19') // 实登件数
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-06-wa-confirmed.png`, fullPage: true })
  })

  test('FWD-02 驳回链：同批 2 单 → WA 撤回其一 → WK 驳回 → WA 复制重建预填', async ({
    page,
  }) => {
    const seed = await seedSellChain(30)
    // API 造同批 2 单（共享 batchSubmitId → 「同批 2 单」标识）
    const docs = await apiWaSubmit(seed.waLogin.token, seed.wholesalerId, [
      { skuId: seed.skuId, qty: 10, palletQty: 1 },
      { skuId: seed.skuId, qty: 5, remark: '第二批补货' },
    ])
    expect(docs.length).toBe(2)

    // ---------- WA：同批标识 + 撤回其一 ----------
    await enterWaInbound(page, seed)
    await switchToMineView(page)
    await page.locator('[data-test="mine-tabs"] .el-tabs__item', { hasText: '待受理' }).click()
    const rows = page.locator('[data-test="mine-table"] .el-table__row')
    await expect(rows).toHaveCount(2, { timeout: 15_000 })
    await expect(page.locator('[data-test="batch-tag"]').first()).toContainText('同批 2 单')

    // 撤回 qty=10 的那单
    const rowToWithdraw = rows.filter({ hasText: docs[0].docNo }).first()
    await rowToWithdraw.locator('[data-test="withdraw-btn"]').click()
    const wDialog = page.locator('[data-test="withdraw-dialog"]')
    await expect(wDialog).toBeVisible()
    // 理由必填校验
    await wDialog.locator('[data-test="withdraw-submit"]').click()
    await expect(page.locator('.el-message--warning').last()).toContainText('撤回理由')
    await wDialog.locator('[data-test="withdraw-reason"]').fill('重复提交，撤回一单')
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-07-wa-withdraw-dialog.png`, fullPage: true })
    await wDialog.locator('[data-test="withdraw-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已撤回', { timeout: 15_000 })

    // 已撤回 Tab 可见
    await page.locator('[data-test="mine-tabs"] .el-tabs__item', { hasText: '已撤回' }).click()
    await expect(
      page.locator('[data-test="mine-table"] .el-table__row', { hasText: docs[0].docNo }),
    ).toHaveCount(1, { timeout: 15_000 })

    // ---------- WK：驳回剩余一单（四选原因 + 备注必填 + 附件） ----------
    await enterWkInbound(page, seed)
    const wbRow = page
      .locator('[data-test="wb-table"] .el-table__row', { hasText: docs[1].docNo })
      .first()
    await expect(wbRow).toBeVisible({ timeout: 15_000 })
    await wbRow.locator('[data-test="reject-btn"]').click()

    const rejectDialog = page.locator('[data-test="reject-dialog"]')
    await expect(rejectDialog).toBeVisible()
    await rejectDialog
      .locator('[data-test="reject-reason"] .el-radio', { hasText: '数量不符' })
      .click()
    await rejectDialog
      .locator('[data-test="reject-remark"]')
      .fill('到货件数与申请不符，请核实后重新提交')
    // 举证附件（复用 AttachmentUpload）
    await rejectDialog
      .locator('[data-test="attachment-input"]')
      .setInputFiles({ name: 'evidence.png', mimeType: 'image/png', buffer: PNG_1PX })
    await expect(rejectDialog.locator('[data-test="attachment-upload"] img')).toHaveCount(1, {
      timeout: 15_000,
    })
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-08-wk-reject-dialog.png`, fullPage: true })
    await rejectDialog.locator('[data-test="reject-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已驳回', { timeout: 15_000 })

    // WK 已驳回 Tab：原因中文展示
    await page.locator('[data-test="wb-tabs"] .el-tabs__item', { hasText: '已驳回' }).click()
    await expect(
      page.locator('[data-test="wb-table"] .el-table__row', { hasText: docs[1].docNo }),
    ).toContainText('数量不符', { timeout: 15_000 })

    // ---------- WA：已驳回 → 一键复制重建（预填原字段） ----------
    await enterWaInbound(page, seed)
    await switchToMineView(page)
    await page.locator('[data-test="mine-tabs"] .el-tabs__item', { hasText: '已驳回' }).click()
    const rejectedRow = page
      .locator('[data-test="mine-table"] .el-table__row', { hasText: docs[1].docNo })
      .first()
    await expect(rejectedRow).toContainText('数量不符', { timeout: 15_000 })
    await rejectedRow.locator('[data-test="rebuild-btn"]').click()

    const rebuildDialog = page.locator('[data-test="inbound-submit-dialog"]')
    await expect(rebuildDialog).toBeVisible()
    // 预填断言：qty=5、SKU 名称已带出、备注带出
    await expect(rebuildDialog.locator('[data-test="row-qty"] input')).toHaveValue('5')
    await expect(rebuildDialog.locator('.cc-picker__trigger input')).toHaveValue(
      new RegExp(seed.skuName),
    )
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-09-wa-rebuild-prefill.png`, fullPage: true })
  })

  test('FWD-03 纠错链：WK 发起纠错（封顶预览）→ TA 审批通过 → 库存联动', async ({ page }) => {
    const seed = await seedSellChain(30)
    // API 造「已入库」正向链单据：提交 10 → 受理 → 实登 10（库存 30+10=40）
    const docs = await apiWaSubmit(seed.waLogin.token, seed.wholesalerId, [
      { skuId: seed.skuId, qty: 10 },
    ])
    await apiWkAcceptAndRegister(seed.wkToken, docs[0].id, 10)
    const onhandBefore = await apiOnhand(seed.wkToken, seed.wholesalerId, seed.skuId)
    expect(onhandBefore).toBe(40)

    // ---------- WK：发起纠错（10 → 4，改小 6；在库充足无差额） ----------
    await enterWkInbound(page, seed)
    await page.locator('[data-test="wb-tabs"] .el-tabs__item', { hasText: '已入库' }).click()
    const row = page
      .locator('[data-test="wb-table"] .el-table__row', { hasText: docs[0].docNo })
      .first()
    await expect(row).toBeVisible({ timeout: 15_000 })
    await row.locator('[data-test="corr-btn"]').click()

    const corrDialog = page.locator('[data-test="corr-dialog"]')
    await expect(corrDialog).toBeVisible()
    await corrDialog.locator('[data-test="corr-new-qty"] input').fill('4')
    await corrDialog.locator('[data-test="corr-new-qty"] input').press('Enter')
    await expect(corrDialog.locator('[data-test="corr-delta"]')).toContainText('-6 件')
    await expect(corrDialog.locator('[data-test="corr-cap-alert"]')).toContainText('冲销 6 件', {
      timeout: 15_000,
    })
    await corrDialog.locator('[data-test="corr-reason"]').fill('现场复核多记 6 件')
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-10-wk-corr-dialog.png`, fullPage: true })
    await corrDialog.locator('[data-test="corr-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('等待租户管理员审批', {
      timeout: 15_000,
    })

    // ---------- TA：审批中心纠错卡 → 通过 ----------
    await enterTaApprovals(page, seed)
    const corrRow = page
      .locator('[data-test="corr-table"] .el-table__row', { hasText: docs[0].docNo })
      .first()
    await expect(corrRow).toBeVisible({ timeout: 15_000 })
    await expect(corrRow).toContainText('10 → 4')
    await corrRow.locator('[data-test="corr-decide-btn"]').click()

    const decideDialog = page.locator('[data-test="corr-decide-dialog"]')
    await expect(decideDialog).toBeVisible()
    await expect(decideDialog.locator('[data-test="corr-decide-delta"]')).toContainText('-6 件')
    await expect(decideDialog.locator('[data-test="corr-cap-preview"]')).toContainText('库存', {
      timeout: 15_000,
    })
    // 复用 decide 弹窗模式：先驳回缺备注 → 前端拦截
    await decideDialog
      .locator('[data-test="corr-conclusion-radio"] .el-radio', { hasText: '驳回' })
      .click()
    await decideDialog.locator('[data-test="corr-decide-submit"]').click()
    await expect(page.locator('.el-message--warning').last()).toContainText('驳回时必须填写结论备注')
    // 改为通过
    await decideDialog
      .locator('[data-test="corr-conclusion-radio"] .el-radio', { hasText: '通过' })
      .click()
    await decideDialog.locator('[data-test="corr-decide-remark"]').fill('核实无误，同意更正')
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-11-ta-corr-decide.png`, fullPage: true })
    await decideDialog.locator('[data-test="corr-decide-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('实际生效 6 件', {
      timeout: 15_000,
    })

    // 已通过 Tab：生效/差额 6/0
    await page.locator('[data-test="corr-tabs"] .el-tabs__item', { hasText: '已通过' }).click()
    const approvedRow = page
      .locator('[data-test="corr-table"] .el-table__row', { hasText: docs[0].docNo })
      .first()
    await expect(approvedRow).toContainText('6 / 0', { timeout: 15_000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-12-ta-corr-approved.png`, fullPage: true })

    // 库存联动断言：40 − 6 = 34（CORRECTION_OUT 冲销）
    const onhandAfter = await apiOnhand(seed.wkToken, seed.wholesalerId, seed.skuId)
    expect(onhandAfter).toBe(34)
  })
})
