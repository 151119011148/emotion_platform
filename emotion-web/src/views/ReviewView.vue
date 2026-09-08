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

          <el-form-item label="行情数据">
            <div class="fetch-row">
              <el-button type="success" plain :loading="fetching" @click="handleFetchMarket(false)">
                拉取行情
              </el-button>
              <el-button v-if="snapshot" :loading="fetching" text @click="handleFetchMarket(true)">
                跳过缓存重拉
              </el-button>
            </div>
          </el-form-item>

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
                留空＝该维不评，整维从分母里剔除（不是 0 分，也不会虚高）。缺得越多温度越不稳，少于 5 维不出阶段。
              </p>
              <p v-for="(item, i) in snapshot.notes" :key="'note' + i" class="panel-note">{{ item }}</p>
              <p v-for="(item, i) in snapshot.warnings" :key="'warn' + i" class="panel-warn">{{ item }}</p>
            </div>
          </el-alert>

          <el-divider content-position="left">九维打分</el-divider>

          <div class="sub-head">
            <el-button size="small" plain :loading="subLoading" :disabled="!form.tradeDate"
              @click="loadSubReadings(form.tradeDate)">取分项读数</el-button>
            <span class="panel-sub">
              {{ subLoading ? '阵眼与监管名单逐只打日 K，最长约 30 秒'
                : (sub || tiers ? `8 条分项读数已取回 ${subFilledCount} 条；填了数的那几格以你填的进分，清空即退回读数`
                  : '拉一次行情会自动带上；也可以单独重取') }}
            </span>
          </div>
          <p v-if="subLoading && !sub && !tiers" class="field-note">正在取这天的公开分项读数……</p>
          <p v-else-if="!sub && !tiers" class="field-note">
            还没取这天的公开分项读数。第 1/3/5/6/7 维直接填就行，不依赖这一次取数；
            取回来才能看到第 2 维三组、第 4 维另两条、第 8/9 维各是多少。
          </p>
          <p v-if="subError" class="panel-warn">{{ subError }}</p>
          <p v-if="!formReady" class="panel-warn">
            这天的记录还没读回来，那 8 格人工覆盖本次<b>不会</b>提交（否则等于把你已存的覆盖洗掉）。
          </p>

          <div class="dim-grid">
            <div v-for="group in dimRows" :key="'dim' + group.dim" class="dim-group">
              <div class="dim-title">
                <span class="dim-no">{{ group.dim }}</span>
                <span class="dim-name">{{ group.name }}</span>
              </div>

              <div v-for="cell in group.cells" :key="cell.key" class="dim-cell"
                :class="{ covered: cell.kind === 'override' && form[cell.key] != null, 'dim-cell-tall': !!cell.prop }">
                <span class="cell-label">{{ cell.label }}<span v-if="cell.required" class="cell-req">*</span></span>

                <span v-if="cell.kind === 'readonly'" class="cell-auto">{{ cell.text }}</span>

                <el-form-item v-else-if="cell.kind === 'select'" :prop="cell.prop" label-width="0"
                  class="cell-item cell-item-wide">
                  <el-select v-model="form[cell.key]" size="small" placeholder="未判断（不计入分母）">
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

          <el-divider content-position="left">广度与仓位（不参与打分）</el-divider>

          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="上涨家数">
                <el-input-number v-model="form.upCount" :min="0" :max="6000" controls-position="right"
                  style="width: 100%" placeholder="手记" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="下跌家数">
                <el-input-number v-model="form.downCount" :min="0" :max="6000" controls-position="right"
                  style="width: 100%" placeholder="手记" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="我的仓位(%)">
                <el-input-number v-model="form.myPositionPct" :min="0" :max="100" :precision="1" :step="5"
                  controls-position="right" style="width: 100%" placeholder="只有你知道" />
              </el-form-item>
            </el-col>
          </el-row>
          <p class="field-note">
            涨跌家数没有可回溯的上游（东财延迟域 f104/f105 回 "-"），仓位也不是行情——这两格留给你手填。
            都不进分母：03 篇把涨跌家数比列在「辅助指标」，第 3 维用的是涨停 vs 跌停家数。
          </p>

          <el-divider content-position="left">主线与龙头</el-divider>

          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="主线题材" prop="mainTheme">
                <el-input v-model="form.mainTheme" placeholder="如：AI、新能源" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="总龙头" prop="leadingStock">
                <el-input v-model="form.leadingStock" placeholder="龙头名称" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="龙头状态" prop="leadingStockStatus">
                <el-select v-model="form.leadingStockStatus" style="width: 100%">
                  <el-option label="加速" value="加速" />
                  <el-option label="滞涨" value="滞涨" />
                  <el-option label="断板" value="断板" />
                  <el-option label="反包" value="反包" />
                  <el-option label="正常" value="正常" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="中军" prop="midCapStock">
                <el-input v-model="form.midCapStock" placeholder="中军名称（选填）" />
              </el-form-item>
            </el-col>
          </el-row>
          <p class="field-note">
            主线明确度（第 7 维）在上面「九维打分」里判，和这三格同属主线这一件事，拆成两处填就会填歪。
          </p>

          <el-divider content-position="left">复盘笔记</el-divider>

          <el-form-item label="轮动观察">
            <el-input v-model="form.rotationNote" type="textarea" :rows="2"
              placeholder="资金从哪流出、往哪流入？有没有新题材冒头？" />
          </el-form-item>

          <el-form-item label="对答案（昨天计划兑现了吗？）">
            <el-input v-model="form.reviewNote" type="textarea" :rows="2"
              placeholder="昨天的判断对了吗？错在定位、选股、还是执行？" />
          </el-form-item>

          <el-form-item label="明日计划">
            <el-input v-model="form.tomorrowPlan" type="textarea" :rows="2"
              placeholder="明天在什么条件下做什么、买什么、多大仓、错在哪止损" />
          </el-form-item>

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
              <span v-else-if="item.score < 0" class="minus">-1</span>
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

        <div class="advice-card" v-if="advice">
          <h3>操作建议</h3>
          <div class="advice-item">
            <span class="advice-label">基调</span>
            <span>{{ advice.tone }}</span>
          </div>
          <div class="advice-item">
            <span class="advice-label">仓位</span>
            <span>{{ advice.position }}</span>
          </div>
          <div class="advice-item">
            <span class="advice-label">动作</span>
            <span>{{ advice.action }}</span>
          </div>
          <div class="advice-item warning">
            <span class="advice-label">警告</span>
            <span>{{ advice.warning }}</span>
          </div>
        </div>

        <div class="mini-trend" v-if="recentRecords.length > 0">
          <h3>近7日温度趋势</h3>
          <div class="trend-bars">
            <div v-for="r in recentRecords" :key="r.id" class="trend-bar-wrapper">
              <div class="trend-bar" :style="{
                height: barHeight(r.temperature) + '%',
                background: getBarColor(r.temperature)
              }"></div>
              <span class="trend-date">{{ formatDate(r.tradeDate) }}</span>
              <span class="trend-val">{{ r.temperature == null ? '未出' : Number(r.temperature).toFixed(0) }}</span>
            </div>
          </div>
        </div>

        <div class="import-detail" v-if="detail">
          <h3>指数与对照</h3>
          <p class="detail-hint">
            指数收盘由「拉取行情」自动补齐，涨跌家数、我的仓位、持仓与预判、各节判断文字、这一格的对照都能在本页改，
            都跟着上面的「更新记录」一起存。只有当日板块强度还只能从那天导入的原文里带出来。
          </p>

          <div class="detail-section" v-if="detail.indexes && detail.indexes.length">
            <h4>指数收盘</h4>
            <el-table :data="detail.indexes" size="small">
              <el-table-column prop="code" label="代码" width="90" />
              <el-table-column prop="name" label="名称" width="100" />
              <el-table-column label="收盘" width="100" align="right">
                <template #default="{ row }">{{ row.closePrice != null ? Number(row.closePrice).toFixed(2) : '—' }}</template>
              </el-table-column>
              <el-table-column label="涨跌" width="90" align="right">
                <template #default="{ row }">
                  <span v-if="row.changePct != null" :class="Number(row.changePct) >= 0 ? 'up' : 'down'">
                    {{ Number(row.changePct) >= 0 ? '+' : '' }}{{ Number(row.changePct).toFixed(2) }}%
                  </span>
                  <span v-else>—</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="detail-section">
            <h4>对照</h4>
            <el-input v-model="form.compareNote" type="textarea" :rows="2"
              placeholder="你的手记数与系统取到的数不一致时写在这里，如「手记 46涨停/17跌停，系统取到 44/16」" />
            <p v-if="!formReady" class="panel-warn">
              这天的记录还没读回来，这一格本次<b>不会</b>提交（否则等于把他存的对照洗掉）。
            </p>
            <p v-else-if="detail.compareNote" class="field-note">
              下面那行是那天原文里的对照，这一格还没填过值。写一次存下来就以这一格为准。
            </p>
            <p v-if="detail.compareNote && detail.compareNote !== form.compareNote" class="compare-note">
              原文：{{ detail.compareNote }}
            </p>
          </div>
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

      <div class="ledger-card">
        <div class="card-head">
          <h3>预判与对答案</h3>
          <span class="header-spacer"></span>
          <el-button size="small" @click="addPredictionRow">加一行</el-button>
          <el-button size="small" type="primary" plain :loading="savingPred" @click="savePredictions">保存</el-button>
        </div>
        <el-table :data="predRows" size="small" empty-text="这天还没有预判，点「加一行」录三条路径">
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-select v-model="row.kind" size="small">
                <el-option label="预判 PLAN" value="PLAN" />
                <el-option label="对答案 ANSWER" value="ANSWER" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="路径名" width="150">
            <template #default="{ row }"><el-input v-model="row.name" size="small" placeholder="如：主线续攻" /></template>
          </el-table-column>
          <el-table-column label="概率%" width="100">
            <template #default="{ row }">
              <el-input-number v-model="row.prob" size="small" :min="0" :max="100" :controls="false"
                :disabled="row.kind !== 'PLAN'" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="触发条件">
            <template #default="{ row }">
              <el-input v-model="row.conditionText" size="small" :disabled="row.kind !== 'PLAN'"
                placeholder="PLAN：什么情况下走这条" />
            </template>
          </el-table-column>
          <el-table-column label="结果" width="110">
            <template #default="{ row }">
              <el-select v-model="row.result" size="small" :disabled="row.kind !== 'ANSWER'" placeholder="ANSWER">
                <el-option v-for="r in RESULTS" :key="r" :label="r" :value="r" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="依据" width="180">
            <template #default="{ row }">
              <el-input v-model="row.resultNote" size="small" :disabled="row.kind !== 'ANSWER'"
                placeholder="ANSWER：一句话依据" />
            </template>
          </el-table-column>
          <el-table-column label="删" width="52" align="center">
            <template #default="{ $index }">
              <el-button size="small" text type="danger" @click="predRows.splice($index, 1)">×</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="detail-hint">
          对答案答的是<b>前一日</b>那些路径，靠路径名回填，所以名字每天要复用。
          保存同样整表替换，且 PLAN 与 ANSWER 一起换——只发一种会把另一种清掉。
        </p>
      </div>

      <div class="ledger-card">
        <div class="card-head">
          <h3>各节判断文字</h3>
          <span class="head-hint">导出复盘文档时按节填进对应小节，平台原样存、不解析</span>
        </div>
        <div class="note-grid">
          <div class="note-cell" v-for="s in NOTE_SECTIONS" :key="s.key">
            <label class="note-label">{{ s.label }}</label>
            <el-input v-model="notes[s.key]" type="textarea" :autosize="{ minRows: 2, maxRows: 10 }"
              :placeholder="s.placeholder" />
          </div>
        </div>
        <p class="detail-hint">
          {{ notesReady ? '正文原样进下一份导出，不参与打分，也不进可导入模板的 meta 契约。' : '这页的判断文字还没读回来，本次不会提交它们（避免把已存的正文洗掉）——跟着上面的「更新记录」一起存。' }}
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { recordApi, marketApi, importApi } from '../api/modules'
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
 * 这天的行读回来了，才敢发「表单独有那三格」（上涨/下跌家数、我的仓位）。
 * 后端把"带了这个键"当成"这格就该是这个值"，留空即清空——没读回来就带键等于把已存的数洗掉。
 */
const formReady = ref(false)
const advice = ref(null)
const recentRecords = ref([])
const detail = ref(null)
const exportingDoc = ref(false)

const today = new Date().toLocaleDateString('en-CA')

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
  myPositionPct: null,
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
  mainTheme: '',
  leadingStock: '',
  leadingStockStatus: '正常',
  midCapStock: '',
  scoreTheme: null,
  rotationNote: '',
  reviewNote: '',
  tomorrowPlan: '',
  // 手记读数对照。列没填过时下面那块会另显示原文里那份，两者不一致不奇怪。
  compareNote: ''
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

const SCORE_DIMS = [
  ['scoreHeight', '连板高度'], ['scorePremium', '溢价'], ['scoreBreadth', '涨跌停比'],
  ['scoreBroken', '炸板率'], ['scoreLoss', '大面数'], ['scoreVolume', '量能'],
  ['scoreTheme', '主线明确度']
]

const scoreItems = computed(() => SCORE_DIMS.map(([key, label]) => ({
  key, label, score: savedRecord.value?.[key]
})))

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

/** 条高不能按负百分比算（浏览器会忽略这条声明，条子直接变成容器全高）。 */
function barHeight(temp) {
  if (temp == null) return 0
  return Math.max(2, Math.abs(Number(temp)))
}

/** 档位边界必须和 TemperatureCalculator.determineStage 一致，否则色块会和阶段标签互相打脸。 */
function getBarColor(temp) {
  if (temp == null) return '#5b6b7c'
  if (temp < 0) return '#1e40af'
  if (temp <= 15) return '#1e3a5f'
  if (temp <= 35) return '#2d5a87'
  if (temp < 55) return '#2d8a4e'
  if (temp < 80) return '#d97706'
  return '#dc2626'
}

function formatDate(dateStr) {
  if (!dateStr) return ''
  return dateStr.slice(5)
}

/** 8 个小节键必须和后端 ReviewDocFormatter.NOTE_KEYS 一字不差，对不上就变成"没归节的文字"单列一节。 */
const NOTE_SECTIONS = [
  { key: 'index', label: '【一】指数与量能', placeholder: '三大指数怎么走的、量能说明什么、对盘面的定性' },
  { key: 'theme', label: '【二】板块主线（按强度排序）', placeholder: '主线强度排序 + 每条一句翻译；元宝/豆包给的答案可以直接粘' },
  { key: 'emotion', label: '【三】情绪与连板生态', placeholder: '周期定位的理由、位置与来路怎么读' },
  { key: 'position', label: '【四】持仓处理评价', placeholder: '这几只今天该怎么做、哪里应做未做、教训是什么' },
  { key: 'answer', label: '【五】对答案', placeholder: '昨日路径兑现情况、错在定位/选股/执行哪一环' },
  { key: 'plan', label: '【六】明日预期 · 三路径', placeholder: '每条路径的概率、触发条件、对应动作；AI 给的推演可以直接粘' },
  { key: 'anchor', label: '【七】关键锚点', placeholder: '为什么是这几只当阵眼、它们的跨度在说什么' },
  { key: 'strategy', label: '【八】仓位与总策略', placeholder: '总仓位定在多少、为什么、什么信号加、什么信号撤' }
]
/** 与服务端 ReviewImportParser.DISCIPLINE / ANSWER_RESULT 同一套取值。 */
const DISCIPLINES = ['遵守', '违约', '待执行']
const RESULTS = ['命中', '落空', '部分', '违约']

const notes = reactive({})
/** 判断文字读回来了才随表单一起提交；没读回来就提交等于把他已存的正文清空。 */
const notesReady = ref(false)
const posRows = ref([])
const predRows = ref([])
const savingPos = ref(false)
const savingPred = ref(false)

const fetching = ref(false)
const snapshot = ref(null)
const missingList = computed(() => (snapshot.value && snapshot.value.missing) || [])
const filledCount = computed(() => Object.keys((snapshot.value && snapshot.value.filled) || {}).length)

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
  { key: 'manualAnchorScore', dim: 8, label: '阵眼当日反馈分', kind: 'score', auto: 'anchorScore', note: 'anchorNote', missing: '未设阵眼或那天取不到行情：这一维不计入分母' },
  { key: 'manualSurvCount', dim: 9, label: '监管股进分家数', kind: 'count', auto: 'survCount', note: 'survNote', missing: '这天的公告还没拉过：这一维不计入分母' },
  { key: 'manualSurvPremium', dim: 9, label: '监管股今日溢价', kind: 'pct', auto: 'survPremium', note: 'survNote', missing: '这天的公告还没拉过：这一维不计入分母' }
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

const dimRows = computed(() => {
  const s = SUB_BY_KEY.value
  const t = tiers.value || {}
  const pool = stocks.value
  const losses = pool ? (pool.bigLoss || []) : []
  return [
    {
      dim: 1, name: '连板高度',
      cells: [plainCell('maxConsecutiveLimit', '最高连板（板）',
        { prop: 'maxConsecutiveLimit', required: true, min: 1, max: 30 })]
    },
    {
      dim: 2, name: '分档溢价',
      cells: [
        s.manualPremiumHighPct, s.manualPremiumMidPct, s.manualPremiumLowPct,
        {
          key: 'weightedPct', label: '三组加权', kind: 'readonly', text: pctText(t.weightedPct),
          note: t.weightedPct == null ? '还没取档位溢价：这一维先按未评处理' : '进分的是这个数，不是含首板整体'
        },
        {
          key: 'yesterdayLimitPremium', label: '含首板整体', kind: 'readonly',
          text: pctText(form.yesterdayLimitPremium),
          note: '含首板，只展示不打分。它是拉行情带回来的那个数，改它不影响第 2 维'
        }
      ].filter(Boolean),
      note: tierBinNote.value
    },
    {
      dim: 3, name: '涨停 / 跌停家数',
      cells: [
        plainCell('limitUpCount', '涨停（家）', { prop: 'limitUpCount', required: true, min: 0, max: 500 }),
        plainCell('limitDownCount', '跌停（家）', { prop: 'limitDownCount', required: true, min: 0, max: 500 })
      ]
    },
    {
      dim: 4, name: '炸板 · 封板 · 回封',
      cells: [
        plainCell('brokenBoardRate', '炸板率(次数)(%)', { min: 0, max: 100, precision: 1 }),
        s.manualSealedHomeRate, s.manualResealRate
      ].filter(Boolean),
      note: '三分支各出分再平均。炸板率数的是打开次数（一只票炸三次算三次），'
        + '100% 减它不等于家数封板率；第一格既是当日读数也是你的数——填了按填的进分，不必另开覆盖。'
    },
    {
      dim: 5, name: '大面数',
      cells: [plainCell('bigLossCount', '大面（家）', { min: 0, max: 200 })],
      list: losses.slice(0, LIST_LIMIT),
      listHidden: Math.max(0, losses.length - LIST_LIMIT),
      listNote: pool ? '今天没有满足这条算式的票。'
        : '这天的盘面明细没回补过，名单取不到；家数这一格仍然可以自己填。',
      note: '算式：自涨停回撤 >7% 且收盘绿盘。名单按回撤深到浅排。'
    },
    {
      dim: 6, name: '量能',
      cells: [plainCell('totalVolume', '两市成交额(亿)', { min: 0, precision: 2, step: 100 })]
    },
    {
      dim: 7, name: '主线明确度',
      cells: [{
        key: 'scoreTheme', label: '你的判断', kind: 'select', prop: 'scoreTheme',
        options: [
          { value: 3, label: '3 · 有清晰主线 + 龙头' },
          { value: 1, label: '1 · 有热点无主线' },
          { value: 0, label: '0 · 无主线' }
        ],
        note: '这一维只由你判，盘面没有对应读数；选「未判断」即整维剔出分母。'
      }]
    },
    {
      dim: 8, name: '阵眼当日反馈',
      cells: [s.manualAnchorScore].filter(Boolean),
      note: '在位几只取最差那一只的分。逐只读数在「主线龙头」页的阵眼名单上。'
    },
    {
      dim: 9, name: '监管股',
      cells: [s.manualSurvCount, s.manualSurvPremium].filter(Boolean),
      note: '真监管名单的当日进分家数与平均涨跌。名单与逐日曲线在「异动监管」页。'
    }
  ]
})

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
      + '。第 1/3/5/6/7 维不受影响，手改的那几格也还在表单里，照常能存。'
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
  } catch (e) { /* 读不回来就当不知道这天存了什么，那三格这次不发（见 formReady） */ }
}

/** 三块编辑器都按"这天的数据还没读回来"起步，读回来之前不提交判断文字。 */
function clearLedger() {
  NOTE_SECTIONS.forEach(s => { notes[s.key] = '' })
  Object.keys(notes).filter(k => !NOTE_SECTIONS.some(s => s.key === k))
    .forEach(k => { delete notes[k] })
  posRows.value = []
  predRows.value = []
  notesReady.value = false
}

function handleDateChange(date) {
  snapshot.value = null
  clearSub()
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
    const res = await marketApi.snapshot(date, refresh)
    // 拉一次最长 12s，期间用户可能已经切了日期。这时把上一日的数据写进当日表单是脏数据
    if (form.tradeDate !== date) return
    snapshot.value = res.data || {}
    fillForm(snapshot.value.filled || {})
    // 不 await：这一路最坏 60s，而拉行情按钮该在七个数到手时就交还操作。
    // 分项块自己有加载态，取砸了也只红它那一块。
    loadSubReadings(date)
    ElMessage.success(`已填充 ${filledCount.value} 项，请核对后保存；分项读数在九维那块里单独取`)
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
    loadAdvice()
    loadRecent()
    snapshot.value = null
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function loadAdvice() {
  try {
    const res = await recordApi.getAdvice()
    advice.value = res.data
  } catch (e) { /* ignore */ }
}

async function loadRecent() {
  try {
    const res = await recordApi.getLatest(7)
    recentRecords.value = Array.isArray(res.data) ? res.data : (res.data?.records || [])
  } catch (e) { /* ignore */ }
}

async function loadDetail(date, withNotes = true) {
  // 只有切日期那一次才清空整块：清空会把 v-if 连着正在编辑的输入框一起卸掉
  if (withNotes) detail.value = null
  if (!date) return
  try {
    const res = await importApi.detail(date)
    // 和拉行情同一个坑：请求发出去之后用户可能已经切了日期，把上一日的行写进当日台账是最脏的一种错
    if (form.tradeDate !== date) return
    detail.value = res.data || null
    if (!detail.value) return
    if (withNotes) {
      NOTE_SECTIONS.forEach(s => { notes[s.key] = detail.value.docNotes?.[s.key] || '' })
      Object.entries(detail.value.docNotes || {}).forEach(([k, v]) => {
        if (!(k in notes)) notes[k] = v
      })
      notesReady.value = true
    }
    applyLedgerRows(detail.value)
  } catch (e) { /* 拉不回来时三块编辑器一起空着，卡片底部那句提示会说明为什么这次不带判断文字 */ }
}

/** 持仓与预判两批行按服务端原样铺开（PLAN 在前、ANSWER 在后，与表里那个类型下拉一一对应）。 */
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
  predRows.value = [
    ...(data.plans || []).map(p => ({
      kind: 'PLAN', name: p.name, prob: numOrNull(p.prob),
      conditionText: p.condition || '', result: '', resultNote: ''
    })),
    ...(data.answers || []).map(a => ({
      kind: 'ANSWER', name: a.name, prob: null,
      conditionText: '', result: a.result || '', resultNote: a.note || ''
    }))
  ]
}

function numOrNull(v) {
  return v == null || v === '' ? null : Number(v)
}

/**
 * 表单的提交体。共同规矩：后端把"带了这个键"当成"这格就该是这个值"，所以只在
 * 那天的数据真的读回来之后才发键——没读回来就带键，等于把他存的判断文字和那几格数清空。
 * docNotes 看 notesReady，表单独有的那十二格看 formReady。
 *
 * <p>八格 manual_* 必须和涨跌家数/仓位/对照那四格同进退：服务端那一律是无守卫的 set
 * （留空＝清回未覆盖，否则他清不掉一格），守卫只做在这道键过滤上。两边只改一边，症状就是
 * "没读回这天的行、随手存一下，人工覆盖全没了"。
 */
const FORM_OWNED_KEYS = [
  'upCount', 'downCount', 'myPositionPct', 'compareNote',
  'manualSealedHomeRate', 'manualResealRate',
  'manualPremiumLowPct', 'manualPremiumMidPct', 'manualPremiumHighPct',
  'manualAnchorScore', 'manualSurvCount', 'manualSurvPremium'
]

function payload() {
  const body = { ...form }
  if (!formReady.value) FORM_OWNED_KEYS.forEach(k => delete body[k])
  if (notesReady.value) body.docNotes = { ...notes }
  return body
}

function addPositionRow() {
  posRows.value.push({
    stockCode: '', stockName: '', costPrice: null, currentPrice: null,
    floatPct: null, action: '', plannedAction: '', discipline: ''
  })
}

function addPredictionRow() {
  predRows.value.push({
    kind: 'PLAN', name: '', prob: null, conditionText: '', result: '', resultNote: ''
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

async function savePredictions() {
  if (!form.tradeDate) return
  savingPred.value = true
  try {
    await recordApi.savePredictions(form.tradeDate, predRows.value)
    ElMessage.success('预判与对答案已存')
    await loadDetail(form.tradeDate, false)
  } catch (e) { /* 同上 */ } finally {
    savingPred.value = false
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

onMounted(() => {
  loadRecord(form.tradeDate)
  loadAdvice()
  loadRecent()
  loadDetail(form.tradeDate)
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
  margin-bottom: 20px;
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
.score-card, .advice-card, .mini-trend {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
}
.score-card h3, .advice-card h3, .mini-trend h3 {
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
.advice-item {
  margin-bottom: 12px;
  color: #e1e8ed;
  font-size: 14px;
}
.advice-label {
  color: #8899a6;
  margin-right: 8px;
  font-weight: 600;
}
.advice-item.warning {
  color: #f59e0b;
}
.trend-bars {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 120px;
}
.trend-bar-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
  justify-content: flex-end;
}
.trend-bar {
  width: 100%;
  border-radius: 4px 4px 0 0;
  min-height: 4px;
}
.trend-date {
  font-size: 11px;
  color: #8899a6;
  margin-top: 4px;
}
.trend-val {
  font-size: 11px;
  color: #e1e8ed;
}
.import-detail {
  border-top: 1px solid #2d3748;
  padding-top: 16px;
  margin-top: 16px;
}
.import-detail h3 {
  margin: 0 0 8px;
  font-size: 15px;
  color: #e1e8ed;
}
.detail-hint {
  color: #8899a6;
  font-size: 12px;
  margin: 0 0 12px;
}
.detail-stats {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
}
.detail-stat {
  background: #0f1419;
  border-radius: 8px;
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.detail-stat .stat-label {
  font-size: 12px;
  color: #8899a6;
}
.detail-stat .stat-value {
  font-size: 16px;
  font-weight: 600;
  color: #e1e8ed;
}
.detail-stat .stat-sub {
  font-size: 11px;
  color: #6b7c8c;
}
.detail-section {
  margin-top: 14px;
}
.detail-section h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: #cbd5e0;
  font-weight: 600;
}
.up { color: #ef4444; }
.down { color: #3b82f6; }
.compare-note {
  margin: 12px 0 0;
  color: #93c5fd;
  font-size: 13px;
  font-style: italic;
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
.head-hint {
  font-size: 12px;
  color: #8899a6;
}
.note-grid {
  display: grid;
  /* minmax(0,..)：中文的 min-content 是一个字宽，写 1fr 会让格子压成竖排单字 */
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px 16px;
}
.note-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.note-label {
  font-size: 12px;
  color: #cbd5e0;
  font-weight: 600;
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
.sub-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
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
