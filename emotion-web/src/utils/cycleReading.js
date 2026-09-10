/**
 * 「这一天是怎么被定成这个交易带的」+「今天亮着哪些结构信号」。
 *
 * 两个都只是读、不改分不改带；把已经算出来的结论后面那句没说出口的话补上。
 * 阈值/判据抄后端 BoardScoreCalculator，改那边必须改这里。
 *
 * <p>旧 7 阶段时代的「反弹一/二/三段」「Δ 阈值 12°」都随 CycleStageMachine 下线；
 * record.stagePhase / stageSeq 保留字段但不再更新，本模块也不再读它们。
 */

/** 与 BoardScoreCalculator 一致的 4 带下界；命中区间返回带名。 */
export function stageBandOf(total) {
  if (total == null) return ''
  const v = Number(total)
  if (Number.isNaN(v)) return ''
  if (v >= 85) return '高潮'
  if (v >= 60) return '发酵'
  if (v >= 40) return '混沌'
  return '退潮'
}

/**
 * 来路：五维引擎只看当日总水位落在哪条带；没有 Δ 触发、也没有反弹。
 * 人工改判（record.stageOverridden=1）仍然优先展示，说明"这是覆盖后的结果"。
 */
export function stageBasis(record) {
  if (!record || !record.stage) return ''
  if (record.forcedEbb === 1) {
    return `强制退潮：${record.forcedEbbReason || '命中任一强制空仓条件'}`
  }
  if (record.stageOverridden) return '人工改判（下面解释的是机器那一版）'
  const t = Number(record.temperature)
  if (Number.isNaN(t)) return ''
  const prev = record.prevTemperature == null ? null : Number(record.prevTemperature)
  const tail = prev == null
    ? '（无前一日可比）'
    : `（较前一日 Δ ${(t - prev).toFixed(1)}，方向按 ±3 判定）`
  return `水位触发：总分 ${t.toFixed(1)} 落在 4 带（<40 退潮 / 40-59 混沌 / 60-84 发酵 / ≥85 高潮）${tail}`
}

/**
 * 结构信号：五维引擎算出的 5 个 flag（后端 signal_flags 逗号分隔字符串）。
 * 每项独立亮灯、不加权；这里只做拆分和空值处理，不重算。
 */
export function topSignals(record) {
  if (!record || !record.signalFlags) return []
  return String(record.signalFlags)
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}
