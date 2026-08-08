<script setup lang="ts">
/**
 * 申诉处理（结算员 · P4 W4）
 *
 * - 队列：待处理升序先到先处理（后端排序）；可切换查看已处理留痕
 * - 处理弹窗：结论=成立/不成立 + 处理说明必填（50376 已处理防重）
 * - 申诉不冻结账单（PRD §3.5）；是否撤回调整由结算员判断
 */

import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { StatusBadge } from '@cangchu/ui-shared'
import type { BillDispute } from '@cangchu/api-types'
import { stBillApi } from '@/api/billing'
import { disputeStatusMeta, fmtDateTime } from '@/utils/billing'
import StShell from './StShell.vue'

const router = useRouter()

const loading = ref(false)
const statusFilter = ref('PENDING')
const rows = ref<BillDispute[]>([])

const fetchList = async () => {
  loading.value = true
  try {
    rows.value = (await stBillApi.listDisputes(statusFilter.value || undefined)) ?? []
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

onMounted(fetchList)

// ============ 处理弹窗 ============
const resolveVisible = ref(false)
const resolving = ref(false)
const target = ref<BillDispute | null>(null)
const conclusion = ref<'RESOLVED' | 'REJECTED'>('RESOLVED')
const resolution = ref('')

const openResolve = (d: BillDispute) => {
  target.value = d
  conclusion.value = 'RESOLVED'
  resolution.value = ''
  resolveVisible.value = true
}

const onResolve = async () => {
  if (!target.value) return
  if (!resolution.value.trim()) {
    ElMessage.warning('请填写处理说明')
    return
  }
  resolving.value = true
  try {
    await stBillApi.resolveDispute(String(target.value.id), {
      conclusion: conclusion.value,
      resolution: resolution.value.trim(),
    })
    resolveVisible.value = false
    ElMessage.success(
      conclusion.value === 'RESOLVED'
        ? '申诉已处理：成立（批发商将收到通知；如需改账请撤回账单后调整）'
        : '申诉已处理：不成立（批发商将收到通知）',
    )
    await fetchList()
  } catch {
    // 50376「该申诉已处理」：全局 toast 已提示
  } finally {
    resolving.value = false
  }
}
</script>

<template>
  <StShell active="/st/disputes">
    <header class="page-head">
      <div>
        <h2 class="page-head__title">申诉处理</h2>
        <p class="page-head__sub">先到先处理；申诉不冻结账单，是否撤回调整由您判断</p>
      </div>
      <el-radio-group v-model="statusFilter" size="small" data-test="dispute-filter" @change="fetchList">
        <el-radio-button value="PENDING">待处理</el-radio-button>
        <el-radio-button value="">全部</el-radio-button>
      </el-radio-group>
    </header>

    <section class="card">
      <el-table v-loading="loading" :data="rows" class="dispute-table" data-test="dispute-table">
        <el-table-column label="账单编号" min-width="190">
          <template #default="{ row }">
            <el-button text type="primary" size="small" class="mono" @click="router.push(`/st/bills/${row.billId}`)">
              {{ row.billNo }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="批发商" prop="wholesalerName" min-width="110" />
        <el-table-column label="申诉理由" prop="reason" min-width="200" show-overflow-tooltip />
        <el-table-column label="争议条目" width="90">
          <template #default="{ row }">
            {{ row.disputedItemIds?.length ? `${row.disputedItemIds.length} 条` : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusBadge :variant="disputeStatusMeta(row.status).variant" :text="disputeStatusMeta(row.status).label" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              type="primary"
              size="small"
              data-test="dispute-resolve-open"
              @click="openResolve(row as BillDispute)"
            >
              处理
            </el-button>
            <span v-else class="hint-inline">{{ row.resolution ?? '—' }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无申诉" :image-size="72" />
        </template>
      </el-table>
    </section>

    <!-- 处理弹窗 -->
    <el-dialog
      v-model="resolveVisible"
      title="处理账单申诉"
      width="480px"
      append-to-body
      data-test="dispute-resolve-dialog"
    >
      <template v-if="target">
        <p class="resolve-summary">
          账单：<span class="mono">{{ target.billNo }}</span> · {{ target.wholesalerName }}<br />
          申诉理由：{{ target.reason }}
        </p>
        <div v-if="target.attachments?.length" class="resolve-attach">
          <el-image
            v-for="(u, i) in target.attachments"
            :key="u"
            :src="u"
            :preview-src-list="target.attachments"
            :initial-index="i"
            preview-teleported
            fit="cover"
            class="resolve-attach__img"
          />
        </div>
      </template>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="处理结论" required>
          <el-radio-group v-model="conclusion" data-test="dispute-conclusion">
            <el-radio value="RESOLVED">成立</el-radio>
            <el-radio value="REJECTED">不成立</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处理说明（必填，双方留痕可见）" required>
          <el-input
            v-model="resolution"
            type="textarea"
            :rows="3"
            maxlength="300"
            show-word-limit
            placeholder="说明核实过程与结论依据"
            data-test="dispute-resolution"
          />
        </el-form-item>
        <p class="hint">※ 结论成立需改账时：请前往账单撤回下发后调整（历史已出账不重算）</p>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" :loading="resolving" data-test="dispute-resolve-submit" @click="onResolve">
          提交处理结果
        </el-button>
      </template>
    </el-dialog>
  </StShell>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
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
  padding: var(--space-4);
  box-shadow: var(--shadow-base);
}
.dispute-table {
  width: 100%;
}
.mono {
  font-family: var(--font-family-mono);
}
.hint {
  margin: var(--space-2) 0 0;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
}
.hint-inline {
  color: var(--color-fg-4);
  font-size: var(--font-size-caption);
}
.resolve-summary {
  margin: 0 0 var(--space-3);
  color: var(--color-fg-2);
  line-height: 1.8;
}
.resolve-attach {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
.resolve-attach__img {
  width: 56px;
  height: 56px;
  border-radius: var(--radius-sm);
}
</style>
