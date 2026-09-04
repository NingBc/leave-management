<template>
  <div class="manage">
    <div class="toolbar">
      <div class="year-picker">
        <span class="year-label">年度</span>
        <el-select v-model="selectedYear" style="width: 120px" @change="onYearChange">
          <el-option v-for="year in yearOptions" :key="year" :label="`${year} 年`" :value="year" />
        </el-select>
      </div>
      <span class="count num">共 {{ total }} 名员工</span>
    </div>

    <!-- ===== 桌面: 表格 ===== -->
    <el-table v-if="!isMobile" :data="accounts" v-loading="loading" class="surface account-table">
      <el-table-column prop="employeeNumber" label="工号" min-width="104" />
      <el-table-column prop="realName" label="姓名" min-width="104" />

      <!-- 单位放列头, 单元格只留数字并右对齐: 一列数字右边缘对齐才好上下比对 -->
      <el-table-column
        v-for="col in numericColumns"
        :key="col.key"
        :prop="col.key"
        :min-width="col.colWidth"
        align="right"
        header-align="right"
      >
        <template #header>
          <span class="th">
            {{ col.short }}<i class="th-unit">{{ col.unit.trim() }}</i>
            <FieldHint :label="col.label" :text="col.hint" />
          </span>
        </template>
        <template #default="{ row }">
          <span class="num">{{ col.raw ? (row[col.key] ?? 0) : fmtDays(row[col.key]) }}</span>
        </template>
      </el-table-column>

      <el-table-column min-width="112" align="right" header-align="right">
        <template #header>
          <span class="th">
            {{ FIELD.totalBalance.short }}<i class="th-unit">天</i>
            <FieldHint :label="FIELD.totalBalance.label" :text="FIELD.totalBalance.hint" />
          </span>
        </template>
        <template #default="{ row }">
          <strong class="num balance-cell">{{ fmtDays(row.totalBalance) }}</strong>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRecords(row)">
            记录 ({{ row.records?.length || 0 }})
          </el-button>
          <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
        </template>
      </el-table-column>

      <template #empty>
        <span class="empty-text">{{ selectedYear }} 年没有年假账户</span>
      </template>
    </el-table>

    <!-- ===== 移动: 卡片流 ===== -->
    <div v-else v-loading="loading" class="card-list">
      <article v-for="row in accounts" :key="row.userId" class="acct-card surface">
        <header class="acct-head">
          <span class="acct-name">{{ row.realName }}</span>
          <span class="acct-no num">工号 {{ row.employeeNumber || '—' }}</span>
        </header>

        <div class="acct-balance">
          <span class="acct-balance-label">{{ FIELD.totalBalance.short }}</span>
          <span class="acct-balance-value num">{{ fmtDays(row.totalBalance) }} 天</span>
        </div>

        <dl class="acct-grid">
          <div v-for="col in numericColumns" :key="col.key">
            <dt>{{ col.short }}</dt>
            <dd class="num">{{ col.raw ? (row[col.key] ?? 0) : fmtDays(row[col.key]) }}{{ col.unit }}</dd>
          </div>
        </dl>

        <footer class="acct-foot">
          <el-button size="small" @click="openRecords(row)">
            记录 ({{ row.records?.length || 0 }})
          </el-button>
          <el-button size="small" type="primary" @click="handleEdit(row)">编辑</el-button>
        </footer>
      </article>

      <p v-if="!loading && !accounts.length" class="empty-text list-empty">
        {{ selectedYear }} 年没有年假账户
      </p>
    </div>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      :layout="isMobile ? 'prev, pager, next' : 'total, sizes, prev, pager, next, jumper'"
      :pager-count="isMobile ? 5 : 7"
      class="pager"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
    />

    <!-- ===== 只读: 查看记录 ===== -->
    <el-dialog
      v-model="recordsVisible"
      :title="`${viewing?.realName || ''} 的休假记录`"
      :width="isMobile ? '92%' : '760px'"
    >
      <el-table v-if="!isMobile" :data="viewing?.records || []" size="small" border>
        <el-table-column prop="startDate" label="开始日期" width="120" />
        <el-table-column prop="endDate" label="结束日期" width="120" />
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
        <el-table-column prop="remarks" label="备注" min-width="150" show-overflow-tooltip />
        <template #empty><span class="empty-text">暂无记录</span></template>
      </el-table>

      <div v-else class="rec-list">
        <div v-for="(r, i) in viewing?.records || []" :key="r.id ?? i" class="rec-card">
          <div class="rec-top">
            <span class="num">{{ dateRange(r) }}</span>
            <el-tag :type="recordTypeTag(r.type)" size="small" effect="light">
              {{ formatRecordType(r.type) }}
            </el-tag>
          </div>
          <div class="rec-days num">{{ fmtDays(r.days) }} 天</div>
          <div v-if="r.remarks" class="rec-remarks">{{ r.remarks }}</div>
        </div>
        <p v-if="!viewing?.records?.length" class="empty-text">暂无记录</p>
      </div>
    </el-dialog>

    <!-- ===== 编辑 ===== -->
    <el-dialog
      v-model="editDialogVisible"
      :title="`编辑 ${form.realName || ''} 的 ${selectedYear} 年账户`"
      :width="isMobile ? '94%' : '860px'"
      :close-on-click-modal="false"
      top="6vh"
    >
      <!-- 只读字段用纯文本展示: 改版前是一排 disabled 的数字输入框,
           看着像能填, 管理员反复试都改不动 -->
      <section class="readonly">
        <div class="readonly-head">
          <el-icon><Lock /></el-icon>
          <span>以下由系统按档案自动计算，不可修改</span>
        </div>
        <dl class="readonly-grid">
          <div v-for="col in readonlyFields" :key="col.key">
            <dt>
              {{ col.short }}
              <FieldHint :label="col.label" :text="col.hint" />
            </dt>
            <dd class="num">{{ col.raw ? (form[col.key] ?? 0) : fmtDays(form[col.key]) }}{{ col.unit }}</dd>
          </div>
        </dl>
        <p class="readonly-note">调整余额请在下方添加记录，改动才不被重算覆盖</p>
      </section>

      <el-form :model="form" :label-position="isMobile ? 'top' : 'right'" label-width="110px">
        <el-form-item>
          <template #label>
            {{ FIELD.lastYearBalance.label }}
            <FieldHint :label="FIELD.lastYearBalance.label" :text="FIELD.lastYearBalance.hint" />
          </template>
          <el-input-number v-model="form.lastYearBalance" :precision="1" :step="0.5" />
          <span class="field-note">自动结转，可手工更正</span>
        </el-form-item>
      </el-form>

      <div class="records-head">
        <h4>休假记录</h4>
        <el-button type="primary" size="small" plain @click="addRecord">
          <el-icon><Plus /></el-icon>添加记录
        </el-button>
      </div>

      <!-- 桌面: 可编辑表格 -->
      <el-table v-if="!isMobile" :data="form.records" size="small" border>
        <el-table-column label="开始日期" width="150">
          <template #default="{ row }">
            <el-date-picker
              v-model="row.startDate" type="date" size="small"
              placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%"
            />
          </template>
        </el-table-column>
        <el-table-column label="结束日期" width="150">
          <template #default="{ row }">
            <el-date-picker
              v-model="row.endDate" type="date" size="small"
              placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%"
            />
          </template>
        </el-table-column>
        <el-table-column label="天数" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.days" :step="0.5" :precision="1" size="small" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="180">
          <template #default="{ row }">
            <el-select v-if="row.id === null" v-model="row.type" size="small" style="width: 100%">
              <el-option
                v-for="opt in MANUAL_RECORD_TYPES"
                :key="opt.value" :label="opt.label" :value="opt.value"
              />
            </el-select>
            <el-tag v-else :type="recordTypeTag(row.type)" size="small" effect="light">
              {{ formatRecordType(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.remarks" size="small" placeholder="调整原因" clearable />
          </template>
        </el-table-column>
        <template #empty><span class="empty-text">还没有记录</span></template>
      </el-table>

      <!-- 移动: 卡片表单 -->
      <div v-else class="edit-rec-list">
        <div v-for="(row, i) in form.records" :key="row.id ?? `new-${i}`" class="edit-rec surface">
          <div class="edit-rec-head">
            <el-select v-if="row.id === null" v-model="row.type" size="small" style="width: 160px">
              <el-option
                v-for="opt in MANUAL_RECORD_TYPES"
                :key="opt.value" :label="opt.label" :value="opt.value"
              />
            </el-select>
            <el-tag v-else :type="recordTypeTag(row.type)" size="small" effect="light">
              {{ formatRecordType(row.type) }}
            </el-tag>
          </div>
          <div class="edit-rec-field">
            <label>开始日期</label>
            <el-date-picker v-model="row.startDate" type="date" size="small" value-format="YYYY-MM-DD" style="width: 100%" />
          </div>
          <div class="edit-rec-field">
            <label>结束日期</label>
            <el-date-picker v-model="row.endDate" type="date" size="small" value-format="YYYY-MM-DD" style="width: 100%" />
          </div>
          <div class="edit-rec-field">
            <label>天数</label>
            <el-input-number v-model="row.days" :step="0.5" :precision="1" size="small" style="width: 100%" />
          </div>
          <div class="edit-rec-field">
            <label>备注</label>
            <el-input v-model="row.remarks" size="small" placeholder="调整原因" clearable />
          </div>
        </div>
        <p v-if="!form.records?.length" class="empty-text">还没有记录</p>
      </div>

      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Lock, Plus } from '@element-plus/icons-vue'
import {
  getAllAccounts, updateAccount,
  addRecord as addLeaveRecordApi, updateRecord as updateLeaveRecordApi
} from '../../api/leave'
import request from '../../utils/request'
import { useBreakpoint } from '../../composables/useBreakpoint'
import {
  FIELD, MANUAL_RECORD_TYPES, fmtDays, formatRecordType, recordTypeTag
} from '../../constants/leave'
import FieldHint from '../../components/FieldHint.vue'

const { isMobile } = useBreakpoint()
const currentYear = new Date().getFullYear()

const accounts = ref([])
const loading = ref(false)
const saving = ref(false)
const selectedYear = ref(currentYear)
const yearOptions = ref([])

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

/** 表格数值列 / 编辑弹窗只读区共用一份定义, 保证两处口径和叫法一致 */
const numericColumns = [
  { key: 'socialSeniority', ...FIELD.socialSeniority, raw: true, unit: ' 年', colWidth: 106 },
  { key: 'standardQuota', ...FIELD.standardQuota, unit: ' 天', colWidth: 106 },
  { key: 'daysEmployed', ...FIELD.daysEmployed, raw: true, unit: ' 天', colWidth: 106 },
  { key: 'actualQuota', ...FIELD.actualQuota, unit: ' 天', colWidth: 100 },
  { key: 'lastYearBalance', ...FIELD.lastYearBalance, unit: ' 天', colWidth: 106 },
  { key: 'currentYearUsed', ...FIELD.currentYearUsed, unit: ' 天', colWidth: 106 }
]

/** 编辑弹窗里的只读项: 上年结转可改, 所以不在其中 */
const readonlyFields = numericColumns.filter(c => c.key !== 'lastYearBalance')

const dateRange = (r) => {
  if (!r.endDate || r.endDate === r.startDate) return r.startDate
  return `${r.startDate} ~ ${r.endDate}`
}

/* ---------- 列表 ---------- */

const loadAvailableYears = async () => {
  try {
    const res = await request.get('/leave/available-years')
    yearOptions.value = res || []
    if (!yearOptions.value.includes(currentYear)) {
      yearOptions.value.unshift(currentYear)
    }
  } catch (e) {
    console.error('Failed to load available years', e)
    yearOptions.value = [currentYear, currentYear - 1, currentYear - 2]
  }
}

const loadAccounts = async () => {
  try {
    loading.value = true
    const res = await getAllAccounts(selectedYear.value, currentPage.value, pageSize.value)
    accounts.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    console.error(e)
    ElMessage.error('数据加载失败')
  } finally {
    loading.value = false
  }
}

const onYearChange = () => {
  currentPage.value = 1
  loadAccounts()
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

/* ---------- 查看记录 ---------- */

const recordsVisible = ref(false)
const viewing = ref(null)

const openRecords = (row) => {
  viewing.value = row
  recordsVisible.value = true
}

/* ---------- 编辑 ---------- */

const editDialogVisible = ref(false)
const form = ref({ records: [] })

// 打开弹窗时的记录快照, 用于判断哪些已有记录被真正改过
const originalRecords = ref({})

const isRecordDirty = (record) => {
  const before = originalRecords.value[record.id]
  if (!before) return false
  return ['startDate', 'endDate', 'days', 'remarks', 'type']
    .some(key => String(before[key] ?? '') !== String(record[key] ?? ''))
}

const handleEdit = (account) => {
  const records = (account.records || []).map(r => ({ ...r }))
  originalRecords.value = Object.fromEntries(records.map(r => [r.id, { ...r }]))
  form.value = { ...account, records }
  editDialogVisible.value = true
}

const addRecord = () => {
  if (!form.value.records) form.value.records = []
  form.value.records.unshift({
    id: null,
    startDate: '',
    endDate: '',
    days: 1.0,
    type: 'ANNUAL',
    remarks: ''
  })
}

const handleSave = async () => {
  try {
    saving.value = true

    // 1. 账户信息(目前仅「上年结转」可手工修正)
    await updateAccount(form.value)

    const records = form.value.records || []

    // 2. 新增记录
    for (const record of records.filter(r => !r.id)) {
      record.userId = form.value.userId
      await addLeaveRecordApi(record)
    }

    // 3. 已有记录的改动
    //    此前这里被整段跳过, 管理员改完点保存会提示成功但改动被丢弃。
    for (const record of records.filter(r => r.id && isRecordDirty(r))) {
      await updateLeaveRecordApi(record)
    }

    ElMessage.success('保存成功')
    editDialogVisible.value = false
    loadAccounts()
  } catch (e) {
    console.error(e)
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadAvailableYears()
  loadAccounts()
})
</script>

<style scoped>
.year-picker {
  display: flex;
  align-items: center;
  gap: 8px;
}

.year-label {
  font-size: 13px;
  color: var(--text-secondary);
}

.count {
  font-size: 13px;
  color: var(--text-muted);
}

.account-table {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.th {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* 单位只在列头出现一次。小一号 + 弱化字重, 免得像两个并列的词, 也不至于把列头撑折行 */
.th-unit {
  margin-left: 3px;
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
  color: var(--text-annotation);
}

.balance-cell {
  color: var(--brand);
  font-size: 14px;
}

.empty-text {
  font-size: 13px;
  color: var(--text-muted);
}

.list-empty {
  padding: 32px 0;
  text-align: center;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

/* ---- 移动端账户卡 ---- */

.card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.acct-card {
  padding: 14px;
}

.acct-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.acct-name {
  font-size: 15px;
  font-weight: 600;
}

.acct-no {
  font-size: 12px;
  color: var(--text-muted);
}

.acct-balance {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 10px 0;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--brand-subtle);
}

.acct-balance-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.acct-balance-value {
  font-size: 18px;
  font-weight: 600;
  color: var(--brand);
}

.acct-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px 8px;
  margin: 0;
}

.acct-grid dt {
  font-size: 11px;
  color: var(--text-muted);
}

.acct-grid dd {
  margin: 2px 0 0;
  font-size: 14px;
  font-weight: 500;
}

.acct-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

/* ---- 编辑弹窗只读区 ---- */

.readonly {
  padding: 14px;
  margin-bottom: 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-sunken);
}

.readonly-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
}

.readonly-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(96px, 1fr));
  gap: 12px;
  margin: 12px 0 0;
}

.readonly-grid dt {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.readonly-grid dd {
  margin: 2px 0 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.readonly-note {
  margin: 14px 0 0;
  padding-top: 10px;
  border-top: 1px dashed var(--border-strong);
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-muted);
}

.readonly-note strong {
  color: var(--text-secondary);
}

.field-note {
  margin-left: 10px;
  font-size: 12px;
  color: var(--text-muted);
}

.records-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  padding-top: 4px;
}

.records-head h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

/* ---- 记录卡片(只读 / 编辑) ---- */

.rec-list,
.edit-rec-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rec-card {
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.rec-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 13px;
  color: var(--text-secondary);
}

.rec-days {
  margin-top: 4px;
  font-size: 15px;
  font-weight: 600;
}

.rec-remarks {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.edit-rec {
  padding: 12px;
}

.edit-rec-head {
  margin-bottom: 10px;
}

.edit-rec-field {
  margin-bottom: 8px;
}

.edit-rec-field label {
  display: block;
  margin-bottom: 3px;
  font-size: 12px;
  color: var(--text-muted);
}

@media screen and (max-width: 767px) {
  .toolbar {
    margin-bottom: 12px;
  }

  .pager {
    justify-content: center;
  }

  .field-note {
    display: block;
    margin: 4px 0 0;
  }
}
</style>
