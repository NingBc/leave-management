<template>
  <div>
    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>我的假期余额 ({{ currentYear }})</span>
        </div>
      </template>
      <div v-if="account">
        <!-- Desktop Description View -->
        <el-descriptions v-if="!isMobile" :column="2" border>
          <el-descriptions-item label="工龄">{{ account.socialSeniority }} 年</el-descriptions-item>
          <el-descriptions-item label="入职日期">{{ account.entryDate }}</el-descriptions-item>
          <el-descriptions-item label="每年年假额度">{{ account.standardQuota }} 天</el-descriptions-item>
          <el-descriptions-item label="上年结余">{{ account.lastYearBalance }} 天</el-descriptions-item>
          <el-descriptions-item label="当年在职天数">{{ account.daysEmployed }} 天</el-descriptions-item>
          <el-descriptions-item label="当年年假天数">{{ account.actualQuota }} 天</el-descriptions-item>
          <el-descriptions-item label="本年已用">{{ account.currentYearUsed }} 天</el-descriptions-item>
          <el-descriptions-item label="总可用余额">
            <el-tag type="success" size="large">{{ totalAvailable }} 天</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <!-- Mobile Premium Card View -->
        <div v-else class="mobile-balance-container">
          <div class="main-balance-card">
            <div class="label">可休合计 (天)</div>
            <div class="value">{{ totalAvailable }}</div>
            <div class="sub-label">账户状态: 正常活跃</div>
          </div>
          
          <div class="balance-grid">
            <div class="grid-item">
              <span class="grid-label">每年额度</span>
              <span class="grid-value">{{ account.standardQuota }}</span>
            </div>
            <div class="grid-item">
              <span class="grid-label">当年实际</span>
              <span class="grid-value">{{ account.actualQuota }}</span>
            </div>
            <div class="grid-item">
              <span class="grid-label">上年结转</span>
              <span class="grid-value">{{ account.lastYearBalance }}</span>
            </div>
            <div class="grid-item">
              <span class="grid-label">本年已用</span>
              <span class="grid-value">{{ account.currentYearUsed }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="empty-state">
        <el-empty description="本年度暂无账户信息" />
        <div class="action-btn">
          <el-button type="primary" @click="initAccount" round>初始化 2026 账户</el-button>
        </div>
      </div>
    </el-card>

    <div class="history-container">
      <div class="history-header">
        <span class="title">休假历史</span>
        <el-select v-model="selectedHistoryYear" placeholder="选择年份" @change="loadHistory" style="width: 120px" clearable>
          <el-option label="全部年份" :value="null" />
          <el-option
            v-for="year in availableYears"
            :key="year"
            :label="year + ' 年'"
            :value="year"
          />
        </el-select>
      </div>
      
      <el-table :data="history" style="width: 100%" class="custom-table">
      <el-table-column prop="startDate" label="休假日期" min-width="110" />
      <el-table-column v-if="!isMobile" prop="endDate" label="结束日期" min-width="110" />
      <el-table-column prop="days" label="天数" width="70" />
      <el-table-column prop="type" label="类型" width="90">
        <template #default="scope">
          <el-tag :type="getRecordTypeTag(scope.row.type)" size="small" effect="light">{{ formatRecordType(scope.row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" prop="remarks" label="备注" min-width="150" show-overflow-tooltip />
    </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import request from '../../utils/request'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../../stores/user'

const userStore = useUserStore()
const currentYear = new Date().getFullYear()

const account = ref(null)
const history = ref([])
const availableYears = ref([])
const selectedHistoryYear = ref(currentYear)
const screenWidth = ref(window.innerWidth)

const isMobile = computed(() => screenWidth.value < 768)
const descColumn = computed(() => isMobile.value ? 1 : 2)

const totalAvailable = computed(() => {
  if (!account.value) return 0
  // Backend now returns the fully calculated balance in totalBalance field
  return account.value.totalBalance || 0
})

const formatRecordType = (type) => {
  const map = {
    'ANNUAL': '年假',
    'ADJUSTMENT_ADD': '额度增加',
    'ADJUSTMENT_DEDUCT': '额度扣除',
    'CARRY_OVER': '上年结转',
    'EXPIRED': '过期清理'
  }
  return map[type] || type
}

const getRecordTypeTag = (type) => {
  const map = {
    'ANNUAL': '',
    'ADJUSTMENT_ADD': 'warning',
    'ADJUSTMENT_DEDUCT': 'danger',
    'CARRY_OVER': 'success',
    'EXPIRED': 'info'
  }
  return map[type] || ''
}

const loadData = async () => {
  const userId = userStore.userId
  if (!userId) {
    ElMessage.warning('未找到用户信息，请重新登录')
    return
  }
  try {
    const res = await request.get('/leave/account', { params: { userId, year: currentYear } })
    account.value = res
  } catch (e) {
    console.error(e)
  }
}

const loadHistory = async () => {
  const userId = userStore.userId
  if (!userId) return
  try {
    const params = { userId }
    if (selectedHistoryYear.value) {
      params.year = selectedHistoryYear.value
    }
    const hist = await request.get('/leave/history', { params })
    history.value = hist
  } catch (e) {
    console.error(e)
  }
}

const loadAvailableYears = async () => {
  try {
    const years = await request.get('/leave/available-years')
    availableYears.value = years
  } catch (e) {
    console.error(e)
  }
}

const initAccount = async () => {
  const userId = userStore.userId
  if (!userId) return
  try {
    await request.post(`/leave/init?userId=${userId}&year=${currentYear}`)
    ElMessage.success('账户初始化成功')
    loadData()
  } catch (e) {
    console.error(e)
  }
}

const handleResize = () => {
  screenWidth.value = window.innerWidth
}

onMounted(() => {
  loadData()
  loadHistory()
  loadAvailableYears()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.history-container {
  margin-top: 25px;
}

.history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
  padding-left: 5px;
  border-left: 4px solid #409eff;
}

.history-header .title {
  font-size: 16px;
  font-weight: bold;
}

/* Mobile Premium UI Styles */
.mobile-balance-container {
  padding: 10px 0;
}

.main-balance-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 16px;
  padding: 25px 20px;
  color: white;
  text-align: center;
  box-shadow: 0 10px 25px rgba(102, 126, 234, 0.4);
  margin-bottom: 20px;
}

.main-balance-card .label {
  font-size: 14px;
  opacity: 0.9;
  margin-bottom: 8px;
}

.main-balance-card .value {
  font-size: 48px;
  font-weight: 700;
  line-height: 1;
  margin-bottom: 12px;
}

.main-balance-card .sub-label {
  font-size: 12px;
  opacity: 0.7;
}

.balance-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.grid-item {
  background: #f8fafc;
  border-radius: 12px;
  padding: 15px;
  display: flex;
  flex-direction: column;
  border: 1px solid #edf2f7;
}

.grid-label {
  font-size: 12px;
  color: #718096;
  margin-bottom: 4px;
}

.grid-value {
  font-size: 18px;
  font-weight: 600;
  color: #2d3748;
}

.empty-state {
  text-align: center;
  padding: 20px 0;
}

.action-btn {
  margin-top: -20px;
}

.custom-table :deep(.el-table__header-wrapper) th {
  background-color: #f8fafc;
  color: #4a5568;
  font-weight: 600;
}

@media screen and (max-width: 768px) {
  .box-card :deep(.el-card__body) {
    padding: 15px;
  }
  
  .box-card :deep(.el-card__header) {
    padding: 12px 15px;
  }

  .history-header {
    margin-top: 10px;
  }

  /* Table density on mobile */
  .custom-table :deep(.el-table__cell) {
    padding: 8px 0;
  }

  .custom-table :deep(.cell) {
    padding-left: 8px;
    padding-right: 8px;
    font-size: 13px;
  }
}
</style>
