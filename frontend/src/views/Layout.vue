<template>
  <div class="layout">
    <!-- ===== 桌面端侧栏 ===== -->
    <aside v-if="!isMobile" class="sidebar">
      <div class="sidebar-brand">
        <div class="brand-mark">
          <el-icon :size="16"><Calendar /></el-icon>
        </div>
        <span>年假管理</span>
      </div>

      <el-menu
        router
        :default-active="route.path"
        class="sidebar-menu"
        :default-openeds="openedMenuIds"
      >
        <template v-for="menu in userMenus" :key="menu.id">
          <el-menu-item v-if="!menu.children || !menu.children.length" :index="menu.path">
            <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
            <span>{{ menu.menuName }}</span>
          </el-menu-item>
          <el-sub-menu v-else :index="String(menu.id)">
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
    </aside>

    <!-- ===== 主区 ===== -->
    <div class="main">
      <header v-if="showTopbar" class="topbar">
        <h2 class="topbar-title">{{ currentTitle }}</h2>

        <el-dropdown v-if="!isMobile" trigger="click" @command="handleCommand">
          <button class="user-btn" type="button">
            <span class="user-avatar">{{ avatarText }}</span>
            <span class="user-name">{{ displayName }}</span>
            <el-icon :size="12"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="changePassword">
                <el-icon><Lock /></el-icon>修改密码
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <main class="content" :class="{ 'has-tabbar': isMobile }">
        <router-view />
      </main>
    </div>

    <!-- ===== 移动端底部标签栏 ===== -->
    <nav v-if="isMobile" class="tabbar">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        class="tab"
        :class="{ active: isTabActive(tab) }"
        @click="onTabClick(tab)"
      >
        <el-icon :size="20"><component :is="tab.icon" /></el-icon>
        <span>{{ tab.label }}</span>
      </button>
    </nav>

    <!-- 移动端「更多」: 菜单项超过底栏容量时的兜底 -->
    <el-drawer
      v-model="moreVisible"
      direction="btt"
      size="auto"
      title="全部功能"
      class="more-drawer"
    >
      <div class="more-grid">
        <button
          v-for="item in overflowMenus"
          :key="item.path"
          type="button"
          class="more-item"
          @click="goto(item.path)"
        >
          <el-icon :size="20"><component :is="item.icon || 'Document'" /></el-icon>
          <span>{{ item.menuName }}</span>
        </button>
      </div>
    </el-drawer>

    <ChangePasswordDialog v-model="passwordDialogVisible" @success="logout" />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  ArrowDown, Lock, SwitchButton, Calendar,
  HomeFilled, User, Grid, Document
} from '@element-plus/icons-vue'
import request from '../utils/request'
import { useUserStore } from '../stores/user'
import { useBreakpoint } from '../composables/useBreakpoint'
import ChangePasswordDialog from '../components/ChangePasswordDialog.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const { isMobile } = useBreakpoint()

const userMenus = ref([])
const moreVisible = ref(false)

/* ---------- 菜单 ---------- */

const loadUserMenus = async () => {
  if (userStore.userMenus?.length) {
    userMenus.value = userStore.userMenus
    return
  }
  try {
    const userId = userStore.userId
    if (!userId) return
    const menus = await request.get('/system/menu/user-menus', { params: { userId } })
    userStore.setUserMenus(menus)
    userMenus.value = menus
  } catch (e) {
    console.error('Failed to load user menus:', e)
    userMenus.value = []
  }
}

/** 展平成可跳转的叶子菜单 */
const leafMenus = computed(() => {
  const out = []
  const walk = (list) => {
    for (const m of list || []) {
      if (m.children?.length) walk(m.children)
      else if (m.path) out.push(m)
    }
  }
  walk(userMenus.value)
  return out
})

/** 默认展开所有分组, 免得管理员每次进来都要点开二级菜单 */
const openedMenuIds = computed(() =>
  userMenus.value.filter(m => m.children?.length).map(m => String(m.id))
)

const currentTitle = computed(() => {
  const hit = leafMenus.value.find(m => m.path === route.path)
  if (hit) return hit.menuName
  return { '/dashboard': '首页', '/profile': '我的' }[route.path] || '年假管理'
})

/* ---------- 移动端底栏 ----------
   位置有限, 排布规则: 首页 + 主业务 + (第三项 或 更多) + 我 */

const businessMenus = computed(() =>
  leafMenus.value.filter(m => m.path && m.path !== '/dashboard')
)

const primaryMenu = computed(() =>
  businessMenus.value.find(m => m.path === '/leave/my') || businessMenus.value[0] || null
)

const restMenus = computed(() =>
  businessMenus.value.filter(m => m.path !== primaryMenu.value?.path)
)

const overflowMenus = computed(() => restMenus.value)

const tabs = computed(() => {
  const list = [{ key: 'home', label: '首页', icon: HomeFilled, path: '/dashboard' }]

  if (primaryMenu.value) {
    list.push({
      key: primaryMenu.value.path,
      label: shortLabel(primaryMenu.value.menuName),
      icon: primaryMenu.value.icon || Document,
      path: primaryMenu.value.path
    })
  }

  if (restMenus.value.length === 1) {
    const only = restMenus.value[0]
    list.push({
      key: only.path,
      label: shortLabel(only.menuName),
      icon: only.icon || Document,
      path: only.path
    })
  } else if (restMenus.value.length > 1) {
    list.push({ key: 'more', label: '更多', icon: Grid })
  }

  list.push({ key: 'profile', label: '我的', icon: User, path: '/profile' })
  return list
})

/** 底栏一格四个字放得下, 更长的(如「钉钉同步任务」)才截 */
const shortLabel = (name = '') => (name.length > 4 ? name.slice(0, 4) : name)

/**
 * 移动端底栏已经高亮出当前页名, 顶栏再写一遍纯属重复(钉钉容器自己还有一层标题栏)。
 * 只有走「更多」进来的页面 —— 底栏那格只显示「更多」—— 才需要顶栏点出当前位置。
 * 桌面端顶栏还挂着用户菜单, 始终保留。
 */
const showTopbar = computed(() => {
  if (!isMobile.value) return true
  return !tabs.value.some(t => t.path && t.path === route.path)
})

const isTabActive = (tab) => {
  if (tab.key === 'more') return restMenus.value.some(m => m.path === route.path)
  return route.path === tab.path
}

const onTabClick = (tab) => {
  if (tab.key === 'more') {
    moreVisible.value = true
    return
  }
  if (route.path !== tab.path) router.push(tab.path)
}

const goto = (path) => {
  moreVisible.value = false
  if (route.path !== path) router.push(path)
}

// 走底栏、后退键等其它方式换页时也要收起抽屉, 否则它会盖在新页面上
watch(() => route.path, () => {
  moreVisible.value = false
})

/* ---------- 用户 ---------- */

const displayName = computed(() => userStore.username || '用户')
const avatarText = computed(() => displayName.value.slice(0, 1).toUpperCase())

const handleCommand = (command) => {
  if (command === 'logout') logout()
  else if (command === 'changePassword') openPasswordDialog()
}

const passwordDialogVisible = ref(false)
const openPasswordDialog = () => { passwordDialogVisible.value = true }

const logout = () => {
  userStore.logout()
  router.push('/login')
}

onMounted(loadUserMenus)
</script>

<style scoped>
/* 桌面端: 外壳不滚, 只有内容区滚 —— 侧栏和顶栏始终可见。
   移动端在文件末尾改回整页滚动, 配合 fixed 底栏。 */
.layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--bg-page);
}

/* ===== 侧栏 ===== */

.sidebar {
  width: var(--sidebar-w);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-surface);
  border-right: 1px solid var(--border);
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--header-h);
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  flex-shrink: 0;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-sm);
  background: var(--brand);
  color: var(--text-inverse);
  flex-shrink: 0;
}

.sidebar-menu {
  flex: 1;
  padding: 8px;
  border-right: none;
  overflow-y: auto;
}

.sidebar-menu :deep(.el-menu-item),
.sidebar-menu :deep(.el-sub-menu__title) {
  height: 40px;
  line-height: 40px;
  margin-bottom: 2px;
  padding-left: 12px !important;
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--text-secondary);
  transition: background var(--ease), color var(--ease);
}

.sidebar-menu :deep(.el-menu-item:hover),
.sidebar-menu :deep(.el-sub-menu__title:hover) {
  background: var(--bg-hover);
  color: var(--text-primary);
}

/* 选中态: 浅底 + 左侧色条, 不用满色块 */
.sidebar-menu :deep(.el-menu-item.is-active) {
  position: relative;
  background: var(--brand-subtle);
  color: var(--brand);
  font-weight: 500;
}

.sidebar-menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background: var(--brand);
}

.sidebar-menu :deep(.el-sub-menu .el-menu-item) {
  padding-left: 34px !important;
}

/* ===== 主区 ===== */

.main {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.topbar {
  height: var(--header-h);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 24px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
  z-index: 10;
}

.topbar-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.user-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px 5px 5px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: background var(--ease), border-color var(--ease);
}

.user-btn:hover {
  background: var(--bg-hover);
  border-color: var(--border);
}

.user-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--brand-subtle);
  color: var(--brand);
  font-size: 12px;
  font-weight: 600;
}

.user-name {
  font-weight: 500;
  color: var(--text-primary);
}

.content {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
  overflow-x: hidden;
}

/* ===== 移动端底栏 ===== */

.tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 100;
  display: flex;
  background: var(--bg-surface);
  border-top: 1px solid var(--border);
  padding-bottom: env(safe-area-inset-bottom);
}

.tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  height: var(--tabbar-h);
  border: none;
  background: none;
  font-family: inherit;
  font-size: 11px;
  color: var(--text-muted);
  cursor: pointer;
  transition: color var(--ease);
}

.tab.active {
  color: var(--brand);
}

.more-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  padding-bottom: env(safe-area-inset-bottom);
}

.more-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 14px 4px;
  border: none;
  border-radius: var(--radius);
  background: var(--bg-sunken);
  font-family: inherit;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}

@media screen and (max-width: 767px) {
  /* 手机上交还给整页滚动: 内嵌滚动容器会和钉钉容器的下拉刷新打架 */
  .layout {
    height: auto;
    min-height: 100dvh;
    overflow: visible;
  }

  .main {
    height: auto;
    overflow: visible;
  }

  .topbar {
    position: sticky;
    top: 0;
    padding: 0 16px;
  }

  .content {
    padding: 16px;
    overflow: visible;
  }

  /* 给底栏让位, 否则最后一行内容被挡住 */
  .content.has-tabbar {
    overflow: visible;
    padding-bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom) + 16px);
  }
}
</style>
