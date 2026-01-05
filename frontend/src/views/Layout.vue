<template>
  <div class="common-layout">
    <!-- Mobile Drawer -->
    <el-drawer
      v-model="drawerVisible"
      direction="ltr"
      size="240px"
      :with-header="false"
      class="mobile-drawer"
    >
      <div class="drawer-content">
        <div class="logo-section">
          <h3>年假管理</h3>
        </div>
        <el-menu
          router
          :default-active="$route.path"
          class="el-menu-vertical-demo"
          background-color="#2c3e50"
          text-color="#ecf0f1"
          active-text-color="#409eff"
          @select="drawerVisible = false"
        >
          <template v-for="menu in userMenus" :key="menu.id">
            <el-menu-item v-if="!menu.children || menu.children.length === 0" :index="menu.path">
              <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
              <span>{{ menu.menuName }}</span>
            </el-menu-item>
            <el-sub-menu v-else :index="menu.id.toString()">
              <template #title>
                <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
                <span>{{ menu.menuName }}</span>
              </template>
              <el-menu-item v-for="child in menu.children" :key="child.id" :index="child.path">
                {{ child.menuName }}
              </el-menu-item>
            </el-sub-menu>
          </template>
        </el-menu>
      </div>
    </el-drawer>

    <el-container>
      <el-aside v-if="!isMobile" width="200px">
        <div class="logo-section">
          <h3>年假管理</h3>
        </div>
        <el-menu
          router
          :default-active="$route.path"
          class="el-menu-vertical-demo"
          background-color="#2c3e50"
          text-color="#ecf0f1"
          active-text-color="#409eff"
        >
          <template v-for="menu in userMenus" :key="menu.id">
            <!-- 一级菜单 -->
            <el-menu-item v-if="!menu.children || menu.children.length === 0" :index="menu.path">
              <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
              <span>{{ menu.menuName }}</span>
            </el-menu-item>
            
            <!-- 有子菜单的一级菜单 -->
            <el-sub-menu v-else :index="menu.id.toString()">
              <template #title>
                <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
                <span>{{ menu.menuName }}</span>
              </template>
              <el-menu-item v-for="child in menu.children" :key="child.id" :index="child.path">
                {{ child.menuName }}
              </el-menu-item>
            </el-sub-menu>
          </template>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header>
          <div class="header-content">
            <div class="header-left">
              <el-icon v-if="isMobile" @click="drawerVisible = true" class="menu-trigger">
                <Menu />
              </el-icon>
            </div>
            <div class="header-right">
              <el-dropdown @command="handleCommand">
                <div class="user-section">
                  <el-avatar :size="36" class="user-avatar">
                    <el-icon><UserFilled /></el-icon>
                  </el-avatar>
                  <span class="username">{{ userStore.username || '用户' }}</span>
                  <el-icon class="dropdown-icon"><ArrowDown /></el-icon>
                </div>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item disabled>
                      <el-icon><User /></el-icon>
                      <span>{{ userStore.username }}</span>
                    </el-dropdown-item>
                    <el-dropdown-item divided command="changePassword">
                      <el-icon><Lock /></el-icon>
                      <span>修改密码</span>
                    </el-dropdown-item>
                    <el-dropdown-item divided command="logout">
                      <el-icon><SwitchButton /></el-icon>
                      <span>退出登录</span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </el-header>
        <el-main>
          <router-view />
        </el-main>
      </el-container>
    </el-container>

    <!-- 修改密码对话框 -->
    <el-dialog v-model="passwordDialogVisible" title="修改密码" width="450px">
      <el-form :model="passwordForm" ref="passwordFormRef" :rules="passwordRules" label-width="100px">
        <el-form-item label="旧密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="密码确认" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="passwordDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="submitPasswordChange">确认</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { UserFilled, User, SwitchButton, ArrowDown, Lock, Menu, Expand } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { useUserStore } from '../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const userMenus = ref([])
const drawerVisible = ref(false)
const screenWidth = ref(window.innerWidth)

const isMobile = computed(() => screenWidth.value < 768)

const handleResize = () => {
  screenWidth.value = window.innerWidth
  if (!isMobile.value) {
    drawerVisible.value = false
  }
}
const loadUserMenus = async () => {
  // 如果Store中已经有菜单数据，直接使用
  if (userStore.userMenus && userStore.userMenus.length > 0) {
    userMenus.value = buildMenuTree(userStore.userMenus)
    return
  }

  try {
    const userId = userStore.userId
    if (!userId) {
      console.warn('No user ID found, skipping menu load')
      return
    }
    const menus = await request.get('/system/menu/user-menus', {
      params: { userId }
    })
    
    // 保存到Store
    userStore.setUserMenus(menus)
    
    // 构建菜单树结构
    userMenus.value = buildMenuTree(menus)
  } catch (e) {
    console.error('Failed to load user menus:', e)
    userMenus.value = []
  }
}

const buildMenuTree = (menus) => {
  const menuMap = {}
  const tree = []
  
  menus.forEach(menu => {
    menuMap[menu.id] = { ...menu, children: [] }
  })
  
  menus.forEach(menu => {
    if (menu.parentId === 0 || !menuMap[menu.parentId]) {
      tree.push(menuMap[menu.id])
    } else {
      menuMap[menu.parentId].children.push(menuMap[menu.id])
    }
  })
  
  return tree
}

const handleCommand = (command) => {
  if (command === 'logout') {
    logout()
  } else if (command === 'changePassword') {
    openPasswordDialog()
  }
}

// 修改密码相关
const passwordDialogVisible = ref(false)
const passwordFormRef = ref(null)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const passwordRules = reactive({
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少为 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
})

const openPasswordDialog = () => {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordDialogVisible.value = true
}

const submitPasswordChange = async () => {
  if (!passwordFormRef.value) return
  
  await passwordFormRef.value.validate(async (valid) => {
    if (valid) {
      try {
        await request.post('/system/user/change-password', passwordForm)
        ElMessage.success('密码修改成功，请重新登录')
        passwordDialogVisible.value = false
        logout()
      } catch (e) {
        console.error(e)
      }
    }
  })
}

const logout = () => {
  userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

onMounted(() => {
  loadUserMenus()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
.common-layout {
  height: 100vh;
}

.el-container {
  height: 100%;
}

.el-aside {
  background-color: #2c3e50;
  box-shadow: 2px 0 8px rgba(0, 0, 0, 0.1);
}

.logo-section {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  text-align: center;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  box-sizing: border-box;
}

.logo-section h3 {
  margin: 0;
  color: rgba(255, 255, 255, 0.95);
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 1px;
}

.logo-section p {
  margin: 0;
  color: rgba(255, 255, 255, 0.7);
  font-size: 12px;
  font-weight: 300;
}

.el-menu {
  border-right: none;
}

.el-menu-item, .el-sub-menu {
  transition: all 0.3s;
}

.el-menu-item:hover,
.el-sub-menu:hover {
  background-color: rgba(255, 255, 255, 0.1) !important;
}

.el-menu-item.is-active {
  background-color: rgba(64, 158, 255, 0.2) !important;
  border-right: 3px solid #409eff;
  font-weight: 500;
}

.menu-trigger {
  font-size: 24px;
  cursor: pointer;
  color: #303133;
  margin-right: 15px;
  transition: color 0.3s;
}

.menu-trigger:hover {
  color: #409eff;
}

.drawer-content {
  height: 100%;
  background-color: #2c3e50;
  display: flex;
  flex-direction: column;
}

:deep(.mobile-drawer) {
  --el-drawer-padding-primary: 0;
}

.el-header {
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  padding: 0 24px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}

.header-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.header-left h3 {
  margin: 0;
  font-size: 18px;
  color: #303133;
  font-weight: 500;
}

.header-right {
  display: flex;
  align-items: center;
}

.user-section {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  border-radius: 20px;
  cursor: pointer;
  transition: all 0.3s;
  background-color: #f5f7fa;
}

.user-section:hover {
  background-color: #e4e7ed;
}

.user-avatar {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.username {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.dropdown-icon {
  color: #909399;
  font-size: 12px;
  transition: transform 0.3s;
}

.user-section:hover .dropdown-icon {
  transform: rotate(180deg);
}

.el-main {
  background-color: #f5f7fa;
  padding: 24px;
  overflow-y: auto;
}

:deep(.el-dropdown-menu__item) {
  padding: 10px 20px;
  display: flex;
  align-items: center;
  gap: 8px;
}

:deep(.el-dropdown-menu__item .el-icon) {
  font-size: 16px;
}

@media screen and (max-width: 768px) {
  .el-header {
    padding: 0 15px;
    height: 56px !important;
  }
  
  .el-main {
    padding: 15px;
  }

  .user-section {
    padding: 4px 8px;
    gap: 5px;
  }

  .username {
    display: none;
  }
  
  .logo-section {
    height: 56px;
    padding: 0;
    justify-content: center;
  }
  
  .logo-section h3 {
    font-size: 18px;
  }
}
</style>
