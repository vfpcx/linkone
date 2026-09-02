<script setup lang="ts">
/**
 * OPS 客诉仲裁（P3 FE-W2 · 12 §3.4/§6.1，复用 FE-W1 TA decide 模式）
 *
 * 契约（权威：OpsArbitrationController，据实查证）：
 *  - GET  /ops/arbitrations?bizType=&status=&page=&size=（MpPage<Arbitration>，跨租户；
 *      bizType 仅 OUTBOUND_COMPLAINT，角标 = status=PENDING 的 total）
 *  - POST /ops/arbitrations/{id}/decide
 *      结论四选 WK_LIABLE/WA_LIABLE/NEGOTIATED/NO_LIABILITY（conclusion 即判责，50333）；
 *      remark 必填（结论备注是线下赔偿唯一依据）；liability 必空（50342）；
 *      并发双裁被抢占 50334；副作用：出库单 客诉处理中→已出库（仅判责不动库存/账单，D43）。
 *
 * 文案（规则 8）：结论四选中文经 ui-shared roleLabel 生成（库管员责任/批发商管理员责任/
 * 双方协商/无责），用户可见文案零角色码。
 *
 * 视觉：沿用 ops/Blacklist.vue 顶栏 + 左侧菜单 shell + el-table/el-dialog 风格。
 */

import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Monitor, CircleClose, Stamp, Bell, Refresh, ScaleToOriginal } from '@element-plus/icons-vue'
import { AppTopbar, NavCountBadge, roleLabel } from '@cangchu/ui-shared'
import type {
  Arbitration,
  OutboundComplaintConclusion,
  OpsArbitrationDecideRequest,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import { opsArbitrationApi } from '@/api/arbitration'
import { accountApi } from '@/api/account'

const router = useRouter()
const auth = useAuthStore()

// ============ 顶栏 ============
const handleSwitchRole = () => auth.showSwitcher()

const handleProfileMenu = async (key: string) => {
  switch (key) {
    case 'profile':
      ElMessage.info('个人资料页留给后续 Agent 实现')
      break
    case 'security':
      ElMessage.info('安全设置页留给后续 Agent 实现')
      break
    case 'logout':
      try {
        await ElMessageBox.confirm('确认退出登录？', '退出确认', {
          confirmButtonText: '退出',
          cancelButtonText: '取消',
          type: 'warning',
        })
        await accountApi.logout().catch(() => undefined)
        auth.clear()
        router.replace('/login')
      } catch {
        /* cancel */
      }
      break
  }
}

// ============ 菜单（OPS 端） ============
const activeMenu = ref('/ops/arbitrations')

const menus = [
  { key: '/ops/dashboard', label: '运营控制台', icon: Monitor },
  { key: '/ops/tenant-audit', label: '租户审核', icon: Stamp },
  { key: '/ops/blacklist', label: '黑名单', icon: CircleClose },
  { key: '/ops/announcements', label: '公告管理', icon: Bell },
  { key: '/ops/arbitrations', label: '客诉仲裁', icon: ScaleToOriginal },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ops/arbitrations') {
    activeMenu.value = key
    return
  }
  if (
    key === '/ops/dashboard' ||
    key === '/ops/tenant-audit' ||
    key === '/ops/blacklist' ||
    key === '/ops/announcements'
  ) {
    router.push(key)
    return
  }
  ElMessage.info('该页面留给后续 Agent 实现')
}

// ============ 映射（规则 8：角色中文经 roleLabel，零角色码） ============
const CONCLUSION_LABEL: Record<OutboundComplaintConclusion, string> = {
  WK_LIABLE: `${roleLabel('WK')}责任`,
  WA_LIABLE: `${roleLabel('WA')}责任`,
  NEGOTIATED: '双方协商',
  NO_LIABILITY: '无责',
}
const CONCLUSION_OPTIONS = (
  ['WK_LIABLE', 'WA_LIABLE', 'NEGOTIATED', 'NO_LIABILITY'] as OutboundComplaintConclusion[]
).map((v) => ({ value: v, label: CONCLUSION_LABEL[v] }))
const conclusionLabel = (v: string | null) =>
  v ? (CONCLUSION_LABEL[v as OutboundComplaintConclusion] ?? v) : '—'

const conclusionTagType = (v: string | null): 'danger' | 'warning' | 'info' | 'success' => {
  switch (v) {
    case 'WK_LIABLE':
      return 'danger'
    case 'WA_LIABLE':
      return 'warning'
    case 'NEGOTIATED':
      return 'info'
    default:
      return 'success'
  }
}

const formatTime = (v: string | null): string =>
  v ? String(v).replace('T', ' ').slice(0, 19) : '—'

// ============ 列表 ============
const STATUS_PENDING = 'PENDING'
const STATUS_DECIDED = 'DECIDED'
const activeTab = ref<string>(STATUS_PENDING)

const loading = ref(false)
const rows = ref<Arbitration[]>([])
const page = ref(1)
const size = 20
const total = ref(0)
/** 客诉仲裁角标 = PENDING total（契约） */
const pendingCount = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const data = await opsArbitrationApi.list({
      bizType: 'OUTBOUND_COMPLAINT',
      status: activeTab.value,
      page: page.value,
      size,
    })
    rows.value = data.records ?? []
    total.value = Number(data.total) || 0
    if (activeTab.value === STATUS_PENDING) {
      pendingCount.value = total.value
    }
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

/** 角标独立拉取（当前 tab 非 PENDING 时保持角标准确） */
const fetchPendingCount = async () => {
  try {
    const data = await opsArbitrationApi.list({
      bizType: 'OUTBOUND_COMPLAINT',
      status: STATUS_PENDING,
      page: 1,
      size: 1,
    })
    pendingCount.value = Number(data.total) || 0
  } catch {
    /* 静默 */
  }
}

const onTabChange = () => {
  page.value = 1
  void fetchList()
}

const onPageChange = (p: number) => {
  page.value = p
  void fetchList()
}

// ============ 裁决弹窗（四选中文 + 备注必填） ============
const decideVisible = ref(false)
const decideTarget = ref<Arbitration | null>(null)
const decideSubmitting = ref(false)

const conclusion = ref<OutboundComplaintConclusion | ''>('')
const remark = ref('')

const openDecide = (row: Arbitration) => {
  decideTarget.value = row
  conclusion.value = ''
  remark.value = ''
  decideVisible.value = true
}

const decideHint = computed(() => {
  if (!conclusion.value) return '请选择结论查看说明；裁决后不可撤销、不可重开。'
  return `裁决后：出库单回到「已出库」；结论「${CONCLUSION_LABEL[conclusion.value]}」仅作判责与线下赔偿依据，不改库存与账单（平台不接资金），双方将收到站内信。`
})

const onDecideSubmit = async () => {
  const row = decideTarget.value
  if (!row) return
  if (!conclusion.value) {
    ElMessage.warning('请选择结论')
    return
  }
  // 结论备注必填（09 PRD §1.1：结论是线下赔偿唯一依据，必须留痕）
  if (!remark.value.trim()) {
    ElMessage.warning('请填写结论备注')
    return
  }
  const payload: OpsArbitrationDecideRequest = {
    conclusion: conclusion.value,
    remark: remark.value.trim(),
  }
  decideSubmitting.value = true
  try {
    const updated = await opsArbitrationApi.decide(String(row.id), payload)
    decideVisible.value = false
    ElMessage.success(
      `客诉单 ${updated.docNo} 已裁决：${conclusionLabel(updated.conclusion)}`,
    )
    await Promise.all([fetchList(), fetchPendingCount()])
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_ARBITRATION_NOT_PENDING) {
      // 并发双裁被抢占：关闭弹窗刷新回显
      decideVisible.value = false
      await Promise.all([fetchList(), fetchPendingCount()])
    }
  } finally {
    decideSubmitting.value = false
  }
}

// ============ 详情弹窗（已裁决只读） ============
const detailVisible = ref(false)
const detailTarget = ref<Arbitration | null>(null)

const openDetail = (row: Arbitration) => {
  detailTarget.value = row
  detailVisible.value = true
}

onMounted(() => {
  void fetchList()
  void fetchPendingCount()
})
</script>

<template>
  <div class="ops-shell">
    <!-- 顶栏 -->
    <AppTopbar
      store-name="平台运营"
      avatar-text="O"
      @switch-role="handleSwitchRole"
      @profile-command="handleProfileMenu"
    />

    <div class="ops-body">
      <!-- 左侧菜单 -->
      <aside class="ops-side">
        <el-menu :default-active="activeMenu" class="ops-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
            <NavCountBadge
              v-if="m.key === '/ops/arbitrations'"
              :count="pendingCount"
              class="menu-badge"
            />
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ops-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">客诉仲裁</h2>
            <p class="page-head__sub">
              商户对仓库代建出库的客诉在此裁决：结论仅判责与线下赔偿依据，不改库存与账单
            </p>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        </header>

        <section class="card">
          <el-tabs v-model="activeTab" data-test="ops-arb-tabs" @tab-change="onTabChange">
            <el-tab-pane :name="STATUS_PENDING">
              <template #label>
                <span class="tab-label">
                  待仲裁
                  <NavCountBadge :count="pendingCount" />
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane label="已裁决" :name="STATUS_DECIDED" />
          </el-tabs>

          <el-table
            v-loading="loading"
            :data="rows"
            row-key="id"
            class="arb-table"
            data-test="ops-arb-table"
            :empty-text="activeTab === STATUS_PENDING ? '暂无待仲裁客诉' : '暂无已裁决记录'"
          >
            <el-table-column prop="docNo" label="客诉单号" min-width="170">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="关联出库单" min-width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.refDocNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="涉事商户" min-width="120">
              <template #default="{ row }">{{ row.wholesalerName || '—' }}</template>
            </el-table-column>
            <el-table-column label="客诉理由" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-muted">{{ row.reason }}</span>
              </template>
            </el-table-column>
            <el-table-column label="附件" width="80" align="center">
              <template #default="{ row }">
                <span class="cell-muted">
                  {{ row.attachments?.length ? `📷 ${row.attachments.length}` : '—' }}
                </span>
              </template>
            </el-table-column>
            <!-- L-7：170 宽秒位被裁 → 放宽 190；根因是 fixed 悬浮操作列在 1280 下盖住本列，
                 沿 ta/Batches 先例去掉 fixed（自然滚动），并微缩商户/理由 min-width 使默认视口不滚动 -->
            <el-table-column label="发起时间" width="190">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="activeTab === STATUS_DECIDED" label="结论" width="160">
              <template #default="{ row }">
                <el-tag :type="conclusionTagType(row.conclusion)" effect="light" round>
                  {{ conclusionLabel(row.conclusion) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'PENDING'"
                  type="primary"
                  size="small"
                  data-test="ops-decide-btn"
                  @click="openDecide(row as Arbitration)"
                >
                  仲裁
                </el-button>
                <el-button
                  v-else
                  size="small"
                  data-test="ops-detail-btn"
                  @click="openDetail(row as Arbitration)"
                >
                  详情
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-if="total > size"
            class="pager"
            layout="total, prev, pager, next"
            :total="total"
            :page-size="size"
            :current-page="page"
            @current-change="onPageChange"
          />
        </section>
      </main>
    </div>

    <!-- 裁决弹窗（四选中文 + 备注必填） -->
    <el-dialog
      v-model="decideVisible"
      :title="`⚖️ 仲裁 · 出库客诉 ${decideTarget?.docNo ?? ''}`"
      width="600px"
      :close-on-click-modal="false"
      data-test="ops-decide-dialog"
    >
      <template v-if="decideTarget">
        <el-descriptions :column="2" size="small" border class="decide-info">
          <el-descriptions-item label="关联出库单" :span="2">
            {{ decideTarget.refDocNo }}
          </el-descriptions-item>
          <el-descriptions-item label="涉事商户">
            {{ decideTarget.wholesalerName || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="发起时间">
            {{ formatTime(decideTarget.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="客诉理由" :span="2">
            {{ decideTarget.reason }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="decideTarget.attachments?.length" class="decide-attachments">
          <span class="decide-attachments__label">附件：</span>
          <el-image
            v-for="(url, i) in decideTarget.attachments"
            :key="url"
            :src="url"
            :preview-src-list="decideTarget.attachments"
            :initial-index="i"
            fit="cover"
            class="decide-attachments__img"
            preview-teleported
          />
        </div>

        <el-form label-position="top" class="decide-form" @submit.prevent>
          <el-form-item label="结论（必选）" required>
            <el-radio-group v-model="conclusion" data-test="ops-conclusion-radio">
              <el-radio v-for="opt in CONCLUSION_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="结论备注（必填，线下赔偿唯一依据）" required>
            <el-input
              v-model="remark"
              type="textarea"
              :rows="3"
              maxlength="512"
              show-word-limit
              placeholder="裁决理由与线下处理约定，双方站内信将附带此备注"
              data-test="ops-decide-remark"
            />
          </el-form-item>
        </el-form>

        <el-alert type="info" :closable="false" class="decide-hint">
          {{ decideHint }}
        </el-alert>
      </template>

      <template #footer>
        <el-button :disabled="decideSubmitting" @click="decideVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="decideSubmitting"
          data-test="ops-decide-submit"
          @click="onDecideSubmit"
        >
          提交裁决
        </el-button>
      </template>
    </el-dialog>

    <!-- 已裁决详情（只读） -->
    <el-dialog
      v-model="detailVisible"
      :title="`客诉详情 ${detailTarget?.docNo ?? ''}`"
      width="560px"
      data-test="ops-arb-detail-dialog"
    >
      <el-descriptions v-if="detailTarget" :column="1" border>
        <el-descriptions-item label="关联出库单">
          {{ detailTarget.refDocNo }}
        </el-descriptions-item>
        <el-descriptions-item label="涉事商户">
          {{ detailTarget.wholesalerName || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="客诉理由">{{ detailTarget.reason }}</el-descriptions-item>
        <el-descriptions-item label="结论">
          <span data-test="ops-detail-conclusion">
            {{ conclusionLabel(detailTarget.conclusion) }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="结论备注">
          {{ detailTarget.conclusionRemark || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="裁决时间">
          {{ formatTime(detailTarget.decidedAt) }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ops-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}

.ops-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}

/* ===== 左侧菜单 ===== */
.ops-side {
  width: 220px;
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.ops-side__menu {
  border-right: none;
}
.ops-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.ops-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}
.menu-badge {
  margin-left: var(--space-2);
}

/* ===== 主区 ===== */
.ops-main {
  flex: 1;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  min-width: 0;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
.page-head__title {
  font-size: var(--font-size-h1);
  font-weight: var(--font-weight-bold);
  color: var(--color-fg-1);
  margin: 0;
}
.page-head__sub {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}

.arb-table {
  width: 100%;
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.pager {
  margin-top: var(--space-4);
  justify-content: flex-end;
}

/* ===== 裁决弹窗 ===== */
.decide-info {
  margin-bottom: var(--space-3);
}
.decide-attachments {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
.decide-attachments__label {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.decide-attachments__img {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-1);
  cursor: pointer;
}
.decide-form :deep(.el-radio) {
  margin-right: var(--space-4);
}
.decide-hint {
  margin-top: var(--space-2);
}
.decide-hint :deep(.el-alert__description) {
  margin: 0;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ops-side {
    display: none;
  }
  .ops-main {
    padding: var(--space-4);
    min-width: 0;
  }
}
</style>
