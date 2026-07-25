/**
 * 附件上传接口封装（P3 FE-W1 · 异议/客诉附件共用基建）
 *
 * 权威来源：backend/.../common/file/FileController.java + FileStorageServiceImpl
 *  - POST /api/v1/files（登录用户，multipart 单文件字段名 file）→ { url }
 *  - 限制：≤5MB，jpg/png/webp（后端魔数校验，扩展名不可信）→ 违规 50340
 *  - 返回 url 形如 /files/xxx.png；GET /files/** 为静态映射免登录（SaTokenConfig 放行）
 *
 * Content-Type 说明：http.ts 实例默认头为 application/json，实测会覆盖 axios 的
 * FormData 自动检测（E2E 抓包 REQ-CT=application/json → 后端 500）。因此本请求
 * 显式置 null 摘除该头，交由浏览器补 multipart boundary。
 */

import { request } from './http'
import type { FileUploadResponse } from '@cangchu/api-types'

/** 前端预检常量（与后端一致，仅做提前拦截省一次往返；后端魔数校验仍是权威） */
export const FILE_MAX_SIZE = 5 * 1024 * 1024
export const FILE_ACCEPT_MIMES = ['image/jpeg', 'image/png', 'image/webp']

export const fileApi = {
  /** 上传图片附件 → { url } */
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<FileUploadResponse>({
      method: 'POST',
      url: '/files',
      data: form,
      // null = 摘除实例默认 application/json，浏览器自动带 multipart boundary
      headers: { 'Content-Type': null as unknown as string },
    })
  },
}
