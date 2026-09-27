import { reactive } from 'vue'

/**
 * 曲线坐标轴缩放/平移状态。
 *
 * <p>窗口由调用方「切片」实现，而不是挂 ECharts dataZoom：dataZoom 的 filter 模式会同时
 * 改变轴上可见的下标语义（markPoint 的 coord 指向被滤掉的类目、tooltip 的 dataIndex 与
 * 点击换算像素→下标各按各的口径），而切完之后轴类目、系列数据、标记、下标全是同一份数组，
 * 少一类「缩放了就点不动/标不出来」的隐性 bug。
 *
 * <p>窗口 = 「离最新一天还差几个点」(back) + 「窗口宽度」(span)，不是绝对下标闭区间。
 * 因为两条联动曲线的条数不一样（生态分 390 天、连板高度 299 天，都到同一天为止），
 * 按下标对齐会把两条曲线切到两段不同的日期上；按「离末尾多远」对齐才是同一个交易日窗口。
 * 顺带一条：换数据长度（重新拉了更长/更短的区间）时窗口天然还锚在最新一天，
 * 不需要再判一次「条数变了就重锚」。
 *
 * <p>只由按钮驱动，不接滚轮和拖拽：滚轮缩放会抢掉长页面本来就在要的上下滚动，
 * 拖拽平移会和「点图上任意一天＝切换日期」这条主交互抢同一次鼠标按下。
 */

/** 每按一次 +：窗口收窄到 75%（至少减 1 个点，否则连着按会没反应） */
const ZOOM_RATIO = 0.75
/** 每按一次 ≪/≫：平移当前窗口的 25%（缩到最小时等价于挪 1 天） */
const PAN_RATIO = 0.25
/** 最少可见点数：再少就只剩三五个点，曲线形状读不出来了 */
export const MIN_SPAN = 6
/** 没动过按钮时默认可见的交易日数。留头寸，−/≪ 才不是永远点不动的死键 */
export const DEFAULT_SPAN = 20

/** groupId → 共享窗口。同一组曲线各自的 ±/≪≫ 按钮改的是同一份状态。 */
const groups = new Map()
function groupState(groupId) {
  let s = groups.get(groupId)
  if (!s) {
    s = reactive({ back: 0, span: 0 })
    groups.set(groupId, s)
  }
  return s
}

/**
 * @param {() => number} countOf 当前数据点总数（要写成函数，好跟着 props 变）
 * @param {number} initialSpan 未操作时的默认窗口宽度
 * @param {string} groupId 联动组名；同名的曲线一起缩放。不传就是自己管自己。
 */
export function useCurveZoom(countOf, initialSpan = DEFAULT_SPAN, groupId = '') {
  // span=0 表示还没人动过按钮：窗口恒锚在最新一天、只取最近 initialSpan 个。
  // 窗口宽度上限是**这条曲线自己**的条数，不按组取最小值：生态分有 390 天、高度只有 299 天，
  // 按最小值封顶会让分数曲线再也翻不到 2025-01 那段回补出来的历史。
  const win = groupId ? groupState(groupId) : reactive({ back: 0, span: 0 })

  function total() {
    return Math.max(0, countOf() | 0)
  }

  function defaultSpan() {
    return Math.min(initialSpan, total())
  }

  /** 可见闭区间 [a,b]；无数据时为 [0,-1]。纯读换算，不在渲染期回写响应式状态。 */
  function range() {
    const n = total()
    if (!n) return [0, -1]
    const s = Math.max(1, Math.min(win.span || defaultSpan(), n))
    const b = n - 1 - Math.max(0, Math.min(win.back, n - s))
    return [Math.max(0, b - s + 1), b]
  }

  function apply(back, s) {
    const width = Math.max(1, s)
    win.back = Math.max(0, Math.min(back, Math.max(0, total() - width)))
    // 正好落回「锚最新 + 默认宽度」时清成未操作态，让下一次换数据长度还跟着重算宽度
    win.span = win.back === 0 && width === defaultSpan() ? 0 : width
  }

  /** 以窗口中点为锚放到 ns 宽，越界时整体往回收 */
  function setSpan(ns) {
    const n = total()
    const [a, b] = range()
    const width = Math.max(1, Math.min(ns, n))
    apply(n - 1 - b + Math.round((b - a + 1 - width) / 2), width)
  }

  function zoomIn() {
    const [a, b] = range()
    const span = b - a + 1
    setSpan(Math.max(MIN_SPAN, Math.min(span - 1, Math.floor(span * ZOOM_RATIO))))
  }

  function zoomOut() {
    const [a, b] = range()
    const span = b - a + 1
    setSpan(Math.max(span + 1, Math.ceil(span / ZOOM_RATIO)))
  }

  /** dir=-1 左移看更早的数据，dir=+1 右移看更新的 */
  function pan(dir) {
    const n = total()
    const [a, b] = range()
    const span = b - a + 1
    const step = Math.max(1, Math.round(span * PAN_RATIO))
    apply(n - 1 - b - dir * step, span)
  }

  function span() {
    const [a, b] = range()
    return b - a + 1
  }

  function canZoomIn() {
    return total() > MIN_SPAN && span() > MIN_SPAN
  }

  function canZoomOut() {
    return span() < total()
  }

  function canPanLeft() {
    return range()[0] > 0
  }

  function canPanRight() {
    const n = total()
    return n > 0 && range()[1] < n - 1
  }

  /** 可见切片：调用方据此重算 Y 轴量程，让放大真的把波动摊开 */
  function visible(arr) {
    const [a, b] = range()
    return (arr || []).slice(a, b + 1)
  }

  return {
    state: win,
    range,
    span,
    zoomIn,
    zoomOut,
    pan,
    visible,
    canZoomIn,
    canZoomOut,
    canPanLeft,
    canPanRight
  }
}
