import { test, expect } from '@playwright/test'
import {
  seedActiveTenant,
  registerWaWithTarget,
  listTaApplications,
  seedStockForWholesaler,
  confirmPendingInbound,
  apiGet,
  apiPost,
  ok,
  type WaSeed,
} from '../helpers/onboarding'
import { openUniStore } from './rt-helpers'

/**
 * RT 买家正式端（uni H5）· 撮合展示基线
 *
 * 迁移自 p5a-storefront-featured.spec.ts FE-02 的「RT 店铺页 UI 展示」段
 * （admin RT 最小 H5 过渡态 /rt/store UI）→ 跑在 uni H5（5175）。
 * admin 端 p5a spec 只保留撮合配置管理 UI（FE-01/FE-03~FE-07）+ RT 聚合 API 契约断言（FE-02 API 段）。
 * 撮合运营的「主推/置顶」正式端渲染（商户 tab 置顶徽标 + SKU 主推徽标 + 前端顺序）由本文件承接。
 */

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'

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

interface FeaturedCfg {
  mainSkuIds: string[]
  pinWaIds: string[]
}

interface StoreFrontRow {
  storeCode: string
  storeName: string
  wholesalers: Array<{
    wholesalerId: string
    name: string
    pinned?: boolean
    skus: Array<{ skuId: string; name: string; featured?: boolean }>
  }>
  featuredSkuIds: string[]
  pinnedWholesalerIds: string[]
}

interface WholesalerRow {
  id: string
  name: string
}

test.describe.serial('UNI-RT · 撮合展示（主推/置顶，正式端 uni H5）', () => {
  let taToken = ''
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

  async function approveWa(waSeed: WaSeed): Promise<string> {
    const apps = await listTaApplications(taToken, 'PENDING')
    const app = apps.find((a) => a.name === waSeed.wholesalerName)
    if (!app) throw new Error(`[uni-rt-fe] 未找到入驻申请: ${waSeed.wholesalerName}`)
    ok(
      await apiPost(`/tenant/wholesaler-applications/${app.id}/audit`, { action: 'APPROVED' }, taToken),
      'TA 审批入驻',
    )
    const ws = ok(await apiGet<WholesalerRow[]>('/tenant/wholesalers', taToken), '批发商列表')
    const hit = ws.find((w) => w.name === waSeed.wholesalerName)
    if (!hit) throw new Error(`[uni-rt-fe] 审批后批发商列表未找到: ${waSeed.wholesalerName}`)
    return String(hit.id)
  }

  test.beforeAll(async () => {
    test.setTimeout(240_000)
    const seed = await seedActiveTenant()
    taToken = seed.ta.login.token

    // WA1（置顶目标）：2 条在售 SKU
    const wa1 = await registerWaWithTarget(seed.tenantId)
    wa1Name = wa1.wholesalerName
    w1Id = await approveWa(wa1)
    const s1 = await seedStockForWholesaler(taToken, w1Id, 4)
    const s2 = await seedStockForWholesaler(taToken, w1Id, 4)
    await confirmPendingInbound(wa1.login.token)

    // WA2（普通对比商户）：1 条在售 SKU
    const wa2 = await registerWaWithTarget(seed.tenantId)
    wa2Name = wa2.wholesalerName
    w2Id = await approveWa(wa2)
    const s3 = await seedStockForWholesaler(taToken, w2Id, 4)
    await confirmPendingInbound(wa2.login.token)

    storeCode = s1.storeCode
    sku1Id = s1.skuId
    sku2Id = s2.skuId
    sku3Id = s3.skuId
    const skus1 = ok(await apiGet<Array<{ id: string; name: string }>>('/tenant/skus', taToken, { wholesalerId: w1Id }), 'SKU 列表1')
    const skus3 = ok(await apiGet<Array<{ id: string; name: string }>>('/tenant/skus', taToken, { wholesalerId: w2Id }), 'SKU 列表3')
    sku1Name = skus1.find((s) => String(s.id) === sku1Id)?.name ?? ''
    sku2Name = skus1.find((s) => String(s.id) === sku2Id)?.name ?? ''
    sku3Name = skus3.find((s) => String(s.id) === sku3Id)?.name ?? ''
    if (!sku1Name || !sku2Name || !sku3Name) throw new Error('[uni-rt-fe] SKU 名称回查失败')

    // 撮合配置：sku1 主推 + wa1 置顶（覆盖写幂等契约在 admin p5a spec 覆盖，这里只做一次生效配置）
    ok(
      await apiPut<never>(
        '/tenant/storefront/featured',
        { mainSkuIds: [sku1Id], pinWaIds: [w1Id] },
        taToken,
      ),
      '配置撮合（sku1 主推 + wa1 置顶）',
    )
  })

  test('UNI-RT-FE02 RT 聚合 API 契约（主推/置顶/排序）', async () => {
    const store = ok(await apiGet<StoreFrontRow>('/rt/store', undefined, { code: storeCode }), 'RT 进店页')
    expect(store.featuredSkuIds).toEqual([sku1Id])
    expect(store.pinnedWholesalerIds).toEqual([w1Id])
    expect(store.wholesalers[0].wholesalerId).toBe(w1Id)
    expect(store.wholesalers[0].pinned).toBe(true)
    expect(store.wholesalers[1].wholesalerId).toBe(w2Id)
    expect(store.wholesalers[1].pinned).toBe(false)
    expect(store.wholesalers[0].skus[0].skuId).toBe(sku1Id)
    expect(store.wholesalers[0].skus[0].featured).toBe(true)
    expect(store.wholesalers[0].skus[1].skuId).toBe(sku2Id)
    expect(store.wholesalers[0].skus[1].featured).toBe(false)
  })

  test('UNI-RT-FE02-UI 正式端渲染：置顶商户前置 + 主推徽标 + tab 切换', async ({ page }) => {
    await openUniStore(page, storeCode)

    // 2 个商户 tab：第一个为置顶 wa1（wa-tab__pin 徽标）
    const tabs = page.locator('.wa-tab')
    await expect(tabs).toHaveCount(2)
    await expect(tabs.first()).toContainText(wa1Name)
    await expect(tabs.first().locator('.wa-tab__pin')).toHaveText('置顶')
    await expect(tabs.nth(1)).toContainText(wa2Name)
    await expect(tabs.nth(1).locator('.wa-tab__pin')).toHaveCount(0)

    // 默认激活 wa1：主推 SKU 在其商品列表首位
    const rows = page.locator('.sku-row')
    await expect(rows).toHaveCount(2)
    await expect(rows.first().locator('.sku-name')).toContainText(sku1Name)
    await expect(rows.first().locator('.badge--hot')).toHaveText('主推')
    await expect(rows.nth(1).locator('.sku-name')).toContainText(sku2Name)
    await expect(rows.nth(1).locator('.badge--hot')).toHaveCount(0)

    // 切到 wa2：商品列表变为其 SKU，无主推徽标
    await tabs.nth(1).click()
    const rows2 = page.locator('.sku-row')
    await expect(rows2).toHaveCount(1)
    await expect(rows2.first().locator('.sku-name')).toContainText(sku3Name)
    await expect(rows2.first().locator('.badge--hot')).toHaveCount(0)
  })
})
