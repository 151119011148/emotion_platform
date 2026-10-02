/**
 * 交易日历（共享）：
 * 后端两份数据撑起全站日期控件：
 *   - /review/holidays —— 整张官方休市表，含今天之后的（国庆落在工作日也要知道）；
 *   - /review/trading-days —— 近几个月可复盘交易日（降序，[0]=最近交易日），只用来定默认日期。
 * 判定只认休市表：周末或表内休市日 = 休市，今天之后的工作日 = 未到，两者都不可选但各有标识；
 * 其余工作日可选（早于休市表覆盖年份的日子按可选处理，点了拉不到数据就是了）。
 * 两份集合都是应用级单例，首个 view 加载一次即缓存，避免每个页面重复请求。
 */
import { ref } from 'vue'
import { reviewApi } from '../api/modules'

const tradingDays = ref([])
const holidayDays = ref(new Set())
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

/** 是否休市：周末恒休；官方休市日查表（表里也有未来那段）。 */
function isNonTrading(dstr) {
  const d = new Date(dstr + 'T00:00:00')
  const dow = d.getDay()
  return dow === 0 || dow === 6 || holidayDays.value.has(dstr)
}

/**
 * 单元格性质，供日期面板做样式区分：
 *   'non-trading' —— 休市：周末，或落在工作日上的官方休市日（过去和未来一样对待）
 *   'future'      —— 未到：今天之后的工作日，日子还没到，数据无从产生
 *   'trading'     —— 可选交易日
 * 两种不可选日在面板上各有一种标识，见 App.vue 全局段。
 */
function dayState(d) {
  const k = keyOf(d)
  if (isNonTrading(k)) return 'non-trading'
  if (k > todayKey()) return 'future'
  return 'trading'
}

/** el-date-picker 的 cell-class-name：把上面三种性质落成 td 上的 class。 */
function cellClass(d) {
  const s = dayState(d)
  if (s === 'non-trading') return 'day-non-trading'
  if (s === 'future') return 'day-future'
  return ''
}

/** el-date-picker 的 disabled-date：休市与未到都点不了，标识即原因。 */
function disabledDate(d) {
  return dayState(d) !== 'trading'
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

/** 加载交易日历 + 休市表。成功则单例缓存；任一失败不置 loaded，下次调用重试，
 *  避免「空集合」被永久缓存污染全局（休市表缺了就把未来休市日错标成「未到」）。 */
function loadTradingDays() {
  if (loaded) return Promise.resolve(tradingDays)
  if (loading) return loading
  loading = Promise.all([
    reviewApi.tradingDays().then((r) => r?.data || []).catch(() => null),
    reviewApi.holidays().then((r) => r?.data || []).catch(() => null)
  ])
    .then(([days, holidays]) => {
      loading = null
      if (days === null || holidays === null) {
        tradingDays.value = days || []
        holidayDays.value = new Set(holidays || [])
        return tradingDays // 有一边没成：本轮按已有信息降级渲染，下轮重试
      }
      tradingDays.value = days
      holidayDays.value = new Set(holidays)
      loaded = !!days.length || holidays.length > 0 // 两份都空=库没数据，不缓存死
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