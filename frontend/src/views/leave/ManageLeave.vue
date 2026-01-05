<template>
  <div>
    <h3>年假管理 ({{ selectedYear }})</h3>
    <div class="toolbar" style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px;">
      <div style="display: flex; align-items: center;">
        <span style="margin-right: 10px;">查看年份：</span>
        <el-select v-model="selectedYear" @change="loadAccounts" style="width: 120px">
          <el-option v-for="year in yearOptions" :key="year" :label="year + '年'" :value="year" />
        </el-select>
      </div>

    </div>

    <el-table :data="accounts" style="width: 100%" border stripe>
      <el-table-column prop="employeeNumber" label="工号" width="100" />
      <el-table-column prop="realName" label="姓名" width="120" />
      <el-table-column prop="socialSeniority" label="工龄(年)" width="120" />
      <el-table-column prop="standardQuota" label="标准额度" width="100" />
      <el-table-column prop="daysEmployed" label="在职天数" width="120" />
      <el-table-column prop="actualQuota" label="实际额度" width="100" />
      <el-table-column prop="lastYearBalance" label="上年结余" width="100" />
      <el-table-column prop="currentYearUsed" label="今年已用" width="100" />
      <el-table-column prop="totalBalance" label="年假余额" width="100">
        <template #default="scope">
          <el-tag type="success">{{ scope.row.totalBalance }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="使用记录" min-width="150">
        <template #default="scope">
          <el-popover placement="right" :width="800" trigger="click">
          <template #reference>
            <el-button type="primary" link>查看详情</el-button>
          </template>
          <el-table :data="scope.row.records" border size="small">
            <el-table-column prop="startDate" label="开始日期" width="120" />
            <el-table-column prop="endDate" label="结束日期" width="120" />
            <el-table-column prop="days" label="天数" width="80" />
            <el-table-column prop="remarks" label="备注" min-width="150" />
            <el-table-column prop="type" label="类型" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.type === 'ANNUAL'" type="success">年假</el-tag>
                <el-tag v-else-if="row.type === 'ADJUSTMENT_ADD'" type="warning">额度增加</el-tag>
                <el-tag v-else-if="row.type === 'ADJUSTMENT_DEDUCT'" type="danger">额度扣除</el-tag>
                <el-tag v-else-if="row.type === 'CARRY_OVER'" type="info">年假结转</el-tag>
                <el-tag v-else-if="row.type === 'EXPIRED'" type="danger">过期清理</el-tag>
                <el-tag v-else>{{ row.type }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-popover>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="scope">
          <el-button type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
      style="margin-top: 20px; justify-content: flex-end"
    />

    <!-- Edit Dialog -->
    <el-dialog v-model="editDialogVisible" title="编辑年假账户" width="900px">
      <el-alert
        title="基础数据由系统自动计算"
        type="info"
        description="工龄、额度等数据根据员工档案自动生成，不可直接修改。如需调整余额（例如奖励或惩罚），请在下方添加'额度增加'或'额度扣除'记录。"
        show-icon
        :closable="false"
        style="margin-bottom: 20px"
      />
      <el-form :model="form" label-width="140px">
        <el-form-item label="员工">
          <el-input v-model="form.realName" disabled />
        </el-form-item>
        <el-form-item label="工龄(年)">
          <el-input-number v-model="form.socialSeniority" disabled />
        </el-form-item>
        <el-form-item label="标准额度">
          <el-input-number v-model="form.standardQuota" :precision="1" :step="0.5" disabled />
        </el-form-item>
        <el-form-item label="在职天数">
          <el-input-number v-model="form.daysEmployed" disabled />
        </el-form-item>
        <el-form-item label="实际额度">
          <el-input-number v-model="form.actualQuota" :precision="1" :step="0.5" disabled />
        </el-form-item>
        <el-form-item label="上年结余">
          <el-input-number v-model="form.lastYearBalance" :precision="1" :step="0.5" />
          <span style="margin-left: 10px; color: #999; font-size: 12px">（可手动修正）</span>
        </el-form-item>
        <el-form-item label="今年已用">
          <el-input-number v-model="form.currentYearUsed" :precision="1" :step="0.5" disabled />
          <span style="margin-left: 10px; color: #999; font-size: 12px">（由休假记录自动计算）</span>
        </el-form-item>
        
        <el-divider>休假记录管理</el-divider>
        <el-button type="primary" size="small" @click="addRecord" style="margin-bottom: 10px">添加记录</el-button>
        <el-table :data="form.records" border size="small" style="width: 100%">
            <el-table-column prop="startDate" label="开始日期" width="140">
              <template #default="scope">
                <el-date-picker v-model="scope.row.startDate" type="date" placeholder="选择日期" size="small" value-format="YYYY-MM-DD" style="width: 100%"/>
              </template>
            </el-table-column>
            <el-table-column prop="endDate" label="结束日期" width="140">
              <template #default="scope">
                <el-date-picker v-model="scope.row.endDate" type="date" placeholder="选择日期" size="small" value-format="YYYY-MM-DD" style="width: 100%"/>
              </template>
            </el-table-column>
            <el-table-column prop="days" label="天数" width="110">
              <template #default="scope">
                <el-input-number v-model="scope.row.days" :step="0.5" :precision="1" size="small" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column prop="remarks" label="备注" min-width="150">
              <template #default="scope">
                <el-input v-model="scope.row.remarks" placeholder="请输入备注" size="small" clearable />
              </template>
            </el-table-column>
            <el-table-column prop="type" label="类型" width="150">
              <template #default="scope">
                <el-select v-if="scope.row.id === null" v-model="scope.row.type" placeholder="请选择" size="small" style="width: 100%">
                  <el-option label="年假 (补录)" value="ANNUAL" />
                  <el-option label="额度增加 (奖励/恢复)" value="ADJUSTMENT_ADD" />
                  <el-option label="额度扣除 (惩罚/冲销)" value="ADJUSTMENT_DEDUCT" />
                </el-select>
                <el-tag v-else :type="getRecordTypeTag(scope.row.type)" size="small">
                  {{ formatRecordType(scope.row.type) }}
                </el-tag>
              </template>
            </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="editDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleSave">保存</el-button>
        </span>
      </template>
    </el-dialog>


  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAllAccounts, updateAccount, addRecord as addLeaveRecordApi } from '../../api/leave'
import request from '../../utils/request'


const currentYear = new Date().getFullYear()
const accounts = ref([])
const editDialogVisible = ref(false)
const form = ref({
  userId: null,
  year: currentYear,
  realName: '',
  socialSeniority: 0,
  standardQuota: 0,
  daysEmployed: 0,
  actualQuota: 0,
  lastYearBalance: 0,
  currentYearUsed: 0,
  totalBalance: 0,
  records: []
})

// Year selection
const selectedYear = ref(new Date().getFullYear())
const yearOptions = ref([])

// Pagination
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

// Load available years from backend
const loadAvailableYears = async () => {
  try {
    const res = await request.get('/leave/available-years')
    yearOptions.value = res || []
    
    // Always include current year even if no data yet
    const currentYear = new Date().getFullYear()
    if (!yearOptions.value.includes(currentYear)) {
      yearOptions.value.unshift(currentYear)
    }
  } catch (e) {
    console.error('Failed to load available years', e)
    // Fallback: use last 3 years
    const current = new Date().getFullYear()
    yearOptions.value = [current, current - 1, current - 2]
  }
}



const loadAccounts = async () => {
  try {
    const res = await getAllAccounts(selectedYear.value, currentPage.value, pageSize.value)
    accounts.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    console.error(e)
    ElMessage.error('数据加载失败')
  }
}

const handleSizeChange = (newSize) => {
  pageSize.value = newSize
  currentPage.value = 1
  loadAccounts()
}

const handleCurrentChange = (newPage) => {
  currentPage.value = newPage
  loadAccounts()
}



const formatRecordType = (type) => {
  const map = {
    'ANNUAL': '年假',
    'ADJUSTMENT_ADD': '额度增加',
    'ADJUSTMENT_DEDUCT': '额度扣除',
    'CARRY_OVER': '年假结转',
    'EXPIRED': '过期清理'
  }
  return map[type] || type
}

const getRecordTypeTag = (type) => {
  const map = {
    'ANNUAL': 'primary',
    'ADJUSTMENT_ADD': 'warning',
    'ADJUSTMENT_DEDUCT': 'danger',
    'EXPIRED': 'info',
    'CARRY_OVER': 'success'
  }
  return map[type] || 'info'
}

const handleEdit = (account) => {
  form.value = {
    ...account,
    records: account.records || []
  }
  editDialogVisible.value = true
}

const newRecord = () => ({
  id: null,
  startDate: '',
  endDate: '',
  days: 1.0, // Keep original default days
  type: 'ANNUAL', // Default to ANNUAL
  remarks: ''
})

const addRecord = () => {
  if (!form.value.records) form.value.records = []
  form.value.records.push(newRecord())
}



const handleSave = async () => {
  try {
    // 1. Update account details
    await updateAccount(form.value)

    // 2. Add new records
    if (form.value.records && form.value.records.length > 0) {
      const newRecords = form.value.records.filter(r => !r.id)
      for (const record of newRecords) {
        // Ensure userId is set
        record.userId = form.value.userId
        await addLeaveRecordApi(record)
      }
    }

    ElMessage.success('保存成功')
    editDialogVisible.value = false
    loadAccounts()
  } catch (e) {
    console.error(e)
    ElMessage.error('保存失败')
  }
}




onMounted(() => {
  loadAvailableYears()
  loadAccounts()
})
</script>

<style scoped>
.toolbar {
  margin-bottom: 20px;
}
</style>
