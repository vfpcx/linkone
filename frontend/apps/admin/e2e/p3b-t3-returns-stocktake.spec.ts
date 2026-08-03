/**
 * P3b T3-FE · 退货 + 盘点 E2E（13 §6 T3-FE 关卡：退货全链、盘点建→提→批双路）
 *
 * 三链：
 *  RTN-01 退货主链：WA UI 发起（在库>0 选择器/超量红字）→ 撤回（理由必填）→ WK 受理 →
 *          登记出货（在库 ✅/托盘默认值）→ 库存联动断言 → 在库不足红条（预检 50251 口径）
 *  PD-02 盘点主链含封顶：WA 出库在途造数 → WK 建单（在途提示条/全仓载入/差异实时预览）→
 *          提交（快照说明）→ 审批等待期出库 20 件 → TA 审批弹窗封顶预览
 *          min(|盘亏|, currentStock) + 差额高亮 → 通过 → 库存归零断言 → 已决详情生效值
 *  PD-03 驳回重提：WK 存草稿 → 提交 → TA 驳回（备注必填拦截）→ WK 编辑重提 → TA 通过
 *
 * 造数走 seedSellChain（库存 N）；前置：后端 8080（T3-W2 已合 main）+ 前端 dev server。
 * 截图：test-results/screens/p3b-t3-*.png（供 Team Lead 视觉目检，00-overview §3.5/§3.6）。
 */

import { test, expect, type Page } from '@playwright/test'
import { seedSellChain, type SellSeed } from './helpers/sell'

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'
const SCREEN_DIR = 'test-results/screens'

// ============ 登录态注入（复刻 p3b-inbound-forward.spec 先例） ============

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

/** WA 登录态补 WA 角色条目（seed 快照早于 TA 绑定，前端 isWaAdmin/myWholesalerId 从 roles 推导） */
const waLoginWithRole = (seed: SellSeed): SellSeed['waLogin'] => ({
  ...seed.waLogin,
  roles: [
    ...seed.waLogin.roles,
    { role: 'WA', wholesalerId: seed.wholesalerId, priority: 40 } as SellSeed['waLogin']['roles'][number],
  ],
})

const enterWaReturns = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, waLoginWithRole(seed), 'WA', '/wa/returns', '退货')

const enterWkReturns = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, seed.wkLogin, 'WK', '/ta/returns', '退货受理')

const enterWkStocktake = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, seed.wkLogin, 'WK', '/ta/stocktake', '盘点')

const enterTaStocktake = (page: Page, seed: SellSeed) =>
  enterWithAuth(page, seed.taLogin, 'TA', '/ta/stocktake', '盘点')

// ============ API 旁路造数 ============

interface ApiHeaders {
  [k: string]: string
}
const authHeaders = (token: string): ApiHeaders => ({
  'Content-Type': 'application/json',
  Authorization: token,
  satoken: token,
})

interface Envelope<T> {
  code: number
  message?: string
  data: T
}

/** WA 发起退货（撤回/不足链造数用） */
async function apiWaCreateReturn(
  waToken: string,
  skuId: string,
  qty: number,
): Promise<{ id: string; docNo: string }> {
  const res = await fetch(`${API}/api/v1/wholesaler/return-requests`, {
    method: 'POST',
    headers: authHeaders(waToken),
    body: JSON.stringify({ skuId, qty }),
  })
  const env = (await res.json()) as Envelope<{ id: string; docNo: string }>
  if (env.code !== 0) throw new Error(`WA 发起退货失败 code=${env.code} msg=${env.message ?? ''}`)
  return env.data
}

/** WA 手动出库申请（提交即扣 → 制造「已确认未出库」在途单） */
async function apiWaSubmitOutbound(
  waToken: string,
  wholesalerId: string,
  skuId: string,
  qty: number,
): Promise<void> {
  const res = await fetch(`${API}/api/v1/wholesaler/outbound-requests`, {
    method: 'POST',
    headers: authHeaders(waToken),
    body: JSON.stringify({ wholesalerId, skuId, qty }),
  })
  const env = (await res.json()) as Envelope<unknown>
  if (env.code !== 0) throw new Error(`WA 出库申请失败 code=${env.code} msg=${env.message ?? ''}`)
}

/** WK 代建出库（直达 COMPLETED 即时扣库存；大额需复述件数） */
async function apiWkProxyOutbound(
  seed: SellSeed,
  qty: number,
): Promise<void> {
  const tenantId = seed.taLogin.roles.find((r) => r.role === 'TA')?.tenantId
  const res = await fetch(`${API}/api/v1/tenant/wk/outbound-requests`, {
    method: 'POST',
    headers: {
      ...authHeaders(seed.wkToken),
      ...(tenantId ? { 'X-Tenant-Id': String(tenantId) } : {}),
    },
    body: JSON.stringify({
      wholesalerId: seed.wholesalerId,
      skuId: seed.skuId,
      qty,
      confirmed: true,
      restatedQty: qty,
    }),
  })
  const env = (await res.json()) as Envelope<unknown>
  if (env.code !== 0) throw new Error(`WK 代建出库失败 code=${env.code} msg=${env.message ?? ''}`)
}

/** 查该商户 SKU 在库件数 */
async function apiOnhand(seed: SellSeed): Promise<number> {
  const res = await fetch(
    `${API}/api/v1/tenant/inventories?wholesalerId=${seed.wholesalerId}&skuId=${seed.skuId}`,
    { headers: authHeaders(seed.wkToken) },
  )
  const env = (await res.json()) as Envelope<Array<{ qty: number }>>
  if (env.code !== 0) throw new Error(`库存查询失败 code=${env.code}`)
  return env.data.length ? Number(env.data[0].qty) : 0
}

// ============ 用例 ============

test.describe('P3b T3 退货 + 盘点', () => {
  test('RTN-01 退货主链：WA 发起/撤回 → WK 受理/登记 → 库存联动 + 在库不足红条', async ({
    page,
  }) => {
    const seed = await seedSellChain(30)

    // ---------- WA 发起（UI：在库>0 选择器 + 超量红字软校验） ----------
    await enterWaReturns(page, seed)
    await page.locator('[data-test="new-return-btn"]').click()
    const createDialog = page.locator('[data-test="return-create-dialog"]')
    await expect(createDialog).toBeVisible()

    await createDialog.locator('.cc-picker__trigger').click()
    const picker = page.locator('.cc-picker-dialog').last()
    await expect(picker).toBeVisible()
    const skuRow = picker.locator('.el-table__row', { hasText: seed.skuName })
    await expect(skuRow).toContainText('30 件') // 选择器行展示当前在库
    await skuRow.first().dblclick()
    await expect(picker).toBeHidden()
    // 回显「名称（在库 N 件）」
    await expect(createDialog.locator('.cc-picker__trigger input')).toHaveValue(
      new RegExp(`${seed.skuName}.*在库 30 件`),
    )

    // 超量 → 红字 + 提交置灰
    await createDialog.locator('[data-test="return-qty"] input').fill('40')
    await createDialog.locator('[data-test="return-qty"] input').press('Enter')
    await expect(createDialog.locator('[data-test="return-stock-warn"]')).toContainText(
      '在库仅 30 件',
    )
    await expect(createDialog.locator('[data-test="return-submit"]')).toBeDisabled()
    // 合法件数 10
    await createDialog.locator('[data-test="return-qty"] input').fill('10')
    await createDialog.locator('[data-test="return-qty"] input').press('Enter')
    await createDialog.locator('[data-test="return-remark"]').fill('包装破损批次，整批退回')
    await expect(createDialog.locator('[data-test="return-submit"]')).toBeEnabled()
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-01-wa-return-create.png`, fullPage: true })
    await createDialog.locator('[data-test="return-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('退货申请已提交', {
      timeout: 15_000,
    })

    // 待受理 Tab 出现 RTN- 单据
    const waRow = page.locator('[data-test="wa-return-table"] .el-table__row').first()
    await expect(waRow).toContainText('RTN-', { timeout: 15_000 })
    await expect(waRow).toContainText('待受理')
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-02-wa-return-list.png`, fullPage: true })

    // ---------- WA 撤回（API 造第二单 → UI 撤回，理由必填拦截） ----------
    const rtnB = await apiWaCreateReturn(seed.waLogin.token, seed.skuId, 5)
    await page.reload()
    await page.waitForLoadState('networkidle')
    await page.locator('[data-test="return-tabs"] .el-tabs__item', { hasText: '待受理' }).click()
    const rowB = page
      .locator('[data-test="wa-return-table"] .el-table__row', { hasText: rtnB.docNo })
      .first()
    await expect(rowB).toBeVisible({ timeout: 15_000 })
    await rowB.locator('[data-test="withdraw-btn"]').click()
    const wDialog = page.locator('[data-test="return-withdraw-dialog"]')
    await expect(wDialog).toBeVisible()
    await wDialog.locator('[data-test="return-withdraw-submit"]').click()
    await expect(page.locator('.el-message--warning').last()).toContainText('撤回理由')
    await wDialog.locator('[data-test="return-withdraw-reason"]').fill('重复发起，撤回一单')
    await wDialog.locator('[data-test="return-withdraw-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已撤回', {
      timeout: 15_000,
    })

    // ---------- WK 受理 + 登记（在库 ✅ / 托盘默认值） ----------
    // 第三单（在库不足链路用，先占位）
    const rtnC = await apiWaCreateReturn(seed.waLogin.token, seed.skuId, 20)

    await enterWkReturns(page, seed)
    const pendingRows = page.locator('[data-test="wk-return-table"] .el-table__row')
    // 待受理升序：第一单（qty 10）在前
    await expect(pendingRows.first()).toContainText('RTN-', { timeout: 15_000 })
    await expect(pendingRows.first()).toContainText('10')
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-03-wk-return-queue.png`, fullPage: true })

    await pendingRows.first().locator('[data-test="accept-btn"]').click()
    await expect(page.locator('.el-message-box')).toContainText('受理不动库存')
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已受理', {
      timeout: 15_000,
    })

    await page
      .locator('[data-test="return-tabs"] .el-tabs__item', { hasText: '已受理待登记' })
      .click()
    const acceptedRow = page.locator('[data-test="wk-return-table"] .el-table__row').first()
    await expect(acceptedRow).toContainText('已受理', { timeout: 15_000 })
    await acceptedRow.locator('[data-test="register-btn"]').click()

    const regDialog = page.locator('[data-test="return-register-dialog"]')
    await expect(regDialog).toBeVisible()
    await expect(regDialog.locator('[data-test="register-current-stock"]')).toContainText(
      '30 件',
    )
    await expect(regDialog.locator('[data-test="register-current-stock"]')).toContainText('✅')
    await expect(regDialog.locator('[data-test="register-pallet-hint"]')).toContainText(
      '默认按比例建议',
    )
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-04-wk-return-register.png`, fullPage: true })

    await regDialog.locator('[data-test="register-submit"]').click()
    await expect(page.locator('.el-message-box')).toContainText('登记后库存 −10')
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success').last()).toContainText('实退 10 件', {
      timeout: 15_000,
    })

    // 库存联动：30 − 10 = 20（RETURN 流水）
    expect(await apiOnhand(seed)).toBe(20)

    // ---------- 在库不足红条（登记预检，与后端 50251 同口径） ----------
    // 受理第三单（qty 20，此刻在库 20 尚够）
    await page.locator('[data-test="return-tabs"] .el-tabs__item', { hasText: '待受理' }).click()
    const rowC = page
      .locator('[data-test="wk-return-table"] .el-table__row', { hasText: rtnC.docNo })
      .first()
    await expect(rowC).toBeVisible({ timeout: 15_000 })
    await rowC.locator('[data-test="accept-btn"]').click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已受理', {
      timeout: 15_000,
    })
    // 等待期被出库出走 18 件 → 在库 2 < 20
    await apiWkProxyOutbound(seed, 18)
    await page
      .locator('[data-test="return-tabs"] .el-tabs__item', { hasText: '已受理待登记' })
      .click()
    const shortRow = page
      .locator('[data-test="wk-return-table"] .el-table__row', { hasText: rtnC.docNo })
      .first()
    await expect(shortRow).toBeVisible({ timeout: 15_000 })
    await shortRow.locator('[data-test="register-btn"]').click()
    await expect(regDialog).toBeVisible()
    await expect(regDialog.locator('[data-test="register-stock-alert"]')).toContainText(
      '不足退货 20 件，请联系批发商修改退货单',
    )
    await expect(regDialog.locator('[data-test="register-submit"]')).toBeDisabled()
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-05-wk-return-short.png`, fullPage: true })

    expect(await apiOnhand(seed)).toBe(2) // 不足未登记，库存未动
  })

  test('PD-02 盘点主链：在途提示 → 建单提交 → 等待期出库 → TA 封顶审批 → 库存联动', async ({
    page,
  }) => {
    const seed = await seedSellChain(30)
    // 在途造数：WA 手动出库 5（提交即扣 → 账面 25、在途 1 张 5 件、货未离仓）
    await apiWaSubmitOutbound(seed.waLogin.token, seed.wholesalerId, seed.skuId, 5)

    // ---------- WK 建盘点单（在途提示条 + 全仓载入 + 差异预览） ----------
    await enterWkStocktake(page, seed)
    await page.locator('[data-test="new-sheet-btn"]').click()
    const editor = page.locator('[data-test="sheet-editor-dialog"]')
    await expect(editor).toBeVisible()

    // 选商户（唯一商户，首行双击）
    await editor.locator('.cc-picker__trigger').first().click()
    const wPicker = page.locator('.cc-picker-dialog').last()
    await expect(wPicker).toBeVisible()
    await wPicker.locator('.el-table__row').first().dblclick()
    await expect(wPicker).toBeHidden()

    // 在途提示条（护栏必做）：1 张已确认未出库（合计 5 件）
    await expect(editor.locator('[data-test="in-transit-banner"]')).toContainText(
      '1 张已确认未出库单据（合计 5 件）',
      { timeout: 15_000 },
    )

    // 全仓载入 → 1 行；账面 25（已扣后口径）；实物 0 → 盘亏 25
    await editor.locator('[data-test="load-all-btn"]').click()
    await expect(editor.locator('[data-test="item-row"]')).toHaveCount(1)
    await expect(editor.locator('[data-test="item-system-qty"]')).toContainText('25')
    await editor.locator('[data-test="item-actual"] input').fill('0')
    await editor.locator('[data-test="item-actual"] input').press('Enter')
    await expect(editor.locator('[data-test="item-diff"]')).toContainText('盘亏 25 件')
    await expect(editor.locator('[data-test="item-transit-note"]')).toContainText('≤5 件')
    await editor.locator('[data-test="sheet-remark"]').fill('月末例行盘点，现场清点')
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-06-wk-sheet-editor.png`, fullPage: true })

    // 提交（system_qty 快照说明在确认弹窗）
    await editor.locator('[data-test="sheet-submit"]').click()
    await expect(page.locator('.el-message-box')).toContainText('快照定格')
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已提交', {
      timeout: 15_000,
    })
    const pendingRow = page.locator('[data-test="stocktake-table"] .el-table__row').first()
    await expect(pendingRow).toContainText('PD-', { timeout: 15_000 })
    await expect(pendingRow).toContainText('待审批')

    // ---------- 等待期被出库出走 20 件（G9 场景：审批时在库仅 5） ----------
    await apiWkProxyOutbound(seed, 20)
    expect(await apiOnhand(seed)).toBe(5)

    // ---------- TA 审批：封顶预览 min(25, 5)=5 + 差额 20 高亮 → 通过 ----------
    await enterTaStocktake(page, seed)
    const taRow = page.locator('[data-test="stocktake-table"] .el-table__row').first()
    await expect(taRow).toContainText('待审批', { timeout: 15_000 })
    await taRow.locator('[data-test="decide-btn"]').click()

    const decideDialog = page.locator('[data-test="stocktake-decide-dialog"]')
    await expect(decideDialog).toBeVisible()
    await expect(decideDialog.locator('[data-test="decide-transit-banner"]')).toContainText(
      '已确认未出库',
    )
    // 逐行差异 + 封顶预览（前端直算 min(|盘亏|, currentStock)）
    const decideRow = decideDialog.locator('[data-test="decide-items-table"] .el-table__row').first()
    await expect(decideRow).toContainText('25') // 账面快照（提交时刻，不受后续出库影响）
    await expect(decideRow).toContainText('盘亏 25 件')
    await expect(decideRow.locator('[data-test="cap-preview"]')).toContainText('盘亏生效 5 件')
    await expect(decideRow.locator('[data-test="cap-shortfall"]')).toContainText('差额 20 件')
    await expect(decideDialog.locator('[data-test="decide-linkage"]')).toContainText('20 件')
    await decideDialog.locator('[data-test="decide-remark"]').fill('封顶生效，差额线下核查')
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-07-ta-decide-cap.png`, fullPage: true })

    await decideDialog.locator('[data-test="decide-approve"]').click()
    // 封顶二次确认
    await expect(page.locator('.el-message-box')).toContainText('差额合计 20 件')
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已通过', {
      timeout: 15_000,
    })

    // 库存联动：5 − 5（封顶 LOSS）= 0
    expect(await apiOnhand(seed)).toBe(0)

    // ---------- 已决详情：生效值 −5 ----------
    await page.locator('[data-test="stocktake-tabs"] .el-tabs__item', { hasText: '已通过' }).click()
    const doneRow = page.locator('[data-test="stocktake-table"] .el-table__row').first()
    await expect(doneRow).toContainText('已通过', { timeout: 15_000 })
    await doneRow.locator('[data-test="detail-btn"]').click()
    const detailDialog = page.locator('[data-test="stocktake-detail-dialog"]')
    await expect(detailDialog).toBeVisible()
    await expect(detailDialog.locator('[data-test="detail-applied"]')).toContainText('-5')
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-08-ta-detail-applied.png`, fullPage: true })
  })

  test('PD-03 驳回重提：草稿 → 提交 → TA 驳回（备注必填）→ WK 编辑重提 → TA 通过', async ({
    page,
  }) => {
    const seed = await seedSellChain(30)

    // ---------- WK 存草稿（实物 28 → 盘亏 2） ----------
    await enterWkStocktake(page, seed)
    await page.locator('[data-test="new-sheet-btn"]').click()
    const editor = page.locator('[data-test="sheet-editor-dialog"]')
    await expect(editor).toBeVisible()
    await editor.locator('.cc-picker__trigger').first().click()
    const wPicker = page.locator('.cc-picker-dialog').last()
    await wPicker.locator('.el-table__row').first().dblclick()
    await expect(wPicker).toBeHidden()
    await editor.locator('[data-test="load-all-btn"]').click()
    await expect(editor.locator('[data-test="item-row"]')).toHaveCount(1)
    await editor.locator('[data-test="item-actual"] input').fill('28')
    await editor.locator('[data-test="item-actual"] input').press('Enter')
    await expect(editor.locator('[data-test="item-diff"]')).toContainText('盘亏 2 件')
    await editor.locator('[data-test="sheet-save-draft"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('草稿已保存', {
      timeout: 15_000,
    })

    // 草稿行内提交
    const draftRow = page.locator('[data-test="stocktake-table"] .el-table__row').first()
    await expect(draftRow).toContainText('草稿', { timeout: 15_000 })
    await draftRow.locator('[data-test="submit-btn"]').click()
    await expect(page.locator('.el-message-box')).toContainText('快照定格')
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已提交', {
      timeout: 15_000,
    })

    // ---------- TA 驳回（备注必填前端拦截） ----------
    await enterTaStocktake(page, seed)
    const taRow = page.locator('[data-test="stocktake-table"] .el-table__row').first()
    await expect(taRow).toContainText('待审批', { timeout: 15_000 })
    await taRow.locator('[data-test="decide-btn"]').click()
    const decideDialog = page.locator('[data-test="stocktake-decide-dialog"]')
    await expect(decideDialog).toBeVisible()
    await decideDialog.locator('[data-test="decide-reject"]').click()
    await expect(page.locator('.el-message--warning').last()).toContainText(
      '驳回时必须填写审批意见',
    )
    await decideDialog.locator('[data-test="decide-remark"]').fill('请复核第 1 行实物数后重提')
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-09-ta-reject.png`, fullPage: true })
    await decideDialog.locator('[data-test="decide-reject"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已驳回', {
      timeout: 15_000,
    })

    // ---------- WK 编辑重提（回草稿 → 再提交） ----------
    await enterWkStocktake(page, seed)
    await page.locator('[data-test="stocktake-tabs"] .el-tabs__item', { hasText: '已驳回' }).click()
    const rejectedRow = page.locator('[data-test="stocktake-table"] .el-table__row').first()
    await expect(rejectedRow).toContainText('已驳回', { timeout: 15_000 })
    await expect(rejectedRow).toContainText('请复核第 1 行实物数后重提')
    await rejectedRow.locator('[data-test="reedit-btn"]').click()
    await expect(editor).toBeVisible()
    // 预填断言：实物 28 带出
    await expect(editor.locator('[data-test="item-actual"] input')).toHaveValue('28')
    await editor.locator('[data-test="item-actual"] input').fill('29')
    await editor.locator('[data-test="item-actual"] input').press('Enter')
    await expect(editor.locator('[data-test="item-diff"]')).toContainText('盘亏 1 件')
    await editor.locator('[data-test="sheet-save-draft"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已改回草稿', {
      timeout: 15_000,
    })
    const redraftRow = page.locator('[data-test="stocktake-table"] .el-table__row').first()
    await expect(redraftRow).toContainText('草稿', { timeout: 15_000 })
    await redraftRow.locator('[data-test="submit-btn"]').click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已提交', {
      timeout: 15_000,
    })
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${SCREEN_DIR}/p3b-t3-10-wk-resubmit.png`, fullPage: true })

    // ---------- TA 通过（无封顶：在库 30 ≥ 盘亏 1） ----------
    await enterTaStocktake(page, seed)
    const taRow2 = page.locator('[data-test="stocktake-table"] .el-table__row').first()
    await expect(taRow2).toContainText('待审批', { timeout: 15_000 })
    await taRow2.locator('[data-test="decide-btn"]').click()
    await expect(decideDialog).toBeVisible()
    const row2 = decideDialog.locator('[data-test="decide-items-table"] .el-table__row').first()
    await expect(row2).toContainText('盘亏 1 件')
    await expect(row2.locator('[data-test="cap-preview"]')).toContainText('盘亏生效 1 件')
    await expect(row2.locator('[data-test="cap-shortfall"]')).toHaveCount(0) // 无差额不高亮
    await decideDialog.locator('[data-test="decide-approve"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('已通过', {
      timeout: 15_000,
    })

    // 库存联动：30 − 1 = 29
    expect(await apiOnhand(seed)).toBe(29)
  })
})
