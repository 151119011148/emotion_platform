<template>
  <div class="waverider">
    <div class="page-head">
      <div>
        <h2>策略选股</h2>
        <p class="sub">
          WaveRider · 连板周期选股引擎。候选为 <b>T 日已涨停</b>的个股。
          本策略是 <b>打板</b>策略：买点在 T 日涨停板上，收益基准取 <b>T 日收盘价</b>（即 T 日涨停价）；
          同时并排给出「D+1 开盘再接」的备选口径供对比。
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
            共 {{ candidates.length }} 只<template v-if="nodeTagCount">（其中来自节点追踪 {{ nodeTagCount }} 只）</template>
            · 组合总仓位 {{ totalPosition }}%
            · 按「封单强度」<b>降序</b>排列，封单越强越靠前（打板口径下唯一跨档单调的正向因子）
            · 题材取 <b>通达信概念板块</b>，按当日该题材的涨停家数降序
            <template v-if="!hasT1">· T+1 表现待补写（今日尚未收盘）</template>
          </span>
        </div>
      </template>

      <el-table :data="candidates" size="small" stripe :row-class-name="rowClass" style="width: 100%">
        <el-table-column prop="rankNo" label="#" width="52" />
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }">
            <div class="nm">
              <el-tooltip placement="top" :show-after="150">
                <template #content>
                  <div>代码 {{ row.code }}</div>
                </template>
                <span class="nm-text">{{ row.name }}</span>
              </el-tooltip>
              <span class="nm-tags">
                <el-tag v-for="t in nodeTagsOf(row)" :key="t.kind" size="small" effect="dark"
                  :type="t.type" :title="t.title">{{ t.text }}</el-tag>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="连板" width="64">
          <template #default="{ row }">
            <span class="board">{{ row.board }}</span>
          </template>
        </el-table-column>
        <el-table-column label="题材" min-width="190">
          <template #default="{ row }">
            <span v-if="row.tdxThemes && row.tdxThemes.length" class="thm">
              <el-tag v-for="t in topThemes(row)" :key="t.name" size="small" effect="plain"
                class="tag-theme" :title="themeTip(t)">{{ t.name }}</el-tag>
              <span v-if="restThemes(row).length" class="more"
                :title="restTip(row)">+{{ restThemes(row).length }}</span>
            </span>
            <span v-else class="mut"
              title="这只票在通达信题材索引里没有记录，退回引擎分组用的行业">{{ row.topic || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="封单强度" width="120">
          <template #default="{ row }">
            <span v-if="sealRatio(row) != null" class="num">{{ sealRatio(row).toFixed(2) }}%</span>
            <span v-else class="mut">—</span>
            <el-tag v-if="needQueue(row)" size="small" type="warning" effect="plain" class="qtag">排队</el-tag>
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
        <el-table-column label="警示" width="128">
          <template #default="{ row }">
            <span v-if="row.alertFlag || row.riskFlag" class="alerts">
              <el-tag v-if="row.alertFlag" size="small" type="danger" effect="dark"
                :title="alertTip(row.alertFlag)">{{ alertText(row.alertFlag) }}</el-tag>
              <el-tag v-if="row.riskFlag" size="small" type="warning" effect="dark"
                title="风险项参与仓位折算：带风险标的建议仓位折半">{{ riskText(row.riskFlag) }}</el-tag>
            </span>
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
        <el-table-column label="打板收益" width="94">
          <template #default="{ row }">
            <span v-if="t1Of(row) && t1Of(row).t1ChangePct != null" :class="['num', cls(t1Of(row).t1ChangePct)]">
              {{ pct(t1Of(row).t1ChangePct) }}
            </span>
            <span v-else class="mut">—</span>
          </template>
        </el-table-column>
        <el-table-column label="接盘收益" width="94">
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

      <p class="fn">
        标签怎么看：名称后面 <b>节点票 / 锚定龙头 / D0候选</b>＝它出现在「节点追踪」的某条节点里，
        悬浮看是哪一段周期、什么状态（鼠标停在名称上显示代码）；
        蓝底的 <b>题材</b>＝通达信概念板块，按当日该题材的涨停家数降序，只铺前 3 个，
        多的折成 <b>+N</b>（悬浮看全部与家数）。
        <b>警示</b>列里红底的 <b>一字断魂刀</b>＝今日与昨日连续锁死、小盘、封成比高、换手低，
        大概率<b>排不到队</b>（与「连板生态」页同一判据；只影响买不买得到，不剔除也不降权）；
        琥珀色的 <b>一字缩量 / 过度换手</b> 才是要折算仓位的风险项。
      </p>

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
            <div class="k-lab">打板收益（T 日封板买入）</div>
            <div class="k-val" :class="cls(review.overall.avgChangeA)">{{ pctOr(review.overall.avgChangeA) }}</div>
            <div class="k-fn">本策略真实口径，前提是当天排到了队</div>
          </div>
          <div class="kpi">
            <div class="k-lab">接盘收益（D+1 开盘买）</div>
            <div class="k-val" :class="cls(review.overall.avgChangeB)">{{ pctOr(review.overall.avgChangeB) }}</div>
            <div class="k-fn">备选打法：放弃打板、次日开盘再接</div>
          </div>
          <div class="kpi">
            <div class="k-lab">胜率（打板 / 接盘）</div>
            <div class="k-val">
              {{ pctOr(review.overall.winRateA) }}<span class="mut"> / </span>{{ pctOr(review.overall.winRateB) }}
            </div>
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
              <th>打板收益</th><th>接盘收益</th><th>接盘胜率</th>
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
          跳空越低越「买得到」，而接盘口径的收益对跳空高度极其敏感。注意「一字/高开」那一档：
          它在打板口径下最好看，在接盘口径下却是负的——溢价在隔夜一次吃完了。
          这正是打板与追高的分野。
        </p>

        <table class="bk bk2">
          <thead>
            <tr>
              <th>封单强度（封单额 ÷ 成交额）</th><th>样本</th><th>晋级率</th>
              <th>打板收益</th><th>打板胜率</th><th>接盘收益</th><th>接盘胜率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="k in review.bySealBucket || []" :key="k.label">
              <td>{{ k.label }}</td>
              <td class="num">{{ k.n || 0 }}</td>
              <td class="num">{{ pctOr(k.promoteRate) }}</td>
              <td class="num"><span :class="cls(k.avgChangeA)">{{ pctOr(k.avgChangeA) }}</span></td>
              <td class="num">{{ pctOr(k.winRateA) }}</td>
              <td class="num"><span :class="cls(k.avgChangeB)">{{ pctOr(k.avgChangeB) }}</span></td>
              <td class="num">{{ pctOr(k.winRateB) }}</td>
            </tr>
          </tbody>
        </table>
        <p class="fn">
          这张表就是「封单锁死不再剔除、反而置顶」的依据本身：以 <b>T 日涨停价</b>（本策略买点）买入时，
          平均收益与胜率沿封单强度<b>严格单调递增</b>；换成 <b>D+1 开盘价</b>买则单调递减。
          所以封单强度只在「打板」口径下是正向因子——「买不进」这件事，只对「等 D+1 再接」成立。
          清单里带 <b>排队</b> 标签的，就是封单 ≥150%、需要集合竞价挂涨停价排队的样本。
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

/**
 * 「来自节点追踪」的标记：后端读侧把命中的 t_node_event 挂在 row.nodeTags 上（瞬态，不落库）。
 * 三种角色：节点票（它是这条节点的 node_stock）、锚定龙头、D0 候选。
 * 这里是纯展示层——只做分组与文案，判据一律在服务端。
 */
const NODE_KIND_TEXT = { NODE_STOCK: '节点票', ANCHOR: '锚定龙头', D0_CAND: 'D0候选' }
const NODE_KIND_TYPE = { NODE_STOCK: 'danger', ANCHOR: 'warning', D0_CAND: 'info' }

const nodeTagCount = computed(
  () => candidates.value.filter((c) => c.nodeTags && c.nodeTags.length).length
)

/**
 * 同一角色可能命中多条节点事件（一只票既当过甲节点的节点票、又是乙节点的 D0 候选），
 * 合成一个标签显示数量，来源明细放在悬浮里——列宽有限，塞不下每条事件的 D0 与状态。
 */
function nodeTagsOf(row) {
  const list = (row && row.nodeTags) || []
  const byKind = {}
  for (const t of list) {
    if (!byKind[t.kind]) byKind[t.kind] = []
    byKind[t.kind].push(t)
  }
  return Object.keys(byKind).map((kind) => {
    const items = byKind[kind]
    const text = NODE_KIND_TEXT[kind] || kind
    const title = items.map((x) => {
      const parts = ['D0 ' + (x.d0Date || '—')]
      if (x.status) parts.push(x.status)
      if (x.anchorStock) parts.push('锚龙 ' + x.anchorStock)
      if (x.theme) parts.push(x.theme)
      return text + '：' + parts.join(' · ')
    }).join('\n')
    return {
      kind,
      text: items.length > 1 ? text + '×' + items.length : text,
      type: NODE_KIND_TYPE[kind] || 'info',
      title
    }
  })
}

/**
 * 执行预警的文案。后端只回代码，文案在这一层。
 *
 * <p>它和 {@code riskFlag} 不是一回事：预警说的是「大概率买不进」，风险项说的是
 * 「质地有风险、要折算仓位」。两者会同时出现在「警示」格里，所以是两个标签并排。
 */
function alertText(flag) {
  if (flag === 'DUANDAO') return '一字断魂刀'
  return flag
}

/**
 * 一字断魂刀的悬浮解释。必须讲清「买不进」不等于「不该选」——
 * 判据与「连板生态」页完全同一份（后端共用一个 isDuanDao），这里只做说明，不重算。
 */
function alertTip(flag) {
  if (flag === 'DUANDAO') {
    return '今日与昨日连续锁死（首封≤09:30:30 且 0 炸板）＋流通≤35亿＋(封单≥10亿 或 封成比≥3)＋换手<5%；'
      + '判据与「连板生态」页一致。它大概率排不到队，只能集合竞价挂涨停价。'
      + '封单锁死恰是本策略最强的正向因子，所以不剔除、不降权、不改排序。'
  }
  return ''
}

/**
 * 题材标签最多铺几个。
 *
 * <p>一只涨停票实测带 1~11 个通达信题材，全铺出来这一格就成了一堵墙。
 * 后端已经按「当日该题材的涨停家数」降序排好，所以前 3 个就是最热的那几个。
 */
const THEME_SHOW = 3

function topThemes(row) {
  return ((row && row.tdxThemes) || []).slice(0, THEME_SHOW)
}

function restThemes(row) {
  return ((row && row.tdxThemes) || []).slice(THEME_SHOW)
}

/** 一条题材的悬浮文案：简称(全称) · 今日涨停 N 家 · 板块指数 880xxx。 */
function themeTip(t) {
  if (!t) return ''
  const head = t.fullName && t.fullName !== t.name ? t.name + '（' + t.fullName + '）' : t.name
  const parts = [head, '今日涨停 ' + (t.ztCount || 0) + ' 家']
  if (t.indexCode) parts.push('板块指数 ' + t.indexCode)
  return parts.join(' · ')
}

function restTip(row) {
  return restThemes(row).map(themeTip).join('\n')
}

/** 命中节点追踪的行加左侧红条——「标记不够明显」最直接的解法是给它一条能扫到的色。 */
function rowClass({ row }) {
  return row && row.nodeTags && row.nodeTags.length ? 'row-node' : ''
}

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
 * 封单强度 = 封单额 ÷ 成交额，单位 %。这是候选池的排序依据（<b>降序</b>）：
 * 在本策略真实的买点（T 日涨停板）上，它是唯一跨档单调的正向因子——
 * 实测五档打板收益 +1.71 / +3.55 / +4.37 / +5.40 / +7.56 %、胜率 53% → 87%。
 * 它同时是「排不排得到队」的指示：≥150% 的样本 97.5% 在 T 日开盘即封。
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

/**
 * 「需排队」标记：封单 ≥150% 的样本实测 97.5% 在 T 日开盘即封，
 * 盘中挂单基本排不到，只能集合竞价挂涨停价。这只影响「买不买得到」，不影响排序。
 */
function needQueue(row) {
  const r = sealRatio(row)
  return r != null && r >= 150
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

/* 命中节点追踪的行左侧色条。用 box-shadow 内描边而不是 border-left：
   它不参与表格自身的边框合并，也不会把行高顶开。 */
.waverider :deep(.el-table__row.row-node > td:first-child) {
  box-shadow: inset 3px 0 0 #f87171;
}
/* 名称格 = 股票名 + 节点标签；题材格 = 通达信题材标签。
   都允许换行：窄屏下一行塞不下宁可折行，也不要横向撑破表格。 */
.nm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px 6px;
}
.nm-text {
  color: #e1e8ed;
  font-weight: 600;
  cursor: help;
  border-bottom: 1px dashed rgba(136, 153, 166, 0.45);
}
.nm-tags,
.thm {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px;
}
.more {
  color: #8899a6;
  font-size: 11.5px;
  cursor: help;
}

/* 题材标签是 effect="plain"，plain 的底色落在 Element 的浅色兜底上，
   深色行里就是一颗白药丸。直接改写标签自身的语义变量，比跟 Element 的选择器比权重稳；
   多套一层容器选择器，是为了在权重上稳压 .el-tag--plain.el-tag--primary（同理见文件末尾的 .qtag）。 */
.waverider .thm .tag-theme {
  --el-tag-bg-color: rgba(56, 189, 248, 0.1);
  --el-tag-border-color: rgba(56, 189, 248, 0.34);
  --el-tag-text-color: #7dd3fc;
}
.qtag {
  margin-left: 5px;
}
/* 「警示」格可能同时挂预警与风险两个标签：窄列里换行排，别横向撑破表格。 */
.alerts {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 3px;
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
.bk2 {
  margin-top: 18px;
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

/* ===== 本页独有的几处 Element 浅色兜底 =====
   这几个节点取的是 Element「浅色主题专用」的兜底变量（--el-fill-color-blank / --el-color-*-light-9），
   App.vue 的表格变量块管不到；也正因为它们只在本页出现在深色底上，才只在本页压。
   不动全站：改全站等于所有页面的输入框、告警条一起换观感，该单独决策。 */

/* 运行告警条：is-light 的底色取 --el-alert-bg-color（信息档 #f4f4f5、警告档 #fdf6ec），
   深色底上就是一整条亮带。换成暗底 + 同色系描边，语义色（图标/文字）保留。 */
.waverider :deep(.el-alert.is-light) {
  border: 1px solid #2d3748;
  background-color: #16202e;
}
.waverider :deep(.el-alert--warning.is-light) {
  border-color: rgba(251, 191, 36, .38);
  background-color: rgba(251, 191, 36, .10);
}
.waverider :deep(.el-alert.is-light .el-alert__title) {
  color: #cbd5e0;
}
.waverider :deep(.el-alert--warning.is-light .el-alert__title) {
  color: #fbbf24;
}
.waverider :deep(.el-alert__icon) {
  color: inherit;
}

/* 「排队」标记是 effect="plain" 的 el-tag，plain 的底色落在 --el-fill-color-blank（#fff），
   深色行里就是一颗纯白药丸。直接改写标签自身的语义变量，
   比跟 Element 的选择器比权重稳（作用域属性让它天然高一级）。 */
.waverider .qtag {
  --el-tag-bg-color: rgba(251, 191, 36, .12);
  --el-tag-border-color: rgba(251, 191, 36, .45);
  --el-tag-text-color: #fbbf24;
}
</style>
