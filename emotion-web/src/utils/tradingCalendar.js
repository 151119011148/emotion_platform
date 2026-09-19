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

/** 是否非交易日：周末恒非；交易日历在手时不在集合里的也算（用于拉取前兜底拦截）。 */
function isNonTrading(dstr) {
  const dow = new Date(dstr + 'T00:00:00').getDay()
  if (dow === 0 || dow === 6) return true
  return tradingDays.value.length ? !tradingDays.value.includes(dstr) : false
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

/** 加载交易日历（幂等，only once）。返回 promise<ref>，失败时集合为空、仅周末置灰。 */
function loadTradingDays() {
  if (loaded) return Promise.resolve(tradingDays)
  if (loading) return loading
  loading = reviewApi
    .tradingDays()
    .then((r) => {
      tradingDays.value = r?.data || []
      loaded = true
      return tradingDays
    })
    .catch(() => {
      tradingDays.value = []
      loaded = true
      return tradingDays
    })
  return loading
}

export function useTradingCalendar() {
  return {
    tradingDays,
    loadTradingDays,
    disabledDate,
    isNonTrading,
    latestTradingDay,
  }
}