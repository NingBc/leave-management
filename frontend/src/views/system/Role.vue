<template>
  <div>
    <div class="toolbar">
      <span></span>
      <el-button type="primary" @click="openDialog('add')">添加角色</el-button>
    </div>
    
    <el-table :data="paginatedData" style="width: 100%">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="roleName" label="角色名称" width="180" />
      <el-table-column prop="roleKey" label="角色标识" width="180" />
      <el-table-column prop="description" label="描述" />
      <el-table-column label="操作" width="250">
        <template #default="scope">
          <el-button size="small" @click="openDialog('edit', scope.row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      :total="tableData.length"
      layout="total, sizes, prev, pager, next, jumper"
      style="margin-top: 20px; justify-content: flex-end"
    />

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="角色名称">
          <el-input v-model="form.roleName" />
        </el-form-item>
        <el-form-item label="角色标识">
          <el-input v-model="form.roleKey" placeholder="例如：ROLE_ADMIN" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" />
        </el-form-item>
        <el-form-item label="菜单权限">
          <el-tree
            ref="menuTreeRef"
            :data="allMenus"
            show-checkbox
            node-key="id"
            :check-strictly="false"
            :props="{ children: 'children', label: 'menuName' }"
            :default-checked-keys="selectedMenuIds"
            style="width: 100%; border: 1px solid #dcdfe6; border-radius: 4px; padding: 10px;"
          />
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
import { ref, computed, onMounted, nextTick } from 'vue'
import request from '../../utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'

const tableData = ref([])
const dialogVisible = ref(false)
const editMode = ref('add')
const currentPage = ref(1)
const pageSize = ref(10)
const allMenus = ref([])
const selectedMenuIds = ref([])
const menuTreeRef = ref(null)

const form = ref({
  id: null,
  roleName: '',
  roleKey: '',
  description: '',
  menuIds: []
})

const dialogTitle = computed(() => {
  return editMode.value === 'add' ? '添加角色' : '编辑角色'
})

const paginatedData = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return tableData.value.slice(start, end)
})

const loadData = async () => {
  try {
    const res = await request.get('/system/role/list')
    tableData.value = res
  } catch (e) {
    console.error(e)
  }
}

const loadAllMenus = async () => {
  try {
    const res = await request.get('/system/menu/list')
    allMenus.value = res
  } catch (e) {
    console.error(e)
  }
}

const getLeafKeys = (nodes, keys) => {
  let leafKeys = []
  nodes.forEach(node => {
    if (node.children && node.children.length > 0) {
      leafKeys = leafKeys.concat(getLeafKeys(node.children, keys))
    } else {
      if (keys.includes(node.id)) {
        leafKeys.push(node.id)
      }
    }
  })
  return leafKeys
}

const openDialog = async (mode, row = null) => {
  editMode.value = mode
  dialogVisible.value = true
  
  // Reset tree selection first
  selectedMenuIds.value = []
  if (menuTreeRef.value) {
    menuTreeRef.value.setCheckedKeys([])
  }

  if (mode === 'add') {
    form.value = {
      id: null,
      roleName: '',
      roleKey: '',
      description: '',
      menuIds: []
    }
  } else {
    form.value = { ...row, menuIds: [] }
    // Load existing menus for this role
    try {
      // Ensure allMenus is loaded
      if (allMenus.value.length === 0) {
        await loadAllMenus()
      }

      const menuIds = await request.get(`/system/role/${row.id}/menus`)
      console.log('Backend returned menuIds:', menuIds)
      
      // Filter out parent keys, let el-tree handle them
      const leafKeys = getLeafKeys(allMenus.value, menuIds)
      console.log('Calculated leafKeys:', leafKeys)
      
      selectedMenuIds.value = leafKeys
      
      // Wait for tree to render then set checked keys
      nextTick(() => {
        if (menuTreeRef.value) {
          menuTreeRef.value.setCheckedKeys(leafKeys)
        }
      })
    } catch (e) {
      console.error(e)
    }
  }
}

const handleSubmit = async () => {
  try {
    // Get all selected keys (including half-checked parents)
    const checkedKeys = menuTreeRef.value.getCheckedKeys()
    const halfCheckedKeys = menuTreeRef.value.getHalfCheckedKeys()
    form.value.menuIds = [...checkedKeys, ...halfCheckedKeys]

    if (editMode.value === 'add') {
      await request.post('/system/role/add', form.value)
      ElMessage.success('角色已添加')
    } else {
      await request.put('/system/role/update', form.value)
      ElMessage.success('角色已更新')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
    console.error(e)
  }
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm('确定要删除该角色吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await request.delete(`/system/role/delete/${row.id}`)
    ElMessage.success('角色已删除')
    loadData()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

onMounted(() => {
  loadData()
  loadAllMenus()
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
