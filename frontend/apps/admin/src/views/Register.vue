<script setup lang="ts">
/**
 * 注册页（角色感知）
 *
 * 来源：
 *  - 线框：shared/product/06-page-wireframes.md §0.5.2
 *  - 视觉：shared/design-system/pages/auth.md
 *  - 故事：US-COMMON-01 / US-TA-01
 *
 * URL 参数：?role=ta|wa|wk|st|we|rt （默认 rt）
 *
 * 字段矩阵（来自 US-COMMON-01）：
 *  - TA: phone + smsCode + password + realName + tenantName + 营业资质（占位）
 *  - WA: phone + smsCode + password + realName + wholesalerName + targetTenantId + 营业资质
 *  - WK/ST/WE: phone + smsCode + password + realName + inviteCode
 *  - RT: phone + smsCode + agreedTerms（自动昵称）
 */

import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { z } from 'zod'
import type { Role } from '@cangchu/api-types'
import { accountApi } from '@/api/account'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

// ============ 角色解析 ============
const SUPPORTED_ROLES: Role[] = ['TA', 'WA', 'WK', 'ST', 'WE', 'RT']

// 员工受邀注册角色（凭码）
const EMPLOYEE_ROLES: Role[] = ['WK', 'ST', 'WE']

const role = computed<Role>(() => {
  const r = String(route.query.role || '').toUpperCase() as Role
  if (SUPPORTED_ROLES.includes(r)) return r
  // ?invite=1 但未带 role：进入员工凭码注册模式，默认库管员(WK)
  if (String(route.query.invite) === '1') return 'WK'
  return 'RT'
})

const roleLabel = computed(() => {
  const map: Record<Role, string> = {
    OPS: '运维',
    TA: '仓库入驻（租户管理员）',
    WA: '批发商入驻',
    WK: '库管员（受邀注册）',
    ST: '结算员（受邀注册）',
    WE: '批发商员工（受邀注册）',
    RT: '终端买家',
  }
  return map[role.value]
})

// 角色门槛
const needPassword = computed(() => role.value !== 'RT')
const needRealName = computed(() => role.value !== 'RT')
const needTenantName = computed(() => role.value === 'TA')
const needWholesalerName = computed(() => role.value === 'WA')
const needTargetTenantId = computed(() => role.value === 'WA')
const needInviteCode = computed(() => ['WK', 'ST', 'WE'].includes(role.value))
const needBusinessLicense = computed(() => ['TA', 'WA'].includes(role.value))

// ============ 表单 ============
const formRef = ref<FormInstance>()
const form = reactive({
  phone: '',
  smsCode: '',
  password: '',
  realName: '',
  tenantName: '',
  wholesalerName: '',
  targetTenantId: '',
  inviteCode: '',
  agreedTerms: false,
})

const loading = ref(false)

// ============ Zod ============
const phoneSchema = z.string().regex(/^1[3-9]\d{9}$/, '手机号格式不正确')
const passwordSchema = z
  .string()
  .min(6, '密码至少 6 位')
  .max(20, '密码最多 20 位')
  .regex(/[a-zA-Z]/, '密码必须包含字母')
  .regex(/\d/, '密码必须包含数字')

const rules = computed<FormRules>(() => ({
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        const r = phoneSchema.safeParse(v)
        cb(r.success ? undefined : new Error(r.error.issues[0]?.message))
      },
      trigger: 'blur',
    },
  ],
  smsCode: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{4,6}$/, message: '验证码为 4-6 位数字', trigger: 'blur' },
  ],
  password: needPassword.value
    ? [
        { required: true, message: '请设置密码', trigger: 'blur' },
        {
          validator: (_r, v, cb) => {
            const r = passwordSchema.safeParse(v)
            cb(r.success ? undefined : new Error(r.error.issues[0]?.message))
          },
          trigger: 'blur',
        },
      ]
    : [],
  realName: needRealName.value
    ? [{ required: true, message: '请输入真实姓名', trigger: 'blur' }]
    : [],
  tenantName: needTenantName.value
    ? [{ required: true, message: '请输入仓库名称', trigger: 'blur' }]
    : [],
  wholesalerName: needWholesalerName.value
    ? [{ required: true, message: '请输入商户名称', trigger: 'blur' }]
    : [],
  targetTenantId: needTargetTenantId.value
    ? [{ required: true, message: '请选择目标仓库', trigger: 'change' }]
    : [],
  inviteCode: needInviteCode.value
    ? [{ required: true, message: '请输入员工注册码', trigger: 'blur' }]
    : [],
}))

// ============ 验证码 ============
const smsCooldown = ref(0)
let smsTimer: ReturnType<typeof setInterval> | null = null

const sendSms = async () => {
  const r = phoneSchema.safeParse(form.phone)
  if (!r.success) {
    ElMessage.warning(r.error.issues[0]?.message ?? '手机号格式不正确')
    return
  }
  try {
    const res = await accountApi.sendSmsCode({ phone: form.phone, scene: 'REGISTER' })
    smsCooldown.value = res.cooldownSec || 60
    smsTimer && clearInterval(smsTimer)
    smsTimer = setInterval(() => {
      if (smsCooldown.value <= 1) {
        smsCooldown.value = 0
        smsTimer && clearInterval(smsTimer)
        smsTimer = null
      } else {
        smsCooldown.value--
      }
    }, 1000)
    ElMessage.success('验证码已发送')
  } catch {
    // 全局 toast
  }
}

const sendSmsBtnText = computed(() =>
  smsCooldown.value > 0 ? `${smsCooldown.value}s 后重试` : '发送验证码',
)

// ============ 营业资质（占位） ============
const businessLicenseFile = ref<File | null>(null)
const handleFileChange = (file: { raw?: File }) => {
  if (file.raw) businessLicenseFile.value = file.raw
  ElMessage.info('文件已选中（上传接口待后续 Agent 对接 OSS）')
}

// ============ 目标仓库选项（占位 mock；实际从 /api/v1/rt/stores 查） ============
const tenantOptions = ref<Array<{ id: string; name: string }>>([])

const fetchTenants = async () => {
  // 后端接口可能 /api/v1/rt/stores 暂未实现，前端 mock 列表
  tenantOptions.value = [
    { id: '184237892374820001', name: 'XX 海鲜库（顺义）' },
    { id: '184237892374820002', name: 'YY 冷藏中心（朝阳）' },
    { id: '184237892374820003', name: 'ZZ 仓库（通州）' },
  ]
}

// ============ 提交 ============
const onSubmit = async () => {
  if (!formRef.value) return
  if (!form.agreedTerms) {
    ElMessage.warning('请先同意《用户协议》《隐私政策》')
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    // D-16：后端 RegisterDto 已扩展，真实发送
    // realName/tenantName/wholesalerName/targetTenantId/agreedTerms。
    // agreedTerms 必须为 true，后端兜底校验（null/false 抛 40001）。
    // 营业资质（图片）待后端补上传接口后再对接 OSS。
    const payload = await accountApi.register({
      phone: form.phone,
      smsCode: form.smsCode,
      password: needPassword.value ? form.password : undefined,
      role: role.value,
      inviteCode: needInviteCode.value ? form.inviteCode : undefined,
      // 真实姓名（实名）独立发送，不再塞 nickname
      realName: needRealName.value ? form.realName : undefined,
      // TA 建仓：仓库名称
      tenantName: needTenantName.value ? form.tenantName : undefined,
      // WA 入驻：商户名称 + 目标租户
      wholesalerName: needWholesalerName.value ? form.wholesalerName : undefined,
      targetTenantId: needTargetTenantId.value ? form.targetTenantId : undefined,
      // 同意条款真实勾选值（前端已先拦，后端兜底）
      agreedTerms: form.agreedTerms,
    })

    ElMessage.success(
      role.value === 'TA'
        ? '注册成功！资质审核中，登录后可填资料'
        : role.value === 'WA'
          ? '注册成功！等待租户审批入驻'
          : EMPLOYEE_ROLES.includes(role.value)
            ? '注册成功！已为您加入所在仓库'
            : '注册成功，请登录',
    )

    // 注册返回即 LoginVo（含 token + roles），直接登录
    auth.setLoginPayload({
      token: payload.token,
      userId: payload.userId,
      primaryRole: payload.primaryRole,
      roles: payload.roles ?? [
        {
          role: payload.primaryRole,
          tenantId: null,
          wholesalerId: null,
          priority: 50,
        },
      ],
      primaryRouter: payload.primaryRouter,
      expireAt: payload.expireAt ?? '',
    })
    router.replace(payload.primaryRouter || '/ta/dashboard')
  } catch (e) {
    if (e instanceof ApiError) {
      // 41104 手机号已注册 → 引导登录
      if (e.code === 41104) {
        ElMessage.info('该手机号已注册，请直接登录')
      }
    } else {
      // 非 ApiError（如 getter/字段缺失抛错、网络异常）不静默吞掉
      console.error('[register] 非预期异常', e)
      ElMessage.error('注册异常，请重试')
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (needTargetTenantId.value) fetchTenants()
  // ?code=xxx 预填员工注册码（扫码进入注册页时）
  if (needInviteCode.value) {
    const code = route.query.code
    if (typeof code === 'string' && code.trim()) {
      form.inviteCode = code.trim()
    }
  }
})

onBeforeUnmount(() => {
  smsTimer && clearInterval(smsTimer)
})
</script>

<template>
  <div class="auth-page">
    <aside class="auth-brand">
      <div class="auth-brand__content">
        <h1 class="auth-brand__logo">仓储云</h1>
        <p class="auth-brand__slogan">让仓储更智能</p>
      </div>
      <footer class="auth-brand__footer">© 2026 仓储云</footer>
    </aside>

    <main class="auth-form">
      <div class="auth-card">
        <div class="auth-card__back">
          <router-link to="/login">← 返回登录</router-link>
        </div>
        <h2 class="auth-card__title">注册账号</h2>
        <p class="auth-card__sub">注册角色：{{ roleLabel }}</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="onSubmit"
        >
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" placeholder="请输入手机号" size="large" maxlength="11" inputmode="tel" />
          </el-form-item>

          <el-form-item label="验证码" prop="smsCode">
            <div class="sms-row">
              <el-input
                v-model="form.smsCode"
                placeholder="请输入短信验证码"
                size="large"
                maxlength="6"
                inputmode="numeric"
              />
              <el-button size="large" :disabled="smsCooldown > 0" class="sms-btn" @click="sendSms">
                {{ sendSmsBtnText }}
              </el-button>
            </div>
          </el-form-item>

          <el-form-item v-if="needPassword" label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="6-20 位，含字母 + 数字"
              size="large"
              autocomplete="new-password"
            />
          </el-form-item>

          <el-form-item v-if="needRealName" label="真实姓名" prop="realName">
            <el-input v-model="form.realName" placeholder="请输入真实姓名" size="large" maxlength="20" />
          </el-form-item>

          <el-form-item v-if="needTenantName" label="仓库名称" prop="tenantName">
            <el-input v-model="form.tenantName" placeholder="如：XX 海鲜库" size="large" maxlength="30" />
          </el-form-item>

          <el-form-item v-if="needWholesalerName" label="商户名称" prop="wholesalerName">
            <el-input v-model="form.wholesalerName" placeholder="如：鲜冻供应" size="large" maxlength="30" />
          </el-form-item>

          <el-form-item v-if="needTargetTenantId" label="目标仓库" prop="targetTenantId">
            <el-select v-model="form.targetTenantId" placeholder="选择想入驻的仓库" size="large" style="width: 100%">
              <el-option v-for="t in tenantOptions" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
          </el-form-item>

          <el-form-item v-if="needInviteCode" label="员工注册码" prop="inviteCode">
            <el-input
              v-model="form.inviteCode"
              placeholder="扫码或输入仓库/批发商提供的码"
              size="large"
              maxlength="20"
            />
          </el-form-item>

          <el-form-item v-if="needBusinessLicense" label="营业资质（图片）">
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept="image/*"
              :on-change="handleFileChange"
            >
              <el-button>选择文件</el-button>
              <span v-if="businessLicenseFile" class="upload-name">
                已选 · {{ businessLicenseFile.name }}
              </span>
              <span v-else class="upload-tip">支持 jpg/png，&lt;5MB</span>
            </el-upload>
          </el-form-item>

          <div class="agree-row">
            <el-checkbox v-model="form.agreedTerms">
              我已阅读并同意
            </el-checkbox>
            <a href="#" class="terms-link">《用户协议》</a>
            <a href="#" class="terms-link">《隐私政策》</a>
          </div>

          <el-button
            type="primary"
            size="large"
            class="submit-btn"
            :loading="loading"
            :disabled="!form.agreedTerms"
            @click="onSubmit"
          >
            注&nbsp;册
          </el-button>

          <p class="auth-footer">
            已有账号？
            <router-link to="/login">直接登录</router-link>
          </p>
        </el-form>
      </div>
    </main>
  </div>
</template>

<style scoped>
@import './auth-shared.scss';

.auth-card__back {
  margin-bottom: var(--space-3);
}
.auth-card__back a {
  color: var(--color-fg-3);
  font-size: var(--font-size-body);
}

.auth-card__sub {
  color: var(--color-fg-3);
  margin: 0 0 var(--space-6);
  font-size: var(--font-size-body);
}

.sms-row {
  display: flex;
  gap: var(--space-3);
  width: 100%;
}
.sms-row .el-input {
  flex: 1;
}
.sms-btn {
  width: 130px;
  flex-shrink: 0;
}

.upload-name {
  margin-left: var(--space-2);
  color: var(--color-success);
  font-size: var(--font-size-caption);
}
.upload-tip {
  margin-left: var(--space-2);
  color: var(--color-fg-4);
  font-size: var(--font-size-caption);
}

.agree-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin: var(--space-4) 0 var(--space-6);
}
.terms-link {
  color: var(--color-brand-accent);
  font-size: var(--font-size-body);
}
</style>
