/**
 * 温度交易带按「相对冷热」定：四条线取可比样本的分位数，不再写死 40/60/85。
 *
 * 可比样本 = scoredDims >= 5 且温度非空的那些天。更早的记录只有一、二、四维参与打分，
 * 总分是"少数几维的均值"，与五维齐的日子不同尺度：既不入样本（会把线拉偏），
 * 也不在这里重判带（沿用落库 stage）。
 *
 * 全程只读：引擎落库的 stage 一字不改，冰点/沸点只活在这一层展示里。
 * 取数失败时退回 FALLBACK_CUTS，带照样画，来源那行会写明是兜底值。
 */
import { ref } from 'vue'
import { recordApi } from '../api/modules'

/** 2026-10-02 实测 42 个可比日的 P5 / P25 / P75 / P95。 */
export const FALLBACK_CUTS = [33.7, 46.1, 62.6, 69.6]

/** 入样本的下界：参与打分的维数。 */
export const COMPARABLE_DIMS = 5

/** 四条分位线，升序：[P5, P25, P75, P95]。模块级单例，谁 import 都是同一份。 */
export const cuts = ref([...FALLBACK_CUTS])
export const bandSample = ref({
  n: 0,
  from: '',
  to: '',
  min: null,
  max: null,
  source: '兜底分位（未取到数据）'
})
let loaded = false
let loading = null

/** 这一天能不能参与定带、能不能被重切。 */
export function isComparable(record) {
  return !!record
    && record.temperature != null
    && Number.isFinite(Number(record.temperature))
    && Number(record.scoredDims) >= COMPARABLE_DIMS
}

/** 线性插值分位（与 numpy 默认口径一致）：arr 须升序，p ∈ [0,1]。 */
export function quantile(arr, p) {
  if (!arr.length) return null
  const idx = (arr.length - 1) * p
  const lo = Math.floor(idx)
  const hi = Math.ceil(idx)
  return lo === hi ? arr[lo] : arr[lo] + (arr[hi] - arr[lo]) * (idx - lo)
}

function round1(v) {
  return v == null ? null : Math.round(v * 10) / 10
}

/** 用一批记录现算分位线。样本太薄（<10 天）时返回 false，让调用方继续用兜底值。 */
function applyRecords(records) {
  const rows = (records || []).filter(isComparable)
  const vals = rows.map((r) => Number(r.temperature)).sort((a, b) => a - b)
  if (vals.length < 10) return false
  cuts.value = [0.05, 0.25, 0.75, 0.95].map((p) => round1(quantile(vals, p)))
  const dates = rows.map((r) => r.tradeDate).filter(Boolean).sort()
  bandSample.value = {
    n: vals.length,
    from: dates[0] || '',
    to: dates[dates.length - 1] || '',
    min: round1(vals[0]),
    max: round1(vals[vals.length - 1]),
    source: '实测分位'
  }
  return true
}

/** 拉全量记录算一次。成功即缓存；失败不置 loaded，下次调用重试，避免兜底值被当成实测。 */
function loadBands() {
  if (loaded) return Promise.resolve(bandSample)
  if (loading) return loading
  const today = new Date().toLocaleDateString('en-CA')
  loading = recordApi.getRange('2025-01-01', today, { skipErrorToast: true })
    .then((res) => {
      loading = null
      loaded = applyRecords(res?.data)
      return bandSample
    })
    .catch(() => {
      loading = null
      return bandSample
    })
  return loading
}

export function useTemperatureBands() {
  return { cuts, bandSample, loadBands, isComparable, quantile, FALLBACK_CUTS, COMPARABLE_DIMS }
}
