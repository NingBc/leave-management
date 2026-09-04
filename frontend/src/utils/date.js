/**
 * 日期小工具。
 *
 * 单独抽出来是因为 `new Date('2020-01-01')` 会按 UTC 午夜解析, 而 `Date.now()` 是本地时刻,
 * 东八区直接算差值会少 8 小时, 取整后经常差一天。这里一律先还原成本地日历日再相减。
 */

/** 把 'YYYY-MM-DD' 解析成本地时区的那一天零点 */
const parseLocalDate = (dateStr) => {
  if (!dateStr) return null
  const [y, m, d] = String(dateStr).slice(0, 10).split('-').map(Number)
  if (!y || !m || !d) return null
  return new Date(y, m - 1, d)
}

/** 从某天到今天的整天数; 日期缺失或在未来返回 null */
export const daysSince = (dateStr) => {
  const from = parseLocalDate(dateStr)
  if (!from) return null
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const days = Math.round((today - from) / 86400000)
  return days >= 0 ? days : null
}

/** 把天数说成「6 年 8 个月」这类人话 */
export const humanizeDuration = (days) => {
  if (days == null) return '—'
  const years = Math.floor(days / 365)
  const months = Math.floor((days % 365) / 30)
  if (years > 0) return months > 0 ? `${years} 年 ${months} 个月` : `${years} 年`
  if (months > 0) return `${months} 个月`
  return `${days} 天`
}
