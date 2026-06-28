import { test, expect } from '@playwright/test'
import { apiRegisterTa, seedTa, uniqPhone, SMS_CODE, DEFAULT_PWD } from './helpers/api'
import { loginUi } from './helpers/ui'

/**
 * 仓储云 admin · E2E 套件（E1-E8）
 *
 * 迁移自 .e2e-tmp/smoke.py（E1-E5）+ .e2e-tmp/extra.py（E6-E8），逐条对齐选择器与断言。
 *
 * 前置：前端 5173 + 后端 8080 均在运行；mock 短信验证码固定 888888；
 *       后端契约：注册/登录返回 roles + primaryRouter=/ta/dashboard。
 *
 * 分组：
 *   happy        : E1 注册→工作台 / E2 密码登录 / E5 找回密码 / E6 工作台渲染 / E8 退出登录
 *   negative     : E3 手机号格式错 / E4 密码错误
 *   idempotency  : E7 重复注册→引导登录（场景 S6 幂等/去重）
 */

// ============================== happy ==============================
test.describe('happy', () => {
  // ---------- E1: TA 注册 → 直接进工作台 ----------
  test('E1 TA 注册→工作台', async ({ page }) => {
    const taPhone = uniqPhone()
    await page.goto('/register?role=ta')
    await page.waitForLoadState('networkidle')

    await page.getByPlaceholder('请输入手机号').fill(taPhone)
    await page.getByPlaceholder('请输入短信验证码').fill(SMS_CODE)
    await page.getByPlaceholder('6-20').fill(DEFAULT_PWD)
    await page.getByPlaceholder('请输入真实姓名').fill('张三测试')
    await page.getByPlaceholder('如：XX 海鲜库').fill(`E2E仓库${taPhone.slice(-4)}`)
    await page.locator('.agree-row .el-checkbox').click()
    await page.locator('.submit-btn').click()

    // 注册成功后应离开注册页并落地工作台（路由契约 /ta/dashboard）
    await page.waitForURL('**/ta/dashboard', { timeout: 15_000 })
    await expect(page).toHaveURL(/\/ta\/dashboard/)
    // 不应落到 404
    await expect(page.getByText('页面不存在')).toHaveCount(0)
  })

  // ---------- E2: 用 API 预置账号 → UI 密码登录 ----------
  test('E2 密码登录(已注册账号)', async ({ page }) => {
    const { phone, pwd } = await seedTa() // API 旁路建号，隔离注册偶发失败

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
    await expect(page.getByText('页面不存在')).toHaveCount(0)
  })

  // ---------- E5: 找回密码 两步重置 ----------
  test('E5 找回密码两步重置', async ({ page }) => {
    const { phone } = await seedTa()
    const newPwd = 'ResetPwd456'

    await page.goto('/forgot-password')
    await page.waitForLoadState('networkidle')

    // Step 1：手机号 + 验证码 → 下一步
    await page.getByPlaceholder('请输入注册手机号').fill(phone)
    await page.getByPlaceholder('请输入短信验证码').fill(SMS_CODE)
    await page.getByRole('button', { name: '下一步' }).click()

    // Step 2：新密码 + 确认 → 提交
    await page.getByPlaceholder('6-20').fill(newPwd)
    await page.getByPlaceholder('再次输入新密码').fill(newPwd)
    await page.locator('.step2-actions .el-button--primary').click()

    // 重置后应出现成功提示，或跳回登录页
    await expect
      .poll(
        async () => {
          const msgCount = await page.locator('.el-message--success, .el-message').count()
          const backLogin = /\/login/.test(page.url())
          return msgCount > 0 || backLogin
        },
        { timeout: 10_000, message: '未见成功提示也未跳回登录页' },
      )
      .toBe(true)

    // 验证新密码可登录（增强断言，证明重置真实生效）
    await loginUi(page, phone, newPwd)
  })

  // ---------- E6: 工作台渲染校验 ----------
  test('E6 工作台渲染校验', async ({ page }) => {
    const { phone, pwd } = await seedTa()
    await loginUi(page, phone, pwd)

    const body = page.locator('body')
    await expect(body).toContainText('仓储云') // 顶栏品牌
    await expect(body).toContainText('本店概览')
    await expect(body).toContainText('待处理')
    await expect(body).toContainText('今日')
    await expect(body).toContainText('店铺设置') // 左侧菜单
  })

  // ---------- E8: 退出登录 ----------
  test('E8 退出登录', async ({ page }) => {
    const { phone, pwd } = await seedTa()
    await loginUi(page, phone, pwd)

    await page.locator('.ta-topbar__user').click()
    await page.getByText('退出登录').click()
    // 确认弹窗（ElMessageBox confirmButtonText='退出'）
    await page.getByRole('button', { name: '退出' }).click()

    await page.waitForURL('**/login', { timeout: 10_000 })
    await expect(page).toHaveURL(/\/login/)
  })
})

// ============================== negative ==============================
test.describe('negative', () => {
  // ---------- E3: 负向 手机号格式错 → 字段报错 ----------
  test('E3 负向·手机号格式错', async ({ page }) => {
    await page.goto('/login')
    await page.evaluate(() => localStorage.clear())
    await page.goto('/login')
    await page.waitForLoadState('networkidle')

    await page.getByPlaceholder('请输入手机号').fill('12345')
    await page.getByPlaceholder('请输入密码').click() // blur 触发校验

    const err = page.locator('.el-form-item__error').first()
    await expect(err).toBeVisible({ timeout: 5_000 })
    await expect(err).toContainText('手机号')
  })

  // ---------- E4: 负向 密码错误 → 顶部告警 + 停留登录页 ----------
  test('E4 负向·密码错误', async ({ page }) => {
    const { phone } = await seedTa() // 真实存在的账号，密码故意错

    await page.goto('/login')
    await page.evaluate(() => localStorage.clear())
    await page.goto('/login')
    await page.waitForLoadState('networkidle')

    await page.getByPlaceholder('请输入手机号').fill(phone)
    await page.getByPlaceholder('请输入密码').fill('WrongPwd999')
    await page.locator('.submit-btn').click()

    // 应停留登录页并出现告警（顶部 Alert 或 message）
    const alert = page.locator('.auth-alert, .el-message--error, .el-message')
    await expect(alert.first()).toBeVisible({ timeout: 8_000 })
    await expect(page).toHaveURL(/\/login/)
  })
})

// ============================== idempotency S6 ==============================
test.describe('idempotency S6', () => {
  // ---------- E7: 重复手机号注册 → 引导登录 ----------
  test('E7 重复注册→引导登录', async ({ page }) => {
    const dupPhone = uniqPhone()
    // 先用 API 占用该手机号
    expect(await apiRegisterTa(dupPhone, DEFAULT_PWD)).toBe(true)

    await page.goto('/register?role=ta')
    await page.evaluate(() => localStorage.clear())
    await page.goto('/register?role=ta')
    await page.waitForLoadState('networkidle')

    await page.getByPlaceholder('请输入手机号').fill(dupPhone)
    await page.getByPlaceholder('请输入短信验证码').fill(SMS_CODE)
    await page.getByPlaceholder('6-20').fill(DEFAULT_PWD)
    await page.getByPlaceholder('请输入真实姓名').fill('重复用户')
    await page.getByPlaceholder('如：XX 海鲜库').fill('重复仓库')
    await page.locator('.agree-row .el-checkbox').click()
    await page.locator('.submit-btn').click()

    // 重复手机号：应停留注册页 + 提示"已注册"（前端 41104 → ElMessage.info('该手机号已注册，请直接登录')）
    await expect(page.getByText('已注册').first()).toBeVisible({ timeout: 8_000 })
    await expect(page).toHaveURL(/\/register/)
  })
})
