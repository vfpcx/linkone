/**
 * PII 查全号（与 admin apps/admin/src/api/pii.ts 同口径，15-pii-hardening-v2 §4 阶段2-1）。
 *
 * 后端真实端点 = GET /api/v1/pii/phone-reveal?biz=&id=——
 * VO 层默认只回打码号，业务确需完整手机号的场景（WA/WE 联系买家）经此端点取号。
 * 权限 + 归属校验 + 审计日志均在服务端完成；前端不做任何明文缓存。
 */
import { request } from '../utils/request'

/** 查全号 biz 枚举（后端 PiiRevealService 四类权限矩阵；移动端仅用 INQUIRY） */
export type RevealBiz = 'BLACKLIST' | 'TENANT' | 'WA_APPLICATION' | 'INQUIRY'

export interface PhoneRevealResult {
  phone: string
}

export const piiApi = {
  /** 查看完整手机号（服务端已做角色/归属校验 + 审计，调用即留痕） */
  revealPhone(biz: RevealBiz, id: string | number): Promise<PhoneRevealResult> {
    return request<PhoneRevealResult>({
      url: '/pii/phone-reveal',
      method: 'GET',
      params: { biz, id },
    })
  },
}
