/**
 * P3b T4-FE · 批次/临期/清库 E2E（13 §6 T4-FE 关卡：开关启用→入库带批次→临期→清库全链）
 *
 * 三链：
 *  T4-01 开关+批次录入链：TA UI 开启（一次性说明弹窗+confirmed）→ 默认批次吸收存量 →
 *        WK 代建入库 UI 录批次三字段（临期黄条）→ 登记簿/预警列表联动 → 24h 限 2（50361 API 断言）
 *  T4-02 临期看板+手动通知链：造临期批次（入库即 EXPIRING）→ TA 看板四卡+bySku →
 *        WK 预警列表 [通知批发商] → 冷却置灰 → 二发 50367（API 断言）
 *  T4-03 清库全链：过期入库（50364 强确认 API 断言→凭据放行）→ 测试接缝标记待清理 →
 *        WK 预警列表 [发起清库] → 建单（原因/照片必填）→ 提交 → TA 审批弹窗封顶预览 →
 *        通过 → 库存扣减断言 + 批次 CLEARED 断言
 *
 * 造数走 seedSellChain；前置：后端 8080（main ≥ 80e5dd9 含 V22/V23）+ 前端 dev 5173。
 * 截图：test-results/screens/p3b-t4-*.png（Team Lead 视觉目检，00-overview §3.5/§3.6）。
 */

import { test, expect, type Page } from '@playwright/test'
import { seedSellChain, fetchRtStore, stockOfSku, type SellSeed } from './helpers/sell'
import {
  apiBatchToggle,
  apiBatchToggleOk,
  apiProxyInbound,
  apiNotifyWholesaler,
  findBatchByNo,
  sqlMarkPendingClearance,
  dateOffset,
} from './helpers/batch'

const SCREEN_DIR = 'test-results/screens'

/** 1×1 透明 PNG（清库照片上传用） */
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
)

// ============ 登录态注入（p3b-t3 spec 先例） ============

async function enterWithAuth(
  page: Page,
  login: SellSeed['taLogin'],
  primaryRole: 'TA' | 'WK',
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
      ? { tenantId: roleEntry.tenantId, tenantName: '测试仓' }
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
 * data-test 透传定位：Element Plus 输入类组件会把未知 attrs 落到内部 input 上
 * （el-input/el-date-picker），部分组件落在包装 div（el-input-number）——两种写法兼容取首个。
 */
const testInput = (page: Page, testId: string) =>
  page.locator(`input[data-test="${testId}"], [data-test="${testId}"] input`).first()

/**
 * 填 el-date-picker（value-format=YYYY-MM-DD）：el-date-picker 会吞掉 data-test attrs，
 * 以可访问名（combobox aria-label=label）定位；输入后 Enter 提交、Escape 收起面板。
 */
async function fillDate(page: Page, label: string, value: string): Promise<void> {
  const input = page.getByRole('combobox', { name: label }).first()
  await input.click()
  await input.fill(value)
  await input.press('Enter')
  await page.keyboard.press('Escape')
}

// ============ T4-01 开关 + 批次录入链 ============

test('T4-01 TA 开启批次(默认批次吸收) → WK 代建录批次(临期黄条) → 登记簿联动 → 24h 限 2', async ({
  page,
}) => {
  const seed = await seedSellChain(30)

  // --- TA UI 开启：一次性说明弹窗 + confirmed 凭据 ---
  await enterWithAuth(page, seed.taLogin, 'TA', '/ta/settings', '店铺设置')
  await page.locator('[data-test="batch-toggle-switch"]').click()
  const box = page.locator('.el-message-box')
  await expect(box).toBeVisible()
  await expect(box).toContainText('默认批次')
  await expect(box).toContainText('24 小时内最多操作 2 次')
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-01-toggle-confirm.png` })
  await box.getByRole('button', { name: '确认开启' }).click()
  await expect(page.locator('.el-message', { hasText: '批次管理已开启' })).toBeVisible()
  // 存量 30 件 → 默认批次 1 条
  await expect(page.locator('.el-message', { hasText: '已生成 1 条默认批次' })).toBeVisible()

  // --- TA 批次临期页：登记簿出现默认批次（在库/默认来源/效期待补录） ---
  await page.goto('/ta/batches')
  await expect(page.locator('.page-head__title')).toContainText('批次临期')
  await expect(page.locator('[data-test="expiry-dashboard"]')).toBeVisible()
  await page.locator('.el-tabs__item', { hasText: '批次登记簿' }).click()
  const defaultRow = page.locator('[data-test="registry-table"] .el-table__row', {
    hasText: 'DEFAULT-',
  })
  await expect(defaultRow).toBeVisible()
  await expect(defaultRow).toContainText('在库')
  await expect(defaultRow).toContainText('默认批次')
  await expect(defaultRow).toContainText('待补录')
  await expect(defaultRow).toContainText('30 件*')
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-01-default-batch.png`, fullPage: true })

  // --- WK 代建入库：批次三字段 + 临期黄条（到效期 today+10 ≤ 阈值 30） ---
  await enterWithAuth(page, seed.wkLogin, 'WK', '/ta/inbound', '入库')
  await page.locator('.el-tabs__item', { hasText: '现场代建入库' }).click()
  // 商户默认已选（首个）；选商品
  await page.locator('.cc-picker__trigger').nth(1).click()
  const skuPicker = page.locator('.cc-picker-dialog').last()
  await expect(skuPicker).toBeVisible()
  await skuPicker.locator('.el-table__row', { hasText: seed.skuName }).dblclick()
  await expect(skuPicker).toBeHidden()
  // 数量 + 批次三字段
  await page.locator('.inbound-form .el-input-number input').first().fill('10')
  await testInput(page, 'proxy-batch-no').fill('E2E-B1')
  await fillDate(page, '生产日期', dateOffset(-10))
  await fillDate(page, '到效期', dateOffset(10))
  await expect(page.locator('[data-test="proxy-near-alert"]')).toBeVisible()
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-01-proxy-batch-form.png`, fullPage: true })
  await page.getByRole('button', { name: '登记入库' }).first().click()
  await expect(page.locator('.el-message', { hasText: '入库登记成功' })).toBeVisible()

  // --- 预警列表联动：入库即临期（13 v1.4 备注 7） ---
  await page.goto('/ta/batches')
  await expect(page.locator('.page-head__title')).toContainText('批次临期')
  const expRow = page.locator('[data-test="expiring-table"] .el-table__row', {
    hasText: 'E2E-B1',
  })
  await expect(expRow).toBeVisible()
  await expect(expRow).toContainText('临期')
  await expect(expRow).toContainText('10 件*')
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-01-expiring-list.png`, fullPage: true })

  // --- 24h 限 2：UI 开启=第 1 次 → API 关闭=第 2 次 → 第 3 次 50361 ---
  await apiBatchToggleOk(seed.taToken, false)
  const third = await apiBatchToggle(seed.taToken, true)
  expect(third.code).toBe(50361)
})

// ============ T4-02 临期看板 + 手动通知链 ============

test('T4-02 临期看板四卡+bySku → WK 手动通知 → 24h 冷却置灰 → 二发 50367', async ({ page }) => {
  const seed = await seedSellChain(40)
  await apiBatchToggleOk(seed.taToken, true)
  const batchNo = `NEAR-${Date.now().toString().slice(-6)}`
  // 到效期 today+5 ≤ 阈值 30 → 入库即 EXPIRING
  const created = await apiProxyInbound(seed.wkToken, {
    wholesalerId: seed.wholesalerId,
    skuId: seed.skuId,
    qty: 8,
    batchNo,
    productionDate: dateOffset(-3),
    expiryDate: dateOffset(5),
  })
  expect(created.code).toBe(0)

  // --- TA 看板：四卡 + bySku 分组 ---
  await enterWithAuth(page, seed.taLogin, 'TA', '/ta/batches', '批次临期')
  const dash = page.locator('[data-test="expiry-dashboard"]')
  await expect(dash).toBeVisible()
  await expect(dash.locator('[data-test="card-expiring"] .kpi-card__value')).toHaveText('1')
  await expect(dash.locator('[data-test="card-expiring"]')).toContainText('8 件*')
  await expect(dash.locator('[data-test="card-expired"] .kpi-card__value')).toHaveText('0')
  await expect(dash.locator('[data-test="card-pending-doc"] .kpi-card__value')).toHaveText('0')
  const bySkuRow = dash.locator('[data-test="dashboard-by-sku"] .el-table__row', {
    hasText: seed.skuName,
  })
  await expect(bySkuRow).toBeVisible()
  await expect(bySkuRow).toContainText('8 件*')
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-02-dashboard.png`, fullPage: true })

  // --- WK 预警列表：剩余 5 天 → 手动通知 → 冷却态 ---
  await enterWithAuth(page, seed.wkLogin, 'WK', '/ta/batches', '批次临期')
  const row = page.locator('[data-test="expiring-table"] .el-table__row', { hasText: batchNo })
  await expect(row).toBeVisible()
  await expect(row.locator('[data-test="remaining-days"]')).toHaveText('5 天')
  const notifyBtn = row.locator('[data-test="notify-btn"]')
  await expect(notifyBtn).toContainText('通知批发商')
  await notifyBtn.click()
  const box = page.locator('.el-message-box')
  await expect(box).toContainText('24 小时内限通知 1 次')
  await box.getByRole('button', { name: '发送通知' }).click()
  await expect(page.locator('.el-message', { hasText: '已通知商户' })).toBeVisible()
  // 列表刷新后按钮进入冷却态（manualNotifiedAt 回显 → 置灰 + 剩余小时）
  await expect(notifyBtn).toBeDisabled()
  await expect(notifyBtn).toContainText('后可再发')
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-02-notify-cooldown.png`, fullPage: true })

  // --- 二发 50367（D-12 手动限频） ---
  const batch = await findBatchByNo(seed.wkToken, batchNo)
  expect(batch.manualNotifiedAt).toBeTruthy()
  const second = await apiNotifyWholesaler(seed.wkToken, batch.id)
  expect(second.code).toBe(50367)
})

// ============ T4-03 清库全链 ============

test('T4-03 过期入库(50364→凭据放行) → 待清理 → WK 发起清库 → TA 审批封顶预览 → 通过扣库存+批次已清库', async ({
  page,
}) => {
  const seed = await seedSellChain(50)
  await apiBatchToggleOk(seed.taToken, true)
  const batchNo = `EXPB-${Date.now().toString().slice(-6)}`

  // 过期批次（到效期 today-2）：缺凭据 → 50364；expiredConfirmed=true → 放行（入库即 EXPIRING）
  const noConfirm = await apiProxyInbound(seed.wkToken, {
    wholesalerId: seed.wholesalerId,
    skuId: seed.skuId,
    qty: 12,
    batchNo,
    productionDate: dateOffset(-40),
    expiryDate: dateOffset(-2),
  })
  expect(noConfirm.code).toBe(50364)
  const confirmed = await apiProxyInbound(seed.wkToken, {
    wholesalerId: seed.wholesalerId,
    skuId: seed.skuId,
    qty: 12,
    batchNo,
    productionDate: dateOffset(-40),
    expiryDate: dateOffset(-2),
    expiredConfirmed: true,
  })
  expect(confirmed.code).toBe(0)

  // 测试接缝：复现 02:30 归零标记（昨日到期 ∧ remaining>0 → PENDING_CLEARANCE）
  const batch = await findBatchByNo(seed.wkToken, batchNo)
  expect(batch.status).toBe('EXPIRING')
  sqlMarkPendingClearance(batch.id)

  // --- WK 预警列表：过期红显 + 待清理 tag + 发起清库 ---
  await enterWithAuth(page, seed.wkLogin, 'WK', '/ta/batches', '批次临期')
  const row = page.locator('[data-test="expiring-table"] .el-table__row', { hasText: batchNo })
  await expect(row).toBeVisible()
  await expect(row.locator('[data-test="remaining-days"]')).toHaveText('过期 2 天')
  await expect(row).toContainText('待清理')
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-03-expired-row.png`, fullPage: true })
  await row.locator('[data-test="start-clearance-btn"]').click()

  // --- 清库建单弹窗（?batch= 直开；批次五字段只读 + 默认件数=推算剩余） ---
  await expect(page).toHaveURL(/\/ta\/clearance\?batch=/)
  const editor = page.locator('[data-test="clearance-editor-dialog"]')
  await expect(editor).toBeVisible()
  await expect(editor.locator('[data-test="batch-info-banner"]')).toContainText(batchNo)
  await expect(editor.locator('[data-test="batch-info-banner"]')).toContainText('已过期 2 天')
  await expect(
    editor.locator('input[data-test="clearance-qty"], [data-test="clearance-qty"] input').first(),
  ).toHaveValue('12')
  // 前端预检链（按序回显）：原因未选 → 照片未传（50366），期间提交按钮置灰
  await expect(editor.locator('[data-test="editor-error"]')).toContainText('请选择清库原因')
  await expect(editor.locator('[data-test="clearance-submit"]')).toBeDisabled()
  await editor.locator('[data-test="clearance-reason"]').getByText('过期').click()
  await expect(editor.locator('[data-test="editor-error"]')).toContainText('实物照片必填')
  await expect(editor.locator('[data-test="clearance-submit"]')).toBeDisabled()
  await editor
    .locator('[data-test="attachment-upload"] input[type="file"]')
    .setInputFiles({ name: 'clearance-proof.png', mimeType: 'image/png', buffer: PNG_1PX })
  await expect(editor.locator('[data-test="attachment-upload"] img')).toBeVisible()
  await expect(editor.locator('[data-test="editor-error"]')).toBeHidden()
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-03-clearance-form.png`, fullPage: true })
  await editor.locator('[data-test="clearance-submit"]').click()
  const submitBox = page.locator('.el-message-box')
  await expect(submitBox).toContainText('封顶')
  await submitBox.getByRole('button', { name: '提交审批' }).click()
  await expect(page.locator('.el-message', { hasText: '已提交，等待租户管理员审批' })).toBeVisible()

  // --- TA 审批：批次五字段 + 封顶预览（现场核数 12 ≤ 在库 62 → 生效 12 无差额） ---
  await enterWithAuth(page, seed.taLogin, 'TA', '/ta/clearance', '清库')
  const pendingRow = page.locator('[data-test="clearance-table"] .el-table__row', {
    hasText: 'QK-',
  })
  await expect(pendingRow).toBeVisible()
  await pendingRow.locator('[data-test="decide-btn"]').click()
  const decide = page.locator('[data-test="clearance-decide-dialog"]')
  await expect(decide).toBeVisible()
  await expect(decide).toContainText(batchNo)
  await expect(decide).toContainText('已过期 2 天')
  await expect(decide.locator('[data-test="cap-preview"]')).toContainText('生效 12 件')
  await expect(decide).toContainText('仓储费当日截止')
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-03-decide-dialog.png`, fullPage: true })
  await decide.locator('[data-test="decide-approve"]').click()
  await expect(page.locator('.el-message', { hasText: '已通过' })).toBeVisible()

  // --- 联动断言：池库存 50+12−12=50；批次 CLEARED（推算剩余清零） ---
  const store = await fetchRtStore(seed.storeCode)
  expect(stockOfSku(store, seed.skuId)).toBe(50)
  const cleared = await findBatchByNo(seed.wkToken, batchNo)
  expect(cleared.status).toBe('CLEARED')
  expect(cleared.remainingQty).toBe(0)

  // 已通过详情追溯（批次五字段 + 照片凭证）
  await page.locator('.el-tabs__item', { hasText: '已通过' }).click()
  const doneRow = page.locator('[data-test="clearance-table"] .el-table__row', { hasText: 'QK-' })
  await expect(doneRow).toBeVisible()
  await doneRow.locator('[data-test="detail-btn"]').click()
  const detail = page.locator('[data-test="clearance-detail-dialog"]')
  await expect(detail).toBeVisible()
  await expect(detail).toContainText(batchNo)
  await expect(detail).toContainText('已通过')
  await page.waitForTimeout(350)
  await page.screenshot({ path: `${SCREEN_DIR}/p3b-t4-03-detail.png`, fullPage: true })
})
