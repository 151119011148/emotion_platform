/**
 * 把全市场 A股代码/名称灌进 t_stock（选股搜索的字典表）。
 *
 * 什么时候用它：新库第一次部署、或后端还没重启到含 stockDictSyncTask 的版本时，
 * 选股下拉「输什么都搜不到」——不是搜索坏了，是 t_stock 空的。
 * 后端起来之后正常走 POST /api/market/stock-dict/sync（或每周一的定时任务）即可，
 * 这个脚本只是绕过重启、直接对库的一次性通道，取数口径与 StockDictService 完全一致。
 *
 * 用法：
 *   NODE_PATH=C:/Users/1/.workbuddy/binaries/node/workspace/node_modules \
 *     node scripts/sync_stock_dict.js [host] [user] [password] [database]
 */
const mysql = require('mysql2/promise');

const LIST_BASE = 'http://push2delay.eastmoney.com';
const UT = '7eea3edcaed734bea9cbfc24409ed989';
// 与 EastmoneyClient.LIST_FS 同一串：沪深主板/创业板/科创板 + 北交所
const FS = 'm:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048';
// 与 EastmoneyClient.STOCK_PREFIX 同一套：北交所那条 fs 会捎回 81xxxx 定向可转债
const PREFIX = /^(00|30|60|68|43|83|87|88|92)\d{4}$/;
const PAGE_SIZE = 100;
const MAX_PAGES = 80;

function boardOf(code) {
  const p = code.slice(0, 2);
  if (p === '60') return '沪主板';
  if (p === '68') return '科创板';
  if (p === '00') return '深主板';
  if (p === '30') return '创业板';
  return '北交所';
}

async function fetchAll() {
  const rows = [];
  let total = 0;
  let dropped = 0;
  for (let pn = 1; pn <= MAX_PAGES; pn++) {
    const url = `${LIST_BASE}/api/qt/clist/get?ut=${UT}&pn=${pn}&pz=${PAGE_SIZE}`
      + `&po=1&np=1&fltt=2&invt=2&fid=f12&fs=${FS}&fields=f12,f13,f14`;
    const res = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
    const json = await res.json();
    if (!json.data || !json.data.diff) break;
    total = json.data.total || total;
    const diff = Array.isArray(json.data.diff) ? json.data.diff : Object.values(json.data.diff);
    for (const item of diff) {
      const code = item.f12;
      const name = item.f14;
      if (!code || !name) continue;
      if (!PREFIX.test(code)) { dropped++; continue; }
      rows.push({ code, name: String(name).trim(), market: item.f13, board: boardOf(code) });
    }
    if (rows.length + dropped >= total || diff.length === 0) break;
  }
  return { rows, total, dropped };
}

(async () => {
  const [host = '192.168.123.18', user = 'root', password = '123456', database = 'emotion_dashboard'] =
    process.argv.slice(2);
  console.log('拉取全市场名单…');
  const { rows, total, dropped } = await fetchAll();
  console.log(`拿到 ${rows.length} 只（上游 total ${total}，过滤掉非股票 ${dropped} 行）`);
  if (!rows.length) {
    console.error('一条都没拿到，不写库（避免把好表换成空表）');
    process.exit(1);
  }

  const conn = await mysql.createConnection({ host, user, password, database, multipleStatements: true });
  const sql = 'INSERT INTO t_stock (code, name, market, board) VALUES ? '
    + 'ON DUPLICATE KEY UPDATE name=VALUES(name), market=VALUES(market), board=VALUES(board)';
  let written = 0;
  for (let from = 0; from < rows.length; from += 1000) {
    const chunk = rows.slice(from, from + 1000).map((r) => [r.code, r.name, r.market, r.board]);
    await conn.query(sql, [chunk]);
    written += chunk.length;
  }
  const [[c]] = await conn.query('SELECT COUNT(*) AS n FROM t_stock');
  await conn.end();
  console.log(`写入 ${written} 行；t_stock 现有 ${c.n} 行`);
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });
