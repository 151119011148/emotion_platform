// 用最小 DOM 桩跑一遍页面脚本，捕获运行期报错并抽查渲染产物。
// 用法：node 08_页面冒烟测试.js [html路径]，默认取同目录上一级的预览页。
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const target = process.argv[2] || path.join(__dirname, '..', '短线连板策略-回测与推荐清单.html');
const html = fs.readFileSync(target, 'utf8');
const js = html.match(/<script>([\s\S]*?)<\/script>/)[1];
console.log('被测页面:', path.basename(target));

const log = { inner: [], text: [], svg: [], attrs: 0, err: [] };

function stub(tag) {
  const o = {
    tagName: tag, children: [], _cls: '', _html: '', textContent: '', style: {},
    offsetTop: 0, classList: { toggle() {}, add() {}, remove() {} },
    setAttribute(k, v) { log.attrs++; },
    getAttribute() { return 'x'; },
    appendChild(c) { this.children.push(c); return c; },
  };
  Object.defineProperty(o, 'className', { get() { return this._cls; }, set(v) { this._cls = v; } });
  Object.defineProperty(o, 'innerHTML', {
    get() { return this._html; },
    set(v) { this._html = v; log.inner.push(v); },
  });
  return o;
}

const reg = {};
const document = {
  createElementNS: (ns, tag) => { log.svg.push(tag); return stub(tag); },
  createElement: (tag) => stub(tag),
  getElementById: (id) => { if (!reg[id]) reg[id] = stub('div'); return reg[id]; },
  querySelector: (sel) => { if (!reg[sel]) reg[sel] = stub('tbody'); return reg[sel]; },
  querySelectorAll: () => [],
};
const window = { addEventListener() {}, scrollY: 0 };

let errCount = 0;
try {
  vm.runInNewContext(js, { document, window, console });
} catch (e) {
  errCount++;
  console.log('运行期错误:', e.message);
}

const innerOk = log.inner.filter((s) => /\d/.test(s)).length;
console.log('运行期错误数:', errCount);
console.log('写入 innerHTML 次数:', log.inner.length, '（含数字的', innerOk, '）');
console.log('创建 SVG 元素数:', log.svg.length, '· setAttribute', log.attrs, '次');

const dump = (label, re, limit) => {
  const hits = log.inner.filter((s) => re.test(s));
  console.log('\n[' + label + '] 命中 ' + hits.length + ' 行');
  hits.slice(0, limit || 3).forEach((s) => console.log('   ', s.replace(/\s+/g, ' ').slice(0, 260)));
};
dump('日级明细行', /class="num"/, 2);
dump('分档行', /胜率/, 2);
dump('IC 行', /pp</, 2);
dump('09-18 逐只', /走强|未走出来/, 3);

// 抽查关键数字是否真的出现在产物里
const blob = log.inner.join('\n');
['+56.60%', '+39.52%', '+7.19%', '+6.01%', '−0.150', '-0.150', '0.308', '301390', '华瓷股份', '83.7', '11.0']
  .forEach((k) => console.log('含 "' + k + '" :', blob.includes(k)));
console.log('空产物（innerHTML 长度 0）次数:',
  log.inner.filter((s) => !s || s.length === 0).length);

// ---- ★ 执行时点校验节（v1.4）抽查 ----
const exSel = ['#tbl-exec-1 tbody', '#tbl-exec-2 tbody', '#tbl-exec-3 tbody',
  '#tbl-exec-4 tbody', '#tbl-exec-5 tbody', '#tbl-exec-6 tbody'];
console.log('\n[★ 执行时点校验]');
let exBad = 0;
exSel.forEach((k, i) => {
  const box = reg[k];
  const n = box ? box.children.length : -1;
  if (n <= 0) exBad++;
  let first = '';
  if (n > 0) {
    first = box.children[0].children
      .map((c) => String(c._html || c.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 34))
      .join(' | ');
  }
  console.log('  tbl-exec-' + (i + 1) + ' 行数:', n, n > 0 ? '| ' + first : '');
});
const st = reg['exec-stats'];
console.log('  exec-stats 卡片数:', st ? st.children.length : -1,
  st && st.children.length ? '| 首卡 ' + st.children[0].children
    .map((x) => String(x._html || x.textContent || '')).join(' / ') : '');
console.log('  ★ 节表格为空的数量:', exBad, exBad === 0 ? '(全部渲染成功)' : '(有表没渲染出来)');

// ---- ★ 推荐清单与仓位配比节（v1.5）抽查 ----
const pkSel = ['#tbl-funnel tbody', '#tbl-pick tbody', '#tbl-alloc tbody',
  '#tbl-prewarn tbody', '#tbl-tstat tbody', '#tbl-funnel2 tbody', '#tbl-pick2 tbody',
  '#tbl-funnel3 tbody', '#tbl-pick3 tbody'];
console.log('\n[★ 推荐清单与仓位配比]');
let pkBad = 0;
pkSel.forEach((k) => {
  const box = reg[k];
  const n = box ? box.children.length : -1;
  if (n <= 0) pkBad++;
  let first = '';
  if (n > 0) {
    first = box.children[0].children
      .map((c) => String(c._html || c.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30))
      .join(' | ');
  }
  console.log('  ' + k.replace('#', '').replace(' tbody', '') + ' 行数:', n, n > 0 ? '| ' + first : '');
});
const ps = reg['pick-stats'];
console.log('  pick-stats 卡片数:', ps ? ps.children.length : -1);
console.log('  pw-high:', reg['pw-high'] && reg['pw-high'].textContent,
  '| pw-n:', reg['pw-n'] && reg['pw-n'].textContent,
  '| t-sig:', reg['t-sig'] && reg['t-sig'].textContent,
  '| t2-day:', reg['t2-day'] && reg['t2-day'].textContent,
  '| sk-n:', reg['sk-n'] && reg['sk-n'].textContent,
  '| tk-n:', reg['tk-n'] && reg['tk-n'].textContent);
const pkBlob = log.inner.join('\n');
['一字·买不进', '已成交·盈', '大概率买不进', '缺口', '5%'].forEach((k) => {
  console.log('  含 "' + k + '" :', pkBlob.includes(k));
});
console.log('  清单节表格为空的数量:', pkBad, pkBad === 0 ? '(全部渲染成功)' : '(有表没渲染出来)');

// ---- ★ 12.7 第 3 期（进行中）抽查 ----
const ps3 = reg['pick3-stats'];
console.log('  pick3-stats 卡片数:', ps3 ? ps3.children.length : -1,
  ps3 && ps3.children.length ? '| 首卡 ' + ps3.children[0].children
    .map((x) => String(x._html || x.textContent || '')).join(' / ') : '');
const f3 = reg['#tbl-funnel3 tbody'], p3 = reg['#tbl-pick3 tbody'];
console.log('  tbl-funnel3 行数:', f3 ? f3.children.length : -1);
console.log('  tbl-pick3 行数:', p3 ? p3.children.length : -1,
  p3 && p3.children.length ? '| 首行 ' + p3.children[0].children
    .map((c) => String(c._html || c.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 22))
    .join(' | ') : '');
const p3Blob = log.inner.join('\n');
['封单锁死 · 出池', '一字 · 买不进', '成交 · 预留 5%', '40.9', '进行中'].forEach((k) => {
  console.log('  含 "' + k + '" :', p3Blob.includes(k));
});
const p3Bad = pkSel.slice(-2).filter((k) => !(reg[k] && reg[k].children.length > 0)).length;
console.log('  12.7 节表格为空的数量:', p3Bad, p3Bad === 0 ? '(全部渲染成功)' : '(有表没渲染出来)');
