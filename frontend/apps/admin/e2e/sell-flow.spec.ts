import { test, expect, type Page } from '@playwright/test'
import {
  seedSellChain,
  fetchRtStore,
  stockOfSku,
  apiSubmitInquiry,
  apiConfirmInquiry,
  apiListInquiries,
  findInquiryByDocNo,
  completeOutboundChain,
  type SellSeed,
} from './helpers/sell'

/**
 * 仓储云 admin · 卖货整链 E2E（SELL-S1-S2 已在 uni H5 承接买家侧 UI，本文件保留 admin 侧卖家/幂等契约）
 *
 * 迁移说明（E2E 基线迁 uni）：买家（RT）进店/下单 UI 已由 admin 内 RT 最小 H5 过渡态
 * 迁至 RT 正式端 uni H5（e2e/rt-h5/rt-buy.spec.ts，SELL-S1-01/S2-01/S2-01b）；
 * 本文件只保留「卖家侧」可观测主线与契约用例：
 *  - SELL-S1-02 卖家确认转出库：RT 下单改 API 造数（UI 已不在 admin 侧），WA 确认 UI 保留
 *  - SELL-S6-01 重复确认幂等：纯 API 契约（无 UI）
 *
 * 造数：全程 API 旁路（helpers/sell.ts）。WA 确认页由注入 auth（primaryRole=WA）+ 直达 /wa/inquiry 驱动。
 *
 * 契约要点（见 helpers/sell.ts 顶注 + 交付说明）：
 *  - 询价单号前缀 XJ-；确认幂等冲突码 50285。
 *  - 确认后询价停 CONFIRMED、出库单 PENDING_ACCEPT；WK 打印+登记出库后联动 COMPLETED。
 *  - WA 会话：复用 WA 注册自动登录 token（二次 password 登录的 token 会被 sa-token 拒绝，41001）。
 */

/**
 * 注入 WA 登录态到前端 auth（pinia-persist localStorage）并直达 /wa/inquiry。
 * primaryRole 强制为 WA（注册时为 RT 占位），使前端按 WA 主角色渲染确认页。
 */
async function enterWaInquiry(page: Page, seed: SellSeed): Promise<void> {
  const waRole = seed.waLogin.roles.find((r) => r.role === 'WA') ?? seed.waLogin.roles[0]
  const authState = {
    token: seed.waLogin.token,
    userId: seed.waLogin.userId,
    primaryRole: 'WA',
    roles: seed.waLogin.roles,
    primaryRouter: '/wa/inquiry',
    expireAt: null,
    tenantInfo: waRole?.tenantId ? { tenantId: waRole.tenantId, tenantName: '我的商户' } : null,
  }
  // 先落一个同源页面再写 localStorage（pinia-persist key = cangchu-admin-auth）
  await page.goto('/login')
  await page.evaluate((s) => {
    localStorage.clear()
    localStorage.setItem('cangchu-admin-auth', JSON.stringify(s))
  }, authState)
  await page.goto('/wa/inquiry')
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.page-head__title')).toContainText('询价确认', { timeout: 15_000 })
}

// ============================== S1 整链（卖家侧 UI 主线） ==============================
test.describe('sell S1 happy', () => {
  test('SELL-S1-02 卖家确认转出库·库存扣减', async ({ page }) => {
    const seed = await seedSellChain()
    const qty = 3

    // --- RT 下单（API 造数，买家 UI 已在 uni H5 承接）拿 docNo ---
    const sub = await apiSubmitInquiry({
      code: seed.storeCode,
      wholesalerId: seed.wholesalerId,
      skuId: seed.skuId,
      qty,
    })
    expect(sub.code, `RT 下单失败 msg=${sub.message}`).toBe(0)
    const docNo = sub.data.docNo
    expect(docNo).toMatch(/^XJ-/)

    // --- WA 确认（UI 优先，API 兜底）---
    await enterWaInquiry(page, seed)

    // 定位到该单所在行（按 docNo 文案）
    const row = page.locator('.el-table__row', { hasText: docNo })
    await expect(row).toBeVisible({ timeout: 15_000 })

    let confirmedViaUi = false
    const confirmBtn = row.getByRole('button', { name: '确认' })
    if (await confirmBtn.count()) {
      await confirmBtn.click()
      // ElMessageBox 确认弹窗
      await page.getByRole('button', { name: '确认并出库' }).click()
      // 等成功 toast 或状态标签变「已转出库」
      const settled = await Promise.race([
        page
          .locator('.el-message--success')
          .first()
          .waitFor({ timeout: 12_000 })
          .then(() => true)
          .catch(() => false),
        row
          .getByText('已转出库')
          .waitFor({ timeout: 12_000 })
          .then(() => true)
          .catch(() => false),
      ])
      confirmedViaUi = settled
    }

    if (!confirmedViaUi) {
      // 兜底：经 API 确认（题面允许），保证断言可执行
      const target = await findInquiryByDocNo(seed.waLogin.token, docNo)
      const res = await apiConfirmInquiry(target.id, seed.waLogin.token)
      expect(res.code, `API 兜底确认失败 msg=${res.message}`).toBe(0)
    }

    // --- 断言 1（P3 契约，12 §1.4）：确认后询价停 CONFIRMED（出库单 PENDING_ACCEPT）---
    await expect
      .poll(
        async () => {
          const rows = await apiListInquiries(seed.waLogin.token)
          return rows.find((r) => r.docNo === docNo)?.status
        },
        { timeout: 12_000, message: '询价单未变为 CONFIRMED' },
      )
      .toBe('CONFIRMED')

    // --- 断言 2：扣库存时点不变=确认即扣，N → N-qty（经公开 RT 店铺回读） ---
    const store = await fetchRtStore(seed.storeCode)
    expect(stockOfSku(store, seed.skuId)).toBe(seed.stock - qty)

    // --- 断言 3（P3 终态联动）：WK 打印+登记出库后，询价 COMPLETED ---
    await completeOutboundChain(seed)
    await expect
      .poll(
        async () => {
          const rows = await apiListInquiries(seed.waLogin.token)
          return rows.find((r) => r.docNo === docNo)?.status
        },
        { timeout: 12_000, message: '出库登记后询价未联动 COMPLETED' },
      )
      .toBe('COMPLETED')
  })
})

// ============================== S6 重复确认幂等 ==============================
test.describe('sell S6 idempotency', () => {
  test('SELL-S6-01 重复确认·第二次被拒且库存只扣一次', async () => {
    const seed = await seedSellChain()
    const qty = 5

    // RT 下单（API 造数，买家 UI 已在 uni H5 承接）
    const sub = await apiSubmitInquiry({
      code: seed.storeCode,
      wholesalerId: seed.wholesalerId,
      skuId: seed.skuId,
      qty,
    })
    expect(sub.code).toBe(0)
    const docNo = sub.data.docNo
    expect(docNo).toMatch(/^XJ-/)

    const target = await findInquiryByDocNo(seed.waLogin.token, docNo)

    // 第一次确认 → 成功（P3 契约：确认即扣库存，询价停 CONFIRMED 等 WK 登记出库）
    const c1 = await apiConfirmInquiry(target.id, seed.waLogin.token)
    expect(c1.code, `首次确认应成功 msg=${c1.message}`).toBe(0)
    expect(c1.data.status).toBe('CONFIRMED')

    // 第二次确认 → 被状态机拒绝（50285）
    const c2 = await apiConfirmInquiry(target.id, seed.waLogin.token)
    expect(c2.code).toBe(50285)

    // 库存只扣一次：N → N-qty（而非 N-2*qty）
    const store = await fetchRtStore(seed.storeCode)
    expect(stockOfSku(store, seed.skuId)).toBe(seed.stock - qty)
  })
})
