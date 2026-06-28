import { type Page, expect } from '@playwright/test'

/**
 * UI 登录 helper（复刻 .e2e-tmp/extra.py 的 login_ui）：
 *  - 进登录页 → 清登录态 → 重进 → 填手机号/密码 → 提交 → 等跳转 /ta/dashboard
 */
export async function loginUi(page: Page, phone: string, pwd: string): Promise<void> {
  await page.goto('/login')
  await page.evaluate(() => {
    localStorage.clear()
    sessionStorage.clear()
  })
  await page.goto('/login')
  await page.waitForLoadState('networkidle')
  await page.getByPlaceholder('请输入手机号').fill(phone)
  await page.getByPlaceholder('请输入密码').fill(pwd)
  await page.locator('.submit-btn').click()
  await page.waitForURL('**/ta/dashboard', { timeout: 15_000 })
  await expect(page).toHaveURL(/\/ta\/dashboard/)
}
