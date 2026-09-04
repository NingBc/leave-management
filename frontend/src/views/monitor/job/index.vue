<template>
  <div class="job-page">
    <div class="toolbar">
      <span class="hint-text">后台自动任务</span>
      <el-button type="primary" @click="handleAdd">
        <el-icon><Plus /></el-icon>添加任务
      </el-button>
    </div>

    <!-- ===== 桌面: 表格 ===== -->
    <el-table v-if="!isMobile" :data="jobList" v-loading="loading" class="surface job-table">
      <el-table-column prop="jobName" label="任务名称" min-width="150" fixed />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="isRunning(row) ? 'success' : 'info'" size="small" effect="light">
            {{ isRunning(row) ? '已启用' : '已暂停' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="执行计划" min-width="150">
        <template #default="{ row }">
          <span>{{ describeCron(row.cronExpression) }}</span>
          <div v-if="showRawCron(row.cronExpression)" class="cron-raw num">{{ row.cronExpression }}</div>
        </template>
      </el-table-column>
      <el-table-column min-width="220">
        <template #header>
          <span class="th">
            执行内容
            <FieldHint label="执行内容" text="任务调用的后端方法，格式 Bean名称.方法名()，写错任务到点会执行失败。" />
          </span>
        </template>
        <template #default="{ row }">
          <code class="invoke">{{ row.invokeTarget }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="说明" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="{ 'text-muted': !row.remark }">{{ row.remark || '未填写' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :loading="!!runningIds[row.id]" @click="handleRun(row.id)">
            立即执行
          </el-button>
          <el-button link type="primary" @click="handleToggleStatus(row)">
            {{ isRunning(row) ? '暂停' : '启用' }}
          </el-button>
          <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty><span class="text-muted">没有任务</span></template>
    </el-table>

    <!-- ===== 移动: 卡片流 ===== -->
    <div v-else v-loading="loading" class="card-list">
      <article v-for="row in jobList" :key="row.id" class="job-card surface">
        <header class="jc-head">
          <span class="jc-name">{{ row.jobName }}</span>
          <el-tag :type="isRunning(row) ? 'success' : 'info'" size="small" effect="light">
            {{ isRunning(row) ? '已启用' : '已暂停' }}
          </el-tag>
        </header>
        <div class="jc-schedule">{{ describeCron(row.cronExpression) }}</div>
        <code class="invoke">{{ row.invokeTarget }}</code>
        <p v-if="row.remark" class="jc-remark">{{ row.remark }}</p>
        <footer class="jc-foot">
          <el-button size="small" :loading="!!runningIds[row.id]" @click="handleRun(row.id)">
            立即执行
          </el-button>
          <el-button size="small" @click="handleToggleStatus(row)">
            {{ isRunning(row) ? '暂停' : '启用' }}
          </el-button>
          <el-button size="small" @click="handleEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="handleDelete(row)">删除</el-button>
        </footer>
      </article>
      <p v-if="!loading && !jobList.length" class="text-muted list-empty">没有任务</p>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      :width="isMobile ? '94%' : '620px'"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :label-position="isMobile ? 'top' : 'right'"
        label-width="100px"
      >
        <el-form-item label="任务名称" prop="jobName">
          <el-input v-model="form.jobName" placeholder="如 同步钉钉休假" />
        </el-form-item>
        <el-form-item prop="invokeTarget">
          <template #label>
            执行内容
            <FieldHint label="执行内容" text="任务调用的后端方法，格式 Bean名称.方法名()，写错任务到点会执行失败。" />
          </template>
          <el-input v-model="form.invokeTarget" placeholder="如 dingTalkService.syncLeaveData()" />
        </el-form-item>
        <el-form-item label="执行计划">
          <CronGenerator v-model="form.cronExpression" />
        </el-form-item>
        <el-form-item>
          <template #label>
            任务组
            <FieldHint label="任务组" text="任务分类标签，同组内任务名不能重复。一般保持 DEFAULT。" />
          </template>
          <el-input v-model="form.jobGroup" placeholder="DEFAULT" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listJobs, addJob, updateJob, deleteJob, runJob, changeStatus } from '../../../api/monitor/job'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import CronGenerator from '../../../components/CronGenerator.vue'
import FieldHint from '../../../components/FieldHint.vue'
import { useBreakpoint } from '../../../composables/useBreakpoint'
import { describeCron } from '../../../utils/cron'

const { isMobile } = useBreakpoint()

const jobList = ref([])
const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('添加任务')
const editMode = ref('add')
const runningIds = ref({})
const formRef = ref(null)

const emptyForm = () => ({
  id: null,
  jobName: '',
  jobGroup: 'DEFAULT',
  invokeTarget: '',
  cronExpression: '0 0 10 * * ?',
  remark: ''
})

const form = ref(emptyForm())

const rules = {
  jobName: [{ required: true, message: '请填写任务名称', trigger: 'blur' }],
  invokeTarget: [{ required: true, message: '请填写执行内容', trigger: 'blur' }]
}

/** describeCron 认不出的表达式会原样返回, 此时别把同一串再显示一遍 */
const showRawCron = (cron) => describeCron(cron) !== cron

/** 后端用 0 表示启用, 1 表示暂停 */
const isRunning = (row) => row.status === 0

const loadJobs = async () => {
  try {
    loading.value = true
    jobList.value = await listJobs()
  } catch (e) {
    console.error(e)
    ElMessage.error('加载任务列表失败')
  } finally {
    loading.value = false
  }
}

const handleAdd = () => {
  editMode.value = 'add'
  dialogTitle.value = '添加任务'
  form.value = emptyForm()
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

const handleEdit = (row) => {
  editMode.value = 'edit'
  dialogTitle.value = '编辑任务'
  form.value = { ...row }
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      submitting.value = true
      if (editMode.value === 'add') {
        await addJob(form.value)
        ElMessage.success('任务已创建')
      } else {
        await updateJob(form.value)
        ElMessage.success('任务已更新')
      }
      dialogVisible.value = false
      loadJobs()
    } catch (e) {
      console.error(e)
      ElMessage.error('操作失败')
    } finally {
      submitting.value = false
    }
  })
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `删除后「${row.jobName}」不再自动执行，且无法恢复。`,
      '删除任务',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteJob(row.id)
    ElMessage.success('任务已删除')
    loadJobs()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
      ElMessage.error('删除失败')
    }
  }
}

const handleRun = async (id) => {
  try {
    runningIds.value[id] = true
    await runJob(id)
    ElMessage.success('已触发执行')
  } catch (e) {
    console.error(e)
    ElMessage.error('执行失败')
  } finally {
    runningIds.value[id] = false
  }
}

const handleToggleStatus = async (row) => {
  try {
    const newStatus = isRunning(row) ? 1 : 0
    await changeStatus(row.id, newStatus)
    ElMessage.success(newStatus === 0 ? '任务已启用' : '任务已暂停')
    loadJobs()
  } catch (e) {
    console.error(e)
    ElMessage.error('状态切换失败')
  }
}

onMounted(loadJobs)
</script>

<style scoped>
.hint-text {
  font-size: 13px;
  color: var(--text-muted);
}

.job-table {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.th {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.cron-raw {
  margin-top: 2px;
  font-family: var(--font-num);
  font-size: 11px;
  color: var(--text-placeholder);
}

.invoke {
  display: inline-block;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-sunken);
  font-family: var(--font-num);
  font-size: 12px;
  color: var(--text-secondary);
  word-break: break-all;
}

.text-muted {
  font-size: 13px;
  color: var(--text-muted);
}

.list-empty {
  padding: 32px 0;
  text-align: center;
}

.field-note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

/* ---- 移动端卡片 ---- */

.card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.job-card {
  padding: 14px;
}

.jc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.jc-name {
  font-size: 15px;
  font-weight: 600;
}

.jc-schedule {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.jc-remark {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}

.jc-foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

@media screen and (max-width: 767px) {
  .toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
}
</style>
