import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import { isDingTalk, dingtalkLogin, silentClient } from './dingtalk'

const service = axios.create({
    // In production (All-in-One), backend serves directly at root, so no /api prefix needed.
    // In dev, /api triggers the Vite proxy to rewrite path.
    baseURL: import.meta.env.PROD ? '' : '/api',
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

/**
 * 登录过期只处理一次。
 *
 * 一个页面往往同时发出好几个请求(首页一进来就是 4 个), 令牌一过期它们会一起返回 401,
 * 逐个处理就是弹出 N 条一模一样的提示、排上 N 个跳转定时器。
 * 跳转是整页刷新, 标志位随页面一起重置, 不需要手动复位。
 */
let sessionExpiredHandled = false

const handleSessionExpired = () => {
    if (sessionExpiredHandled) return
    sessionExpiredHandled = true

    localStorage.removeItem('token')
    localStorage.removeItem('userId')
    localStorage.removeItem('username')

    ElMessage.error({
        message: '登录已过期，请重新登录',
        duration: 5000,
        showClose: true
    })
    setTimeout(() => {
        window.location.href = '/login'
    }, 1500)
}

/** 这个请求当时带的令牌 (请求拦截器塞进 Authorization 头里的那个) */
const bearerOf = (config) => {
    const h = config.headers
    const value = (h && (typeof h.get === 'function' ? h.get('Authorization') : h.Authorization)) || ''
    return String(value).replace(/^Bearer /, '')
}

/**
 * 钉钉里令牌过期不打扰用户: 身份本来就来自钉钉, 过期了再走一遍免密登录换新令牌就行。
 *
 * 并发的 401 共用同一次续登 —— 否则首页那 4 个请求会各向钉钉要一次授权码、各换一次令牌。
 * 失败(用户没绑定、钉钉取码失败、网络断了)时由调用方回落到「登录已过期」的常规流程。
 */
let renewing = null

const renewDingTalkSession = () => {
    if (!renewing) {
        renewing = dingtalkLogin(silentClient)
            .then(({ token, userId, username }) => {
                // 走 store 而不是直接写 localStorage: 两边都要更新, 否则 store 里还是过期的旧令牌
                useUserStore().setLoginState(token, userId, username)
            })
            .finally(() => {
                renewing = null
            })
    }
    return renewing
}

// Response interceptor
service.interceptors.response.use(
    response => {
        const res = response.data

        // 如果是Result格式（有code字段）
        if (res.code !== undefined) {
            if (res.code === 200) {
                return res.data  // 返回data字段
            } else {
                // 调用方声明自己会提示时不再叠一条 (例如登录页要把 Spring 的「Bad credentials」换成一句人话)。
                // 只管业务错误: 网络断了、HTTP 5xx 这类没有调用方文案兜底的, 仍由下面的 error 分支提示
                if (!response.config?.silentBusinessError) {
                    ElMessage.error(res.message || '请求失败')
                }
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

        if (status === 401) {
            // 已经在走「登录已过期」流程(马上要跳转)了, 后到的 401 不再续登、不再提示
            if (sessionExpiredHandled) return new Promise(() => {})

            // 钉钉里: 静默续登, 然后把这个请求重发一次。每个请求只续一次(标记随 config 带到重发的请求上),
            // 重发后还是 401 就说明续来的令牌也不被接受, 按过期处理, 不会循环。
            const config = error.config
            if (isDingTalk() && config && !config._dingTalkRenewed) {
                config._dingTalkRenewed = true
                // 请求带的是旧令牌、而令牌已经被别的请求换过了(慢请求的 401 比续登回来得晚):
                // 不必再续一次, 直接用新令牌重发
                const current = localStorage.getItem('token')
                const renewed = current && current !== bearerOf(config)
                return (renewed ? Promise.resolve() : renewDingTalkSession()).then(
                    () => service(config),
                    (e) => {
                        console.warn('DingTalk silent re-login failed:', e)
                        handleSessionExpired()
                        return new Promise(() => {})
                    }
                )
            }

            // 其它情况 - 清除登录信息并跳转。
            // 请求保持挂起而不是 reject: 页面马上要跳走, 而各页面的 catch 里还会再弹一条
            // 「数据加载失败」之类的提示, 叠在「登录已过期」上面, 说的还是同一件事。
            handleSessionExpired()
            return new Promise(() => {})
        }

        // 构建详细错误消息
        let displayMessage = ''

        if (status === 403) {
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
