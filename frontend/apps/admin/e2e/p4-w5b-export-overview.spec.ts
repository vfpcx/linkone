/**
 * P4 W5b · 导出接入 + TA 账单总览页 E2E（关卡 2 例）
 *
 *  1) 导出触发下载：ST 账单详情三导出按钮（PDF/Excel/对账单）→ 断言 download 事件 +
 *     RFC 5987 中文文件名 + 文件魔数（%PDF- / PK）；DRAFT 可导（带水印在后端 JUnit 已断言）
 *  2) 总览一致性：TA /ta/bills → 重定向 /ta/bills-overview；四汇总卡数值与
 *     GET /tenant/st/bills 汇总一致；商户行下钻 → /st/bills?wholesalerId= 过滤生效
 *
 * 前置：后端 8080 + 本 worktree dev server（E2E_BASE_URL 指向）。
 * 造数走 API（onboarding/billing helpers），测试接缝=流水/规则回拨上月（batch.ts mysql 先例）。
 */

import { test, expect, type Page } from '@playwright/test'
import path from 'node:path'
import fs from 'node:fs'
import {
  seedActiveTenant,
  registerWaWithTarget,
  listTaApplications,
  auditApplication,
  seedStockForWholesaler,
  confirmPendingInbound,
  injectAuthAndGoto,
  apiGet,
  ok,
  type TenantSeed,
  type WaSeed,
  type LoginData,
} from './helpers/onboarding'
import {
  backdateToLastMonth,
  lastMonth,
  registerStEmployee,
  saveRule,
  generateBills,
  listStBills,
} from './helpers/billing'

const SCREEN_DIR = path.resolve(process.cwd(), 'test-results/screens')

/** MoneyDisplay 文本 → number（"¥2,340.56" → 2340.56） */
const moneyOf = async (page: Page, testId: string): Promise<number> =>
  Number((await page.locator(`[data-test="${testId}"]`).innerText()).replace(/[^0-9.-]/g, ''))

test.beforeAll(() => {
  fs.mkdirSync(SCREEN_DIR, { recursive: true })
})

test.describe.serial('P4 W5b 导出 + TA 账单总览', () => {
  let seed: TenantSeed
  let wa: WaSeed
  let wholesalerId = ''
  let stLogin: LoginData
  let billId = ''
  const month = lastMonth()

  test.beforeAll(async () => {
    test.setTimeout(180_000)
    // ACTIVE 租户 + WA 入驻 + 库存（10 件）
    seed = await seedActiveTenant()
    wa = await registerWaWithTarget(seed.tenantId)
    const apps = await listTaApplications(seed.ta.login.token)
    const app = apps.find((a) => a.name === wa.wholesalerName)
    if (!app) throw new Error('[w5b] 未找到入驻申请')
    const audit = ok(
      await auditApplication(seed.ta.login.token, app.id, 'APPROVED'),
      'TA 审批入驻',
    )
    wholesalerId = audit.wholesalerId ?? ''
    if (!wholesalerId) throw new Error('[w5b] 审批未返回 wholesalerId')
    await seedStockForWholesaler(seed.ta.login.token, wholesalerId, 10)
    await confirmPendingInbound(wa.login.token)
    // 结算员 + 首版规则（件·天 0.5 元）
    stLogin = (await registerStEmployee(seed.ta.login.token)).login
    ok(
      await saveRule(seed.ta.login.token, { billingByQty: true, pricePerQtyDay: 0.5, billingByPallet: false }),
      '首存计费规则',
    )
    // 测试接缝：流水+规则回拨上月 → 补生成上月账单（DRAFT，US-ST-05：待核对亦可导）
    backdateToLastMonth(seed.tenantId, wholesalerId)
    const gen = ok(await generateBills(stLogin.token, month), '补生成账单')
    billId = gen.billIds?.[0] ?? ''
    if (!billId) {
      const bills = ok(await listStBills(stLogin.token, { month }), 'ST 账单列表')
      billId = bills.records[0]?.id ?? ''
    }
    if (!billId) throw new Error('[w5b] 未生成账单')
  })

  // ============ 例 1 · 导出触发下载（download 事件 + 文件名 + 魔数） ============

  test('ST 详情三导出：PDF/Excel/对账单触发下载，RFC 5987 中文文件名 + 魔数校验', async ({ page }) => {
    test.setTimeout(120_000)
    await injectAuthAndGoto(page, stLogin, 'ST', `/st/bills/${billId}`)
    await expect(page.locator('[data-test="bill-amount-cards"]')).toBeVisible()
    // DRAFT 状态（待核对可导，PDF 带「未下发预览稿」水印——内容断言在后端 JUnit）
    await expect(page.locator('[data-test="bill-status"]')).toContainText('待核对')

    const magicOf = async (dl: import('@playwright/test').Download, bytes: number) => {
      const p = await dl.path()
      if (!p) throw new Error('[w5b] 下载未落盘')
      return fs.readFileSync(p).subarray(0, bytes).toString('latin1')
    }

    // 1) 导出 PDF：账单-BL-….pdf，魔数 %PDF-
    const [pdfDl] = await Promise.all([
      page.waitForEvent('download'),
      page.locator('[data-test="bill-export-pdf"]').click(),
    ])
    expect(pdfDl.suggestedFilename()).toMatch(/^账单-BL-.+\.pdf$/)
    expect(await magicOf(pdfDl, 5)).toBe('%PDF-')
    await expect(page.locator('.el-message--success').last()).toContainText('已开始下载')

    // 2) 导出 Excel：账单-BL-….xlsx，魔数 PK（zip）
    const [xlsxDl] = await Promise.all([
      page.waitForEvent('download'),
      page.locator('[data-test="bill-export-excel"]').click(),
    ])
    expect(xlsxDl.suggestedFilename()).toMatch(/^账单-BL-.+\.xlsx$/)
    expect(await magicOf(xlsxDl, 2)).toBe('PK')

    // 3) 导出对账单：对账单-{商户名}-{yyyy-MM}.xlsx
    const [snapDl] = await Promise.all([
      page.waitForEvent('download'),
      page.locator('[data-test="bill-export-snapshots"]').click(),
    ])
    expect(snapDl.suggestedFilename()).toMatch(new RegExp(`^对账单-.+-${month}\\.xlsx$`))
    expect(await magicOf(snapDl, 2)).toBe('PK')

    // 视觉 QA：操作区三导出按钮
    await page.waitForTimeout(500)
    await page.screenshot({
      path: path.join(SCREEN_DIR, 'w5b-st-bill-detail-export.png'),
      fullPage: true,
    })
  })

  // ============ 例 2 · TA 总览页数值与 ST 列表汇总一致 + 行下钻 ============

  test('TA 账单总览：/ta/bills 重定向新页，四汇总卡与 ST 列表汇总一致，行点击下钻商户过滤', async ({ page }) => {
    test.setTimeout(120_000)
    // ST 列表汇总（同租户全量口径，权威对照）
    const stList = ok(
      await apiGet<{
        records: Array<{ billNo: string }>
        total: number
        receivable: number
        received: number
        outstanding: number
      }>('/tenant/st/bills', seed.ta.login.token, { page: 1, size: 20 }),
      'ST 列表汇总',
    )
    expect(stList.total).toBeGreaterThanOrEqual(1)

    // /ta/bills 旧入口 → 重定向独立总览页
    await injectAuthAndGoto(page, seed.ta.login, 'TA', '/ta/bills')
    await expect(page).toHaveURL(/\/ta\/bills-overview$/)
    await expect(page.locator('[data-test="ta-overview-cards"]')).toBeVisible()

    // 先等张数卡到位（重试型断言，规避拉取完成前读 0 的竞态），再读三金额卡
    await expect(page.locator('[data-test="ta-overview-bill-count"]')).toHaveText(
      String(stList.total),
    )
    // 四汇总卡数值与 ST 列表汇总一致
    expect(await moneyOf(page, 'ta-overview-receivable')).toBeCloseTo(stList.receivable, 2)
    expect(await moneyOf(page, 'ta-overview-received')).toBeCloseTo(stList.received, 2)
    expect(await moneyOf(page, 'ta-overview-outstanding')).toBeCloseTo(stList.outstanding, 2)

    // 状态分布（DRAFT 1 张）+ 商户行
    await expect(page.locator('[data-test="ta-overview-status"]')).toContainText('待核对 1 张')
    const row = page.locator('[data-test="ta-overview-table"] tbody tr').first()
    await expect(row).toContainText(wa.wholesalerName)
    await expect(row).toContainText('待核对 1')

    // 视觉 QA：总览页整页
    await page.waitForTimeout(500)
    await page.screenshot({
      path: path.join(SCREEN_DIR, 'w5b-ta-bills-overview.png'),
      fullPage: true,
    })

    // 行点击下钻 → /st/bills?wholesalerId=（TA 兼岗权限并集）
    await row.click()
    await expect(page).toHaveURL(new RegExp(`/st/bills\\?.*wholesalerId=${wholesalerId}`))
    const stRow = page.locator('[data-test="bill-table"] tbody tr')
    await expect(stRow.first()).toContainText('BL-')
    await expect(stRow).toHaveCount(1)
    // 下钻后列表汇总条与总览行同源（应收=总览应收）
    await expect(page.locator('[data-test="bill-summary-bar"]')).toContainText('合计 1 张')
  })
})
