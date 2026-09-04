<template>
  <div class="menu-page">
    <div class="toolbar">
      <span class="hint-text">改动后用户需重新登录才生效</span>
      <el-button type="primary" @click="openDialog('add')">
        <el-icon><Plus /></el-icon>添加菜单
      </el-button>
    </div>

    <div class="table-scroll">
      <el-table
        :data="tableData"
        row-key="id"
        default-expand-all
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
        class="surface menu-table"
      >
        <el-table-column prop="menuName" label="菜单名称" min-width="180" />
        <el-table-column label="图标" width="70" align="center">
          <template #default="{ row }">
            <el-icon v-if="row.icon"><component :is="row.icon" /></el-icon>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="orderNum" label="排序" width="70" align="center">
          <template #default="{ row }"><span class="num">{{ row.orderNum }}</span></template>
        </el-table-column>
        <el-table-column min-width="180">
          <template #header>
            <span class="th">
              页面路径
              <FieldHint label="页面路径" text="前端路由地址，须与代码里注册的路径完全一致（如 /leave/my），写错菜单点开是空白页。分组菜单留空。" />
            </span>
          </template>
          <template #default="{ row }">
            <code v-if="row.path" class="path">{{ row.path }}</code>
            <span v-else class="text-muted">分组</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog('add', row)">加子菜单</el-button>
            <el-button link type="primary" @click="openDialog('edit', row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><span class="text-muted">没有菜单</span></template>
      </el-table>
    </div>

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
        label-width="100px"
      >
        <el-form-item label="上级菜单">
          <el-tree-select
            v-model="form.parentId"
            :data="treeOptions"
            :props="{ label: 'menuName', value: 'id', children: 'children' }"
            check-strictly
            placeholder="选择上级菜单"
            style="width: 100%"
          />
          <div class="field-note">选「主类目」即一级菜单</div>
        </el-form-item>
        <el-form-item label="菜单名称" prop="menuName">
          <el-input v-model="form.menuName" placeholder="导航栏显示的文字" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.orderNum" :min="0" />
          <span class="field-note inline">数字越小越靠前</span>
        </el-form-item>
        <el-form-item>
          <template #label>
            页面路径
            <FieldHint label="页面路径" text="前端路由地址，须与代码里注册的路径完全一致（如 /leave/my），写错菜单点开是空白页。分组菜单留空。" />
          </template>
          <el-input v-model="form.path" placeholder="如 /leave/my，分组留空" />
        </el-form-item>
        <el-form-item>
          <template #label>
            图标
            <FieldHint label="图标" text="Element Plus 图标名，区分大小写，如 User、Calendar。填错不报错，只是不显示。" />
          </template>
          <el-input v-model="form.icon" placeholder="如 Calendar，可留空" />
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
import { ref, computed, onMounted } from 'vue'
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
const formRef = ref(null)

const form = ref({ id: null, parentId: 0, menuName: '', path: '', icon: '', orderNum: 1 })

const rules = {
  menuName: [{ required: true, message: '请填写菜单名称', trigger: 'blur' }]
}

const dialogTitle = computed(() => (editMode.value === 'add' ? '添加菜单' : '编辑菜单'))

const loadData = async () => {
  try {
    tableData.value = await request.get('/system/menu/list')
  } catch (e) {
    console.error(e)
  }
}

const openDialog = (mode, row = null) => {
  editMode.value = mode
  form.value = mode === 'add'
    ? { id: null, parentId: row ? row.id : 0, menuName: '', path: '', icon: '', orderNum: 1 }
    : { ...row }
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

const treeOptions = computed(() => {
  const options = [{ id: 0, menuName: '主类目', children: [] }]
  options[0].children = JSON.parse(JSON.stringify(tableData.value))
  return options
})

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      submitting.value = true
      // children 是前端拼的树形字段, 不能回传给后端
      const payload = { ...form.value }
      delete payload.children
      if (payload.path && !payload.component) {
        payload.component = payload.path.replace(/^\//, '')
      }

      if (editMode.value === 'add') {
        await request.post('/system/menu/add', payload)
        ElMessage.success('菜单已添加')
      } else {
        await request.put('/system/menu/update', payload)
        ElMessage.success('菜单已更新')
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
  const hasChildren = row.children?.length > 0
  try {
    await ElMessageBox.confirm(
      hasChildren
        ? `「${row.menuName}」下还有 ${row.children.length} 个子菜单，请先处理。`
        : `删除后所有角色都不再看到「${row.menuName}」。`,
      '删除菜单',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    await request.delete(`/system/menu/delete/${row.id}`)
    ElMessage.success('菜单已删除')
    loadData()
  } catch (e) {
    if (e !== 'cancel') console.error(e)
  }
}

onMounted(loadData)
</script>

<style scoped>
.hint-text {
  font-size: 13px;
  color: var(--text-muted);
}

/* 树形表格拆成卡片会丢掉层级关系, 移动端保留表格让它横向滚动 */
.table-scroll {
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

.menu-table {
  min-width: 640px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.th {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.path {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-sunken);
  font-family: var(--font-num);
  font-size: 12px;
  color: var(--text-secondary);
}

.text-muted {
  font-size: 13px;
  color: var(--text-muted);
}

.field-note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.field-note.inline {
  margin: 0 0 0 10px;
}

@media screen and (max-width: 767px) {
  .toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
}
</style>
