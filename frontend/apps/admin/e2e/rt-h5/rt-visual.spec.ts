import { test, expect } from '@playwright/test'
import path from 'node:path'
import fs from 'node:fs'
import { seedSellChain, uniqPhone } from '../helpers/sell'
import { openUniStore, tapPlus } from './rt-helpers'

/**
 * RT 买家正式端（uni H5）· 视觉基线
 *
 * 迁移自 admin 内 RT 最小 H5 过渡态页的视觉截图（p5a-w5-visual.spec.ts 的 RT 段）→ 打 uni H5（375 移动视口）。
 * 产物输出到 test-results/screens/（不入库）。
 */

const SCREEN_DIR = path.resolve(process.cwd(), 'test-results/screens')

test.describe.serial('UNI-RT · 视觉快照（正式端 uni H5）', () => {
  let storeCode = ''
  let skuName = ''

  test.beforeAll(async () => {
    test.setTimeout(120_000)
    fs.mkdirSync(SCREEN_DIR, { recursive: true })
    const seed = await seedSellChain()
    storeCode = seed.storeCode
    skuName = seed.skuName
  })

  test('UNI-RT-VIS-01 进店 → 选量 → 提交成功弹层（375）', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 })
    await openUniStore(page, storeCode)
    await expect(page.locator('.sku-name').first()).toContainText(skuName)
    await page.screenshot({ path: path.join(SCREEN_DIR, 'uni-rt-store.png'), fullPage: true })

    // 选量后提交栏出现
    await tapPlus(page, 2)
    await expect(page.locator('.submit-bar')).toBeVisible()
    await page.screenshot({ path: path.join(SCREEN_DIR, 'uni-rt-store-qty.png'), fullPage: true })

    // 提交（首次进店收集手机号）→ 成功弹层
    await page.locator('.submit-bar__btn').click()
    await page.locator('.phone-input input').fill(uniqPhone())
    await page.locator('.sheet:has(.phone-input) .btn-primary').click()
    await expect(page.locator('.sheet__ok')).toBeVisible({ timeout: 15_000 })
    await page.screenshot({ path: path.join(SCREEN_DIR, 'uni-rt-done-sheet.png') })
  })
})
