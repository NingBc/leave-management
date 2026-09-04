<template>
  <div class="role-page">
    <div class="toolbar">
      <span class="count num">共 {{ tableData.length }} 个角色</span>
      <el-button type="primary" @click="openDialog('add')">
        <el-icon><Plus /></el-icon>添加角色
      </el-button>
    </div>

    <el-table v-if="!isMobile" :data="paginatedData" class="surface role-table">
      <el-table-column prop="roleName" label="角色名称" width="160" />
      <el-table-column width="180">
        <template #header>
          <span class="th">
            角色标识
            <FieldHint label="角色标识" text="程序内部判断权限用的标识，如 ROLE_ADMIN。创建后勿改，改了该角色的用户权限会失效。" />
          </span>
        </template>
        <template #default="{ row }">
          <code class="role-key">{{ row.roleKey }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="{ 'text-muted': !row.description }">{{ row.description || '未填写' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog('edit', row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty><span class="empty-text">没有角色</span></template>
    </el-table>

    <div v-else class="card-list">
      <article v-for="row in paginatedData" :key="row.id" class="role-card surface">
        <div class="rc-name">{{ row.roleName }}</div>
        <code class="role-key">{{ row.roleKey }}</code>
        <p class="rc-desc">{{ row.description || '未填写说明' }}</p>
        <footer class="rc-foot">
          <el-button size="small" @click="openDialog('edit', row)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="handleDelete(row)">删除</el-button>
        </footer>
      </article>
      <p v-if="!tableData.length" class="empty-text list-empty">没有角色</p>
    </div>

    <el-pagination
      v-if="tableData.length > pageSize"
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      :total="tableData.length"
      :layout="isMobile ? 'prev, pager, next' : 'total, sizes, prev, pager, next, jumper'"
      class="pager"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      :width="isMobile ? '94%' : '600px'"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :label-position="isMobile ? 'top' : 'right'"
        label-width="110px"
      >
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="如 HR 管理员" />
        </el-form-item>
        <el-form-item prop="roleKey">
          <template #label>
            角色标识
            <FieldHint label="角色标识" text="程序内部判断权限用的标识，如 ROLE_ADMIN。创建后勿改，改了该角色的用户权限会失效。" />
          </template>
          <el-input v-model="form.roleKey" placeholder="如 ROLE_ADMIN" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="选填" />
        </el-form-item>
        <el-form-item label="可访问的菜单">
          <el-tree
            ref="menuTreeRef"
            :data="allMenus"
            show-checkbox
            node-key="id"
            :props="{ children: 'children', label: 'menuName' }"
            :default-checked-keys="selectedMenuIds"
            class="menu-tree"
          />
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
import { ref, computed, onMounted, nextTick } from 'vue'
import request from '../../utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { useBreakpoint } from '../../composables/useBreakpoint'
import FieldHint from '../../components/FieldHint.vue'

const { isMobile } = useBreakpoint()

const tableData = ref([])
const dialogVisible = ref(false)
const submitting = ref(false)
const editMode = ref('add')
const currentPage = ref(1)
const pageSize = ref(10)
const allMenus = ref([])
const selectedMenuIds = ref([])
const menuTreeRef = ref(null)
const formRef = ref(null)

const form = ref({ id: null, roleName: '', roleKey: '', description: '', menuIds: [] })

const rules = {
  roleName: [{ required: true, message: '请填写角色名称', trigger: 'blur' }],
  roleKey: [{ required: true, message: '请填写角色标识', trigger: 'blur' }]
}

const dialogTitle = computed(() => (editMode.value === 'add' ? '添加角色' : '编辑角色'))

const paginatedData = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return tableData.value.slice(start, start + pageSize.value)
})

const loadData = async () => {
  try {
    tableData.value = await request.get('/system/role/list')
  } catch (e) {
    console.error(e)
  }
}

const loadAllMenus = async () => {
  try {
    allMenus.value = await request.get('/system/menu/list')
  } catch (e) {
    console.error(e)
  }
}

/**
 * 只回填叶子节点。
 * el-tree 会自己根据子节点推出父节点的勾选/半选状态, 若把父 id 也塞进去,
 * 那些「父节点选中但子节点没全选」的角色会被展开成全选。
 */
const getLeafKeys = (nodes, keys) => {
  let leafKeys = []
  nodes.forEach(node => {
    if (node.children?.length) {
      leafKeys = leafKeys.concat(getLeafKeys(node.children, keys))
    } else if (keys.includes(node.id)) {
      leafKeys.push(node.id)
    }
  })
  return leafKeys
}

const openDialog = async (mode, row = null) => {
  editMode.value = mode
  dialogVisible.value = true
  selectedMenuIds.value = []
  menuTreeRef.value?.setCheckedKeys([])

  if (mode === 'add') {
    form.value = { id: null, roleName: '', roleKey: '', description: '', menuIds: [] }
    return
  }

  form.value = { ...row, menuIds: [] }
  try {
    if (!allMenus.value.length) await loadAllMenus()
    const menuIds = await request.get(`/system/role/${row.id}/menus`)
    const leafKeys = getLeafKeys(allMenus.value, menuIds)
    selectedMenuIds.value = leafKeys
    nextTick(() => menuTreeRef.value?.setCheckedKeys(leafKeys))
  } catch (e) {
    console.error(e)
  }
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      submitting.value = true
      // 半选的父节点也要提交, 否则子菜单挂不到导航树上
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
    } finally {
      submitting.value = false
    }
  })
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `删除后属于「${row.roleName}」的用户将失去对应菜单权限。`,
      '删除角色',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    await request.delete(`/system/role/delete/${row.id}`)
    ElMessage.success('角色已删除')
    loadData()
  } catch (e) {
    if (e !== 'cancel') console.error(e)
  }
}

onMounted(() => {
  loadData()
  loadAllMenus()
})
</script>

<style scoped>
.count {
  font-size: 13px;
  color: var(--text-muted);
}

.role-table {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.th {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.role-key {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-sunken);
  font-family: var(--font-num);
  font-size: 12px;
  color: var(--text-secondary);
}

.text-muted,
.empty-text {
  color: var(--text-muted);
}

.empty-text {
  font-size: 13px;
}

.list-empty {
  padding: 32px 0;
  text-align: center;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

.card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.role-card {
  padding: 14px;
}

.rc-name {
  margin-bottom: 6px;
  font-size: 15px;
  font-weight: 600;
}

.rc-desc {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}

.rc-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.menu-tree {
  width: 100%;
  max-height: 280px;
  overflow-y: auto;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.field-note {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

@media screen and (max-width: 767px) {
  .pager {
    justify-content: center;
  }
}
</style>
