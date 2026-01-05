<template>
  <div>
    <div class="toolbar">
      <span></span>
      <el-button type="primary" @click="openDialog('add')">添加菜单</el-button>
    </div>
    
    <el-table 
      :data="tableData" 
      style="width: 100%" 
      row-key="id" 
      border
      default-expand-all
      :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
    >
      <el-table-column prop="menuName" label="菜单名称" width="200" />
      <el-table-column prop="icon" label="图标" width="80" align="center">
        <template #default="scope">
          <el-icon v-if="scope.row.icon"><component :is="scope.row.icon" /></el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="orderNum" label="排序" width="80" align="center" />
      <el-table-column prop="perms" label="权限标识" width="200" />
      <el-table-column prop="component" label="组件路径" width="200" />
      <el-table-column prop="path" label="路由地址" />
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="openDialog('add', scope.row)">新增下级</el-button>
          <el-button link type="primary" size="small" @click="openDialog('edit', scope.row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="上级菜单">
          <el-tree-select
            v-model="form.parentId"
            :data="[{ id: 0, menuName: '主类目', children: tableData }]"
            :props="{ label: 'menuName', value: 'id', children: 'children' }"
            check-strictly
            placeholder="选择上级菜单"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="菜单名称">
          <el-input v-model="form.menuName" placeholder="请输入菜单名称" />
        </el-form-item>
        <el-form-item label="显示排序">
          <el-input-number v-model="form.orderNum" :min="0" />
        </el-form-item>
        <el-form-item label="路由地址">
          <el-input v-model="form.path" placeholder="请输入路由地址" />
        </el-form-item>
        <el-form-item label="组件路径">
          <el-input v-model="form.component" placeholder="请输入组件路径" />
        </el-form-item>
        <el-form-item label="权限标识">
          <el-input v-model="form.perms" placeholder="请输入权限标识" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="form.icon" placeholder="请输入图标名称 (如: Menu)" />
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
import { ref, computed, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'

const tableData = ref([])
const dialogVisible = ref(false)
const editMode = ref('add')

const form = ref({
  id: null,
  parentId: 0,
  menuName: '',
  path: '',
  component: '',
  perms: '',
  icon: '',
  orderNum: 1
})

const dialogTitle = computed(() => {
  return editMode.value === 'add' ? '添加菜单' : '编辑菜单'
})

const loadData = async () => {
  try {
    const res = await request.get('/system/menu/list')
    tableData.value = res
  } catch (e) {
    console.error(e)
  }
}

const openDialog = (mode, row = null) => {
  editMode.value = mode
  if (mode === 'add') {
    form.value = {
      id: null,
      parentId: row ? row.id : 0, // If row is provided (Add Submenu), set parentId
      menuName: '',
      path: '',
      component: '',
      perms: '',
      icon: '',
      orderNum: 1
    }
  } else {
    form.value = { ...row }
  }
  dialogVisible.value = true
}

const handleSubmit = async () => {
  try {
    if (editMode.value === 'add') {
      await request.post('/system/menu/add', form.value)
      ElMessage.success('菜单已添加')
    } else {
      await request.put('/system/menu/update', form.value)
      ElMessage.success('菜单已更新')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
    console.error(e)
  }
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm('确定要删除该菜单吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await request.delete(`/system/menu/delete/${row.id}`)
    ElMessage.success('菜单已删除')
    loadData()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
</style>
