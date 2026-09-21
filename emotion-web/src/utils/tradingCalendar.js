/**
 * 交易日历（共享）：
 * 从后端 /review/trading-days 拉取「可复盘交易日」集合，供全站所有日期控件统一：
 *   - 非交易日（周末 + 官方休市日）置灰不可选；
 *   - 默认选最近一个交易日。
 * 集合是应用级单例，首个 view 加载一次即缓存，避免每个页面重复请求。
 */
import { ref } from 'vue'
import { reviewApi } from '../api/modules'

const tradingDays = ref([])
let loaded = false
let loading = null

const pad = (n) => String(n).padStart(2, '0')

function keyOf(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

/** 今天（本地时区）YYYY-MM-DD。 */
function todayKey() {
  return keyOf(new Date())
}

/** 是否非交易日：周末恒非；交易日历在手时不在集合里的也算（用于拉取前兜底拦截）。 */
function isNonTrading(dstr) {
  const dow = new Date(dstr + 'T00:00:00').getDay()
  if (dow === 0 || dow === 6) return true
  return tradingDays.value.length ? !tradingDays.value.includes(dstr) : false
}

/**
 * 单元格性质，供日期面板做样式区分：
 *   'non-trading' —— 非交易日（周末恒非；日历已加载的工作日里不在集合里的=官方休市）
 *   'future'      —— 还没到的交易日（今天之后的工作日，数据尚未产生，点了也拉不到东西）
 *   'trading'     —— 正常可选交易日
 * 注意后端只给到「今天」为止，未来某天是否休市无从判断，所以未来工作日一律按「未到」处理。
 */
function dayState(d) {
  const dow = d.getDay()
  const k = keyOf(d)
  if (k > todayKey()) return dow === 0 || dow === 6 ? 'non-trading' : 'future'
  if (dow === 0 || dow === 6) return 'non-trading'
  const days = tradingDays.value
  if (!days.length) return 'trading'
  // 早于日历覆盖范围（后端只回近 3 个月）的日子：性质未知，不贴标签，只按默认置灰处理
  if (k < days[days.length - 1]) return 'unknown'
  return days.includes(k) ? 'trading' : 'non-trading'
}

/** el-date-picker 的 cell-class-name：把上面三种性质落成 td 上的 class，样式见 App.vue 全局段。 */
function cellClass(d) {
  const s = dayState(d)
  if (s === 'non-trading') return 'day-non-trading'
  if (s === 'future') return 'day-future'
  return ''
}

/** el-date-picker 的 disabled-date：周末恒灰；交易日历在手时不在集合里的全灰。 */
function disabledDate(d) {
  const dow = d.getDay()
  if (dow === 0 || dow === 6) return true
  if (tradingDays.value.length) return !tradingDays.value.includes(keyOf(d))
  return false
}

/** 取最接近传入日(含)的最近交易日；传入省略则取整个集合第一个（全局最近交易日）。 */
function latestTradingDay(dstr) {
  if (!dstr) return tradingDays.value[0] || null
  return tradingDays.value.find((d) => d <= dstr) || tradingDays.value[0] || null
}

/** 本地兜底：即便交易日集合未加载成功，也返回 ≤传入日(默认今天) 的最近一个非周末工作日。 */
function fallbackRecentTradingDay(dstr) {
  let d = dstr ? new Date(dstr + 'T00:00:00') : new Date()
  if (isNaN(d.getTime())) d = new Date()
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() - 1)
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

/** 加载交易日历。成功则单例缓存；失败不置 loaded，避免「空集合」被永久缓存污染全局。
 *  失败时返回空集合（仅周末置灰），并允许下次调用重试恢复。 */
function loadTradingDays() {
  if (loaded) return Promise.resolve(tradingDays)
  if (loading) return loading
  loading = reviewApi
    .tradingDays()
    .then((r) => {
      const list = r?.data || []
      tradingDays.value = list
      loaded = !!list.length // 有真数据才缓存成功，空结果不缓存（下轮重试）
      return tradingDays
    })
    .catch(() => {
      tradingDays.value = []
      loaded = false
      loading = null // 失败不缓存，允许下次重试
      return tradingDays
    })
  return loading
}

export function useTradingCalendar() {
  return {
    tradingDays,
    loadTradingDays,
    disabledDate,
    cellClass,
    dayState,
    isNonTrading,
    latestTradingDay,
    fallbackRecentTradingDay,
  }
}