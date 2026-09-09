<template>
  <div class="review-page">
    <div class="page-header">
      <h2>每日复盘</h2>
      <el-tag v-if="recordId" type="success">已录入</el-tag>
      <el-tag v-else type="warning">未录入</el-tag>
      <span class="header-spacer"></span>
      <el-button size="small" :loading="exportingDoc" :disabled="!form.tradeDate"
        @click="handleExportDoc">导出复盘文档</el-button>
    </div>

    <div class="review-grid">
      <div class="form-section">
        <el-form :model="form" label-position="top" ref="formRef" :rules="rules">
          <el-form-item label="交易日期" prop="tradeDate">
            <el-date-picker v-model="form.tradeDate" type="date" value-format="YYYY-MM-DD"
              placeholder="选择日期" style="width: 100%" />
          </el-form-item>

          <el-divider content-position="left">九维打分</el-divider>

          <p class="sub-status">{{ subStatus }}</p>
          <p v-if="subError" class="panel-warn">{{ subError }}</p>
          <p v-if="!formReady" class="panel-warn">
            这天的记录没读回来：下面 8 格人工覆盖<b>不会</b>发出（发出去等于把你已存的覆盖洗掉），
            已有这一行时点「更新记录」会被直接拦下。切到别的日期再切回来、或刷新页面才能存。
          </p>

          <div class="dim-grid">
            <div v-for="group in dimRows" :key="'dim' + group.dim" class="dim-group">
              <div class="dim-title">
                <span class="dim-no">{{ group.dim }}</span>
                <span class="dim-name">{{ group.name }}</span>
                <span class="dim-score" :class="{ unscored: group.score == null }">
                  {{ group.score == null ? '未评' : `${group.score} 分 ×${group.weight}` }}
                </span>
              </div>

              <div v-for="cell in group.cells" :key="cell.key" class="dim-cell"
                :class="{ covered: cell.kind === 'override' && form[cell.key] != null, 'dim-cell-tall': !!cell.prop }">
                <span class="cell-label">{{ cell.label }}<span v-if="cell.required" class="cell-req">*</span></span>

                <span v-if="cell.kind === 'readonly'" class="cell-auto">{{ cell.text }}</span>

                <el-form-item v-else-if="cell.kind === 'select'" :prop="cell.prop" label-width="0"
                  class="cell-item cell-item-wide">
                  <el-select v-model="form[cell.key]" size="small" placeholder="未判断（这一维未评）">
                    <el-option v-for="opt in cell.options" :key="opt.value" :label="opt.label" :value="opt.value" />
                  </el-select>
                </el-form-item>

                <template v-else>
                  <span v-if="cell.kind === 'override'" class="cell-auto">{{ cell.autoText }}</span>
                  <el-form-item :prop="cell.prop" label-width="0" class="cell-item">
                    <el-input-number v-model="form[cell.key]" :min="cell.min" :max="cell.max"
                      :precision="cell.precision" :step="cell.step" :placeholder="cell.placeholder"
                      controls-position="right" size="small" />
                  </el-form-item>
                  <el-button v-if="cell.kind === 'override' && form[cell.key] != null" size="small" text
                    @click="form[cell.key] = null">退回自动</el-button>
                  <span v-else-if="cell.kind === 'override'" class="cell-plain">用自动</span>
                </template>

                <p v-if="cell.note" class="cell-note">{{ cell.note }}</p>
              </div>

              <div v-if="group.list && group.list.length" class="cell-list">
                <span v-for="s in group.list" :key="s.code" class="list-item"
                  :title="`${s.name} ${s.code} · 自涨停回撤 ${s.pullback}% · 收盘 ${s.pct}%${s.industry ? ' · ' + s.industry : ''}`">
                  {{ s.name }}<i>-{{ s.pullback }}%</i>
                </span>
                <span v-if="group.listHidden" class="list-more">另有 {{ group.listHidden }} 家</span>
              </div>
              <p v-else-if="group.listNote" class="cell-note">{{ group.listNote }}</p>

              <p v-if="group.note" class="dim-note">{{ group.note }}</p>
            </div>
          </div>

          <el-form-item>
            <el-button type="primary" :loading="saving" @click="handleSave" size="large">
              {{ recordId ? '更新记录' : '提交记录' }}
            </el-button>
          </el-form-item>
        </el-form>
      </div>

      <div class="score-section">
        <div class="score-card" v-if="savedRecord">
          <h3>打分结果</h3>
          <div class="score-grid">
            <div class="score-item" v-for="item in scoreItems" :key="item.key">
              <span class="label">{{ item.label }}</span>
              <span v-if="item.score == null" class="unscored">未评</span>
              <span v-else-if="item.score < 0" class="minus">{{ item.score }}</span>
              <span v-else class="dots">
                <span v-for="i in 3" :key="i" :class="{ active: i <= item.score }"></span>
              </span>
            </div>
          </div>
          <div class="total-row">
            <span>总分：{{ totalText }}</span>
            <span class="temp">温度：{{ tempText }}</span>
          </div>
          <div class="stage-row">
            <template v-if="savedRecord.stage">
              <el-tag :type="stageTagType" size="large">{{ savedRecord.stage }}</el-tag>
              <span class="direction">{{ savedRecord.stageDirection }}</span>
            </template>
            <span v-else class="insufficient">
              仅 {{ savedRecord.scoredDims || 0 }} 维参与打分，不足 5 维不出阶段
            </span>
          </div>
        </div>

        <div class="fetch-card">
          <h3>行情数据</h3>
          <div class="fetch-row">
            <el-button type="success" plain :loading="fetching" @click="handleFetchMarket(false)">
              拉取行情
            </el-button>
            <el-button v-if="snapshot" :loading="fetching" text @click="handleFetchMarket(true)">
              跳过缓存重拉
            </el-button>
          </div>
          <p class="field-note">
            这个按钮只取公开市场的那七个数，取回来直接落进左栏对应的维里。
            分档溢价三组、封板率与回封率、阵眼分、异动监管那两个数不用点，切日期就会自己取。
          </p>

          <el-alert v-if="snapshot" class="fetch-panel" :closable="false" show-icon
            :type="missingList.length ? 'warning' : 'success'">
            <template #title>
              <span class="panel-title">
                {{ snapshot.tradeDate }}：已自动填充 {{ filledCount }} / 7 项
              </span>
              <el-tag v-if="snapshot.fromCache" size="small" type="info" effect="plain">缓存</el-tag>
              <el-tag v-if="snapshot.live" size="small" type="danger" effect="plain">盘中未收盘</el-tag>
            </template>
            <div class="panel-body">
              <p v-if="missingList.length">
                <span class="panel-key">仍需手工</span>{{ labelList(missingList) }}
              </p>
              <p>
                <span class="panel-key">人工判断</span>{{ labelList(snapshot.manualFields) }}
              </p>
              <p v-if="snapshot.indexTotal != null">
                <span class="panel-key">指数收盘</span>{{ snapshot.indexFilled ?? 0 }} / {{ snapshot.indexTotal }}
                <span class="panel-sub">腾讯日 K，缺才取；齐了这次就一个请求都不发</span>
              </p>
              <p v-if="missingList.length" class="panel-warn">
                留空＝该维不评：它不进分子，但分母仍是 39，所以在温度上就是一个 0 分——
                左栏每维标题写的「未评」是唯一能把它和真 0 分分清的地方。缺得越多温度越不稳，少于 5 维不出阶段。
              </p>
              <p v-for="(item, i) in snapshot.notes" :key="'note' + i" class="panel-note">{{ item }}</p>
              <p v-for="(item, i) in snapshot.warnings" :key="'warn' + i" class="panel-warn">{{ item }}</p>
            </div>
          </el-alert>
        </div>
      </div>
    </div>

    <div class="ledger-section" v-if="detail">
      <div class="ledger-card">
        <div class="card-head">
          <h3>持仓台账</h3>
          <span class="header-spacer"></span>
          <el-button size="small" @click="addPositionRow">加一行</el-button>
          <el-button size="small" type="primary" plain :loading="savingPos" @click="savePositions">保存台账</el-button>
        </div>
        <el-table :data="posRows" size="small" empty-text="这天还没有持仓，点「加一行」录">
          <el-table-column label="代码" width="96">
            <template #default="{ row }"><el-input v-model="row.stockCode" size="small" placeholder="6位" /></template>
          </el-table-column>
          <el-table-column label="名称" width="110">
            <template #default="{ row }"><el-input v-model="row.stockName" size="small" placeholder="以代码表为准" /></template>
          </el-table-column>
          <el-table-column label="成本" width="110">
            <template #default="{ row }">
              <el-input-number v-model="row.costPrice" size="small" :min="0" :precision="2" :controls="false"
                style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="现价" width="110">
            <template #default="{ row }">
              <el-input-number v-model="row.currentPrice" size="small" :min="0" :precision="2" :controls="false"
                style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="浮动%" width="110">
            <template #default="{ row }">
              <el-input-number v-model="row.floatPct" size="small" :precision="2" :controls="false"
                style="width: 100%" placeholder="留空自动" />
            </template>
          </el-table-column>
          <el-table-column label="动作" width="110">
            <template #default="{ row }"><el-input v-model="row.action" size="small" placeholder="今日实际" /></template>
          </el-table-column>
          <el-table-column label="应做" width="110">
            <template #default="{ row }"><el-input v-model="row.plannedAction" size="small" placeholder="计划" /></template>
          </el-table-column>
          <el-table-column label="纪律" width="110">
            <template #default="{ row }">
              <el-select v-model="row.discipline" size="small" clearable placeholder="未填">
                <el-option v-for="d in DISCIPLINES" :key="d" :label="d" :value="d" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="删" width="52" align="center">
            <template #default="{ $index }">
              <el-button size="small" text type="danger" @click="posRows.splice($index, 1)">×</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="detail-hint">
          保存是<b>整表替换</b>当天的行：删到空再保存 = "这天清仓了"，这件事 md 导入做不到（解析器拒收空的 `持仓:` 键）。
          名称以 A股代码表反查为准；浮动% 填了就用你的，留空才由成本/现价算。
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { recordApi, marketApi, importApi } from '../api/modules'
import { CARD_ORDER, DIMS, survivalBandOf } from '../utils/scores'
import { ElMessage } from 'element-plus'

/** 后端 missing / manualFields 用的是实体字段名，面板要显示中文。 */
const MARKET_LABELS = {
  maxConsecutiveLimit: '连板高度',
  limitUpCount: '涨停家数',
  limitDownCount: '跌停家数',
  yesterdayLimitPremium: '昨日涨停溢价',
  brokenBoardRate: '炸板率',
  bigLossCount: '大面数',
  totalVolume: '两市成交额',
  mainTheme: '主线题材',
  leadingStock: '总龙头',
  leadingStockStatus: '龙头状态',
  scoreTheme: '主线明确度'
}

const formRef = ref(null)
const saving = ref(false)
const recordId = ref(null)
const savedRecord = ref(null)
/**
 * 这天的行读回来了，才敢发「表单独占的那八格」（八格 manual_*）。
 * 后端把"带了这个键"当成"这格就该是这个值"，留空即清空——没读回来就带键等于把已存的数洗掉。
 */
const formReady = ref(false)
const detail = ref(null)
const exportingDoc = ref(false)

const today = new Date().toLocaleDateString('en-CA')

/**
 * 这一份就是本页<b>唯一</b>会提交的字段。
 *
 * <p>刻意不含涨跌家数、我的仓位、主线/龙头那几串文字、复盘笔记和对照：那些格子已经从页面上删了，
 * 留在 form 里就等于每次「更新记录」都把一个页面从没显示过的空值发回后端。
 * 键不在这里 = 请求里没有这个键 = 后端不动那一列，md 导入存进去的值因此活得好好的。
 */
const form = reactive({
  tradeDate: today,
  maxConsecutiveLimit: null,
  limitUpCount: null,
  limitDownCount: null,
  yesterdayLimitPremium: null,
  brokenBoardRate: null,
  bigLossCount: null,
  totalVolume: null,
  // 分项人工覆盖：有值就是"这一格按我的数进分"，null 就是"用盘面公开读数"。
  // 刻意不复用那七个市场字段——那七格是整维的输入，这八格是合并维拆开后每一条的输入，两件事两列。
  manualSealedHomeRate: null,
  manualResealRate: null,
  manualPremiumLowPct: null,
  manualPremiumMidPct: null,
  manualPremiumHighPct: null,
  manualAnchorScore: null,
  manualSurvCount: null,
  manualSurvPremium: null,
  scoreTheme: null
  // 对照（compare_note）不在这份里：编辑口已经还给复盘 md，页面不发这个键，那一列就不会被空串洗掉。
})

const rules = {
  tradeDate: [{ required: true, message: '请选择日期', trigger: 'change' }],
  maxConsecutiveLimit: [{ required: true, message: '必填', trigger: 'blur' }],
  limitUpCount: [{ required: true, message: '必填', trigger: 'blur' }],
  limitDownCount: [{ required: true, message: '必填', trigger: 'blur' }]
}

const stageTagType = computed(() => {
  const map = { '冰点': 'info', '修复': '', '启动': 'success', '发酵': 'warning', '高潮': 'danger', '分歧': 'warning', '退潮': 'info' }
  return map[savedRecord.value?.stage] || 'info'
})

/** 分母是"已评维数 × 3"而不是固定的 21：未评的维整维剔出分母，写死 21 会把缺维说成低分。 */
const totalText = computed(() => {
  const r = savedRecord.value
  if (!r || r.totalScore == null) return '—'
  const dims = r.scoredDims || 0
  return `${r.totalScore} / ${dims * 3}（${dims} 维）`
})

const tempText = computed(() => {
  const t = savedRecord.value?.temperature
  return t == null ? '—' : `${Number(t).toFixed(1)}°`
})

/** 与服务端 ReviewImportParser.DISCIPLINE 同一套取值。 */
const DISCIPLINES = ['遵守', '违约', '待执行']

const posRows = ref([])
const savingPos = ref(false)

const fetching = ref(false)
const snapshot = ref(null)
const missingList = computed(() => (snapshot.value && snapshot.value.missing) || [])
const filledCount = computed(() => Object.keys((snapshot.value && snapshot.value.filled) || {}).length)

/**
 * 后端 manualFields 那份名单是"结构上取不到、只能人判"的字段，不只指本页能填的那几格：
 * 主线/总龙头/龙头状态照旧是人判的，只是作者换成了复盘 md。所以这里原样显示，不删名字。
 */
function labelList(keys) {
  const list = keys || []
  return list.length ? list.map(k => MARKET_LABELS[k] || k).join('、') : '—'
}

// ---------- 分项读数与人工覆盖 ----------

/** 公开读数（打分的默认值）。和表单分开两份，合并只发生在保存那一次。 */
const sub = ref(null)
const tiers = ref(null)
const subLoading = ref(false)
const subError = ref('')
/** 第 5 维那份名单：{@code /market/stocks} 纯本地读 t_market_stock，一次请求都不发。 */
const stocks = ref(null)

/** 每种格子的取值边界与输入步长：单位不同，不能共用一个 el-input-number 配置。 */
const SUB_KINDS = {
  rate: { min: 0, max: 100, precision: 2, step: 1, unit: '%' },
  pct: { min: -30, max: 30, precision: 2, step: 0.5, unit: '%' },
  score: { min: 0, max: 3, precision: 0, step: 1, unit: ' 分' },
  count: { min: 0, max: 999, precision: 0, step: 1, unit: ' 家' }
}

/**
 * 八条分项。key 逐字等于表单字段名（也就是 manual_* 列名），输入框直接绑 form[row.key]，
 * 中间不留第二套命名——两套名字对不上的那天，界面上就会出现"改了这一格、动的是另一格"。
 */
const SUB_ROWS = [
  { key: 'manualPremiumHighPct', dim: 2, label: '高位组均涨幅', kind: 'pct', group: 'HIGH' },
  { key: 'manualPremiumMidPct', dim: 2, label: '中位组均涨幅', kind: 'pct', group: 'MID' },
  { key: 'manualPremiumLowPct', dim: 2, label: '低位组均涨幅', kind: 'pct', group: 'LOW' },
  { key: 'manualSealedHomeRate', dim: 4, label: '家数封板率', kind: 'rate', auto: 'sealedHomeRate', note: 'sealedNote', missing: '当日未回补盘面明细：这条口径未评（不是 0%）' },
  { key: 'manualResealRate', dim: 4, label: '回封率', kind: 'rate', auto: 'resealRate', note: 'resealNote', missing: '当日未回补盘面明细：这条口径未评（不是 0%）' },
  { key: 'manualAnchorScore', dim: 8, label: '阵眼当日反馈分', kind: 'score', auto: 'anchorScore', note: 'anchorNote', missing: '未设阵眼或那天取不到行情：这一维未评（分母仍是 39，在温度上就是一个 0 分）' },
  { key: 'manualSurvCount', dim: 9, label: '进分家数', kind: 'count', auto: 'survCount', note: 'survNote', missing: '这天的公告还没拉过：这一维未评（分母仍是 39，在温度上就是一个 0 分）' },
  { key: 'manualSurvPremium', dim: 9, label: '进分溢价', kind: 'pct', auto: 'survPremium', note: 'survNote', missing: '这天的公告还没拉过：这一维未评（分母仍是 39，在温度上就是一个 0 分）' }
]

function groupOf(name) {
  const list = (tiers.value && tiers.value.groups) || []
  return list.find(g => g.group === name) || null
}

/** 一行一个读法：公开值、算式、边界，全部在这里凑齐，模板只管摆。 */
const subRows = computed(() => SUB_ROWS.map(row => {
  const kind = SUB_KINDS[row.kind]
  const out = {
    key: row.key, dim: row.dim, label: row.label, kind: 'override',
    min: kind.min, max: kind.max, precision: kind.precision, step: kind.step,
    placeholder: '覆盖'
  }
  let value = null
  let note = ''
  if (row.group) {
    const g = groupOf(row.group)
    value = g ? g.avgPct : null
    if (g) {
      note = `${g.label}：${g.stockCount} 家（取到涨跌 ${g.matched} 家）· 判 ${g.score} 分 · 权重 ${g.weight}`
    } else {
      note = tiers.value && tiers.value.available === false
        ? '当日昨日涨停池无非首板档位：第 2 维未评（不是 0%）'
        : '档位溢价还没取过：第 2 维未评（不是 0%）'
    }
  } else {
    value = sub.value ? sub.value[row.auto] : null
    note = (sub.value && sub.value[row.note]) || row.missing || ''
  }
  out.hasAuto = value !== null && value !== undefined
  out.autoText = out.hasAuto ? `${value}${kind.unit}` : '—'
  out.note = note
  return out
}))

/** 面板标题要说"八条里取回了几条"，只报"7 项"就是他抱怨的那个样子。 */
const subFilledCount = computed(() => subRows.value.filter(r => r.hasAuto).length)
const tierBinNote = computed(() => (tiers.value && tiers.value.binNote) || '')

/**
 * 按钮没了，这一屏关于"读数取没取回来"的话就只剩这一行，整句在这里拼，模板只管摆。
 *
 * <p>把后端报的 {@code elapsedMs} 带出来：「最长约 30 秒」是句形容词，
 * 而他真正要知道的是切一天日期得等多久——数比形容词有用。
 */
const subStatus = computed(() => {
  if (subLoading.value) {
    return '正在取这天的分项读数（阵眼与异动监管名单逐只打日 K，最长约 30 秒）……'
  }
  if (!sub.value && !tiers.value) {
    return '这天的分项读数还没取回来。成交额、涨跌停家数、连板高度、大面数、主线明确度这几维不依赖它，直接填就行；'
      + '分档溢价那三组、封板率与回封率、阵眼分、异动监管那两个数，要等这一次取数回来才看得见。'
  }
  const took = sub.value && sub.value.elapsedMs != null
    ? `，这次取了 ${(Number(sub.value.elapsedMs) / 1000).toFixed(1)} 秒`
    : ''
  return `分项读数已取回 ${subFilledCount.value} / ${subRows.value.length} 条${took}；`
    + '填了数的那几格以你填的进分，清空即退回读数'
})

// ---------- 九维一行行的形状 ----------

/**
 * 表单不再分「七个输入」和「八个覆盖」两块：那两块里同一个数出现两次，改一处另一处还留着旧值。
 * 这里按维拼一次，模板只管摆，不在此处以外判任何口径。
 */
function plainCell(key, label, opts) {
  const o = opts || {}
  return {
    key, label, kind: 'plain', prop: o.prop, required: !!o.required,
    placeholder: o.placeholder || '', min: o.min, max: o.max,
    precision: o.precision == null ? 0 : o.precision, step: o.step == null ? 1 : o.step,
    note: o.note || ''
  }
}

const SUB_BY_KEY = computed(() => {
  const map = {}
  subRows.value.forEach(r => { map[r.key] = r })
  return map
})

/** 名单一次全摆出来太长，扫一眼要的只是"哪几家、各回撤多深"。 */
const LIST_LIMIT = 20
const pctText = (v) => (v == null ? '—' : `${v}%`)

/**
 * 上次存下来这一维进了几分。列名和 DIMS 的 key 不是一套（第 8 维那列叫 anchorScore），
 * 所以在这里对一次，模板只管显示。
 *
 * <p>第 9 维没有分数列（它由 surv_premium 现算），走 utils/scores 那份接力五档，
 * 和仪表盘那张卡同一个算法。<b>null 就是未评</b>：新口径下分母固定，未评与 0 分对温度的
 * 影响同数，界面上再说一句"这是 0 分"就是把没判过的东西写成判过了。
 */
const DIM_SCORE_FIELD = {
  height: 'scoreHeight', premium: 'scorePremium', breadth: 'scoreBreadth',
  broken: 'scoreBroken', loss: 'scoreLoss', volume: 'scoreVolume',
  theme: 'scoreTheme', anchor: 'anchorScore'
}

function dimScoreOf(key) {
  const r = savedRecord.value
  if (!r) return null
  if (key === 'surv') {
    return r.survCount > 0 ? survivalBandOf(r.survPremium) : null
  }
  return r[DIM_SCORE_FIELD[key]] ?? null
}

const dimRows = computed(() => {
  const s = SUB_BY_KEY.value
  const t = tiers.value || {}
  const pool = stocks.value
  const losses = pool ? (pool.bigLoss || []) : []
  // 字面量按打分引擎的维序写（1..9），读代码时和后端对得上；摆出来的顺序在末尾按 CARD_ORDER 排。
  return [
    {
      key: 'height', name: '连板高度',
      cells: [plainCell('maxConsecutiveLimit', '最高连板（板）',
        { prop: 'maxConsecutiveLimit', required: true, min: 1, max: 30 })]
    },
    {
      key: 'premium', name: '分档溢价',
      cells: [
        s.manualPremiumHighPct, s.manualPremiumMidPct, s.manualPremiumLowPct,
        {
          key: 'weightedPct', label: '三组加权', kind: 'readonly', text: pctText(t.weightedPct),
          note: t.weightedPct == null ? '还没取档位溢价：这一维先按未评处理' : '进分的是这个数，不是含首板整体'
        },
        {
          key: 'yesterdayLimitPremium', label: '含首板整体', kind: 'readonly',
          text: pctText(form.yesterdayLimitPremium),
          note: '含首板，只展示不打分。它是拉行情带回来的那个数，改它不影响这一维'
        }
      ].filter(Boolean),
      note: tierBinNote.value
    },
    {
      key: 'breadth', name: '涨停 / 跌停家数',
      cells: [
        plainCell('limitUpCount', '涨停（家）', { prop: 'limitUpCount', required: true, min: 0, max: 500 }),
        plainCell('limitDownCount', '跌停（家）', { prop: 'limitDownCount', required: true, min: 0, max: 500 })
      ]
    },
    {
      key: 'broken', name: '炸板 · 封板 · 回封',
      cells: [
        plainCell('brokenBoardRate', '炸板率(次数)(%)', { min: 0, max: 100, precision: 1 }),
        s.manualSealedHomeRate, s.manualResealRate
      ].filter(Boolean),
      note: '三分支各出分再平均。炸板率数的是打开次数（一只票炸三次算三次），'
        + '100% 减它不等于家数封板率；第一格既是当日读数也是你的数——填了按填的进分，不必另开覆盖。'
        + '后两条读数随拉行情和切日期自动回显，不用点。'
    },
    {
      key: 'loss', name: '大面数',
      cells: [plainCell('bigLossCount', '大面（家）', { min: 0, max: 200 })],
      list: losses.slice(0, LIST_LIMIT),
      listHidden: Math.max(0, losses.length - LIST_LIMIT),
      listNote: pool ? '今天没有满足这条算式的票。'
        : '这天的盘面明细没回补过，名单取不到；家数这一格仍然可以自己填。',
      note: '算式：自涨停回撤 >7% 且收盘绿盘。名单按回撤深到浅排。'
    },
    {
      key: 'volume', name: '量能',
      cells: [plainCell('totalVolume', '两市成交额(亿)', { min: 0, precision: 2, step: 100 })]
    },
    {
      key: 'theme', name: '主线明确度',
      cells: [{
        key: 'scoreTheme', label: '你的判断', kind: 'select', prop: 'scoreTheme',
        options: [
          { value: 3, label: '3 · 有清晰主线 + 龙头' },
          { value: 1, label: '1 · 有热点无主线' },
          { value: 0, label: '0 · 无主线' }
        ],
        note: '这一维只由你判，盘面没有对应读数；选「未判断」即这一维不评——分母仍是 39，'
          + '在温度上就是一个 0 分。主线题材、总龙头、龙头状态、中军那四格不在本页编辑，值由复盘 md 导入写。'
      }]
    },
    {
      key: 'anchor', name: '阵眼当日反馈',
      cells: [s.manualAnchorScore].filter(Boolean),
      note: '取「主线龙头」页那块「周期阵眼 · 跨度」的当日分：在位几只取最差那一只，逐只读数在同一处。'
    },
    {
      key: 'surv', name: '异动监管',
      cells: [s.manualSurvCount, s.manualSurvPremium].filter(Boolean),
      note: '当日名单的进分家数与进分溢价，和「异动监管」页顶上那两个数是同一份数。'
        + '名单与逐日曲线在同名那一页。'
    }
  ]
    // 编号只有一份来源（DIMS[key].dim，后端依据串里写的就是这个数），所以摆出来会跳着数：6、3、1、2…
    .map((g) => ({ ...g, dim: DIMS[g.key].dim, score: dimScoreOf(g.key), weight: DIMS[g.key].weight }))
    .sort((a, b) => CARD_ORDER.indexOf(a.key) - CARD_ORDER.indexOf(b.key))
})

/** 右栏那份结果与左栏同一顺序、同一维名：从 dimRows 派生，不再写第二套清单。 */
const scoreItems = computed(() => dimRows.value.map((g) => ({
  key: g.key, label: g.name, score: g.score
})))

function clearSub() {
  sub.value = null
  tiers.value = null
  stocks.value = null
  subError.value = ''
}

/**
 * 取分项读数：第 4/8/9 维走 score-context，第 2 维走现成的 premium-tiers（只读库、不打上游），
 * 第 5 维那份名单走 /market/stocks（同样是只读库，纯本地 t_market_stock）。
 *
 * <p>失败只写这一块自己的红字。<b>绝不</b>把返回的数并进 fillForm：那条路会把他的手改当成自动值刷回去。
 * 名单那一格单独 catch：它只是第 5 维的补充，取不到不该把另外两条读数一起说成失败。
 */
async function loadSubReadings(date) {
  if (!date) return
  subLoading.value = true
  subError.value = ''
  try {
    const [ctx, tierRes, stockRes] = await Promise.all([
      marketApi.scoreContext(date),
      marketApi.premiumTiers(date),
      marketApi.stocks(date).catch(() => null)
    ])
    // 最长 60s，期间完全可能已经切了日期：把上一日的读数落在当日表单上是脏数据
    if (form.tradeDate !== date) return
    sub.value = ctx.data || null
    tiers.value = tierRes.data || null
    stocks.value = stockRes && stockRes.data && stockRes.data.available ? stockRes.data : null
  } catch (e) {
    if (form.tradeDate !== date) return
    clearSub()
    subError.value = '分项读数这次没取回来：' + (e.response?.data?.message || e.message || '未知原因')
      + '。成交额、涨跌停家数、连板高度、大面数、主线明确度这几维不受影响，手改的那几格也还在表单里，照常能存。'
  } finally {
    if (form.tradeDate === date) subLoading.value = false
  }
}

const FORM_DEFAULTS = JSON.parse(JSON.stringify(form))

function resetForm(keepDate) {
  Object.keys(FORM_DEFAULTS).forEach(key => { form[key] = FORM_DEFAULTS[key] })
  form.tradeDate = keepDate
}

async function loadRecord(date) {
  resetForm(date)
  clearLedger()
  formReady.value = false
  recordId.value = null
  savedRecord.value = null
  if (!date) return
  try {
    const res = await recordApi.getByDate(date)
    if (res.data) {
      fillForm(res.data)
      recordId.value = res.data.id
      savedRecord.value = res.data
    }
    // 200 但 data 为空 = 确认这天没有记录，表单从空起步也是权威状态
    formReady.value = true
  } catch (e) { /* 读不回来就当不知道这天存了什么，那八格这次不发（见 formReady） */ }
}

/** 台账从"这天的行还没读回来"起步：空表不等于"这天清仓了"，所以读回来之前不铺开、也不给保存。 */
function clearLedger() {
  posRows.value = []
}

/**
 * 切一次日期＝四条请求：记录、台账明细，外加三条读数（score-context 最坏 60s）。
 *
 * <p>代价写在这：他切得快时，上一日的慢响应会晚于当日的回来，靠 {@code loadSubReadings}
 * 里的跨日守卫丢结果（不取消请求，只丢返回值）。读数块不阻塞表单，读砸了也只红它自己那一行。
 */
function handleDateChange(date) {
  snapshot.value = null
  clearSub()
  loadRecord(date)
  loadDetail(date)
  loadSubReadings(date)
}

// 不用 @change：实测 el-date-picker 改了模型却不触发 change，日期换了记录却不重载
watch(() => form.tradeDate, (date) => handleDateChange(date))

async function handleFetchMarket(refresh) {
  if (!form.tradeDate) {
    ElMessage.warning('请先选择交易日期')
    return
  }
  const date = form.tradeDate
  fetching.value = true
  try {
    const res = await marketApi.snapshot(date, refresh)
    // 拉一次最长 12s，期间用户可能已经切了日期。这时把上一日的数据写进当日表单是脏数据
    if (form.tradeDate !== date) return
    snapshot.value = res.data || {}
    fillForm(snapshot.value.filled || {})
    // 不 await：这一路最坏 60s，而拉行情按钮该在七个数到手时就交还操作。
    // 读数块自己有加载态，取砸了也只红它那一块。
    // 切日期时已经取过一轮，这一次是因为 compute() 刚把那天的三池与逐档溢价写进库——
    // 在那之前第 2 维三组和第 4 维那两条一律是空的。
    loadSubReadings(date)
    ElMessage.success(`已填充 ${filledCount.value} 项，请核对后保存；分项读数随后自己刷新`)
  } catch (e) {
    // 失败提示由 axios 拦截器统一弹（后端保证 message 是中文）。
    // 这里只清面板：拉取不写库，表单里不会留下半截数据。
    snapshot.value = null
  } finally {
    fetching.value = false
  }
}

function fillForm(data) {
  Object.keys(form).forEach(key => {
    if (data[key] !== null && data[key] !== undefined) {
      form[key] = data[key]
    }
  })
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 已经有这一行、却从没读回来过：那八格现在表单里是空的，发出去就是把已存的覆盖清成未覆盖。
  // 与其悄悄少存八格，不如停下来把原因说清楚。
  if (recordId.value && !formReady.value) {
    ElMessage.error('这天的记录没读回来，本次不能保存：切到别的日期再切回来，或刷新页面')
    return
  }
  saving.value = true
  const body = payload()
  try {
    if (recordId.value) {
      const res = await recordApi.update(recordId.value, body)
      savedRecord.value = res.data
      formReady.value = true
      ElMessage.success('更新成功')
    } else {
      const res = await recordApi.create(body)
      savedRecord.value = res.data
      recordId.value = res.data.id
      formReady.value = true
      ElMessage.success('提交成功')
    }
    snapshot.value = null
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function loadDetail(date, resetPanel = true) {
  // 只有切日期那一次才清空整块：清空会把 v-if 连着正在编辑的输入框一起卸掉
  if (resetPanel) detail.value = null
  if (!date) return
  try {
    const res = await importApi.detail(date)
    // 和拉行情同一个坑：请求发出去之后用户可能已经切了日期，把上一日的行写进当日台账是最脏的一种错
    if (form.tradeDate !== date) return
    detail.value = res.data || null
    if (!detail.value) return
    applyLedgerRows(detail.value)
  } catch (e) { /* 拉不回来时持仓台账那一块整块空着（它挂在 detail 上），不拿半截数据顶上 */ }
}

/** 持仓行按服务端原样铺开。预判与对答案不在这里——那块编辑器已经还给复盘 md 了。 */
function applyLedgerRows(data) {
  posRows.value = (data.positions || []).map(p => ({
    stockCode: p.code,
    stockName: p.name,
    costPrice: numOrNull(p.costPrice),
    currentPrice: numOrNull(p.currentPrice),
    floatPct: numOrNull(p.floatPct),
    action: p.action || '',
    plannedAction: p.plannedAction || '',
    discipline: p.discipline || ''
  }))
}

function numOrNull(v) {
  return v == null || v === '' ? null : Number(v)
}

/**
 * 表单独占的那八格（八格 manual_*）：列都是 ALWAYS 策略，后端把"带了这个键"
 * 当成"这格就该是这个值"，所以只在真的读回那天的行之后才发键——没读回来就带键，
 * 等于把他存的人工覆盖一并清回未覆盖。
 *
 * <p>另一半守卫在后端：{@code DailyRecordService.copyFields} 认的就是这个键集合。
 * 从页面删掉的那六块（涨跌家数、我的仓位、主线与龙头、复盘笔记、预判与对答案、对照）
 * 因为压根不在 {@code form} 里，所以永远不会出现在这一份 body 里；
 * 对照这一列从此只有复盘 md 一个作者，页面不发键它就一动不动。
 */
const FORM_OWNED_KEYS = [
  'manualSealedHomeRate', 'manualResealRate',
  'manualPremiumLowPct', 'manualPremiumMidPct', 'manualPremiumHighPct',
  'manualAnchorScore', 'manualSurvCount', 'manualSurvPremium'
]

function payload() {
  const body = { ...form }
  if (!formReady.value) FORM_OWNED_KEYS.forEach(k => delete body[k])
  return body
}

function addPositionRow() {
  posRows.value.push({
    stockCode: '', stockName: '', costPrice: null, currentPrice: null,
    floatPct: null, action: '', plannedAction: '', discipline: ''
  })
}

async function savePositions() {
  if (!form.tradeDate) return
  savingPos.value = true
  try {
    await recordApi.savePositions(form.tradeDate, posRows.value)
    ElMessage.success('持仓台账已存')
    await loadDetail(form.tradeDate, false)
  } catch (e) { /* 校验失败的原因由拦截器弹（服务端一次把坏行说全） */ } finally {
    savingPos.value = false
  }
}

async function handleExportDoc() {
  if (!form.tradeDate) return
  const date = form.tradeDate
  exportingDoc.value = true
  try {
    const res = await recordApi.reviewDoc(date)
    const vo = res.data || {}
    if (!vo.content) {
      ElMessage.warning(date + ' 没生成出内容')
      return
    }
    saveFile('复盘_' + (vo.date || date) + '.md', vo.content)
    const warnings = vo.warnings || []
    if (warnings.length) {
      ElMessage.info(warnings[0] + (warnings.length > 1 ? `（另有 ${warnings.length - 1} 条提示）` : ''))
    } else {
      ElMessage.success('已导出 复盘_' + (vo.date || date) + '.md')
    }
  } catch (e) {
    // 拦截器已经把后端那句原因弹出来了，这里再弹一次就是两条 toast
  } finally {
    exportingDoc.value = false
  }
}

function saveFile(name, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

/** 首屏走的是切日期那一条路：两条路各列一遍的话，"进页面少取一样"迟早会回来。 */
onMounted(() => {
  handleDateChange(form.tradeDate)
})
</script>

<style scoped>
.review-page {
  max-width: 1200px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}
.page-header h2 {
  margin: 0;
  color: #e1e8ed;
}
.header-spacer {
  flex: 1;
}
.review-grid {
  display: grid;
  /* 中文的 min-content 是一个字宽，必须用 minmax(0,..) 否则列会被压成竖排单字 */
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 24px;
}
/* 窄屏时右侧 360px 定宽会把表单列压成 0 宽，内容溢出到建议面板上 */
@media (max-width: 1100px) {
  .review-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
.form-section {
  background: #1a2332;
  border-radius: 12px;
  padding: 24px;
}
.form-section :deep(.el-divider__text) {
  color: #8899a6;
  background: #1a2332;
}
.form-section :deep(.el-form-item__label) {
  color: #8899a6;
}

.fetch-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.fetch-panel {
  margin-top: 14px;
}
.panel-title {
  font-weight: 600;
  margin-right: 8px;
}
.panel-body {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
}
.panel-body p {
  margin: 0;
}
.panel-key {
  font-weight: 600;
  margin-right: 6px;
}
.panel-note {
  color: #606266;
}
.panel-warn {
  color: #d97706;
}

.score-section {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.score-card, .fetch-card {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
}
.score-card h3, .fetch-card h3 {
  margin: 0 0 16px;
  color: #e1e8ed;
  font-size: 16px;
}
.score-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.score-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.score-item .label {
  color: #8899a6;
  font-size: 13px;
}
.dots {
  display: flex;
  gap: 4px;
}
.dots span {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #2d3748;
}
.dots span.active {
  background: #3b82f6;
}
/* 未评和 0 分是两回事，所以要有一个自己的样子，而不是三颗空心的点 */
.unscored {
  font-size: 11px;
  line-height: 12px;
  color: #8899a6;
  border: 1px solid #2d3748;
  border-radius: 4px;
  padding: 0 5px;
}
/* 负分：三颗空心和 0 分撞脸，只能单独给个标记 */
.minus {
  font-size: 11px;
  line-height: 12px;
  font-weight: 700;
  color: #60a5fa;
  border: 1px solid #1e40af;
  border-radius: 4px;
  padding: 0 5px;
}
.insufficient {
  color: #8899a6;
  font-size: 13px;
}
.total-row {
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #2d3748;
  color: #e1e8ed;
  font-weight: 600;
}
.temp {
  color: #f59e0b;
}
.stage-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.direction {
  color: #8899a6;
  font-size: 14px;
}
.detail-hint {
  color: #8899a6;
  font-size: 12px;
  margin: 0 0 12px;
}

/* 台账区整行宽：八列输入框塞不进右侧那 360px 的列 */
.ledger-section {
  margin-top: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.ledger-card {
  background: #1a2332;
  border-radius: 12px;
  padding: 18px 24px 20px;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.card-head h3 {
  margin: 0;
  font-size: 15px;
  color: #e1e8ed;
}
.ledger-card .detail-hint {
  margin: 10px 0 0;
}
.field-note {
  font-size: 12px;
  color: #8899a6;
  margin: -8px 0 12px;
  line-height: 1.5;
}
.panel-sub {
  font-size: 11px;
  color: #6b7c8c;
  margin-left: 6px;
}

/* 九维打分：算式常驻可见，不藏进 hover——这几套刻度里有两套是这边按实测自造的，要能当场否 */
.sub-status {
  font-size: 12px;
  color: #8899a6;
  line-height: 1.6;
  margin: 0 0 10px;
}
/* 九维打分：算式常驻可见，不藏进 hover——这几套刻度里有两套是这边按实测自造的，要能当场否 */
.dim-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}
.dim-group {
  border: 1px solid #2d3748;
  border-radius: 10px;
  padding: 8px 12px 9px;
}
.dim-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 5px;
}
.dim-no {
  width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 5px;
  background: #22303f;
  color: #8899a6;
  font-size: 11px;
}
.dim-name {
  color: #e1e8ed;
  font-size: 13px;
  font-weight: 600;
}
/* 这一维进了几分就写在标题右边，和仪表盘 hover 那行分数串同一个色 */
.dim-score {
  margin-left: auto;
  font-size: 12px;
  font-weight: 700;
  color: #f59e0b;
}
.dim-score.unscored {
  color: #6b7c8c;
  font-weight: 500;
}
.dim-cell {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 3px 6px;
  margin: 0 -6px;
  border-radius: 6px;
}
.dim-cell.covered {
  background: #16202e;
}
.dim-cell.covered .cell-auto {
  color: #6b7c8c;
  text-decoration: line-through;
}
.cell-label {
  color: #cbd5e0;
  font-size: 13px;
  font-weight: 600;
  min-width: 128px;
}
.cell-req {
  color: #dc5b5b;
  margin-left: 3px;
}
.cell-auto {
  color: #e1e8ed;
  font-size: 13px;
  min-width: 74px;
}
.cell-item {
  margin-bottom: 0;
  width: 122px;
  flex: none;
}
.cell-item.cell-item-wide {
  width: 190px;
}
.cell-item :deep(.el-input-number),
.cell-item :deep(.el-select) {
  width: 100%;
}
.dim-cell-tall {
  padding-bottom: 18px;
}
.cell-plain {
  color: #6b7c8c;
  font-size: 12px;
}
.cell-note {
  flex-basis: 100%;
  margin: 0;
  color: #8899a6;
  font-size: 12px;
  line-height: 1.5;
}
.cell-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 2px 0 0;
}
.list-item {
  background: #16202e;
  border: 1px solid #2d3748;
  border-radius: 6px;
  padding: 1px 6px;
  font-size: 12px;
  color: #cbd5e0;
  cursor: default;
}
.list-item i {
  font-style: normal;
  color: #dc5b5b;
  margin-left: 4px;
}
.list-more {
  color: #6b7c8c;
  font-size: 12px;
  align-self: center;
}
.dim-note {
  margin: 4px 0 0;
  color: #8899a6;
  font-size: 12px;
  line-height: 1.5;
}

/* el-table 默认浅色，这里压成深色以匹配整页 */
.import-detail :deep(.el-table),
.ledger-section :deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: #16202e;
  --el-table-border-color: #2d3748;
  --el-table-text-color: #cbd5e0;
  --el-table-header-text-color: #8899a6;
  --el-table-row-hover-bg-color: #22303f;
}
.import-detail :deep(.el-table::before),
.ledger-section :deep(.el-table::before) {
  background-color: #2d3748;
}
</style>
