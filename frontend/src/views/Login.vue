<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="brand-mark">
          <el-icon :size="20"><Calendar /></el-icon>
        </div>
        <h1>年假管理</h1>
      </div>

      <!-- 钉钉容器内自动登录中: 不给表单, 免得用户以为要手输账号 -->
      <div v-if="ddLoggingIn" class="dd-state">
        <el-icon class="dd-spin"><Loading /></el-icon>
        <span>正在通过钉钉登录…</span>
      </div>

      <template v-else>
        <div v-if="ddFailed" class="dd-failed">
          钉钉登录失败，请用账号密码登录
        </div>

        <el-form :model="form" class="login-form" @keyup.enter="handleLogin">
          <el-form-item>
            <el-input
              v-model="form.username"
              placeholder="用户名"
              :prefix-icon="User"
              size="large"
              autocomplete="username"
            />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              :prefix-icon="Lock"
              size="large"
              show-password
              autocomplete="current-password"
            />
          </el-form-item>
          <el-button
            type="primary"
            class="submit"
            size="large"
            :loading="loading"
            @click="handleLogin"
          >
            登录
          </el-button>
        </el-form>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock, Calendar, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { useUserStore } from '../stores/user'
import * as dd from 'dingtalk-jsapi'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const ddLoggingIn = ref(false)
const ddFailed = ref(false)

const form = ref({
  username: '',
  password: ''
})

const isDingTalk = () => /DingTalk/i.test(navigator.userAgent)

const handleDingTalkLogin = async () => {
  if (!isDingTalk()) return

  try {
    ddLoggingIn.value = true

    const config = await request.get('/auth/config/dingtalk')
    const corpId = config.corpId
    if (!corpId) {
      throw new Error('后端未配置钉钉 CorpId')
    }

    const { code } = await dd.runtime.permission.requestAuthCode({ corpId })
    if (!code) {
      throw new Error('获取授权码失败')
    }

    const res = await request.post('/auth/dingtalk/login', { code })
    userStore.setLoginState(res.token, res.userId, res.username)
    router.push('/')
  } catch (e) {
    // 失败就静默退回密码登录, 不再弹一个用户看不懂的红色报错
    console.error('DingTalk SSO failed:', e)
    ddFailed.value = true
  } finally {
    ddLoggingIn.value = false
  }
}

const handleLogin = async () => {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }

  try {
    loading.value = true
    const res = await request.post('/auth/login', form.value)
    userStore.setLoginState(res.token, res.userId, res.username)
    router.push('/')
  } catch (e) {
    console.error(e)
    ElMessage.error('用户名或密码不正确')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (isDingTalk() && !userStore.token) {
    handleDingTalkLogin()
  }
})
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--bg-page);
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 32px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 28px;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--radius);
  background: var(--brand);
  color: var(--text-inverse);
  flex-shrink: 0;
}

.brand h1 {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.dd-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 32px 0;
  font-size: 14px;
  color: var(--text-secondary);
}

.dd-spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.dd-failed {
  margin-bottom: 16px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--warning);
  background: var(--warning-subtle);
  border-radius: var(--radius-sm);
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.submit {
  width: 100%;
  margin-top: 4px;
  font-size: 15px;
}

@media screen and (max-width: 767px) {
  .login-page {
    align-items: flex-start;
    padding: 0;
    background: var(--bg-surface);
  }

  .login-card {
    max-width: none;
    min-height: 100dvh;
    padding: 48px 24px calc(24px + env(safe-area-inset-bottom));
    border: none;
    border-radius: 0;
    box-shadow: none;
  }

  .brand {
    margin-bottom: 32px;
  }
}
</style>
