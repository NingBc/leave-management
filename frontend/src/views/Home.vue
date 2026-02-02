<template>
  <div class="home-container">
    <div class="welcome-section">
      <div class="welcome-card">
        <div class="greeting">
          <h1>你好，{{ userInfo.realName || userStore.username || '用户' }}！</h1>
        </div>
        <div v-if="dailyQuote" class="daily-quote">
          🍵 {{ dailyQuote }}
        </div>
      </div>
    </div>

    <div class="stats-section">
      <el-row :gutter="isMobile ? 12 : 20">
        <el-col :span="isMobile ? 24 : 8">
          <div class="stat-card">
            <div class="stat-icon" style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);">
              <el-icon :size="28"><Calendar /></el-icon>
            </div>
            <div class="stat-content">
              <div class="stat-value">{{ daysInCompany }}</div>
              <div class="stat-label">在职天数</div>
            </div>
          </div>
        </el-col>
        <el-col :span="isMobile ? 24 : 8">
          <div class="stat-card">
            <div class="stat-icon" style="background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);">
              <el-icon :size="28"><Clock /></el-icon>
            </div>
            <div class="stat-content">
              <div class="stat-value">{{ formatDate(userInfo.entryDate) }}</div>
              <div class="stat-label">入职日期</div>
            </div>
          </div>
        </el-col>
        <el-col :span="isMobile ? 24 : 8">
          <div class="stat-card">
            <div class="stat-icon" style="background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);">
              <el-icon :size="28"><TrophyBase /></el-icon>
            </div>
            <div class="stat-content">
              <div class="stat-value">{{ userInfo.socialSeniority || 0 }} 年</div>
              <div class="stat-label">社会工龄</div>
            </div>
          </div>
        </el-col>
      </el-row>
    </div>

    <div class="quick-actions" v-if="quickActions.length > 0">
      <h3 class="section-title">快捷操作</h3>
      <el-row :gutter="isMobile ? 12 : 20">
        <el-col :span="isMobile ? 12 : 6" v-for="action in quickActions" :key="action.path">
          <div class="action-card" @click="navigateTo(action.path)">
            <el-icon :size="32" :style="{ color: action.color }">
              <component :is="action.icon" />
            </el-icon>
            <div class="action-title">{{ action.title }}</div>
          </div>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { 
  Calendar, User, Clock, TrophyBase,
  DocumentCopy, Tickets, Setting, DataAnalysis
} from '@element-plus/icons-vue'
import request from '../utils/request'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()
const userInfo = ref({})
const dailyQuote = ref('')
const userMenus = ref([])
const screenWidth = ref(window.innerWidth)

const isMobile = computed(() => screenWidth.value < 768)

const handleResize = () => {
  screenWidth.value = window.innerWidth
}

const allQuickActions = [
  { title: '我的休假', icon: Tickets, path: '/leave/my', color: '#667eea' },
  { title: '年假管理', icon: DocumentCopy, path: '/leave/manage', color: '#f093fb' },
  { title: '用户管理', icon: User, path: '/system/user', color: '#4facfe' },
  { title: '系统设置', icon: Setting, path: '/system/role', color: '#43e97b' }
]

// 根据用户菜单权限过滤快捷操作
const quickActions = computed(() => {
  if (!userMenus.value || userMenus.value.length === 0) {
    return []
  }
  
  // 收集所有用户有权访问的路径
  const allowedPaths = new Set()
  const collectPaths = (menus) => {
    menus.forEach(menu => {
      if (menu.path) {
        allowedPaths.add(menu.path)
      }
      if (menu.children && menu.children.length > 0) {
        collectPaths(menu.children)
      }
    })
  }
  collectPaths(userMenus.value)
  
  // 只显示用户有权限的快捷操作
  return allQuickActions.filter(action => allowedPaths.has(action.path))
})

const daysInCompany = computed(() => {
  if (!userInfo.value.entryDate) return 0
  const entryDate = new Date(userInfo.value.entryDate)
  const today = new Date()
  const diffTime = Math.abs(today - entryDate)
  const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24))
  return diffDays
})

const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

const navigateTo = (path) => {
  router.push(path)
}

const loadUserInfo = async () => {
  try {
    const userId = userStore.userId
    if (!userId) return
    
    const res = await request.get(`/system/user/${userId}`)
    userInfo.value = res
  } catch (e) {
    console.error('Failed to load user info:', e)
  }
}

const getDailyQuote = async () => {
  try {
    const userId = userStore.userId
    const apiUrl = userId == 6
      ? 'https://api.oick.cn/api/dog?apikey=4fb8e9f4b65be468e0cdbe226701a370'
      : 'https://api.oick.cn/api/dutang?apikey=4ca862840fce3181c75d8d029f0876ec'
    
    const response = await fetch(apiUrl)
    if (response.ok) {
      const data = await response.json()
      dailyQuote.value = data
    }
  } catch (e) {
    console.error('Failed to fetch daily quote:', e)
  }
}

const loadUserMenus = async () => {
  // 直接从Store获取菜单数据，不再发起请求
  if (userStore.userMenus && userStore.userMenus.length > 0) {
    userMenus.value = userStore.userMenus
  } else {
    try {
      const userId = userStore.userId
      if (!userId) return
      
      const menus = await request.get('/system/menu/user-menus', {
        params: { userId }
      })
      userStore.setUserMenus(menus)
      userMenus.value = menus
    } catch (e) {
      console.error('Failed to load user menus:', e)
    }
  }
}


onMounted(() => {
  loadUserInfo()
  loadUserMenus()
  getDailyQuote()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
.home-container {
  max-width: 1200px;
  margin: 0 auto;
}

.welcome-section {
  margin-bottom: 30px;
}

.welcome-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 16px;
  padding: 40px;
  color: white;
  box-shadow: 0 10px 30px rgba(102, 126, 234, 0.3);
  position: relative;
  overflow: hidden;
  margin-bottom: 30px;
}

.welcome-card::before {
  content: '';
  position: absolute;
  top: -50%;
  right: -10%;
  width: 300px;
  height: 300px;
  background: radial-gradient(circle, rgba(255,255,255,0.1) 0%, transparent 70%);
  border-radius: 50%;
}

.greeting {
  position: relative;
  z-index: 1;
}

.greeting h1 {
  margin: 0 0 8px 0;
  font-size: 32px;
  font-weight: 600;
}

.welcome-text {
  margin: 0;
  font-size: 16px;
  opacity: 0.9;
}

.daily-quote {
  margin-top: 16px;
  padding: 12px 16px;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  font-style: italic;
  font-size: 14px;
  line-height: 1.6;
  opacity: 0.95;
}

.stats-section {
  margin-bottom: 30px;
}

.stat-card {
  background: white;
  border-radius: 12px;
  padding: 24px;
  display: flex;
  align-items: center;
  gap: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  transition: all 0.3s;
}

.stat-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}

.stat-icon {
  width: 60px;
  height: 60px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
}

.stat-content {
  flex: 1;
}

.stat-value {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
}

.quick-actions {
  margin-top: 30px;
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 20px 0;
}

.action-card {
  background: white;
  border-radius: 12px;
  padding: 30px 20px;
  text-align: center;
  cursor: pointer;
  transition: all 0.3s;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}

.action-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.15);
}

.action-title {
  margin-top: 12px;
  font-size: 15px;
  font-weight: 500;
  color: #303133;
}

@media screen and (max-width: 768px) {
  .home-container {
    padding: 0 5px;
  }
  
  .welcome-card {
    padding: 24px 20px;
    margin-bottom: 20px;
  }
  
  .greeting h1 {
    font-size: 22px;
  }
  
  .stat-card {
    padding: 16px;
    margin-bottom: 12px;
  }
  
  .stat-icon {
    width: 48px;
    height: 48px;
  }
  
  .stat-value {
    font-size: 18px;
  }
  
  .action-card {
    padding: 20px 10px;
  }
  
  .action-title {
    font-size: 13px;
  }
}
</style>
