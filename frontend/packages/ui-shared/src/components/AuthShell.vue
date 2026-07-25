<script setup lang="ts">
/**
 * 认证页壳层 · AuthShell（真机 P0 根治 · 2026-07-25）
 *
 * 背景：Login/Register/ForgotPassword 三页此前各自复制「品牌 hero + 白卡片」
 * 壳层（Login 内联样式，另两页 @import auth-shared.scss），窄屏（390/375）下
 * hero 定高 30vh + 卡片负 margin(-40px) 上提，而 hero 为 position:relative
 * 定位元素、绘制层级高于普通流卡片 → hero 盖住卡片顶部（登录页「欢迎登录」
 * 被切半、注册/找回页「返回登录」被盖）。现参照 DEF-4 AppTopbar 先例收编为
 * 公共组件，窄屏 hero 收敛为紧凑横条 + 正常文档流（去负 margin），卡片完整可见。
 *
 * 用法：
 *  <AuthShell slogan="通用仓储 SaaS 平台 · 让仓储更智能">
 *    <template #bullets><ul>…卖点列表（可选，仅桌面显示）…</ul></template>
 *    …卡片内容（标题/表单等，样式留在页面 scoped）…
 *  </AuthShell>
 */

interface Props {
  /** 品牌名 */
  brand?: string
  /** hero 标语 */
  slogan?: string
  /** hero 底部版权文案（窄屏隐藏） */
  footerText?: string
}

withDefaults(defineProps<Props>(), {
  brand: '仓储云',
  slogan: '让仓储更智能',
  footerText: '© 2026 仓储云',
})
</script>

<template>
  <div class="cc-auth">
    <!-- 品牌 hero：桌面左半屏，≤768 收敛为顶部紧凑横条 -->
    <aside class="cc-auth__brand">
      <div class="cc-auth__content">
        <h1 class="cc-auth__logo">{{ brand }}</h1>
        <p class="cc-auth__slogan">{{ slogan }}</p>
        <div v-if="$slots.bullets" class="cc-auth__bullets">
          <slot name="bullets" />
        </div>
      </div>
      <footer class="cc-auth__footer">{{ footerText }}</footer>
    </aside>

    <!-- 表单卡片 -->
    <main class="cc-auth__form">
      <div class="cc-auth__card">
        <slot />
      </div>
    </main>
  </div>
</template>

<style scoped>
.cc-auth {
  display: flex;
  min-height: 100vh;
  background: var(--color-bg-2);
}

/* ===== 品牌 hero（桌面：左半屏压底） ===== */
.cc-auth__brand {
  flex: 1;
  background: var(--color-brand-primary);
  color: var(--color-brand-primary-on);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 64px 80px;
  position: relative;
  overflow: hidden;
}

.cc-auth__brand::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 0% 100%, rgba(3, 105, 161, 0.35), transparent 60%),
    radial-gradient(ellipse at 100% 0%, rgba(5, 150, 105, 0.18), transparent 50%);
  pointer-events: none;
}

.cc-auth__content {
  position: relative;
  z-index: 1;
}

.cc-auth__logo {
  font-size: 48px;
  font-weight: var(--font-weight-bold);
  margin: 0 0 var(--space-4);
  letter-spacing: -1px;
}

.cc-auth__slogan {
  font-size: 18px;
  opacity: 0.85;
  margin: 0;
}

.cc-auth__bullets {
  margin-top: var(--space-8, 32px);
}

.cc-auth__footer {
  position: relative;
  z-index: 1;
  font-size: var(--font-size-caption);
  opacity: 0.5;
}

/* ===== 表单卡片（桌面：右半屏居中） ===== */
.cc-auth__form {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-6);
}

.cc-auth__card {
  width: 480px;
  max-width: 100%;
  background: var(--color-bg-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  padding: 32px;
}

/* ===== ≤768：hero 紧凑横条 + 正常文档流（禁负 margin，卡片不被遮挡） ===== */
@media (max-width: 768px) {
  .cc-auth {
    flex-direction: column;
  }
  .cc-auth__brand {
    flex: 0 0 auto;
    padding: 14px 20px;
  }
  .cc-auth__content {
    display: flex;
    align-items: baseline;
    gap: var(--space-3, 12px);
    min-width: 0;
  }
  .cc-auth__logo {
    font-size: 22px;
    margin: 0;
    letter-spacing: 0;
  }
  .cc-auth__slogan {
    font-size: 13px;
    margin: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .cc-auth__bullets,
  .cc-auth__footer {
    display: none;
  }
  .cc-auth__form {
    align-items: flex-start;
    padding: var(--space-4, 16px);
  }
  .cc-auth__card {
    width: 100%;
    padding: var(--space-6, 24px);
  }
}
</style>
