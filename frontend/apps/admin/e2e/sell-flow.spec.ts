import { test, expect, type Page } from '@playwright/test'
import {
  seedSellChain,
  fetchRtStore,
  stockOfSku,
  apiConfirmInquiry,
  apiListInquiries,
  findInquiryByDocNo,
  uniqPhone,
  type SellSeed,
} from './helpers/sell'

/**
 * 仓储云 admin · phase-1 卖货整链 E2E（SELL-S1/S2/S6）
 *
 * 金字塔定位：E2E 做 S1 整链(UI 可观测) + 抽样 S2/S6；S4/S5/S7 已在后端 ScenarioTest 覆盖，不重复。
 *
 * 造数：全程 API 旁路（helpers/sell.ts）——TA 建仓→店铺码→WA 账号→自营商户→上架 SKU→
 *       WK 凭码注册→WK 入库(库存 N)。UI 只跑「买家下单 / 卖家确认」这段可观测主线。
 *
 * 前置：前端 5173 + 后端 8080 均在运行；mock 短信码 888888（dev）。
 *
 * 契约要点（见 helpers/sell.ts 顶注 + 交付说明）：
 *  - RT 进店 code = tenantSimpleCode；在售 SKU 需 listed=true 且库存 qty>0（入库后才可见）。
 *  - 询价单号前缀 XJ-（DocType.INQUIRY）；确认幂等冲突码 50285（INQUIRY_STATUS_INVALID）。
 *  - WA 会话：复用 WA 注册自动登录 token（二次 password 登录的 token 会被 sa-token 拒绝，41001）。
 *  - resolveRouter('WA') 后端返回 /ta/dashboard，但前端 defaultRouterFor('WA')=/wa/inquiry；
 *    故 WA 确认页由「注入 auth（primaryRole=WA）+ 直达 /wa/inquiry」驱动。
 */

/** RT 进店：等店铺加载出在售 SKU（浏览态渲染） */
async function openStoreWithSku(page: Page, seed: SellSeed): Promise<void> {
  await page.goto(`/rt/store?code=${seed.storeCode}`)
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.rt-header__title')).toContainText(/./, { timeout: 15_000 })
  // 至少一个在售 SKU（入库后 listed && qty>0）
  await expect(page.locator('.rt-sku').first()).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.rt-sku__stock').first()).toContainText(String(seed.stock))
}

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

// ============================== S1 整链（UI 主线） ==============================
test.describe('sell S1 happy', () => {
  test('SELL-S1-01 整链happy·RT 下单拿单号', async ({ page }) => {
    const seed = await seedSellChain()
    await openStoreWithSku(page, seed)

    // 步进器 +3
    const plus = page.locator('.rt-stepper__btn').last()
    await plus.click()
    await plus.click()
    await plus.click()
    await expect(page.locator('.rt-stepper__input')).toHaveValue('3')

    // 填手机号 → 提交
    await page.locator('.rt-phone').fill(uniqPhone())
    await page.locator('.rt-footer__submit').click()

    // 成功态：单号含 XJ-
    const no = page.locator('.rt-success__no')
    await expect(no).toBeVisible({ timeout: 15_000 })
    await expect(no).toContainText('XJ-')
  })

  test('SELL-S1-02 卖家确认转出库·库存扣减', async ({ page }) => {
    const seed = await seedSellChain()
    const qty = 3

    // --- RT 下单（UI）拿 docNo ---
    await openStoreWithSku(page, seed)
    const plus = page.locator('.rt-stepper__btn').last()
    for (let i = 0; i < qty; i++) await plus.click()
    await page.locator('.rt-phone').fill(uniqPhone())
    await page.locator('.rt-footer__submit').click()
    const noText = await page.locator('.rt-success__no').innerText({ timeout: 15_000 })
    const docNo = noText.replace(/[^A-Za-z0-9-]/g, '').match(/XJ-[A-Za-z0-9-]+/)?.[0]
    expect(docNo, `未从成功态解析出 XJ- 单号，原文=${noText}`).toBeTruthy()

    // --- WA 确认（UI 优先，API 兜底）---
    await enterWaInquiry(page, seed)

    // 定位到该单所在行（按 docNo 文案）
    const row = page.locator('.el-table__row', { hasText: docNo! })
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
      const target = await findInquiryByDocNo(seed.waLogin.token, docNo!)
      const res = await apiConfirmInquiry(target.id, seed.waLogin.token)
      expect(res.code, `API 兜底确认失败 msg=${res.message}`).toBe(0)
    }

    // --- 断言 1：该单状态最终为 COMPLETED（经 WA 列表回读，抗 UI 时序） ---
    await expect
      .poll(
        async () => {
          const rows = await apiListInquiries(seed.waLogin.token)
          return rows.find((r) => r.docNo === docNo)?.status
        },
        { timeout: 12_000, message: '询价单未变为 COMPLETED' },
      )
      .toBe('COMPLETED')

    // --- 断言 2：库存已扣减 N → N-qty（经公开 RT 店铺回读） ---
    const store = await fetchRtStore(seed.storeCode)
    expect(stockOfSku(store, seed.skuId)).toBe(seed.stock - qty)
  })
})

// ============================== S2 非法输入（UI 拦截） ==============================
test.describe('sell S2 invalid', () => {
  test('SELL-S2-01 非法输入·手机号格式错被前端拦截', async ({ page }) => {
    const seed = await seedSellChain()
    await openStoreWithSku(page, seed)

    // 先记录当前询价数（应保持不变）
    const before = await apiListInquiries(seed.waLogin.token)

    // 选量 2 + 填非法手机号 12345
    const plus = page.locator('.rt-stepper__btn').last()
    await plus.click()
    await plus.click()
    await page.locator('.rt-phone').fill('12345')
    await page.locator('.rt-footer__submit').click()

    // 前端 PHONE_RE 拦截 → ElMessage.warning「请输入正确的 11 位手机号」，不进入成功态
    await expect(page.getByText('请输入正确的 11 位手机号').first()).toBeVisible({ timeout: 8_000 })
    await expect(page.locator('.rt-success__no')).toHaveCount(0)

    // 不产生询价：WA 列表数量不变
    await page.waitForTimeout(500)
    const after = await apiListInquiries(seed.waLogin.token)
    expect(after.length).toBe(before.length)
  })

  test('SELL-S2-01b 非法输入·未选数量提交按钮禁用', async ({ page }) => {
    const seed = await seedSellChain()
    await openStoreWithSku(page, seed)

    // 未选任何数量 → 提交按钮 disabled（selectedCount===0），无法产生询价
    await page.locator('.rt-phone').fill(uniqPhone())
    await expect(page.locator('.rt-footer__submit')).toBeDisabled()
  })
})

// ============================== S6 重复确认幂等 ==============================
test.describe('sell S6 idempotency', () => {
  test('SELL-S6-01 重复确认·第二次被拒且库存只扣一次', async ({ page }) => {
    const seed = await seedSellChain()
    const qty = 5

    // RT 下单（UI）
    await openStoreWithSku(page, seed)
    const plus = page.locator('.rt-stepper__btn').last()
    for (let i = 0; i < qty; i++) await plus.click()
    await page.locator('.rt-phone').fill(uniqPhone())
    await page.locator('.rt-footer__submit').click()
    const noText = await page.locator('.rt-success__no').innerText({ timeout: 15_000 })
    const docNo = noText.replace(/[^A-Za-z0-9-]/g, '').match(/XJ-[A-Za-z0-9-]+/)?.[0]
    expect(docNo).toBeTruthy()

    const target = await findInquiryByDocNo(seed.waLogin.token, docNo!)

    // 第一次确认 → 成功 COMPLETED
    const c1 = await apiConfirmInquiry(target.id, seed.waLogin.token)
    expect(c1.code, `首次确认应成功 msg=${c1.message}`).toBe(0)
    expect(c1.data.status).toBe('COMPLETED')

    // 第二次确认 → 被状态机拒绝（50285）
    const c2 = await apiConfirmInquiry(target.id, seed.waLogin.token)
    expect(c2.code).toBe(50285)

    // 库存只扣一次：N → N-qty（而非 N-2*qty）
    const store = await fetchRtStore(seed.storeCode)
    expect(stockOfSku(store, seed.skuId)).toBe(seed.stock - qty)
  })
})
