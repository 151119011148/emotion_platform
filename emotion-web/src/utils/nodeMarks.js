/**
 * 节点页曲线上那枚标的落点算法（◆ 节点票）。
 *
 * <p>后端 height-range 每天都带一份「当天 2 板及以上、各自几板」的名单（{@code ladder}），
 * 所以这个日子在前端就能算出来，不用为节点页新开接口。
 *
 * <p>找不到就是<b>没有这枚标</b>，不拿 D0 或今天硬凑一个位置：
 * 曲线上的点说的是「这只票那天真站在这个高度上」，凑出来的点比缺点更坏。
 */

/** 节点票存的是「名称(代码)」；手填的行可能只有名称。 */
export function splitStock(text) {
  const m = /^(.*?)\((\d{6})\)\s*$/.exec(text || '')
  return m ? { name: m[1], code: m[2] } : { name: text || '', code: '' }
}

/** 这只票当天在梯上的板高；不在梯上是 null（不是 0 板）。 */
function boardOn(row, code, name) {
  const hit = (row.ladder || []).find((s) => (code ? s.code === code : !!name && s.name === name))
  return hit ? hit.board : null
}

/** D0 起（含当天）节点票第一次站上 nodeStockMaxBoard 的那天；rows 按日期升序。 */
function markDay(node, rows) {
  const target = node.nodeStockMaxBoard
  if (!target || !node.nodeStock) return null
  const { name, code } = splitStock(node.nodeStock)
  for (const r of rows) {
    if (node.d0Date && r.date < node.d0Date) continue
    if (boardOn(r, code, name) === target) return { date: r.date, board: target }
  }
  return null
}

/**
 * 全部节点 → 曲线的 nodeMarks。
 * @param infoOf 由调用方给的浮层读数（节点页才知道的中文标签与验证话）
 */
export function buildNodeMarks(nodes, rows, infoOf) {
  const out = []
  for (const n of nodes || []) {
    const d = markDay(n, rows)
    if (!d) continue
    const st = splitStock(n.nodeStock)
    out.push({
      id: n.id, date: d.date, board: d.board, status: n.status,
      info: infoOf(n), name: st.name, code: st.code
    })
  }
  return out
}
