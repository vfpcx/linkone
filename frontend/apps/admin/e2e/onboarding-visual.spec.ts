import { test, expect, type Page } from '@playwright/test'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  seedActiveTenant,
  registerWaWithTarget,
  registerWaPlain,
  listTaApplications,
  auditApplication,
  seedStockForWholesaler,
  confirmPendingInbound,
  sellOutStock,
  submitWithdraw,
  listTaWithdrawApps,
  auditWithdraw,
  opsAddBlacklist,
  selfApply,
  apiLogin,
  injectAuthAndGoto,
  ok,
  uniqPhone,
} from './helpers/onboarding'

/**
 * P2 入驻生态 · 视觉验收截图（04-onboarding-test-plan §4 V-01~V-06 + 00 §3.5/§3.6）
 *
 * 7 个新页面各截「正常态 + 关键弹窗态」，正常态覆盖 1280/768/375 三断点，
 * 弹窗态 1280。截图仅为「视觉验收产物」——逐张肉眼目检由能读图的审阅者完成
 * （00 §3.5.3：视觉判断不得下放给文本子 Agent），本 spec 不做像素断言。
 *
 * 产物目录：仓根 .e2e-tmp/visual-p2/（未跟踪，不进 git；报告中引用相对路径）。
 * 命名：{V编号}-{页面}-{状态}-{宽度}.png
 */

const SHOT_DIR = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../../.e2e-tmp/visual-p2',
)

const BREAKPOINTS = [
  { w: 1280, h: 800 },
  { w: 768, h: 900 },
  { w: 375, h: 812 },
] as const

async function shot(page: Page, name: string, width: number): Promise<void> {
  // el-dialog/el-message-box 有 ~300ms 淡入动画；fullPage 截图会滚动页面导致
  // sticky 顶栏错位假象 → 弹窗一律等动画结束后截 viewport（弹窗必在首屏内）
  await page.waitForTimeout(450)
  const isDialogShot = /dialog|confirm/i.test(name)
  await page.screenshot({
    path: path.join(SHOT_DIR, `${name}-${width}.png`),
    fullPage: !isDialogShot,
  })
}

/** 三断点正常态连拍（等页面稳定后逐断点截） */
async function shotAllBreakpoints(page: Page, name: string): Promise<void> {
  for (const bp of BREAKPOINTS) {
    await page.setViewportSize({ width: bp.w, height: bp.h })
    await page.waitForTimeout(300) // 布局重排稳定
    await shot(page, name, bp.w)
  }
  await page.setViewportSize({ width: 1280, height: 800 })
}

test.describe('P2 视觉验收截图', () => {
  test.setTimeout(180_000)

  test('V-01 WA入驻申请页：表单/校验错/待审核', async ({ page }) => {
    const tenant = await seedActiveTenant()
    const wa = await registerWaPlain()

    // 表单态（未申请）
    await injectAuthAndGoto(page, wa.login, 'WA', '/wa/apply')
    await expect(page.locator('.form-card')).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V01-apply-form')

    // 校验错误态（空提交触发全部必填红字）
    await page.getByRole('button', { name: '提交申请' }).click()
    await expect(page.locator('.el-form-item__error').first()).toBeVisible({ timeout: 8_000 })
    await shot(page, 'V01-apply-form-errors', 1280)

    // 待审核状态卡（先 API 建 PENDING 申请再刷新）
    ok(
      await selfApply(wa.login.token, {
        targetTenantId: tenant.tenantId,
        name: wa.wholesalerName,
        contactName: '视觉验收',
        contactPhone: wa.phone,
      }),
      'V01 造 PENDING 申请',
    )
    await page.reload()
    await expect(page.locator('.status-card')).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V01-apply-pending')
  })

  test('V-02 TA审批页：双Tab列表/驳回弹窗（含必填红字）', async ({ page }) => {
    const tenant = await seedActiveTenant()
    const wa = await registerWaWithTarget(tenant.tenantId) // 自动建 PENDING 申请

    await injectAuthAndGoto(page, tenant.ta.login, 'TA', '/ta/wholesaler-applications')
    const row = page.locator('.el-table__row', { hasText: wa.wholesalerName })
    await expect(row).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V02-ta-apps-pending')

    // 驳回弹窗 + 理由必填红字校验
    await row.getByRole('button', { name: '驳回' }).click()
    const rejectDialog = page.locator('.el-dialog', { hasText: '驳回' })
    await expect(rejectDialog).toBeVisible()
    await rejectDialog.getByRole('button', { name: /确认驳回|驳回/ }).last().click()
    await expect(rejectDialog.locator('.el-form-item__error')).toBeVisible({ timeout: 8_000 })
    await shot(page, 'V02-ta-apps-reject-dialog-error', 1280)
    await rejectDialog.getByRole('button', { name: '取消' }).click()

    // 退驻申请 Tab（空态）
    await page.locator('.kind-switch .el-radio-button', { hasText: '退驻申请' }).click()
    await page.waitForTimeout(500)
    await shot(page, 'V02-ta-apps-withdraw-tab-empty', 1280)
  })

  test('V-03 OPS黑名单页：列表/加黑弹窗/移除确认', async ({ page }) => {
    const tenant = await seedActiveTenant()
    // 造两行数据（手机号 + 执照号，覆盖长执照号溢出检查）
    ok(
      await opsAddBlacklist(tenant.ops.login.token, 'PHONE', uniqPhone(), '视觉验收样本：恶意刷单'),
      'V03 加黑手机号',
    )
    ok(
      await opsAddBlacklist(
        tenant.ops.login.token,
        'LICENSE_NO',
        `91330106MA2B${String(Date.now()).slice(-8)}XLONGCODE`,
        '视觉验收样本：伪造资质，原因文案较长用于检查省略与换行是否溢出撑破单元格',
      ),
      'V03 加黑执照号',
    )

    await injectAuthAndGoto(page, tenant.ops.login, 'OPS', '/ops/blacklist')
    await expect(page.locator('.el-table__row').first()).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V03-ops-blacklist-list')

    // 加黑弹窗（双键切换到执照号）
    await page.getByRole('button', { name: '加入黑名单' }).click()
    const dialog = page.locator('.el-dialog', { hasText: '加入黑名单' })
    await expect(dialog).toBeVisible()
    await shot(page, 'V03-ops-blacklist-add-dialog-phone', 1280)
    await dialog.locator('.el-radio-button', { hasText: '营业执照号' }).click()
    await shot(page, 'V03-ops-blacklist-add-dialog-license', 1280)
    await dialog.getByRole('button', { name: '取消' }).click()

    // 移除二次确认弹窗
    await page
      .locator('.el-table__row')
      .first()
      .getByRole('button', { name: '移除' })
      .click()
    await expect(page.locator('.el-message-box')).toBeVisible()
    await shot(page, 'V03-ops-blacklist-remove-confirm', 1280)
  })

  test('V-04 OPS租户审核页：列表/驳回弹窗', async ({ page }) => {
    // 新 TA 注册即产生 PENDING 租户（不审核，留作列表样本）
    const tenant = await seedActiveTenant()
    await registerWaPlain() // 无关账号，只为时间错位
    const pendingTaPhone = uniqPhone()
    const { registerWithRetry } = await import('./helpers/onboarding')
    await registerWithRetry(
      {
        phone: pendingTaPhone,
        password: 'OnbPass123',
        smsCode: '888888',
        role: 'TA',
        realName: '视觉样本',
        tenantName: '视觉验收待审仓' + pendingTaPhone.slice(-4),
        agreedTerms: true,
      },
      'V04 造待审租户',
    )

    await injectAuthAndGoto(page, tenant.ops.login, 'OPS', '/ops/tenant-audit')
    await expect(page.locator('.el-table__row').first()).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V04-ops-tenant-audit-list')

    // 驳回弹窗（理由必填）
    await page
      .locator('.el-table__row', { hasText: '视觉验收待审仓' })
      .first()
      .getByRole('button', { name: '驳回' })
      .click()
    const dialog = page.locator('.el-dialog', { hasText: '驳回' })
    await expect(dialog).toBeVisible()
    await shot(page, 'V04-ops-tenant-audit-reject-dialog', 1280)
  })

  test('V-05 WA退驻页：自查未通过/通过/确认弹窗/60天倒计时', async ({ page }) => {
    // 造：已入驻 WA + 库存（自查 ❌ 态）
    const tenant = await seedActiveTenant()
    const wa = await registerWaWithTarget(tenant.tenantId)
    const pending = await listTaApplications(tenant.ta.login.token)
    const approve = await auditApplication(tenant.ta.login.token, pending[0].id, 'APPROVED')
    const wholesalerId = String(approve.data.wholesalerId)
    const waLogin = ok(await apiLogin(wa.phone, wa.pwd), 'V05 WA 登录')
    const stock = await seedStockForWholesaler(tenant.ta.login.token, wholesalerId, 3)
    // P3 BE-W1：WK 代建入库停 PENDING_WA_CONFIRM（R13 未结单据），WA 先确认收尾，否则退驻自查会卡「存在未结单据」
    await confirmPendingInbound(waLogin.token)

    // 自查未通过态（库存 ❌ 提交置灰）
    await injectAuthAndGoto(page, waLogin, 'WA', '/wa/withdraw')
    await expect(page.getByText('仍有在库库存，请先清空')).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V05-withdraw-precheck-fail')

    // 清库存 → 自查通过态
    await sellOutStock(stock, waLogin.token)
    await page.getByRole('button', { name: '重新检查' }).click()
    await expect(page.getByText('在库库存已清零')).toBeVisible({ timeout: 15_000 })
    await shot(page, 'V05-withdraw-precheck-pass', 1280)

    // 提交确认弹窗
    await page.getByRole('button', { name: '提交退驻申请' }).click()
    const confirmDialog = page.locator('.el-dialog', { hasText: '确认提交退驻申请' })
    await expect(confirmDialog).toBeVisible()
    await shot(page, 'V05-withdraw-confirm-dialog', 1280)
    await confirmDialog.getByRole('button', { name: '确认提交' }).click()
    await expect(page.getByText('退驻审批中')).toBeVisible({ timeout: 15_000 })
    await shot(page, 'V05-withdraw-pending', 1280)

    // TA 审批通过（API）→ 60 天倒计时页
    const wdApps = await listTaWithdrawApps(tenant.ta.login.token)
    ok(await auditWithdraw(tenant.ta.login.token, wdApps[0].id, 'APPROVED'), 'V05 退驻审批')
    const fresh = ok(await apiLogin(wa.phone, wa.pwd), 'V05 退驻后登录')
    await injectAuthAndGoto(page, fresh, 'WA', '/wa/withdraw')
    await expect(page.locator('.countdown-box')).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V05-withdraw-countdown')
  })

  test('V-06 WA员工管理页：员工Tab/注册码Tab/生码弹窗', async ({ page }) => {
    // 造：已入驻 WA + 1 名 WE 员工（含授权开关行）
    const tenant = await seedActiveTenant()
    const wa = await registerWaWithTarget(tenant.tenantId)
    const pending = await listTaApplications(tenant.ta.login.token)
    ok(
      await auditApplication(tenant.ta.login.token, pending[0].id, 'APPROVED'),
      'V06 审批',
    )
    const waLogin = ok(await apiLogin(wa.phone, wa.pwd), 'V06 WA 登录')
    const { apiPost, registerWithRetry } = await import('./helpers/onboarding')
    const invite = ok(
      await apiPost<{ code: string }>(
        '/wholesaler/employee-invites',
        { maxUses: 5, expireDays: 7, permissions: ['INQUIRY_CONFIRM'] },
        waLogin.token,
      ),
      'V06 生码',
    )
    await registerWithRetry(
      {
        phone: uniqPhone(),
        password: 'OnbPass123',
        smsCode: '888888',
        role: 'WE',
        realName: '视觉员工',
        inviteCode: invite.code,
        agreedTerms: true,
      },
      'V06 WE 注册',
    )

    await injectAuthAndGoto(page, waLogin, 'WA', '/wa/staff')
    await expect(page.locator('.el-table__row').first()).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V06-staff-employees')

    // 注册码 Tab
    await page.getByRole('tab', { name: '注册码' }).click()
    await page.waitForTimeout(400)
    await shot(page, 'V06-staff-invites', 1280)

    // 生码弹窗（默认仅勾询价确认）
    await page.getByRole('button', { name: '生成注册码' }).click()
    const dialog = page.locator('.el-dialog', { hasText: '生成员工注册码' })
    await expect(dialog).toBeVisible()
    await shot(page, 'V06-staff-create-dialog', 1280)
  })

  test('V-07 TA商户列表：下架弹窗（双条件解锁）', async ({ page }) => {
    const tenant = await seedActiveTenant()
    const wa = await registerWaWithTarget(tenant.tenantId)
    const pending = await listTaApplications(tenant.ta.login.token)
    ok(await auditApplication(tenant.ta.login.token, pending[0].id, 'APPROVED'), 'V07 审批')

    await injectAuthAndGoto(page, tenant.ta.login, 'TA', '/ta/wholesalers')
    const row = page.locator('.el-table__row', { hasText: wa.wholesalerName })
    await expect(row).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V07-wholesalers-list')

    // 强制下架弹窗：初始 → 名称不一致红提示态
    await row.getByRole('button', { name: '强制下架' }).click()
    const dialog = page.locator('.el-dialog', { hasText: '强制下架商户' })
    await expect(dialog).toBeVisible()
    await shot(page, 'V07-wholesalers-offline-dialog', 1280)
    await dialog.locator('textarea').fill('短') // <5 字 → 红提示
    await dialog.locator('input[maxlength="50"]').fill('名称不匹配样本')
    await page.waitForTimeout(300)
    await shot(page, 'V07-wholesalers-offline-dialog-invalid', 1280)
  })

  test('V-08 WE注册流适配：预填页/375断点', async ({ page }) => {
    await page.goto('/register?role=we')
    await page.waitForLoadState('networkidle')
    await expect(page.getByText('批发商员工（受邀注册）')).toBeVisible({ timeout: 15_000 })
    await shotAllBreakpoints(page, 'V08-we-register')
  })
})
