/**
 * 交易带（全站唯一一份）：5 条带 + 强制退潮徽标。
 *
 * 带位按「相对冷热」定，边界是可比样本的分位线，见 temperatureBands.js。
 * 后端落库的 stage 仍是绝对四条线那一版（<40 退潮 / 40-59 混沌 / 60-84 发酵 / ≥85 高潮），
 * 外加命中空仓条件时的 "退潮(强制)"。这里做的是**读时重切**：
 *   强制退潮 > 人工改判 > 分位带 > 落库标签（参与维数不足、与五维齐的日子不同尺度，宁可用旧标签）。
 * 落库一个字不改，所以「冰点」这一档在库里根本不存在，只有这一层说得出口。
 *
 * 逐点着色、日历格子、页头徽章、tooltip 说的是同一套话。
 */
import { cuts, isComparable, COMPARABLE_DIMS } from './temperatureBands'

export const STAGES = [
  { name: '冰点', color: '#1e40af' },
  { name: '退潮', color: '#4a5568' },
  { name: '混沌', color: '#0891b2' },
  { name: '发酵', color: '#d97706' },
  { name: '沸点', color: '#dc2626' }
]

/** 强制退潮穿透全带：命中任一强制条件时后端把 stage 落 "退潮(强制)"，颜色独立、优先度最高。 */
export const FORCED_EBB_STAGE = '退潮(强制)'
export const FORCED_EBB_COLOR = '#111827'

/** 判不出阶段（参与打分维数不足、温度=空）用的颜色。 */
export const NO_STAGE_COLOR = '#374151'

/** 高潮是落库旧词（≥85 那一档），颜色跟着沸点走，旧串不致于灰掉。 */
export const STAGE_COLORS = {
  ...Object.fromEntries(STAGES.map((s) => [s.name, s.color])),
  高潮: '#dc2626',
  [FORCED_EBB_STAGE]: FORCED_EBB_COLOR
}

export function stageColorOf(stage) {
  if (!stage) return NO_STAGE_COLOR
  return STAGE_COLORS[stage] || NO_STAGE_COLOR
}

/** 落库的旧词在这套带里没有独立一档：≥85 那档现在叫沸点。 */
export function normalizeStageName(stage) {
  return stage === '高潮' ? '沸点' : stage
}

/** 当前分位线切出来的 5 条带（含边界），曲线画带、tooltip 印区间都从这里取。 */
export function bandRanges() {
  const [ice, ebb, ferment, climax] = cuts.value
  const colorOf = (name) => STAGES.find((s) => s.name === name).color
  return [
    { name: '冰点', color: colorOf('冰点'), min: 0, max: ice },
    { name: '退潮', color: colorOf('退潮'), min: ice, max: ebb },
    { name: '混沌', color: colorOf('混沌'), min: ebb, max: ferment },
    { name: '发酵', color: colorOf('发酵'), min: ferment, max: climax },
    { name: '沸点', color: colorOf('沸点'), min: climax, max: 101 }
  ]
}

/** 分 → 带名。null / NaN 返回空串，未评绝不退化成"冰点"。 */
export function bandNameOf(total) {
  if (total == null) return ''
  const v = Number(total)
  if (!Number.isFinite(v)) return ''
  // 不走 bandRanges 的区间匹配：旧引擎落库过负温度，第一带下界是 0，匹配不上会被误判成沸点
  const [ice, ebb, ferment, climax] = cuts.value
  if (v < ice) return '冰点'
  if (v < ebb) return '退潮'
  if (v < ferment) return '混沌'
  if (v < climax) return '发酵'
  return '沸点'
}

/**
 * 这一天算哪个带。record 是完整 DailyRecord（有 forcedEbb / stageOverridden 那两个标记）时用这个。
 */
export function bandOfRecord(record) {
  if (!record) return ''
  if (record.forcedEbb === 1) return FORCED_EBB_STAGE
  if (record.stageOverridden && record.stage) return normalizeStageName(record.stage)
  if (isComparable(record)) return bandNameOf(record.temperature)
  return normalizeStageName(record.stage || '')
}

/**
 * 曲线只有一个温度点，拿不到强制/改判两个标记，只有三条并行数组：
 * 强制退潮认落库串本身，人工改判在这一层看不出来（改判的日子本来就极少）。
 */
export function bandOfPoint(temperature, scoredDims, storedStage) {
  if (storedStage === FORCED_EBB_STAGE) return FORCED_EBB_STAGE
  if (temperature != null && Number(scoredDims) >= COMPARABLE_DIMS) return bandNameOf(temperature)
  return normalizeStageName(storedStage || '')
}

/**
 * 保留 seqOnly() 只是为了旧 record（stage_phase/stage_seq 曾非空）不炸组件；
 * 新 record 里 phase/seq 恒空，函数直接返回原 stage。
 */
export function seqOnly(stage, label) {
  if (!label) return ''
  return label
}
