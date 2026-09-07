<script setup lang="ts">
// RT 买家登录 / 注册（F6 RT 登录子波 · D-49/D-50 免密验证码）：
// POST /account/login/rt?phone=&code= —— 首次登录自动建 RT 账号（无密码）。
// 登录成功后：本地身份手机号 = 登录手机号；意向单被商户确认（CONFIRMED/COMPLETED）
// 即可查看批发商联系方式线下成交（US-RT-04 验收 · D-RT-01）。
import { computed, ref } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import { PHONE_MAX_LEN, PHONE_RE } from '../../../config'
import { accountApi } from '../../../api/account'
import { clearAuth, hasRtScope, writeAuth } from '../../../utils/session'
import { savePhone } from '../../../utils/storage'

const phoneInput = ref('')
const codeInput = ref('')
const cooldown = ref(0)
const sending = ref(false)
const submitting = ref(false)
const rtLogged = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const canSend = computed(() => PHONE_RE.test(phoneInput.value.trim()) && cooldown.value <= 0 && !sending.value)
const canSubmit = computed(() => PHONE_RE.test(phoneInput.value.trim()) && codeInput.value.trim().length >= 4 && !submitting.value)

function maskPhone(p: string): string {
  return p.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2')
}

onShow(() => {
  rtLogged.value = hasRtScope()
  if (phoneInput.value) return
  // 登录页预填：优先回填本地身份手机号，减少输入
  try {
    const saved = uni.getStorageSync('cc_rt_phone') as string
    if (saved) phoneInput.value = saved
  } catch {
    // 忽略
  }
})

function startCooldown(): void {
  cooldown.value = 60
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function onSendCode(): Promise<void> {
  const p = phoneInput.value.trim()
  if (!PHONE_RE.test(p)) {
    uni.showToast({ title: '请输入正确的 11 位手机号', icon: 'none' })
    return
  }
  sending.value = true
  try {
    await accountApi.sendRtSmsCode(p)
    uni.showToast({ title: '验证码已发送', icon: 'none' })
    startCooldown()
  } catch {
    // request 已 toast（如发送频繁）
  } finally {
    sending.value = false
  }
}

function goBackHome(): void {
  const pages = getCurrentPages()
  if (pages.length > 1) {
    uni.navigateBack()
  } else {
    uni.reLaunch({ url: '/pages/index/index' })
  }
}

async function onSubmit(): Promise<void> {
  const p = phoneInput.value.trim()
  const code = codeInput.value.trim()
  if (!PHONE_RE.test(p) || code.length < 4) {
    uni.showToast({ title: '请先输入正确的手机号与验证码', icon: 'none' })
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    const res = await accountApi.rtLogin({ phone: p, code })
    savePhone(p)
    writeAuth(res)
    rtLogged.value = true
    uni.showToast({ title: res.isNew ? '登录成功，已创建买家账号' : '登录成功', icon: 'none' })
    setTimeout(goBackHome, 500)
  } catch {
    // request 已 toast
  } finally {
    submitting.value = false
  }
}

function onLogout(): void {
  uni.showModal({
    title: '退出买家账号',
    content: '退出后仍可用本地手机号浏览店铺与意向单，但无法查看已确认商户的电话。',
    confirmColor: '#cc3b3b',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await accountApi.logout()
      } catch {
        // 忽略：token 可能已失效
      }
      clearAuth()
      rtLogged.value = false
      uni.showToast({ title: '已退出', icon: 'none' })
    },
  })
}

function goInquiries(): void {
  uni.reLaunch({ url: '/pages/rt/inquiries/index' })
}

onUnload(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="page">
    <!-- 品牌区 -->
    <view class="hero">
      <text class="hero__title">买家登录</text>
      <text class="hero__sub">意向单被商户确认后，登录即可联系批发商线下成交</text>
    </view>

    <!-- 已登录态 -->
    <view v-if="rtLogged" class="card">
      <view class="ok-head">
        <text class="ok-head__dot">✓</text>
        <view class="ok-head__main">
          <text class="ok-head__title">买家账号已登录</text>
          <text class="ok-head__sub">当前账号 {{ maskPhone(phoneInput || '') }}</text>
        </view>
      </view>
      <view class="ok-actions">
        <button class="btn-primary btn-block" @click="goInquiries">查看我的意向单</button>
        <button class="btn-ghost btn-block" @click="onLogout">退出登录</button>
      </view>
    </view>

    <!-- 登录表单 -->
    <view v-else class="card">
      <view class="form">
        <input
          v-model="phoneInput"
          class="field"
          type="number"
          :maxlength="PHONE_MAX_LEN"
          placeholder="输入 11 位手机号"
          placeholder-class="ph"
        />
        <view class="code-row">
          <input
            v-model="codeInput"
            class="field code-row__input"
            type="number"
            maxlength="6"
            placeholder="短信验证码"
            placeholder-class="ph"
            confirm-type="done"
            @confirm="onSubmit"
          />
          <button class="code-btn" :disabled="!canSend" :class="{ 'code-btn--off': !canSend }" @click="onSendCode">
            {{ cooldown > 0 ? `${cooldown}s 后重发` : '获取验证码' }}
          </button>
        </view>
        <button class="btn-primary btn-block" :disabled="!canSubmit" @click="onSubmit">
          {{ submitting ? '登录中…' : '登录 / 一键注册' }}
        </button>
        <text class="form__tip">未注册的手机号验证后将自动创建买家账号；已登录后意向单确认即可查看批发商电话</text>
        <view class="back-link" @click="goBackHome">
          <text>暂不登录，先逛逛店铺 ›</text>
        </view>
      </view>
    </view>

    <!-- 隐私说明 -->
    <view class="foot-note">
      <text>登录即代表同意用户协议与隐私政策。批发商联系方式仅在您的意向单被确认后展示，用于线下成交。</text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  padding-bottom: 60rpx;
  background: $cc-bg-2;
  box-sizing: border-box;
}

.hero {
  margin: 8rpx 0 24rpx;
  padding: 40rpx 32rpx;
  border-radius: 24rpx;
  background: linear-gradient(135deg, #047857 0%, $cc-rt-accent 55%, #10b981 100%);
  color: #fff;
  display: flex;
  flex-direction: column;
  gap: 12rpx;

  &__title {
    font-size: 40rpx;
    font-weight: 700;
    letter-spacing: 1rpx;
  }
  &__sub {
    font-size: 26rpx;
    color: rgba(255, 255, 255, 0.88);
  }
}

.card {
  padding: 28rpx 24rpx;
  background: $cc-bg-1;
  border-radius: $cc-card-radius;
  box-shadow: 0 2rpx 12rpx rgba(2, 6, 23, 0.04);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 20rpx;

  &__tip {
    font-size: 22rpx;
    line-height: 1.6;
    color: $cc-fg-4;
  }
}

.field {
  width: 100%;
  height: 92rpx;
  padding: 0 24rpx;
  border: 2rpx solid $cc-border-1;
  border-radius: 12rpx;
  background: #fff;
  font-size: 30rpx;
  letter-spacing: 2rpx;
  color: $cc-fg-1;
  box-sizing: border-box;
}

.ph {
  color: $cc-fg-4;
}

.code-row {
  display: flex;
  align-items: center;
  gap: 16rpx;

  &__input {
    flex: 1;
    letter-spacing: 6rpx;
  }
}

.code-btn {
  width: 220rpx;
  height: 92rpx;
  margin: 0;
  line-height: 92rpx;
  border-radius: 12rpx;
  background: #ecfdf5;
  color: $cc-rt-accent;
  font-size: 26rpx;
  font-weight: 600;

  &::after {
    border: none;
  }

  &--off {
    background: $cc-bg-3;
    color: $cc-fg-3;
    font-weight: 400;
  }
}

.btn-primary,
.btn-ghost {
  margin: 0;
  border-radius: 12rpx;
  font-size: 30rpx;
  font-weight: 600;

  &::after {
    border: none;
  }
}

.btn-primary {
  height: 92rpx;
  line-height: 92rpx;
  background: $cc-rt-accent;
  color: #fff;
}

.btn-ghost {
  height: 92rpx;
  line-height: 92rpx;
  background: $cc-bg-3;
  color: $cc-fg-2;
  font-weight: 500;
}

.btn-block {
  width: 100%;
}

.back-link {
  padding: 8rpx 4rpx 0;
  font-size: 26rpx;
  color: $cc-accent;
  text-align: center;
}

/* ===== 已登录 ===== */
.ok-head {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 8rpx 4rpx 24rpx;

  &__dot {
    width: 56rpx;
    height: 56rpx;
    border-radius: 50%;
    background: $cc-success-bg;
    color: $cc-success;
    font-size: 30rpx;
    text-align: center;
    line-height: 56rpx;
    font-weight: 700;
  }

  &__main {
    display: flex;
    flex-direction: column;
    gap: 6rpx;
  }

  &__title {
    font-size: 30rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__sub {
    font-size: 24rpx;
    color: $cc-fg-3;
  }
}

.ok-actions {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.foot-note {
  margin-top: 24rpx;
  padding: 0 12rpx;
  font-size: 22rpx;
  line-height: 1.7;
  color: $cc-fg-4;
}
</style>
