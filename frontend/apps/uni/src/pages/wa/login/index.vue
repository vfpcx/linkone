<script setup lang="ts">
// 商户/结算工作台统一登录（F3 WA/WE · F4 ST 共用本页）：密码登录。
// 员工密码由店主/仓库管理员在电脑端分配；本端服务批发商（WA/WE）与结算员（ST），
// TA/OPS/WK 等其他角色请使用电脑端（WK 移动端待 F5）。
import { ref } from 'vue'
import { accountApi } from '../../../api/account'
import { PHONE_MAX_LEN, PHONE_RE } from '../../../config'
import { clearAuth, hasStScope, hasWaScope, writeAuth } from '../../../utils/session'

const phone = ref('')
const password = ref('')
const showPwd = ref(false)
const submitting = ref(false)

function doLogin(): void {
  const p = phone.value.trim()
  if (!PHONE_RE.test(p)) {
    uni.showToast({ title: '请输入正确的 11 位手机号', icon: 'none' })
    return
  }
  if (!password.value) {
    uni.showToast({ title: '请输入密码', icon: 'none' })
    return
  }
  if (submitting.value) return
  submitting.value = true
  accountApi
    .login({ phone: p, password: password.value })
    .then((res) => {
      writeAuth(res)
      // 落地页按可用工作区角色分流：结算员 ST → 结算工作台；批发商 WA/WE → 批发商工作台。
      // （账号同时具备多区角色时取 priority 小者——结算 ST 优先，跨区切换需重新登录，v1 约定）
      if (hasStScope()) {
        uni.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => uni.reLaunch({ url: '/pages/st/index' }), 500)
        return
      }
      if (hasWaScope()) {
        uni.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => uni.reLaunch({ url: '/pages/wa/index' }), 500)
        return
      }
      clearAuth()
      uni.showToast({ title: '该账号请使用电脑端登录', icon: 'none' })
    })
    .catch(() => {
      /* 错误已由 request toast */
    })
    .finally(() => {
      submitting.value = false
    })
}

function goBuyer(): void {
  uni.reLaunch({ url: '/pages/index/index' })
}
</script>

<template>
  <view class="page">
    <view class="brand">
      <view class="brand__logo">仓</view>
      <view class="brand__name">仓储云工作台</view>
      <view class="brand__sub">批发商处理询价 · 结算员登记回款</view>
    </view>

    <view class="card">
      <view class="field">
        <input
          v-model="phone"
          class="field__input"
          type="number"
          :maxlength="PHONE_MAX_LEN"
          placeholder="登录手机号"
          placeholder-class="ph"
        />
      </view>
      <view class="field">
        <input
          v-model="password"
          class="field__input"
          :password="!showPwd"
          placeholder="登录密码"
          placeholder-class="ph"
          confirm-type="done"
          @confirm="doLogin"
        />
        <view class="field__eye" @click="showPwd = !showPwd">{{ showPwd ? '隐藏' : '显示' }}</view>
      </view>
      <button class="btn-primary login-btn" :loading="submitting" @click="doLogin">登录</button>
      <view class="tips">
        <text class="tips__t">员工账号由店主 / 仓库管理员在电脑端分配；忘记密码请联系所在仓库管理员</text>
      </view>
    </view>

    <view class="buyer-entry" @click="goBuyer">以买家身份逛店 ›</view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 0 40rpx;
  background: linear-gradient(170deg, $cc-brand-primary 0%, $cc-brand-primary 40%, $cc-bg-2 40.2%);
  box-sizing: border-box;
}

.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  padding: 130rpx 0 70rpx;

  &__logo {
    width: 112rpx;
    height: 112rpx;
    line-height: 112rpx;
    text-align: center;
    border-radius: 28rpx;
    background: #fff;
    color: $cc-brand-primary;
    font-size: 58rpx;
    font-weight: 700;
    margin-bottom: 20rpx;
  }

  &__name {
    color: #fff;
    font-size: 38rpx;
    font-weight: 700;
  }

  &__sub {
    color: rgba(255, 255, 255, 0.75);
    font-size: 24rpx;
  }
}

.card {
  padding: 34rpx 28rpx 24rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 8rpx 30rpx rgba(2, 6, 23, 0.08);
}

.field {
  position: relative;
  display: flex;
  align-items: center;
  height: 100rpx;
  padding: 0 24rpx;
  border: 2rpx solid $cc-border-1;
  border-radius: 12rpx;
  margin-bottom: 24rpx;

  &__input {
    flex: 1;
    font-size: 30rpx;
    color: $cc-fg-1;
  }

  &__eye {
    padding: 8rpx 0 8rpx 20rpx;
    font-size: 24rpx;
    color: $cc-fg-3;
  }
}

.ph {
  color: $cc-fg-4;
}

.login-btn {
  margin-top: 6rpx;
  height: 96rpx;
  font-size: 32rpx;
  font-weight: 600;
}

.tips {
  margin-top: 22rpx;

  &__t {
    font-size: 22rpx;
    color: $cc-fg-4;
    line-height: 1.6;
  }
}

.buyer-entry {
  margin-top: 80rpx;
  text-align: center;
  font-size: 26rpx;
  color: $cc-fg-3;
  text-decoration: underline;
}
</style>
