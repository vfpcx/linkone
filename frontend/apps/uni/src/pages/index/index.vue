<script setup lang="ts">
// RT 买家首页（F2 正式多端；F6 加登录态）：
// 输入店铺码 / 扫店铺码进店，直达 pages/rt/store；手机号为本地身份（询价/价目/意向单复用）。
// F6：RT 免密登录后（hasRtScope），本地手机号锁定为登录账号，意向单确认即可查批发商电话（D-RT-01）。
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { PHONE_MAX_LEN, PHONE_RE } from '../../config'
import { accountApi } from '../../api/account'
import { clearAuth, hasRtScope } from '../../utils/session'
import { getRecentStores, getSavedPhone, savePhone, type RecentStore } from '../../utils/storage'

const codeInput = ref('')
const phoneInput = ref('')
const phoneOk = ref(false)
const recentStores = ref<RecentStore[]>([])
const entering = ref(false)
const rtLogged = ref(false)

onShow(() => {
  recentStores.value = getRecentStores()
  rtLogged.value = hasRtScope()
  if (!phoneInput.value) {
    phoneInput.value = getSavedPhone()
    phoneOk.value = PHONE_RE.test(phoneInput.value)
  }
})

function maskPhone(p: string): string {
  return p.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2')
}

/** 提取店铺码：支持纯码，或含 ?code= 的分享链接（进店短链/分享页）。 */
function extractCode(raw: string): string {
  const t = raw.trim()
  const m = t.match(/[?&#]code=([0-9a-zA-Z]+)/i)
  return m ? m[1] : t
}

function validCode(c: string): boolean {
  return /^[0-9a-zA-Z]{2,16}$/.test(c)
}

function goStore(code: string): void {
  if (entering.value) return
  const c = extractCode(code)
  if (!validCode(c)) {
    uni.showToast({ title: '店铺码不正确，请核对后重试', icon: 'none' })
    return
  }
  entering.value = true
  uni.navigateTo({
    url: `/pages/rt/store/index?code=${encodeURIComponent(c)}`,
    complete: () => {
      entering.value = false
    },
  })
}

function onEnter(): void {
  goStore(codeInput.value)
}

function onRecent(code: string): void {
  goStore(code)
}

/** 保存/更新本地手机号（身份），供询价与意向单使用 */
function onSavePhone(): void {
  const p = phoneInput.value.trim()
  if (!PHONE_RE.test(p)) {
    uni.showToast({ title: '请输入正确的 11 位手机号', icon: 'none' })
    return
  }
  savePhone(p)
  phoneOk.value = true
  uni.showToast({ title: '手机号已保存', icon: 'none' })
}

/** 商户/结算/库管工作台登录入口（WA/WE/ST/WK 统一登录页） */
function goBiz(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

/** F6：买家账号登录页（免密验证码，首登自动注册） */
function goRtLogin(): void {
  uni.navigateTo({ url: '/pages/rt/login/index' })
}

/** F6：退出买家账号（保留本地身份手机号，仅清登录会话） */
function onLogout(): void {
  uni.showModal({
    title: '退出买家账号',
    content: '退出后仍可浏览店铺与意向单，但无法查看已确认商户的电话。',
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
      phoneInput.value = getSavedPhone()
      phoneOk.value = PHONE_RE.test(phoneInput.value)
      uni.showToast({ title: '已退出', icon: 'none' })
    },
  })
}

function onScan(): void {
  // #ifdef MP-WEIXIN
  uni.scanCode({
    success: (res) => {
      if (res.result) goStore(extractCode(res.result))
    },
    fail: () => {
      uni.showToast({ title: '未识别到店铺码', icon: 'none' })
    },
  })
  // #endif
  // #ifndef MP-WEIXIN
  uni.showToast({ title: '请在微信内扫码进店，或输入店铺码', icon: 'none' })
  // #endif
}
</script>

<template>
  <view class="page">
    <!-- 品牌区 -->
    <view class="hero">
      <text class="hero__title">仓储云 · 买家直批</text>
      <text class="hero__sub">批发商在库实价直连，扫码进店提交意向单</text>
    </view>

    <!-- 进店卡 -->
    <view class="card">
      <view class="card__title">进店逛货</view>
      <view class="code-row">
        <input
          v-model="codeInput"
          class="code-input"
          placeholder="输入店铺码，如 wh01"
          placeholder-class="ph"
          maxlength="16"
          confirm-type="go"
          @confirm="onEnter"
        />
        <button class="btn-primary" :loading="entering" @click="onEnter">进店</button>
      </view>
      <button class="scan-btn" @click="onScan">
        <text class="scan-btn__icon">⌗</text>
        <text>扫店铺码进店（微信内可用）</text>
      </button>

      <view v-if="recentStores.length" class="recent">
        <text class="recent__label">最近逛过</text>
        <view class="recent__list">
          <view v-for="s in recentStores" :key="s.code" class="chip" @click="onRecent(s.code)">
            <text class="chip__name">{{ s.name }}</text>
            <text class="chip__code">{{ s.code }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 本地身份卡（F6：登录后锁定为本账号） -->
    <view class="card">
      <view class="card__title">
        我的手机号
        <text v-if="phoneOk && !rtLogged" class="phone-ok">已绑定 · 询价/价目/意向单通用</text>
        <text v-else-if="phoneOk && rtLogged" class="phone-ok">已登录买家账号</text>
        <text v-else class="phone-hint">用于接收询价结果与查看我的意向单</text>
      </view>
      <view class="phone-row">
        <input
          v-model="phoneInput"
          class="code-input"
          type="number"
          :maxlength="PHONE_MAX_LEN"
          placeholder="输入手机号"
          placeholder-class="ph"
          :disabled="rtLogged"
        />
        <button class="btn-ghost" :disabled="rtLogged" @click="onSavePhone">{{ rtLogged ? '已锁定' : '保存' }}</button>
      </view>

      <!-- 买家账号登录态（F6 · US-RT-04 / D-RT-01） -->
      <view class="session" :class="{ 'session--rt': rtLogged }">
        <view v-if="rtLogged" class="session__info">
          <text class="session__tag">买家账号已登录</text>
          <text class="session__sub">{{ maskPhone(phoneInput) }} · 意向单确认后可查看批发商电话线下成交</text>
        </view>
        <view v-else class="session__info">
          <text class="session__tag">未登录买家账号</text>
          <text class="session__sub">意向单被商户确认后，登录即可直接联系批发商</text>
        </view>
        <button v-if="rtLogged" class="session__btn session__btn--out" @click="onLogout">退出</button>
        <button v-else class="session__btn" @click="goRtLogin">去登录</button>
      </view>
    </view>

    <view class="biz-entry" @click="goBiz">
      <text class="biz-entry__t">我是批发商 / 结算员 / 库管</text>
      <text class="biz-entry__arrow">›</text>
    </view>
    <view class="foot-note">
      <text>进店后即可浏览在库商品与公开价；提交意向单前需填写手机号，商户确认后可直接成交。</text>
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

/* ===== 品牌 hero ===== */
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

/* ===== 通用卡 ===== */
.card {
  margin-bottom: 24rpx;
  padding: 28rpx 24rpx;
  background: $cc-bg-1;
  border-radius: $cc-card-radius;
  box-shadow: 0 2rpx 12rpx rgba(2, 6, 23, 0.04);

  &__title {
    display: flex;
    align-items: baseline;
    margin-bottom: 20rpx;
    font-size: 30rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }
}

.code-row,
.phone-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.code-input {
  flex: 1;
  height: 88rpx;
  padding: 0 24rpx;
  border: 2rpx solid $cc-border-1;
  border-radius: 12rpx;
  background: #fff;
  font-size: 30rpx;
  color: $cc-fg-1;
  box-sizing: border-box;
}

.ph {
  color: $cc-fg-4;
}

.btn-primary {
  height: 88rpx;
  padding: 0 40rpx;
  margin: 0;
  line-height: 88rpx;
  border-radius: 12rpx;
  background: $cc-rt-accent;
  color: #fff;
  font-size: 30rpx;
  font-weight: 600;

  &::after {
    border: none;
  }
}

.btn-ghost {
  height: 88rpx;
  padding: 0 32rpx;
  margin: 0;
  line-height: 88rpx;
  border-radius: 12rpx;
  background: $cc-bg-3;
  color: $cc-fg-2;
  font-size: 28rpx;

  &::after {
    border: none;
  }
}

.scan-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  width: 100%;
  height: 84rpx;
  margin: 20rpx 0 0;
  line-height: 84rpx;
  border-radius: 12rpx;
  background: #ecfdf5;
  color: $cc-rt-accent;
  font-size: 28rpx;

  &::after {
    border: none;
  }

  &__icon {
    font-size: 36rpx;
    font-weight: 700;
  }
}

/* ===== 最近店铺 ===== */
.recent {
  margin-top: 24rpx;

  &__label {
    display: block;
    margin-bottom: 14rpx;
    font-size: 24rpx;
    color: $cc-fg-3;
  }

  &__list {
    display: flex;
    flex-wrap: wrap;
    gap: 16rpx;
  }
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  padding: 14rpx 20rpx;
  border: 2rpx solid $cc-border-1;
  border-radius: 999rpx;
  background: $cc-bg-1;

  &__name {
    font-size: 26rpx;
    color: $cc-fg-1;
    font-weight: 500;
  }

  &__code {
    font-size: 24rpx;
    color: $cc-fg-4;
  }
}

/* ===== 手机号卡 ===== */
.phone-ok {
  margin-left: auto;
  font-size: 22rpx;
  font-weight: 400;
  color: $cc-success;
}

.phone-hint {
  margin-left: auto;
  font-size: 22rpx;
  font-weight: 400;
  color: $cc-fg-4;
}

/* 登录后锁输入框 */
.code-input[disabled] {
  background: $cc-bg-3;
  color: $cc-fg-3;
}

/* ===== 买家登录态（F6） ===== */
.session {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 20rpx;
  padding: 20rpx;
  border-radius: 12rpx;
  background: $cc-bg-2;

  &--rt {
    background: $cc-success-bg;
  }

  &__info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4rpx;
  }

  &__tag {
    font-size: 24rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__sub {
    font-size: 22rpx;
    line-height: 1.5;
    color: $cc-fg-3;
  }

  &__btn {
    height: 64rpx;
    padding: 0 28rpx;
    margin: 0;
    line-height: 64rpx;
    border-radius: 999rpx;
    background: $cc-rt-accent;
    color: #fff;
    font-size: 24rpx;
    font-weight: 600;

    &::after {
      border: none;
    }

    &--out {
      background: $cc-bg-1;
      border: 2rpx solid $cc-border-1;
      color: $cc-fg-2;
      font-weight: 400;
    }
  }
}

/* ===== 商户/结算入口 ===== */
.biz-entry {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  margin: 4rpx 0 20rpx;
  padding: 24rpx;
  font-size: 26rpx;
  color: $cc-accent;
  background: $cc-info-bg;
  border-radius: 12rpx;

  &__arrow {
    font-size: 30rpx;
  }
}

.foot-note {
  padding: 0 12rpx;
  font-size: 24rpx;
  line-height: 1.7;
  color: $cc-fg-4;
}
</style>
