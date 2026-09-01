<script lang="ts">
/**
 * NotificationList 通用站内信列表组件（P5-A W4 · ui-shared 复用）
 *
 * 功能（18-p5-design §7 / §4.1）：
 *  - 分组 Tab：全部 / 业务 / 公告（group 参数由调用方透传给后端筛选）
 *  - 未读筛选（unreadOnly 开关）
 *  - 单条已读 / 全部已读（幂等，调用方传入 API 函数）
 *  - 点击跳转：公告类展开正文；业务类 emit('navigate') 由调用方决定跳转
 *  - 分页（MpPage.records/total/current 契约）
 *
 * 用法（admin 消息中心 / 司机端复用）：
 *   <NotificationList
 *     :fetch-list="load"
 *     :mark-read="markOne"
 *     :mark-read-all="markAll"
 *     @navigate="onNavigate" />
 *
 * 中文规范：Tab/按钮/空态文案全中文；公告/角色码一律经 announcement.ts 映射，禁止硬编码英文码。
 */

export interface NotificationListItem {
  id: string
  type: string
  title: string
  content: string
  refType: string | null
  refId: string | null
  readAt: string | null
  createdAt: string
}

/** 列表分页契约（对齐后端 MyBatis-Plus Page：records/total/current） */
export interface NotificationListPage {
  records: NotificationListItem[]
  total: number | string
  current: number | string
}

/** 查询参数（group 透传后端：ALL/BIZ/ANNOUNCE，缺省后端按全部） */
export interface NotificationListQuery {
  page: number
  size: number
  unreadOnly: boolean
  group: 'ALL' | 'BIZ' | 'ANNOUNCE'
}
</script>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { Check, Promotion } from '@element-plus/icons-vue'

interface Props {
  /** 列表拉取函数（调用方注入 notificationApi.list 或司机端等价实现） */
  fetchList: (q: NotificationListQuery) => Promise<NotificationListPage>
  /** 单条已读（幂等） */
  markRead?: (id: string) => Promise<void>
  /** 全部已读（幂等） */
  markReadAll?: () => Promise<void>
  /** 是否显示「只看未读」开关（默认 true） */
  showUnreadFilter?: boolean
  /** 是否显示「全部已读」按钮（默认 true） */
  showReadAll?: boolean
  /** 空态文案（默认按 Tab 区分） */
  emptyText?: string
}

const props = withDefaults(defineProps<Props>(), {
  markRead: undefined,
  markReadAll: undefined,
  showUnreadFilter: true,
  showReadAll: true,
  emptyText: '',
})

const emit = defineEmits<{
  /** 业务类消息点击（调用方决定跳转；公告类不触发，组件内展开正文） */
  (e: 'navigate', item: NotificationListItem): void
}>()

// ============ 状态 ============
const loading = ref(false)
const list = ref<NotificationListItem[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const group = ref<'ALL' | 'BIZ' | 'ANNOUNCE'>('ALL')
const unreadOnly = ref(false)
/** 展开中的公告 id（点击公告切换正文展示） */
const expandedId = ref('')
const readingId = ref('')
const readingAll = ref(false)

const isAnnouncement = (item: NotificationListItem) =>
  item.type === 'PLATFORM_ANNOUNCEMENT' || item.refType === 'ANNOUNCEMENT'

const fetchList = async () => {
  loading.value = true
  try {
    const data = await props.fetchList({
      page: page.value,
      size: size.value,
      unreadOnly: unreadOnly.value,
      group: group.value,
    })
    list.value = data?.records ?? []
    total.value = Number(data?.total ?? 0)
    // 后端钳制回显页码
    const cur = Number(data?.current ?? page.value)
    if (cur >= 1) page.value = cur
  } catch {
    // 全局 toast 已提示；保留已有数据以便重试
  } finally {
    loading.value = false
  }
}

const hasMore = computed(() => total.value > size.value)
const canReadAll = computed(() => list.value.some((n) => !n.readAt))

const currentEmptyText = computed(() => {
  if (props.emptyText) return props.emptyText
  if (unreadOnly.value) return '暂无未读消息'
  return '暂无消息'
})

// ============ 交互 ============
const onGroupChange = (g: 'ALL' | 'BIZ' | 'ANNOUNCE') => {
  group.value = g
  page.value = 1
  expandedId.value = ''
  fetchList()
}

const onUnreadFilterChange = () => {
  page.value = 1
  expandedId.value = ''
  fetchList()
}

const onItemClick = (item: NotificationListItem) => {
  if (isAnnouncement(item)) {
    expandedId.value = expandedId.value === item.id ? '' : item.id
  } else {
    emit('navigate', item)
  }
}

const onMarkRead = async (item: NotificationListItem) => {
  if (!props.markRead) return
  readingId.value = item.id
  try {
    await props.markRead(item.id)
    item.readAt = new Date().toISOString()
  } catch {
    // 全局 toast 已提示
  } finally {
    readingId.value = ''
  }
}

const onMarkReadAll = async () => {
  if (!props.markReadAll) return
  readingAll.value = true
  try {
    await props.markReadAll()
    list.value.forEach((n) => {
      n.readAt = n.readAt ?? new Date().toISOString()
    })
  } catch {
    // 全局 toast 已提示
  } finally {
    readingAll.value = false
  }
}

const onPageChange = (p: number) => {
  page.value = p
  expandedId.value = ''
  fetchList()
}

const onSizeChange = (s: number) => {
  size.value = s
  page.value = 1
  expandedId.value = ''
  fetchList()
}

// ============ 格式化 ============
const formatTime = (iso?: string | null): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).replace('T', ' ').slice(0, 16)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

watch(
  () => [props.fetchList, props.markRead, props.markReadAll] as const,
  () => {
    fetchList()
  },
)

onMounted(fetchList)
</script>

<template>
  <div class="cc-notif">
    <!-- 工具栏：分组 Tab + 未读开关 + 全部已读 -->
    <div class="cc-notif__toolbar">
      <el-checkbox
        v-if="props.showUnreadFilter"
        v-model="unreadOnly"
        class="cc-notif__unread"
        @change="onUnreadFilterChange"
      >
        只看未读
      </el-checkbox>
      <el-button
        v-if="props.showReadAll && props.markReadAll"
        class="cc-notif__readall"
        :icon="Check"
        :disabled="!canReadAll"
        :loading="readingAll"
        text
        type="primary"
        @click="onMarkReadAll"
      >
        全部已读
      </el-button>
    </div>

    <!-- 分组 Tab -->
    <div class="cc-notif__tabs">
      <button
        v-for="g in ([{ key: 'ALL', label: '全部' }, { key: 'BIZ', label: '业务' }, { key: 'ANNOUNCE', label: '公告' }] as const)"
        :key="g.key"
        class="cc-notif__tab"
        :class="{ 'is-active': group === g.key }"
        type="button"
        @click="onGroupChange(g.key)"
      >
        {{ g.label }}
      </button>
    </div>

    <!-- 列表 -->
    <div v-loading="loading" class="cc-notif__body">
      <ul v-if="list.length > 0" class="cc-notif__list">
        <li
          v-for="item in list"
          :key="item.id"
          class="cc-notif__item"
          :class="{ 'is-unread': !item.readAt, 'is-announcement': isAnnouncement(item) }"
          @click="onItemClick(item)"
        >
          <div class="cc-notif__row">
            <span class="cc-notif__dot" :class="{ 'is-hidden': item.readAt }" />
            <el-tag
              v-if="isAnnouncement(item)"
              class="cc-notif__group-tag"
              size="small"
              effect="plain"
              type="warning"
            >
              公告
            </el-tag>
            <span class="cc-notif__title">{{ item.title }}</span>
            <span class="cc-notif__time">{{ formatTime(item.createdAt) }}</span>
            <el-button
              v-if="!item.readAt && props.markRead"
              class="cc-notif__read-one"
              :icon="Check"
              :loading="readingId === item.id"
              size="small"
              text
              type="primary"
              @click.stop="onMarkRead(item)"
            >
              标为已读
            </el-button>
          </div>
          <p class="cc-notif__content" :class="{ 'is-expanded': expandedId === item.id }">
            {{ item.content }}
          </p>
          <p v-if="isAnnouncement(item) && expandedId === item.id" class="cc-notif__hint">
            <el-icon><Promotion /></el-icon>
            平台公告
          </p>
        </li>
      </ul>
      <el-empty v-else :description="currentEmptyText" :image-size="72" class="cc-notif__empty" />
    </div>

    <!-- 分页 -->
    <div v-if="hasMore" class="cc-notif__pagination">
      <el-pagination
        :current-page="page"
        :page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        small
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<style scoped>
.cc-notif {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-height: 320px;
}
.cc-notif__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}
.cc-notif__unread {
  flex-shrink: 0;
}
.cc-notif__readall {
  flex-shrink: 0;
}
.cc-notif__tabs {
  display: flex;
  gap: var(--space-1);
  border-bottom: 1px solid var(--color-border-1);
}
.cc-notif__tab {
  appearance: none;
  border: none;
  background: transparent;
  cursor: pointer;
  padding: var(--space-2) var(--space-3);
  font-size: var(--font-size-body);
  color: var(--color-fg-3);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color var(--duration-fast) var(--easing-standard);
}
.cc-notif__tab:hover {
  color: var(--color-fg-1);
}
.cc-notif__tab.is-active {
  color: var(--color-brand-accent);
  border-bottom-color: var(--color-brand-accent);
  font-weight: var(--font-weight-medium);
}
.cc-notif__body {
  flex: 1;
  min-height: 240px;
}
.cc-notif__list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.cc-notif__item {
  padding: var(--space-3) var(--space-2);
  border-radius: var(--radius-base);
  cursor: pointer;
  transition: background var(--duration-fast) var(--easing-standard);
}
.cc-notif__item:hover {
  background: var(--color-bg-2);
}
.cc-notif__item.is-unread {
  background: var(--color-info-bg);
}
.cc-notif__row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}
.cc-notif__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-brand-accent);
  flex-shrink: 0;
}
.cc-notif__dot.is-hidden {
  background: transparent;
}
.cc-notif__group-tag {
  flex-shrink: 0;
}
.cc-notif__title {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.cc-notif__item.is-unread .cc-notif__title {
  font-weight: var(--font-weight-semibold);
}
.cc-notif__time {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  flex-shrink: 0;
}
.cc-notif__read-one {
  flex-shrink: 0;
}
.cc-notif__content {
  margin: var(--space-2) 0 0 20px;
  color: var(--color-fg-2);
  font-size: var(--font-size-caption);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.cc-notif__content.is-expanded {
  display: block;
  white-space: pre-wrap;
}
.cc-notif__hint {
  margin: var(--space-2) 0 0 20px;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--color-brand-accent);
  font-size: var(--font-size-caption);
}
.cc-notif__empty {
  padding: var(--space-8) 0;
}
.cc-notif__pagination {
  display: flex;
  justify-content: flex-end;
}
</style>
