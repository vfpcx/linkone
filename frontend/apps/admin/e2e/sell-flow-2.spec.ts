import { test, expect } from '@playwright/test'
import {
  seedSellChain,
  apiSubmitInquiry,
  apiConfirmInquiry,
  apiCreateInvite,
  apiRegisterOnce,
  fetchRtStore,
  stockOfSku,
  uniqPhone,
  SMS_CODE,
  SEED_PWD,
} from './helpers/sell'

/**
 * 仓储云 admin · phase-1 业务旅程 E2E（API 契约兜底）
 *
 * 迁移说明（E2E 基线迁 uni）：买家（RT）UI 可观测用例已由 admin 内 RT 最小 H5 过渡态
 * 迁至 RT 正式端 uni H5（e2e/rt-h5/）：
 *  - B-RT-02 卖光空态        → rt-journeys.spec.ts UNI-RT-B02
 *  - B-RT-03 步进钳制 UI 佐证 → rt-journeys.spec.ts UNI-RT-B03
 *  - B-RT-07 空店空态        → rt-journeys.spec.ts UNI-RT-B07
 * 本文件只保留纯 API 契约用例：
 *  - B-RT-03 缺货下单主旅程（API 造超量询价 → WA 确认 50251 → 库存不扣）
 *  - B-WA-04 越权确认（B 家 WA 确认 A 家询价被拒）
 *  - B-EMP-02 员工离职（maxUses=1 码用尽后再注册被拒 41303）
 *
 * 造数全程复用 helpers/sell.ts（API 旁路），遵守 05-secure-coding-guardrails：
 * 归属由后端登录态/店铺码推导，前端不伪造 tenantId（G-2.1）；会话走真实 token（G-1.x）。
 */

// ============================== B-RT-03 缺货下单旅程（API 主旅程） ==============================
// 前端 stepper 上限=库存（UI 佐证已迁 uni-rt UNI-RT-B03），UI 无法选“超量”，
// 故“询价量>库存→确认失败”这段只能在 API 层复现（题面明确允许）。
test.describe('journey B-RT-03 over-order shortage', () => {
  test('B-RT-03 询价量>库存·WA 确认失败(50251)且库存不扣', async () => {
    const seed = await seedSellChain(5) // 库存 5

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

// ============================== B-WA-04 越权确认（API） ==============================
// 越权判定是后端 requireWaRole 的鉴权行为，UI 上 B 家 WA 的确认页根本列不到 A 家询价
// （列表按归属过滤），无“可点的越权入口”，故直接以 API 断言最能刻画规约。
test.describe('journey B-WA-04 cross-wholesaler confirm forbidden', () => {
  test('B-WA-04 B家WA 确认 A家询价·被拒(50284/50286)', async () => {
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
    // B 的租户上下文根本读不到 A 的询价单，比 50286 更早、更严地挡住越权。两者皆为合法拒绝。
    const cross = await apiConfirmInquiry(sub.data.id, b.waLogin.token)
    expect(cross.code, `越权确认应被拒，实际 code=${cross.code}`).not.toBe(0)
    expect([50284, 50286], `越权拒绝码应为租户隔离/非本商户WA，实际 ${cross.code}`).toContain(
      cross.code,
    )

    // 反证：A 家询价仍可正常确认、库存按量扣减
    const own = await apiConfirmInquiry(sub.data.id, a.waLogin.token)
    expect(own.code, `A 家 WA 应能正常确认自己的单 msg=${own.message}`).toBe(0)
    const store = await fetchRtStore(a.storeCode)
    expect(stockOfSku(store, a.skuId)).toBe(10 - 2)
  })
})

// ============================== B-EMP-02 员工离职（API） ==============================
// 注册码作废/用尽后“前员工再注册被拒”是账户/邀请码鉴权语义，无 UI 旅程可观测，API 级断言即可。
test.describe('journey B-EMP-02 offboarded employee cannot re-register', () => {
  test('B-EMP-02 maxUses=1 码用尽后·再注册被拒(41303)', async () => {
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
