<template>
  <div class="positions-page">
    <div class="page-header">
      <div>
        <h2>持仓与台账</h2>
        <div class="page-sub">主入口=每日复盘页 · 本页=标的维度全生命周期 · 快照日 {{ latestDate || '—' }}</div>
      </div>
      <el-button :loading="loading" @click="loadAll">刷新</el-button>
    </div>

    <!-- 顶部页签：切换四类视图 -->
    <div class="tabs">
      <div v-for="t in TABS" :key="t.key" class="tab" :class="{ on: tab === t.key }" @click="tab = t.key">{{ t.label }}</div>
    </div>

    <!-- 统计卡：除纪律统计页签外都显示 -->
    <div class="cards" v-if="tab !== 'stats'">
      <div class="card">
        <div class="k">当前持仓</div>
        <div class="v">{{ holdingRows.length }} 只</div>
        <div class="x">{{ holdingStatSub }}</div>
      </div>
      <div class="card">
        <div class="k">浮动盈亏（均值）</div>
        <div class="v" :class="pctClass(holdingAvgPct)">{{ fmtPct(holdingAvgPct) }}</div>
        <div class="x">{{ holdingPctSub }}</div>
      </div>
      <div class="card">
        <div class="k">近7日已实现</div>
        <div class="v" :class="pctClass(weekRealizedAvg)">{{ fmtPct(weekRealizedAvg) }}</div>
        <div class="x">{{ weekClosed.length }} 笔清仓</div>
      </div>
      <div class="card">
        <div class="k">平均纪律分</div>
        <div class="v" :class="scoreClass(weekAvgScore)">{{ weekAvgScore == null ? '—' : weekAvgScore }}</div>
        <div class="x">近7日 {{ weekRows.length }} 行快照</div>
      </div>
      <div class="card">
        <div class="k">近7日胜率</div>
        <div class="v">{{ weekWinRate == null ? '—' : weekWinRate + '%' }}</div>
        <div class="x">{{ weekClosed.length }} 笔：{{ weekWins }} 盈 {{ weekClosed.length - weekWins }} 亏</div>
      </div>
    </div>

    <!-- 待裁决提醒：未执行决策外溢 -->
    <div class="alert" v-if="tab !== 'stats' && pendingRows.length">
      <b>⚠ 待裁决（同步至仪表盘）：</b>
      <template v-for="(p, i) in pendingRows" :key="p.id">
        <span v-if="i"> · </span>
        <span>{{ p.stockName }}（{{ p.nextDayPlan || '待定动作' }}，{{ p.tradeDate }}）</span>
        <el-button size="small" type="primary" link class="alert-btn" @click="markExecuted(p)">标记执行</el-button>
      </template>
    </div>

    <!-- ① 当前持仓 -->
    <div class="sec" v-if="tab === 'holding' || tab === 'all'">
      <div class="sec-hd">
        <h2>🏷 当前持仓（{{ holdingRows.length }} 只）</h2>
        <div class="note">快照日 {{ latestDate || '—' }} 收盘 · 现价自动取行情表，编辑回每日复盘页</div>
      </div>
      <div class="sec-bd">
        <el-table :data="holdingRows" style="width: 100%" empty-text="快照日无持仓中记录">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="plan-grid">
                <div class="plan-cell"><div class="c">次日高开→</div><div class="a">{{ row.planOpen || '—' }}</div></div>
                <div class="plan-cell"><div class="c">次日炸板→</div><div class="a">{{ row.planBreak || '—' }}</div></div>
                <div class="plan-cell"><div class="c">次日平开/低开→</div><div class="a">{{ row.planLow || '—' }}</div></div>
                <div class="plan-cell"><div class="c">次日跌停→</div><div class="a">{{ row.planFall || '—' }}</div></div>
              </div>
              <div class="expand-note" v-if="row.actualAction">已执行：{{ row.actualAction }}</div>
            </template>
          </el-table-column>
          <el-table-column label="代码/名称" min-width="130">
            <template #default="{ row }">
              <div class="sn"><b>{{ row.stockName }}</b></div>
              <div class="mini">{{ row.stockCode }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="industry" label="板块" width="90">
            <template #default="{ row }">{{ row.industry || '—' }}</template>
          </el-table-column>
          <el-table-column label="板数" width="70">
            <template #default="{ row }">{{ row.boardNum ? (row.boardNum > 1 ? row.boardNum + '板' : '首板') : '—' }}</template>
          </el-table-column>
          <el-table-column prop="tradeDate" label="买入日" width="92" />
          <el-table-column label="成本" width="80">
            <template #default="{ row }">{{ row.costPrice ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="现价" width="80">
            <template #default="{ row }">{{ row.currentPrice ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="浮动" width="86">
            <template #default="{ row }">
              <span :class="pctClass(row.floatPct)">{{ fmtPct(row.floatPct) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="110">
            <template #default="{ row }">
              <el-tag v-if="row.discipline === '违约'" type="danger" size="small">违约</el-tag>
              <el-tag v-else-if="row.discipline === '待执行'" type="warning" size="small">待执行</el-tag>
              <el-tag v-else-if="row.discipline === '遵守'" type="success" size="small">遵守</el-tag>
              <el-tag v-else type="info" size="small">持仓中</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="次日决策" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="next-plan">{{ row.nextDayPlan || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- ② 今日清仓 / 交易流水 -->
    <div class="sec" v-if="tab === 'closed' || tab === 'all'">
      <div class="sec-hd"><h2>📋 今日清仓 / 交易流水</h2><div class="note">{{ latestDate || '—' }}</div></div>
      <div class="sec-bd">
        <el-table :data="closedRows" style="width: 100%" empty-text="快照日无清仓记录">
          <el-table-column label="名称" min-width="100">
            <template #default="{ row }"><b>{{ row.stockName }}</b></template>
          </el-table-column>
          <el-table-column prop="action" label="动作" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.action || '—' }}</template>
          </el-table-column>
          <el-table-column label="板数" width="70">
            <template #default="{ row }">{{ row.boardNum ? (row.boardNum > 1 ? row.boardNum + '板' : '首板') : '—' }}</template>
          </el-table-column>
          <el-table-column label="盈亏" width="86">
            <template #default="{ row }"><span :class="pctClass(row.floatPct)">{{ fmtPct(row.floatPct) }}</span></template>
          </el-table-column>
          <el-table-column prop="plannedAction" label="预案" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.plannedAction || '—' }}</template>
          </el-table-column>
          <el-table-column label="延迟" width="76">
            <template #default="{ row }">
              <span :class="delayClass(row.delayDays)">{{ row.delayDays == null ? '—' : row.delayDays + '天' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="纪律分" width="86">
            <template #default="{ row }">
              <span class="n" :class="scoreClass(row.disciplineScore)">{{ row.disciplineScore ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="评价" min-width="110">
            <template #default="{ row }">
              <el-tag v-if="row.discipline === '遵守'" type="success" size="small">遵守</el-tag>
              <el-tag v-else-if="row.discipline === '违约'" type="danger" size="small">违约</el-tag>
              <el-tag v-else-if="row.discipline === '待执行'" type="warning" size="small">待执行</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- ③ 次日处理决策 -->
    <div class="sec" v-if="(tab === 'holding' || tab === 'all') && nextDayDecisions.length">
      <div class="sec-hd"><h2>📅 次日处理决策</h2><div class="note">★ 自动外溢 → 仪表盘待办 + 次日复盘页顶部</div></div>
      <div class="sec-bd">
        <div class="dec-row" v-for="d in nextDayDecisions" :key="d.id">
          <div class="dec-target">
            <b>{{ d.stockName }}</b>
            <div class="mini">{{ d.industry || '' }} {{ d.boardNum ? (d.boardNum > 1 ? d.boardNum + '板' : '首板') : '' }}</div>
            <div class="mini" v-if="d.floatPct != null">浮动 {{ fmtPct(d.floatPct) }}</div>
          </div>
          <div class="dec-cards">
            <div class="dec-i"><div class="c">次日高开</div><div class="a">{{ d.planOpen || '—' }}</div></div>
            <div class="dec-i"><div class="c">次日炸板</div><div class="a">{{ d.planBreak || '—' }}</div></div>
            <div class="dec-i"><div class="c">平开/低开</div><div class="a">{{ d.planLow || '—' }}</div></div>
            <div class="dec-i"><div class="c">次日跌停</div><div class="a">{{ d.planFall || '—' }}</div></div>
          </div>
        </div>
        <div class="mini" style="margin-top: 10px" v-if="!hasPlanColumns">未填四档计划时显示整段 nextDayPlan 文本；分档在每日复盘页的持仓台账里编辑。</div>
      </div>
    </div>

    <!-- ④ 标的全生命周期 -->
    <div class="sec">
      <div class="sec-hd"><h2>📁 标的全生命周期</h2><div class="note">标的维度 · 买入→持有→卖出→纪律评分 · 近一年</div></div>
      <div class="sec-bd">
        <el-table :data="lifecycleRows" style="width: 100%" empty-text="台账还没有记录；在每日复盘页录入持仓后这里自动聚合">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="life-wrap">
                <div class="life-head">
                  <b>{{ row.stockName }} {{ row.stockCode }} · 全生命周期</b>
                  <span class="mini">共 {{ row.timeline.length }} 条快照 · 最新：{{ row.lastRow.action || row.lastRow.status || '—' }}</span>
                </div>
                <div class="life">
                  <div class="life-i" v-for="t in row.timeline" :key="t.id">
                    <div class="d">{{ t.tradeDate }}</div>
                    <div class="e" :class="lifeCls(t)">{{ lifeEvent(t) }}</div>
                    <div class="p">{{ lifeNote(t) }}</div>
                  </div>
                </div>
                <div class="dec-cards" style="margin-top: 10px">
                  <div class="dec-i"><div class="c">快照数</div><div class="a">{{ row.timeline.length }}</div></div>
                  <div class="dec-i"><div class="c">最新浮动</div><div class="a" :class="pctClass(row.lastFloat)">{{ fmtPct(row.lastFloat) }}</div></div>
                  <div class="dec-i"><div class="c">纪律评价</div><div class="a">{{ row.lastRow.discipline || '—' }}</div></div>
                  <div class="dec-i"><div class="c">最新纪律分</div><div class="a" :class="scoreClass(row.lastScore)">{{ row.lastScore ?? '—' }}</div></div>
                </div>
                <div class="mini" style="margin-top: 8px" v-if="row.lastRow.nextDayPlan">次日决策：{{ row.lastRow.nextDayPlan }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="标的" min-width="110">
            <template #default="{ row }">
              <b>{{ row.stockName }}</b>
              <div class="mini">{{ row.stockCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="板块" width="90">
            <template #default="{ row }">{{ row.industry || '—' }}</template>
          </el-table-column>
          <el-table-column prop="buyDate" label="买入日" width="96" />
          <el-table-column label="卖出日" width="96">
            <template #default="{ row }">{{ row.sellDate || '—' }}</template>
          </el-table-column>
          <el-table-column label="板数" width="70">
            <template #default="{ row }">{{ row.boardNum ? (row.boardNum > 1 ? row.boardNum + '板' : '首板') : '—' }}</template>
          </el-table-column>
          <el-table-column label="盈亏" width="90">
            <template #default="{ row }"><span :class="pctClass(row.lastFloat)">{{ fmtPct(row.lastFloat) }}</span></template>
          </el-table-column>
          <el-table-column label="持有" width="76">
            <template #default="{ row }">{{ row.holdDays }}天</template>
          </el-table-column>
          <el-table-column label="纪律分" width="86">
            <template #default="{ row }">
              <span class="n" :class="scoreClass(row.lastScore)">{{ row.lastScore ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === '持仓' ? 'warning' : 'success'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- ⑤ 纪律统计与认知沉淀 -->
    <div class="sec" v-if="tab === 'stats' || tab === 'all'">
      <div class="sec-hd"><h2>📊 纪律统计与认知沉淀</h2><div class="note">近 {{ STAT_DAYS }} 个自然日 {{ statRows.length }} 行快照</div></div>
      <div class="sec-bd">
        <div class="cards" style="margin-bottom: 0; grid-template-columns: repeat(5, 1fr)">
          <div class="card">
            <div class="k">卖出端纪律分</div>
            <div class="v" :class="scoreClass(sellScore)">{{ sellScore == null ? '—' : sellScore }}</div>
            <div class="x">已清仓 {{ statClosed.length }} 笔均值</div>
          </div>
          <div class="card">
            <div class="k">持仓端纪律分</div>
            <div class="v" :class="scoreClass(holdScore)">{{ holdScore == null ? '—' : holdScore }}</div>
            <div class="x">持仓中均值</div>
          </div>
          <div class="card">
            <div class="k">违约次数</div>
            <div class="v" :class="brokenCount ? 'red' : 'grn'">{{ brokenCount }}</div>
            <div class="x">{{ brokenNames || '无违约记录' }}</div>
          </div>
          <div class="card">
            <div class="k">平均清仓延迟</div>
            <div class="v">{{ avgDelay == null ? '—' : avgDelay + '天' }}</div>
            <div class="x">0天=预案当期执行</div>
          </div>
          <div class="card">
            <div class="k">待裁决</div>
            <div class="v" :class="pendingRows.length ? 'yel' : 'grn'">{{ pendingRows.length }}</div>
            <div class="x">未执行决策数</div>
          </div>
        </div>
      </div>
      <div class="foot">口径说明：纪律分为每行快照的自评分均值；违约=该做没做（discipline=违约）；延迟=清仓距应做时点的天数。编辑回每日复盘页。</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { recordApi } from '../api/modules'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTradingCalendar } from '../utils/tradingCalendar'

const { loadTradingDays } = useTradingCalendar()

/** 页签：持仓中 / 已清仓 / 全部 / 纪律统计 */
const TABS = [
  { key: 'holding', label: '持仓中' },
  { key: 'closed', label: '已清仓' },
  { key: 'all', label: '全部' },
  { key: 'stats', label: '纪律统计' }
]
/** 纪律统计的回看窗口（自然日）。 */
const STAT_DAYS = 30
/** 近7日窗口：已实现/胜率/平均纪律分与顶部卡对齐。 */
const WEEK_DAYS = 7

const loading = ref(false)
const tab = ref('holding')
const rows = ref([]) // 全量台账，trade_date 升序
const pendingRows = ref([])

/** 快照日 = 台账里最近一个交易日（行是升序的，取末行日期）。 */
const latestDate = computed(() => (rows.value.length ? rows.value[rows.value.length - 1].tradeDate : ''))

/** 快照日当天的全部行。 */
const todayRows = computed(() => rows.value.filter(r => r.tradeDate === latestDate.value))
const holdingRows = computed(() => todayRows.value.filter(r => r.status === '持仓中'))
const closedRows = computed(() => todayRows.value.filter(r => r.status === '今日清仓'))

/** 顶部卡：持仓均值浮动。 */
const holdingAvgPct = computed(() => avgOf(holdingRows.value.map(r => r.floatPct)))
const holdingStatSub = computed(() => {
  const bad = holdingRows.value.filter(r => r.floatPct != null && r.floatPct < 0).length
  const good = holdingRows.value.filter(r => r.floatPct != null && r.floatPct >= 0).length
  return `${bad} 只浮亏 / ${good} 只浮盈`
})
const holdingPctSub = computed(() => holdingRows.value.map(r => r.stockName).join(' · ') || '无持仓')

function withinDays(list, days) {
  const cutoff = new Date()
  cutoff.setDate(cutoff.getDate() - days)
  const cutoffStr = cutoff.toISOString().slice(0, 10)
  return list.filter(r => r.tradeDate >= cutoffStr)
}
const weekRows = computed(() => withinDays(rows.value, WEEK_DAYS))
const weekClosed = computed(() => withinDays(rows.value.filter(r => r.status === '今日清仓'), WEEK_DAYS))
const weekRealizedAvg = computed(() => avgOf(weekClosed.value.map(r => r.floatPct)))
const weekWins = computed(() => weekClosed.value.filter(r => r.floatPct != null && r.floatPct > 0).length)
const weekWinRate = computed(() => (weekClosed.value.length ? Math.round((weekWins.value / weekClosed.value.length) * 100) : null))
const weekAvgScore = computed(() => avgOf(withinDays(rows.value, WEEK_DAYS).map(r => r.disciplineScore)))

/** 次日处理决策：快照日持仓中且有决策内容的行；四档有值用四档，否则整段 nextDayPlan 兜底。 */
const nextDayDecisions = computed(() =>
  holdingRows.value.filter(r => r.nextDayPlan || r.planOpen || r.planBreak || r.planLow || r.planFall))
const hasPlanColumns = computed(() => nextDayDecisions.value.some(r => r.planOpen || r.planBreak || r.planLow || r.planFall))

/**
 * 标的维度聚合：同一 stockCode 的各日快照串成一条生命周期。
 * 买入日=最早快照日，卖出日=最后一条"今日清仓"快照日；持有天数=去重快照日数。
 */
const lifecycleRows = computed(() => {
  const map = new Map()
  for (const r of rows.value) {
    let g = map.get(r.stockCode)
    if (!g) {
      g = { stockCode: r.stockCode, stockName: r.stockName, industry: '', boardNum: null, buyDate: r.tradeDate, sellDate: '', timeline: [], lastRow: r }
      map.set(r.stockCode, g)
    }
    if (r.tradeDate < g.buyDate) g.buyDate = r.tradeDate
    if (r.industry) g.industry = r.industry
    if (r.boardNum != null) g.boardNum = r.boardNum
    if (r.stockName) g.stockName = r.stockName
    if (r.status === '今日清仓' && r.tradeDate > g.sellDate) g.sellDate = r.tradeDate
    g.timeline.push(r)
    g.lastRow = r
  }
  return Array.from(map.values()).map(g => {
    const dates = new Set(g.timeline.map(t => t.tradeDate))
    const scores = g.timeline.map(t => t.disciplineScore).filter(v => v != null)
    const floats = g.timeline.map(t => t.floatPct).filter(v => v != null)
    return {
      ...g,
      holdDays: dates.size,
      lastScore: scores.length ? scores[scores.length - 1] : null,
      lastFloat: floats.length ? floats[floats.length - 1] : null,
      status: g.lastRow.status === '今日清仓' ? '已清' : '持仓'
    }
  }).sort((a, b) => (b.lastRow.tradeDate || '').localeCompare(a.lastRow.tradeDate || ''))
})

/** 纪律统计窗口。 */
const statRows = computed(() => withinDays(rows.value, STAT_DAYS))
const statClosed = computed(() => statRows.value.filter(r => r.status === '今日清仓'))
const sellScore = computed(() => avgOf(statClosed.value.map(r => r.disciplineScore)))
const holdScore = computed(() => avgOf(statRows.value.filter(r => r.status === '持仓中').map(r => r.disciplineScore)))
const brokenCount = computed(() => statRows.value.filter(r => r.discipline === '违约').length)
const brokenNames = computed(() => {
  const seen = []
  for (const r of statRows.value) {
    if (r.discipline === '违约' && !seen.includes(r.stockName)) seen.push(r.stockName)
  }
  return seen.slice(0, 4).join(' / ')
})
const avgDelay = computed(() => {
  const ds = statClosed.value.map(r => r.delayDays).filter(v => v != null)
  return ds.length ? Math.round(ds.reduce((a, b) => a + b, 0) / ds.length) : null
})

function avgOf(list) {
  const vals = list.filter(v => v != null)
  if (!vals.length) return null
  return Math.round((vals.reduce((a, b) => a + Number(b), 0) / vals.length) * 10) / 10
}
function fmtPct(v) {
  if (v == null) return '—'
  const n = Number(v)
  return (n > 0 ? '+' : '') + n + '%'
}
/** 颜色语义沿用 PRD 原型：绿=盈/优，红=亏/差。 */
function pctClass(v) {
  if (v == null) return ''
  const n = Number(v)
  return n > 0 ? 'grn' : n < 0 ? 'red' : ''
}
function scoreClass(v) {
  if (v == null) return ''
  return v >= 80 ? 'grn' : v >= 60 ? 'yel' : 'red'
}
function delayClass(v) {
  if (v == null) return ''
  return v === 0 ? 'grn' : v >= 2 ? 'red' : 'yel'
}
/** 生命周期时间轴：把一行快照翻译成 事件/注脚。 */
function lifeEvent(t) {
  if (t.status === '今日清仓') return t.action || '清仓'
  if (t.action) return t.action
  return '持有'
}
function lifeNote(t) {
  const parts = []
  if (t.industry) parts.push(t.industry)
  if (t.disciplineScore != null) parts.push('纪律 ' + t.disciplineScore)
  if (t.floatPct != null) parts.push(fmtPct(t.floatPct))
  return parts.join(' · ')
}
function lifeCls(t) {
  if (t.status === '今日清仓') return t.discipline === '违约' ? 'red' : 'grn'
  return 'blu'
}

/** 标记待裁决已执行：闭环回填真实动作。 */
async function markExecuted(row) {
  let action = ''
  try {
    const res = await ElMessageBox.prompt(`标记「${row.stockName}」的决策已执行，可回填真实动作：`, '标记执行', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: row.nextDayPlan || '',
      placeholder: '如：竞价清仓 / 反抽减亏'
    })
    action = (res.value || '').trim()
  } catch (e) {
    return
  }
  try {
    await recordApi.markPositionExecuted(row.id, action)
    ElMessage.success('已标记执行')
    loadAll()
  } catch (e) {
    ElMessage.error('标记失败')
  }
}

async function loadAll() {
  loading.value = true
  try {
    const [allRes, pendRes] = await Promise.all([
      recordApi.allPositions(365),
      recordApi.pendingPositions(undefined, 10).catch(() => ({ data: [] }))
    ])
    rows.value = Array.isArray(allRes.data) ? allRes.data : []
    pendingRows.value = Array.isArray(pendRes.data) ? pendRes.data : []
  } catch (e) {
    rows.value = []
    pendingRows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadTradingDays()
  loadAll()
})
</script>

<style scoped>
.positions-page { max-width: 1240px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; }
.page-header h2 { margin: 0; color: #e1e8ed; }
.page-sub { color: #8899a6; font-size: 12px; margin-top: 4px; }

/* 页签（沿用 PRD 原型的胶囊样式） */
.tabs { display: flex; gap: 6px; margin-bottom: 14px; }
.tab { padding: 5px 14px; border-radius: 6px; background: #1a2332; border: 1px solid #2a3a52; color: #8899a6; cursor: pointer; font-size: 12px; }
.tab.on { background: #3b82f6; border-color: #3b82f6; color: #fff; }

/* 统计卡 */
.cards { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin-bottom: 14px; }
.card { background: #1a2332; border: 1px solid #2a3a52; border-radius: 10px; padding: 12px 14px; }
.card .k { color: #8899a6; font-size: 11px; margin-bottom: 5px; }
.card .v { font-size: 19px; font-weight: 600; color: #e1e8ed; }
.card .x { font-size: 11px; color: #6e7681; margin-top: 3px; }

/* 区块 */
.sec { background: #1a2332; border: 1px solid #2a3a52; border-radius: 12px; margin-bottom: 16px; overflow: hidden; }
.sec-hd { display: flex; justify-content: space-between; align-items: center; padding: 11px 16px; border-bottom: 1px solid #2a3a52; background: #16202e; }
.sec-hd h2 { font-size: 14px; font-weight: 600; margin: 0; color: #e1e8ed; }
.sec-hd .note { font-size: 11px; color: #8899a6; }
.sec-bd { padding: 12px 16px; }
.foot { color: #6e7681; font-size: 11px; padding: 9px 16px; border-top: 1px solid #2a3a52; background: #16202e; }

/* 待裁决提醒条 */
.alert { background: rgba(210, 153, 34, 0.08); border: 1px solid rgba(210, 153, 34, 0.25); border-left: 3px solid #d29922; border-radius: 6px; padding: 9px 12px; margin-bottom: 14px; font-size: 12px; color: #c9d1d9; }
.alert b { color: #d29922; }
.alert-btn { margin-left: 4px; }

/* 次日处理决策 */
.dec-row { display: flex; gap: 12px; align-items: stretch; padding: 10px 0; border-bottom: 1px solid #21262d; }
.dec-row:last-of-type { border-bottom: none; }
.dec-target { width: 150px; flex-shrink: 0; display: flex; flex-direction: column; gap: 2px; justify-content: center; }
.dec-target b { color: #e1e8ed; }
.dec-cards { flex: 1; display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.dec-i { background: #0f1720; border: 1px solid #2a3a52; border-radius: 6px; padding: 9px; }
.dec-i .c { font-size: 11px; color: #8899a6; margin-bottom: 4px; }
.dec-i .a { font-size: 12px; font-weight: 600; color: #e1e8ed; }

/* 展开区：四档计划 + 生命周期时间轴 */
.plan-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; padding: 4px 12px 8px 48px; }
.plan-cell { background: #0f1720; border: 1px solid #2a3a52; border-radius: 6px; padding: 8px 10px; }
.plan-cell .c { font-size: 11px; color: #8899a6; margin-bottom: 3px; }
.plan-cell .a { font-size: 12px; color: #e1e8ed; }
.expand-note { color: #4ade80; font-size: 12px; padding: 0 12px 8px 48px; }
.life-wrap { padding: 4px 12px 8px 48px; }
.life-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; color: #e1e8ed; flex-wrap: wrap; gap: 6px; }
.life { display: flex; flex-wrap: wrap; }
.life-i { flex: 1; min-width: 96px; text-align: center; padding: 6px 4px; border-left: 1px solid #21262d; }
.life-i:first-child { border-left: none; }
.life-i .d { font-size: 10px; color: #6e7681; }
.life-i .e { font-size: 12px; margin-top: 3px; }
.life-i .p { font-size: 10px; color: #8899a6; margin-top: 2px; }

/* 数字颜色语义（PRD 原型同款） */
.grn { color: #3fb950; }
.red { color: #f85149; }
.yel { color: #d29922; }
.blu { color: #58a6ff; }
.n { font-weight: 600; }
.sn b { color: #e6ecf2; }
.mini { font-size: 11px; color: #6e7681; }
.next-plan { color: #ff9f45; }
</style>
