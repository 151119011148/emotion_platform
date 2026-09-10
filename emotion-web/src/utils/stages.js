/**
 * 五维双层模型的 4 条交易纪律带 + 强制退潮徽标，全站唯一一份。
 *
 * 后端 BoardScoreCalculator.evaluate 只落这五个字面量之一：
 *   高潮(>=85) / 发酵(60-84) / 混沌(40-59) / 退潮(<40) / 退潮(强制)。
 * 老 7 阶段（冰点/修复/启动/发酵/高潮/分歧/退潮）+ 反弹一/二/三段的段号语义已经下线；
 * stage_phase / stage_seq 两列不再由后端 resequence 更新，前端也不再消费。
 *
 * 逐点着色、日历格子、页头徽章、tooltip 说的是同一套话。
 */
export const STAGES = [
  { name: '退潮', color: '#4a5568', min: 0, max: 40 },
  { name: '混沌', color: '#0891b2', min: 40, max: 60 },
  { name: '发酵', color: '#d97706', min: 60, max: 85 },
  { name: '高潮', color: '#dc2626', min: 85, max: 101 }
]

/** 强制退潮穿透 4 带：命中任一强制条件时后端把 stage 落 "退潮(强制)"，颜色独立、优先度最高。 */
export const FORCED_EBB_STAGE = '退潮(强制)'
export const FORCED_EBB_COLOR = '#111827'

export const STAGE_COLORS = {
  ...Object.fromEntries(STAGES.map((s) => [s.name, s.color])),
  [FORCED_EBB_STAGE]: FORCED_EBB_COLOR
}

/** 判不出阶段（参与打分维数不足、温度=空）用的颜色。 */
export const NO_STAGE_COLOR = '#374151'

export function stageColorOf(stage) {
  if (!stage) return NO_STAGE_COLOR
  return STAGE_COLORS[stage] || NO_STAGE_COLOR
}

/**
 * 保留 seqOnly() 只是为了旧 record（stage_phase/stage_seq 曾非空）不炸组件；
 * 新 record 里 phase/seq 恒空，函数直接返回原 stage。
 */
export function seqOnly(stage, label) {
  if (!label) return ''
  return label
}
