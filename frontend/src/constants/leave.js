import { daysSince } from '../utils/date'

/**
 * 年假相关的字段文案与枚举。
 *
 * 集中在这里的原因:
 * 1. 改版前 formatRecordType / getRecordTypeTag 在 MyLeave 和 ManageLeave 各写了一份,
 *    两份对 CARRY_OVER 的译法还不一样(「上年结转」vs「年假结转」)。
 * 2. 同一个后端字段在不同页面有三种叫法(socialSeniority = 工龄 / 社会工龄 / 工龄(年)),
 *    用户以为是三个不同的东西。
 *
 * 字段口径以 LeaveServiceImpl#recalcQuotaFields 为准, 改文案前先对齐那里的公式。
 */

/** 休假/调整流水的类型 */
export const RECORD_TYPE = {
  ANNUAL: {
    label: '年假',
    tag: 'primary',
    desc: '正常休掉的年假'
  },
  CARRY_OVER: {
    label: '上年结转',
    tag: 'success',
    desc: '上一年度结转过来的天数'
  },
  ADJUSTMENT_ADD: {
    label: '手工加假',
    tag: 'warning',
    desc: '管理员手动增加的天数'
  },
  ADJUSTMENT_DEDUCT: {
    label: '手工扣假',
    tag: 'danger',
    desc: '管理员手动扣除的天数'
  },
  EXPIRED: {
    label: '过期作废',
    tag: 'info',
    desc: '过期未使用、被系统清零的天数'
  }
}

export const formatRecordType = (type) => RECORD_TYPE[type]?.label ?? type
export const recordTypeTag = (type) => RECORD_TYPE[type]?.tag ?? 'info'
export const recordTypeDesc = (type) => RECORD_TYPE[type]?.desc ?? ''

/** 管理员手工新增流水时可选的类型(系统自动产生的 CARRY_OVER / EXPIRED 不给选) */
export const MANUAL_RECORD_TYPES = [
  { value: 'ANNUAL', label: '年假（补录）' },
  { value: 'ADJUSTMENT_ADD', label: '手工加假' },
  { value: 'ADJUSTMENT_DEDUCT', label: '手工扣假' }
]

/**
 * 字段文案表。
 *
 * short 用于表格列头(空间紧), label 用于详情页, hint 是鼠标悬停/问号里的解释。
 * hint 存在的意义: 「已累积」比「全年应享」少, 是因为年假按在职天数逐日累积,
 * 不写清楚用户就会以为假被扣了 —— 这是改版前最常见的疑问。
 */
export const FIELD = {
  socialSeniority: {
    short: '累计工龄',
    label: '累计工龄',
    unit: '年',
    hint: '含入职本公司之前的工作年限，按「首次参加工作时间」算。决定年假档位：满 1 年 5 天、10 年 10 天、20 年 15 天。'
  },
  standardQuota: {
    short: '全年应享',
    label: '全年应享年假',
    unit: '天',
    hint: '按累计工龄档位，整年在职可享的天数。'
  },
  daysEmployed: {
    short: '今年在职',
    label: '今年在职天数',
    unit: '天',
    hint: '只算本年度：从 1 月 1 日（或入职当天）算到今天。年假按这个天数折算。'
  },
  // 和 daysEmployed 只差两个字, 所以 hint 里必须点明区别 ——
  // 改版前这两个数就是分别叫「在职天数」和「当年在职天数」, 谁也分不清
  totalDaysEmployed: {
    short: '总共在职',
    label: '总共在职天数',
    unit: '天',
    hint: '从入职本公司到今天的总天数，跨年累计。与「今年在职天数」不同，后者只算本年度、用于折算今年的年假。'
  },
  actualQuota: {
    short: '已累积',
    label: '截至今日已累积',
    unit: '天',
    hint: '年假逐日累积，不是年初一次性到账。算法：全年应享 × 今年在职天数 ÷ 全年天数，按 0.5 天向下取整。12 月 31 日累积满。'
  },
  lastYearBalance: {
    short: '上年结转',
    label: '上年结转',
    unit: '天',
    hint: '上一年度没休完、结转到今年的天数。'
  },
  currentYearUsed: {
    short: '今年已休',
    label: '今年已休',
    unit: '天',
    hint: '本年度已休掉的年假，每周一从钉钉审批单同步。最近几天请的假可能还没同步进来，所以余额会偏大。'
  },
  totalBalance: {
    short: '当前可休',
    label: '当前可休',
    unit: '天',
    hint: '此刻还能休的天数 = 上年结转 + 已累积 − 今年已休 ± 手工调整。其中「今年已休」来自钉钉同步，同步之后请的假还没扣减。'
  },
  entryDate: {
    short: '入职日期',
    label: '入职本公司日期',
    hint: '决定今年在职天数。'
  },
  firstWorkDate: {
    short: '首次参加工作',
    label: '首次参加工作时间',
    hint: '第一份工作的入职时间（含在其它公司的经历），决定累计工龄和年假档位。填错会算错年假。'
  }
}

/** 余额构成算式, 直接展示给用户看, 省掉「为什么是这个数」的追问 */
export const balanceFormula = (account) => {
  if (!account) return ''
  const n = (v) => Number(v ?? 0)
  return `上年结转 ${n(account.lastYearBalance)} + 已累积 ${n(account.actualQuota)} − 今年已休 ${n(account.currentYearUsed)}`
}

/** 天数展示: 去掉 3.0 这种多余的小数尾巴, 但保留 3.5 */
export const fmtDays = (v) => {
  const n = Number(v ?? 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(1)
}

/**
 * 解析账户上的 lastSyncTime。
 *
 * 后端 resolveLastSyncTime() 在查不到同步任务或出异常时, 返回的是「暂无同步记录」
 * 「获取失败」这类中文文案而不是时间戳 —— 直接拼进「上次 XXX」会读成
 * 「上次 暂无同步记录」。所以这里先判格式, 由调用方按 ok 分支渲染。
 */
const SYNC_TS = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/

export function parseSyncTime(raw) {
  if (!raw) return { ok: false, date: '', full: '', note: '', daysAgo: null }
  const text = String(raw).trim()
  if (SYNC_TS.test(text)) {
    const date = text.slice(0, 10)
    // 「已同步至 08-31」还得自己数几天前, 直接把天数算出来更有感知
    return { ok: true, date, full: text, note: '', daysAgo: daysSince(date) ?? 0 }
  }
  // 后端给的是说明性文案, 原样透出, 不要套进时间的句式里
  return { ok: false, date: '', full: '', note: text, daysAgo: null }
}
