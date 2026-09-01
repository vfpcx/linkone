/**
 * P5-A W4/W5 · 店铺撮合运营（主推商品 + 置顶批发商）E2E（p5a-storefront-featured.spec.ts）
 *
 * 覆盖（14-p5a-e2e-cases.md 撮合链 P5A-FE-*）：
 *  1) FE-01 撮合配置 UI 全流程：TA 店铺设置「撮合运营」卡空态 → el-select 添加主推商品 / 置顶批发商 →
 *     保存成功 → API 回显一致
 *  2) FE-02 RT 店铺页（公开路由 /rt/store?code=）展示「主推」「置顶」标识 + 前置排序（API+UI 双断言）
 *  3) FE-03 上限校验：主推 >20 → 50711；置顶 >5 → 50712
 *  4) FE-04 重复条目 → 50713
 *  5) FE-05 非本店在售 SKU / 非本店入驻批发商 → 50714
 *  6) FE-06 覆盖写幂等 + 顺序随数组更新
 *  7) FE-07 非 TA 角色（WA）访问撮合配置 → 42101
 *
 * 前置：后端 8080（main ≥ 4fc717b，含 b2cb572 撮合接口）+ 前端 dev server（W4 6f0ca67）。
 * 造数：seedActiveTenant（ACTIVE 租户）+ registerWaWithTarget（WA 直申）→ TA 审批 →
 *       seedStockForWholesaler（SKU + 库存）→ WA 确认入库（PENDING_WA_CONFIRM 闭环后 RT 才可见）。
 *
 * 断言契约：backend .../tenant/controller/StorefrontFeatureController.java（50711-50714、42101）；
 *           RT 店铺页聚合 backend .../storefront/service/impl/StoreFrontServiceImpl.java。
 */

import { test, expect } from '@playwright/test'
import path from 'node:path'
import fs from 'node:fs'
import {
  seedActiveTenant,
  registerWaWithTarget,
  listTaApplications,
  seedStockForWholesaler,
  confirmPendingInbound,
  apiGet,
  apiPost,
  apiLogin,
  ok,
  type LoginData,
  type WaSeed,
  injectAuthAndGoto,
} from './helpers/onboarding'

/** 将字符串转义为 RegExp 字面量（防 wa 名称含 . * 等正则元字符误匹配） */
const reLiteral = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'

const SCREEN_DIR = path.resolve(process.cwd(), 'test-results/screens')
test.beforeAll(() => {
  fs.mkdirSync(SCREEN_DIR, { recursive: true })
})

/** PUT（helper 未导出 apiPut，按 call 同款契约本地实现） */
const apiPut = async <T>(path: string, body?: unknown, token?: string) => {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) {
    headers['Authorization'] = token
    headers['satoken'] = token
  }
  const res = await fetch(`${API}/api/v1${path}`, {
    method: 'PUT',
    headers,
    body: body != null ? JSON.stringify(body) : undefined,
  })
  return (await res.json()) as { code: number; message?: string; data: T }
}

// ============ 契约类型（与后端 VO 对齐；id 均为字符串序列化） ============

interface StoreSkuRow {
  skuId: string
  wholesalerId: string
  name: string
  spec?: string
  unitPrice?: number
  stockQty?: number
  featured?: boolean
}

interface StoreWholesalerRow {
  wholesalerId: string
  name: string
  intro?: string
  status?: string
  skus: StoreSkuRow[]
  pinned?: boolean
}

interface StoreFrontRow {
  storeId: string
  tenantId: string
  storeCode: string
  storeName: string
  wholesalers: StoreWholesalerRow[]
  featuredSkuIds: string[]
  pinnedWholesalerIds: string[]
}

interface FeaturedCfg {
  mainSkuIds: string[]
  pinWaIds: string[]
}

interface SkuRow {
  id: string
  name: string
  listed: boolean
}

interface WholesalerRow {
  id: string
  name: string
  status: string
}

// ============ 全局造数 ============

test.describe.serial('P5-A 店铺撮合（主推/置顶）', () => {
  let ta: LoginData
  let ops: LoginData
  let wa1: WaSeed
  let wa2: WaSeed
  let w1Id = ''
  let w2Id = ''
  let sku1Id = ''
  let sku2Id = ''
  let sku3Id = ''
  let sku1Name = ''
  let sku2Name = ''
  let sku3Name = ''
  let wa1Name = ''
  let wa2Name = ''
  let storeCode = ''

  /** 审批 WA 入驻并回查 wholesalerId（audit 响应可能不含该字段，以列表回查为准） */
  async function approveWa(waSeed: WaSeed): Promise<string> {
    const apps = await listTaApplications(ta.token, 'PENDING')
    const app = apps.find((a) => a.name === waSeed.wholesalerName)
    if (!app) throw new Error(`[p5a-fe] 未找到入驻申请: ${waSeed.wholesalerName}`)
    ok(
      await apiPost(`/tenant/wholesaler-applications/${app.id}/audit`, { action: 'APPROVED' }, ta.token),
      'TA 审批入驻',
    )
    const ws = ok(await apiGet<WholesalerRow[]>('/tenant/wholesalers', ta.token), '批发商列表')
    const hit = ws.find((w) => w.name === waSeed.wholesalerName)
    if (!hit) throw new Error(`[p5a-fe] 审批后批发商列表未找到: ${waSeed.wholesalerName}`)
    return String(hit.id)
  }

  test.beforeAll(async () => {
    test.setTimeout(240_000)
    const seed = await seedActiveTenant()
    ta = seed.ta.login
    ops = seed.ops.login

    // WA1（置顶目标）：2 条在售 SKU（验证商户内主推前置）
    wa1 = await registerWaWithTarget(seed.tenantId)
    wa1Name = wa1.wholesalerName
    w1Id = await approveWa(wa1)
    const s1 = await seedStockForWholesaler(ta.token, w1Id, 4)
    const s2 = await seedStockForWholesaler(ta.token, w1Id, 4)
    await confirmPendingInbound(wa1.login.token)

    // WA2（普通对比商户）：1 条在售 SKU
    wa2 = await registerWaWithTarget(seed.tenantId)
    wa2Name = wa2.wholesalerName
    w2Id = await approveWa(wa2)
    const s3 = await seedStockForWholesaler(ta.token, w2Id, 4)
    await confirmPendingInbound(wa2.login.token)

    storeCode = s1.storeCode
    sku1Id = s1.skuId
    sku2Id = s2.skuId
    sku3Id = s3.skuId

    // 回查 SKU 名称（UI 选项匹配用）
    const skus1 = ok(await apiGet<SkuRow[]>('/tenant/skus', ta.token, { wholesalerId: w1Id }), 'SKU 列表1')
    const skus3 = ok(await apiGet<SkuRow[]>('/tenant/skus', ta.token, { wholesalerId: w2Id }), 'SKU 列表3')
    sku1Name = skus1.find((s) => String(s.id) === sku1Id)?.name ?? ''
    sku2Name = skus1.find((s) => String(s.id) === sku2Id)?.name ?? ''
    sku3Name = skus3.find((s) => String(s.id) === sku3Id)?.name ?? ''
    if (!sku1Name || !sku2Name || !sku3Name) throw new Error('[p5a-fe] SKU 名称回查失败')
  })

  // ============ 撮合配置 UI 全流程 ============

  test('FE-01 撮合运营卡：空态 → el-select 添加主推/置顶 → 保存 → API 回显', async ({ page }) => {
    await injectAuthAndGoto(page, ta, 'TA', '/ta/settings')
    const card = page.locator('[data-test="featured-card"]')
    await expect(card).toBeVisible()

    // 初始空态
    await expect(card.locator('.featured-block__empty')).toHaveCount(2)
    await expect(card.locator('.featured-block__empty').first()).toContainText('尚未设置主推商品')
    await expect(card.locator('.featured-block__empty').nth(1)).toContainText('尚未设置置顶批发商')

    // 主推商品 select：点击 → 点选 sku1（SKU 名称唯一，不串名）
    const mainSelect = card.locator('.featured-block__select').first()
    await mainSelect.click()
    const mainOption = page.locator('.el-select-dropdown__item', { hasText: sku1Name }).first()
    await expect(mainOption).toBeVisible()
    await mainOption.click()
    // 先关闭主弹窗，避免其 popper 遮罩拦截下一个 select 的点击
    await page.keyboard.press('Escape')
    await expect(page.locator('.el-select-dropdown').filter({ hasText: sku1Name })).toBeHidden()

    // 置顶批发商 select：点选 wa1（用"商户"关键词防 SKU 选项标签中含 WA 名称误命中）
    const waSelect = card.locator('.featured-block__select').nth(1)
    await waSelect.click()
    const waOption = page
      .locator('.el-select-dropdown:visible .el-select-dropdown__item', {
        hasText: new RegExp(reLiteral(wa1Name) + '[\\s\\S]*商户'),
      })
      .first()
    await expect(waOption).toBeVisible()
    await waOption.click()

    // 列表出现已选项 + 计数 + 保存按钮可用（主推/置顶分 block 断言，避免 SKU 副标题含 WA 名称串名）
    const mainBlock = card.locator('.featured-block').first()
    const waBlock = card.locator('.featured-block').nth(1)
    await expect(mainBlock.locator('.featured-item', { hasText: sku1Name })).toBeVisible()
    await expect(waBlock.locator('.featured-item', { hasText: wa1Name })).toBeVisible()
    await expect(mainBlock.locator('.featured-block__count')).toContainText('1/20')
    await expect(waBlock.locator('.featured-block__count')).toContainText('1/5')
    const save = card.locator('[data-test="featured-save"]')
    await expect(save).toBeEnabled()
    await page.screenshot({ path: path.join(SCREEN_DIR, 'p5a-featured-settings.png'), fullPage: true })

    // 保存 → 成功提示 → API 回显一致
    await save.click()
    await expect(page.locator('.el-message--success').filter({ hasText: '已保存' })).toBeVisible()
    const cfg = ok(await apiGet<FeaturedCfg>('/tenant/storefront/featured', ta.token), '撮合配置回显')
    expect(cfg.mainSkuIds).toEqual([sku1Id])
    expect(cfg.pinWaIds).toEqual([w1Id])
  })

  test('FE-02 RT 店铺页展示主推/置顶标识 + 前置排序（API+UI）', async ({ page }) => {
    // API 契约断言
    const store = ok(await apiGet<StoreFrontRow>('/rt/store', undefined, { code: storeCode }), 'RT 进店页')
    expect(store.featuredSkuIds).toEqual([sku1Id])
    expect(store.pinnedWholesalerIds).toEqual([w1Id])
    // 置顶批发商前置
    expect(store.wholesalers[0].wholesalerId).toBe(w1Id)
    expect(store.wholesalers[0].pinned).toBe(true)
    expect(store.wholesalers[1].wholesalerId).toBe(w2Id)
    expect(store.wholesalers[1].pinned).toBe(false)
    // 商户内主推前置
    expect(store.wholesalers[0].skus[0].skuId).toBe(sku1Id)
    expect(store.wholesalers[0].skus[0].featured).toBe(true)
    expect(store.wholesalers[0].skus[1].skuId).toBe(sku2Id)
    expect(store.wholesalers[0].skus[1].featured).toBe(false)

    // UI：公开路由进店
    await page.goto(`/rt/store?code=${storeCode}`)
    await expect(page.locator('.rt-header__title')).toBeVisible()
    const ws = page.locator('.rt-wholesaler')
    await expect(ws).toHaveCount(2)

    const first = ws.first()
    await expect(first).toHaveClass(/rt-wholesaler--pinned/)
    await expect(first.locator('.rt-wholesaler__name')).toContainText(wa1Name)
    await expect(first.locator('.rt-tag--pinned')).toContainText('置顶')
    const firstSkus = first.locator('.rt-sku')
    await expect(firstSkus.first().locator('.rt-sku__name')).toContainText(sku1Name)
    await expect(firstSkus.first().locator('.rt-tag--featured')).toContainText('主推')
    await expect(firstSkus.nth(1).locator('.rt-sku__name')).toContainText(sku2Name)
    await expect(firstSkus.nth(1).locator('.rt-tag--featured')).toHaveCount(0)

    const second = ws.nth(1)
    await expect(second).not.toHaveClass(/rt-wholesaler--pinned/)
    await expect(second.locator('.rt-wholesaler__name')).toContainText(wa2Name)
    await expect(second.locator('.rt-tag--pinned')).toHaveCount(0)
    await page.screenshot({ path: path.join(SCREEN_DIR, 'p5a-rt-store-featured.png'), fullPage: true })
  })

  // ============ 边界 / 异常 ============

  test('FE-03 上限校验：主推 >20 → 50711；置顶 >5 → 50712', async () => {
    const tooMany = Array.from({ length: 21 }, (_, i) => 10_000_000_000 + i)
    const env1 = await apiPut<never>('/tenant/storefront/featured', { mainSkuIds: tooMany, pinWaIds: [] }, ta.token)
    expect(env1.code).toBe(50711)
    const tooManyWa = Array.from({ length: 6 }, (_, i) => 20_000_000_000 + i)
    const env2 = await apiPut<never>('/tenant/storefront/featured', { mainSkuIds: [], pinWaIds: tooManyWa }, ta.token)
    expect(env2.code).toBe(50712)
  })

  test('FE-04 重复条目 → 50713', async () => {
    const env1 = await apiPut<never>(
      '/tenant/storefront/featured',
      { mainSkuIds: [sku1Id, sku1Id], pinWaIds: [] },
      ta.token,
    )
    expect(env1.code).toBe(50713)
    const env2 = await apiPut<never>(
      '/tenant/storefront/featured',
      { mainSkuIds: [], pinWaIds: [w1Id, w1Id] },
      ta.token,
    )
    expect(env2.code).toBe(50713)
  })

  test('FE-05 非本店在售 SKU / 非本店入驻批发商 → 50714', async () => {
    const env1 = await apiPut<never>(
      '/tenant/storefront/featured',
      { mainSkuIds: ['999999999999'], pinWaIds: [] },
      ta.token,
    )
    expect(env1.code).toBe(50714)
    const env2 = await apiPut<never>(
      '/tenant/storefront/featured',
      { mainSkuIds: [], pinWaIds: ['999999999999'] },
      ta.token,
    )
    expect(env2.code).toBe(50714)
  })

  test('FE-06 覆盖写幂等 + 顺序随数组更新', async ({ page }) => {
    // 覆盖写 [sku1, sku2] + [wa1]
    ok(
      await apiPut<never>(
        '/tenant/storefront/featured',
        { mainSkuIds: [sku1Id, sku2Id], pinWaIds: [w1Id] },
        ta.token,
      ),
      '覆盖写配置',
    )
    const cfg1 = ok(await apiGet<FeaturedCfg>('/tenant/storefront/featured', ta.token), '配置1')
    expect(cfg1.mainSkuIds).toEqual([sku1Id, sku2Id])

    // 幂等：重复保存同内容 → 0 且不回显漂移
    ok(
      await apiPut<never>(
        '/tenant/storefront/featured',
        { mainSkuIds: [sku1Id, sku2Id], pinWaIds: [w1Id] },
        ta.token,
      ),
      '幂等保存',
    )
    const cfg2 = ok(await apiGet<FeaturedCfg>('/tenant/storefront/featured', ta.token), '配置2')
    expect(cfg2.mainSkuIds).toEqual([sku1Id, sku2Id])

    // 顺序更新：换序 [sku2, sku1] → 回显按新序；RT 店铺页首个 SKU 变为 sku2
    ok(
      await apiPut<never>(
        '/tenant/storefront/featured',
        { mainSkuIds: [sku2Id, sku1Id], pinWaIds: [w1Id] },
        ta.token,
      ),
      '换序保存',
    )
    const cfg3 = ok(await apiGet<FeaturedCfg>('/tenant/storefront/featured', ta.token), '配置3')
    expect(cfg3.mainSkuIds).toEqual([sku2Id, sku1Id])

    // 设置页回显（重新进入触发 fetchFeatured）；主推/置顶分 block 断言
    await injectAuthAndGoto(page, ta, 'TA', '/ta/settings')
    const card = page.locator('[data-test="featured-card"]')
    const mainBlock = card.locator('.featured-block').first()
    const waBlock = card.locator('.featured-block').nth(1)
    await expect(mainBlock.locator('.featured-item').first()).toContainText(sku2Name)
    await expect(mainBlock.locator('.featured-item').nth(1)).toContainText(sku1Name)
    await expect(waBlock.locator('.featured-item', { hasText: wa1Name })).toBeVisible()

    // RT 店铺页首个 SKU 变为 sku2（主推前置）
    await page.goto(`/rt/store?code=${storeCode}`)
    const ws = page.locator('.rt-wholesaler')
    await expect(ws.first().locator('.rt-sku').first().locator('.rt-sku__name')).toContainText(sku2Name)
  })

  test('FE-07 非 TA 角色（WA）访问撮合配置 → 42101', async () => {
    // 审批通过后重新登录：注册时的 token 申请单仍 PENDING，roles 未挂租户
    const waLogin = ok(await apiLogin(wa1.phone, wa1.pwd), 'WA 重新登录')
    const envGet = await apiGet<FeaturedCfg>('/tenant/storefront/featured', waLogin.token)
    expect(envGet.code).toBe(42101)
    const envPut = await apiPut<never>(
      '/tenant/storefront/featured',
      { mainSkuIds: [sku1Id], pinWaIds: [] },
      waLogin.token,
    )
    expect(envPut.code).toBe(42101)
    // OPS 无租户上下文 → 50210（未找到租户，先建仓）
    const opsEnv = await apiGet<FeaturedCfg>('/tenant/storefront/featured', ops.token)
    expect(opsEnv.code).toBe(50210)
  })
})
