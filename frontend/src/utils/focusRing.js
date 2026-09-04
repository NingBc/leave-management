/**
 * 只在键盘操作时显示焦点环。
 *
 * Element Plus 的 `.el-button:focus-visible` 会画一圈 2px outline。鼠标点开对话框或下拉后,
 * 组件关闭时会把焦点「还」给触发它的按钮 —— 那是一次编程式 focus, Chrome 判定为 focus-visible,
 * 于是按钮上凭空多出一个框, 看着像渲染 bug(点「记录」看完弹窗关掉就会留一个)。
 *
 * 直接 `outline: none` 会把键盘用户的焦点指示一并抹掉, 所以这里按最近一次输入方式来决定:
 * 鼠标/触摸操作后不显示, 一按 Tab 立刻恢复。
 */
export function setupFocusRing() {
  const body = document.body
  const useMouse = () => body.classList.add('using-mouse')
  const useKeyboard = (e) => {
    if (e.key === 'Tab') body.classList.remove('using-mouse')
  }

  document.addEventListener('mousedown', useMouse, true)
  document.addEventListener('touchstart', useMouse, { capture: true, passive: true })
  document.addEventListener('keydown', useKeyboard, true)
}
