<template>
  <div>
    <div class="toolbar">
      <span></span>
      <div>
        <el-button type="success" @click="showImportDialog">导入用户</el-button>
        <el-button type="primary" @click="openDialog('add')">添加用户</el-button>
      </div>
    </div>
    
    <el-table :data="tableData" style="width: 100%">
		<el-table-column prop="employeeNumber" label="编号" width="100" />
	<el-table-column prop="username" label="用户名" width="120" />
      <el-table-column prop="realName" label="姓名" min-width="100" />
	   <el-table-column prop="entryDate" label="入职日期" width="120" />
      <el-table-column prop="firstWorkDate" label="首次参加工作时间" width="140" />
      <el-table-column prop="socialSeniority" label="社会工龄(年)" width="80" />
      <el-table-column prop="dingtalkUserId" label="钉钉ID" min-width="150" />
      <el-table-column label="角色" min-width="140" show-overflow-tooltip>
	    <template #default="scope">
	      <el-tag>{{ getRoleName(scope.row.roleId) }}</el-tag>
	    </template>
	  </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="scope">
          <el-button type="primary" size="small" @click="openDialog('edit', scope.row)">编辑</el-button>
          <el-button v-if="scope.row.status === 'ACTIVE' || !scope.row.status" type="warning" size="small" @click="resignUser(scope.row)">离职</el-button>
          <el-button v-else type="success" size="small" @click="activateUser(scope.row)">恢复在职</el-button>
          <el-button type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next, jumper"
      style="margin-top: 20px; justify-content: flex-end"
      @size-change="loadData"
      @current-change="loadData"
    />

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px">
      <el-form :model="form" label-width="140px">
        <el-form-item label="编号">
          <el-input v-model="form.employeeNumber" style="width: 100%" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.username" style="width: 100%" />
        </el-form-item>
        <el-form-item label="密码" v-if="editMode === 'add'">
          <el-input v-model="form.password" type="password" style="width: 100%" />
        </el-form-item>
        <el-form-item label="修改密码" v-if="editMode === 'edit'">
          <el-input v-model="form.password" type="password" placeholder="留空则不修改密码" style="width: 100%" />
        </el-form-item>
        <el-form-item label="真实姓名">
          <el-input v-model="form.realName" style="width: 100%" />
        </el-form-item>
        <el-form-item label="钉钉ID">
          <el-input v-model="form.dingtalkUserId" style="width: 100%" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleId" placeholder="请选择角色" style="width: 100%">
            <el-option
              v-for="role in roles"
              :key="role.id"
              :label="role.roleName"
              :value="role.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="首次参加工作时间">
          <el-date-picker v-model="form.firstWorkDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="入职日期">
          <el-date-picker v-model="form.entryDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleSubmit">确认</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- 导入用户对话框 -->
    <el-dialog v-model="importDialogVisible" title="导入用户" width="500px">
      <el-alert title="导入说明" type="info" :closable="false" style="margin-bottom: 15px">
        <p>1. 请下载CSV模板并填写用户信息</p>
        <p>2. 必填字段：工号、姓名</p>
        <p>3. 默认密码：123456，角色：员工</p>
        <p>4. 用户名将自动生成为姓名拼音</p>
      </el-alert>

      <el-button type="primary" @click="downloadTemplate" style="margin-bottom: 15px">
        下载CSV模板
      </el-button>

      <el-upload
        ref="uploadRef"
        :auto-upload="false"
        :on-change="handleFileChange"
        :limit="1"
        accept=".csv"
        drag
      >
        <el-icon class="el-icon--upload"><upload-filled /></el-icon>
        <div class="el-upload__text">
          拖拽文件到此处或<em>点击选择文件</em>
        </div>
        <template #tip>
          <div class="el-upload__tip">只支持CSV格式文件</div>
        </template>
      </el-upload>

      <div v-if="importResult" style="margin-top: 20px">
        <el-alert 
          :title="`导入完成：成功 ${importResult.successCount} 条，失败 ${importResult.failureCount} 条`"
          :type="importResult.failureCount > 0 ? 'warning' : 'success'"
          :closable="false"
        >
          <div v-if="importResult.errors && importResult.errors.length > 0" style="max-height: 200px; overflow-y: auto; margin-top: 10px">
            <p v-for="(error, index) in importResult.errors" :key="index" style="margin: 5px 0; color: #f56c6c">
              {{ error }}
            </p>
          </div>
        </el-alert>
      </div>

      <template #footer>
        <span>
          <el-button @click="importDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleImport" :loading="importing">开始导入</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'

const tableData = ref([])
const roles = ref([]) // Store roles list
const dialogVisible = ref(false)
const editMode = ref('add')
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

// 导入相关
const importDialogVisible = ref(false)
const uploadRef = ref(null)
const uploadFile = ref(null)
const importing = ref(false)
const importResult = ref(null)

const form = ref({
  id: null,
  employeeNumber: '',
  username: '',
  password: '',
  realName: '',
  dingtalkUserId: '',
  firstWorkDate: '',
  entryDate: '',
  socialSeniority: 0,
  roleId: null // Add roleId
})

const dialogTitle = computed(() => {
  return editMode.value === 'add' ? '添加用户' : '编辑用户'
})

// Helper to get role name from ID
const getRoleName = (roleId) => {
  const role = roles.value.find(r => r.id === roleId)
  return role ? role.roleName : '未知'
}

const loadRoles = async () => {
  try {
    const res = await request.get('/system/role/list')
    roles.value = res
  } catch (e) {
    console.error(e)
  }
}

const loadData = async () => {
  try {
    const res = await request.get('/system/user/list', {
      params: {
        current: currentPage.value,
        size: pageSize.value
      }
    })
    tableData.value = res.records
    total.value = res.total
  } catch (e) {
    console.error(e)
  }
}

const openDialog = (mode, row = null) => {
  editMode.value = mode
  if (mode === 'add') {
    form.value = {
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
    }
  } else {
    form.value = { ...row, password: '' }
  }
  dialogVisible.value = true
}

const handleSubmit = async () => {
  try {
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
  }
}

const handleDelete = async (row) => {
  try {
    await request.delete(`/system/user/delete/${row.id}`)
    ElMessage.success('用户已删除')
    loadData()
  } catch (e) {
    console.error(e)
  }
}

const resignUser = async (user) => {
  try {
    await ElMessageBox.confirm(`确定要将 ${user.realName} 设置为离职状态吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await request.post(`/system/user/resign/${user.id}`)
    ElMessage.success('操作成功')
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
    await ElMessageBox.confirm(`确定要恢复 ${user.realName} 为在职状态吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'success'
    })
    await request.post(`/system/user/activate/${user.id}`)
    ElMessage.success('操作成功')
    loadData()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
      ElMessage.error('操作失败')
    }
  }
}

// 导入功能
const showImportDialog = () => {
  importDialogVisible.value = true
  uploadFile.value = null
  importResult.value = null
}

const handleFileChange = (file) => {
  uploadFile.value = file.raw
}

const downloadTemplate = () => {
  // 创建CSV模板内容 - 使用正确的换行符
  const template = `工号,姓名,入职日期,首次参加工作日期,钉钉ID
E001,张三,2024-01-15,2020-06-01,zhangsan123
E002,李四,2024-02-01,2018-03-15,lisi456`
  
  // 创建Blob，添加BOM以支持Excel正确显示中文
  const blob = new Blob(['\ufeff' + template], { type: 'text/csv;charset=utf-8;' })
  
  // 创建下载链接
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = '用户导入模板.csv'
  link.click()
  URL.revokeObjectURL(link.href)
  
  ElMessage.success('模板下载成功')
}

const handleImport = async () => {
  if (!uploadFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }

  const formData = new FormData()
  formData.append('file', uploadFile.value)

  importing.value = true
  importResult.value = null

  try {
    const response = await request.post('/system/user/import', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      },
      timeout: 60000
    })

    importResult.value = response
    
    if (response.successCount > 0) {
      ElMessage.success(`成功导入 ${response.successCount} 个用户`)
      loadData() // 刷新列表
    }

    if (response.failureCount > 0) {
      ElMessage.warning(`${response.failureCount} 个用户导入失败，请查看详情`)
    }

    // 清空上传组件
    if (uploadRef.value) {
      uploadRef.value.clearFiles()
    }
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
.toolbar {
  display: flex;
  justify-content: space-between;
  margin-bottom: 16px;
}
</style>
```
