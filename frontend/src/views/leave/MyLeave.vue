<template>
  <div class="my-leave">
    <div class="section-title history-head">
      <span>休假记录</span>
      <el-select
        v-model="selectedHistoryYear"
        placeholder="全部年份"
        clearable
        style="width: 118px"
        @change="loadHistory"
      >
        <el-option label="全部年份" value="" />
        <el-option v-for="year in availableYears" :key="year" :label="`${year} 年`" :value="year" />
      </el-select>
    </div>

    <!-- 没有内容时也占一行高度, 同步时间晚于列表到达时表格不会往下跳 -->
    <p class="sync-note">
      <template v-if="sync.ok">上次同步 {{ sync.timeShort }}</template>
      <template v-else>{{ sync.note }}</template>
    </p>

    <!-- 桌面: 表格 -->
    <el-table v-if="!isMobile" :data="rows" v-loading="loading" class="surface history-table">
      <!-- 单日假不用把同一个日期写两遍, 和手机卡片一个写法 -->
      <el-table-column label="日期" min-width="190">
        <template #default="{ row }"><span class="num">{{ dateRange(row) }}</span></template>
      </el-table-column>
      <el-table-column prop="days" label="天数" width="80">
        <template #default="{ row }">{{ fmtDays(row.days) }}</template>
      </el-table-column>
      <el-table-column prop="type" label="类型" width="110">
        <template #default="{ row }">
          <el-tag :type="recordTypeTag(row.type, row.remarks)" size="small" effect="light">
            {{ formatRecordType(row.type, row.remarks) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remarkText" label="备注" min-width="160" show-overflow-tooltip />
      <template #empty>
        <span class="empty-text">没有记录</span>
      </template>
    </el-table>

    <!-- 移动: 卡片流 -->
    <div v-else v-loading="loading" class="history-list">
      <div v-for="(item, i) in rows" :key="item.id ?? i" class="hist-card surface">
        <div class="hist-top">
          <span class="hist-date num">{{ dateRange(item) }}</span>
          <el-tag :type="recordTypeTag(item.type, item.remarks)" size="small" effect="light">
            {{ formatRecordType(item.type, item.remarks) }}
          </el-tag>
        </div>
        <div class="hist-days num">{{ fmtDays(item.days) }} 天</div>
        <div v-if="item.remarkText" class="hist-remarks">{{ item.remarkText }}</div>
      </div>
      <p v-if="!loading && !history.length" class="empty-text list-empty">没有记录</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../utils/request'
import { useUserStore } from '../../stores/user'
import { useBreakpoint } from '../../composables/useBreakpoint'
import { fmtDays, formatRecordType, recordTypeTag, formatRemarks, parseSyncTime } from '../../constants/leave'

const userStore = useUserStore()
const { isMobile } = useBreakpoint()
const currentYear = new Date().getFullYear()

const history = ref([])
/** 备注是后端拼的模板, 展示前收敛一遍 (见 formatRemarks); 类型标签仍按原始备注判断, 所以原字段不动 */
const rows = computed(() => history.value.map(r => ({ ...r, remarkText: formatRemarks(r.type, r.remarks) })))
const availableYears = ref([])
const selectedHistoryYear = ref(currentYear)
const sync = ref(parseSyncTime(null))
const loading = ref(true)

/** 一天的假不用写成「8-11 ~ 8-11」 */
const dateRange = (item) => {
  if (!item.endDate || item.endDate === item.startDate) return item.startDate
  return `${item.startDate} ~ ${item.endDate}`
}

const loadHistory = async () => {
  const userId = userStore.userId
  if (!userId) return
  try {
    const params = { userId }
    if (selectedHistoryYear.value) params.year = selectedHistoryYear.value
    history.value = await request.get('/leave/history', { params })
  } catch (e) {
    console.error(e)
  }
}

const loadAvailableYears = async () => {
  try {
    const years = (await request.get('/leave/available-years')) || []
    availableYears.value = years.includes(currentYear) ? years : [currentYear, ...years]
  } catch (e) {
    console.error(e)
  }
}

/** 只为拿同步时间; 余额和明细都在首页 */
const loadSyncTime = async () => {
  const userId = userStore.userId
  if (!userId) return
  try {
    const account = await request.get('/leave/account', { params: { userId, year: currentYear } })
    sync.value = parseSyncTime(account?.lastSyncTime)
  } catch (e) {
    console.error(e)
  }
}

onMounted(async () => {
  if (!userStore.userId) {
    ElMessage.warning('登录信息已失效，请重新登录')
    loading.value = false
    return
  }
  await Promise.all([loadHistory(), loadAvailableYears(), loadSyncTime()])
  loading.value = false
})
</script>

<style scoped>
.my-leave {
  max-width: 760px;
  margin: 0 auto;
}

.history-head {
  margin-bottom: 8px;
}

.sync-note {
  min-height: 1.6em;
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}

.history-table {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.empty-text {
  font-size: 13px;
  color: var(--text-muted);
}

.list-empty {
  padding: 32px 0;
  text-align: center;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hist-card {
  padding: 12px 14px;
}

.hist-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.hist-date {
  font-size: 13px;
  color: var(--text-secondary);
}

.hist-days {
  margin-top: 4px;
  font-size: 16px;
  font-weight: 600;
}

.hist-remarks {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
</style>
