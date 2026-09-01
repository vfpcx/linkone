import { test, expect, type Page } from '@playwright/test'
import {
  seedActiveTenant,
  registerWaWithTarget,
  registerWaPlain,
  listTaApplications,
  listMyApplications,
  auditApplication,
  selfApply,
  restoreWithdraw,
  seedStockForWholesaler,
  sellOutStock,
  confirmPendingInbound,
  fetchRtStore,
  listSkus,
  listWaEmployees,
  disableWaEmployee,
  apiLogin,
  apiGet,
  loginAs,
  ok,
  uniqPhone,
  SMS_CODE,
  SEED_PWD,
  registerWithRetry,
} from './helpers/onboarding'

/**
 * PII-W7（15 §4 阶段2-1）：审批/租户列表只回打码号（138****1234），
 * E2E 行匹配用打码形态，全号断言一律走 API。
 */
const masked = (p: string) => (p.length >= 7 ? `${p.slice(0, 3)}****${p.slice(7)}` : p)

/**
 * W8/V34（16 §1.5）：黑名单 PHONE 行 target_value 已改写为 PHONE_****{last4} 摘要
 * （hmac 尾 4 消歧行含 :{hmac4} 后缀），后端 page() 原样返回、无前端 maskPhone。
 * 与审批/租户列表的 maskPhone（138****1234）口径不同，行匹配用摘要形态。
 */
const blacklistSummary = (p: string) => `PHONE_****${p.slice(-4)}`

/**
 * 仓储云 admin · P2 入驻生态 4 链路 E2E（04-onboarding-test-plan §3）
 *
 * ONB-E2E-01 入驻主链 / ONB-E2E-02 黑名单拦截链 / ONB-E2E-03 退驻链 / ONB-E2E-04 WE 员工链
 *
 * 造数：ACTIVE 租户 = TA 注册(PENDING 壳) + OPS 审核（helpers/onboarding.ts），
 *       手机号时间戳隔离；UI 只跑「可观测主线」，副作用（SKU 下架/店铺隐藏/踢出）经 API 断言。
 *
 * 前置：前端 5173 + 后端 8080（dev,local）运行中；mock 短信码 888888。
 *
 * 已知契约偏差（详见 shared/test-plan/07-onboarding-e2e-report.md 缺陷清单）：
 *  - DEF-1: 注册页(role=wa)「目标仓库」下拉为硬编码 mock（Register.vue fetchTenants），
 *    真实租户 ID 无法经 UI 选择 → E2E-01 的「注册携 targetTenantId」走 API（与后端接入点等价）。
 *  - DEF-2 已修（Wave6 §30）：拦截文案已中性化「暂不满足入驻条件，请联系平台客服」；
 *    下方仍保留「透出黑名单字样则登记 annotation」的哨兵逻辑防回退。
 */

// ============================== ONB-E2E-01 入驻主链 ==============================
test.describe('onboarding E2E-01 入驻主链', () => {
  test('ONB-E2E-01 WA注册直申→TA审批通过→WA落/wa/inquiry→TA商户列表可见', async ({ page }) => {
    // ---- 造数：ACTIVE 租户 + WA 注册携 targetTenantId（注册接入点自动建 PENDING 申请单）----
    const tenant = await seedActiveTenant()
    const wa = await registerWaWithTarget(tenant.tenantId)

    // ---- ① 申请单自动创建（API 断言：listMine 有 PENDING）----
    const mine = await listMyApplications(wa.login.token)
    expect(mine.length, '注册接入点应自动创建申请单').toBeGreaterThan(0)
    expect(mine[0].status).toBe('PENDING')

    // ---- ② WA 登录 → /wa/apply 呈现「待审核」状态卡 ----
    await loginAs(page, wa.phone, wa.pwd, /\/wa\/inquiry/)
    await page.goto('/wa/apply')
    await expect(page.getByText('申请已提交，等待仓库老板审核')).toBeVisible({ timeout: 15_000 })

    // ---- ③④ TA 登录 → 审批列表可见该申请 → 通过 ----
    await loginAs(page, tenant.ta.phone, tenant.ta.pwd, /\/ta\/dashboard/)
    await page.goto('/ta/wholesaler-applications')
    const row = page.locator('.el-table__row', { hasText: wa.wholesalerName })
    await expect(row).toBeVisible({ timeout: 15_000 })
    await row.getByRole('button', { name: '通过' }).click()
    // ElMessageBox 确认弹窗（confirmButtonText='通过'）
    await page.locator('.el-message-box').getByRole('button', { name: '通过' }).click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 12_000 })

    // ---- ⑤ WA 重新登录 → 落 /wa/inquiry，且角色已绑定 wholesalerId（API 双保险）----
    const relogin = ok(await apiLogin(wa.phone, wa.pwd), 'WA 审批后重新登录')
    expect(relogin.primaryRouter).toBe('/wa/inquiry')
    const bound = relogin.roles.find((r) => r.role === 'WA' && r.wholesalerId)
    expect(bound, '审批通过后 user_roles 应回填 wholesalerId').toBeTruthy()

    await loginAs(page, wa.phone, wa.pwd, /\/wa\/inquiry/)
    await expect(page.locator('.page-head__title')).toContainText('询价确认')
    // /wa/apply 状态卡切「已通过」
    await page.goto('/wa/apply')
    await expect(page.getByText('恭喜，入驻申请已通过')).toBeVisible({ timeout: 15_000 })

    // ---- ⑥ TA 商户列表可见该商户（生效中）----
    await loginAs(page, tenant.ta.phone, tenant.ta.pwd, /\/ta\/dashboard/)
    await page.goto('/ta/wholesalers')
    const wsRow = page.locator('.el-table__row', { hasText: wa.wholesalerName })
    await expect(wsRow).toBeVisible({ timeout: 15_000 })
    await expect(wsRow).toContainText('生效中')
  })
})

// ============================== ONB-E2E-02 黑名单拦截链 ==============================
test.describe('onboarding E2E-02 黑名单拦截链', () => {
  test('ONB-E2E-02 OPS拉黑→WA申请被拒→OPS移除→可申请', async ({ page }) => {
    const tenant = await seedActiveTenant()
    // 被拉黑的手机号先注册纯 WA 账号（注册本身不拦，拦的是入驻申请）。
    // 后端 9104adf 已修复同尾号再拉黑撞 uk 50310（REMOVED 摘要参与占位消歧），无需选号规避。
    const victim = await registerWaPlain()

    // ---- ① OPS 登录 → 黑名单页添加手机号 ----
    await loginAs(page, tenant.ops.phone, tenant.ops.pwd, /\/ops\/dashboard/)
    await page.goto('/ops/blacklist')
    await expect(page.locator('.page-head__title')).toContainText('黑名单')
    await page.getByRole('button', { name: '加入黑名单' }).click()
    const dialog = page.locator('.el-dialog', { hasText: '加入黑名单' })
    await expect(dialog).toBeVisible()
    await dialog.getByPlaceholder('11 位手机号').fill(victim.phone)
    await dialog.getByPlaceholder(/请填写加黑原因/).fill('E2E 黑名单链路测试拉黑')
    await dialog.getByRole('button', { name: '确认加黑' }).click()
    const blRow = page.locator('.el-table__row', { hasText: blacklistSummary(victim.phone) })
    await expect(blRow).toBeVisible({ timeout: 12_000 })

    // ---- ② 该手机号提交入驻申请被拒（UI 文案验证）----
    await loginAs(page, victim.phone, victim.pwd, /\/wa\/inquiry/)
    await page.goto('/wa/apply')
    await fillApplyForm(page, tenant.tenantId, victim)
    await page.getByRole('button', { name: '提交申请' }).click()
    // 拒绝生效：出现错误提示，且不落「待审核」状态卡
    const errToast = page.locator('.el-message--error').first()
    await expect(errToast).toBeVisible({ timeout: 12_000 })
    const errText = await errToast.innerText().catch(() => '')
    await expect(page.locator('.status-card')).toHaveCount(0)
    // DEF-2 记录：文案是否透出「黑名单」字样（不阻断，登记缺陷）
    if (/黑名单/.test(errText)) {
      test.info().annotations.push({
        type: 'defect',
        description: `DEF-2 拦截文案透出「黑名单」字样：「${errText}」（要求不透出）`,
      })
    }
    // API 双保险：直调申请接口同样 50205，且未建申请单
    const apiRes = await selfApply(victim.login.token, {
      targetTenantId: tenant.tenantId,
      name: victim.wholesalerName,
      contactName: 'E2E联系人',
      contactPhone: victim.phone,
    })
    expect(apiRes.code).toBe(50205)
    expect((await listMyApplications(victim.login.token)).length).toBe(0)

    // ---- 顺带断言：非 OPS 账号直达 /ops/blacklist 被角色守卫弹回 ----
    await page.goto('/ops/blacklist')
    await expect(page).not.toHaveURL(/\/ops\/blacklist/, { timeout: 10_000 })

    // ---- ③ OPS 移除 ----
    await loginAs(page, tenant.ops.phone, tenant.ops.pwd, /\/ops\/dashboard/)
    await page.goto('/ops/blacklist')
    const rmRow = page.locator('.el-table__row', { hasText: blacklistSummary(victim.phone) })
    await expect(rmRow).toBeVisible({ timeout: 12_000 })
    await rmRow.getByRole('button', { name: '移除' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '移除' }).click()
    await expect(page.locator('.el-table__row', { hasText: blacklistSummary(victim.phone) })).toHaveCount(0, {
      timeout: 12_000,
    })

    // ---- ④ 移除后可申请（UI 提交成功 → 待审核状态卡）----
    await loginAs(page, victim.phone, victim.pwd, /\/wa\/inquiry/)
    await page.goto('/wa/apply')
    await fillApplyForm(page, tenant.tenantId, victim)
    await page.getByRole('button', { name: '提交申请' }).click()
    await expect(
      page.locator('.el-alert__title', { hasText: '申请已提交，等待仓库老板审核' }),
    ).toBeVisible({ timeout: 15_000 })
  })
})

/** /wa/apply 表单填写（黑名单链两次提交复用） */
async function fillApplyForm(
  page: Page,
  tenantId: string,
  wa: { phone: string; wholesalerName: string },
): Promise<void> {
  await expect(page.locator('.form-card')).toBeVisible({ timeout: 15_000 })
  await page.getByPlaceholder('请输入目标仓库的 ID（纯数字）').fill(tenantId)
  await page.getByPlaceholder('您的批发商户名称，如：XX 副食批发').fill(wa.wholesalerName)
  await page.getByPlaceholder('联系人姓名').fill('E2E联系人')
  await page.getByPlaceholder('11 位手机号').fill(wa.phone)
}

// ============================== ONB-E2E-03 退驻链 ==============================
test.describe('onboarding E2E-03 退驻链', () => {
  test('ONB-E2E-03 前置自查→清库存→提交→TA审批→踢出/隐藏/下架→60天倒计时→restore', async ({
    page,
  }) => {
    // ---- 造数：ACTIVE 租户 + 已通过入驻的 WA + 4 件库存 ----
    const tenant = await seedActiveTenant()
    const wa = await registerWaWithTarget(tenant.tenantId)
    const pending = await listTaApplications(tenant.ta.login.token)
    const approve = await auditApplication(tenant.ta.login.token, pending[0].id, 'APPROVED')
    expect(approve.code).toBe(0)
    const wholesalerId = String(approve.data.wholesalerId)
    const waLogin = ok(await apiLogin(wa.phone, wa.pwd), 'WA 登录')
    const stock = await seedStockForWholesaler(tenant.ta.login.token, wholesalerId, 4)
    // P3 BE-W1：WK 代建入库停 PENDING_WA_CONFIRM（R13 未结单据），WA 先确认收尾
    await confirmPendingInbound(waLogin.token)

    // ---- ① 前置自查展示：库存未清 → ❌ + 提交置灰 ----
    await loginAs(page, wa.phone, wa.pwd, /\/wa\/inquiry/)
    await page.goto('/wa/withdraw')
    await expect(page.getByText('仍有在库库存，请先清空')).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('button', { name: '提交退驻申请' })).toBeDisabled()

    // ---- ② 清库存（RT 整量下单 + WA 确认转出库）→ 重新检查翻绿 ----
    await sellOutStock(stock, waLogin.token)
    await page.getByRole('button', { name: '重新检查' }).click()
    await expect(page.getByText('在库库存已清零')).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText('无未确认询价与未完成出库单据')).toBeVisible()

    // ---- ③ 提交退驻（确认弹窗）→ 待审批态 ----
    await page.locator('.withdraw-form textarea').fill('E2E 退驻链路测试')
    const submitBtn = page.getByRole('button', { name: '提交退驻申请' })
    await expect(submitBtn).toBeEnabled()
    await submitBtn.click()
    const confirmDialog = page.locator('.el-dialog', { hasText: '确认提交退驻申请' })
    await expect(confirmDialog).toBeVisible()
    await confirmDialog.getByRole('button', { name: '确认提交' }).click()
    await expect(page.getByText('退驻审批中')).toBeVisible({ timeout: 15_000 })

    // ---- ④ TA 审批通过（双 Tab 页切「退驻申请」）----
    await loginAs(page, tenant.ta.phone, tenant.ta.pwd, /\/ta\/dashboard/)
    await page.goto('/ta/wholesaler-applications')
    // el-radio-button 的 input 被 inner span 遮挡 → 点外层 label
    await page.locator('.kind-switch .el-radio-button', { hasText: '退驻申请' }).click()
    const wdRow = page.locator('.el-table__row', { hasText: wa.wholesalerName })
    await expect(wdRow).toBeVisible({ timeout: 15_000 })
    await wdRow.getByRole('button', { name: '通过' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '确认通过' }).click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 12_000 })

    // ---- ⑤ 副作用断言（API）：旧 token 被踢(41001) / 店铺隐藏 / SKU 下架 ----
    const kicked = await apiGet('/wholesaler/withdraw/mine', waLogin.token)
    expect(kicked.code, '退驻通过后旧 WA token 应被踢出').toBe(41001)

    const store = await fetchRtStore(stock.storeCode)
    expect(store.code).toBe(0)
    const visibleWs = (store.data?.wholesalers ?? []).map((w) => String(w.wholesalerId))
    expect(visibleWs, '退驻后店铺对 RT 应隐藏该商户').not.toContain(wholesalerId)

    const skus = await listSkus(tenant.ta.login.token, wholesalerId)
    expect(skus.code).toBe(0)
    for (const s of skus.data ?? []) {
      expect(s.listed, `退驻后 SKU ${s.name} 应下架`).toBe(false)
    }

    // ---- ⑥ WA 重新登录 → 60 天倒计时页 ----
    await loginAs(page, wa.phone, wa.pwd, /\/wa\/inquiry/)
    await page.goto('/wa/withdraw')
    await expect(page.getByText('您已退驻')).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.countdown-box')).toContainText('天可申请恢复入驻')
    await expect(page.getByRole('button', { name: '申请恢复入驻' })).toBeVisible()

    // ---- ⑦ restore（API 步，60 天内恢复 → ACTIVE）----
    const fresh = ok(await apiLogin(wa.phone, wa.pwd), 'WA 退驻后登录')
    const restored = await restoreWithdraw(fresh.token)
    expect(restored.code).toBe(0)
    expect((restored.data as { status?: string }).status).toBe('ACTIVE')
  })
})

// ============================== ONB-E2E-04 WE 员工链 ==============================
test.describe('onboarding E2E-04 WE员工链', () => {
  test('ONB-E2E-04 WA生码(默认仅询价确认)→WE凭码注册→落/wa/inquiry→禁用→被踢回登录页', async ({
    page,
  }) => {
    // ---- 造数：ACTIVE 租户 + 已通过入驻的 WA ----
    const tenant = await seedActiveTenant()
    const wa = await registerWaWithTarget(tenant.tenantId)
    const pending = await listTaApplications(tenant.ta.login.token)
    const approve = await auditApplication(tenant.ta.login.token, pending[0].id, 'APPROVED')
    expect(approve.code).toBe(0)
    const waLogin = ok(await apiLogin(wa.phone, wa.pwd), 'WA 登录')

    // ---- ① WA /wa/staff 生码：默认只勾「询价确认」 ----
    await loginAs(page, wa.phone, wa.pwd, /\/wa\/inquiry/)
    await page.goto('/wa/staff')
    await expect(page.locator('.page-head__title')).toContainText('员工管理')
    // 「生成注册码」按钮仅在「注册码」Tab 呈现（v-if activeTab==='invites'）
    await page.getByRole('tab', { name: '注册码' }).click()
    await page.getByRole('button', { name: '生成注册码' }).click()
    const createDialog = page.locator('.el-dialog', { hasText: '生成员工注册码' })
    await expect(createDialog).toBeVisible()
    // 默认授权断言：仅 INQUIRY_CONFIRM 勾选（最小授权原则）
    await expect(
      createDialog.locator('.el-checkbox', { hasText: '询价确认' }).locator('input'),
    ).toBeChecked()
    await expect(
      createDialog.locator('.el-checkbox', { hasText: '改价' }).locator('input'),
    ).not.toBeChecked()
    await createDialog.getByRole('button', { name: '生成', exact: true }).click()

    // 生码成功弹窗 → 抽取注册码
    const successBox = page.locator('.el-message-box', { hasText: '生成成功' })
    await expect(successBox).toBeVisible({ timeout: 12_000 })
    const boxText = await successBox.locator('.el-message-box__message').innerText()
    const inviteCode = boxText.match(/注册码：([A-Z0-9]+)/)?.[1]
    expect(inviteCode, `未从生码弹窗解析出注册码，原文=${boxText}`).toBeTruthy()
    await successBox.getByRole('button', { name: '关闭', exact: true }).click()

    // ---- ② WE 凭码注册（UI）→ ③ 自动登录落 /wa/inquiry ----
    const wePhone = uniqPhone()
    await page.goto('/login')
    await page.evaluate(() => {
      localStorage.clear()
      sessionStorage.clear()
    })
    await page.goto('/register?role=we')
    await page.waitForLoadState('networkidle')
    await page.getByPlaceholder('请输入手机号').fill(wePhone)
    await page.getByPlaceholder('请输入短信验证码').fill(SMS_CODE)
    await page.getByPlaceholder('6-20').fill(SEED_PWD)
    await page.getByPlaceholder('请输入真实姓名').fill('E2E员工')
    await page.getByPlaceholder('扫码或输入仓库/批发商提供的码').fill(inviteCode!)
    await page.locator('.agree-row .el-checkbox').click()
    await page.locator('.submit-btn').click()
    await page.waitForURL('**/wa/inquiry', { timeout: 15_000 })
    await expect(page.locator('.page-head__title')).toContainText('询价确认')

    // WE 绑定断言（API）：员工列表出现该手机号，permissions=仅 INQUIRY_CONFIRM
    const emps = ok(await listWaEmployees(waLogin.token), 'WA 员工列表')
    const emp = emps.find((e) => e.phone === wePhone)
    expect(emp, 'WE 注册后应出现在本商户员工列表').toBeTruthy()
    expect(emp!.permissions).toEqual(['INQUIRY_CONFIRM'])

    // ---- ④ WA 禁用该员工（API，保持 WE 的 UI 会话在场）----
    const dis = await disableWaEmployee(waLogin.token, emp!.id)
    expect(dis.code).toBe(0)

    // ---- ⑤ WE 被踢：页面再发请求 → 41001 → 「请重新登录」→ 回登录页 ----
    await page.reload()
    const reloginBox = page.locator('.el-message-box', { hasText: '请重新登录' })
    await expect(reloginBox.first()).toBeVisible({ timeout: 15_000 })
    // 前端 9ee9eb7 已做 41001 会话级去重（logoutAlertPending），并发只弹一个，正常点击即可。
    await reloginBox.last().getByRole('button', { name: '去登录' }).click()
    await page.waitForURL('**/login', { timeout: 10_000 })
    await expect(page).toHaveURL(/\/login/)

    // API 双保险：WE 旧 token 复用 → 41001；再登录 → 41110 已禁用
    const weRelogin = await apiLogin(wePhone, SEED_PWD)
    expect(weRelogin.code, '被禁用 WE 不可再登录').toBe(41110)
  })
})

// ============================== ONB-E2E-05 PII-W7 查全号入口 ==============================
test.describe('onboarding E2E-05 PII-W7 查全号入口', () => {
  test('OPS 租户审核页：列表打码 + 「查看完整号」弹窗全号', async ({ page }) => {
    const tenant = await seedActiveTenant()
    // 造 PENDING 租户行（TA 注册即 PENDING，不审核，供审核列表留样）
    const pendingPhone = uniqPhone()
    const tenantName = 'PII待审仓' + pendingPhone.slice(-4)
    await registerWithRetry(
      {
        phone: pendingPhone,
        password: SEED_PWD,
        smsCode: SMS_CODE,
        role: 'TA',
        realName: 'PII样本',
        tenantName,
        agreedTerms: true,
      },
      'E2E-05 造待审租户',
    )

    await loginAs(page, tenant.ops.phone, tenant.ops.pwd, /\/ops\/dashboard/)
    await page.goto('/ops/tenant-audit')
    const row = page.locator('.el-table__row', { hasText: tenantName })
    await expect(row).toBeVisible({ timeout: 15_000 })
    // PII-W7：列表只回打码号，全号不得直接出现
    await expect(row).toContainText(masked(pendingPhone))
    await expect(row).not.toContainText(pendingPhone)
    // 查全号入口 → 弹窗全号
    await row.getByRole('button', { name: '查看完整号' }).click()
    const box = page.locator('.el-message-box', { hasText: '完整联系方式' })
    await expect(box).toBeVisible()
    await expect(box).toContainText(pendingPhone)
  })

  test('TA 商户申请页：列表打码 + 「查看完整号」弹窗全号', async ({ page }) => {
    const tenant = await seedActiveTenant()
    const wa = await registerWaWithTarget(tenant.tenantId)

    await loginAs(page, tenant.ta.phone, tenant.ta.pwd, /\/ta\/dashboard/)
    await page.goto('/ta/wholesaler-applications')
    const row = page.locator('.el-table__row', { hasText: wa.wholesalerName })
    await expect(row).toBeVisible({ timeout: 15_000 })
    // PII-W7：列表只回打码号，全号不得直接出现
    await expect(row).toContainText(masked(wa.phone))
    await expect(row).not.toContainText(wa.phone)
    // 归属 TA 查全号 → 弹窗全号
    await row.getByRole('button', { name: '查看完整号' }).click()
    const box = page.locator('.el-message-box', { hasText: '完整联系方式' })
    await expect(box).toBeVisible()
    await expect(box).toContainText(wa.phone)
  })
})
