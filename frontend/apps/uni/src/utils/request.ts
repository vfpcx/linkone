/**
 * 统一请求封装（uni-app）。
 *
 * 身份策略：
 *  - RT 匿名：无 token，走公开端点（/rt/*）；
 *  - WA/WE/ST/WK 登录态：自动注入 Authorization/satoken + X-Tenant-Id（当前工作仓）；
 *  - F6 RT 登录态：token 注入同上，但无工作仓（无 X-Tenant-Id）；41xxx 登出级错误退回买家首页。
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
          // F6：RT 登录态失效退回买家首页（无仓角色），否则回工作台登录页
          const auth = readAuth()
          const wasRt = !!auth && auth.roles.some((r) => r.role === 'RT' && !r.tenantId)
          clearAuth()
          uni.showToast({ title: '登录已失效，请重新登录', icon: 'none' })
          setTimeout(() => {
            uni.reLaunch({ url: wasRt ? '/pages/index/index' : '/pages/wa/login/index' })
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

/**
 * 上传图片附件（F7-1 · 复用后端 POST /api/v1/files，multipart 单文件字段 file）→ 返回 /files/... 相对 URL。
 * 与 admin 的 fileApi 同契：≤5MB、jpg/png/webp（后端魔数权威 50340）；返回 url 供展示/挂单据（N2 白名单）。
 */
interface UploadResp {
  code?: number | string
  message?: string
  data?: { url?: string }
}

export function uploadImage(filePath: string): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    uni.uploadFile({
      url: `${API_BASE}/files`,
      filePath,
      name: 'file',
      header: resolveHeader(),
      timeout: 20000,
      success: (res) => {
        let body: UploadResp | null = null
        if (typeof res.data === 'string' && res.data) {
          try {
            body = JSON.parse(res.data) as UploadResp
          } catch {
            body = null
          }
        } else {
          body = res.data as UploadResp
        }
        const code = String(body?.code ?? '-1')
        if (code === '0' || code === '200') {
          const url = body?.data?.url
          if (url) {
            resolve(url)
            return
          }
        }
        const msg = body?.message || `上传失败（${code}）`
        uni.showToast({ title: msg, icon: 'none' })
        reject(new ApiError(code, msg))
      },
      fail: () => {
        uni.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
        reject(new Error('upload failed'))
      },
    })
  })
}
