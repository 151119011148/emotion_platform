import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { scoringApi, recordApi } from '../api/modules'
import {
  DIMS, CARD_ORDER, MAX_POSSIBLE,
  FIVE_DIM_DIMS, FIVE_DIM_ORDER, FIVE_DIM_MAX
} from '../utils/scores'

/**
 * 生效打分模型（旧 9 维 + 新五维双层）的读侧视图。
 *
 * <p>维序 / 权重 / 卡面名 / 卡片摆放序 / 中间层 sub / 阶梯 band 全部从后端 /scoring/effective 拿，
 * 管理页改一维权重、增一个 sub、改一档阈值，仪表盘与复盘页随之变，不用再改前端代码。
 *
 * <p>读不到（后端不可达、或返回 source=BUILTIN）时，退回 {@code utils/scores.js} 里的两套常量兜底：
 *   <ul>
 *     <li>{@code legacyDims} — 旧 9 维，legacy 卡片/列</li>
 *     <li>{@code fiveDimTree} — 新五维（含 subs），五维卡片与详情页</li>
 *   </ul>
 * 缺兜底会在后端不可达时渲染空卡；改后端常量必须同步那两个常量。
 */
const BUILTIN_DIMS = Object.entries(DIMS).map(([key, d]) => ({
  dimKey: key,
  dimNo: d.dim,
  label: d.label,
  weight: d.weight,
  sortNo: CARD_ORDER.indexOf(key),
  recordColumn: null,
  ruleEngine: null,
  note: null
}))

/** 兜底五维树：只有一层 dim，subs 从后端拿；后端不可达时至少 5 张一级卡不空。 */
const BUILTIN_FIVE_DIMS = Object.entries(FIVE_DIM_DIMS).map(([key, d]) => ({
  dimKey: key,
  dimNo: d.dim,
  label: d.label,
  weight: d.weight,
  sortNo: FIVE_DIM_ORDER.indexOf(key),
  recordColumn: d.recordColumn,
  ruleEngine: 'WEIGHTED_SUM',
  note: null
}))

export const useScoringStore = defineStore('scoring', () => {
  /** 后端 ScoringModelVO（含 dims / subs / rules）；source=DB 且有 dims 时才算生效。 */
  const vo = ref(null)
  const loading = ref(false)

  /**
   * 当前日期的 score-detail（后端现算的五维 eval 树）。切日期<b>必须</b>先清空再拉，
   * 否则 60s 慢响应会把上一日子分落在当日卡片上（ReviewView 里已经踩过一次）。
   */
  const detail = ref(null)
  const detailDate = ref(null)
  const detailLoading = ref(false)

  async function load(force = false) {
    if (loading.value) return
    if (vo.value && !force) return
    loading.value = true
    try {
      const res = await scoringApi.effective()
      const data = res?.data
      vo.value = data && data.source === 'DB' && Array.isArray(data.dims) && data.dims.length ? data : null
    } catch (e) {
      vo.value = null // 静默退回兜底常量：配置读不到是"管理员改动没生效"，不是"整页打不开"
    } finally {
      loading.value = false
    }
  }

  /**
   * 拉/复用某一天的 score-detail eval 树。
   * - 同一天已加载 → 直接返回（除非 force）
   * - 换日期 → <b>先清 detail</b>，避免旧日期读数落在新日期卡片上
   * - 失败 → detail=null，卡片走 record.score_* 兜底显示，不弹红条
   */
  async function loadDetail(date, force = false) {
    if (!date) {
      detail.value = null
      detailDate.value = null
      return
    }
    if (!force && detailDate.value === date && detail.value) return
    detail.value = null
    detailDate.value = date
    detailLoading.value = true
    try {
      const res = await recordApi.scoreDetail(date)
      // 60s 里用户可能已经切了日期：晚到的响应不许落在新日期上
      if (detailDate.value !== date) return
      detail.value = res?.data || null
    } catch (e) {
      if (detailDate.value !== date) return
      detail.value = null
    } finally {
      detailLoading.value = false
    }
  }

  const isDb = computed(() => !!vo.value)
  const source = computed(() => (isDb.value ? 'DB' : 'BUILTIN'))

  /** 后端返回的全部维度行（旧 9 + 新 5 混在一张表里，用 dimKey 归属判别）。 */
  const allDims = computed(() => {
    if (!isDb.value) return []
    return (vo.value.dims || []).map((d) => ({
      dimKey: d.dimKey,
      dimNo: d.dimNo,
      label: d.label,
      weight: Number(d.weight),
      sortNo: d.sortNo,
      recordColumn: d.recordColumn,
      ruleEngine: d.ruleEngine,
      note: d.note
    }))
  })

  const FIVE_KEYS = new Set(Object.keys(FIVE_DIM_DIMS))
  const LEGACY_KEYS = new Set(Object.keys(DIMS))

  /** 五维维（新 active 模型）；DB 缺时退回兜底常量。 */
  const fiveDimDims = computed(() => {
    const fromDb = allDims.value.filter((d) => FIVE_KEYS.has(d.dimKey))
    return fromDb.length ? fromDb : BUILTIN_FIVE_DIMS
  })

  /** 旧 9 维维（legacy 展示列仍在用）；DB 缺时退回兜底常量。 */
  const legacyDims = computed(() => {
    const fromDb = allDims.value.filter((d) => LEGACY_KEYS.has(d.dimKey))
    if (!fromDb.length) return BUILTIN_DIMS
    return fromDb
  })

  /** 兼容旧消费者：原 dims getter 仍返回 legacy 9 维（IndicatorCards 里 byKey 靠它）。 */
  const dims = computed(() => legacyDims.value)

  const dimMap = computed(() => {
    const m = {}
    for (const d of dims.value) m[d.dimKey] = d
    return m
  })

  /** 卡片摆放序（legacy）：库里按 sort_no，否则 CARD_ORDER。 */
  const cardOrder = computed(() => {
    if (!isDb.value) return CARD_ORDER
    return legacyDims.value
      .filter((d) => d.sortNo != null)
      .sort((a, b) => a.sortNo - b.sortNo)
      .map((d) => d.dimKey)
  })

  /** 五维卡片摆放序：按 dimNo（1..5）。 */
  const fiveDimCardOrder = computed(() => {
    return [...fiveDimDims.value]
      .sort((a, b) => (a.dimNo || 0) - (b.dimNo || 0))
      .map((d) => d.dimKey)
  })

  /** 温度分母（旧 9 维口径）：库里 effectiveMaxScore，否则 MAX_POSSIBLE=39。 */
  const maxPossible = computed(() => {
    if (!isDb.value) return MAX_POSSIBLE
    const m = Number(vo.value.effectiveMaxScore)
    return Number.isFinite(m) && m > 0 ? m : MAX_POSSIBLE
  })

  /** 五维模型的总分满分：固定 100，直加权和。 */
  const fiveDimMax = computed(() => FIVE_DIM_MAX)

  /** 中间层 subs（含四层）；后端只在 five_dim 模型下才填。 */
  const subs = computed(() => (isDb.value && Array.isArray(vo.value.subs) ? vo.value.subs : []))

  /** 按 dim_key 分组的一级 sub（parent_sub_key='-'）。 */
  const subsByDim = computed(() => {
    const m = {}
    for (const s of subs.value) {
      if ((s.parentSubKey || '-') === '-') {
        ;(m[s.dimKey] = m[s.dimKey] || []).push(s)
      }
    }
    for (const arr of Object.values(m)) arr.sort((a, b) => (a.sortNo || 0) - (b.sortNo || 0))
    return m
  })

  /** 某一 parent sub 下的层/叶 children。 */
  function childrenOf(dimKey, parentSubKey) {
    return subs.value
      .filter((s) => s.dimKey === dimKey && s.parentSubKey === parentSubKey)
      .sort((a, b) => (a.sortNo || 0) - (b.sortNo || 0))
  }

  /** 阶梯 rules 按 (dim_key, sub_key) 索引，rule_no 升序。 */
  const rulesBySub = computed(() => {
    const m = {}
    const arr = (isDb.value && Array.isArray(vo.value.rules)) ? vo.value.rules : []
    for (const r of arr) {
      const k = `${r.dimKey}|${r.subKey}`
      ;(m[k] = m[k] || []).push(r)
    }
    for (const list of Object.values(m)) list.sort((a, b) => (a.ruleNo || 0) - (b.ruleNo || 0))
    return m
  })

  function ladderOf(dimKey, subKey) {
    return rulesBySub.value[`${dimKey}|${subKey}`] || []
  }

  const labelOf = (key) => dimMap.value[key]?.label ?? ''
  const dimNoOf = (key) => dimMap.value[key]?.dimNo ?? 0
  const weightOf = (key) => dimMap.value[key]?.weight ?? 0

  /** 一维进分那句 tooltip（旧 9 维口径）。 */
  function dimScoreLine(key, score) {
    const d = dimMap.value[key]
    if (!d) return ''
    const fmt = (v) => (v > 0 ? '+' : '') + Math.round(v * 100) / 100
    if (score == null) {
      return `第 ${d.dimNo} 维 · ${d.label} · 未评（不进分子，分母仍是 ${maxPossible.value}）`
    }
    const v = Number(score)
    return `第 ${d.dimNo} 维 · ${d.label} · ${fmt(v)} 分 × 权重 ${d.weight} = 加权 ${fmt(v * d.weight)}`
  }

  /** 五维 tooltip：0-100 直加权；未评/缺分时提示"人工未评 ≠ 0"。 */
  function fiveDimScoreLine(key, score) {
    const d = fiveDimDims.value.find((x) => x.dimKey === key)
    if (!d) return ''
    if (score == null) {
      return `第 ${d.dimNo} 维 · ${d.label} · 未评（剔出分母，不按 0 计）`
    }
    const v = Number(score)
    const fmt = (n) => Math.round(n * 100) / 100
    return `第 ${d.dimNo} 维 · ${d.label} · ${fmt(v)} 分 × 维权 ${d.weight} = 加权 ${fmt(v * d.weight)}`
  }

  /** 当前 detail 里按 dimKey 取 DimEval（NodeEval），拿不到返回 null；调用方判 null 走 record.score_* 兜底。 */
  function dimEval(dimKey) {
    const dims = detail.value?.dims
    if (!Array.isArray(dims)) return null
    return dims.find((d) => d.key === dimKey) || null
  }

  /** 一维直属 subs；detail 未到位时返回空数组，让 tooltip 自己说"未取到"。 */
  function subsOf(dimKey) {
    const d = dimEval(dimKey)
    return (d && Array.isArray(d.children)) ? d.children : []
  }

  return {
    vo, loading, isDb, source, load,
    // legacy
    dims, dimMap, cardOrder, maxPossible, dimScoreLine,
    labelOf, dimNoOf, weightOf,
    // five-dim
    fiveDimDims, fiveDimCardOrder, fiveDimMax, fiveDimScoreLine,
    subs, subsByDim, childrenOf, ladderOf,
    // score-detail eval 树
    detail, detailDate, detailLoading, loadDetail, dimEval, subsOf
  }
})
