/**
 * P3 W5 终验收 · 视觉矩阵截图（供 Team Lead / 用户亲检，非断言型用例）
 *
 * 覆盖：
 *  1) 未登录三页 login/register/forgot-password × 两档窄屏（390×844 iPhone 14 / 375×667 iPhone SE）
 *  2) 各角色首页 + P3 关键新页 @1280×800：
 *     TA dashboard/outbound/approvals、WA inquiry/inbound/outbound、
 *     WK 出库作业（/ta/outbound WK 登录态）、OPS dashboard/arbitrations
 *
 * 产物：<repo>/.e2e-tmp/w5-visual/*.png
 * 前置：后端 8080（dev,local）+ 前端 dev server 5173，同 e2e/README。
 */

import { test, expect, type Page } from '@playwright/test'
import path from 'node:path'
import fs from 'node:fs'
import { seedSellChain, type SellSeed } from './helpers/sell'

// cwd = frontend/apps/admin（pnpm --filter @cangchu/admin exec playwright test）
const OUT_DIR = path.resolve(process.cwd(), '../../../.e2e-tmp/w5-visual')

test.beforeAll(() => {
  fs.mkdirSync(OUT_DIR, { recursive: true })
})

/** 动画/骨架静置后整页截图 */
async function shot(page: Page, name: string): Promise<void> {
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(900) // 入场动画/倒计时首帧静置（FE-W1 教训：动画入镜需静置重拍）
  await page.screenshot({ path: path.join(OUT_DIR, `${name}.png`), fullPage: true })
}

/** 注入登录态并直达目标页（对齐 outbound-chain.spec 的 enterWithAuth） */
async function enterWithAuth(
  page: Page,
  login: SellSeed['waLogin'],
  primaryRole: 'WA' | 'TA' | 'WK' | 'OPS',
  targetPath: string,
): Promise<void> {
  const roleEntry = login.roles.find((r) => r.role === primaryRole) ?? login.roles[0]
  const authState = {
    token: login.token,
    userId: login.userId,
    primaryRole,
    roles: login.roles,
    primaryRouter: targetPath,
    expireAt: null,
    tenantInfo: roleEntry?.tenantId
      ? {
          tenantId: roleEntry.tenantId,
          tenantName:
            primaryRole === 'TA' || primaryRole === 'WK'
              ? '测试仓'
              : primaryRole === 'OPS'
                ? '平台运营'
                : '我的商户',
        }
      : null,
  }
  await page.goto('/login')
  await page.evaluate((s) => {
    localStorage.clear()
    localStorage.setItem('cangchu-admin-auth', JSON.stringify(s))
  }, authState)
  await page.goto(targetPath)
  await page.waitForLoadState('networkidle')
}

// ============ 1. 未登录三页 × 两档窄屏 ============

const AUTH_PAGES: Array<[string, string]> = [
  ['/login', 'login'],
  ['/register', 'register'],
  ['/forgot-password', 'forgot'],
]
const VIEWPORTS: Array<[number, number]> = [
  [390, 844],
  [375, 667],
]

test.describe('W5 视觉矩阵 · 未登录页', () => {
  for (const [route, name] of AUTH_PAGES) {
    for (const [w, h] of VIEWPORTS) {
      test(`${name} @${w}x${h}`, async ({ page }) => {
        await page.setViewportSize({ width: w, height: h })
        await page.goto(route)
        await expect(page.locator('.auth-card, form').first()).toBeVisible({ timeout: 15_000 })
        await shot(page, `${name}-${w}x${h}`)
      })
    }
  }
})

// ============ 2. 角色页 @1280 ============

test.describe('W5 视觉矩阵 · 角色页 1280', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  let seed: SellSeed

  test.beforeAll(async () => {
    seed = await seedSellChain()
  })

  const cases: Array<{
    name: string
    role: 'WA' | 'TA' | 'WK' | 'OPS'
    login: (s: SellSeed) => SellSeed['waLogin']
    path: string
  }> = [
    { name: 'ta-home-dashboard', role: 'TA', login: (s) => s.taLogin, path: '/ta/dashboard' },
    { name: 'ta-outbound', role: 'TA', login: (s) => s.taLogin, path: '/ta/outbound' },
    { name: 'ta-approvals', role: 'TA', login: (s) => s.taLogin, path: '/ta/approvals' },
    { name: 'wa-home-inquiry', role: 'WA', login: (s) => s.waLogin, path: '/wa/inquiry' },
    { name: 'wa-inbound', role: 'WA', login: (s) => s.waLogin, path: '/wa/inbound' },
    { name: 'wa-outbound', role: 'WA', login: (s) => s.waLogin, path: '/wa/outbound' },
    { name: 'wk-outbound-workbench', role: 'WK', login: (s) => s.wkLogin, path: '/ta/outbound' },
  ]

  for (const c of cases) {
    test(c.name, async ({ page }) => {
      await enterWithAuth(page, c.login(seed), c.role, c.path)
      await shot(page, c.name)
    })
  }

  test('ops-home + arbitrations', async ({ page }) => {
    // 注册临时 OPS 账号（dev 注册白名单含 OPS）
    const API = process.env.E2E_API_URL ?? 'http://localhost:8080'
    const res = await fetch(`${API}/api/v1/account/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        phone: '13' + String(Date.now()).slice(-9),
        password: 'OpsPass123',
        smsCode: '888888',
        role: 'OPS',
        realName: 'W5VisualOPS',
        agreedTerms: true,
      }),
    })
    const env = (await res.json()) as {
      code: number
      data: SellSeed['waLogin']
    }
    if (env.code !== 0) throw new Error(`OPS 注册失败: ${JSON.stringify(env)}`)
    await enterWithAuth(page, env.data, 'OPS', '/ops/dashboard')
    await shot(page, 'ops-home-dashboard')
    await page.goto('/ops/arbitrations')
    await page.waitForLoadState('networkidle')
    await shot(page, 'ops-arbitrations')
  })
})
