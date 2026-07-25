<script setup lang="ts">
/**
 * 登录页（PC）
 *
 * 来源：
 *  - 线框：shared/product/06-page-wireframes.md §0.5.1
 *  - 视觉：shared/design-system/pages/auth.md（PC 50/50 + 左侧品牌色压底）
 *  - 故事：US-COMMON-02
 *
 * 关键特性：
 *  - 双 Tab：密码登录 / 验证码登录
 *  - 字段校验在 blur 触发
 *  - 字段错误显示输入框下方
 *  - 后端错误用顶部 Alert
 *  - 5 次锁定后弹 Modal 提示剩余时间 + 找回密码入口
 *  - 多角色登录后弹 WorkspaceSwitcher
 */

import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { z } from 'zod'
import { AuthShell } from '@cangchu/ui-shared'
import { accountApi } from '@/api/account'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import { useWarehouseStore } from '@/stores/warehouse'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const warehouseStore = useWarehouseStore()

// ============ Tab 切换 ============
type LoginMode = 'password' | 'sms'
const mode = ref<LoginMode>('password')

// ============ 表单 ============
const formRef = ref<FormInstance>()
const form = reactive({
  phone: '',
  password: '',
  smsCode: '',
  remember30Days: false,
})

const alertMessage = ref('')
const alertType = ref<'error' | 'warning' | 'info'>('error')
const loading = ref(false)

// Zod schema（业务校验）
const phoneSchema = z.string().regex(/^1[3-9]\d{9}$/, '手机号格式不正确')
const passwordSchema = z
  .string()
  .min(6, '密码至少 6 位')
  .max(20, '密码最多 20 位')
  .regex(/[a-zA-Z]/, '密码必须包含字母')
  .regex(/\d/, '密码必须包含数字')

const rules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        const r = phoneSchema.safeParse(v)
        cb(r.success ? undefined : new Error(r.error.issues[0]?.message))
      },
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (mode.value !== 'password') return cb()
        const r = passwordSchema.safeParse(v)
        cb(r.success ? undefined : new Error(r.error.issues[0]?.message))
      },
      trigger: 'blur',
    },
  ],
  smsCode: [
    {
      validator: (_r, v, cb) => {
        if (mode.value !== 'sms') return cb()
        if (!v) return cb(new Error('请输入验证码'))
        if (!/^\d{4,6}$/.test(v)) return cb(new Error('验证码为 4-6 位数字'))
        cb()
      },
      trigger: 'blur',
    },
  ],
}

// ============ 验证码倒计时 ============
const smsCooldown = ref(0)
let smsTimer: ReturnType<typeof setInterval> | null = null

const startCooldown = (sec = 60) => {
  smsCooldown.value = sec
  smsTimer && clearInterval(smsTimer)
  smsTimer = setInterval(() => {
    if (smsCooldown.value <= 1) {
      smsCooldown.value = 0
      smsTimer && clearInterval(smsTimer)
      smsTimer = null
    } else {
      smsCooldown.value--
    }
  }, 1000)
}

const sendSmsBtnText = computed(() =>
  smsCooldown.value > 0 ? `${smsCooldown.value}s 后重试` : '发送验证码',
)

const sendSms = async () => {
  const r = phoneSchema.safeParse(form.phone)
  if (!r.success) {
    ElMessage.warning(r.error.issues[0]?.message ?? '手机号格式不正确')
    return
  }
  try {
    const res = await accountApi.sendSmsCode({ phone: form.phone, scene: 'LOGIN' })
    startCooldown(res.cooldownSec || 60)
    ElMessage.success('验证码已发送')
  } catch (e) {
    if (e instanceof ApiError) {
      // 41204 / 41205 等错误已被全局 toast，不重复
    }
  }
}

// ============ 登录 ============
const onSubmit = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  alertMessage.value = ''
  loading.value = true
  try {
    let payload
    if (mode.value === 'password') {
      payload = await accountApi.login({
        phone: form.phone,
        password: form.password,
        device: 'PC',
        deviceInfo: navigator.userAgent.slice(0, 200),
      })
    } else {
      // 验证码登录走 RT 接口（后端用 query 参数 phone/code）
      payload = await accountApi.rtSmsLogin({
        phone: form.phone,
        code: form.smsCode,
      })
    }

    auth.setLoginPayload(payload)
    // 换账号：清空上一用户的多仓状态，避免内存残留 + 强制切换器重新拉取
    warehouseStore.clear()
    ElMessage.success('登录成功')

    // 多角色：弹切换器
    if (auth.hasMultipleRoles) {
      auth.showSwitcher()
      return
    }

    const redirect = (route.query.redirect as string) || auth.primaryRouter || '/ta/dashboard'
    router.replace(redirect)
  } catch (e) {
    if (e instanceof ApiError) {
      handleLoginError(e)
    } else {
      // 非 ApiError（如 getter/字段缺失抛错、网络异常）不静默吞掉
      console.error('[login] 非预期异常', e)
      ElMessage.error('登录异常，请重试')
    }
  } finally {
    loading.value = false
  }
}

function handleLoginError(e: ApiError) {
  // 账号锁定弹 Modal
  if (e.code === ErrorCode.AUTH_ACCOUNT_LOCKED) {
    const lockMinutes = (e.details?.lockoutAt as number | undefined) ?? 15
    ElMessageBox.confirm(
      `账号已锁定，请 ${lockMinutes} 分钟后重试。忘记密码？立即找回。`,
      '登录受限',
      {
        confirmButtonText: '找回密码',
        cancelButtonText: '我知道了',
        type: 'warning',
      },
    )
      .then(() => router.push('/forgot-password'))
      .catch(() => undefined)
    return
  }

  // 41101 账号密码错误 + remainAttempts
  if (e.code === ErrorCode.AUTH_INVALID_CREDENTIALS) {
    const remain = e.details?.remainAttempts as number | undefined
    alertMessage.value =
      remain !== undefined ? `账号或密码错误，还可尝试 ${remain} 次` : '账号或密码错误'
    alertType.value = 'error'
    return
  }

  // 41110 全部角色被禁用（R17 员工禁用后登录拒绝）
  if (e.code === ErrorCode.AUTH_ALL_ROLES_DISABLED) {
    alertMessage.value = '账号已被禁用，请联系商户管理员'
    alertType.value = 'error'
    return
  }

  // 41201 验证码过期、41202 验证码错误
  if (
    e.code === ErrorCode.AUTH_SMS_EXPIRED ||
    e.code === ErrorCode.AUTH_SMS_WRONG ||
    e.code === ErrorCode.AUTH_SMS_NOT_FOUND
  ) {
    alertMessage.value = e.errorMessage
    alertType.value = 'warning'
    return
  }

  // 其他统一 Alert
  alertMessage.value = e.errorMessage
  alertType.value = 'error'
}

onMounted(() => {
  // 如果已带 redirect 且已登录，直接跳
  if (auth.isAuthenticated) {
    router.replace(auth.primaryRouter || '/ta/dashboard')
  }
})

onBeforeUnmount(() => {
  smsTimer && clearInterval(smsTimer)
})
</script>

<template>
  <AuthShell slogan="通用仓储 SaaS 平台 · 让仓储更智能">
    <template #bullets>
      <ul class="auth-bullets">
        <li>多端协同：PC / H5 / 微信小程序一体化</li>
        <li>全链路：入驻 · 入库 · 出库 · 询价 · 账单</li>
        <li>多角色：平台运维 / 租户管理员 / 库管员 / 结算员 / 批发商管理员 / 批发商员工 / 终端买家</li>
      </ul>
    </template>

        <h2 class="auth-card__title">欢迎登录</h2>

        <el-tabs v-model="mode" class="auth-tabs">
          <el-tab-pane label="密码登录" name="password" />
          <el-tab-pane label="验证码登录" name="sms" />
        </el-tabs>

        <el-alert
          v-if="alertMessage"
          :title="alertMessage"
          :type="alertType"
          show-icon
          :closable="false"
          class="auth-alert"
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="onSubmit"
        >
          <el-form-item label="手机号" prop="phone">
            <el-input
              v-model="form.phone"
              placeholder="请输入手机号"
              size="large"
              maxlength="11"
              inputmode="tel"
              autocomplete="username"
            />
          </el-form-item>

          <el-form-item v-if="mode === 'password'" label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              size="large"
              show-password
              autocomplete="current-password"
              @keyup.enter="onSubmit"
            />
          </el-form-item>

          <el-form-item v-else label="验证码" prop="smsCode">
            <div class="sms-row">
              <el-input
                v-model="form.smsCode"
                placeholder="请输入验证码"
                size="large"
                maxlength="6"
                inputmode="numeric"
                @keyup.enter="onSubmit"
              />
              <el-button
                size="large"
                :disabled="smsCooldown > 0"
                class="sms-btn"
                @click="sendSms"
              >
                {{ sendSmsBtnText }}
              </el-button>
            </div>
          </el-form-item>

          <div class="auth-options">
            <el-checkbox v-model="form.remember30Days">30 天内自动登录</el-checkbox>
            <router-link to="/forgot-password" class="forgot-link">忘记密码?</router-link>
          </div>

          <el-button
            type="primary"
            size="large"
            class="submit-btn"
            :loading="loading"
            native-type="submit"
            @click="onSubmit"
          >
            登&nbsp;录
          </el-button>

          <p class="auth-footer">
            新用户?
            <router-link to="/register?role=ta">注册</router-link>
          </p>
        </el-form>
  </AuthShell>
</template>

<style scoped>
@import './auth-shared.scss';

/* hero 卖点列表（AuthShell #bullets 插槽内容，窄屏由壳层隐藏） */
.auth-bullets {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.auth-bullets li {
  font-size: 16px;
  opacity: 0.78;
  padding-left: 24px;
  position: relative;
}

.auth-bullets li::before {
  content: '✓';
  position: absolute;
  left: 0;
  color: var(--color-success);
  font-weight: var(--font-weight-bold);
}

/* 登录页标题与 tabs 间距覆写（共享默认 space-3） */
.auth-card__title {
  margin-bottom: var(--space-6);
}

.auth-tabs {
  margin-bottom: var(--space-6);
}

.auth-alert {
  margin-bottom: var(--space-4);
}

.sms-row {
  display: flex;
  gap: var(--space-3);
  width: 100%;
}

.sms-row .el-input {
  flex: 1;
}

.sms-btn {
  width: 130px;
  flex-shrink: 0;
}

.auth-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: var(--space-2) 0 var(--space-6);
}

.forgot-link {
  font-size: var(--font-size-body);
  color: var(--color-brand-accent);
}

:deep(.el-input__wrapper) {
  border-radius: var(--radius-base);
}
</style>
