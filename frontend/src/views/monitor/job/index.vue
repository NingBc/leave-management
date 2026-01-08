<template>
  <div>
    <div class="toolbar">
      <span></span>
      <el-button type="primary" @click="handleAdd">添加任务</el-button>
    </div>

    <el-table :data="jobList" style="width: 100%">
      <el-table-column prop="id" label="任务ID" width="80" />
      <el-table-column prop="jobName" label="任务名称" min-width="150" />
      <el-table-column prop="jobGroup" label="任务组" width="120" />
      <el-table-column prop="invokeTarget" label="调用目标" min-width="200" />
      <el-table-column prop="cronExpression" label="Cron表达式" width="150" />
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag :type="scope.row.status === 0 ? 'success' : 'info'">
            {{ scope.row.status === 0 ? '运行中' : '已暂停' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="150" />
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="scope">
          <el-button size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button size="small" type="success" :loading="!!runningIds[scope.row.id]" @click="handleRun(scope.row.id)">执行一次</el-button>
          <el-button size="small" :type="scope.row.status === 0 ? 'warning' : 'primary'" @click="handleToggleStatus(scope.row)">
            {{ scope.row.status === 0 ? '暂停' : '恢复' }}
          </el-button>
          <el-button size="small" type="danger" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="任务名称">
          <el-input v-model="form.jobName" placeholder="请输入任务名称" />
        </el-form-item>
        <el-form-item label="任务组">
          <el-input v-model="form.jobGroup" placeholder="默认为 DEFAULT" />
        </el-form-item>
        <el-form-item label="调用目标">
          <el-input v-model="form.invokeTarget" placeholder="例如: dingTalkService.syncLeaveData()" />
          <span style="color: #999; font-size: 12px">格式：Bean名称.方法名(参数)</span>
        </el-form-item>
        <el-form-item label="执行计划">
          <CronGenerator v-model="form.cronExpression" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" placeholder="任务说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleSubmit">确认</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listJobs, addJob, updateJob, deleteJob, runJob, changeStatus } from '../../../api/monitor/job'
import { ElMessage, ElMessageBox } from 'element-plus'
import CronGenerator from '../../../components/CronGenerator.vue'

const jobList = ref([])
const dialogVisible = ref(false)
const dialogTitle = ref('添加任务')
const editMode = ref('add')
const runningIds = ref({})

const form = ref({
  id: null,
  jobName: '',
  jobGroup: 'DEFAULT',
  invokeTarget: '',
  cronExpression: '',
  remark: ''
})

const loadJobs = async () => {
  try {
    const res = await listJobs()
    jobList.value = res
  } catch (e) {
    console.error(e)
    ElMessage.error('加载任务列表失败')
  }
}

const handleAdd = () => {
  editMode.value = 'add'
  dialogTitle.value = '添加任务'
  form.value = {
    id: null,
    jobName: '',
    jobGroup: 'DEFAULT',
    invokeTarget: '',
    cronExpression: '',
    remark: ''
  }
  dialogVisible.value = true
}

const handleEdit = (row) => {
  editMode.value = 'edit'
  dialogTitle.value = '编辑任务'
  form.value = { ...row }
  dialogVisible.value = true
}

const handleSubmit = async () => {
  try {
    if (editMode.value === 'add') {
      await addJob(form.value)
      ElMessage.success('任务创建成功')
    } else {
      await updateJob(form.value)
      ElMessage.success('任务更新成功')
    }
    dialogVisible.value = false
    loadJobs()
  } catch (e) {
    console.error(e)
    ElMessage.error('操作失败')
  }
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm('确定要删除该任务吗？', '提示', {
      type: 'warning'
    })
    await deleteJob(row.id)
    ElMessage.success('任务删除成功')
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
    ElMessage.success('任务执行成功')
  } catch (e) {
    console.error(e)
    ElMessage.error('任务执行失败')
  } finally {
    runningIds.value[id] = false
  }
}

const handleToggleStatus = async (row) => {
  try {
    const newStatus = row.status === 0 ? 1 : 0
    await changeStatus(row.id, newStatus)
    ElMessage.success(newStatus === 0 ? '任务已恢复' : '任务已暂停')
    loadJobs()
  } catch (e) {
    console.error(e)
    ElMessage.error('状态切换失败')
  }
}

onMounted(() => {
  loadJobs()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  margin-bottom: 20px;
}
</style>
