import { test, expect } from '@playwright/test'
import {
  seedSellChain,
  seedEmptyStore,
  apiSubmitInquiry,
  apiConfirmInquiry,
  fetchRtStore,
  stockOfSku,
  type SellSeed,
} from '../helpers/sell'
import { openUniStore, tapPlus } from './rt-helpers'

/**
 * RT 买家正式端（uni H5）· 边界旅程 UI 基线
 *
 * 迁移自 admin 内 RT 最小 H5 过渡态页（/rt/store UI）的用例：
 * sell-flow-2.spec.ts 的 B-RT-02（卖光空态）/ B-RT-03（步进钳制 UI 佐证）/ B-RT-07（空店）。
 * API 主旅程断言仍留在 admin 端 sell-flow-2.spec.ts（B-RT-03 超量提交→确认 50251 等）。
 */

/** 直接下单买光 seed 全部库存（B-RT-02 前置：卖光） */
async function sellOut(seed: SellSeed): Promise<void> {
  const res = await apiSubmitInquiry({
    code: seed.storeCode,
    wholesalerId: seed.wholesalerId,
    skuId: seed.skuId,
    qty: seed.stock,
  })
  expect(res.code).toBe(0)
  expect(res.data.docNo).toMatch(/^XJ-/)
  const res2 = await apiConfirmInquiry(res.data.id, seed.waLogin.token)
  expect(res2.code).toBe(0)
}

test.describe.serial('UNI-RT · 边界旅程（正式端 uni H5）', () => {
  test('UNI-RT-B02 卖光后进店：无 SKU、空态提示、不报错', async ({ page }) => {
    const seed = await seedSellChain(5)
    // 前置：库存 5 → 下单买光 → 库存归零（API 契约断言仍留 admin 端，这里只做造数）
    const before = await fetchRtStore(seed.storeCode)
    expect(stockOfSku(before, seed.skuId)).toBe(5)
    await sellOut(seed)
    const after = await fetchRtStore(seed.storeCode)
    expect(stockOfSku(after, seed.skuId)).toBeNull()

    await openUniStore(page, seed.storeCode)
    await expect(page.locator('.sku-row')).toHaveCount(0)
    await expect(page.locator('.empty__text')).toBeVisible()
    await expect(page.getByText(seed.skuName)).toHaveCount(0)
  })

  test('UNI-RT-B03 步进钳制：上限=库存，+到顶后不可再增', async ({ page }) => {
    const seed = await seedSellChain(5)
    await openUniStore(page, seed.storeCode)

    // 连点 +（tapPlus 内部遇 step--off 即停）：期望停在库存 5
    await tapPlus(page, 8)
    const input = page.locator('.step-input input').first()
    await expect(input).toHaveValue('5')
    // 已到顶：按钮 step--off（pointer-events:none），数值不再增长
    const plus = page.locator('.sku-row .step--plus').first()
    await expect(plus).toHaveClass(/step--off/)
    await expect(input).toHaveValue('5')
  })

  test('UNI-RT-B07 空店进店：空态提示、不报错', async ({ page }) => {
    const seed = await seedEmptyStore()
    await openUniStore(page, seed.storeCode)
    await expect(page.locator('.sku-row')).toHaveCount(0)
    await expect(page.locator('.empty__text')).toBeVisible()
  })
})
