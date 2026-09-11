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

          <el-divider content-position="left">五维原始行情读数（与各维度页面同源）</el-divider>
          <p v-if="!formReady" class="panel-warn">
            这天的记录没读回来：下面的人工读数（manual_* + 涨跌家数）<b>不会</b>发出
            （发了就等于把你已存的值连同你没显示的格子一起洗成空）。已有这一行时点「更新记录」会被直接拦下。
            切到别的日期再切回来、或刷新页面才能存。
          </p>

          <!-- D1 大盘生态：五大指数 + 成交额/量比/涨跌家数/涨跌停（与大盘页同一套原始读数） -->
          <div v-if="ebbActive" class="ebb-chip">
            <span class="ebb-chip-ico">⛔</span>
            <span class="ebb-chip-text">
              <b>强制退潮</b>：无视总分按「退潮(强制)」应对 — {{ ebbReason || '已命中强制退潮条件' }}
            </span>
          </div>
          <DimRawBlock v-if="form.tradeDate" :date="form.tradeDate" dim-key="market" title="D1 · 大盘生态"
            :up-count="form.upCount" :down-count="form.downCount" />

          <!-- 日内核心：聚集度/催化剂/持续性原始读数 -->
          <DimRawBlock v-if="form.tradeDate" :date="form.tradeDate" dim-key="theme_main" title="日内核心" />

          <!-- D3 连板生态：最高连板/日内核心/涨停炸板/涨停聚集（与天梯页同一套原始读数） -->
          <DimRawBlock v-if="form.tradeDate" :date="form.tradeDate" dim-key="board" title="D3 · 连板生态" />

          <!-- D4 首板生态：首板封住/炸板/封板率/1进2/昨首板溢价（与首板页同一套原始读数） -->
          <DimRawBlock v-if="form.tradeDate" :date="form.tradeDate" dim-key="first" title="D4 · 首板生态" />
          <section v-if="D4_MANUALS.length" class="block manual-block">
            <h4 class="manual-title">D4 人工读数（封板率 / 溢价人工兜底）</h4>
            <div class="stat-grid">
              <EditableStatCard v-for="m in D4_MANUALS" :key="m.metric"
                v-model="form[m.field]" :label="m.label" :unit="m.unit"
                :min="m.min" :max="m.max" :precision="m.precision" :step="m.step"
                :ph="m.ph" :hint="m.hint" />
            </div>
          </section>

          <!-- D5 阵眼：总龙/中军/跟风/卡位/反包原始读数（与主线页同一套龙头分工） -->
          <DimRawBlock v-if="form.tradeDate" :date="form.tradeDate" dim-key="anchor" title="D5 · 阵眼" />

          <el-divider content-position="left">强制退潮条件</el-divider>
          <section class="block">
            <div class="stat-grid">
              <div v-for="m in FORCED_EBB_METRICS" :key="m.metric" class="stat-card">
                <span class="stat-label">{{ m.label }}<span v-if="m.unit" class="stat-unit"> ({{ m.unit }})</span></span>
                <el-select v-if="m.kind === 'select'" v-model="form[m.field]" size="small" clearable
                  :placeholder="m.ph" class="stat-input">
                  <el-option v-for="o in m.options" :key="o.value" :label="o.label" :value="o.value" />
                </el-select>
                <el-input-number v-else v-model="form[m.field]" :min="m.min" :max="m.max"
                  :precision="m.precision" :step="m.step" controls-position="right" size="small"
                  :placeholder="m.ph" class="stat-input" />
                <span v-if="m.hint" class="stat-hint">{{ m.hint }}</span>
              </div>
            </div>
          </section>

          <el-form-item class="action-row">
            <el-button type="primary" :loading="saving" @click="handleSave" size="large">
              {{ recordId ? '更新记录' : '提交记录' }}
            </el-button>
          </el-form-item>
        </el-form>
      </div>

      <div class="score-section">
        <div class="score-card" v-if="savedRecord">
          <h3>打分结果</h3>
          <p class="fd-norecord">
            各维度的具体打分规则和子指标分，请前往各单维页面查看（大盘生态 / 连板生态 / 首板生态 / 主线龙头）。
          </p>
          <div class="total-row">
            <span>总分：{{ savedRecord.totalScore ?? '—' }} / 100</span>
            <span class="temp">温度：{{ savedRecord.temperature ?? '—' }}</span>
          </div>
          <div class="stage-row">
            <el-tag v-if="savedRecord.forcedEbb === 1" type="danger" size="small" effect="dark">强制退潮</el-tag>
            <el-tag v-if="savedRecord.stage" :type="stageTagType" size="large">{{ savedRecord.stage }}</el-tag>
            <span v-if="savedRecord.stageDirection" class="direction">{{ savedRecord.stageDirection }}</span>
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
            这个按钮只取公开市场的那七个数，取回来直接落进上面「行情读数」那一块。
            五维里只能人判的那几条不用点按钮：D2 四条（板块涨停数 / 梯队完整性 / 板块溢价 / 持续性）、
            D4 的首板封板率与首板溢价、D5 的监管折扣、强制退潮的极高位换手与断板，在各自的维段里当场填。
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
                留空＝该子指标未评：未评的那一条会从分母里剔掉，这一维按剩下几条的权重归一化出分，
                <b>不是按 0 计</b>。所以缺数不会把分压低，只会让这一维的分更"薄"——
                每维标题右边那个「未评（剔出分母）」和维段里每行的「未评」，是唯一能把它和真 0 分分清的地方。
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
import { ElMessage } from 'element-plus'
import EditableStatCard from '../components/EditableStatCard.vue'
import DimRawBlock from '../components/DimRawBlock.vue'
import { useScoringStore } from '../stores/scoring'

const scoring = useScoringStore()

/** 后端 missing / manualFields 用的是实体字段名，面板要显示中文。 */
const MARKET_LABELS = {
  maxConsecutiveLimit: '连板高度',
  limitUpCount: '涨停家数',
  limitDownCount: '跌停家数',
  yesterdayLimitPremium: '昨日涨停溢价',
  brokenBoardRate: '炸板率',
  bigLossCount: '大面数',
  totalVolume: '两市成交额',
  upCount: '上涨家数',
  downCount: '下跌家数',
  mainTheme: '主线题材',
  leadingStock: '总龙头',
  leadingStockStatus: '龙头状态',
  manualSectorLimitUpCount: '主线板块涨停数',
  manualLadderCompleteScore: '板块梯队完整性',
  manualSectorPremiumPct: '主线板块溢价',
  manualThemePersistenceDays: '主线持续天数',
  manualFirstPremiumPct: '首板次日均溢价'
}

const formRef = ref(null)
const saving = ref(false)
const recordId = ref(null)
const savedRecord = ref(null)
// D1 强制退潮红片：优先用引擎现算的 score-detail，页面刚载入还没拉明细时回落到已存记录的 forcedEbb。
// 两者都是引擎真值（跌停≥10/阵眼核按钮/中位吹哨+大面/极高位爆量断板），前端不自行判定。
const ebbActive = computed(() =>
  scoring.detail?.forcedEbb === true || savedRecord.value?.forcedEbb === 1)
const ebbReason = computed(() =>
  scoring.detail?.forcedEbbReason || savedRecord.value?.forcedEbbReason || '')
/**
 * 这天的行读回来了，才敢发「表单独占的那 11 格」（九格五维 manual_* + 涨跌家数）。
 * 后端把"带了这个键"当成"这格就该是这个值"，留空即清空——没读回来就带键等于把已存的数洗掉。
 */
const formReady = ref(false)
const detail = ref(null)
const exportingDoc = ref(false)

const today = new Date().toLocaleDateString('en-CA')

/**
 * 这一份就是本页<b>唯一</b>会提交的字段。
 *
 * <p>刻意不含我的仓位、主线/龙头那几串文字、复盘笔记和对照：那些格子不在页面上，
 * 留在 form 里就等于每次「更新记录」都把一个页面从没显示过的空值发回后端。
 * 键不在这里 = 请求里没有这个键 = 后端不动那一列，md 导入存进去的值因此活得好好的。
 *
 * <p>涨跌家数是个例外：列由复盘 md 独家写了几周，现在它喂的是 D1·广度，页面得能填，
 * 所以它进了 {@link FORM_OWNED_KEYS}——发键的守卫和那九格人工读数同一条路。
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
  upCount: null,
  downCount: null,
  // 五维里盘面没有可回补取数口径的读数：填了按填的进分，清空即退回未评（不是 0 分）。
  manualSectorLimitUpCount: null,
  manualLadderCompleteScore: null,
  manualSectorPremiumPct: null,
  manualThemePersistenceDays: null,
  manualFirstPremiumPct: null,
  manualFirstSealedRate: null,
  manualAnchorSupervisionDiscount: null,
  manualTopHighTurnoverPct: null,
  manualTopHighBreak: null
  // 对照（compare_note）不在这份里：编辑口已经还给复盘 md，页面不发这个键，那一列就不会被空串洗掉。
})

const rules = {
  tradeDate: [{ required: true, message: '请选择日期', trigger: 'change' }],
  maxConsecutiveLimit: [{ required: true, message: '必填', trigger: 'blur' }],
  limitUpCount: [{ required: true, message: '必填', trigger: 'blur' }],
  limitDownCount: [{ required: true, message: '必填', trigger: 'blur' }]
}

const stageTagType = computed(() => {
  const map = { '退潮': 'info', '混沌': '', '发酵': 'warning', '高潮': 'danger', '退潮(强制)': 'danger' }
  return map[savedRecord.value?.stage] || 'info'
})

/** 与服务端 ReviewImportParser.DISCIPLINE 同一套取值。 */
const DISCIPLINES = ['遵守', '违约', '待执行']

const posRows = ref([])
const savingPos = ref(false)

const fetching = ref(false)
const snapshot = ref(null)
const missingList = computed(() => (snapshot.value && snapshot.value.missing) || [])
// 面板上的 "/7" 只数七项打分口径行情；涨跌家数是客观附加项（同交易时段才有），
// 它的回显看 D1 卡片，不挤进这 7 项的完成度。
const MARKET_AUTO_KEYS = ['maxConsecutiveLimit', 'limitUpCount', 'limitDownCount',
  'yesterdayLimitPremium', 'brokenBoardRate', 'bigLossCount', 'totalVolume']
const filledCount = computed(() => {
  const f = (snapshot.value && snapshot.value.filled) || {}
  return MARKET_AUTO_KEYS.filter(k => f[k] != null).length
})

function labelList(keys) {
  const list = keys || []
  return list.length ? list.map(k => MARKET_LABELS[k] || k).join('、') : '—'
}

/** D2/D4/D5 的人工读数已由 MANUAL_METRICS 静态表定义，见下方。 */

/**
 * 九条人工读数。<b>键是 metric key（也就是子指标的 source_key）</b>，不是维键也不是子键。
 *
 * <p>刻意做成静态表：输入框只由它渲染，生效模型 subs 与 score-detail 那两份只读数据谁挂了
 * 都不影响「能不能填」。上一版把 D2 那四格挂在只读端点上，端点一慢整维就永远填不进去。
 * dimKey=null 的两条不属于任何子指标（是强制退潮条件 4 的闸门），摆在③那一块。
 */
const MANUAL_METRICS = [
  {
    metric: 'sector_limit_up_count', dimKey: 'theme_main', field: 'manualSectorLimitUpCount',
    label: '板块涨停数', unit: '家', min: 0, max: 200, precision: 0, step: 1, ph: '家',
    hint: '第一主线今日涨停家数（按 industry 数不出题材，要自己看）'
  },
  {
    metric: 'ladder_complete_score', dimKey: 'theme_main', field: 'manualLadderCompleteScore',
    label: '梯队完整性', unit: '分', min: 0, max: 100, precision: 0, step: 5, ph: '0-100',
    hint: '直接给分：完整梯队≈90，单高标无跟风≈35'
  },
  {
    metric: 'sector_premium_pct', dimKey: 'theme_main', field: 'manualSectorPremiumPct',
    label: '板块溢价', unit: '%', min: -30, max: 50, precision: 2, step: 0.5, ph: '%',
    hint: '主线板块昨日涨停今日的平均溢价'
  },
  {
    metric: 'persistence_days', dimKey: 'theme_main', field: 'manualThemePersistenceDays',
    label: '持续性', unit: '天', min: 0, max: 30, precision: 0, step: 1, ph: '天',
    hint: '主线连续活跃天数：≥3 天算持续，首日算新启动'
  },
  {
    metric: 'first_sealed_rate', dimKey: 'first', field: 'manualFirstSealedRate',
    label: '首板封板率', unit: '%', min: 0, max: 100, precision: 2, step: 1, ph: '%',
    hint: '首板封住 /（封住 + 炸）；炸板池分不出首板，只能人判'
  },
  {
    metric: 'first_premium_pct', dimKey: 'first', field: 'manualFirstPremiumPct',
    label: '首板溢价', unit: '%', min: -30, max: 50, precision: 2, step: 0.5, ph: '%',
    hint: '首板次日均溢价；自动取数没并进 board=1 时按这一格兜'
  },
  {
    metric: 'anchor_supervision_discount', dimKey: 'anchor', field: 'manualAnchorSupervisionDiscount',
    label: '监管折扣', unit: '×', min: 0, max: 1, precision: 2, step: 0.05, ph: '0-1',
    hint: '阵眼被监管时给的乘数（如 0.80）；留空 = 不打折。它是乘数不是百分数'
  },
  {
    metric: 'top_high_turnover_pct', dimKey: null, field: 'manualTopHighTurnoverPct',
    label: '极高位换手', unit: '%', min: 0, max: 100, precision: 1, step: 1, ph: '%',
    hint: 'H≥7 才用得上：极高位龙头当日换手 >35% 且断板未回封 = 强制退潮'
  },
  {
    metric: 'top_high_break', dimKey: null, field: 'manualTopHighBreak', label: '极高位断板',
    kind: 'select', ph: '未判',
    options: [{ value: 1, label: '是 · 爆量断板未回封' }, { value: 0, label: '否 · 封住或已回封' }],
    hint: '这一条要人看盘，盘面没有对应读数；留空 = 强制退潮条件 4 不参与'
  }
]
const MANUAL_BY_METRIC = MANUAL_METRICS.reduce((m, x) => { m[x.metric] = x; return m }, {})
const FORCED_EBB_METRICS = MANUAL_METRICS.filter((m) => m.dimKey === null)

/**
 * MANUAL_METRICS 已按 dimKey 归属：
 *   D2 theme_main —— 4 格主线人判（板块涨停数/梯队/溢价/持续性）
 *   D4 first        —— 2 格首板人判（封板率/溢价）
 *   D5 anchor       —— 1 格监管折扣
 *   null 强制退潮   —— 2 格（极高位换手/断板）
 */
const D2_MANUALS = MANUAL_METRICS.filter(m => m.dimKey === 'theme_main')
const D4_MANUALS = MANUAL_METRICS.filter(m => m.dimKey === 'first')
const D5_MANUALS = MANUAL_METRICS.filter(m => m.dimKey === 'anchor')


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
  } catch (e) { /* 读不回来就当不知道这天存了什么，那十一格这次不发（见 formReady） */ }
}

/** 台账从"这天的行还没读回来"起步：空表不等于"这天清仓了"，所以读回来之前不铺开、也不给保存。 */
function clearLedger() {
  posRows.value = []
}

/**
 * 切一次日期＝四条请求：记录、台账明细、大面名单，外加五维现算 eval 树（最坏 60s）。
 *
 * <p>代价写在这：他切得快时，上一日的慢响应会晚于当日的回来，靠各自的跨日守卫丢结果
 * （不取消请求，只丢返回值）。读数不阻塞表单，读砸了也只空它自己那一块。
 */
function handleDateChange(date) {
  snapshot.value = null
  loadRecord(date)
  loadDetail(date)
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
    // 一次 snapshot 由后端统一取数并返回 filled：七项行情 + 同交易时段的上涨/下跌家数。
    // 后端还会把客观的涨跌家数窄更新进这天已存在的复盘记录（拉取即持久化，只写 up/down 两列）。
    // 历史日后端取不到当天涨跌家数（只有实时口径），filled 里就没这两键，卡片如实留空。
    const snapRes = await marketApi.snapshot(date, refresh)
    if (form.tradeDate !== date) return
    snapshot.value = snapRes.data || {}
    const filled = { ...(snapshot.value.filled || {}) }
    fillForm(filled)
    // snapshot 已把今昨涨停/炸板池写进 t_market_stock：四层晋级/溢价/大面、封板率、回封率、连板数
    // 这些分层原始读数此刻就能按最新池子重算，强制刷一次五维明细（不等保存）。
    scoring.loadDetail(date, true)
    ElMessage.success(`已填充 ${filledCount.value} 项，请核对后保存`)
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
  // 已经有这一行、却从没读回来过：那十一格现在表单里是空的，发出去就是把已存的读数清成未填。
  // 与其悄悄少存这十一格，不如停下来把原因说清楚。
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
    // 记录已落库：涨跌停/最高板/量比/红盘率等读 t_daily_record 的原始读数随之定型，强制刷新五维明细。
    scoring.loadDetail(form.tradeDate, true)
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
 * 表单独占的那 11 格（九格五维 {@code manual_*} + 涨跌家数）：列全是 ALWAYS 策略，
 * 后端把"带了这个键"当成"这格就该是这个值"，所以只在真的读回那天的行之后才发键——
 * 没读回来就带键，等于把他存的人工覆盖一并清回未覆盖。
 *
 * <p>涨跌家数以前不在这一份里：那两列只有复盘 md 写。现在它们喂 D1·广度，页面必须能填，
 * 于是与九格走同一条守卫。
 *
 * <p>另一半守卫在后端：{@code DailyRecordService.copyFields} 认的就是这个键集合。
 * 旧九维那八格覆盖（{@code manual_sealed_home_rate} 等）已从页面下线——不发键，已存的历史值一动不动。
 */
const FORM_OWNED_KEYS = [
  'upCount', 'downCount',
  'manualSectorLimitUpCount', 'manualLadderCompleteScore', 'manualSectorPremiumPct',
  'manualThemePersistenceDays', 'manualFirstPremiumPct', 'manualFirstSealedRate',
  'manualAnchorSupervisionDiscount', 'manualTopHighTurnoverPct', 'manualTopHighBreak'
]

function payload() {
  const body = { ...form }
  // el-input-number / el-select 清空给的是 undefined，JSON.stringify 会直接把键丢掉：
  // 那样"清空这一格"就静默变成了"这格不动"。 ALWAYS 列要的是显式 null。
  Object.keys(body).forEach(k => { if (body[k] === undefined) body[k] = null })
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

.ebb-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0 12px;
  padding: 9px 12px;
  border-radius: 8px;
  background: rgba(239, 68, 68, .16);
  border: 1px solid #ef4444;
}
.ebb-chip-ico { flex: none; animation: ebb-chip-blink 1.1s steps(2, start) infinite; }
.ebb-chip-text { color: #fecaca; font-size: 12.5px; line-height: 1.5; }
.ebb-chip-text b { color: #fca5a5; }
@keyframes ebb-chip-blink { to { opacity: .25; } }

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
.five-dim-strip {
  margin-bottom: 14px;
  padding: 12px;
  background: rgba(8, 145, 178, 0.08);
  border: 1px solid rgba(8, 145, 178, 0.28);
  border-radius: 8px;
}
.fd-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.fd-title {
  font-size: 12px;
  color: #67e8f9;
  font-weight: 600;
}
.fd-items {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
}
.fd-item {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-size: 13px;
}
.fd-label {
  color: #8899a6;
}
.fd-score {
  color: #e1e8ed;
  font-weight: 600;
}
.fd-score.unscored {
  color: #64748b;
  font-weight: 400;
  font-size: 12px;
}
.fd-weight {
  color: #64748b;
  font-size: 11px;
}
.fd-source {
  font-size: 11px;
  color: #64748b;
}
.fd-norecord {
  margin: 10px 0 0 0;
  font-size: 12px;
  color: #8899a6;
  line-height: 1.5;
}
.fd-norecord b {
  color: #67e8f9;
}
.fd-band {
  font-size: 11px;
}
.fd-band.b-none { color: #64748b; }
.fd-band.b-ebb { color: #94a3b8; }
.fd-band.b-chaos { color: #60a5fa; }
.fd-band.b-ferment { color: #fbbf24; }
.fd-band.b-climax { color: #f87171; }
.legacy-note {
  margin: 14px 0 6px;
  font-size: 11px;
  color: #64748b;
}
.score-grid.legacy {
  opacity: 0.55;
}
.fd-forced-reason {
  margin: 8px 0 0 0;
  font-size: 12px;
  color: #fca5a5;
  line-height: 1.5;
}
.fd-signals {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.fd-signal-label {
  font-size: 12px;
  color: #fbbf24;
}
.fd-chip {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  background: rgba(251, 191, 36, 0.14);
  border: 1px solid rgba(251, 191, 36, 0.36);
  color: #fcd34d;
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

/* =========== ReviewView 表单区块（复刻 MarketView 卡片风格） =========== */
.block {
  background: #1a2332;
  border-radius: 12px;
  padding: 16px 18px 18px;
  margin-bottom: 16px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr));
  gap: 10px;
}
.stat-card {
  background: #1a2332;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 86px;
  transition: background .15s;
}
.stat-card:hover { background: #243040; }
.stat-label {
  font-size: 12px;
  color: #8899a6;
}
.stat-unit {
  color: #6b7c8c;
  font-size: 11px;
}
.stat-input {
  width: 100%;
}
.stat-input :deep(.el-input-number) {
  width: 100%;
}
.stat-input :deep(.el-input__wrapper) {
  background: #0f1419;
  box-shadow: none;
  border-radius: 6px;
}
.stat-hint {
  font-size: 11px;
  color: #6b7c8c;
  line-height: 1.4;
}

/* === 人工读数小块：挂在对应维度 DimScoreBlock 下方，与该维打成一组 === */
.manual-block {
  margin-top: -8px;
}
.manual-title {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: #c8d3dd;
}

/* 五维录入台（旧样式已废，保留以防历史引用） */
.sub-status {
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
/* 这一维进了几分就写在标题右边，色与仪表盘卡面、右栏横条同一套 4 带 */
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
.dim-score.b-ebb { color: #94a3b8; }
.dim-score.b-chaos { color: #60a5fa; }
.dim-score.b-ferment { color: #fbbf24; }
.dim-score.b-climax { color: #f87171; }
/* 段头下面那根细条：分数一眼看得见长短，配色跟着带走 */
.fd-bar {
  height: 4px;
  border-radius: 2px;
  background: #223041;
  overflow: hidden;
  margin-bottom: 7px;
}
.fd-bar-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.3s ease;
}
.fd-bar-fill.b-none { background: #2d3748; }
.fd-bar-fill.b-ebb { background: #64748b; }
.fd-bar-fill.b-chaos { background: #3b82f6; }
.fd-bar-fill.b-ferment { background: #f59e0b; }
.fd-bar-fill.b-climax { background: #ef4444; }
.fd-sub-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 3px 6px;
  margin: 0 -6px;
  border-radius: 6px;
}
/* 这一格你已经填了数：底色标出来，和"树里没这条、只按你填的进分"是两件事 */
.fd-sub-row.covered {
  background: #16202e;
}
.cell-label {
  color: #cbd5e0;
  font-size: 13px;
  font-weight: 600;
  min-width: 128px;
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
.cell-item :deep(.el-input-number),
.cell-item :deep(.el-select) {
  width: 100%;
}
/* 输入格与只读文本同宽，避免填一次行高跳一次 */
.fd-input {
  width: 132px;
}
.cell-unit {
  color: #8899a6;
  font-size: 12px;
}
.fd-sub-score {
  margin-left: auto;
  color: #f59e0b;
  font-size: 13px;
  font-weight: 700;
  min-width: 34px;
  text-align: right;
}
.fd-sub-score.unscored {
  color: #6b7c8c;
  font-size: 12px;
  font-weight: 400;
}
/* 命中的那条阶梯档：原样印后端 bandHit 的档名，改阈值刷新后就换 */
.fd-hit {
  color: #a8b7c4;
  font-size: 11px;
  border: 1px solid #2d3748;
  border-radius: 4px;
  padding: 1px 5px;
}
/* 复合子（D3 的晋级/溢价/大面…）四层并排一行，段才不会撑到三屏 */
.fd-layer-row {
  flex-basis: 100%;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 0;
  padding-left: 8px;
}
.fd-layer {
  color: #a8b7c4;
  font-size: 12px;
}
.fd-layer b {
  color: #cbd5e0;
  font-weight: 600;
  margin-left: 2px;
}
.fd-layer b.unscored {
  color: #6b7c8c;
  font-weight: 400;
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
/* ③ 结构信号与强制退潮那两块：形状跟维段一致，读起来是一家人 */
.signal-block {
  border: 1px solid #2d3748;
  border-radius: 10px;
  padding: 8px 12px 9px;
  margin-bottom: 12px;
}
.signal-block .fd-sub-row {
  margin-top: 6px;
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
