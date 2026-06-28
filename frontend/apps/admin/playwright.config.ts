import { defineConfig, devices } from '@playwright/test'

/**
 * 仓储云 admin · Playwright E2E 配置
 *
 * 前置条件（外部启动，本配置不自动拉起服务）：
 *  - 前端 dev server：http://localhost:5173   （pnpm --filter @cangchu/admin dev）
 *  - 后端 API：       http://localhost:8080   （需 MySQL + Redis）
 *  - mock 短信验证码固定 888888
 *
 * 详见同目录 e2e/README.md。
 */

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'

export default defineConfig({
  testDir: './e2e',
  // 单串行运行：用例间通过唯一手机号隔离，但共享同一浏览器登录态较脆弱，串行更稳。
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  outputDir: 'test-results',
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})
