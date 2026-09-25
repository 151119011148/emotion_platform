import { reactive } from 'vue'

/**
 * 曲线坐标轴缩放/平移状态。
 *
 * <p>窗口由调用方「切片」实现，而不是挂 ECharts dataZoom：dataZoom 的 filter 模式会同时
 * 改变轴上可见的下标语义（markPoint 的 coord 指向被滤掉的类目、tooltip 的 dataIndex 与
 * 点击换算像素→下标各按各的口径），而切完之后轴类目、系列数据、标记、下标全是同一份数组，
 * 少一类「缩放了就点不动/标不出来」的隐性 bug。
 *
 * <p>窗口用「数据下标闭区间」而不是百分比表达：百分比换算要按轴长取整，缩到小数档位时
 * 会出现按了 + 却没变化、或多退一档就把窗口清空的问题。
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

/**
 * @param {() => number} countOf 当前数据点总数（要写成函数，好跟着 props 变）
 * @param {number} initialSpan 未操作时的默认窗口宽度
 */
export function useCurveZoom(countOf, initialSpan = DEFAULT_SPAN) {
  // touched=false 表示还没人动过按钮：窗口恒锚在最新一天、只取最近 initialSpan 个。
  // 不叫 full 是因为「默认」已不等于「全部」；换数据长度时（近20日→全部）跟着重算，
  // 而不是把旧下标钳成新数据的前若干天，那会让人以为缩放没生效。
  const win = reactive({ touched: false, i0: 0, i1: -1, n: -1 })

  function total() {
    return Math.max(0, countOf() | 0)
  }

  /** 默认窗口：最近 initialSpan 个点，数据本身不够长就全给 */
  function defaultRange() {
    const n = total()
    if (!n) return [0, -1]
    return [n - Math.min(initialSpan, n), n - 1]
  }

  /** 可见闭区间 [a,b]；无数据时为 [0,-1] */
  function range() {
    const n = total()
    if (!win.touched) return defaultRange()
    let [a, b] = [win.i0, win.i1]
    // 数据条数变了（仪表盘切近20日/近60日/全部）：保住窗口宽度、改锚在最新一天。
    // 直接沿用旧下标会落到另一段日期上——缩放在 9 月，换完范围却变成看 7 月。
    // 这里只做纯读换算，不在渲染期回写响应式状态。
    if (win.n !== n && n > 0) {
      const w = Math.max(1, Math.min(b - a + 1, n))
      b = n - 1
      a = b - w + 1
    }
    a = Math.min(Math.max(0, a), Math.max(0, n - 1))
    b = Math.min(Math.max(b, a), Math.max(0, n - 1))
    return [a, b]
  }

  function apply(a, b) {
    const n = total()
    win.i0 = a
    win.i1 = b
    win.n = n
    const [d0, d1] = defaultRange()
    win.touched = !(a === d0 && b === d1)
  }

  /** 以窗口中点为锚放到 ns 宽，越界时整体往回收，保证宽度不变 */
  function centerOn(ns) {
    const n = total()
    const [a, b] = range()
    const width = Math.max(1, Math.min(ns, n))
    let na = Math.round((a + b) / 2 - (width - 1) / 2)
    na = Math.max(0, Math.min(na, n - width))
    apply(na, na + width - 1)
  }

  function zoomIn() {
    const [a, b] = range()
    const span = b - a + 1
    centerOn(Math.max(MIN_SPAN, Math.min(span - 1, Math.floor(span * ZOOM_RATIO))))
  }

  function zoomOut() {
    const [a, b] = range()
    const span = b - a + 1
    centerOn(Math.max(span + 1, Math.ceil(span / ZOOM_RATIO)))
  }

  /** dir=-1 左移看更早的数据，dir=+1 右移看更新的 */
  function pan(dir) {
    const n = total()
    const [a, b] = range()
    const span = b - a + 1
    const step = Math.max(1, Math.round(span * PAN_RATIO))
    const na = Math.max(0, Math.min(a + dir * step, n - span))
    apply(na, na + span - 1)
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
