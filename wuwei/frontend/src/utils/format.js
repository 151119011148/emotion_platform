/** 节点六态配色与评分段文案 */
export const NODE_COLORS = {
  '冰点': '#909399',
  '启动': '#409EFF',
  '发酵': '#67C23A',
  '高潮': '#F56C6C',
  '分歧': '#E6A23C',
  '退潮': '#606266'
}

export const ROLE_EMOJI = {
  ZONG_LONG: '🔴 总龙头',
  ZHONG_JUN: '🔵 中军',
  GEN_FENG: '🟢 跟风',
  KA_WEI: '🟡 卡位',
  FAN_BAO: '🟣 反包'
}

export const TIER_LABELS = {
  HIGH: '高位板（≥7）',
  MIDHIGH: '中高位板（4-6）',
  MID: '中位板（2-3）',
  LOW: '低位板（2）'
}

export const MONITOR_LABELS = {
  NONE: { label: '无', type: 'info' },
  ORDINARY: { label: '普通异动', type: 'warning' },
  SERIOUS: { label: '严重异动', type: 'danger' },
  KEY_MONITOR: { label: '重点监控', type: 'danger' }
}

export function nodeColor(node) {
  return NODE_COLORS[node] || '#909399'
}

export function fmt(v, suffix = '', digits = 2) {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'number') return v.toFixed(digits) + suffix
  return v + suffix
}

export function money(v) {
  if (v === null || v === undefined) return '—'
  return (v / 100000000).toFixed(2) + ' 亿'
}
