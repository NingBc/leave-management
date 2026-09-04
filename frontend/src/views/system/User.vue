<template>
  <div class="user-page">
    <div class="toolbar">
      <span class="count num">共 {{ total }} 人</span>
      <div class="toolbar-actions">
        <el-button @click="showImportDialog">
          <el-icon><Upload /></el-icon>批量导入
        </el-button>
        <el-button type="primary" @click="openDialog('add')">
          <el-icon><Plus /></el-icon>添加用户
        </el-button>
      </div>
    </div>

    <!-- ===== 桌面: 表格 ===== -->
    <el-table v-if="!isMobile" :data="tableData" v-loading="loading" class="surface user-table">
      <el-table-column prop="employeeNumber" label="工号" min-width="104" />
      <el-table-column prop="username" label="登录名" min-width="126" />
      <el-table-column prop="realName" label="姓名" min-width="100" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="isActive(row) ? 'success' : 'info'" size="small" effect="light">
            {{ isActive(row) ? '在职' : '已离职' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="entryDate" label="入职日期" min-width="118" />
      <el-table-column min-width="134">
        <template #header>
          <span class="th">
            {{ FIELD.firstWorkDate.short }}
            <FieldHint :label="FIELD.firstWorkDate.label" :text="FIELD.firstWorkDate.hint" />
          </span>
        </template>
        <template #default="{ row }">
          <span :class="{ 'missing-value': !row.firstWorkDate }">
            {{ row.firstWorkDate || '未填写' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column min-width="112" align="right" header-align="right">
        <template #header>
          <span class="th">
            {{ FIELD.socialSeniority.short }}<i class="th-unit">年</i>
            <FieldHint :label="FIELD.socialSeniority.label" :text="FIELD.socialSeniority.hint" />
          </span>
        </template>
        <template #default="{ row }">
          <span class="num">{{ row.socialSeniority ?? 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="104">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ getRoleName(row.roleId) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
          <el-button link type="primary" @click="openDialog('edit', row)">编辑</el-button>
          <el-dropdown trigger="click" @command="(cmd) => handleRowCommand(cmd, row)">
            <el-button link type="primary">更多<el-icon><ArrowDown /></el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="isActive(row)" command="resign">标记为离职</el-dropdown-item>
                <el-dropdown-item v-else command="activate">恢复为在职</el-dropdown-item>
                <el-dropdown-item divided command="delete" class="danger-item">删除用户</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          </div>
        </template>
      </el-table-column>
      <template #empty><span class="empty-text">没有用户</span></template>
    </el-table>

    <!-- ===== 移动: 卡片流 ===== -->
    <div v-else v-loading="loading" class="card-list">
      <article v-for="row in tableData" :key="row.id" class="user-card surface">
        <header class="uc-head">
          <span class="uc-name">{{ row.realName }}</span>
          <el-tag :type="isActive(row) ? 'success' : 'info'" size="small" effect="light">
            {{ isActive(row) ? '在职' : '已离职' }}
          </el-tag>
        </header>
        <div class="uc-meta num">工号 {{ row.employeeNumber || '—' }} · {{ row.username }}</div>
        <dl class="uc-grid">
          <div>
            <dt>入职日期</dt>
            <dd class="num">{{ row.entryDate || '—' }}</dd>
          </div>
          <div>
            <dt>{{ FIELD.firstWorkDate.short }}</dt>
            <dd class="num" :class="{ 'missing-value': !row.firstWorkDate }">
              {{ row.firstWorkDate || '未填写' }}
            </dd>
          </div>
          <div>
            <dt>{{ FIELD.socialSeniority.short }}</dt>
            <dd class="num">{{ row.socialSeniority ?? 0 }} 年</dd>
          </div>
          <div>
            <dt>角色</dt>
            <dd>{{ getRoleName(row.roleId) }}</dd>
          </div>
        </dl>
        <footer class="uc-foot">
          <el-button size="small" @click="openDialog('edit', row)">编辑</el-button>
          <el-button v-if="isActive(row)" size="small" @click="resignUser(row)">标记离职</el-button>
          <el-button v-else size="small" type="success" plain @click="activateUser(row)">恢复在职</el-button>
          <el-button size="small" type="danger" plain @click="handleDelete(row)">删除</el-button>
        </footer>
      </article>
      <p v-if="!loading && !tableData.length" class="empty-text list-empty">没有用户</p>
    </div>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      :layout="isMobile ? 'prev, pager, next' : 'total, sizes, prev, pager, next, jumper'"
      :pager-count="isMobile ? 5 : 7"
      class="pager"
      @size-change="loadData"
      @current-change="loadData"
    />

    <!-- ===== 新增 / 编辑 ===== -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      :width="isMobile ? '94%' : '560px'"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :label-position="isMobile ? 'top' : 'right'"
        label-width="120px"
      >
        <el-form-item label="工号" prop="employeeNumber">
          <el-input v-model="form.employeeNumber" placeholder="如 E1024" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="登录名" prop="username">
          <el-input v-model="form.username" placeholder="登录账号" />
        </el-form-item>
        <el-form-item v-if="editMode === 'add'" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item v-else label="重置密码">
          <el-input v-model="form.password" type="password" show-password placeholder="留空则不修改" />
        </el-form-item>
        <el-form-item label="角色" prop="roleId">
          <el-select v-model="form.roleId" placeholder="请选择角色" style="width: 100%">
            <el-option v-for="role in roles" :key="role.id" :label="role.roleName" :value="role.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入职本公司">
          <el-date-picker
            v-model="form.entryDate" type="date" placeholder="选择日期"
            value-format="YYYY-MM-DD" style="width: 100%"
          />
        </el-form-item>
        <el-form-item>
          <template #label>
            首次参加工作
            <FieldHint :label="FIELD.firstWorkDate.label" :text="FIELD.firstWorkDate.hint" />
          </template>
          <el-date-picker
            v-model="form.firstWorkDate" type="date" placeholder="选择日期"
            value-format="YYYY-MM-DD" style="width: 100%"
          />
          <div class="field-note">决定年假档位，填错会算错年假</div>
        </el-form-item>
        <el-form-item label="钉钉 UserId">
          <el-input v-model="form.dingtalkUserId" placeholder="用于免密登录和同步休假" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- ===== 批量导入 ===== -->
    <el-dialog
      v-model="importDialogVisible"
      title="批量导入用户"
      :width="isMobile ? '94%' : '540px'"
    >
      <ul class="import-steps">
        <li><strong>工号</strong>、<strong>姓名</strong>必填</li>
        <li><strong>首次参加工作日期</strong>决定年假档位，留空会按 0 年工龄算</li>
        <li>登录名取姓名拼音，角色默认「员工」</li>
      </ul>

      <el-button class="tpl-btn" @click="downloadTemplate">
        <el-icon><Download /></el-icon>下载 CSV 模板
      </el-button>

      <el-upload
        ref="uploadRef"
        :auto-upload="false"
        :on-change="handleFileChange"
        :limit="1"
        accept=".csv"
        drag
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖到这里，或<em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">只接受 .csv，不要改列顺序</div>
        </template>
      </el-upload>

      <div v-if="importResult" class="import-result">
        <el-alert
          :title="`导入完成：成功 ${importResult.successCount} 条，失败 ${importResult.failureCount} 条`"
          :type="importResult.failureCount > 0 ? 'warning' : 'success'"
          :closable="false"
        >
          <ul v-if="importResult.errors?.length" class="import-errors">
            <li v-for="(error, index) in importResult.errors" :key="index">{{ error }}</li>
          </ul>
        </el-alert>
      </div>

      <template #footer>
        <el-button @click="importDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="importing" @click="handleImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled, Upload, Download, Plus, ArrowDown } from '@element-plus/icons-vue'
import { useBreakpoint } from '../../composables/useBreakpoint'
import { FIELD } from '../../constants/leave'
import FieldHint from '../../components/FieldHint.vue'

const { isMobile } = useBreakpoint()

const tableData = ref([])
const roles = ref([])
const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const editMode = ref('add')
const formRef = ref(null)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const importDialogVisible = ref(false)
const uploadRef = ref(null)
const uploadFile = ref(null)
const importing = ref(false)
const importResult = ref(null)

const emptyForm = () => ({
  id: null,
  employeeNumber: '',
  username: '',
  password: '',
  realName: '',
  dingtalkUserId: '',
  firstWorkDate: '',
  entryDate: '',
  socialSeniority: 0,
  roleId: null
})

const form = ref(emptyForm())

const rules = {
  employeeNumber: [{ required: true, message: '请填写工号', trigger: 'blur' }],
  realName: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  username: [{ required: true, message: '请填写登录名', trigger: 'blur' }],
  password: [{ required: true, message: '请设置初始密码', trigger: 'blur' },
             { min: 6, message: '密码至少 6 位', trigger: 'blur' }],
  roleId: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

const dialogTitle = computed(() => (editMode.value === 'add' ? '添加用户' : '编辑用户'))

/** status 为空的老数据按在职处理, 与后端 resign/activate 的默认口径一致 */
const isActive = (row) => row.status === 'ACTIVE' || !row.status

const getRoleName = (roleId) => roles.value.find(r => r.id === roleId)?.roleName || '—'

const loadRoles = async () => {
  try {
    roles.value = await request.get('/system/role/list')
  } catch (e) {
    console.error(e)
  }
}

const loadData = async () => {
  try {
    loading.value = true
    const res = await request.get('/system/user/list', {
      params: { current: currentPage.value, size: pageSize.value }
    })
    tableData.value = res.records
    total.value = res.total
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const openDialog = (mode, row = null) => {
  editMode.value = mode
  form.value = mode === 'add' ? emptyForm() : { ...row, password: '' }
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
        await request.post('/system/user/add', form.value)
        ElMessage.success('用户已添加')
      } else {
        await request.put('/system/user/update', form.value)
        ElMessage.success('用户已更新')
      }
      dialogVisible.value = false
      loadData()
    } catch (e) {
      console.error(e)
    } finally {
      submitting.value = false
    }
  })
}

const handleRowCommand = (cmd, row) => {
  if (cmd === 'resign') resignUser(row)
  else if (cmd === 'activate') activateUser(row)
  else if (cmd === 'delete') handleDelete(row)
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `删除后「${row.realName}」无法登录，年假账户和休假记录一并隐藏。`,
      '删除用户',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    await request.delete(`/system/user/delete/${row.id}`)
    ElMessage.success('用户已删除')
    loadData()
  } catch (e) {
    if (e !== 'cancel') console.error(e)
  }
}

const resignUser = async (user) => {
  try {
    await ElMessageBox.confirm(
      `「${user.realName}」离职后年假停止累积，账户保留可查。`,
      '标记为离职',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    await request.post(`/system/user/resign/${user.id}`)
    ElMessage.success('已标记为离职')
    loadData()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
      ElMessage.error('操作失败')
    }
  }
}

const activateUser = async (user) => {
  try {
    await ElMessageBox.confirm(
      `「${user.realName}」的年假将继续按在职天数累积。`,
      '恢复为在职',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'info' }
    )
    await request.post(`/system/user/activate/${user.id}`)
    ElMessage.success('已恢复为在职')
    loadData()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
      ElMessage.error('操作失败')
    }
  }
}

/* ---------- 导入 ---------- */

const showImportDialog = () => {
  importDialogVisible.value = true
  uploadFile.value = null
  importResult.value = null
}

const handleFileChange = (file) => {
  uploadFile.value = file.raw
}

const downloadTemplate = () => {
  const template = `工号,姓名,入职日期,首次参加工作日期,钉钉ID
E001,张三,2024-01-15,2020-06-01,zhangsan123
E002,李四,2024-02-01,2018-03-15,lisi456`

  // BOM 开头, 否则 Excel 打开是乱码
  const blob = new Blob(['﻿' + template], { type: 'text/csv;charset=utf-8;' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = '用户导入模板.csv'
  link.click()
  URL.revokeObjectURL(link.href)
}

const handleImport = async () => {
  if (!uploadFile.value) {
    ElMessage.warning('请先选择 CSV 文件')
    return
  }

  const formData = new FormData()
  formData.append('file', uploadFile.value)

  importing.value = true
  importResult.value = null

  try {
    const response = await request.post('/system/user/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000
    })

    importResult.value = response

    if (response.successCount > 0) {
      ElMessage.success(`成功导入 ${response.successCount} 个用户`)
      loadData()
    }
    if (response.failureCount > 0) {
      ElMessage.warning(`${response.failureCount} 条导入失败`)
    }

    uploadRef.value?.clearFiles()
    uploadFile.value = null
  } catch (error) {
    console.error('导入失败', error)
    ElMessage.error('导入失败')
  } finally {
    importing.value = false
  }
}

onMounted(() => {
  loadData()
  loadRoles()
})
</script>

<style scoped>
.count {
  font-size: 13px;
  color: var(--text-muted);
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.user-table {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.th {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.th-unit {
  margin-left: 3px;
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
  color: var(--text-annotation);
}

/* el-dropdown 的 vertical-align 是 top, el-button 是 middle, 并排会错开约 3px。
   交给 flex 对齐, 不依赖行内基线。 */
.row-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 缺了首次参加工作日期就等于年假档位算错, 值得标出来 */
.missing-value {
  color: var(--warning);
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

:deep(.danger-item) {
  color: var(--danger);
}

/* ---- 移动端卡片 ---- */

.card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.user-card {
  padding: 14px;
}

.uc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.uc-name {
  font-size: 15px;
  font-weight: 600;
}

.uc-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
}

.uc-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  margin: 12px 0 0;
}

.uc-grid dt {
  font-size: 11px;
  color: var(--text-muted);
}

.uc-grid dd {
  margin: 2px 0 0;
  font-size: 13px;
  font-weight: 500;
}

.uc-foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

/* ---- 表单 / 导入 ---- */

.field-note {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
}

.import-steps {
  margin: 0 0 16px;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.import-steps strong {
  color: var(--text-primary);
}

.tpl-btn {
  margin-bottom: 14px;
}

.import-result {
  margin-top: 16px;
}

.import-errors {
  max-height: 180px;
  overflow-y: auto;
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--danger);
}

@media screen and (max-width: 767px) {
  .toolbar-actions {
    flex: 1;
    justify-content: flex-end;
  }

  .pager {
    justify-content: center;
  }
}
</style>
