import { defineConfig, devices } from '@playwright/test'

/**
 * 仓储云 · Playwright E2E 配置（双工程）
 *
 * 前置条件（外部启动，本配置不自动拉起服务）：
 *  - 前端 admin dev server：http://localhost:5173   （pnpm --filter @cangchu/admin dev）
 *  - RT 买家正式端 uni dev server：http://localhost:5175 （pnpm --filter @cangchu/uni dev:h5）
 *  - 后端 API：       http://localhost:8080   （需 MySQL + Redis）
 *  - mock 短信验证码固定 888888
 *
 * 工程：
 *  - chromium：admin 端 UI（OPS/TA/WA/WK 电脑端，5173），含 e2e/*.spec.ts（rt-h5/ 除外）
 *  - uni-rt：RT 买家正式端 H5（uni，5175，hash 路由），只跑 e2e/rt-h5/*.spec.ts
 *    ——admin 内 RT 最小 H5 过渡态已随 F7-7 物理删除，买家 UI 基线即由本工程承接（进店/下单/卖光/空店/撮合展示）。
 *
 * 详见 e2e/README.md。
 */

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'
/** uni H5（5175，hash 路由） */
const UNI_BASE_URL = process.env.E2E_UNI_URL ?? 'http://localhost:5175'

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
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
  },
  projects: [
    {
      name: 'chromium',
      // admin 端 UI（OPS/TA/WA/WK 电脑端，5173）
      testIgnore: /e2e[\\/]rt-h5[\\/]/,
      use: { ...devices['Desktop Chrome'], baseURL: BASE_URL },
    },
    {
      name: 'uni-rt',
      // RT 买家正式端 H5（uni，5175，hash 路由）；移动视口
      testMatch: /e2e[\\/]rt-h5[\\/].*\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        baseURL: UNI_BASE_URL,
        viewport: { width: 390, height: 844 },
      },
    },
  ],
})
