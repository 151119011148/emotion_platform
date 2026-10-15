import { ref } from 'vue'

/**
 * 手机档判定（窄屏）。全站共用**一份**状态，监听只挂在模块上注册一次。
 *
 * <p>为什么用 matchMedia 而不是 window.resize：跨断点才回调，桌面上拖动窗口
 * 只要没跨过 768 就一次都不触发重渲染；resize 是每帧都来。
 *
 * <p>断点 768 的取法：iPhone SE 375、iPhone 15 393、Pixel 412、横屏的小平板另算。
 * 定在 768 意味着「竖屏手机」走窄屏档，iPad 竖屏（768）仍按桌面档给全宽表格。
 * **各页的 `@media (max-width: 768px)` 必须跟这个数字一致**——模板分叉读这里、
 * 样式分叉读媒体查询，两个数不一样就会出现「卡片视图配桌面内边距」这种半套状态。
 */
export const NARROW_MAX_PX = 768

const isNarrow = ref(false)

if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
  const mq = window.matchMedia(`(max-width: ${NARROW_MAX_PX}px)`)
  isNarrow.value = mq.matches
  const sync = (e) => { isNarrow.value = e.matches }
  // addEventListener 是现在的标准；addListener 只给老 Safari（iOS 12 及以前）兜底，
  // 少这一句在那些机器上「转屏不切换视图」，页面看着像没适配。
  if (typeof mq.addEventListener === 'function') mq.addEventListener('change', sync)
  else if (typeof mq.addListener === 'function') mq.addListener(sync)
}

export function useNarrow() {
  return { isNarrow }
}
