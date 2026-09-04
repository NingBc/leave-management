<template>
  <div class="home">
    <div class="greeting">
      <h1>{{ greetingText }}，{{ displayName }}</h1>
      <p>{{ todayText }}</p>
    </div>

    <template v-if="account">
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
                已同步至 <b>{{ sync.date }}</b><template v-if="sync.daysAgo > 0">（{{ sync.daysAgo }} 天前）</template>，之后请的假尚未扣减
              </span>
              <span v-else>休假记录尚未从钉钉同步，余额可能偏大</span>
            </p>
          </div>
          <el-button v-if="canViewMyLeave" text type="primary" @click="router.push('/leave/my')">
            休假记录<el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>

        <div class="breakdown">
          <div class="bd-item">
            <span class="bd-label">
              {{ FIELD.lastYearBalance.short }}
              <FieldHint :label="FIELD.lastYearBalance.label" :text="FIELD.lastYearBalance.hint" />
            </span>
            <span class="bd-value num">{{ fmtDays(account.lastYearBalance) }}</span>
          </div>
          <div class="bd-op">+</div>
          <div class="bd-item">
            <span class="bd-label">
              {{ FIELD.actualQuota.short }}
              <FieldHint :label="FIELD.actualQuota.label" :text="FIELD.actualQuota.hint" />
            </span>
            <span class="bd-value num">{{ fmtDays(account.actualQuota) }}</span>
          </div>
          <div class="bd-op">−</div>
          <div class="bd-item">
            <span class="bd-label">
              {{ FIELD.currentYearUsed.short }}
              <FieldHint :label="FIELD.currentYearUsed.label" :text="FIELD.currentYearUsed.hint" />
            </span>
            <span class="bd-value num">{{ fmtDays(account.currentYearUsed) }}</span>
          </div>
        </div>
      </section>

      <!-- 「已累积」比「全年应享」少不是被扣了假, 这条提示就是为了消除这个误会 -->
      <div class="accrual-tip">
        <el-icon><InfoFilled /></el-icon>
        <p>年假逐日累积，年底满 <b class="num">{{ fmtDays(account.standardQuota) }}</b> 天</p>
      </div>

      <h3 class="section-title">{{ currentYear }} 年明细</h3>
      <section class="detail surface">
        <div v-for="row in detailRows" :key="row.key" class="row">
          <span class="row-label">
            {{ row.label }}
            <FieldHint :label="row.label" :text="row.hint" />
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
import { FIELD, fmtDays, parseSyncTime } from '../constants/leave'
import { daysSince, humanizeDuration } from '../utils/date'
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

const displayName = computed(() => userInfo.value.realName || userStore.username || '同事')

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
  const totalDays = daysSince(a.entryDate)
  return [
    // 前三项讲「我是谁」, 后两项讲「今年的假怎么算出来的」
    { key: 'seniority', label: FIELD.socialSeniority.label, hint: FIELD.socialSeniority.hint, value: `${a.socialSeniority ?? 0} 年` },
    { key: 'entry', label: FIELD.entryDate.label, hint: FIELD.entryDate.hint, value: a.entryDate || '—' },
    { key: 'totalDays', label: FIELD.totalDaysEmployed.label, hint: FIELD.totalDaysEmployed.hint,
      value: totalDays == null ? '—' : `${totalDays} 天（${humanizeDuration(totalDays)}）` },
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

.breakdown {
  display: flex;
  gap: 10px;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}

.bd-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.bd-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
}

.bd-value {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-primary);
}

/* 对齐到数值那一行, 不然运算符会浮在数字左上角, 看着像正负号 */
.bd-op {
  display: flex;
  align-items: flex-end;
  padding-bottom: 2px;
  flex-shrink: 0;
  font-size: 14px;
  color: var(--text-placeholder);
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

/* ---- 累积说明 ---- */

.accrual-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0 28px;
  padding: 10px 14px;
  border-radius: var(--radius);
  background: var(--brand-subtle);
  color: var(--brand);
}

.accrual-tip p {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.accrual-tip b {
  color: var(--brand);
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

  .bd-label {
    font-size: 11px;
  }

  .bd-value {
    font-size: 16px;
  }
}
</style>
