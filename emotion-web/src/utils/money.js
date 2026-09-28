/**
 * 持仓的金额口径。与后端 t_position.quantity 同一套定义，前端后端两边别各算各的：
 *   成本额 = 股数 × 成本价、市值 = 股数 × 现价、盈亏额 = 市值 − 成本额。
 *
 * <p>没填股数就得不出金额——这时一律返回 null，页面显示「—」。
 * 绝不拿「股价相加」或「股价差」凑数：10 元的票 + 100 元的票 = 110，那不是成本合计；
 * 只差 1 块钱股价的两只票，持仓 100 股和 10000 股也完全不是一回事。
 */

/** @returns {{cost:number,value:number,amount:number}|null} 缺股数或价格时返回 null */
export function pnlOf(row) {
  const q = Number(row && row.quantity)
  const c = Number(row && row.costPrice)
  const p = Number(row && row.currentPrice)
  if (!Number.isFinite(q) || q <= 0) return null
  if (!Number.isFinite(c) || c <= 0 || !Number.isFinite(p) || p <= 0) return null
  return { cost: q * c, value: q * p, amount: q * (p - c) }
}

/** 宽松取数：空串/null/undefined/NaN 一律 null（不要用 0 顶替「没填」）。 */
function numOf(v) {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * 当日了结（卖出）的口径。与后端 t_position.sell_price / sell_qty 同一套定义：
 *   已实现% = (卖价 − 成本基准) / 成本基准、已实现额 = (卖价 − 成本基准) × 卖出股数。
 *
 * 成本基准：本行成本优先；清仓行常常只填股数、没重填成本（成本在上一天的持仓中行上），
 * 就用服务端读取期补的 basisCost。缺卖价或缺成本基准一律返回 null —— 绝不拿收盘价
 * （currentPrice，那是行情不是成交）或手记浮动%顶替，那是把「不知道」写成「知道」。
 *
 * @returns {{basis:number,pct:number,qty:number|null,amount:number|null}|null} 没填卖价时 null
 */
export function realizedOf(row) {
  const sp = numOf(row && row.sellPrice)
  if (sp === null || sp <= 0) return null
  let basis = numOf(row && row.costPrice)
  if (basis === null) basis = numOf(row && row.basisCost)
  if (basis === null || basis <= 0) return null
  const qty = numOf(row && row.sellQty)
  return {
    basis,
    pct: Math.round(((sp - basis) / basis) * 10000) / 100,
    qty,
    amount: qty === null || qty <= 0 ? null : Math.round((sp - basis) * qty * 100) / 100
  }
}

/** 股数：1,000（千分位）。没填返回「—」，不用 0 顶替。 */
export function qtyText(v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return '—'
  return n.toLocaleString('zh-CN')
}

/** 金额：¥1,234.56（负号压在 ¥ 前）。要精确到元的数用这个，不用 moneyText 的「万/亿」粗粒度。 */
export function yuan(n) {
  if (n === null || n === undefined || n === '' || Number.isNaN(Number(n))) return '—'
  const v = Number(n)
  return (v < 0 ? '-¥' : '¥') + Math.abs(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
