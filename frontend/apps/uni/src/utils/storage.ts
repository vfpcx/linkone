/** 买家手机号（本地身份：询价提交 / 我的价目 / 意向单复用） */
const KEY_PHONE = 'cc_rt_phone'
/** 最近访问店铺（最多 3 条） */
const KEY_RECENT_STORES = 'cc_rt_recent_stores'

export interface RecentStore {
  code: string
  name: string
  at: number
}

export function getSavedPhone(): string {
  return (uni.getStorageSync(KEY_PHONE) as string) || ''
}

export function savePhone(phone: string): void {
  uni.setStorageSync(KEY_PHONE, phone.trim())
}

export function getRecentStores(): RecentStore[] {
  try {
    const raw = uni.getStorageSync(KEY_RECENT_STORES) as string
    return raw ? (JSON.parse(raw) as RecentStore[]) : []
  } catch {
    return []
  }
}

/** 进店成功（store 页 onLoad 拉到店铺信息）后记录，供下次一键直达 */
export function addRecentStore(code: string, name: string): void {
  const list = [{ code, name, at: Date.now() }, ...getRecentStores().filter((s) => s.code !== code)].slice(0, 3)
  uni.setStorageSync(KEY_RECENT_STORES, JSON.stringify(list))
}
