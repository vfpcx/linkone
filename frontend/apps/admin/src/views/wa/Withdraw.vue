<script setup lang="ts">
/**
 * WA 退驻发起页（R13）— P2 入驻生态 Wave4b 前端第二批
 *
 * 来源：
 *  - 线框：shared/product/06b-onboarding-wireframes.md §3（前置自查三态 / 确认弹窗 /
 *          待审批·已驳回·已退驻状态页 / 60 天倒计时 + 申请恢复）
 *  - 契约：POST /wholesaler/withdraw、GET /wholesaler/withdraw/mine、
 *          GET .../precheck、POST .../cancel、POST .../restore
 *          （Wave2 后端已落地，权威来源 10-onboarding-design.md §12）
 *  - 错误码：50312 库存 / 50314 未结单 / 50316 重复申请（段 50310-50329 回填清单）/ 50202 已退驻
 *  - 视觉：沿用 Apply.vue 的 WA 顶栏 + 左侧菜单 shell + 卡片
 *
 * 自查三态：✅ 通过 / ❌ 未通过（任一 ❌ 提交置灰）/ ⊘ 账单灰态占位（billing.cleared 恒 null）。
 * 60 天恢复倒计时 = auditedAt（审批通过时刻）+ 60 天，前端自行计算（后端不下发截止时间）。
 * precheck 请求失败时降级："提交时由后端复核"，允许提交，错误码回填清单。
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Switch,
  Bell,
  Document,
  Shop,
  User,
  Warning,
  Refresh,
  CircleCheckFilled,
  CircleCloseFilled,
  RemoveFilled,
  QuestionFilled,
} from '@element-plus/icons-vue'
import { StatusBadge } from '@cangchu/ui-shared'
import type { WaWithdrawApplication, WaWithdrawPrecheck } from '@cangchu/api-types'
import { isWithdrawPreconditionFailed } from '@cangchu/error-codes'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { waWithdrawApi } from '@/api/wholesaler'
import { accountApi } from '@/api/account'

const router = useRouter()
const auth = useAuthStore()

// ============ 顶栏 ============
const storeNameDisplay = computed(() => auth.currentStoreName || '我的商户')

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

// ============ 菜单（WA 端） ============
const activeMenu = ref('/wa/withdraw')

const menus = [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
  { key: '/wa/apply', label: '入驻申请', icon: Shop },
  { key: '/wa/staff', label: '员工管理', icon: User },
  { key: '/wa/withdraw', label: '退驻申请', icon: Warning },
]

const handleMenuSelect = (key: string) => {
  if (key === '/wa/withdraw') {
    activeMenu.value = key
    return
  }
  router.push(key)
}

// ============ 退驻申请状态（mine） ============
const pageLoading = ref(false)
const application = ref<WaWithdrawApplication | null>(null)
/** 已驳回后点了「重新申请」→ 回发起态 */
const reapplying = ref(false)

type ViewState = 'form' | 'pending' | 'rejected' | 'withdrawn' | 'archived'

const view = computed<ViewState>(() => {
  const a = application.value
  // CANCELLED = 已撤回，等同无进行中申请 → 可重新发起
  if (!a || a.status === 'CANCELLED') return 'form'
  if (a.status === 'PENDING') return 'pending'
  if (a.status === 'REJECTED') return reapplying.value ? 'form' : 'rejected'
  // APPROVED = 已退驻（auditedAt 起 60 天恢复期）；倒计时归零 = 已归档
  if (a.status === 'APPROVED') return restoreDaysLeft.value > 0 ? 'withdrawn' : 'archived'
  return 'form'
})

const fetchMine = async () => {
  try {
    application.value = (await waWithdrawApi.mine()) ?? null
  } catch {
    // 端点未落地 / 网络失败 → 按无申请处理，提交时后端兜底
    application.value = null
  }
}

// ============ 前置自查（三态清单） ============
const precheck = ref<WaWithdrawPrecheck | null>(null)
const precheckLoading = ref(false)
/** precheck 端点未就绪（契约微调位）→ 降级为提交时后端复核 */
const precheckUnavailable = ref(false)

const fetchPrecheck = async () => {
  precheckLoading.value = true
  try {
    precheck.value = await waWithdrawApi.precheck()
    precheckUnavailable.value = false
  } catch {
    precheck.value = null
    precheckUnavailable.value = true
  } finally {
    precheckLoading.value = false
  }
}

const openDocsCount = computed(() => precheck.value?.openDocs.count ?? 0)

const stockPass = computed(() => !!precheck.value && precheck.value.stockCleared)
const docsPass = computed(() => !!precheck.value && precheck.value.openDocs.cleared)

/** 任一 ❌ 未通过则置灰；precheck 不可用时放行（后端复核） */
const canSubmit = computed(() => {
  if (precheckLoading.value) return false
  if (precheckUnavailable.value) return true
  return stockPass.value && docsPass.value
})

// ============ 发起退驻 ============
const form = reactive({ reason: '' })
const confirmVisible = ref(false)
const submitting = ref(false)

const openConfirm = () => {
  if (!canSubmit.value) return
  confirmVisible.value = true
}

const onSubmit = async () => {
  submitting.value = true
  try {
    await waWithdrawApi.submit({ reason: form.reason.trim() || undefined })
    confirmVisible.value = false
    reapplying.value = false
    ElMessage.success('退驻申请已提交，等待仓库管理员审批')
    await fetchMine()
  } catch (e) {
    if (e instanceof ApiError) {
      if (isWithdrawPreconditionFailed(e.code)) {
        // 前置不满足 → 关弹窗刷新清单回显未通过项
        confirmVisible.value = false
        await fetchPrecheck()
      } else if (e.code === 50202) {
        // 已退驻 → 拉取最新状态落到已退驻页
        confirmVisible.value = false
        await fetchMine()
      }
    }
  } finally {
    submitting.value = false
  }
}

// ============ 撤回（待审批时，契约微调位） ============
const cancelling = ref(false)

const onCancel = async () => {
  try {
    await ElMessageBox.confirm('撤回后可重新发起退驻申请。确认撤回？', '撤回退驻申请', {
      confirmButtonText: '确认撤回',
      cancelButtonText: '再想想',
      type: 'warning',
    })
  } catch {
    return
  }
  cancelling.value = true
  try {
    await waWithdrawApi.cancel()
    ElMessage.success('退驻申请已撤回')
    application.value = null
    await fetchPrecheck()
  } catch {
    // 全局 toast 已提示（端点未就绪时亦在此兜底）
  } finally {
    cancelling.value = false
  }
}

// ============ 重新申请（已驳回后） ============
const startReapply = async () => {
  reapplying.value = true
  form.reason = application.value?.reason ?? ''
  await fetchPrecheck()
}

// ============ 已退驻 · 60 天倒计时 + 恢复 ============
// 后端不下发截止时间：窗口起点 = auditedAt（审批通过时刻），前端自行 +60 天
const DAY_MS = 24 * 60 * 60 * 1000

const restoreDeadlineDate = computed<Date | null>(() => {
  const a = application.value
  if (!a?.auditedAt) return null
  const d = new Date(a.auditedAt)
  if (Number.isNaN(d.getTime())) return null
  return new Date(d.getTime() + 60 * DAY_MS)
})

const restoreDaysLeft = computed(() => {
  const dl = restoreDeadlineDate.value
  // 后端未下发生效/截止时间（如契约微调期）→ 视为恢复期内，交后端兜底
  if (!dl) return 60
  return Math.ceil((dl.getTime() - Date.now()) / DAY_MS)
})

const restoring = ref(false)

const onRestore = async () => {
  try {
    await ElMessageBox.confirm(
      '恢复后 SKU 与客户专属价需重新设置，历史单据与流水保留。确认申请恢复入驻？',
      '申请恢复入驻',
      {
        confirmButtonText: '确认恢复',
        cancelButtonText: '再想想',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  restoring.value = true
  try {
    await waWithdrawApi.restore()
    ElMessage.success('已恢复入驻，欢迎回来')
    // 恢复后主体已 ACTIVE，但 mine 仍返回 APPROVED 申请单（申请单无 RESTORED 态）
    // → 直接清空回发起态，避免落回"已退驻"页
    application.value = null
    reapplying.value = false
    await fetchPrecheck()
  } catch {
    // 全局 toast 已提示（超窗/已归档 50317）
  } finally {
    restoring.value = false
  }
}

// ============ 格式化 ============
const formatTime = (iso?: string): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).replace('T', ' ').slice(0, 16)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const formatDate = (iso?: string): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 10)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

// VO 不含 tenantName，仓库名取登录态
const tenantNameDisplay = computed(() => auth.currentStoreName || '所在仓库')

onMounted(async () => {
  pageLoading.value = true
  await fetchMine()
  if (view.value === 'form') await fetchPrecheck()
  pageLoading.value = false
})
</script>

<template>
  <div class="wa-shell">
    <!-- 顶栏 -->
    <header class="wa-topbar">
      <div class="wa-topbar__left">
        <span class="wa-topbar__brand">仓储云</span>
        <span class="wa-topbar__divider">·</span>
        <span class="wa-topbar__store">{{ storeNameDisplay }}</span>
      </div>

      <div class="wa-topbar__right">
        <el-button text @click="handleSwitchRole">
          <el-icon><Switch /></el-icon>
          切换角色
        </el-button>
        <el-button text :icon="Bell" class="wa-topbar__bell" />
        <el-dropdown trigger="click" @command="handleProfileMenu">
          <span class="wa-topbar__user">
            <el-avatar :size="28">U</el-avatar>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人资料</el-dropdown-item>
              <el-dropdown-item command="security">安全设置</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="wa-body">
      <!-- 左侧菜单 -->
      <aside class="wa-side">
        <el-menu :default-active="activeMenu" class="wa-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="wa-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">退驻申请</h2>
            <p class="page-head__sub">
              退出当前入驻仓库：需满足前置条件并由仓库管理员审批，60 天内可申请恢复
            </p>
          </div>
        </header>

        <div v-loading="pageLoading" class="withdraw-content">
          <!-- ============ 发起态（自查清单 + 后果 + 提交） ============ -->
          <template v-if="view === 'form'">
            <!-- 已驳回后重新申请：驳回理由黄条常驻 -->
            <el-alert
              v-if="reapplying && application?.auditRemark"
              type="warning"
              :closable="false"
              show-icon
              title="上次申请已被驳回"
              :description="`驳回理由：${application.auditRemark}`"
              class="reapply-alert"
            />

            <section class="card checklist-card">
              <div class="checklist-card__head">
                <h3 class="card-title">⚠️ 退驻前请确认 · 前置条件自查</h3>
                <el-button
                  :icon="Refresh"
                  :loading="precheckLoading"
                  size="small"
                  @click="fetchPrecheck"
                >
                  重新检查
                </el-button>
              </div>

              <!-- precheck 降级提示 -->
              <el-alert
                v-if="precheckUnavailable"
                type="info"
                :closable="false"
                show-icon
                title="自查服务暂不可用"
                description="暂时无法实时校验前置条件，提交后将由系统复核；不满足时会在此回显未通过项。"
              />

              <ul v-else class="checklist" v-loading="precheckLoading">
                <!-- 库存清零 -->
                <li class="checklist__item">
                  <el-icon v-if="stockPass" class="checklist__icon is-pass">
                    <CircleCheckFilled />
                  </el-icon>
                  <el-icon v-else-if="precheck" class="checklist__icon is-fail">
                    <CircleCloseFilled />
                  </el-icon>
                  <el-icon v-else class="checklist__icon is-skip"><QuestionFilled /></el-icon>
                  <div class="checklist__body">
                    <span class="checklist__label">库存已清零</span>
                    <span class="checklist__desc">
                      {{ !precheck ? '—' : stockPass ? '在库库存已清零' : '仍有在库库存，请先清空' }}
                    </span>
                  </div>
                </li>

                <!-- 无未结单据 -->
                <li class="checklist__item">
                  <el-icon v-if="docsPass" class="checklist__icon is-pass">
                    <CircleCheckFilled />
                  </el-icon>
                  <el-icon v-else-if="precheck" class="checklist__icon is-fail">
                    <CircleCloseFilled />
                  </el-icon>
                  <el-icon v-else class="checklist__icon is-skip"><QuestionFilled /></el-icon>
                  <div class="checklist__body">
                    <span class="checklist__label">
                      {{ docsPass ? '无未结单据' : '存在未结单据' }}
                    </span>
                    <span v-if="precheck && !docsPass" class="checklist__desc">
                      未结单据 {{ openDocsCount }} 笔（含未确认询价 / 未完成出库）
                    </span>
                    <span v-else class="checklist__desc">
                      无未确认询价与未完成出库单据
                    </span>
                  </div>
                </li>

                <!-- 账单结清（O-5 占位灰态） -->
                <li class="checklist__item is-placeholder">
                  <el-icon class="checklist__icon is-skip"><RemoveFilled /></el-icon>
                  <div class="checklist__body">
                    <span class="checklist__label">账单已结清</span>
                    <span class="checklist__desc">本期不校验（计费上线后启用）</span>
                  </div>
                </li>
              </ul>
            </section>

            <section class="card consequence-card">
              <h3 class="card-title">退驻后果</h3>
              <ul class="consequence-list">
                <li>全部 SKU 下架</li>
                <li>店铺页不再展示本商户</li>
                <li>客户专属价全部失效</li>
                <li>本账号及员工账号将立即退出登录</li>
              </ul>
              <el-alert
                type="info"
                :closable="false"
                show-icon
                title="60 天内可申请恢复，逾期自动归档"
              />

              <el-form label-position="top" class="withdraw-form" @submit.prevent>
                <el-form-item label="退驻原因（选填）">
                  <el-input
                    v-model="form.reason"
                    type="textarea"
                    :rows="3"
                    maxlength="200"
                    show-word-limit
                    placeholder="可简要说明退驻原因，便于仓库管理员了解情况"
                  />
                </el-form-item>
              </el-form>

              <div class="consequence-card__actions">
                <el-button v-if="reapplying" @click="reapplying = false">返回</el-button>
                <el-button
                  type="danger"
                  :disabled="!canSubmit"
                  :loading="submitting"
                  @click="openConfirm"
                >
                  提交退驻申请
                </el-button>
              </div>
              <p v-if="!canSubmit && !precheckLoading" class="submit-hint">
                前置条件未全部通过，暂不可提交；处理完成后点「重新检查」刷新
              </p>
            </section>
          </template>

          <!-- ============ 待审批态 ============ -->
          <section v-else-if="view === 'pending'" class="card status-card">
            <div class="status-card__head">
              <span class="status-card__label">退驻申请状态</span>
              <StatusBadge variant="warning" text="退驻审批中" :dot="true" />
            </div>

            <!-- 时间线：提交 → TA 审批 → 生效 -->
            <div class="timeline">
              <div class="timeline__node is-done">
                <span class="timeline__dot" />
                <span class="timeline__text">提交</span>
              </div>
              <span class="timeline__bar is-active" />
              <div class="timeline__node is-active">
                <span class="timeline__dot" />
                <span class="timeline__text">仓库审批</span>
              </div>
              <span class="timeline__bar" />
              <div class="timeline__node">
                <span class="timeline__dot" />
                <span class="timeline__text">生效</span>
              </div>
            </div>

            <el-alert
              type="warning"
              :closable="false"
              show-icon
              title="申请已提交，等待仓库管理员审批"
              description="审批通过的瞬间：SKU 全部下架、店铺隐藏、专属价失效，您与员工将被立即退出登录。"
            />

            <el-descriptions :column="2" border class="status-card__desc">
              <el-descriptions-item label="目标仓库">
                {{ tenantNameDisplay }}
              </el-descriptions-item>
              <el-descriptions-item label="提交时间">
                {{ formatTime(application?.createdAt) }}
              </el-descriptions-item>
              <el-descriptions-item label="退驻原因" :span="2">
                {{ application?.reason || '—' }}
              </el-descriptions-item>
            </el-descriptions>

            <div class="status-card__actions">
              <el-button :loading="cancelling" @click="onCancel">撤回退驻申请</el-button>
            </div>
          </section>

          <!-- ============ 已驳回态 ============ -->
          <section v-else-if="view === 'rejected'" class="card status-card">
            <div class="status-card__head">
              <span class="status-card__label">退驻申请状态</span>
              <StatusBadge variant="danger" text="已驳回" :dot="true" />
            </div>

            <el-alert
              type="error"
              :closable="false"
              show-icon
              title="退驻申请已被驳回"
              :description="`驳回理由：${application?.auditRemark || '仓库管理员未填写（可联系仓库确认）'}`"
            />

            <el-descriptions :column="2" border class="status-card__desc">
              <el-descriptions-item label="提交时间">
                {{ formatTime(application?.createdAt) }}
              </el-descriptions-item>
              <el-descriptions-item label="驳回时间">
                {{ formatTime(application?.auditedAt) }}
              </el-descriptions-item>
            </el-descriptions>

            <div class="status-card__actions">
              <el-button type="primary" @click="startReapply">重新申请</el-button>
            </div>
          </section>

          <!-- ============ 已退驻态（60 天倒计时 + 恢复） ============ -->
          <section v-else-if="view === 'withdrawn'" class="card status-card withdrawn-card">
            <div class="withdrawn-card__hero">
              <span class="withdrawn-card__emoji">⚪</span>
              <h3 class="withdrawn-card__title">您已退驻</h3>
              <p class="withdrawn-card__tenant">{{ tenantNameDisplay }}</p>
            </div>

            <el-descriptions :column="2" border class="status-card__desc">
              <el-descriptions-item label="退驻生效">
                {{ formatDate(application?.auditedAt) }}
              </el-descriptions-item>
              <el-descriptions-item label="恢复截止">
                {{
                  restoreDeadlineDate
                    ? formatDate(restoreDeadlineDate.toISOString())
                    : '—'
                }}
              </el-descriptions-item>
            </el-descriptions>

            <div class="countdown-box">
              ⏳ 剩余 <b>{{ restoreDaysLeft }}</b> 天可申请恢复入驻
            </div>

            <ul class="withdrawn-notes">
              <li>恢复后 SKU / 客户专属价需重新设置，历史单据与流水保留</li>
              <li>逾期未恢复将归档，需重新申请入驻</li>
            </ul>

            <div class="status-card__actions is-column">
              <el-button type="primary" :loading="restoring" @click="onRestore">
                申请恢复入驻
              </el-button>
              <el-button @click="router.push('/wa/apply')">申请入驻其他仓库</el-button>
            </div>
          </section>

          <!-- ============ 已归档态（>60 天） ============ -->
          <section v-else-if="view === 'archived'" class="card status-card withdrawn-card">
            <div class="withdrawn-card__hero">
              <span class="withdrawn-card__emoji">⚪</span>
              <h3 class="withdrawn-card__title">您已退驻</h3>
              <p class="withdrawn-card__tenant">{{ tenantNameDisplay }}</p>
            </div>

            <div class="archived-bar">恢复期已过，账号已归档；如需回归请重新申请入驻</div>

            <div class="status-card__actions is-column">
              <el-button type="primary" @click="router.push('/wa/apply')">
                申请入驻其他仓库
              </el-button>
            </div>
          </section>
        </div>
      </main>
    </div>

    <!-- 提交确认弹窗（06b §3.2） -->
    <el-dialog
      v-model="confirmVisible"
      title="⚠️ 确认提交退驻申请？"
      width="440px"
      :close-on-click-modal="false"
    >
      <p class="confirm-tip">
        提交后将由 <b>{{ tenantNameDisplay }}</b> 管理员审批。审批通过的瞬间：
      </p>
      <ul class="confirm-list">
        <li>SKU 全部下架、店铺隐藏</li>
        <li>专属价全部失效</li>
        <li>您与员工将被立即退出登录</li>
      </ul>
      <p class="confirm-tip">60 天内可申请恢复入驻。</p>
      <template #footer>
        <el-button @click="confirmVisible = false">再想想</el-button>
        <el-button type="danger" :loading="submitting" @click="onSubmit">确认提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wa-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}

/* ===== 顶栏（同 Apply.vue） ===== */
.wa-topbar {
  height: 56px;
  background: var(--color-brand-primary);
  color: var(--color-brand-primary-on);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-6);
  position: sticky;
  top: 0;
  z-index: var(--z-fixed);
  box-shadow: var(--shadow-base);
}
.wa-topbar__left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-size-h3);
}
.wa-topbar__brand {
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.5px;
}
.wa-topbar__divider {
  opacity: 0.5;
}
.wa-topbar__store {
  font-weight: var(--font-weight-medium);
  opacity: 0.95;
}
.wa-topbar__right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.wa-topbar__right :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
}
.wa-topbar__right :deep(.el-button.is-text:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}
.wa-topbar__bell :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
  font-size: 18px;
}
.wa-topbar__user {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  padding: 0 var(--space-2);
}
.wa-topbar__user :deep(.el-icon) {
  color: rgba(255, 255, 255, 0.7);
}

/* ===== body / 菜单（同 Apply.vue） ===== */
.wa-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}
.wa-side {
  width: 220px;
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.wa-side__menu {
  border-right: none;
}
.wa-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.wa-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}

/* ===== 主区 ===== */
.wa-main {
  flex: 1;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
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

/* ===== 内容 ===== */
.withdraw-content {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  max-width: 720px;
}
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  box-shadow: var(--shadow-base);
}
.card-title {
  margin: 0;
  font-size: var(--font-size-h3);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.reapply-alert {
  max-width: 720px;
}

/* ===== 自查清单 ===== */
.checklist-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.checklist-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}
.checklist {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}
.checklist__item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-bottom: 1px dashed var(--color-border-1);
}
.checklist__item:last-child {
  border-bottom: none;
}
.checklist__item.is-placeholder {
  opacity: 0.6;
}
.checklist__icon {
  font-size: 20px;
  margin-top: 2px;
  flex-shrink: 0;
}
.checklist__icon.is-pass {
  color: var(--color-success);
}
.checklist__icon.is-fail {
  color: var(--color-danger);
}
.checklist__icon.is-skip {
  color: var(--color-fg-4);
}
.checklist__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.checklist__label {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.checklist__desc {
  font-size: var(--font-size-caption);
  color: var(--color-fg-3);
}

/* ===== 后果卡 ===== */
.consequence-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.consequence-list {
  margin: 0;
  padding-left: 1.4em;
  color: var(--color-fg-2);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: var(--font-size-body);
}
.withdraw-form :deep(.el-form-item) {
  margin-bottom: 0;
}
.consequence-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
.submit-hint {
  margin: 0;
  text-align: right;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
}

/* ===== 状态卡 ===== */
.status-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.status-card__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.status-card__label {
  font-size: var(--font-size-h3);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.status-card__desc {
  margin-top: var(--space-1);
}
.status-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
.status-card__actions.is-column {
  flex-direction: column;
  align-items: stretch;
}
.status-card__actions.is-column .el-button {
  margin-left: 0;
}

/* ===== 时间线（提交 → 审批 → 生效） ===== */
.timeline {
  display: flex;
  align-items: center;
  padding: var(--space-2) var(--space-4);
}
.timeline__node {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-1);
  min-width: 64px;
}
.timeline__dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--color-bg-3, #e5e6eb);
  border: 2px solid var(--color-border-2, #c9cdd4);
  box-sizing: border-box;
}
.timeline__node.is-done .timeline__dot {
  background: var(--color-success);
  border-color: var(--color-success);
}
.timeline__node.is-active .timeline__dot {
  background: var(--color-warning);
  border-color: var(--color-warning);
}
.timeline__text {
  font-size: var(--font-size-caption);
  color: var(--color-fg-3);
  white-space: nowrap;
}
.timeline__node.is-done .timeline__text,
.timeline__node.is-active .timeline__text {
  color: var(--color-fg-1);
  font-weight: var(--font-weight-medium);
}
.timeline__bar {
  flex: 1;
  height: 2px;
  background: var(--color-border-1);
  margin: 0 var(--space-2);
  margin-bottom: 20px;
}
.timeline__bar.is-active {
  background: var(--color-success);
}

/* ===== 已退驻 ===== */
.withdrawn-card__hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-3) 0;
}
.withdrawn-card__emoji {
  font-size: 40px;
  line-height: 1;
}
.withdrawn-card__title {
  margin: var(--space-2) 0 0;
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-bold);
  color: var(--color-fg-1);
}
.withdrawn-card__tenant {
  margin: 0;
  color: var(--color-fg-3);
}
.countdown-box {
  border: 1px solid var(--color-warning, #ff7d00);
  background: var(--color-warning-bg, #fff7e8);
  color: var(--color-fg-1);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  text-align: center;
  font-size: var(--font-size-body);
}
.countdown-box b {
  font-size: var(--font-size-h2);
  margin: 0 4px;
}
.withdrawn-notes {
  margin: 0;
  padding-left: 1.4em;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.archived-bar {
  background: var(--color-bg-2);
  border: 1px solid var(--color-border-1);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  text-align: center;
  color: var(--color-fg-3);
}

/* ===== 确认弹窗 ===== */
.confirm-tip {
  margin: 0 0 var(--space-2);
  color: var(--color-fg-2);
  line-height: var(--line-height-normal);
}
.confirm-list {
  margin: 0 0 var(--space-3);
  padding-left: 1.4em;
  color: var(--color-fg-2);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .wa-side {
    display: none;
  }
  .wa-main {
    padding: var(--space-4);
    min-width: 0;
  }
  /* 窄屏顶栏：品牌/店名单行省略，防纵向折行 */
  .wa-topbar {
    padding: 0 var(--space-4);
  }
  .wa-topbar__left {
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
  }
  .wa-topbar__store {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .wa-topbar__right {
    flex-shrink: 0;
  }
  .status-card__actions {
    flex-direction: column;
    align-items: stretch;
  }
  .status-card__actions .el-button {
    margin-left: 0;
  }
}
</style>
