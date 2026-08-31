import { request } from './http'

/**
 * PII 查全号（PII-W7，15-pii-hardening-v2 §4 阶段2-1）。
 *
 * VO 层默认只回打码号（138****1234），业务确需完整手机号的场景
 * （OPS 审核租户/黑名单、TA 审批申请、WA 联系买家）经此端点取号。
 * 权限 + 归属校验 + 审计日志均在服务端完成；前端不做任何明文缓存。
 */
export interface PhoneRevealResult {
  phone: string
}

export type RevealBiz = 'BLACKLIST' | 'TENANT' | 'WA_APPLICATION' | 'INQUIRY'

export const piiApi = {
  /** 查看完整手机号（服务端已做角色/归属校验 + 审计，调用即留痕） */
  revealPhone: (biz: RevealBiz, id: string | number) =>
    request<PhoneRevealResult>({
      method: 'GET',
      url: '/pii/phone-reveal',
      params: { biz, id },
    }),
}
