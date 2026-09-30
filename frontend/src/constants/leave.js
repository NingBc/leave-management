import { daysSince, daysUntil } from '../utils/date'

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

/**
 * 系统自动写入的透支归位流水。
 *
 * 后端复用 ADJUSTMENT_ADD / ADJUSTMENT_DEDUCT 成对写入 (LeaveServiceImpl#normalizeFloatingDebt,
 * 以及年终结算 ScheduledTasks#cleanupUserForYear), 一加一扣净额为零, 不改变余额。
 * 类型上和管理员手工调整没有区别, 只能靠备注前缀认 —— 按类型直接翻译会显示成
 * 「手工加假 / 手工扣假」, 看账的人会以为有人动过账。
 */
const SETTLEMENT_REMARK = /^(透支归位|系统自动清理透支):/

const SETTLEMENT = {
  label: '透支归位',
  tag: 'info',
  desc: '系统把历史透支挪到有额度的批次上扣，一加一扣成对出现，不改变余额'
}

const resolveRecordType = (type, remarks) =>
  type?.startsWith('ADJUSTMENT_') && SETTLEMENT_REMARK.test(remarks ?? '') ? SETTLEMENT : RECORD_TYPE[type]

export const formatRecordType = (type, remarks) => resolveRecordType(type, remarks)?.label ?? type
export const recordTypeTag = (type, remarks) => resolveRecordType(type, remarks)?.tag ?? 'info'
export const recordTypeDesc = (type, remarks) => resolveRecordType(type, remarks)?.desc ?? ''

/**
 * 备注列的展示文案。
 *
 * 后端给每条扣减流水拼的备注是固定模板 (LeaveServiceImpl#buildRemarks / upsertCarryOverRecord):
 *   员工请假 (来自当年额度, 过期: 2027-12-31)
 *   员工请假 (额度透支)
 *   上年结余年假结转 (过期: 2026-12-31)
 * 「员工请假」和类型标签「年假」重复, 「过期: …」对已经休掉的假没有用, 桌面表格里还会被省略号
 * 截在最有用的「来自…额度」之前; 结转那条整句都是类型标签「上年结转」的复述。
 *
 * 只收敛展示, 库里的原文不动, 「透支归位」等按备注前缀识别类型的逻辑照旧读原文。
 * 匹配不上的 (管理员手填的原因、旧格式) 原样返回; 编辑框绑的是原文, 不要经过这里。
 */
const BOILERPLATE_NOTE = new Set(['员工请假', '额度扣除'])
const SOURCE_REMARK = /^(.*?) ?\(来自(结转|当年)额度, 过期: \d{4}-\d{2}-\d{2}\)$/
const OVERDRAFT_REMARK = /^(?:员工请假|额度扣除) \(额度透支\)$/
const CARRY_REMARK = /^上年结余年假结转 \(过期: \d{4}-\d{2}-\d{2}\)$/

export const formatRemarks = (type, remarks) => {
  const text = remarks ?? ''
  if (type === 'CARRY_OVER' && CARRY_REMARK.test(text)) return ''
  if (OVERDRAFT_REMARK.test(text)) return '额度透支'
  const m = SOURCE_REMARK.exec(text)
  if (!m) return text
  const source = `来自${m[2]}额度`
  // 手填的原因(如「补录: 3 月病假转年假」)是有用信息, 留着, 只去掉过期日
  return !m[1] || BOILERPLATE_NOTE.has(m[1]) ? source : `${m[1]} (${source})`
}

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
    hint: '自首次参加工作起算。档位：不满 10 年 5 天、满 10 年 10 天、满 20 年 15 天。'
  },
  standardQuota: {
    short: '全年应享',
    label: '全年应享年假',
    unit: '天',
    hint: '按累计工龄档位，整年在职可享的天数。当年入职的按在职天数折算，年底实际能累积到的更少。'
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
    hint: '入职本公司至今的总天数，跨年累计。「今年在职天数」只算本年度，用来折算年假。'
  },
  actualQuota: {
    short: '已累积',
    label: '截至今日已累积',
    unit: '天',
    hint: '逐日累积，非年初一次性到账：全年应享 × 今年在职天数 ÷ 全年天数，按 0.5 天向下取整。'
  },
  lastYearBalance: {
    short: '上年结转',
    label: '上年结转',
    unit: '天',
    hint: '上一年度没休完、结转到今年的天数，今年 12 月 31 日作废。请假时先扣它，快到期的先用。'
  },
  currentYearUsed: {
    short: '今年已休',
    label: '今年已休',
    unit: '天',
    hint: '本年度已休的年假（含用掉的上年结转），每周一从钉钉同步。'
  },
  totalBalance: {
    short: '当前可休',
    label: '当前可休',
    unit: '天',
    hint: '此刻还能休的天数 = 上年结转 + 已累积 − 今年已休 ± 手工调整。'
  },
  // 不带 hint: 「决定今年在职天数」这一句配不上一个问号
  entryDate: {
    short: '入职日期',
    label: '入职本公司日期'
  },
  // 「填错会算错年假」是写给录入的 HR 看的, 只在用户表单里提示, 员工只读的页面不带这句
  firstWorkDate: {
    short: '首次参加工作',
    label: '首次参加工作时间',
    hint: '第一份工作的入职时间（含其它公司的经历），决定累计工龄和年假档位。'
  }
}

/** 上年结转离作废不到这么多天、并且还有剩余时, 标橙色提醒 */
export const CARRY_OVER_WARN_DAYS = 90

/**
 * 把余额按来源拆成页面要画的样子: 上年结转、今年额度、透支三部分, 三部分的剩余相加就是「当前可休」。
 *
 * 数字全部来自后端的桶账本拆分 (LeaveServiceImpl#fillBalanceBreakdown), 这里只推导展示状态,
 * 不拿流水自己加减 —— 透支归位、欠账冲抵、手工调整都会让自己算的数和余额对不上。
 *
 * carry.kind:
 *   none     没有上年结转
 *   debt     上年结转为负, 带着欠账进入今年, 已从今年额度里扣
 *   expired  已过作废日, 没用完的已作废
 *   usedUp   已用完
 *   active   还有剩余
 */
export const balanceSources = (account) => {
  const n = (v) => Number(v ?? 0)

  const lastYear = n(account?.lastYearBalance)
  const daysLeft = daysUntil(account?.carryOverExpiry)
  const pastExpiry = daysLeft != null && daysLeft < 0
  let remaining = n(account?.carryOverRemaining)
  // 年终清理还没跑 (比如 1 月 1 日凌晨之前) 时, 过期的剩余还挂在桶里, 页面上一样算作废
  const expired = n(account?.carryOverExpired) + (pastExpiry ? remaining : 0)
  if (pastExpiry) remaining = 0
  const total = Math.max(lastYear, remaining + expired)

  let kind = 'active'
  if (lastYear < 0) kind = 'debt'
  else if (total === 0) kind = 'none'
  else if (expired > 0) kind = 'expired'
  else if (remaining === 0) kind = 'usedUp'

  const carry = {
    kind,
    total,
    remaining,
    expired,
    used: Math.max(0, total - remaining - expired),
    debt: kind === 'debt' ? -lastYear : 0,
    expiry: account?.carryOverExpiry,
    daysLeft,
    warn: kind === 'active' && daysLeft != null && daysLeft <= CARRY_OVER_WARN_DAYS
  }

  // 今年额度: 已用 = 已累积 − 剩余。手工加假会让剩余超过已累积, 此时已用记 0, 不画负数
  const accrued = n(account?.actualQuota)
  // 年底能累积到多少, 由后端按年终结算的口径预告。不能用 standardQuota: 那是整年在职的档位额度,
  // 年中入职的人到年底也满不了它 (7/1 入职的 5 天档, 年底是 2.5 不是 5)。
  // 后端还没带这个字段时退回 standardQuota, 至少和以前一样, 不至于显示成 0
  const full = n(account?.yearEndQuota ?? account?.standardQuota)
  const curRemaining = n(account?.currentQuotaRemaining)
  const curUsed = Math.max(0, accrued - curRemaining)
  const current = {
    accrued,
    full,
    remaining: curRemaining,
    used: curUsed,
    // 进度条的满格: 年底能累积到的数, 年底前「还没累积到」的部分留作空槽
    scale: Math.max(full, accrued, curUsed + curRemaining)
  }

  return { carry, current, debt: n(account?.floatingDebt) }
}

/** 表格/卡片里「上年结转」一格的文案: 主数字、后缀、色调、悬停说明 */
export const carryOverCell = (carry) => {
  const f = fmtDays
  switch (carry.kind) {
    case 'none':
      return { main: '0', rest: '', tone: 'muted', title: '没有上年结转' }
    case 'debt':
      return { main: f(-carry.debt), rest: '', tone: 'danger', title: `上年欠 ${f(carry.debt)} 天，已从今年额度中扣除` }
    case 'expired':
      return { main: '0', rest: ` / ${f(carry.total)}`, tone: 'muted',
        title: `已用 ${f(carry.used)} 天，作废 ${f(carry.expired)} 天` }
    case 'usedUp':
      return { main: '0', rest: ` / ${f(carry.total)}`, tone: 'muted', title: `${f(carry.total)} 天已全部用完` }
    default:
      return {
        main: f(carry.remaining),
        rest: ` / ${f(carry.total)}`,
        tone: carry.warn ? 'warn' : '',
        title: `已用 ${f(carry.used)} 天，还剩 ${f(carry.remaining)} 天，${carry.expiry} 作废（还有 ${carry.daysLeft} 天）`
      }
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
  const none = { ok: false, date: '', dateShort: '', full: '', timeShort: '', note: '', daysAgo: null }
  if (!raw) return none
  const text = String(raw).trim()
  if (SYNC_TS.test(text)) {
    const date = text.slice(0, 10)
    // dateShort / timeShort 去掉年份和秒: 页面上是一行小字, 「2026-09-28 02:00:00」多出来的部分没人看,
    // 跨年时「（N 天前）」也足够消歧。
    // 「已同步至 08-31」还得自己数几天前, 直接把天数算出来更有感知
    return {
      ok: true, date, dateShort: text.slice(5, 10), full: text, timeShort: text.slice(5, 16),
      note: '', daysAgo: daysSince(date) ?? 0
    }
  }
  // 后端给的是说明性文案, 原样透出, 不要套进时间的句式里
  return { ...none, note: text }
}
