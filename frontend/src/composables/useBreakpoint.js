import { ref, readonly } from 'vue'

/**
 * 全站统一的响应式断点。
 *
 * 改版前 Layout / Home / MyLeave 各自写了一份
 * `screenWidth + resize 监听 + onUnmounted 移除`, 三份阈值还都是硬编码的 768。
 * 这里用 matchMedia 只订阅一次断点跨越事件, 不在每次 resize 上跑回调。
 */

const MOBILE_QUERY = '(max-width: 767px)'

const mql = typeof window !== 'undefined' ? window.matchMedia(MOBILE_QUERY) : null

const isMobile = ref(mql ? mql.matches : false)

if (mql) {
  const onChange = (e) => {
    isMobile.value = e.matches
  }
  // Safari < 14 只有 addListener
  if (mql.addEventListener) {
    mql.addEventListener('change', onChange)
  } else {
    mql.addListener(onChange)
  }
}

export function useBreakpoint() {
  return { isMobile: readonly(isMobile) }
}
