<template>
  <div class="waverider">
    <div class="page-head">
      <div>
        <h2>策略选股</h2>
        <p class="sub">
          WaveRider · 连板周期选股引擎。候选为 <b>T 日已涨停</b>的个股，
          收益基准取 <b>T+1 开盘价</b>——T 日收盘价在实盘买不到。
        </p>
      </div>
      <div class="head-actions">
        <el-button size="small" :loading="running" @click="runNow">立即运行</el-button>
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <div class="toolbar">
      <span class="lab">策略</span>
      <el-select v-model="strategyId" placeholder="选择策略" size="small" style="width: 200px"
        @change="loadAll">
        <el-option v-for="s in strategies" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>

      <span class="lab">交易日</span>
      <el-date-picker v-model="date" type="date" size="small" value-format="YYYY-MM-DD"
        placeholder="取最近一个已产出的交易日" :cell-class-name="cellClass" style="width: 170px"
        @change="loadCandidates" />

      <span class="lab">导出</span>
      <el-button size="small" text @click="doExport('md')">md</el-button>
      <el-button size="small" text @click="doExport('csv')">csv</el-button>

      <span v-if="runMeta" class="run-meta">
        {{ runMeta.date }} · {{ runMeta.status }}
        <template v-if="runMeta.cost != null"> · {{ runMeta.cost }} ms</template>
      </span>
    </div>

    <el-alert v-if="emptyReason" :title="emptyReason" type="warning" :closable="false" show-icon class="mb12" />
    <el-alert v-if="warnText" :title="warnText" type="info" :closable="false" show-icon class="mb12" />

    <!-- ---------- 候选池 ---------- -->
    <el-card class="blk" shadow="never">
      <template #header>
        <div class="blk-head">
          <span>候选池</span>
          <span class="blk-sub" v-if="candidates.length">
            共 {{ candidates.length }} 只 · 组合总仓位 {{ totalPosition }}%
            · 按「封单/额」升序排列，越靠前越不容易一字板、越好买进
            <template v-if="!hasT1">· T+1 表现待补写（今日尚未收盘）</template>
          </span>
        </div>
      </template>

      <el-table :data="candidates" size="small" stripe style="width: 100%">
        <el-table-column prop="rankNo" label="#" width="52" />
        <el-table-column prop="code" label="代码" width="80" />
        <el-table-column prop="name" label="名称" width="100" />
        <el-table-column label="连板" width="64">
          <template #default="{ row }">
            <span class="board">{{ row.board }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="topic" label="题材" min-width="110" show-overflow-tooltip />
        <el-table-column prop="positionType" label="身位" min-width="130" show-overflow-tooltip />
        <el-table-column label="封单/额" width="86">
          <template #default="{ row }">
            <span v-if="sealRatio(row) != null" class="num">{{ sealRatio(row).toFixed(2) }}%</span>
            <span v-else class="mut">—</span>
          </template>
        </el-table-column>
        <el-table-column label="得分" width="70">
          <template #default="{ row }">
            <span class="num">{{ row.score == null ? '—' : Number(row.score).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="建议仓位" width="88">
          <template #default="{ row }">
            <span class="num pos">{{ posText(row.suggestPosition) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.riskFlag" size="small" type="warning" effect="dark">{{ riskText(row.riskFlag) }}</el-tag>
            <span v-else class="mut">—</span>
          </template>
        </el-table-column>
        <el-table-column label="T+1 跳空" width="90">
          <template #default="{ row }">
            <span v-if="t1Of(row) && t1Of(row).gapPct != null" :class="['num', cls(t1Of(row).gapPct)]">
              {{ pct(t1Of(row).gapPct) }}
            </span>
            <span v-else class="mut">—</span>
          </template>
        </el-table-column>
        <el-table-column label="次日涨幅" width="90">
          <template #default="{ row }">
            <span v-if="t1Of(row) && t1Of(row).t1ChangePct != null" :class="['num', cls(t1Of(row).t1ChangePct)]">
              {{ pct(t1Of(row).t1ChangePct) }}
            </span>
            <span v-else class="mut">—</span>
          </template>
        </el-table-column>
        <el-table-column label="可执行收益" width="100">
          <template #default="{ row }">
            <span v-if="bPct(row) != null" :class="['num', cls(bPct(row))]">
              <b>{{ pct(bPct(row)) }}</b>
            </span>
            <span v-else class="mut">—</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <span v-if="!t1Of(row)" class="mut">待验证</span>
            <el-tag v-else-if="t1Of(row).promoted === 1" size="small" type="danger" effect="dark">晋级</el-tag>
            <el-tag v-else size="small" type="success" effect="dark">未连板</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!candidates.length" class="empty">
        当日无命中候选<template v-if="emptyReason">（原因见上方提示）</template>
      </div>
    </el-card>

    <!-- ---------- 漏斗 ---------- -->
    <el-card v-if="funnel.length" class="blk" shadow="never">
      <template #header><span>筛选漏斗</span></template>
      <div class="funnel">
        <div v-for="(f, i) in funnel" :key="f.key" class="funnel-step">
          <span class="f-lab">{{ f.key }}</span>
          <span class="f-val">{{ f.value }}</span>
          <span v-if="i < funnel.length - 1" class="f-arrow">→</span>
        </div>
      </div>
      <p class="fn">
        每一步都记数，是为了让「为什么只剩这几只」有答案；也是防止某个阈值悄悄变成死分支——
        调高 <code>min_board_count</code> 后如果这里长期是 0，说明那条规则已经不会命中了。
      </p>
    </el-card>

    <!-- ---------- 复盘 ---------- -->
    <el-card class="blk" shadow="never">
      <template #header>
        <div class="blk-head">
          <span>复盘（近 30 个自然日）</span>
          <span class="blk-sub" v-if="review">已验证样本 {{ (review.overall && review.overall.n) || 0 }} 条</span>
        </div>
      </template>

      <template v-if="review && review.overall && review.overall.n">
        <div class="kpis">
          <div class="kpi">
            <div class="k-lab">晋级率</div>
            <div class="k-val up">{{ pctOr(review.overall.promoteRate) }}</div>
          </div>
          <div class="kpi">
            <div class="k-lab">平均涨幅（收盘对收盘）</div>
            <div class="k-val" :class="cls(review.overall.avgChangeA)">{{ pctOr(review.overall.avgChangeA) }}</div>
            <div class="k-fn">信号强度，含买不到的隔夜跳空</div>
          </div>
          <div class="kpi">
            <div class="k-lab">平均涨幅（开盘买·收盘卖）</div>
            <div class="k-val" :class="cls(review.overall.avgChangeB)">{{ pctOr(review.overall.avgChangeB) }}</div>
            <div class="k-fn">可执行口径，这才是实盘的数</div>
          </div>
          <div class="kpi">
            <div class="k-lab">胜率</div>
            <div class="k-val">{{ pctOr(review.overall.winRateB) }}</div>
          </div>
          <div class="kpi">
            <div class="k-lab">盈亏比</div>
            <div class="k-val">{{ review.overall.profitLossRatio == null ? '—' : review.overall.profitLossRatio }}</div>
          </div>
          <div class="kpi">
            <div class="k-lab">平均跳空</div>
            <div class="k-val" :class="cls(review.overall.avgGap)">{{ pctOr(review.overall.avgGap) }}</div>
          </div>
        </div>

        <table class="bk">
          <thead>
            <tr>
              <th>跳空区间</th><th>样本</th><th>占比</th><th>晋级率</th>
              <th>平均涨幅（收盘口径）</th><th>平均涨幅（开盘口径）</th><th>胜率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in review.gapBuckets || []" :key="b.label">
              <td>{{ b.label }}</td>
              <td class="num">{{ b.n || 0 }}</td>
              <td class="num">{{ b.n ? ((b.n / review.overall.n) * 100).toFixed(1) + '%' : '—' }}</td>
              <td class="num">{{ pctOr(b.promoteRate) }}</td>
              <td class="num"><span :class="cls(b.avgChangeA)">{{ pctOr(b.avgChangeA) }}</span></td>
              <td class="num"><span :class="cls(b.avgChangeB)">{{ pctOr(b.avgChangeB) }}</span></td>
              <td class="num">{{ pctOr(b.winRateB) }}</td>
            </tr>
          </tbody>
        </table>
        <p class="fn">
          跳空越低越可执行，而可执行口径的收益对跳空高度极其敏感。注意「一字/高开」那一档：
          它在收盘口径下往往最好看，在开盘口径下却是负的——溢价在隔夜一次吃完了。
        </p>
      </template>
      <div v-else class="empty">还没有可复盘的样本（需要候选产生后再过一个交易日）</div>
    </el-card>

    <p class="disclaimer">
      本页为交易辅助工具输出，<b>不构成任何投资建议</b>。所有个股与数值取自历史行情，
      仅用于说明算法口径。短线交易风险极高，请结合自身风险承受能力谨慎决策。
    </p>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { waveriderApi } from '../api/modules'
import { useTradingCalendar } from '../utils/tradingCalendar'

const { cellClass } = useTradingCalendar()

const strategies = ref([])
const strategyId = ref(null)
const date = ref(null)
const candidates = ref([])
const t1Rows = ref([])
const emptyReason = ref('')
const warnText = ref('')
const funnel = ref([])
const review = ref(null)
const running = ref(false)
const runMeta = ref(null)

const hasT1 = computed(() => t1Rows.value.length > 0)

const totalPosition = computed(() => {
  const sum = candidates.value.reduce((a, c) => a + (Number(c.suggestPosition) || 0), 0)
  return (sum * 100).toFixed(1)
})

function t1Of(row) {
  return t1Rows.value.find((t) => t.code === row.code)
}

/** 可执行收益 = close(T+1)/open(T+1) − 1，由次日涨幅与跳空推出来，不回上游取价。 */
function bPct(row) {
  const t = t1Of(row)
  if (!t || t.t1ChangePct == null || t.gapPct == null) return null
  const base = 1 + Number(t.gapPct) / 100
  if (!base) return null
  return ((1 + Number(t.t1ChangePct) / 100) / base - 1) * 100
}

function pct(v) {
  if (v == null) return '—'
  const n = Number(v)
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}

function pctOr(v) {
  return v == null ? '—' : pct(v)
}

function posText(v) {
  if (v == null) return '—'
  return (Number(v) * 100).toFixed(2) + '%'
}

/**
 * 封单 ÷ 成交额，单位 %。这是候选池的排序依据——封单越薄，次日越不容易一字开盘，
 * 也就是越买得到。实测该比值 ≥300% 时次日一字概率 87%，所以它衡量的是「可执行性」而非涨幅预期。
 */
function sealRatio(row) {
  if (!row || !row.filterDetailJson) return null
  try {
    const r = JSON.parse(row.filterDetailJson).seal_ratio
    return r == null ? null : Number(r) * 100
  } catch (e) {
    return null
  }
}

/** 中国市场惯例：涨红跌绿。 */
function cls(v) {
  if (v == null) return ''
  const n = Number(v)
  if (n > 0) return 'up'
  if (n < 0) return 'down'
  return ''
}

function riskText(flag) {
  if (flag === 'YIZI_THIN') return '一字缩量'
  if (flag === 'HIGH_TURNOVER') return '过度换手'
  if (flag === 'DRAGON_DEAD') return '龙头断板'
  return flag
}

async function loadStrategies() {
  try {
    const res = await waveriderApi.strategies()
    strategies.value = (res && res.data) || []
    if (!strategyId.value && strategies.value.length) {
      strategyId.value = strategies.value[0].id
    }
  } catch (e) {
    strategies.value = []
  }
}

async function loadCandidates() {
  if (!strategyId.value) return
  try {
    const res = await waveriderApi.candidates(strategyId.value, date.value)
    const d = (res && res.data) || {}
    candidates.value = d.candidates || []
    t1Rows.value = d.t1 || []
    emptyReason.value = d.emptyReason || ''
    funnel.value = toFunnel(d.funnel)
    date.value = d.tradeDate || date.value
    warnText.value = d.runWarning ? '本次运行告警：' + d.runWarning : ''
    if (d.runStatus) {
      runMeta.value = { date: d.tradeDate, status: d.runStatus, cost: null }
    }
  } catch (e) {
    candidates.value = []
    t1Rows.value = []
  }
}

async function loadReview() {
  if (!strategyId.value) return
  const to = date.value || new Date().toISOString().slice(0, 10)
  const from = shiftDays(to, -30)
  try {
    const res = await waveriderApi.review(strategyId.value, from, to)
    review.value = (res && res.data) || null
  } catch (e) {
    review.value = null
  }
}

function toFunnel(obj) {
  if (!obj) return []
  return Object.keys(obj).map((k) => ({ key: k, value: obj[k] }))
}

function shiftDays(iso, days) {
  const d = new Date(iso + 'T00:00:00')
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

async function loadAll() {
  if (!strategyId.value) return
  await loadCandidates()
  await loadReview()
}

async function runNow() {
  if (!strategyId.value) {
    ElMessage.warning('先选一个策略')
    return
  }
  running.value = true
  try {
    const res = await waveriderApi.run({
      strategyId: strategyId.value,
      tradeDate: date.value || null,
      dryRun: false
    })
    const d = (res && res.data) || {}
    runMeta.value = { date: d.tradeDate, status: d.status, cost: null }
    ElMessage.success('运行完成：产出 ' + (d.candidateCount || 0) + ' 只候选')
    await loadAll()
  } finally {
    running.value = false
  }
}

async function doExport(format) {
  if (!strategyId.value) return
  const res = await waveriderApi.exportCandidates(strategyId.value, date.value, format)
  const d = (res && res.data) || {}
  const blob = new Blob([d.content || ''], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = d.filename || ('waverider.' + format)
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(async () => {
  await loadStrategies()
  if (strategyId.value) {
    await loadAll()
  }
})
</script>

<style scoped>
.waverider {
  color: #cbd5e0;
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-head h2 {
  margin: 0 0 6px;
  color: #e1e8ed;
  font-size: 20px;
}
.sub {
  margin: 0;
  color: #8899a6;
  font-size: 13px;
  line-height: 1.6;
}
.sub b {
  color: #fbbf24;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  background: #1a2332;
  border: 1px solid #2d3748;
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 14px;
}
.toolbar .lab {
  color: #8899a6;
  font-size: 12.5px;
}
.run-meta {
  margin-left: auto;
  color: #6b7c8c;
  font-size: 12.5px;
}
.blk {
  background: #1a2332;
  border: 1px solid #2d3748;
  margin-bottom: 14px;
}
.blk-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.blk-sub {
  color: #8899a6;
  font-size: 12.5px;
  font-weight: 400;
}
.empty {
  padding: 24px;
  text-align: center;
  color: #6b7c8c;
  font-size: 13px;
}
.num {
  font-variant-numeric: tabular-nums;
}
.up {
  color: #f87171;
}
.down {
  color: #34d399;
}
.mut {
  color: #6b7c8c;
}
.board {
  display: inline-block;
  min-width: 22px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(251, 191, 36, 0.14);
  color: #fbbf24;
  font-weight: 600;
  text-align: center;
}
.pos {
  color: #fbbf24;
}
.funnel {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.funnel-step {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #16202e;
  border: 1px solid #2d3748;
  border-radius: 6px;
  padding: 6px 10px;
}
.f-lab {
  color: #8899a6;
  font-size: 12.5px;
}
.f-val {
  color: #e1e8ed;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.f-arrow {
  color: #4a5568;
}
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.kpi {
  background: #16202e;
  border: 1px solid #2d3748;
  border-radius: 8px;
  padding: 12px 14px;
}
.k-lab {
  color: #8899a6;
  font-size: 12.5px;
  margin-bottom: 6px;
}
.k-val {
  color: #e1e8ed;
  font-size: 19px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.k-fn {
  margin-top: 4px;
  color: #6b7c8c;
  font-size: 11.5px;
  line-height: 1.4;
}
.bk {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
.bk th,
.bk td {
  border-bottom: 1px solid #2d3748;
  padding: 7px 10px;
  text-align: left;
}
.bk th {
  color: #8899a6;
  font-weight: 500;
  background: #16202e;
}
.bk td {
  color: #cbd5e0;
}
.bk td.num {
  text-align: right;
}
.fn {
  margin: 10px 0 0;
  color: #6b7c8c;
  font-size: 12px;
  line-height: 1.6;
}
.fn code {
  color: #fbbf24;
  background: rgba(251, 191, 36, 0.1);
  padding: 1px 4px;
  border-radius: 3px;
}
.disclaimer {
  margin: 6px 0 0;
  color: #6b7c8c;
  font-size: 12px;
  line-height: 1.7;
}
.disclaimer b {
  color: #8899a6;
}
.mb12 {
  margin-bottom: 12px;
}
</style>
