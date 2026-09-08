import { expect, type Page } from '@playwright/test'
import { uniqPhone } from '../helpers/sell'

/**
 * RT 买家正式端（uni H5）E2E 共享助手（e2e/rt-h5/）。
 *
 * 运行工程：playwright project "uni-rt"（baseURL = uni dev server 5175，hash 路由）。
 * 进店 URL：/#/pages/rt/store/index?code=<tenantSimpleCode>（见 uni src/pages.json）。
 *
 * DOM 约定（uni-app H5 编译产物）：
 *  - view/button/text → div/button 等原生元素，class 原样保留（.store-head__name/.sku-row/.step--plus 等）
 *  - 手机号收集弹层 .sheet:has(.phone-input)；提交成功弹层 .sheet__ok + .sheet__desc（含「单号 XJ-…」）
 *  - 步进器 + 到库存上限后带 .step--off（pointer-events:none，B-RT-03 钳制）
 */

/** 进店（uni hash 路由） */
export async function openUniStore(page: Page, code: string): Promise<void> {
  await page.goto(`/#/pages/rt/store/index?code=${encodeURIComponent(code)}`)
  await expect(page.locator('.store-head__name')).toBeVisible({ timeout: 20_000 })
}

/** 等店内首个商品行渲染（有在售 SKU 时用） */
export async function expectSkuRows(page: Page, min = 1): Promise<void> {
  await expect(page.locator('.sku-row').first()).toBeVisible({ timeout: 15_000 })
  if (min > 1) await expect(page.locator('.sku-row')).toHaveCount(min)
}

/**
 * 底部提交意向单（如首次进店会弹手机号收集 sheet）→ 等待成功弹层。
 * 成功后返回从 .sheet__desc 解析出的单号（XJ-…）；找不到则抛错。
 */
export async function submitAndGetDocNo(page: Page, phone = uniqPhone()): Promise<string> {
  const submit = page.locator('.submit-bar__btn')
  await expect(submit).toBeVisible({ timeout: 15_000 })
  await submit.click()
  // 首次进店（无已存手机号）会弹收集 sheet：填号 → 确认并继续
  // uni H5 中 <input class="phone-input"> 编译为外层 uni-input + 内部原生 input
  const phoneInput = page.locator('.phone-input input')
  if (await phoneInput.count()) {
    await phoneInput.fill(phone)
    await page.locator('.sheet:has(.phone-input) .btn-primary').click()
  }
  await expect(page.locator('.sheet__ok')).toBeVisible({ timeout: 15_000 })
  const desc = await page.locator('.sheet__desc').first().innerText()
  const m = desc.match(/XJ-[A-Z0-9-]+/)
  if (!m) throw new Error(`未从成功弹层解析出单号，desc=${desc}`)
  return m[0]
}

/** 步进 +N 次（首个 SKU），返回最终数量断言前的输入框 locator */
export async function tapPlus(page: Page, times: number): Promise<void> {
  const plus = page.locator('.sku-row .step--plus').first()
  for (let i = 0; i < times; i++) {
    const cls = await plus.getAttribute('class').catch(() => '')
    if (cls?.includes('step--off')) break // 已达库存上限（B-RT-03 钳制）
    await plus.click()
  }
}
