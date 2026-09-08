import { test, expect } from '@playwright/test'
import { seedSellChain, uniqPhone } from '../helpers/sell'
import { openUniStore, submitAndGetDocNo, tapPlus } from './rt-helpers'

/**
 * RT 买家正式端（uni H5）· 下单 UI 基线
 *
 * 迁移自 admin 内 RT 最小 H5 过渡态页（/rt/store UI）的用例：
 * SELL-S1-01 / S2-01 / S2-01b（原 sell-flow.spec.ts）→ 跑在 uni H5（5175，hash 路由）。
 * admin RT 过渡态退役后，买家 UI 下单链路由此文件承接。
 */

test.describe.serial('UNI-RT · 买家下单 UI（正式端 uni H5）', () => {
  test('UNI-RT-S1-01 RT 进店下单拿单号（步进 +3 → 手机号收集 → 成功弹层 XJ-）', async ({ page }) => {
    const seed = await seedSellChain()
    await openUniStore(page, seed.storeCode)

    // 店名渲染 + 首个 SKU（seed 单商户 1 SKU）
    await expect(page.locator('.sku-name').first()).toHaveText(seed.skuName)

    // 步进 +3（uni H5 input 编译为外层 .step-input + 内部原生 input）
    await tapPlus(page, 3)
    await expect(page.locator('.step-input input').first()).toHaveValue('3')

    // 提交 → 首次进店弹手机号收集 sheet → 确认 → 成功弹层含单号
    const docNo = await submitAndGetDocNo(page)
    expect(docNo).toMatch(/^XJ-/)
    await expect(page.locator('.sheet__title')).toContainText('意向单已提交')
  })

  test('UNI-RT-S2-01 非法手机号被拦截（收集弹层不关闭、不下单）', async ({ page }) => {
    const seed = await seedSellChain()
    await openUniStore(page, seed.storeCode)
    await tapPlus(page, 2)

    const submit = page.locator('.submit-bar__btn')
    await expect(submit).toBeVisible()
    await submit.click()

    const phoneInput = page.locator('.phone-input input')
    await expect(phoneInput).toBeVisible()
    await phoneInput.fill('12345') // 非 11 位 → 拦截
    await page.locator('.sheet:has(.phone-input) .btn-primary').click()

    // 弹层仍开着（未进入成功态），即未提交
    await expect(phoneInput).toBeVisible()
    await expect(page.locator('.sheet__ok')).toHaveCount(0)
  })

  test('UNI-RT-S2-01b 未选数量时无提交栏（无法下单）', async ({ page }) => {
    const seed = await seedSellChain()
    await openUniStore(page, seed.storeCode)
    await expectSkuRowsVisible(page)
    // 未选任何数量 → 底部提交栏不渲染
    await expect(page.locator('.submit-bar')).toHaveCount(0)
  })
})

/** 断言店内至少出现首个商品行 */
async function expectSkuRowsVisible(page: import('@playwright/test').Page): Promise<void> {
  await expect(page.locator('.sku-row').first()).toBeVisible({ timeout: 15_000 })
}
