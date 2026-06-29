<script setup lang="ts">
/**
 * TA 店铺设置（PC）
 *
 * 来源：
 *  - 线框：shared/product/06-page-wireframes.md §2.2（地图地址 + 5 个总开关）+ §2.4（计费规则变更确认 R20）
 *  - 规则：shared/product/05-business-rules.md §1（计费维度）/ §8（容量公示）/ §9（临期）/ §13 开关矩阵
 *  - 视觉：MASTER 设计 token，沿用 Dashboard.vue 的顶栏 + 左侧菜单 shell 与 Register.vue 的 el-form 风格
 *  - 故事：US-TA-04（计费）/ US-TA-10（容量公示）/ US-TA-11（拍照）/ US-TA-12（批次）
 *
 * 契约：
 *  - GET  /tenant/me  → TenantSettings（tenantApi.getSettings）
 *  - PUT  /tenant/me  ← UpdateTenantSettingsRequest（tenantApi.updateSettings）
 *  - 计费规则字段变更 → 需 confirmed=true（二次确认弹窗）
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ArrowDown,
  Switch,
  Bell,
  Shop,
  User,
  Document,
  Coin,
  ChatLineSquare,
  Setting,
  TrendCharts,
} from '@element-plus/icons-vue'
import type {
  TenantSettings,
  UpdateTenantSettingsRequest,
  CapacityVisibility,
  CapacityPrecision,
  PhotoMode,
} from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { tenantApi } from '@/api/tenant'
import { accountApi } from '@/api/account'

const router = useRouter()
const auth = useAuthStore()

// ============ 顶栏 ============
const storeNameDisplay = computed(() => auth.currentStoreName || form.storeName || '我的店铺')

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

// ============ 菜单 ============
const activeMenu = ref('/ta/settings')

interface MenuItem {
  key: string
  label: string
  icon: typeof Setting
}

const menus: MenuItem[] = [
  { key: '/ta/dashboard', label: '工作台', icon: TrendCharts },
  { key: '/ta/settings', label: '店铺设置', icon: Setting },
  { key: '/ta/employees', label: '员工', icon: User },
  { key: '/ta/wholesalers', label: '入驻商户', icon: Shop },
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  { key: '/ta/approvals', label: '单据审批', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ta/settings') {
    activeMenu.value = key
    return
  }
  if (key === '/ta/dashboard' || key === '/ta/wholesalers') {
    router.push(key)
    return
  }
  // 其它菜单页尚未实现，保持占位
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 表单数据 ============
const formRef = ref<FormInstance>()
const loading = ref(false)
const saving = ref(false)

// 完整快照（用于检测计费规则是否变更 → 是否需要二次确认）
const original = ref<TenantSettings | null>(null)
// 只读元信息
const meta = reactive({
  tenantId: '',
  tenantSimpleCode: '',
  accuracySource: '' as string,
})

const form = reactive({
  storeName: '',
  // 地址
  addressText: '',
  lng: undefined as number | undefined,
  lat: undefined as number | undefined,
  // 5 开关
  batchEnabled: false,
  photoMode: 'OPTIONAL' as PhotoMode,
  capacityVisibility: 'WA_ONLY' as CapacityVisibility,
  capacityPrecision: 'EXACT' as CapacityPrecision,
  // 计费维度
  billingByQty: true,
  billingByPallet: false,
  pricePerQtyDay: undefined as number | undefined,
  pricePerPalletDay: undefined as number | undefined,
  expiryThresholdDays: 30 as number | undefined,
  // 容量
  totalQty: undefined as number | undefined,
  totalPallet: undefined as number | undefined,
})

// ============ 校验规则 ============
const rules = computed<FormRules>(() => ({
  storeName: [
    { required: true, message: '请输入店铺名称', trigger: 'blur' },
    { max: 30, message: '店铺名称最多 30 字', trigger: 'blur' },
  ],
  // 计费维度：至少启用一种
  billingByQty: [
    {
      validator: (_r, _v, cb) => {
        if (!form.billingByQty && !form.billingByPallet) {
          cb(new Error('请至少启用一种计费维度'))
        } else {
          cb()
        }
      },
      trigger: 'change',
    },
  ],
  pricePerQtyDay: [
    {
      validator: (_r, v, cb) => {
        if (form.billingByQty && (v === undefined || v === null || Number(v) < 0)) {
          cb(new Error('请填写件·天单价（≥0）'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
  pricePerPalletDay: [
    {
      validator: (_r, v, cb) => {
        if (form.billingByPallet && (v === undefined || v === null || Number(v) < 0)) {
          cb(new Error('请填写托盘·天单价（≥0）'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
  expiryThresholdDays: [
    {
      validator: (_r, v, cb) => {
        if (form.batchEnabled) {
          if (v === undefined || v === null || Number(v) < 1) {
            cb(new Error('临期阈值需 ≥1 天'))
            return
          }
        }
        cb()
      },
      trigger: 'change',
    },
  ],
}))

// ============ 回填 ============
const applyToForm = (s: TenantSettings) => {
  meta.tenantId = String(s.tenantId)
  meta.tenantSimpleCode = s.tenantSimpleCode
  meta.accuracySource = s.address?.accuracySource ?? ''

  form.storeName = s.storeName ?? ''
  form.addressText = s.address?.text ?? ''
  form.lng = s.address?.lng
  form.lat = s.address?.lat

  form.batchEnabled = !!s.batchEnabled
  form.photoMode = s.photoMode ?? 'OPTIONAL'
  form.capacityVisibility = s.capacityVisibility ?? 'WA_ONLY'
  form.capacityPrecision = s.capacityPrecision ?? 'EXACT'

  form.billingByQty = !!s.billingByQty
  form.billingByPallet = !!s.billingByPallet
  form.pricePerQtyDay = s.pricePerQtyDay
  form.pricePerPalletDay = s.pricePerPalletDay
  form.expiryThresholdDays = s.expiryThresholdDays ?? 30

  form.totalQty = s.totalQty
  form.totalPallet = s.totalPallet
}

const fetchSettings = async () => {
  loading.value = true
  try {
    const data = await tenantApi.getSettings()
    original.value = data
    applyToForm(data)
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

// ============ 计费规则变更检测（R20） ============
const billingChanged = computed(() => {
  const o = original.value
  if (!o) return false
  return (
    !!o.billingByQty !== form.billingByQty ||
    !!o.billingByPallet !== form.billingByPallet ||
    o.pricePerQtyDay !== form.pricePerQtyDay ||
    o.pricePerPalletDay !== form.pricePerPalletDay
  )
})

// ============ 提交 ============
const buildPayload = (confirmed: boolean): UpdateTenantSettingsRequest => ({
  storeName: form.storeName,
  address:
    form.addressText || form.lng !== undefined || form.lat !== undefined
      ? {
          text: form.addressText,
          lng: form.lng ?? 0,
          lat: form.lat ?? 0,
          accuracySource: (original.value?.address?.accuracySource ?? 'MAP_CLICK'),
        }
      : null,
  batchEnabled: form.batchEnabled,
  photoMode: form.photoMode,
  capacityVisibility: form.capacityVisibility,
  capacityPrecision: form.capacityPrecision,
  billingByQty: form.billingByQty,
  billingByPallet: form.billingByPallet,
  pricePerQtyDay: form.billingByQty ? form.pricePerQtyDay : undefined,
  pricePerPalletDay: form.billingByPallet ? form.pricePerPalletDay : undefined,
  expiryThresholdDays: form.batchEnabled ? form.expiryThresholdDays : undefined,
  totalQty: form.totalQty,
  totalPallet: form.totalPallet,
  confirmed,
})

const onSubmit = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  // 计费规则变更 → 二次确认（R20）
  let confirmed = false
  if (billingChanged.value) {
    try {
      const lines: string[] = []
      if (form.billingByQty) lines.push(`件·天单价：${form.pricePerQtyDay ?? '—'} 元/件·天`)
      if (form.billingByPallet) lines.push(`托盘·天单价：${form.pricePerPalletDay ?? '—'} 元/托盘·天`)
      await ElMessageBox.confirm(
        `变更将于立即生效，规则如下：\n${lines.join('\n')}\n\n影响：\n· 本月历史账单不重算\n· 当月在算账单按分段计费\n· 所有入驻批发商将收到通知`,
        '确认变更计费规则',
        {
          confirmButtonText: '确认变更',
          cancelButtonText: '取消',
          type: 'warning',
          dangerouslyUseHTMLString: false,
        },
      )
      confirmed = true
    } catch {
      return // 取消
    }
  }

  saving.value = true
  try {
    await tenantApi.updateSettings(buildPayload(confirmed))
    ElMessage.success('店铺设置已保存')
    await fetchSettings() // 重新拉取，刷新 original 快照
  } catch {
    // 全局 toast 已提示
  } finally {
    saving.value = false
  }
}

const onReset = () => {
  if (original.value) applyToForm(original.value)
  formRef.value?.clearValidate()
  ElMessage.info('已还原为上次保存的设置')
}

onMounted(fetchSettings)
</script>

<template>
  <div class="ta-shell" v-loading="loading">
    <!-- 顶栏 -->
    <header class="ta-topbar">
      <div class="ta-topbar__left">
        <span class="ta-topbar__brand">仓储云</span>
        <span class="ta-topbar__divider">·</span>
        <span class="ta-topbar__store">{{ storeNameDisplay }}</span>
      </div>

      <div class="ta-topbar__right">
        <el-button text @click="handleSwitchRole">
          <el-icon><Switch /></el-icon>
          切换角色
        </el-button>
        <el-button text :icon="Bell" class="ta-topbar__bell" />
        <el-dropdown trigger="click" @command="handleProfileMenu">
          <span class="ta-topbar__user">
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

    <div class="ta-body">
      <!-- 左侧菜单 -->
      <aside class="ta-side">
        <el-menu :default-active="activeMenu" class="ta-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">店铺设置</h2>
            <p class="page-head__sub">
              店铺码：<code>{{ meta.tenantSimpleCode || '—' }}</code>
            </p>
          </div>
        </header>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="settings-form"
          @submit.prevent="onSubmit"
        >
          <!-- 基础资料 -->
          <section class="card">
            <h3 class="card__title">基础资料</h3>
            <el-form-item label="店铺名称" prop="storeName">
              <el-input v-model="form.storeName" placeholder="如：XX 海鲜库" maxlength="30" show-word-limit />
            </el-form-item>
          </section>

          <!-- 仓库地址（含坐标） -->
          <section class="card">
            <h3 class="card__title">仓库地址（含坐标）</h3>
            <el-form-item label="详细地址">
              <el-input
                v-model="form.addressText"
                type="textarea"
                :rows="2"
                placeholder="如：北京市顺义区 XX 路 XX 号"
                maxlength="120"
              />
            </el-form-item>
            <div class="coord-row">
              <el-form-item label="经度 (lng)" class="coord-item">
                <el-input-number
                  v-model="form.lng"
                  :precision="6"
                  :step="0.0001"
                  :controls="false"
                  placeholder="116.6531"
                  class="full-w"
                />
              </el-form-item>
              <el-form-item label="纬度 (lat)" class="coord-item">
                <el-input-number
                  v-model="form.lat"
                  :precision="6"
                  :step="0.0001"
                  :controls="false"
                  placeholder="40.1287"
                  class="full-w"
                />
              </el-form-item>
            </div>
            <p class="hint">
              定位来源：{{ meta.accuracySource || '未定位' }}
              ｜ 地图选点 SDK（高德/腾讯）待后续 Agent 接入，当前可手填坐标
            </p>
          </section>

          <!-- 运营开关（5 开关） -->
          <section class="card">
            <h3 class="card__title">运营开关</h3>

            <!-- 1. 批次管理 -->
            <div class="switch-row">
              <div class="switch-row__label">
                <span class="switch-row__name">批次管理</span>
                <span class="switch-row__desc">关闭时入库不录批次/保质期，临期预警停用</span>
              </div>
              <el-switch
                v-model="form.batchEnabled"
                active-text="启用"
                inactive-text="关闭"
                inline-prompt
              />
            </div>

            <el-divider class="thin" />

            <!-- 2. 入库拍照 -->
            <div class="switch-row">
              <div class="switch-row__label">
                <span class="switch-row__name">入库拍照</span>
                <span class="switch-row__desc">控制入库录单是否需要现场照片，及 SKU 展示图来源</span>
              </div>
              <el-radio-group v-model="form.photoMode">
                <el-radio-button value="OFF">关闭</el-radio-button>
                <el-radio-button value="OPTIONAL">选填</el-radio-button>
                <el-radio-button value="REQUIRED">必填</el-radio-button>
              </el-radio-group>
            </div>

            <el-divider class="thin" />

            <!-- 3. 容量公示 -->
            <div class="switch-block">
              <div class="switch-row__label">
                <span class="switch-row__name">容量公示</span>
                <span class="switch-row__desc">决定本店在 RT/WA 列表中的可见性与精度（§8）</span>
              </div>
              <div class="sub-field">
                <span class="sub-field__label">可见范围</span>
                <el-radio-group v-model="form.capacityVisibility">
                  <el-radio value="PRIVATE">不公开</el-radio>
                  <el-radio value="WA_ONLY">已入驻批发商</el-radio>
                  <el-radio value="PUBLIC">全平台</el-radio>
                </el-radio-group>
              </div>
              <div class="sub-field">
                <span class="sub-field__label">精度档位</span>
                <el-radio-group v-model="form.capacityPrecision">
                  <el-radio value="EXACT">精确数</el-radio>
                  <el-radio value="TIER">模糊档（&lt;30% / 30-70% / &gt;70%）</el-radio>
                </el-radio-group>
              </div>
            </div>

            <el-divider class="thin" />

            <!-- 4. 计费维度 -->
            <div class="switch-block">
              <div class="switch-row__label">
                <span class="switch-row__name">计费维度</span>
                <span class="switch-row__desc">至少启用一种；变更单价需二次确认（R20）</span>
              </div>

              <el-form-item prop="billingByQty" class="bill-line">
                <el-checkbox v-model="form.billingByQty">件·天</el-checkbox>
                <el-form-item prop="pricePerQtyDay" class="bill-line__price">
                  <el-input-number
                    v-model="form.pricePerQtyDay"
                    :min="0"
                    :precision="2"
                    :step="0.01"
                    :disabled="!form.billingByQty"
                    placeholder="0.05"
                  />
                  <span class="unit">元 / 件·天</span>
                </el-form-item>
              </el-form-item>

              <el-form-item prop="pricePerPalletDay" class="bill-line">
                <el-checkbox v-model="form.billingByPallet">托盘·天</el-checkbox>
                <el-input-number
                  v-model="form.pricePerPalletDay"
                  :min="0"
                  :precision="2"
                  :step="0.01"
                  :disabled="!form.billingByPallet"
                  placeholder="1.20"
                  class="bill-line__price"
                />
                <span class="unit">元 / 托盘·天</span>
              </el-form-item>
            </div>

            <el-divider class="thin" />

            <!-- 5. 临期阈值 -->
            <div class="switch-row">
              <div class="switch-row__label">
                <span class="switch-row__name">临期阈值</span>
                <span class="switch-row__desc">仅批次启用时生效（§9.1，默认 30 天）</span>
              </div>
              <el-form-item prop="expiryThresholdDays" class="inline-item">
                <el-input-number
                  v-model="form.expiryThresholdDays"
                  :min="1"
                  :max="365"
                  :step="1"
                  :disabled="!form.batchEnabled"
                />
                <span class="unit">天</span>
              </el-form-item>
            </div>
          </section>

          <!-- 容量（可选） -->
          <section class="card">
            <h3 class="card__title">额定容量（可选）</h3>
            <div class="coord-row">
              <el-form-item label="总件数容量" class="coord-item">
                <el-input-number v-model="form.totalQty" :min="0" :step="100" :controls="false" class="full-w" placeholder="如 100000" />
              </el-form-item>
              <el-form-item label="总托盘容量" class="coord-item">
                <el-input-number v-model="form.totalPallet" :min="0" :step="10" :controls="false" class="full-w" placeholder="如 2000" />
              </el-form-item>
            </div>
          </section>

          <!-- 操作栏 -->
          <div class="action-bar">
            <el-button @click="onReset">取消</el-button>
            <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
          </div>
        </el-form>
      </main>
    </div>
  </div>
</template>

<style scoped>
.ta-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}

/* ===== 顶栏（复用 Dashboard 风格） ===== */
.ta-topbar {
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
.ta-topbar__left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-size-h3);
}
.ta-topbar__brand {
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.5px;
}
.ta-topbar__divider {
  opacity: 0.5;
}
.ta-topbar__store {
  font-weight: var(--font-weight-medium);
  opacity: 0.95;
}
.ta-topbar__right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.ta-topbar__right :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
}
.ta-topbar__right :deep(.el-button.is-text:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}
.ta-topbar__bell :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
  font-size: 18px;
}
.ta-topbar__user {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  padding: 0 var(--space-2);
}
.ta-topbar__user :deep(.el-icon) {
  color: rgba(255, 255, 255, 0.7);
}

/* ===== body ===== */
.ta-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}

/* ===== 左侧菜单 ===== */
.ta-side {
  width: 220px;
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.ta-side__menu {
  border-right: none;
}
.ta-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.ta-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}

/* ===== 主区 ===== */
.ta-main {
  flex: 1;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  max-width: 920px;
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
.page-head__sub code {
  background: var(--color-bg-3);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-family: var(--font-family-mono);
}

.settings-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* ===== 卡片 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  box-shadow: var(--shadow-base);
}
.card__title {
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  margin: 0 0 var(--space-4);
}

/* 坐标 / 容量两列 */
.coord-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
}
.coord-item {
  margin-bottom: 0;
}
.full-w {
  width: 100%;
}
.full-w :deep(.el-input-number) {
  width: 100%;
}

.hint {
  margin: var(--space-3) 0 0;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
}

/* ===== 开关行 ===== */
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-2) 0;
}
.switch-block {
  padding: var(--space-2) 0;
}
.switch-row__label {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.switch-row__name {
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.switch-row__desc {
  font-size: var(--font-size-caption);
  color: var(--color-fg-3);
}

.thin {
  margin: var(--space-3) 0;
}

/* 子字段（容量公示） */
.sub-field {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-top: var(--space-3);
  flex-wrap: wrap;
}
.sub-field__label {
  width: 84px;
  flex-shrink: 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-body);
}

/* 计费行 */
.bill-line {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
  margin-bottom: 0;
}
.bill-line :deep(.el-form-item__content) {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.bill-line__price {
  margin-bottom: 0;
}
.unit {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

.inline-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: 0;
}
.inline-item :deep(.el-form-item__content) {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

/* ===== 操作栏 ===== */
.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding: var(--space-2) 0 var(--space-6);
  position: sticky;
  bottom: 0;
}

/* ===== 响应式 ===== */
@media (max-width: 1024px) {
  .ta-main {
    max-width: none;
  }
}
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .coord-row {
    grid-template-columns: 1fr;
  }
  .switch-row {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }
  .sub-field {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }
}
</style>
