/**
 * 七个阶段的顺序与颜色，全站唯一一份。
 *
 * 曲线逐点着色、日历格子、定位条、页头徽章说的是同一套话——之前这四个地方各自抄了一遍
 * 同一张表，任何一次改色都会留下一个不自洽的面板。
 */
export const STAGES = [
  { name: '冰点', color: '#1e3a5f' },
  { name: '修复', color: '#2d5a87' },
  { name: '启动', color: '#2d8a4e' },
  { name: '发酵', color: '#d97706' },
  { name: '高潮', color: '#dc2626' },
  { name: '分歧', color: '#7c3aed' },
  { name: '退潮', color: '#4a5568' }
]

export const STAGE_COLORS = Object.fromEntries(STAGES.map((s) => [s.name, s.color]))

/** 判不出阶段（参与打分维数不足）用的颜色。它不属于任何阶段，所以不能借用某个阶段的色。 */
export const NO_STAGE_COLOR = '#374151'

export function stageColorOf(stage) {
  return STAGE_COLORS[stage] || NO_STAGE_COLOR
}

/**
 * 后端给的子段标签是整串「退潮 · 一阶段」。主阶段已经显示在别处时只要后半截，
 * 不然徽章上会读出「退潮 退潮 · 一阶段」。
 * 反弹日的标签头一字是「反弹」而不是主阶段，那种必须整串留下——它说的不是同一个词。
 */
export function seqOnly(stage, label) {
  if (!label) return ''
  const prefix = `${stage} · `
  return label.startsWith(prefix) ? label.slice(prefix.length) : label
}
