<template>
  <div class="home">
    <div class="greeting">
      <h1>{{ greetingText }}，{{ displayName }}</h1>
      <p>{{ todayText }}</p>
    </div>

    <template v-if="hasAccount">
      <!-- 员工进来最想知道的就一件事: 还剩几天假 -->
      <section class="balance surface">
        <div class="balance-head">
          <div>
            <div class="balance-label">
              {{ FIELD.totalBalance.label }}
              <FieldHint :label="FIELD.totalBalance.label" :text="FIELD.totalBalance.hint" />
            </div>
            <div class="balance-value num">
              {{ fmtDays(account.totalBalance) }}<span class="unit">天</span>
            </div>

            <!-- 紧贴数字, 不能塞到卡片最底下: 员工看到「还能休 3 天」就去请假了,
                 而这周已经请掉的假还没同步进来, 等于按偏大的数字做决定。 -->
            <p class="cutoff">
              <el-icon><Clock /></el-icon>
              <span v-if="sync.ok">
                已同步至 <b>{{ sync.dateShort }}</b><template v-if="sync.daysAgo > 0">（{{ sync.daysAgo }} 天前）</template>，之后请的假未扣减
              </span>
              <span v-else>休假记录尚未从钉钉同步，余额可能偏大</span>
            </p>
          </div>
          <el-button v-if="canViewMyLeave" text type="primary" @click="router.push('/leave/my')">
            休假记录<el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>

        <!-- 余额按来源拆开, 各行的「剩」相加就是上面的大数字。
             原来的「上年结转 + 已累积 − 今年已休」算式说得清总数, 说不清结转用了多少、哪天作废。
             只写一行: 「请假先扣它」这条规则在下面「上年结转」的问号里, 这里不再重复 -->
        <el-alert
          v-if="src.carry.warn"
          class="expiry-alert"
          type="warning"
          :closable="false"
          show-icon
        >
          <!-- 「（还剩 N 天）」不换行: 手机上折成两行时, 整段落到第二行, 别从括号中间拆开 -->
          <template #title>
            <template v-if="src.carry.daysLeft === 0">{{ fmtDays(src.carry.remaining) }} 天上年结转今天作废</template>
            <template v-else>
              {{ fmtDays(src.carry.remaining) }} 天上年结转将在 {{ formatMonthDay(src.carry.expiry) }}作废<span class="nowrap">（还剩 {{ src.carry.daysLeft }} 天）</span>
            </template>
          </template>
        </el-alert>

        <div class="sources">
          <div v-if="src.carry.kind !== 'none'" class="src">
            <div class="src-head">
              <span class="src-label">
                {{ FIELD.lastYearBalance.short }}
                <FieldHint :label="FIELD.lastYearBalance.label" :text="FIELD.lastYearBalance.hint" />
              </span>
              <span class="src-value num" :class="`is-${src.carry.kind}`">{{ carryText.value }}</span>
            </div>
            <div v-if="src.carry.kind !== 'debt'" class="src-bar" aria-hidden="true">
              <span class="seg seg-used" :style="segWidth(src.carry.used, src.carry.total)" />
              <span class="seg seg-left" :class="{ warn: src.carry.warn }" :style="segWidth(src.carry.remaining, src.carry.total)" />
            </div>
            <div class="src-meta">
              <span class="num">{{ carryText.left }}</span>
              <span class="num" :class="{ warn: src.carry.warn }">{{ carryText.right }}</span>
            </div>
          </div>

          <div class="src">
            <div class="src-head">
              <span class="src-label">
                今年额度
                <FieldHint :label="FIELD.actualQuota.label" :text="FIELD.actualQuota.hint" />
              </span>
              <span class="src-value num">剩 {{ fmtDays(src.current.remaining) }} 天</span>
            </div>
            <!-- 满格是年底能累积到的数: 年底前还没累积到的部分留作空槽, 顶替了原来那条「年底满 N 天」的提示。
                 文字里的数就是条上的位置: 灰段末端 = 已用, 蓝段末端 = 已累积, 满格 = 年底满。
                 只写后两个的话, 灰段有多长没有出处 —— 员工得自己拿「已累积 − 剩」去减 -->
            <div class="src-bar" aria-hidden="true">
              <span class="seg seg-used" :style="segWidth(src.current.used, src.current.scale)" />
              <span class="seg seg-left" :style="segWidth(src.current.remaining, src.current.scale)" />
            </div>
            <div class="src-meta">
              <span class="num">{{ currentMeta }}</span>
              <span class="num">{{ currentYear + 1 }}-12-31 到期</span>
            </div>
          </div>

          <div v-if="src.debt < 0" class="src">
            <div class="src-head">
              <span class="src-label">透支</span>
              <span class="src-value num is-debt">{{ fmtDays(src.debt) }} 天</span>
            </div>
            <div class="src-meta"><span>额度不够抵扣的部分，之后累积的额度会先抵扣</span></div>
          </div>
        </div>

        <p class="src-foot">
          <span class="num">{{ FIELD.currentYearUsed.short }} {{ fmtDays(account.currentYearUsed) }} 天</span>
          <FieldHint :label="FIELD.currentYearUsed.label" :text="FIELD.currentYearUsed.hint" />
        </p>
      </section>

      <h3 class="section-title">{{ currentYear }} 年明细</h3>
      <section class="detail surface">
        <div v-for="row in detailRows" :key="row.key" class="row">
          <span class="row-label">
            {{ row.label }}
            <FieldHint v-if="row.hint" :label="row.label" :text="row.hint" />
          </span>
          <span class="row-value num">{{ row.value }}</span>
        </div>
      </section>
    </template>

    <section v-else-if="!loading" class="surface empty-account">
      <el-icon :size="20"><InfoFilled /></el-icon>
      <div class="empty-body">
        <div class="empty-title">还没有 {{ currentYear }} 年年假账户</div>
        <div class="empty-desc">按入职日期和累计工龄自动算出年假</div>
        <el-button type="primary" :loading="creating" @click="createAccount">
          建立 {{ currentYear }} 年账户
        </el-button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, InfoFilled, Clock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { useUserStore } from '../stores/user'
import { FIELD, fmtDays, parseSyncTime, balanceSources } from '../constants/leave'
import { daysInclusive, humanizeDuration, formatMonthDay } from '../utils/date'
import FieldHint from '../components/FieldHint.vue'

const router = useRouter()
const userStore = useUserStore()

const currentYear = new Date().getFullYear()
const userInfo = ref({})
const account = ref(null)
const userMenus = ref([])
const loading = ref(true)
const creating = ref(false)

const sync = computed(() => parseSyncTime(account.value?.lastSyncTime))

/**
 * 账户是否真的存在。后端 getAccount 对不存在的账户不返回 null, 而是 id 为空、各项为 0 的空对象,
 * 只判断 account 真假的话「建立账户」这一支永远进不去: 新年度账户生成之前
 * (1 月 1 日凌晨任务跑完前, 或那天任务没跑成), 员工看到的是一张全 0 的余额卡, 没有任何说明。
 */
const hasAccount = computed(() => account.value?.id != null)

/* ---------- 余额来源 ---------- */

const src = computed(() => balanceSources(account.value))

/** 上年结转一行的三段文案: 右上角的值, 进度条下的左右两侧 */
const carryText = computed(() => {
  const c = src.value.carry
  const usedOfTotal = `已用 ${fmtDays(c.used)} · 共 ${fmtDays(c.total)}`
  switch (c.kind) {
    case 'debt':
      return { value: `欠 ${fmtDays(c.debt)} 天`, left: '已从今年额度中扣除', right: '' }
    case 'expired':
      return { value: `作废 ${fmtDays(c.expired)} 天`, left: usedOfTotal, right: `${c.expiry} 已作废` }
    case 'usedUp':
      return { value: '已用完', left: usedOfTotal, right: '' }
    default:
      return { value: `剩 ${fmtDays(c.remaining)} 天`, left: usedOfTotal, right: `${c.expiry} 作废` }
  }
})

/**
 * 今年额度一行的左侧文案。「年底满」只在之后还会继续累积时才写:
 * 12/31 当天它和「已累积」是同一个数, 12 月才入职的人年底也累积不到半天, 写成「年底满 0」只会让人困惑。
 */
const currentMeta = computed(() => {
  const c = src.value.current
  const parts = [`已用 ${fmtDays(c.used)}`, `已累积 ${fmtDays(c.accrued)}`]
  if (c.full > c.accrued) parts.push(`年底满 ${fmtDays(c.full)}`)
  return parts.join(' · ')
})

const segWidth = (value, scale) => ({ width: scale > 0 ? `${(value / scale) * 100}%` : '0%' })

const displayName = computed(() => userInfo.value?.realName || userStore.username || '同事')

const greetingText = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const todayText = computed(() => {
  const d = new Date()
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日 ${week}`
})

/**
 * 明细只放「背景字段」, 不重复余额卡里的算式三项
 * (上年结转 / 已累积 / 今年已休 就在上方的构成里, 同屏再列一遍没有意义)。
 * 这四项正好解释了「已累积」是怎么算出来的: 工龄定档位, 档位按在职天数折算。
 */
const detailRows = computed(() => {
  const a = account.value
  if (!a) return []
  const totalDays = daysInclusive(a.entryDate)
  return [
    // 前三项讲「我是谁」, 后两项讲「今年的假怎么算出来的」
    { key: 'seniority', label: FIELD.socialSeniority.label, hint: FIELD.socialSeniority.hint, value: `${a.socialSeniority ?? 0} 年` },
    { key: 'entry', label: FIELD.entryDate.label, value: a.entryDate || '—' },
    { key: 'totalDays', label: FIELD.totalDaysEmployed.label, hint: FIELD.totalDaysEmployed.hint,
      value: totalDays == null ? '—' : `${totalDays} 天 (${humanizeDuration(totalDays)})` },
    { key: 'standard', label: FIELD.standardQuota.label, hint: FIELD.standardQuota.hint, value: `${fmtDays(a.standardQuota)} 天` },
    { key: 'employed', label: FIELD.daysEmployed.label, hint: FIELD.daysEmployed.hint, value: `${a.daysEmployed ?? 0} 天` }
  ]
})

const canViewMyLeave = computed(() => {
  const walk = (list) => (list || []).some(m =>
    m.path === '/leave/my' || (m.children?.length && walk(m.children))
  )
  return walk(userMenus.value)
})

/* ---------- 数据 ---------- */

const loadUserInfo = async () => {
  try {
    const userId = userStore.userId
    if (!userId) return
    userInfo.value = await request.get(`/system/user/${userId}`)
  } catch (e) {
    console.error('Failed to load user info:', e)
  }
}

const loadAccount = async () => {
  try {
    const userId = userStore.userId
    if (!userId) return
    account.value = await request.get('/leave/account', {
      params: { userId, year: currentYear }
    })
  } catch (e) {
    console.error('Failed to load leave account:', e)
  }
}

const loadUserMenus = async () => {
  if (userStore.userMenus?.length) {
    userMenus.value = userStore.userMenus
    return
  }
  try {
    const userId = userStore.userId
    if (!userId) return
    const menus = await request.get('/system/menu/user-menus', { params: { userId } })
    userStore.setUserMenus(menus)
    userMenus.value = menus
  } catch (e) {
    console.error('Failed to load user menus:', e)
  }
}

/** 新员工自助建号: 后端只允许普通员工建「当年且不存在」的账户 */
const createAccount = async () => {
  const userId = userStore.userId
  if (!userId) return
  try {
    creating.value = true
    await request.post(`/leave/init?userId=${userId}&year=${currentYear}`)
    ElMessage.success('账户已建立')
    await loadAccount()
  } catch (e) {
    console.error(e)
  } finally {
    creating.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadUserInfo(), loadAccount(), loadUserMenus()])
  loading.value = false
})
</script>

<style scoped>
.home {
  max-width: 760px;
  margin: 0 auto;
}

.greeting {
  margin-bottom: 20px;
}

.greeting h1 {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.greeting p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}

/* ---- 余额 ---- */

.balance {
  margin-bottom: 28px;
  padding: 20px;
}

.balance-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.balance-label {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  color: var(--text-muted);
}

.balance-value {
  margin-top: 2px;
  font-size: 40px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}

.unit {
  margin-left: 4px;
  font-size: 15px;
  font-weight: 500;
  color: var(--text-muted);
}

/* ---- 余额来源 ---- */

.expiry-alert {
  margin-top: 16px;
}

.nowrap {
  white-space: nowrap;
}

.sources {
  margin-top: 16px;
  border-top: 1px solid var(--border);
}

.src {
  padding: 12px 0;
}

.src + .src {
  border-top: 1px solid var(--border);
}

.src-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.src-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--text-secondary);
}

.src-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.src-value.is-usedUp,
.src-value.is-expired {
  color: var(--text-muted);
}

.src-value.is-debt {
  color: var(--danger);
}

/* 空槽是底色, 灰段是已用, 主色段是还剩的 —— 快作废时换成警示色 */
.src-bar {
  display: flex;
  height: 6px;
  margin: 8px 0 6px;
  border-radius: var(--radius-pill);
  background: var(--bg-sunken);
  overflow: hidden;
}

.seg {
  height: 100%;
  transition: width var(--ease);
}

.seg-used {
  background: var(--text-placeholder);
}

.seg-left {
  background: var(--brand);
}

.seg-left.warn {
  background: var(--warning);
}

/* 窄屏放不下时让日期整体折到下一行, 别把「已用 … · 已累积 … · 年底满 …」和日期都挤成两截 */
.src-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 2px 12px;
  font-size: 12px;
  color: var(--text-muted);
}

.src-meta .warn {
  color: var(--warning);
  font-weight: 500;
}

.src-foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin: 4px 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-muted);
}

.cutoff {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--text-annotation);
}

/* 日期是这句话里唯一需要记住的信息 */
.cutoff b {
  font-weight: 600;
  color: var(--text-secondary);
}

/* ---- 明细 ---- */

.detail {
  overflow: hidden;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  font-size: 14px;
}

.row:last-child {
  border-bottom: none;
}

.row-label {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--text-secondary);
}

.row-value {
  font-weight: 500;
}

/* ---- 空账户 ---- */

.empty-account {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 20px;
  color: var(--text-muted);
}

.empty-body {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.empty-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.empty-desc {
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 8px;
}

@media screen and (max-width: 767px) {
  .greeting h1 {
    font-size: 19px;
  }

  .balance {
    padding: 18px 16px;
  }

  .balance-value {
    font-size: 36px;
  }

  .src-meta {
    font-size: 11px;
  }
}
</style>
