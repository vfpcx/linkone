<script setup lang="ts">
/**
 * 站内信铃铛 · NotificationBell（P3 FE-W1）
 *
 * 放入 AppTopbar 的 #bell 插槽（根元素带 class="cc-topbar__bell" 继承窄屏收起规则）：
 *   <AppTopbar ...><template #bell><NotificationBell /></template></AppTopbar>
 *
 * 行为：
 *  - 未读角标：GET /notifications/unread-count，挂载即拉 + 60s 轮询（后端契约「铃铛角标轮询」）；
 *  - 点击铃铛开抽屉：GET /notifications 分页列表，「只看未读」开关；
 *  - 点条目 → POST /{id}/read（幂等）→ 行内转已读、角标减一；
 *  - 角标用 el-badge 上标形态（MASTER §4.11：包裹图标时用 el-badge，裸计数才用 NavCountBadge）。
 */

import { ref, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import type { NotificationItem } from '@cangchu/api-types'
import { notificationApi } from '@/api/notification'

const unread = ref(0)
const drawerVisible = ref(false)
const loading = ref(false)
const unreadOnly = ref(false)
const items = ref<NotificationItem[]>([])
const page = ref(1)
const size = 20
const total = ref(0)

let timer: ReturnType<typeof setInterval> | null = null

const fetchUnread = async () => {
  try {
    const { count } = await notificationApi.unreadCount()
    unread.value = Number(count) || 0
  } catch {
    // 静默：角标轮询失败不打扰用户
  }
}

const fetchList = async () => {
  loading.value = true
  try {
    const data = await notificationApi.list({
      page: page.value,
      size,
      unreadOnly: unreadOnly.value,
    })
    items.value = data.records ?? []
    total.value = Number(data.total) || 0
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

const openDrawer = () => {
  drawerVisible.value = true
  page.value = 1
  void fetchList()
  void fetchUnread()
}

const onToggleUnreadOnly = () => {
  page.value = 1
  void fetchList()
}

const onPageChange = (p: number) => {
  page.value = p
  void fetchList()
}

/** 点条目标记已读（幂等；已读条目不再请求） */
const onItemClick = async (item: NotificationItem) => {
  if (item.readAt) return
  try {
    await notificationApi.markRead(String(item.id))
    item.readAt = new Date().toISOString()
    unread.value = Math.max(0, unread.value - 1)
  } catch {
    ElMessage.warning('标记已读失败，请稍后重试')
  }
}

const formatTime = (v: string | null): string =>
  v ? String(v).replace('T', ' ').slice(0, 19) : '—'

onMounted(() => {
  void fetchUnread()
  timer = setInterval(fetchUnread, 60_000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <span class="cc-topbar__bell cc-bell" data-test="notification-bell">
    <el-badge
      :value="unread"
      :hidden="unread <= 0"
      :max="99"
      class="cc-bell__badge"
      data-test="notification-badge"
    >
      <el-button text :icon="Bell" title="站内信" @click="openDrawer" />
    </el-badge>

    <el-drawer
      v-model="drawerVisible"
      title="站内信"
      size="420px"
      append-to-body
      class="cc-bell-drawer"
    >
      <div class="cc-bell-drawer__toolbar">
        <el-switch
          v-model="unreadOnly"
          active-text="只看未读"
          data-test="notification-unread-only"
          @change="onToggleUnreadOnly"
        />
        <span class="cc-bell-drawer__count">未读 {{ unread }} 条</span>
      </div>

      <div v-loading="loading" class="cc-bell-drawer__list" data-test="notification-list">
        <el-empty v-if="!loading && items.length === 0" description="暂无消息" :image-size="72" />
        <div
          v-for="item in items"
          :key="String(item.id)"
          class="cc-bell-item"
          :class="{ 'is-read': !!item.readAt }"
          data-test="notification-item"
          @click="onItemClick(item)"
        >
          <div class="cc-bell-item__head">
            <span class="cc-bell-item__dot" aria-hidden="true" />
            <span class="cc-bell-item__title">{{ item.title }}</span>
            <span class="cc-bell-item__time">{{ formatTime(item.createdAt) }}</span>
          </div>
          <div class="cc-bell-item__content">{{ item.content }}</div>
        </div>
      </div>

      <el-pagination
        v-if="total > size"
        class="cc-bell-drawer__pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        small
        @current-change="onPageChange"
      />
    </el-drawer>
  </span>
</template>

<style scoped>
.cc-bell {
  display: inline-flex;
  align-items: center;
}
.cc-bell__badge :deep(.el-badge__content) {
  /* 顶栏深色底上保持描边可读 */
  border: none;
}

.cc-bell-drawer__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-border-1);
  margin-bottom: var(--space-3);
}
.cc-bell-drawer__count {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

.cc-bell-drawer__list {
  min-height: 120px;
}

.cc-bell-item {
  padding: var(--space-3);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color 0.15s;
}
.cc-bell-item:hover {
  background: var(--color-bg-2);
}
.cc-bell-item + .cc-bell-item {
  margin-top: var(--space-1);
}
.cc-bell-item__head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}
.cc-bell-item__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-danger);
  flex-shrink: 0;
}
.cc-bell-item.is-read .cc-bell-item__dot {
  background: transparent;
}
.cc-bell-item__title {
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cc-bell-item.is-read .cc-bell-item__title {
  color: var(--color-fg-3);
  font-weight: var(--font-weight-regular);
}
.cc-bell-item__time {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  flex-shrink: 0;
}
.cc-bell-item__content {
  margin-top: var(--space-1);
  padding-left: calc(8px + var(--space-2));
  color: var(--color-fg-2);
  font-size: var(--font-size-caption);
  line-height: 1.5;
  word-break: break-all;
}
.cc-bell-item.is-read .cc-bell-item__content {
  color: var(--color-fg-3);
}

.cc-bell-drawer__pager {
  margin-top: var(--space-3);
  justify-content: center;
}
</style>
