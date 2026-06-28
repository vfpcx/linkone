<script setup lang="ts">
/**
 * 找回密码（两步走）
 *
 * 来源：
 *  - 线框：shared/product/06-page-wireframes.md §0.5.3
 *  - 故事：US-COMMON-04
 *
 * 步骤 1：验证手机号 + 验证码（前端先记住，不发后端）
 * 步骤 2：设置新密码 × 2 → 调 reset-password
 */

import { ref, reactive, computed, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { z } from 'zod'
import { accountApi } from '@/api/account'
import { ApiError } from '@/api/http'

const router = useRouter()

// ============ Step ============
const step = ref<1 | 2>(1)

// ============ Step 1 ============
const step1Ref = ref<FormInstance>()
const step1 = reactive({ phone: '', smsCode: '' })

const phoneSchema = z.string().regex(/^1[3-9]\d{9}$/, '手机号格式不正确')

const step1Rules: FormRules = {
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
  smsCode: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{4,6}$/, message: '验证码为 4-6 位数字', trigger: 'blur' },
  ],
}

const smsCooldown = ref(0)
let smsTimer: ReturnType<typeof setInterval> | null = null

const sendSms = async () => {
  const r = phoneSchema.safeParse(step1.phone)
  if (!r.success) {
    ElMessage.warning(r.error.issues[0]?.message ?? '手机号格式不正确')
    return
  }
  try {
    const res = await accountApi.sendSmsCode({
      phone: step1.phone,
      scene: 'RESET_PASSWORD',
    })
    smsCooldown.value = res.cooldownSec || 60
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
    ElMessage.success('验证码已发送（如手机号已注册）')
  } catch {
    /* 全局 toast */
  }
}

const sendSmsBtnText = computed(() =>
  smsCooldown.value > 0 ? `${smsCooldown.value}s 后重试` : '发送验证码',
)

const onNext = async () => {
  if (!step1Ref.value) return
  const valid = await step1Ref.value.validate().catch(() => false)
  if (!valid) return
  step.value = 2
}

// ============ Step 2 ============
const step2Ref = ref<FormInstance>()
const step2 = reactive({ newPassword: '', confirmPassword: '' })
const loading = ref(false)

const passwordSchema = z
  .string()
  .min(6, '密码至少 6 位')
  .max(20, '密码最多 20 位')
  .regex(/[a-zA-Z]/, '密码必须包含字母')
  .regex(/\d/, '密码必须包含数字')

const step2Rules: FormRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        const r = passwordSchema.safeParse(v)
        cb(r.success ? undefined : new Error(r.error.issues[0]?.message))
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v !== step2.newPassword) cb(new Error('两次输入密码不一致'))
        else cb()
      },
      trigger: 'blur',
    },
  ],
}

const onComplete = async () => {
  if (!step2Ref.value) return
  const valid = await step2Ref.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    // 后端 ResetPasswordDto 无 confirmPassword（两次一致性已在前端校验）
    await accountApi.resetPassword({
      phone: step1.phone,
      smsCode: step1.smsCode,
      newPassword: step2.newPassword,
    })
    ElMessage.success('密码已修改，所有设备需重新登录')
    setTimeout(() => {
      router.replace('/login')
    }, 1500)
  } catch (e) {
    if (e instanceof ApiError) {
      // 41201 / 41202 等验证码错误 → 回到 step 1
      if (e.code === 41201 || e.code === 41202 || e.code === 41203) {
        step.value = 1
      }
    }
  } finally {
    loading.value = false
  }
}

onBeforeUnmount(() => {
  smsTimer && clearInterval(smsTimer)
})
</script>

<template>
  <div class="auth-page">
    <aside class="auth-brand">
      <div class="auth-brand__content">
        <h1 class="auth-brand__logo">仓储云</h1>
        <p class="auth-brand__slogan">让仓储更智能</p>
      </div>
      <footer class="auth-brand__footer">© 2026 仓储云</footer>
    </aside>

    <main class="auth-form">
      <div class="auth-card">
        <div class="auth-card__back">
          <router-link to="/login">← 返回登录</router-link>
        </div>
        <h2 class="auth-card__title">找回密码</h2>

        <el-steps :active="step - 1" finish-status="success" class="steps">
          <el-step title="验证手机号" />
          <el-step title="重置密码" />
        </el-steps>

        <el-form
          v-show="step === 1"
          ref="step1Ref"
          :model="step1"
          :rules="step1Rules"
          label-position="top"
          @submit.prevent="onNext"
        >
          <el-form-item label="手机号" prop="phone">
            <el-input
              v-model="step1.phone"
              placeholder="请输入注册手机号"
              size="large"
              maxlength="11"
              inputmode="tel"
            />
          </el-form-item>

          <el-form-item label="验证码" prop="smsCode">
            <div class="sms-row">
              <el-input
                v-model="step1.smsCode"
                placeholder="请输入短信验证码"
                size="large"
                maxlength="6"
                inputmode="numeric"
              />
              <el-button size="large" :disabled="smsCooldown > 0" class="sms-btn" @click="sendSms">
                {{ sendSmsBtnText }}
              </el-button>
            </div>
          </el-form-item>

          <el-button type="primary" size="large" class="submit-btn" @click="onNext">
            下一步
          </el-button>
        </el-form>

        <el-form
          v-show="step === 2"
          ref="step2Ref"
          :model="step2"
          :rules="step2Rules"
          label-position="top"
          @submit.prevent="onComplete"
        >
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="step2.newPassword"
              type="password"
              show-password
              placeholder="6-20 位，含字母 + 数字"
              size="large"
              autocomplete="new-password"
            />
          </el-form-item>

          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input
              v-model="step2.confirmPassword"
              type="password"
              show-password
              placeholder="再次输入新密码"
              size="large"
              autocomplete="new-password"
            />
          </el-form-item>

          <div class="step2-actions">
            <el-button size="large" @click="step = 1">上一步</el-button>
            <el-button
              type="primary"
              size="large"
              :loading="loading"
              :disabled="!step2.newPassword || !step2.confirmPassword"
              @click="onComplete"
            >
              完&nbsp;成
            </el-button>
          </div>
        </el-form>
      </div>
    </main>
  </div>
</template>

<style scoped>
@import './auth-shared.scss';

.auth-card__back {
  margin-bottom: var(--space-3);
}
.auth-card__back a {
  color: var(--color-fg-3);
  font-size: var(--font-size-body);
}

.steps {
  margin: var(--space-4) 0 var(--space-6);
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

.step2-actions {
  display: flex;
  gap: var(--space-3);
}
.step2-actions .el-button {
  flex: 1;
  height: 48px;
}
</style>
