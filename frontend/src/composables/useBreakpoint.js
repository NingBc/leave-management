import { ref, readonly } from 'vue'

/**
 * 全站统一的响应式断点。
 *
 * 改版前 Layout / Home / MyLeave 各自写了一份
 * `screenWidth + resize 监听 + onUnmounted 移除`, 三份阈值还都是硬编码的 768。
 * 这里用 matchMedia 只订阅一次断点跨越事件, 不在每次 resize 上跑回调。
 */

/** 订阅一个媒体查询, 返回随之变化的布尔 ref */
function track(query) {
  const mql = typeof window !== 'undefined' ? window.matchMedia(query) : null
  const state = ref(mql ? mql.matches : false)

  if (mql) {
    const onChange = (e) => {
      state.value = e.matches
    }
    // Safari < 14 只有 addListener
    if (mql.addEventListener) {
      mql.addEventListener('change', onChange)
    } else {
      mql.addListener(onChange)
    }
  }
  return state
}

const isMobile = track('(max-width: 767px)')

/**
 * 较窄的桌面窗口 (768 ~ 1439px)。
 *
 * 侧栏 216px + 内容区左右各 24px 边距, 表格可用宽度只有「视口 − 264」。
 * 年假管理表格的全部列要 1150px 上下, 1280 的笔记本上「当前可休」会被固定的操作列盖住,
 * 表头也在词中间折行。到这个宽度就收起两列参考信息, 让关键列留在首屏。
 */
const isNarrow = track('(max-width: 1439px)')

export function useBreakpoint() {
  return { isMobile: readonly(isMobile), isNarrow: readonly(isNarrow) }
}
