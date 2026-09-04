<template>
  <div class="profile">
    <!-- 身份 -->
    <section class="id-card surface">
      <div class="avatar">{{ avatarText }}</div>
      <div class="id-text">
        <div class="name">{{ user.realName || userStore.username || '未知用户' }}</div>
        <div class="meta">
          <span v-if="user.employeeNumber">工号 {{ user.employeeNumber }}</span>
          <span v-if="user.username">@{{ user.username }}</span>
        </div>
      </div>
    </section>

    <!-- 档案: 这几项直接决定年假档位, 员工看到错了能及时找 HR 改 -->
    <h3 class="section-title">我的档案</h3>
    <section class="list surface">
      <div class="row">
        <span class="row-label">
          入职本公司
          <FieldHint :text="FIELD.entryDate.hint" />
        </span>
        <span class="row-value num">{{ user.entryDate || '—' }}</span>
      </div>
      <div class="row">
        <span class="row-label">司龄</span>
        <span class="row-value num">{{ tenureText }}</span>
      </div>
      <div class="row">
        <span class="row-label">
          首次参加工作
          <FieldHint :text="FIELD.firstWorkDate.hint" />
        </span>
        <span class="row-value num">{{ user.firstWorkDate || '—' }}</span>
      </div>
      <div class="row">
        <span class="row-label">
          {{ FIELD.socialSeniority.label }}
          <FieldHint :text="FIELD.socialSeniority.hint" />
        </span>
        <span class="row-value num">{{ user.socialSeniority ?? 0 }} 年</span>
      </div>
    </section>

    <p class="note">档案由 HR 维护，有误请联系 HR 更正</p>

    <!-- 操作 -->
    <h3 class="section-title">账号</h3>
    <section class="list surface">
      <button type="button" class="row row-action" @click="passwordVisible = true">
        <span class="row-label"><el-icon><Lock /></el-icon>修改密码</span>
        <el-icon class="chev"><ArrowRight /></el-icon>
      </button>
      <button type="button" class="row row-action danger" @click="confirmLogout">
        <span class="row-label"><el-icon><SwitchButton /></el-icon>退出登录</span>
        <el-icon class="chev"><ArrowRight /></el-icon>
      </button>
    </section>

    <ChangePasswordDialog v-model="passwordVisible" @success="logout" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Lock, SwitchButton, ArrowRight } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import request from '../utils/request'
import { useUserStore } from '../stores/user'
import { FIELD } from '../constants/leave'
import { daysSince, humanizeDuration } from '../utils/date'
import ChangePasswordDialog from '../components/ChangePasswordDialog.vue'
import FieldHint from '../components/FieldHint.vue'

const router = useRouter()
const userStore = useUserStore()
const user = ref({})
const passwordVisible = ref(false)

const avatarText = computed(() => {
  const n = user.value.realName || userStore.username || '?'
  return n.slice(0, 1).toUpperCase()
})

/** 司龄: 入职本公司至今。和年假账户里的「今年在职天数」不是一回事, 所以单列一行 */
const tenureText = computed(() => humanizeDuration(daysSince(user.value.entryDate)))

const loadUser = async () => {
  try {
    const userId = userStore.userId
    if (!userId) return
    user.value = await request.get(`/system/user/${userId}`)
  } catch (e) {
    console.error('Failed to load user info:', e)
  }
}

const confirmLogout = async () => {
  try {
    await ElMessageBox.confirm('确定退出登录？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
    logout()
  } catch {
    /* 用户取消 */
  }
}

const logout = () => {
  userStore.logout()
  router.push('/login')
}

onMounted(loadUser)
</script>

<style scoped>
.profile {
  max-width: 560px;
  margin: 0 auto;
}

.id-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  margin-bottom: 24px;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--brand-subtle);
  color: var(--brand);
  font-size: 20px;
  font-weight: 600;
  flex-shrink: 0;
}

.name {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-primary);
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 2px;
  font-size: 13px;
  color: var(--text-muted);
}

.list {
  overflow: hidden;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding: 13px 16px;
  border-bottom: 1px solid var(--border);
  font-size: 14px;
  text-align: left;
}

.row:last-child {
  border-bottom: none;
}

.row-label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-secondary);
}

.row-value {
  color: var(--text-primary);
  font-weight: 500;
}

.row-action {
  border-left: none;
  border-right: none;
  border-top: none;
  background: none;
  font-family: inherit;
  cursor: pointer;
  transition: background var(--ease);
}

.row-action:hover {
  background: var(--bg-hover);
}

.row-action.danger .row-label {
  color: var(--danger);
}

.chev {
  color: var(--text-placeholder);
  font-size: 13px;
}

.note {
  margin: 10px 2px 24px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
</style>
