import axios from 'axios'
import * as dd from 'dingtalk-jsapi'

/** 钉钉容器的 UA 里带 DingTalk */
export const isDingTalk = () => /DingTalk/i.test(navigator.userAgent)

/**
 * 钉钉免密登录: 取 corpId -> 向钉钉要 authCode -> 交给后端换 JWT。
 * 成功返回 { token, userId, username }。
 *
 * client 是带 get/post、直接返回业务数据的对象:
 * 登录页传 request (出错会弹提示), 令牌过期后的静默续登传 silentClient (出错只抛异常, 什么都不弹)。
 */
export async function dingtalkLogin(client) {
  const { corpId } = await client.get('/auth/config/dingtalk')
  if (!corpId) {
    throw new Error('后端未配置钉钉 CorpId')
  }

  const { code } = await dd.runtime.permission.requestAuthCode({ corpId })
  if (!code) {
    throw new Error('获取授权码失败')
  }

  return client.post('/auth/dingtalk/login', { code })
}

/**
 * 静默续登用的客户端。
 *
 * 不能用 request.js 里的实例: 续登发生在它的 401 拦截器里, 走同一个实例出了错会绕回拦截器,
 * 还会弹出用户看不懂的红色报错。这里的失败由调用方统一按「登录已过期」处理。
 */
const bare = axios.create({
  baseURL: import.meta.env.PROD ? '' : '/api', // 与 request.js 保持一致
  timeout: 10000
})

/** 后端约定: HTTP 200 + { code, data, message }, code 不是 200 就是业务错误 */
const unwrap = (res) => {
  const body = res.data
  if (body?.code === undefined) return body
  if (body.code === 200) return body.data
  throw new Error(body.message || '请求失败')
}

export const silentClient = {
  get: async (url) => unwrap(await bare.get(url)),
  post: async (url, data) => unwrap(await bare.post(url, data))
}
