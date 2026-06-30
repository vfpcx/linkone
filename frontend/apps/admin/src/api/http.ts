/**
 * Axios HTTP 客户端 + 全局拦截器
 *
 * 拦截策略对齐 shared/architecture/05-error-codes.md 七大类：
 *  - 41xxx (AUTH)        清 token + 跳登录页
 *  - 42xxx (PERMISSION)  Toast「无权限」
 *  - 43xxx (LIMIT)       Toast + 显示 Retry-After 倒计时
 *  - 40xxx (VALIDATION)  Toast + 抛出 ApiError 给业务层做字段红边
 *  - 50xxx (STATE)       Toast 明确"状态不可达"
 *  - 60xxx (BUSINESS)    Toast 友好提示
 *  - 9xxxx (SYSTEM)      Toast「系统繁忙，已上报」+ trace_id
 *
 * 雪花 ID 防精度丢失：JSON.parse 时把所有 ≥ 16 位的纯数字转 string
 */

import axios, {
  type AxiosInstance,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  categoryOf,
  ErrorCode,
  getMessage,
  isLogoutRequired,
} from '@cangchu/error-codes'
import type { ApiResponse } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'

/**
 * 雪花 ID 反序列化：把响应文本中长数字（≥ 16 位）替换为字符串
 * 避免 JSON.parse 把 184237892374823400 截断为 1.842378923748234e17
 */
function safeJsonParse<T>(text: string): T {
  const safe = text.replace(
    /:\s*(-?\d{16,})([,}\]])/g,
    (_m, num, tail) => `:"${num}"${tail}`,
  )
  return JSON.parse(safe) as T
}

/** 业务异常 · 业务层 try/catch 时用 */
export class ApiError extends Error {
  constructor(
    public readonly code: number,
    public readonly errorMessage: string,
    public readonly details?: Record<string, unknown>,
    public readonly traceId?: string,
  ) {
    super(errorMessage)
    this.name = 'ApiError'
  }
}

const http: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
  // 用自定义解析器（防雪花 ID 精度丢失）
  transformResponse: [(data) => (typeof data === 'string' ? safeJsonParse(data) : data)],
  headers: {
    'Content-Type': 'application/json',
    'X-Client': 'admin-web',
  },
})

// ============ 请求拦截器 ============
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const auth = useAuthStore()
  if (auth.token) {
    // 后端 sa-token：token-name=Authorization 且未配置 token-prefix，
    // 因此 Authorization 头需放「裸 token」（不带 Bearer 前缀），否则被判未登录(41001)。
    config.headers.set('Authorization', auth.token)
    config.headers.set('satoken', auth.token) // Sa-Token 兼容（备用读取头）
  }
  return config
})

// ============ 响应拦截器 ============
http.interceptors.response.use(
  (resp: AxiosResponse<ApiResponse>) => {
    const body = resp.data
    if (!body) {
      throw new ApiError(ErrorCode.SYSTEM_INTERNAL, '响应体为空')
    }
    if (body.code === ErrorCode.OK) {
      return resp
    }
    return handleBusinessError(body)
  },
  (err) => {
    // 网络层错误（超时 / DNS / 5xx 直接抛）
    if (axios.isCancel(err)) return Promise.reject(err)

    const resp = err.response
    if (!resp) {
      ElMessage.error('网络异常，请检查网络后重试')
      return Promise.reject(
        new ApiError(ErrorCode.SYSTEM_INTERNAL, '网络异常', { axios: err.message }),
      )
    }

    // 429 限流（可能没有 body）
    if (resp.status === 429) {
      const retryAfter = Number(resp.headers['retry-after'] ?? 0)
      ElMessage.warning(`操作过于频繁，请 ${retryAfter || 30} 秒后再试`)
      return Promise.reject(
        new ApiError(ErrorCode.LIMIT_RATE, '操作过于频繁', { retryAfter }),
      )
    }

    // 5xx 系统级
    if (resp.status >= 500) {
      const traceId = (resp.data as ApiResponse | undefined)?.traceId
      ElMessage.error(
        `系统繁忙，已上报${traceId ? `（trace_id: ${traceId.slice(0, 8)}…）` : ''}`,
      )
      return Promise.reject(
        new ApiError(ErrorCode.SYSTEM_INTERNAL, '系统繁忙', undefined, traceId),
      )
    }

    // 401 / 403 通过 body 路由
    if (resp.data && typeof resp.data === 'object' && 'code' in resp.data) {
      return handleBusinessError(resp.data as ApiResponse)
    }

    ElMessage.error(`请求失败 (${resp.status})`)
    return Promise.reject(err)
  },
)

/** 按错误码分类路由 */
function handleBusinessError(body: ApiResponse): Promise<never> {
  const { code, message, details, traceId } = body
  const category = categoryOf(code)
  const userMsg = getMessage(code, message)

  switch (category) {
    case 'AUTH':
      if (isLogoutRequired(code)) {
        const auth = useAuthStore()
        auth.clear()
        // 避免 message 风暴
        ElMessageBox.alert(userMsg, '请重新登录', {
          confirmButtonText: '去登录',
          showClose: false,
          callback: () => {
            window.location.href = '/login'
          },
        })
      } else {
        // 41101 账号密码错误、41102 锁定、41201 验证码 等 → 由业务页面 catch 处理（字段红边）
        ElMessage.warning(userMsg)
      }
      break

    case 'PERMISSION':
      ElMessage.error(userMsg)
      break

    case 'LIMIT': {
      const retryAfter = (details?.retryAfter as number | undefined) ?? 0
      ElMessage.warning(`${userMsg}${retryAfter ? `（${retryAfter}s 后重试）` : ''}`)
      break
    }

    case 'VALIDATION':
      // 仅 Toast 简短提示；具体字段红边由业务页面 catch 后处理
      ElMessage.warning(userMsg)
      break

    case 'STATE':
      ElMessage.warning(`${userMsg}（请刷新数据）`)
      break

    case 'BUSINESS':
      // 业务异常：尽量友好，details 已含 SKU/数量等信息可由业务层兜底
      ElMessage.warning(userMsg)
      break

    case 'SYSTEM':
      ElMessage.error(
        `${userMsg}${traceId ? `（trace: ${traceId.slice(0, 8)}…）` : ''}`,
      )
      break

    default:
      ElMessage.error(userMsg)
  }

  return Promise.reject(new ApiError(code, userMsg, details, traceId))
}

/** 业务层用的简化调用器（解包 data） */
export async function request<T>(
  config: Parameters<AxiosInstance['request']>[0],
): Promise<T> {
  const resp = await http.request<ApiResponse<T>>(config)
  return resp.data.data as T
}

export default http
