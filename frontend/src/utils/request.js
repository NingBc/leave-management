import axios from 'axios'
import { ElMessage } from 'element-plus'

const service = axios.create({
    baseURL: '/api',
    timeout: 10000
})

// Request interceptor
service.interceptors.request.use(
    config => {
        const token = localStorage.getItem('token')
        if (token) {
            config.headers['Authorization'] = 'Bearer ' + token
        }
        return config
    },
    error => {
        return Promise.reject(error)
    }
)

// Response interceptor
service.interceptors.response.use(
    response => {
        const res = response.data

        // 如果是Result格式（有code字段）
        if (res.code !== undefined) {
            if (res.code === 200) {
                return res.data  // 返回data字段
            } else {
                ElMessage.error(res.message || '请求失败')
                return Promise.reject(new Error(res.message || 'Error'))
            }
        }

        // 兼容旧格式（直接返回数据）
        return res
    },
    error => {
        console.error('API Error:', error)

        // 提取错误信息
        const status = error.response?.status
        const errorMessage = error.response?.data?.message || error.response?.data?.error || error.message
        const errorDetail = error.response?.data?.details || ''

        // 构建详细错误消息
        let displayMessage = ''

        if (status === 401) {
            // 未认证 - 清除登录信息并跳转
            displayMessage = '登录已过期，请重新登录'
            localStorage.removeItem('token')
            localStorage.removeItem('userId')
            localStorage.removeItem('username')
            setTimeout(() => {
                window.location.href = '/login'
            }, 1500)
        } else if (status === 403) {
            // 无权限 - 显示错误但不退出
            displayMessage = `权限不足: ${errorMessage}`
        } else if (status === 500) {
            // 服务器错误 - 显示详细信息
            displayMessage = `服务器错误: ${errorMessage}`
            if (errorDetail) {
                displayMessage += `\n详细信息: ${errorDetail}`
            }
            // 如果是数据库错误，给出提示
            if (errorMessage.includes('Unknown column') || errorMessage.includes('SQLSyntaxErrorException')) {
                displayMessage += '\n\n💡 提示：可能需要执行数据库迁移脚本'
            }
        } else if (status === 400) {
            // 客户端错误
            displayMessage = `请求错误: ${errorMessage}`
        } else {
            // 其他错误
            displayMessage = `请求失败 (${status || '网络错误'}): ${errorMessage}`
        }

        // 显示错误提示
        ElMessage.error({
            message: displayMessage,
            duration: 5000,
            showClose: true
        })

        return Promise.reject(error)
    }
)

export default service
