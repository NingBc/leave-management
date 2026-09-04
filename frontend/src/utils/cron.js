/**
 * Cron 表达式 <-> 人话。
 *
 * 任务列表里直接摆一串 `0 0 3 ? * MON` 没人看得懂,
 * 列表和 CronGenerator 共用这里的翻译, 免得两处说法对不上。
 */

const WEEK_NAMES = {
  MON: '周一', TUE: '周二', WED: '周三', THU: '周四',
  FRI: '周五', SAT: '周六', SUN: '周日',
  1: '周日', 2: '周一', 3: '周二', 4: '周三', 5: '周四', 6: '周五', 7: '周六'
}

/** 把 Quartz 的 6/7 段表达式翻成「每周一 03:00 执行」这类说法, 认不出就原样返回 */
export const describeCron = (cron) => {
  if (!cron) return '未设置'

  const parts = cron.trim().split(/\s+/)
  if (parts.length < 6) return cron

  const [, min, hour, dayOfMonth, month, dayOfWeek] = parts
  if ([min, hour].some(v => /[*/,-]/.test(v))) return cron

  const time = `${String(hour).padStart(2, '0')}:${String(min).padStart(2, '0')}`

  if (dayOfMonth === '*' && month === '*' && (dayOfWeek === '?' || dayOfWeek === '*')) {
    return `每天 ${time}`
  }
  if (dayOfMonth === '?' && month === '*' && dayOfWeek !== '*' && dayOfWeek !== '?') {
    const name = WEEK_NAMES[dayOfWeek] || dayOfWeek
    return `每${name} ${time}`
  }
  if (dayOfMonth !== '*' && dayOfMonth !== '?' && month === '*' && dayOfWeek === '?') {
    return `每月 ${dayOfMonth} 号 ${time}`
  }
  return cron
}
