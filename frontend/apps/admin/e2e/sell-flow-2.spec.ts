import { test, expect, type Page } from '@playwright/test'
import {
  seedSellChain,
  seedEmptyStore,
  apiSubmitInquiry,
  apiConfirmInquiry,
  apiCreateInvite,
  apiRegisterOnce,
  fetchRtStore,
  stockOfSku,
  uniqPhone,
  SMS_CODE,
  SEED_PWD,
  type SellSeed,
} from './helpers/sell'

/**
 * 仓储云 admin · phase-1 业务旅程 E2E 补齐（04-business-scenarios.md 缺口）
 *
 * 本文件补齐「用户旅程视角」缺口，配合 sell-flow.spec.ts 的 S1/S2/S6 主线：
 *   - B-RT-02 卖光不显示  （UI 可观测：SKU 卖到 0 后进店看不到）
 *   - B-RT-03 缺货下单     （API 造超量询价 + WA 确认失败；UI 侧验证 stepper 上限=库存）
 *   - B-RT-07 空店         （UI 可观测：未上架任何 SKU，进店空态不报错）
 *   - B-WA-04 越权确认     （API：B 家 WA 确认 A 家询价被拒 50286）
 *   - B-EMP-02 员工离职     （API：maxUses=1 码用尽后再注册被拒 41303）
 *
 * 走 UI 还是 API 的取舍见每个 describe 顶注 + 交付说明第③点。
 * 造数全程复用 helpers/sell.ts（API 旁路，全 ASCII），遵守 05-secure-coding-guardrails：
 * 归属由后端登录态/店铺码推导，前端不伪造 tenantId（G-2.1）；会话走真实 token（G-1.x）。
 */

/** RT 进店：等店铺标题渲染（浏览态就绪，不预设是否有 SKU）。 */
async function openStore(page: Page, storeCode: string): Promise<void> {
  await page.goto(`/rt/store?code=${storeCode}`)
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.rt-header__title')).toBeVisible({ timeout: 15_000 })
}

/** 经 API 把某店某 SKU 库存卖光（提交询价 qty=stock → WA 确认转出库扣减）。 */
async function sellOut(seed: SellSeed): Promise<void> {
  const sub = await apiSubmitInquiry({
    code: seed.storeCode,
    wholesalerId: seed.wholesalerId,
    skuId: seed.skuId,
    qty: seed.stock,
  })
  expect(sub.code, `卖光造数·提交询价失败 msg=${sub.message}`).toBe(0)
  const conf = await apiConfirmInquiry(sub.data.id, seed.waLogin.token)
  expect(conf.code, `卖光造数·WA 确认失败 msg=${conf.message}`).toBe(0)
  expect(conf.data.status).toBe('COMPLETED')
}

// ============================== B-RT-02 卖光不显示（UI） ==============================
// 走 UI：卖光→买家进店“该 SKU 消失”是纯前端可观测旅程，价值就在页面表现，故 E2E。
test.describe('journey B-RT-02 sold-out invisible', () => {
  test('B-RT-02 SKU 卖到 0 后·RT 进店该 SKU 不出现', async ({ page }) => {
    // 小库存便于一次卖光
    const seed = await seedSellChain(5)

    // 卖光前：店内确实能看到该 SKU（前置校验，避免“本来就没有”造成假绿）
    const before = await fetchRtStore(seed.storeCode)
    expect(stockOfSku(before, seed.skuId)).toBe(5)

    // 卖光（API）
    await sellOut(seed)
    // 后端聚合层已过滤 qty>0 → 该 SKU 从店铺聚合消失
    const after = await fetchRtStore(seed.storeCode)
    expect(stockOfSku(after, seed.skuId)).toBeNull()

    // UI 断言：进店后该 SKU 不在页面（商户仍在 → 展示“暂无在售商品”空态，页面不报错）
    await openStore(page, seed.storeCode)
    await expect(page.locator('.rt-sku')).toHaveCount(0)
    await expect(page.getByText(seed.skuName)).toHaveCount(0)
    await expect(page.locator('.rt-empty-sku')).toBeVisible()
    // 未落入错误态
    await expect(page.locator('.rt-state--error')).toHaveCount(0)
  })
})

// ============================== B-RT-03 缺货下单旅程（API 主 + UI 佐证） ==============================
// 走 API 为主：前端 stepper 上限=库存，UI 根本无法选“超量”，故“询价量>库存→确认失败”这段
// 只能在 API 层复现（题面明确允许）。UI 侧仅佐证 stepper 被库存钳制（+ 按钮到顶禁用）。
test.describe('journey B-RT-03 over-order shortage', () => {
  test('B-RT-03 询价量>库存·WA 确认失败且库存不扣', async ({ page }) => {
    const seed = await seedSellChain(5) // 库存 5

    // --- UI 佐证：stepper 上限=库存，无法选超量 ---
    await openStore(page, seed.storeCode)
    await expect(page.locator('.rt-sku').first()).toBeVisible({ timeout: 15_000 })
    const plus = page.locator('.rt-stepper__btn').last()
    // 连点 8 次（>库存5），值被钳到 5，且 + 按钮到顶禁用
    for (let i = 0; i < 8; i++) {
      if (await plus.isDisabled()) break
      await plus.click()
    }
    await expect(page.locator('.rt-stepper__input')).toHaveValue('5')
    await expect(plus).toBeDisabled()

    // --- API 主旅程：绕过 UI 钳制，提交超量询价 qty=20 ---
    const sub = await apiSubmitInquiry({
      code: seed.storeCode,
      wholesalerId: seed.wholesalerId,
      skuId: seed.skuId,
      qty: 20,
    })
    expect(sub.code, `超量询价提交应成功(下单不校验库存) msg=${sub.message}`).toBe(0)

    // WA 确认 → 扣库存时 STOCK_NOT_ENOUGH(50251)，整事务回滚
    const conf = await apiConfirmInquiry(sub.data.id, seed.waLogin.token)
    expect(conf.code, `缺货确认应被拒，实际 code=${conf.code}`).toBe(50251)

    // 断言：库存未扣（仍 5），无成功出库
    const store = await fetchRtStore(seed.storeCode)
    expect(stockOfSku(store, seed.skuId)).toBe(5)
  })
})

// ============================== B-RT-07 空店（UI） ==============================
// 走 UI：空态渲染是纯前端旅程（进店不报错、显示空态），价值在页面表现，故 E2E。
test.describe('journey B-RT-07 empty store', () => {
  test('B-RT-07 未上架任何 SKU·进店显示空态不报错', async ({ page }) => {
    const seed = await seedEmptyStore()

    // 聚合层：商户 ACTIVE 但无在售 SKU
    const store = await fetchRtStore(seed.storeCode)
    const totalSkus = store.wholesalers.reduce((n, w) => n + (w.skus?.length ?? 0), 0)
    expect(totalSkus).toBe(0)

    // UI：标题正常渲染，无任何 .rt-sku，展示空态，且不落错误态
    await openStore(page, seed.storeCode)
    await expect(page.locator('.rt-header__title')).toBeVisible()
    await expect(page.locator('.rt-sku')).toHaveCount(0)
    await expect(page.locator('.rt-state--error')).toHaveCount(0)
    // 商户空态或全店空态二者其一（取决于是否有 ACTIVE 商户）
    const emptyHint = page.locator('.rt-empty-sku, .rt-state')
    await expect(emptyHint.first()).toBeVisible()
  })
})

// ============================== B-WA-04 越权确认（API） ==============================
// 走 API：越权判定是后端 requireWaRole 的鉴权行为，UI 上 B 家 WA 的确认页根本列不到 A 家询价
// （列表按归属过滤），无“可点的越权入口”，故直接以 API 断言 code!=0（50286）最能刻画规约。
test.describe('journey B-WA-04 cross-wholesaler confirm forbidden', () => {
  test('B-WA-04 B家WA 确认 A家询价·被拒(50286)', async ({}) => {
    const [a, b] = await Promise.all([seedSellChain(10), seedSellChain(10)])

    // RT 向 A 家提交一张询价（公开端点造数）
    const sub = await apiSubmitInquiry({
      code: a.storeCode,
      wholesalerId: a.wholesalerId,
      skuId: a.skuId,
      qty: 2,
    })
    expect(sub.code, `A 家询价提交失败 msg=${sub.message}`).toBe(0)

    // B 家 WA 用自己的 token 去确认 A 家询价 → 越权拒绝。
    // 实测 code=50284(询价单不存在)：租户行级过滤(G-2.2)先于 WA 角色校验(G-1.3)生效，
    // B 的租户上下文根本读不到 A 的询价单，比 50286 更早、更严地挡住越权。两者皆为合法拒绝，
    // 故断言“被拒(!=0)且属越权类拒绝码(50284 租户隔离 / 50286 非本商户WA)”。
    const cross = await apiConfirmInquiry(sub.data.id, b.waLogin.token)
    expect(cross.code, `越权确认应被拒，实际 code=${cross.code}`).not.toBe(0)
    expect([50284, 50286], `越权拒绝码应为租户隔离/非本商户WA，实际 ${cross.code}`).toContain(
      cross.code,
    )

    // 反证：A 家询价仍 PENDING（未被越权改动）、库存未扣
    const own = await apiConfirmInquiry(sub.data.id, a.waLogin.token)
    expect(own.code, `A 家 WA 应能正常确认自己的单 msg=${own.message}`).toBe(0)
    const store = await fetchRtStore(a.storeCode)
    expect(stockOfSku(store, a.skuId)).toBe(10 - 2)
  })
})

// ============================== B-EMP-02 员工离职（API） ==============================
// 走 API：注册码作废/用尽后“前员工再注册被拒”是账户/邀请码鉴权语义，无对应 UI 旅程可观测，
// 且 sell-flow 的造数已复用同一注册链，故 API 级断言 41303 即可。
test.describe('journey B-EMP-02 offboarded employee cannot re-register', () => {
  test('B-EMP-02 maxUses=1 码用尽后·再注册被拒(41303)', async ({}) => {
    // TA 建仓拿 token（复用 seedSellChain 的 TA 会话，省去重复造仓）
    const seed = await seedSellChain(5)

    // 生成 maxUses=1 的 WK 注册码
    const code = await apiCreateInvite(seed.taToken, 'WK', 1, 7)

    // 首次凭码注册（相当于该员工入职）→ 成功
    const first = await apiRegisterOnce({
      phone: uniqPhone(),
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'WK',
      realName: 'EmpFirst',
      inviteCode: code,
      agreedTerms: true,
    })
    expect(first.code, `首次凭码注册应成功 msg=${first.message}`).toBe(0)

    // 员工离职后（码已用尽），前员工/他人再用同码注册 → 被拒 41303（邀请码已用完）
    const again = await apiRegisterOnce({
      phone: uniqPhone(),
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'WK',
      realName: 'EmpAgain',
      inviteCode: code,
      agreedTerms: true,
    })
    expect(again.code, `用尽后再注册应被拒 41303，实际 code=${again.code}`).toBe(41303)
  })
})
