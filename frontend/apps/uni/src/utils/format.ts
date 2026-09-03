/** 金额展示：分位两位小数，null/undefined → '--' */
export function fmtMoney(n: number | null | undefined): string {
  if (n === null || n === undefined) return '--'
  return `¥${n.toFixed(2)}`
}

/** 展示价：去掉无意义尾零（9.90 → ¥9.9），null → '--' */
export function fmtPrice(n: number | null | undefined): string {
  if (n === null || n === undefined) return '--'
  const s = n.toFixed(2)
  return `¥${s.replace(/\.?0+$/, '')}`
}

/** 时间展示：YYYY-MM-DD HH:mm；null/非法 → '--' */
export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '--'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '--'
  const pad = (x: number): string => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
