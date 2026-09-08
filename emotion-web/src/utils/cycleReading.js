/**
 * 「这一天是怎么被定成这个阶段的」+「当天亮着几盏顶哨」。
 *
 * 两个都只是读，不改温度、不改阶段判定、不改建议映射：把已经算出来的结论后面
 * 那句没说出口的话补上。阈值抄后端，改那边必须改这里。
 */

/** 判转弱的温差，对齐 TemperatureCalculator.DROP_THRESHOLD。 */
const DROP_THRESHOLD = 12

/**
 * 来路。同一个温度可以是 Δ 打下来的、也可以是水位量出来的，两者读法完全不同：
 * 09-03 和 09-04 曾经温度一模一样却给了两个阶段，屏幕上看不出任何区别，缺的就是这句话。
 */
export function stageBasis(record) {
  if (!record || !record.stage) return ''
  if (record.stageOverridden) return '人工改判（下面的判据解释的是机器那一版）'
  if (record.stagePhase === '反弹') return '反弹触发：前一日在退潮/分歧，当日回涨 ≥8° 且未过发酵线'
  const t = Number(record.temperature)
  const prev = record.prevTemperature == null ? null : Number(record.prevTemperature)
  if (prev != null && t - prev <= -DROP_THRESHOLD) {
    return `Δ 触发：当日比前一日掉 ${Math.abs(t - prev).toFixed(1)}°，退潮/分歧 由跌幅定，不看水位`
  }
  const tail = prev == null ? '（无前一日可比）' : `（Δ ${(t - prev).toFixed(1)}° 未过 ±${DROP_THRESHOLD}° 线）`
  return `水位触发：按当日温度落在哪两条阶段线之间定${tail}`
}

/**
 * 顶哨：三个已经判到最低档、却被主阶段那句措辞盖过去的负反馈。
 * 各算一项、不加权——加权等于再造一维，那是打分口径的事，得先过对照表。
 */
export function topSignals(record) {
  if (!record) return []
  const num = (v) => (v == null || Number.isNaN(Number(v)) ? null : Number(v))
  const out = []
  // 阵眼只有第 8 维能给 0 分（收盘跌停 / 盘中触及跌停 / 断板），没设阵眼是 null，不算哨
  const anchor = num(record.anchorScore)
  if (anchor !== null && anchor <= 0) out.push(`阵眼 ${anchor} 分`)
  const survCount = num(record.survCount)
  const surv = num(record.survPremium)
  if (survCount > 0 && surv !== null && surv <= -2) out.push(`监管股均值 ${surv.toFixed(2)}%`)
  const broken = num(record.brokenBoardRate)
  if (broken !== null && broken >= 70) out.push(`炸板率 ${broken}%`)
  return out
}
