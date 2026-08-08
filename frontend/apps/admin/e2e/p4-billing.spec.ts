/**
 * P4 W4 · 计费结算前端 E2E（四链 + ST 三核心页 375 移动截图）
 *
 * 覆盖（关卡要求）：
 *  1) 规则设置链：TA 首存（空态横幅/生效文案逐字/免二次确认）→ R20 变更（二次确认弹窗→
 *     确认→版本递增+历史留痕）
 *  2) 账单全链：ST 登录动线（US-TA-03 注册码→/st/dashboard）→ 补生成 → 待核对详情 →
 *     下发 → WA 确认对账 → ST 回款登记 → 已结清
 *  3) 申诉链：WA 发起申诉（行级条目）→ ST 申诉队列 resolve（成立+说明）→ WA 端留痕可见
 *  4) 精算下钻：账单详情按日视角（每日快照 × 当段单价现算）逐日行断言
 *  5) §8.11 验收：375×812 移动视口三核心页（列表/详情/回款登记弹窗）截图，无横向滚动
 *
 * 前置：后端 8080（main≥befdf85，dev,local）+ 本 worktree dev server（E2E_BASE_URL 指向）。
 * 测试接缝：流水/规则回拨上月（helpers/billing.ts 文件头说明，mysql CLI 沿 batch.ts 先例）。
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
  loginAs,
  injectAuthAndGoto,
  ok,
  SEED_PWD,
  type TenantSeed,
  type WaSeed,
  type LoginData,
} from './helpers/onboarding'
import {
  backdateToLastMonth,
  lastMonth,
  registerStEmployee,
  recalcSnapshots,
  listStBills,
  getStBill,
  reversePaymentApi,
} from './helpers/billing'

const SCREEN_DIR = path.resolve(process.cwd(), 'test-results/screens')

test.beforeAll(() => {
  fs.mkdirSync(SCREEN_DIR, { recursive: true })
})

test.describe.serial('P4 计费结算', () => {
  let seed: TenantSeed
  let wa: WaSeed
  let wholesalerId = ''
  let stLogin: LoginData
  let stPhone = ''
  let billId = ''
  const month = lastMonth()

  test.beforeAll(async () => {
    test.setTimeout(180_000)
    // ACTIVE 租户 + WA 入驻 + 库存（10 件）
    seed = await seedActiveTenant()
    wa = await registerWaWithTarget(seed.tenantId)
    const apps = await listTaApplications(seed.ta.login.token)
    const app = apps.find((a) => a.name === wa.wholesalerName)
    if (!app) throw new Error('[p4] 未找到入驻申请')
    const audit = ok(
      await auditApplication(seed.ta.login.token, app.id, 'APPROVED'),
      'TA 审批入驻',
    )
    wholesalerId = audit.wholesalerId ?? ''
    if (!wholesalerId) throw new Error('[p4] 审批未返回 wholesalerId')
    await seedStockForWholesaler(seed.ta.login.token, wholesalerId, 10)
    await confirmPendingInbound(wa.login.token)
    // 结算员（US-TA-03 注册码链路）
    const st = await registerStEmployee(seed.ta.login.token)
    stLogin = st.login
    stPhone = st.phone
  })

  // ============ 链 1 · 规则设置链（首存 + R20 变更） ============

  test('规则设置链：首存空态→免确认保存→R20 变更二次确认→版本留痕', async ({ page }) => {
    await injectAuthAndGoto(page, seed.ta.login, 'TA', '/ta/settings')

    const card = page.locator('[data-test="billing-rule-card"]')
    await expect(card).toBeVisible()
    // 首次空态引导横幅（PRD §8.1 逐字）
    await expect(page.locator('[data-test="billing-rule-first-banner"]')).toContainText(
      '尚未设置计费规则，保存后系统才会开始累计仓储费并按月生成账单',
    )
    // 生效口径固定文案（PRD §1.3 逐字）
    await expect(page.locator('[data-test="billing-rule-effective-note"]')).toContainText(
      '计费规则自保存当日起生效。生效日之前的在库时间不计费、不补出历史账单；生效当月的账单只包含生效日起的费用。',
    )

    // 首存：启用件·天 0.5 元 → 免二次确认直接成功
    await card.locator('[data-test="rule-qty-toggle"]').click()
    await card.locator('[data-test="rule-qty-price"] input').fill('0.5')
    await card.locator('[data-test="billing-rule-save"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('计费规则已保存')
    await expect(page.locator('[data-test="billing-rule-version"]')).toContainText('第 1 版')

    // R20 变更：单价 0.5 → 0.6 → 二次确认弹窗（影响四条）→ 确认 → 第 2 版
    await card.locator('[data-test="rule-qty-price"] input').fill('0.6')
    await card.locator('[data-test="billing-rule-save"]').click()
    const confirmBox = page.locator('.el-message-box', { hasText: '确认变更计费规则' })
    await expect(confirmBox).toBeVisible()
    await expect(confirmBox).toContainText('历史已出账账单不重算')
    await expect(confirmBox).toContainText('全部在驻批发商将收到通知')
    await confirmBox.getByRole('button', { name: '确认变更' }).click()
    await expect(page.locator('.el-message--success').last()).toContainText('计费规则已更新')
    await expect(page.locator('[data-test="billing-rule-version"]')).toContainText('第 2 版')
    // 历史留痕表：两版可见
    const history = page.locator('[data-test="billing-rule-history"]')
    await expect(history).toContainText('第1版')
    await expect(history).toContainText('第2版')

    // 幂等：内容不变再存 → 「规则未发生变化」
    await card.locator('[data-test="billing-rule-save"]').click()
    await expect(page.locator('.el-message--info').last()).toContainText('规则未发生变化')
  })

  // ============ 链 2 · 账单全链（生成→下发→WA 确认→回款→结清） ============

  test('账单全链：ST 登录动线→补生成→下发→WA 确认→回款登记→已结清', async ({ page }) => {
    test.setTimeout(150_000)
    // 测试接缝：流水+首版规则回拨上月（详见 helpers/billing.ts 头注）
    backdateToLastMonth(seed.tenantId, wholesalerId)
    // 每日快照回补上月（按日下钻数据源；W2 recalc 端点 ≤昨日）
    const lastDay = new Date()
    lastDay.setDate(0) // 上月最后一日
    const to = `${lastDay.getFullYear()}-${String(lastDay.getMonth() + 1).padStart(2, '0')}-${String(lastDay.getDate()).padStart(2, '0')}`
    ok(await recalcSnapshots(stLogin.token, wholesalerId, `${month}-01`, to), '快照回补')

    // 结算员登录动线：UI 密码登录 → primaryRouter=/st/dashboard（注册码链路开通的真实 ST 账号）
    await loginAs(page, stPhone, SEED_PWD, /\/st\/dashboard/)
    await expect(page.locator('[data-test="st-month-cards"]')).toBeVisible()

    // 账单列表 → 补生成上月
    await page.goto('/st/bills')
    await page.locator('[data-test="bill-generate-btn"]').click()
    const genBox = page.locator('.el-message-box', { hasText: '补生成账单' })
    await expect(genBox).toContainText('请留意尾款账单')
    await genBox.getByRole('button', { name: '开始生成' }).click()
    await expect(page.locator('.el-message--success').last()).toContainText('已生成 1 张')

    // 列表出现待核对账单（BL- 单号 + 汇总卡联动）
    const row = page.locator('[data-test="bill-table"] tbody tr').first()
    await expect(row).toContainText('BL-')
    await expect(row).toContainText('待核对')

    // 记录 billId（API 侧取，稳定）
    const bills = ok(await listStBills(stLogin.token, { month }), 'ST 账单列表')
    billId = bills.records[0]?.id ?? ''
    expect(billId).toBeTruthy()

    // 详情：三金额卡 + 明细行 + 下发
    await page.goto(`/st/bills/${billId}`)
    await expect(page.locator('[data-test="bill-amount-cards"]')).toBeVisible()
    await expect(page.locator('[data-test="bill-items"] tbody tr').first()).toContainText('仓储费')
    await page.locator('[data-test="bill-dispatch"]').click()
    const dispatchBox = page.locator('.el-message-box', { hasText: '下发给批发商' })
    await dispatchBox.getByRole('button', { name: '确认下发' }).click()
    // 下发成功提示透出自动确认规则（PRD §3.2）
    await expect(page.locator('.el-message--success').last()).toContainText(
      '满 1 天未确认将自动进入待回款',
    )
    await expect(page.locator('[data-test="bill-status"]')).toContainText('已下发')

    // WA 端：确认对账
    await injectAuthAndGoto(page, wa.login, 'WA', '/wa/bills')
    const waRow = page.locator('[data-test="wa-bill-table"] tbody tr').first()
    await expect(waRow).toContainText('已下发')
    await page.locator('[data-test="wa-bill-row-open"]').first().click()
    await expect(page.locator('[data-test="wa-bill-status"]')).toContainText('已下发')
    await page.locator('[data-test="wa-bill-confirm"]').click()
    const confirmBox = page.locator('.el-message-box', { hasText: '确认对账' })
    // 线下付款口径提示（PRD §3.5 逐字）
    await expect(confirmBox).toContainText('确认后请按线下约定方式付款，仓库结算员收款后将登记回款')
    await confirmBox.getByRole('button', { name: '确认对账' }).click()
    await expect(page.locator('[data-test="wa-bill-status"]')).toContainText('待回款')

    // ST 端：回款登记（弹窗预填剩余应收 → 全额 → 已结清）
    await injectAuthAndGoto(page, stLogin, 'ST', `/st/bills/${billId}`)
    await expect(page.locator('[data-test="bill-status"]')).toContainText('待回款')
    await page.locator('[data-test="bill-pay-open"]').click()
    const payDialog = page.locator('[data-test="pay-dialog"]')
    await expect(payDialog).toBeVisible()
    await expect(payDialog).toContainText('不可超过剩余应收')
    await payDialog.locator('[data-test="pay-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('账单已结清')
    await expect(page.locator('[data-test="bill-status"]')).toContainText('已结清')
    // 回款记录留痕
    await expect(page.locator('[data-test="bill-payments"] tbody tr').first()).toContainText('银行转账')
  })

  // ============ 链 3 · 申诉链（WA 发起→ST 处理→双向留痕） ============

  test('申诉链：WA 行级申诉→ST resolve 成立→WA 端处理说明可见', async ({ page }) => {
    // WA 发起申诉（账单已结清但仍在 7 天窗内——申诉不冻结账单/不限回款态）
    await injectAuthAndGoto(page, wa.login, 'WA', `/wa/bills/${billId}`)
    await page.locator('[data-test="wa-dispute-open"]').click()
    const dlg = page.locator('[data-test="wa-dispute-dialog"]')
    await expect(dlg).toBeVisible()
    // 出库客诉边界说明（D43）
    await expect(dlg).toContainText('此处仅受理账单金额与计费争议')
    // 行级条目可选
    await dlg.locator('[data-test="wa-dispute-items"] .el-checkbox').first().click()
    await dlg.locator('[data-test="wa-dispute-reason"] textarea').fill('E2E 申诉：件·天数量与实际不符')
    await dlg.locator('[data-test="wa-dispute-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('申诉已提交')
    await expect(page.locator('[data-test="wa-dispute-item"]').first()).toContainText('待处理')

    // 重复申诉置灰（同账单单张待处理）
    const disputeBtn = page.locator('[data-test="wa-dispute-open"]')
    await expect(disputeBtn).toBeDisabled()

    // ST 处理：成立 + 说明
    await injectAuthAndGoto(page, stLogin, 'ST', '/st/disputes')
    const row = page.locator('[data-test="dispute-table"] tbody tr').first()
    await expect(row).toContainText('待处理')
    await page.locator('[data-test="dispute-resolve-open"]').first().click()
    const resolveDlg = page.locator('[data-test="dispute-resolve-dialog"]')
    await resolveDlg.locator('[data-test="dispute-resolution"] textarea').fill('E2E 核实：按快照复核属实，下期调整')
    await resolveDlg.locator('[data-test="dispute-resolve-submit"]').click()
    await expect(page.locator('.el-message--success').last()).toContainText('成立')

    // WA 端留痕（处理说明双向可见）
    await injectAuthAndGoto(page, wa.login, 'WA', `/wa/bills/${billId}`)
    await expect(page.locator('[data-test="wa-dispute-resolution"]').first()).toContainText(
      'E2E 核实：按快照复核属实',
    )
  })

  // ============ 链 4 · 精算下钻（按日视角） ============

  test('精算下钻：按日视角逐日快照行 + 口径帮助文案', async ({ page }) => {
    await injectAuthAndGoto(page, stLogin, 'ST', `/st/bills/${billId}`)
    await page.locator('[data-test="bill-view-switch"]').getByText('按日').click()
    const daily = page.locator('[data-test="bill-daily"] tbody tr')
    // 上月逐日（入库次日起算 → ≥27 行），首行 qty=10
    await expect(daily.first()).toBeVisible()
    expect(await daily.count()).toBeGreaterThanOrEqual(27)
    await expect(daily.first()).toContainText('10')
    // 每日口径帮助文案（PRD §2.1 逐字，页面明文）
    await expect(page.locator('.hint', { hasText: '每日计费件数按前一日 24:00' })).toBeVisible()
  })

  // ============ §8.11 · ST 三核心页 375 移动视口截图（含 R12 状态回退支线） ============

  test('375 移动视口：三核心页可用 + 截图（列表卡片流/详情吸底/回款全屏弹窗）', async ({ browser }) => {
    test.setTimeout(120_000)
    // R12 支线：冲销已结清账单的回款 → 状态回退（已结清 → 待回款），
    // 使回款登记弹窗在移动视口可真实打开（同时覆盖回款冲销链路）
    const detail = ok(await getStBill(stLogin.token, billId), 'ST 账单详情')
    const effective = detail.payments.find((p) => p.status === 'EFFECTIVE')
    if (!effective) throw new Error('[p4] 无有效回款记录可冲销')
    ok(await reversePaymentApi(stLogin.token, effective.id, 'E2E 冲销：误登记'), 'R12 回款冲销')
    const after = ok(await getStBill(stLogin.token, billId), '冲销后详情')
    expect(after.bill.status).toBe('PENDING_PAYMENT')

    const ctx = await browser.newContext({ viewport: { width: 375, height: 812 } })
    const page = await ctx.newPage()

    const noHScroll = async (p: Page) =>
      expect(
        await p.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
      ).toBe(true)

    // 1. 账单列表（卡片流 + 汇总条吸底）
    await injectAuthAndGoto(page, stLogin, 'ST', '/st/bills')
    await expect(page.locator('[data-test="bill-card"]').first()).toBeVisible()
    await expect(page.locator('[data-test="bill-summary-bar"]')).toBeVisible()
    await noHScroll(page)
    await page.waitForTimeout(600)
    await page.screenshot({ path: path.join(SCREEN_DIR, 'st-bills-375.png'), fullPage: true })

    // 2. 账单详情（明细卡 + 冲销划线回款 + 操作区吸底）
    await page.goto(`/st/bills/${billId}`)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('[data-test="bill-amount-cards"]')).toBeVisible()
    await expect(page.locator('[data-test="bill-status"]')).toContainText('待回款')
    await noHScroll(page)
    await page.waitForTimeout(600)
    await page.screenshot({ path: path.join(SCREEN_DIR, 'st-bill-detail-375.png'), fullPage: true })

    // 3. 回款登记弹窗（移动全屏化 §8.11-3）
    await page.locator('[data-test="bill-pay-open"]').click()
    const payDialog = page.locator('[data-test="pay-dialog"]')
    await expect(payDialog).toBeVisible()
    await expect(payDialog).toContainText('不可超过剩余应收')
    await noHScroll(page)
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(SCREEN_DIR, 'st-pay-register-375.png'), fullPage: true })

    await ctx.close()
  })
})
