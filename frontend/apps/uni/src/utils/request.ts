/**
 * 统一请求封装（uni-app）。
 *
 * 双身份策略：
 *  - RT 匿名：无 token，走公开端点（/rt/*）；
 *  - WA/WE 登录态：自动注入 Authorization/satoken + X-Tenant-Id（当前工作仓），
 *    41xxx 登出级错误自动清会话并回登录页。
 *
 * 请求头与 admin 对齐：X-Client=uni-mobile；Authorization 裸 token（sa-token 无前缀）。
 */
import { API_BASE } from '../config'
import type { SnowflakeId } from '@cangchu/api-types'
import { clearAuth, readAuth, readWork } from './session'

/** 登出级错误码（与 @cangchu/error-codes 的 AUTH 段一致，命中即清会话） */
const LOGOUT_CODES = new Set(['41001', '41002', '41003', '41004', '41005'])

export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** POST body：透传 uni.request（对象/字符串皆可） */
  data?: UniApp.RequestOptions['data']
  /** GET query；undefined/null/'' 的键自动省略 */
  params?: Record<string, string | number | undefined>
  timeout?: number
}

function buildUrl(url: string, params?: Record<string, string | number | undefined>): string {
  let u = `${API_BASE}${url}`
  if (params) {
    const qs = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
      .join('&')
    if (qs) u += `?${qs}`
  }
  return u
}

function resolveHeader(): Record<string, string> {
  const header: Record<string, string> = { 'X-Client': 'uni-mobile' }
  const auth = readAuth()
  if (!auth) return header
  header.Authorization = auth.token
  header.satoken = auth.token
  const work = readWork()
  if (work && work.tenantId) header['X-Tenant-Id'] = String(work.tenantId as SnowflakeId)
  return header
}

export function request<T>(options: RequestOptions): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: buildUrl(options.url, options.params),
      method: options.method ?? 'GET',
      data: options.data,
      header: resolveHeader(),
      timeout: options.timeout ?? 15000,
      success: (res) => {
        const body = res.data as { code?: number | string; message?: string; data?: T } | null
        const code = String(body?.code ?? '-1')
        if (code === '0' || code === '200') {
          resolve(body?.data as T)
          return
        }
        const msg = body?.message || `请求失败（${code}）`
        if (LOGOUT_CODES.has(code)) {
          clearAuth()
          uni.showToast({ title: '登录已失效，请重新登录', icon: 'none' })
          setTimeout(() => {
            uni.reLaunch({ url: '/pages/wa/login/index' })
          }, 500)
        } else {
          uni.showToast({ title: msg, icon: 'none' })
        }
        reject(new ApiError(code, msg))
      },
      fail: (err) => {
        uni.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
        reject(err)
      },
    })
  })
}
