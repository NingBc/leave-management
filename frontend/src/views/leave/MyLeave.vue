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
        <el-option label="全部年份" :value="null" />
        <el-option v-for="year in availableYears" :key="year" :label="`${year} 年`" :value="year" />
      </el-select>
    </div>

    <p class="sync-note">
      每周一从钉钉同步<span v-if="sync.ok"> · 上次 {{ sync.full }}</span>
      <span v-else-if="sync.note"> · {{ sync.note }}</span>
    </p>

    <!-- 桌面: 表格 -->
    <el-table v-if="!isMobile" :data="history" v-loading="loading" class="surface history-table">
      <el-table-column prop="startDate" label="开始日期" min-width="110" />
      <el-table-column prop="endDate" label="结束日期" min-width="110" />
      <el-table-column prop="days" label="天数" width="80">
        <template #default="{ row }">{{ fmtDays(row.days) }}</template>
      </el-table-column>
      <el-table-column prop="type" label="类型" width="110">
        <template #default="{ row }">
          <el-tag :type="recordTypeTag(row.type)" size="small" effect="light">
            {{ formatRecordType(row.type) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remarks" label="备注" min-width="160" show-overflow-tooltip />
      <template #empty>
        <span class="empty-text">没有记录</span>
      </template>
    </el-table>

    <!-- 移动: 卡片流 -->
    <div v-else v-loading="loading" class="history-list">
      <div v-for="(item, i) in history" :key="item.id ?? i" class="hist-card surface">
        <div class="hist-top">
          <span class="hist-date num">{{ dateRange(item) }}</span>
          <el-tag :type="recordTypeTag(item.type)" size="small" effect="light">
            {{ formatRecordType(item.type) }}
          </el-tag>
        </div>
        <div class="hist-days num">{{ fmtDays(item.days) }} 天</div>
        <div v-if="item.remarks" class="hist-remarks">{{ item.remarks }}</div>
      </div>
      <p v-if="!loading && !history.length" class="empty-text list-empty">没有记录</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../utils/request'
import { useUserStore } from '../../stores/user'
import { useBreakpoint } from '../../composables/useBreakpoint'
import { fmtDays, formatRecordType, recordTypeTag, parseSyncTime } from '../../constants/leave'

const userStore = useUserStore()
const { isMobile } = useBreakpoint()
const currentYear = new Date().getFullYear()

const history = ref([])
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
    availableYears.value = await request.get('/leave/available-years')
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
