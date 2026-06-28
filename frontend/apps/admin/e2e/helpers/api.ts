/**
 * API 旁路建号 helper（数据隔离）
 *
 * 直接走后端注册接口预置 TA 账号，用于需要"已存在账号"的用例
 * （E2 密码登录 / E6 工作台渲染 / E7 重复注册 / E8 退出登录）。
 *
 * 复刻自 .e2e-tmp/extra.py 的 api_register_ta：
 *  - body: { phone, password, smsCode:888888, role:'TA', realName, tenantName, agreedTerms:true }
 *  - 带 4 次重试，规避 Redisson 偶发掉线（历史 Bug A）
 *  - 成功判定：响应 JSON code === 0
 */

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'

export const SMS_CODE = '888888'
export const DEFAULT_PWD = 'TaPass123'

/** 13 + 9 位时间戳尾数 = 11 位，符合 ^1[3-9]\d{9}$，保证唯一 */
export function uniqPhone(): string {
  return '13' + String(Date.now()).slice(-9)
}

/**
 * 通过后端接口注册一个 TA 账号。成功返回 true。
 * 带重试，规避 Redis 偶发写失败。
 */
export async function apiRegisterTa(
  phone: string,
  pwd: string = DEFAULT_PWD,
): Promise<boolean> {
  const body = JSON.stringify({
    phone,
    password: pwd,
    smsCode: SMS_CODE,
    role: 'TA',
    realName: 'APISeed',
    tenantName: 'Seed' + phone.slice(-4),
    agreedTerms: true,
  })

  for (let i = 0; i < 4; i++) {
    try {
      const res = await fetch(`${API}/api/v1/account/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
      })
      const data = (await res.json()) as { code?: number }
      if (data?.code === 0) return true
    } catch {
      // 忽略并重试
    }
    await new Promise((r) => setTimeout(r, 600))
  }
  return false
}

/**
 * 预置一个 TA 账号并返回其手机号 + 密码；建号失败直接抛错（用例 fail-fast）。
 */
export async function seedTa(pwd: string = DEFAULT_PWD): Promise<{ phone: string; pwd: string }> {
  const phone = uniqPhone()
  const ok = await apiRegisterTa(phone, pwd)
  if (!ok) {
    throw new Error(`API 建号 4 次均失败（疑似后端 Redis 异常） phone=${phone}`)
  }
  return { phone, pwd }
}
