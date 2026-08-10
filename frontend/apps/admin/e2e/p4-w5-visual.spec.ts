/**
 * P4 W5c 终验收 · 视觉矩阵截图（供 Team Lead / 用户亲检，非断言型用例）
 *
 * 覆盖：
 *  1) 未登录三页 login/register/forgot-password × 两档窄屏（390×844 / 375×667）
 *  2) P4 新页 @1280×800：
 *     TA settings（计费规则区块）/ bills-overview（账单总览）、
 *     ST dashboard / bills / bill 详情（争议中+部分回款）/ disputes、
 *     WA bills
 *  3) ST 三页 @375×667（结算员窄屏适配）：dashboard / bills / bill 详情
 *
 * 产物：<repo>/.e2e-tmp/p4-w5-visual/*.png
 * 前置：后端 8080（dev,local）+ 前端 dev server 5173，同 e2e/README。
 * 造数：w5b 同款接缝（流水/规则回拨上月 → 补生成上月账单）＋下发＋部分回款＋WA 争议，
 *       使详情/争议/总览页均为非空真实状态。
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
  apiPost,
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
  dispatchBill,
  confirmWaBill,
  registerPaymentApi,
  getStBill,
} from './helpers/billing'

// cwd = frontend/apps/admin（pnpm --filter @cangchu/admin exec playwright test）
const OUT_DIR = path.resolve(process.cwd(), '../../../.e2e-tmp/p4-w5-visual')

test.beforeAll(() => {
  fs.mkdirSync(OUT_DIR, { recursive: true })
})

/** 动画/骨架静置后整页截图 */
async function shot(page: Page, name: string): Promise<void> {
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(900) // 入场动画静置（FE-W1 教训）
  await page.screenshot({ path: path.join(OUT_DIR, `${name}.png`), fullPage: true })
}

// ============ 1. 未登录三页 × 两档窄屏 ============

const AUTH_PAGES: Array<[string, string]> = [
  ['/login', 'login'],
  ['/register', 'register'],
  ['/forgot-password', 'forgot'],
]
const VIEWPORTS: Array<[number, number]> = [
  [390, 844],
  [375, 667],
]

test.describe('P4W5 视觉矩阵 · 未登录页', () => {
  for (const [route, name] of AUTH_PAGES) {
    for (const [w, h] of VIEWPORTS) {
      test(`${name} @${w}x${h}`, async ({ page }) => {
        await page.setViewportSize({ width: w, height: h })
        await page.goto(route)
        await expect(page.locator('.auth-card, form').first()).toBeVisible({ timeout: 15_000 })
        await shot(page, `${name}-${w}x${h}`)
      })
    }
  }
})

// ============ 2. P4 新页（共享一次造数，串行） ============

test.describe.serial('P4W5 视觉矩阵 · P4 计费页', () => {
  let seed: TenantSeed
  let wa: WaSeed
  let stLogin: LoginData
  let billId = ''
  const month = lastMonth()

  test.beforeAll(async () => {
    test.setTimeout(240_000)
    // ACTIVE 租户 + WA 入驻 + 库存（10 件）——w5b 同款
    seed = await seedActiveTenant()
    wa = await registerWaWithTarget(seed.tenantId)
    const apps = await listTaApplications(seed.ta.login.token)
    const app = apps.find((a) => a.name === wa.wholesalerName)
    if (!app) throw new Error('[p4-visual] 未找到入驻申请')
    const audit = ok(await auditApplication(seed.ta.login.token, app.id, 'APPROVED'), 'TA 审批入驻')
    const wholesalerId = audit.wholesalerId ?? ''
    if (!wholesalerId) throw new Error('[p4-visual] 审批未返回 wholesalerId')
    await seedStockForWholesaler(seed.ta.login.token, wholesalerId, 10)
    await confirmPendingInbound(wa.login.token)
    // 结算员 + 首版规则（件·天 0.5 元）
    stLogin = (await registerStEmployee(seed.ta.login.token)).login
    ok(
      await saveRule(seed.ta.login.token, {
        billingByQty: true,
        pricePerQtyDay: 0.5,
        billingByPallet: false,
      }),
      '首存计费规则',
    )
    // 接缝：回拨上月 → 补生成上月账单
    backdateToLastMonth(seed.tenantId, wholesalerId)
    const gen = ok(await generateBills(stLogin.token, month), '补生成账单')
    billId = gen.billIds?.[0] ?? ''
    if (!billId) {
      const bills = ok(await listStBills(stLogin.token, { month }), 'ST 账单列表')
      billId = bills.records[0]?.id ?? ''
    }
    if (!billId) throw new Error('[p4-visual] 未生成账单')
    // 下发 → WA 确认（回款前置态）→ 部分回款 → WA 就首条明细发起争议（详情/争议/总览页非空态）
    ok(await dispatchBill(stLogin.token, billId), '下发账单')
    ok(await confirmWaBill(wa.login.token, billId), 'WA 确认对账')
    ok(await registerPaymentApi(stLogin.token, billId, 1), '登记部分回款')
    const detail = ok(await getStBill(stLogin.token, billId), 'ST 账单详情')
    const firstItemId = detail.items?.[0]?.id
    ok(
      await apiPost(
        `/wholesaler/bills/${billId}/dispute`,
        {
          reason: 'P4W5 视觉矩阵造数：对首条明细金额有异议',
          disputedItemIds: firstItemId ? [firstItemId] : [],
        },
        wa.login.token,
      ),
      'WA 发起争议',
    )
  })

  // ---- @1280×800 ----
  const DESKTOP: Array<{ name: string; role: 'TA' | 'ST' | 'WA'; path: () => string }> = [
    { name: 'ta-settings-billing', role: 'TA', path: () => '/ta/settings' },
    { name: 'ta-bills-overview', role: 'TA', path: () => '/ta/bills-overview' },
    { name: 'st-dashboard', role: 'ST', path: () => '/st/dashboard' },
    { name: 'st-bills', role: 'ST', path: () => '/st/bills' },
    { name: 'st-bill-detail', role: 'ST', path: () => `/st/bills/${billId}` },
    { name: 'st-disputes', role: 'ST', path: () => '/st/disputes' },
    { name: 'wa-bills', role: 'WA', path: () => '/wa/bills' },
  ]

  for (const c of DESKTOP) {
    test(`${c.name} @1280`, async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 800 })
      const login = c.role === 'TA' ? seed.ta.login : c.role === 'ST' ? stLogin : wa.login
      await injectAuthAndGoto(page, login, c.role, c.path())
      if (c.name === 'ta-settings-billing') {
        // 滚到计费规则区块，确保区块入图（fullPage 全高仍截整页）
        await page
          .locator('text=计费规则')
          .first()
          .scrollIntoViewIfNeeded()
          .catch(() => undefined)
      }
      await shot(page, c.name)
    })
  }

  // ---- ST 三页 @375×667 ----
  const ST_MOBILE: Array<{ name: string; path: () => string }> = [
    { name: 'st-dashboard-375', path: () => '/st/dashboard' },
    { name: 'st-bills-375', path: () => '/st/bills' },
    { name: 'st-bill-detail-375', path: () => `/st/bills/${billId}` },
  ]

  for (const c of ST_MOBILE) {
    test(`${c.name}`, async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 })
      await injectAuthAndGoto(page, stLogin, 'ST', c.path())
      await shot(page, c.name)
      if (c.name === 'st-bill-detail-375') {
        // 详情页有吸底操作栏：fullPage 截图会把 sticky 栏画在滚动位（盖住回款行），
        // 追加「滚动到回款记录的视口截图」还原真实用户视野以供甄别
        await page.locator('text=回款记录').first().scrollIntoViewIfNeeded()
        await page.waitForTimeout(500)
        await page.screenshot({
          path: path.join(OUT_DIR, 'st-bill-detail-375-payments-viewport.png'),
          fullPage: false,
        })
        // 甄别吸底栏遮挡：滚到页底后回款行应可见于吸底栏之上（页底 padding 是否足额）
        await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
        await page.waitForTimeout(500)
        await page.screenshot({
          path: path.join(OUT_DIR, 'st-bill-detail-375-bottom-viewport.png'),
          fullPage: false,
        })
      }
    })
  }
})
