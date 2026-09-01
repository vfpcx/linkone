<script setup lang="ts">
/**
 * 登录公告弹窗（P5-A W4 · 18-p5-design §4.2/§6）
 *
 * 行为：
 *  - 登录/刷新后（已登录态）拉取「最新未读公告」：GET /notifications?group=ANNOUNCE&unreadOnly=true&size=1
 *  - 有公告 → 弹窗展示标题+正文；确认/关闭即调 POST /notifications/{id}/read 标记已读
 *  - markRead 幂等 → 下次登录自动不再弹（天然只弹一次）；无公告不弹
 *  - 公告在消息中心仍保留可见（历史已读记录），本弹窗仅负责「登录即达」的一次性展示
 *
 * 全站挂载于 App.vue；仅在非认证页（/login、/register、/forgot-password）不弹。
 * 中文规范：文案全中文，公告正文为平台下发内容原样展示。
 */

import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElDialog } from 'element-plus'
import { Notification, BellFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { notificationApi } from '@/api/notification'

const auth = useAuthStore()
const route = useRoute()

const visible = ref(false)
const announcement = ref<{ id: string; title: string; content: string; createdAt: string } | null>(null)
const loading = ref(false)
/** 会话内已检查标记（同一登录态只拉一次；登出后重置） */
let checkedForSession = false

const isAuthPage = () => ['/login', '/register', '/forgot-password'].includes(route.path)

const checkAndShow = async () => {
  if (checkedForSession) return
  if (!auth.isAuthenticated || isAuthPage()) return
  checkedForSession = true
  loading.value = true
  try {
    const data = await notificationApi.list({ group: 'ANNOUNCE', unreadOnly: true, size: 1 })
    const item = data?.records?.[0]
    if (item) {
      announcement.value = {
        id: item.id,
        title: item.title || '平台公告',
        content: item.content || '',
        createdAt: item.createdAt,
      }
      visible.value = true
    }
  } catch {
    // 网络/鉴权失败不阻断首页；全局 toast 已提示
  } finally {
    loading.value = false
  }
}

const onClose = () => {
  const id = announcement.value?.id
  visible.value = false
  announcement.value = null
  if (id) {
    // 确认/关闭即标已读（幂等；失败不阻塞关闭）
    notificationApi.markRead(id).catch(() => undefined)
  }
}

watch(
  () => auth.isAuthenticated,
  (authed, prev) => {
    if (authed && !prev) {
      // 新登录会话 → 重新检查
      checkedForSession = false
    }
    void checkAndShow()
  },
  { immediate: true },
)
</script>

<template>
  <el-dialog
    v-model="visible"
    :close-on-click-modal="false"
    :close-on-press-escape="true"
    :show-close="true"
    width="520px"
    class="announce-dialog"
    data-test="login-announcement-dialog"
    @close="onClose"
  >
    <template #header>
      <div class="announce-dialog__head">
        <el-icon class="announce-dialog__icon"><BellFilled /></el-icon>
        <span>平台公告</span>
      </div>
    </template>

    <div v-loading="loading" class="announce-dialog__body">
      <h3 class="announce-dialog__title">{{ announcement?.title }}</h3>
      <p class="announce-dialog__time">{{ announcement?.createdAt }}</p>
      <div class="announce-dialog__content">{{ announcement?.content }}</div>
    </div>

    <template #footer>
      <el-button type="primary" data-test="announcement-confirm" @click="onClose">
        <el-icon class="mr-1"><Notification /></el-icon>知道了
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.announce-dialog__head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.announce-dialog__icon {
  color: var(--color-brand-accent);
  font-size: 18px;
}
.announce-dialog__body {
  max-height: 50vh;
  overflow-y: auto;
}
.announce-dialog__title {
  margin: 0;
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.announce-dialog__time {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.announce-dialog__content {
  margin-top: var(--space-4);
  color: var(--color-fg-2);
  line-height: 1.8;
  white-space: pre-wrap;
}
</style>
