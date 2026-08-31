/**
 * 手机号脱敏（对齐后端 SmsUtil.maskPhone：138****1234）。
 *
 * PII-W7（15-pii-hardening-v2 §4 阶段2-1）：VO 层统一只回打码号，
 * 前端所有展示口径与此工具保持一致；需全号一律走 piiApi.revealPhone（权限+审计在服务端）。
 * 非 11 位 / 空值原样返回（如 last4 尾号搜索词不经打码）。
 */
export function maskPhone(phone: string | null | undefined): string {
  if (!phone) return ''
  const p = String(phone).trim()
  if (p.length < 7) return p
  return `${p.slice(0, 3)}****${p.slice(7)}`
}
