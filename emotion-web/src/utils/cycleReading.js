/**
 * 「这一天是怎么被定成这个交易带的」+「今天亮着哪些结构信号」。
 *
 * 两个都只是读、不改分不改带；把已经算出来的结论后面那句没说出口的话补上。
 * 带位边界不在这份文件里抄：分位线由 utils/temperatureBands 现算，判带走 utils/stages。
 * 结构信号（signal_flags）与强制退潮的判据仍在后端 BoardScoreCalculator，改那边要改这里。
 *
 * <p>旧 7 阶段时代的「反弹一/二/三段」「Δ 阈值 12°」都随 CycleStageMachine 下线；
 * record.stagePhase / stageSeq 保留字段但不再更新，本模块也不再读它们。
 */
import { bandNameOf } from './stages'
import { useTemperatureBands } from './temperatureBands'

const { cuts, bandSample } = useTemperatureBands()

/** 分 → 带名（相对冷热定带）。 */
export function stageBandOf(total) {
  return bandNameOf(total)
}

/** 四条分位线一行写完：定带用的是哪几个数、样本多大，这句就是全部依据。 */
function cutText() {
  const [ice, ebb, ferment, climax] = cuts.value
  const s = bandSample.value
  return `分位带 冰点<${ice} / 退潮<${ebb} / 混沌<${ferment} / 发酵<${climax} / 沸点≥${climax}`
    + `（${s.source}，${s.n} 个可比日）`
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
  return `水位触发：总分 ${t.toFixed(1)} → ${bandNameOf(t)}，${cutText()}${tail}`
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
