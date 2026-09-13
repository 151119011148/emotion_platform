<template>
  <div class="review-page">
    <!-- ============ 顶栏：日期 + 一键拉取 + 导出 + 就绪状态 ============ -->
    <div class="page-header">
      <h2>每日复盘</h2>
      <el-date-picker v-model="form.tradeDate" type="date" value-format="YYYY-MM-DD"
        :disabled-date="disableDate" placeholder="选择交易日" style="width: 160px" />
      <span class="header-spacer"></span>
      <el-tag v-if="fetchOverall" :type="overallTag" effect="dark">{{ overallText }}</el-tag>
      <el-button type="primary" :loading="fetching" @click="handleFetch">🔄 一键拉取行情</el-button>
      <el-button :loading="exportingDoc" :disabled="!form.tradeDate" @click="handleExportDoc">
        导出复盘文档
      </el-button>
    </div>

    <!-- ============ 拉取进度（T1-T8 流式回传） ============ -->
    <div v-if="fetchTasks.length" class="fetch-progress">
      <div class="fp-head">
        <span class="fp-title">拉取进度（T1 回补 → T8 五维重算）</span>
        <span v-if="fetching" class="fp-live">● 编排中</span>
      </div>
      <div class="fp-grid">
        <div v-for="t in orderedTasks" :key="t.task" class="fp-chip" :class="'fp-' + (t.status || 'pending')">
          <span class="fp-task">{{ t.task }}</span>
          <span class="fp-icon">{{ chipIcon(t.status) }}</span>
          <span class="fp-rows" v-if="t.rows != null">{{ t.rows }}</span>
          <span class="fp-msg" :title="t.msg">{{ chipMsg(t) }}</span>
        </div>
      </div>
    </div>

    <!-- ============ D1 大盘生态 ============ -->
    <section class="dim-block" :class="readinessClass('D1')">
      <div class="block-head">
        <h3>D1 · 大盘生态 <span class="slice">(T 日)</span></h3>
        <span class="header-spacer"></span>
        <ReadinessBadge :note="readiness.D1" />
        <ScoreChip :score="scoreOf('D1')" />
      </div>
      <div class="index-grid">
        <div v-for="ix in d1.indexes" :key="ix.indexCode" class="index-card">
          <span class="ix-name">{{ ix.indexName || ix.indexCode }}</span>
          <span class="ix-close">{{ fmtNum(ix.closePrice) }}</span>
          <span class="ix-pct" :class="pctClass(ix.changePct)">{{ signed(ix.changePct) + '%' }}</span>
        </div>
        <div v-if="!d1.indexes.length" class="empty-note">五大指数未拉取（去点「一键拉取行情」）</div>
      </div>
      <div v-if="d1.daily" class="stat-line">
        <Stat :k="'两市成交额'" :v="fmtYi(d1.daily.totalVolume) + '亿'" />
        <Stat :k="'上涨 / 下跌'" :v="nz(d1.daily.upCount) + ' / ' + nz(d1.daily.downCount)" />
        <Stat :k="'涨停 / 跌停'" :v="nz(d1.daily.limitUpCount) + ' / ' + nz(d1.daily.limitDownCount)" />
        <Stat :k="'连板高度'" :v="nz(d1.daily.maxConsecutiveLimit, true)" />
        <Stat :k="'炸板率'" :v="fmtPctVal(d1.daily.brokenBoardRate)" />
        <Stat :k="'大面' " :v="nz(d1.daily.bigLossCount, true)" />
        <Stat :k="'昨涨停溢价'" :v="fmtPct(d1.daily.yesterdayLimitPremium)" />
      </div>
      <div v-else class="empty-note">全市场统计未拉取</div>
    </section>

    <!-- ============ D2 日内核心（题材 Top5 + 核心板块 Top5） ============ -->
    <section class="dim-block" :class="readinessClass('D2')">
      <div class="block-head">
        <h3>D2 · 日内核心 <span class="slice">(T 日)</span></h3>
        <span class="header-spacer"></span>
        <ReadinessBadge :note="readiness.D2" />
        <ScoreChip :score="scoreOf('D2')" />
      </div>
      <!-- D2 双列表：题材热度 + 核心板块 同时展示（概念 / 行业两个维度） -->
      <div class="d2-grid">
        <div class="d2-col">
          <h4 class="d2-sub">题材热度 <span class="muted">概念维度 · Top5</span></h4>
          <div v-if="themesView.length" class="topic-list">
            <div v-for="(t, i) in themesView" :key="t.name" class="topic-row">
              <span class="topic-rank" :class="'rk' + (i + 1)">{{ i + 1 }}</span>
              <span class="topic-name">{{ t.name }}</span>
              <span class="topic-sub">涨停 {{ t.ztCount }} · 最高 {{ t.maxBoard }} 板</span>
            </div>
          </div>
          <div v-else class="empty-note">今日题材表为空（读取时后端会按热门行业自动回填题材）</div>
        </div>

        <div class="d2-col">
          <h4 class="d2-sub">核心板块 <span class="muted">行业维度 · Top5</span></h4>
          <div v-if="d2.industries.length" class="topic-list">
            <div v-for="(b, i) in topIndustries" :key="b.industry" class="topic-row">
              <span class="topic-rank" :class="'rk' + (i + 1)">{{ i + 1 }}</span>
              <span class="topic-name">{{ b.industry }}</span>
              <span class="topic-sub">涨停 {{ b.ztCount }} · 最高 {{ b.maxBoard }} 板 · 封单 {{ fmtYi(b.sealSum) }}亿</span>
            </div>
          </div>
          <div v-else class="empty-note">行业板块快照为空（当日无涨停池，或未拉取三池）</div>
          <p v-if="d2.industries.length > 5" class="dim-more">共 {{ d2.industries.length }} 个板块在涨停池，此处展示涨停数前 5</p>
        </div>
      </div>
    </section>

    <!-- ============ D3 连板生态 ============ -->
    <section class="dim-block" :class="readinessClass('D3')">
      <div class="block-head">
        <h3>D3 · 连板生态 <span class="slice">(T-1 → T)</span></h3>
        <span class="header-spacer"></span>
        <ReadinessBadge :note="readiness.D3" />
        <ScoreChip :score="scoreOf('D3')" />
      </div>
      <template v-if="tianti">
        <div class="stat-line" v-if="tianti.maxBoard != null || tianti.ztTotal != null">
          <Stat :k="'空间板'" :v="nz(tianti.maxBoard, true) + ' 板'" />
          <Stat :k="'连板家数'" :v="nz(tianti.lbTotal, true) + ' 家'" />
          <Stat :k="'涨停 / 炸板'" :v="nz(tianti.ztTotal) + ' / ' + nz(tianti.zbTotal)" />
          <Stat :k="'日内核心行业'" :v="tianti.mainIndustry || '—'" />
        </div>
        <div v-for="lv in tianti.levels || []" :key="lv.board" class="tier-block" :class="{ gap: !(lv.rows || []).length }">
          <div class="tier-head">
            <span class="tier-board">{{ lv.board }} 板</span>
            <span v-if="lv.layerLabel" class="tier-layer">{{ lv.layerLabel }}</span>
            <span class="tier-count" v-if="lv.count != null">{{ lv.count }} 家</span>
          </div>
          <div v-if="(lv.rows || []).length" class="tier-rows">
            <div v-for="r in lv.rows" :key="r.code" class="tier-stock">
              <b v-if="r.manualLeader" class="star" title="人工总龙头">★</b>
              <span class="ts-name">{{ r.name }}</span>
              <span v-if="r.pattern" class="ts-pattern" :class="patternClass(r.pattern)">{{ patternText(r.pattern) }}</span>
              <i v-if="r.role" class="ts-role">{{ r.roleLabel || r.role }}</i>
              <span v-if="r.breakCount != null && r.breakCount > 0" class="ts-break" title="日内开板次数">{{ r.breakCount }}开</span>
            </div>
          </div>
          <div v-else class="tier-empty">断档（本层无在板股）</div>
        </div>
        <div v-if="!(tianti.levels || []).length" class="empty-note">当日无 ≥2 板连板（或未拉取三池）</div>
      </template>
      <div v-else class="empty-note">连板天梯未取到（需当日涨停池）</div>
    </section>

    <!-- ============ D4 首板生态 ============ -->
    <section class="dim-block" :class="readinessClass('D4')">
      <div class="block-head">
        <h3>D4 · 首板生态 <span class="slice">(T 日)</span></h3>
        <span class="header-spacer"></span>
        <ReadinessBadge :note="readiness.D4" />
        <ScoreChip :score="scoreOf('D4')" />
      </div>
      <template v-if="shouban">
        <div class="stat-line" v-if="shouban.summary">
          <Stat :k="'首板封住'" :v="nz(shouban.summary.sealedCount, true)" />
          <Stat :k="'首板炸板'" :v="nz(shouban.summary.bombedCount, true)" />
          <Stat :k="'封板率'" :v="fmtPctVal(shouban.summary.sealedRate)" />
          <Stat :k="'一字板'" :v="nz(shouban.summary.yiziCount, true)" />
          <Stat :k="'均封单(亿)'" :v="fmtYi(shouban.summary.avgSealAmount)" />
        </div>
        <div class="sb-list">
          <div class="list-head" @click="sealedOpen = !sealedOpen">
            <h4>首板封住名单 <span class="fold-tag ok-tag">{{ sealedOpen ? '收起 ▲' : '展开 ▼' }}</span></h4>
            <span class="list-count ok-text">{{ (shouban.sealed || []).length }} 只</span>
          </div>
          <div v-show="sealedOpen" class="list-body">
            <div v-if="!(shouban.sealed || []).length" class="list-empty">当日没有封住的首板（或行情明细未回补，点一键拉取）</div>
            <div v-else class="chips">
              <div v-for="r in shouban.sealed" :key="r.code" class="chip">
                <span class="chip-name">{{ r.name }}</span>
                <span class="chip-code">{{ r.code }}</span>
                <span class="chip-industry" :class="{ 'in-main': r.inMain }">{{ r.industry || '—' }}</span>
                <el-tag v-if="r.pattern" size="small" :type="SB_PATTERN_TYPE[r.pattern] || 'info'" effect="plain">
                  {{ SB_PATTERN_LABEL[r.pattern] || '—' }}
                </el-tag>
                <span v-if="r.sealAmount != null" class="chip-seal">封单 {{ moneyText(r.sealAmount) }}</span>
                <span v-if="r.breakCount != null && r.breakCount > 0" class="chip-reseal">开板{{ r.breakCount }}次↩回封</span>
                <span :class="pctClass(r.changePct)" class="chip-pct">{{ signed(r.changePct) + '%' }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>
      <div v-else class="empty-note">首板封住名单未取到（需当日三池）</div>
    </section>

    <!-- ============ D5 高位生态 ============ -->
    <section class="dim-block" :class="readinessClass('D5')">
      <div class="block-head">
        <h3>D5 · 高位生态 <span class="slice">(T-1 → T)</span></h3>
        <span class="header-spacer"></span>
        <ReadinessBadge :note="readiness.D5" />
        <ScoreChip :score="scoreOf('D5')" />
      </div>
      <template v-if="d5high">
        <div class="d5-subtitle">阵眼
          <span v-if="d5high.anchor && d5high.anchor.configured">（在位 {{ (d5high.anchor.items || []).length }} 个）</span>
          <span v-else>（未配置 → 当子项剔出分母）</span>
        </div>
        <div v-if="(d5high.anchor && d5high.anchor.items || []).length" class="anchor-grid">
          <div v-for="it in d5high.anchor.items" :key="it.id" class="anchor-card">
            <div class="anchor-line1">
              <b>{{ it.name }}</b>
              <span v-if="it.roleLabel" class="role-tag">{{ it.roleLabel }}</span>
            </div>
            <div class="anchor-line2">
              <span v-if="it.consecutive != null">{{ it.consecutive }} 板</span>
              <span :class="pctClass(it.chg)">{{ signed(it.chg) + '%' }}</span>
              <span v-if="it.actionLabel" class="ac-act">{{ it.actionLabel }}</span>
            </div>
            <div v-if="it.industry" class="anchor-line3">{{ it.industry }}</div>
          </div>
        </div>
        <div v-else class="empty-note">当日无在位阵眼（t_anchor 未配置，或不在生效区间内）</div>

        <div class="d5-subtitle">监管池（{{ (d5high.monitorPool || []).length }} 家）</div>
        <el-table v-if="(d5high.monitorPool || []).length" :data="d5high.monitorPool" size="small" class="dim-table">
          <el-table-column label="名称" prop="name" min-width="92" />
          <el-table-column label="行业" prop="industry" min-width="92" />
          <el-table-column label="连板" prop="consecutive" width="60" align="center" />
          <el-table-column label="类型" width="88">
            <template #default="{ row }">{{ row.kind }}</template>
          </el-table-column>
          <el-table-column label="公告日" width="92">
            <template #default="{ row }">{{ (row.annDate || '').slice(5) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><span class="mon-status" :class="statusClass(row.status)">{{ row.status }}</span></template>
          </el-table-column>
          <el-table-column label="涨幅" width="80" align="right">
            <template #default="{ row }"><span :class="pctClass(row.chg)">{{ signed(row.chg) + '%' }}</span></template>
          </el-table-column>
        </el-table>
        <div v-else class="empty-note">当日监管池为空（例行 ZD 不进此表）</div>

        <div class="d5-action" v-if="!fetching">
          <el-button size="small" plain @click="openSurv = true">人工补录监管（T7 无自动源）</el-button>
        </div>
      </template>
      <div v-else class="empty-note">高位生态未取到（/d5/high）</div>
    </section>

    <!-- 总览评分卡 -->
    <section class="dim-block score-overview">
      <div class="block-head">
        <h3>五维评分</h3>
        <span class="header-spacer"></span>
        <ScoreChip :score="score.total" label="总分" />
      </div>
      <template v-if="score.available">
        <div class="five-strip">
          <div class="fv">
            <span>D1 大盘</span><b class="sv">{{ fmtScore(score.d1) }}</b>
            <i class="done" v-if="score.d1 != null"></i><i class="pending" v-else></i>
          </div>
          <div class="fv">
            <span>D2 主线</span><b class="sv">{{ fmtScore(score.d2) }}</b>
            <i class="done" v-if="score.d2 != null"></i><i class="pending" v-else></i>
          </div>
          <div class="fv">
            <span>D3 连板</span><b class="sv">{{ fmtScore(score.d3) }}</b>
            <i class="done" v-if="score.d3 != null"></i><i class="pending" v-else></i>
          </div>
          <div class="fv">
            <span>D4 首板</span><b class="sv">{{ fmtScore(score.d4) }}</b>
            <i class="done" v-if="score.d4 != null"></i><i class="pending" v-else></i>
          </div>
          <div class="fv">
            <span>D5 高位</span><b class="sv">{{ fmtScore(score.d5) }}</b>
            <i class="done" v-if="score.d5 != null"></i><i class="pending" v-else></i>
          </div>
        </div>
        <div class="score-meta">
          <span>温度 {{ fmtScore(score.temperature) }}</span>
          <el-tag v-if="score.stage" size="small" :type="stageTagType">{{ score.stage }}</el-tag>
          <el-tag v-if="score.forcedEbb === 1" size="small" type="danger" effect="dark">强制退潮</el-tag>
          <span v-if="score.scoredDims != null" class="muted">已评 {{ score.scoredDims }}/5 维</span>
        </div>
        <p v-if="score.signalFlags" class="signal-line">{{ score.signalFlags }}</p>
        <p v-if="score.forcedEbbReason" class="ebb-reason">⛔ {{ score.forcedEbbReason }}</p>
      </template>
      <div v-else class="empty-note">
        该日还没有复盘记录：先「一键拉取行情」落库原始数据，评分在下方「保存复盘」后自动计算
        （职责分离——拉取只落原始数据，不洗人工维评分）。
      </div>
    </section>

    <!-- ============ 评分录入 + 复盘记录（人工读数据 + 保存） ============ -->
    <el-collapse class="entry-collapse">
      <el-collapse-item title="评分录入（人工读数 + 强制退潮 + 持仓台账）" name="entry">
        <div class="entry-body">
          <div class="ebb-chip" v-if="ebbActive">
            <span class="ebb-chip-ico">⛔</span><b>强制退潮</b> — {{ ebbReason || '已命中强制退潮条件' }}
          </div>

          <h4 class="entry-title">D2 / D4 / D5 人判读数 + 强制退潮闸门（与各维度页同源）</h4>
          <div v-if="!formReady" class="panel-warn">
            这天的记录没读回来：下面的人工读数（manual_* + 涨跌家数）这次<b>不会</b>发出
            （发了就等于把你已存的值连同你没显示的格子一起洗成空）。
          </div>
          <div class="stat-grid">
            <EditableStatCard v-for="m in MANUAL_METRICS" :key="m.metric"
              v-model="form[m.field]" :label="m.label" :unit="m.unit"
              :min="m.min" :max="m.max" :precision="m.precision" :step="m.step"
              :ph="m.ph" :hint="m.hint" />
          </div>

          <div class="action-row">
            <el-button type="primary" :loading="saving" @click="handleSave">
              {{ recordId ? '更新记录' : '提交记录' }}
            </el-button>
            <span v-if="savedRecord && savedRecord.totalScore != null" class="saved-score">
              当前总分 {{ savedRecord.totalScore }} / 温度 {{ savedRecord.temperature ?? '—' }}
            </span>
          </div>

          <h4 class="entry-title">持仓台账（整表替换当天行）</h4>
          <div class="ledger">
            <div class="card-head">
              <span class="header-spacer"></span>
              <el-button size="small" @click="addPositionRow">加一行</el-button>
              <el-button size="small" type="primary" plain :loading="savingPos" @click="savePositions">
                保存台账
              </el-button>
            </div>
            <el-table :data="posRows" size="small" empty-text="这天还没有持仓，点「加一行」录" class="dim-table">
              <el-table-column label="代码" width="92">
                <template #default="{ row }"><el-input v-model="row.stockCode" size="small" placeholder="6位" /></template>
              </el-table-column>
              <el-table-column label="名称" width="100">
                <template #default="{ row }"><el-input v-model="row.stockName" size="small" placeholder="名称" /></template>
              </el-table-column>
              <el-table-column label="成本" width="100">
                <template #default="{ row }">
                  <el-input-number v-model="row.costPrice" size="small" :min="0" :precision="2" :controls="false" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="现价" width="100">
                <template #default="{ row }">
                  <el-input-number v-model="row.currentPrice" size="small" :min="0" :precision="2" :controls="false" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="浮动%" width="100">
                <template #default="{ row }">
                  <el-input-number v-model="row.floatPct" size="small" :precision="2" :controls="false" style="width: 100%" placeholder="自动" />
                </template>
              </el-table-column>
              <el-table-column label="动作" width="100">
                <template #default="{ row }"><el-input v-model="row.action" size="small" placeholder="今日实际" /></template>
              </el-table-column>
              <el-table-column label="应做" width="100">
                <template #default="{ row }"><el-input v-model="row.plannedAction" size="small" placeholder="计划" /></template>
              </el-table-column>
              <el-table-column label="纪律" width="100">
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
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <!-- 监管人工补录弹窗 -->
    <el-dialog v-model="openSurv" title="人工补录监管" width="420px">
      <el-form label-position="top">
        <el-form-item label="股票代码"><el-input v-model="survForm.code" placeholder="6位，如 000001" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="survForm.kind" style="width: 100%">
            <el-option label="重要监管（SEVERE）" value="SEVERE" />
            <el-option label="交易所问询/下辖（EXCH）" value="EXCH" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题/原因"><el-input v-model="survForm.title" placeholder="一句话监管内容" /></el-form-item>
        <el-form-item label="日期"><el-input :model-value="form.tradeDate" disabled /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="openSurv = false">取消</el-button>
        <el-button type="primary" :loading="savingSurv" @click="submitSurveillance">补录</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { recordApi, importApi, reviewApi, prdApi, d5Api } from '../api/modules'
import { ElMessage } from 'element-plus'
import EditableStatCard from '../components/EditableStatCard.vue'

/* ======================================================================= */
/* 只读小组件：就绪度 badge / 得分 chip / 统计项                           */
/* ======================================================================= */
const ReadinessBadge = {
  props: { note: { type: String, default: 'ok' } },
  template: `<span class="rd-badge" :class="note==='ok' ? 'rd-ok' : 'rd-warn'">
    ${''}${`{{ note==='ok' ? '✅ 就绪' : '⚠️ ' + note }}`}</span>`
}
const ScoreChip = {
  props: { score: { type: [Number, String], default: null }, label: { type: String, default: '得分' } },
  template: `<span class="score-chip"><b class="sv">{{ score===null || score===undefined || score==='' ? '未评' : score }}</b>
    ${''}${`{{ label }}`}</span>`
}
const Stat = {
  props: { k: String, v: String },
  template: `<span class="stat-item"><span class="sk">{{k}}</span><b class="sv">{{v}}</b></span>`
}

/* 一行一个只在面板只读的小组件渲染 */
const components = { EditableStatCard, ReadinessBadge, ScoreChip, Stat }

/* ======================================================================= */
/* 状态                                                                    */
/* ======================================================================= */
const form = reactive({
  tradeDate: new Date().toLocaleDateString('en-CA'),
  maxConsecutiveLimit: null, limitUpCount: null, limitDownCount: null,
  yesterdayLimitPremium: null, brokenBoardRate: null, bigLossCount: null,
  totalVolume: null, upCount: null, downCount: null,
  manualSectorLimitUpCount: null, manualLadderCompleteScore: null, manualSectorPremiumPct: null,
  manualThemePersistenceDays: null, manualFirstPremiumPct: null, manualFirstSealedRate: null,
  manualAnchorSupervisionDiscount: null, manualTopHighTurnoverPct: null, manualTopHighBreak: null
})

/* 五维 dashboard（/api/review/detail） */
const dashboard = ref(null)
const d1 = computed(() => dashboard.value?.d1 || { indexes: [], daily: null })
const d2 = computed(() => dashboard.value?.d2 || { industries: [], topics: [] })
const score = computed(() => dashboard.value?.score || {})
const readiness = computed(() => dashboard.value?.readiness || {})

/* ---- D3/D4/D5 内联复用子页面数据（拉一次当日） ---- */
const tianti = ref(null)        // /tianti 连板天梯
const shouban = ref(null)       // /shouban 首板封住名单
const d5high = ref(null)        // /d5/high 阵眼 + 监管池
const themesData = ref(null)   // 题材表：来自 /intraday/themes（与日内核心页题材表同源）
const themesView = computed(() => (themesData.value?.themes || []).slice(0, 5))

/* D4 首板封住名单：chip 样式与首板生态页同款（默认展开，点头部折叠） */
const sealedOpen = ref(false)
const SB_PATTERN_LABEL = { ONE_LINE: '一字', T_SHAPE: 'T字', TURNOVER: '换手' }
const SB_PATTERN_TYPE = { ONE_LINE: 'danger', T_SHAPE: 'warning', TURNOVER: 'info' }

const fetching = ref(false)
const fetchTasks = ref([])        // {task,status,rows,msg} 原始 SSE 流
const fetchOverall = ref('')      // DONE / PARTIAL / FAILED / PENDING
const hasRun = ref(false)

const TASK_ORDER = ['T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'T8']
const TASK_LABEL = { T1: '回补', T2: '指数', T3: '统计', T4: '三池', T5: '板块', T6: '溢价', T7: '监管', T8: '计算' }

const orderedTasks = computed(() => {
  const map = {}
  fetchTasks.value.forEach(t => { if (!map[t.task]) map[t.task] = t })
  return TASK_ORDER.map(t => ({ task: t + ' ' + TASK_LABEL[t], status: map[t] ? map[t].status : (hasRun.value ? '' : 'pending'), rows: map[t]?.rows, msg: map[t]?.msg }))
})
function chipIcon(s) { return s === 'ok' ? '✅' : s === 'fail' ? '❌' : s === 'warn' ? '⚠️' : s === 'running' ? '◐' : '·' }
function chipMsg(t) { return t.msg ? t.msg.replace(/.*?(：|完成|成功|失败).*?/, m => m).slice(0, 26) : '' }

const overallTag = computed(() =>
  fetchOverall.value === 'DONE' ? 'success' : fetchOverall.value === 'PARTIAL' ? 'warning' : fetchOverall.value === 'FAILED' ? 'danger' : 'info')
const overallText = computed(() => {
  const map = { DONE: '数据已就绪 8/8', PARTIAL: '部分完成（缺项见各块）', FAILED: '拉取失败', PENDING: '', RUNNING: '拉取中' }
  return map[fetchOverall.value] || ''
})

function scoreOf(key) {
  const s = score.value
  if (!s.available) return null
  return { D1: s.d1, D2: s.d2, D3: s.d3, D4: s.d4, D5: s.d5 }[key]
}
function readinessClass(key) {
  const n = readiness.value[key] || ''
  return n === 'ok' ? 'rd-ok-block' : 'rd-warn-block'
}

/* ======================================================================= */
/* 手动评分录入 / 记录保存（保留既有语义）                                 */
/* ======================================================================= */
const MANUAL_METRICS = [
  { metric: 'sector_limit_up_count', field: 'manualSectorLimitUpCount', label: '板块涨停数', unit: '家', min: 0, max: 200, precision: 0, step: 1, ph: '家', hint: '第一主线今日涨停家数' },
  { metric: 'ladder_complete_score', field: 'manualLadderCompleteScore', label: '梯队完整性', unit: '分', min: 0, max: 100, precision: 0, step: 5, ph: '0-100', hint: '完整梯队≈90，单高标无跟风≈35' },
  { metric: 'sector_premium_pct', field: 'manualSectorPremiumPct', label: '板块溢价', unit: '%', min: -30, max: 50, precision: 2, step: 0.5, ph: '%', hint: '主线板块昨涨停今日平均溢价' },
  { metric: 'persistence_days', field: 'manualThemePersistenceDays', label: '持续性', unit: '天', min: 0, max: 30, precision: 0, step: 1, ph: '天', hint: '≥3 天算持续，首日算新启动' },
  { metric: 'first_sealed_rate', field: 'manualFirstSealedRate', label: '首板封板率', unit: '%', min: 0, max: 100, precision: 2, step: 1, ph: '%', hint: '首板封住 /（封住+炸），只能人判' },
  { metric: 'first_premium_pct', field: 'manualFirstPremiumPct', label: '首板溢价', unit: '%', min: -30, max: 50, precision: 2, step: 0.5, ph: '%', hint: '自动取数没并进 board=1 时兜底' },
  { metric: 'anchor_supervision_discount', field: 'manualAnchorSupervisionDiscount', label: '监管折扣', unit: '×', min: 0, max: 1, precision: 2, step: 0.05, ph: '0-1', hint: '阵眼被监管乘数，留空不打折' },
  { metric: 'top_high_turnover_pct', field: 'manualTopHighTurnoverPct', label: '极高位换手', unit: '%', min: 0, max: 100, precision: 1, step: 1, ph: '%', hint: 'H≥7 用得上：>35% 且断板=强制退潮' },
  { metric: 'top_high_break', field: 'manualTopHighBreak', label: '极高位断板', ph: '未判', options: [{ value: 1, label: '是 · 爆量断板未回封' }, { value: 0, label: '否 · 封住或已回封' }], hint: '留空 = 强制退潮条件 4 不参与' }
]

const recordId = ref(null)
const savedRecord = ref(null)
const formReady = ref(false)
const saving = ref(false)

/* 可复盘交易日集合（降序，[0]=最近交易日）：非交易日/未拉取日置灰不可选 */
const tradingDays = ref([])
const disableDate = (d) => {
  if (!tradingDays.value.length) return false // 从未拉取时放开选择，允许手动输入后一键拉取
  const p = (n) => String(n).padStart(2, '0')
  const key = d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
  return !tradingDays.value.includes(key)
}
async function loadTradingDays() {
  try {
    const r = await reviewApi.tradingDays()
    tradingDays.value = r?.data || []
    // 进入页面优先选最近交易日：当前选中日不在交易日集合里就切到集合第一个（最近交易日）
    if (tradingDays.value.length && !tradingDays.value.includes(form.tradeDate)) {
      form.tradeDate = tradingDays.value[0]   // 触发下方 watch → handleDateChange
    }
  } catch (e) { tradingDays.value = [] }
}
const ebbActive = computed(() => savedRecord.value?.forcedEbb === 1)
const ebbReason = computed(() => savedRecord.value?.forcedEbbReason || '')

const FORM_OWNED_KEYS = ['upCount', 'downCount',
  'manualSectorLimitUpCount', 'manualLadderCompleteScore', 'manualSectorPremiumPct',
  'manualThemePersistenceDays', 'manualFirstPremiumPct', 'manualFirstSealedRate',
  'manualAnchorSupervisionDiscount', 'manualTopHighTurnoverPct', 'manualTopHighBreak']

const stageTagType = computed(() => {
  const map = { '退潮': 'info', '混沌': '', '发酵': 'warning', '高潮': 'danger', '退潮(强制)': 'danger' }
  return map[savedRecord.value?.stage] || 'info'
})

const DISCIPLINES = ['遵守', '违约', '待执行']
const posRows = ref([])
const savingPos = ref(false)

async function loadRecord(date) {
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
    formReady.value = true
  } catch (e) { /* 读不回就不发人工键（见 formReady） */ }
}

async function loadDashboard(date) {
  if (!date) return
  try {
    const res = await reviewApi.detail(date)
    if (form.tradeDate !== date) return
    dashboard.value = res.data || null
  } catch (e) { /* detail 只读，取不到时块内空态 */ }
  try {
    const s = await reviewApi.status(date)
    if (form.tradeDate === date && s.data) {
      fetchOverall.value = s.data.overall || ''
      try { fetchTasks.value = JSON.parse(s.data.tasksJson || '[]') } catch (e) { fetchTasks.value = [] }
      hasRun.value = true
    }
  } catch (e) { /* 没有该日状态不弹条 */ }
}

function handleDateChange(date) {
  dashboard.value = null
  fetchTasks.value = []
  fetchOverall.value = ''
  hasRun.value = false
  loadRecord(date)
  loadDashboard(date)
  loadReuse(date)
}
watch(() => form.tradeDate, d => handleDateChange(d))

/* ---- D3/D4/D5 内联复用数据 + D2 题材索引 ---- */
async function loadReuse(date) {
  if (!date) return
  tianti.value = null; shouban.value = null; d5high.value = null
  prdApi.tianti(date).then(r => { if (form.tradeDate === date) tianti.value = r.data || null }).catch(() => {})
  prdApi.shouban(date).then(r => { if (form.tradeDate === date) shouban.value = r.data || null }).catch(() => {})
  d5Api.high(date).then(r => { if (form.tradeDate === date) d5high.value = r.data || null }).catch(() => {})
  loadThemes(date)   // D2 题材热度：日内核心题材表（概念维度，后端自动回填）
}

/* D2 题材热度数据源：日内核心页题材表（t_theme/t_theme_stock，后端按热门行业自动回填） */
async function loadThemes(date) {
  try {
    const r = await prdApi.intradayThemes(date)
    if (form.tradeDate === date) themesData.value = r?.data || null
  } catch (e) { themesData.value = null }
}

async function handleFetch() {
  const date = form.tradeDate
  if (!date) { ElMessage.warning('请先选择交易日期'); return }
  fetching.value = true
  fetchTasks.value = []
  fetchOverall.value = 'RUNNING'
  hasRun.value = true
  try {
    await reviewApi.fetch(date, ev => {
      if (form.tradeDate !== date) return
      if (ev.task === 'done') { fetchOverall.value = 'DONE' }
      fetchTasks.value.push({ task: ev.task, status: ev.status, rows: ev.rows, msg: ev.msg })
    })
    if (form.tradeDate !== date) return
    const s = await reviewApi.status(date)
    if (s.data) fetchOverall.value = s.data.overall || 'DONE'
    await loadDashboard(date)   // 落库后按最新数据刷新 D1-D5 + 就绪度
    loadReuse(date)             // T4 三池已回写，刷新内联的天梯/首板/高位
    ElMessage.success('拉取完成，五维数据已刷新')
  } catch (e) {
    fetchOverall.value = 'FAILED'
  } finally {
    fetching.value = false
  }
}

function fillForm(data) {
  Object.keys(form).forEach(k => { if (data[k] !== null && data[k] !== undefined) form[k] = data[k] })
}
function payload() {
  const body = { ...form }
  Object.keys(body).forEach(k => { if (body[k] === undefined) body[k] = null })
  if (!formReady.value) FORM_OWNED_KEYS.forEach(k => delete body[k])
  return body
}

async function handleSave() {
  const date = form.tradeDate
  if (!date) return
  saving.value = true
  try {
    const res = await reviewApi.save(payload())
    savedRecord.value = res.data
    recordId.value = res.data?.id || null
    formReady.value = true
    ElMessage.success(recordId.value ? '更新成功' : '提交成功')
    await loadDashboard(date)  // 评分已回写，刷新 D1-D5 得分
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally { saving.value = false }
}

const exportingDoc = ref(false)
async function handleExportDoc() {
  const date = form.tradeDate
  if (!date) return
  exportingDoc.value = true
  try {
    const res = await reviewApi.exportDoc(date)
    const vo = res.data || {}
    if (!vo.content) { ElMessage.warning(date + ' 没生成出内容'); return }
    saveFile('复盘_' + (vo.date || date) + '.md', vo.content)
    ElMessage.success('已导出')
  } catch (e) { /* 拦截器已弹 */ } finally { exportingDoc.value = false }
}
function saveFile(name, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }))
  const a = document.createElement('a'); a.href = url; a.download = name; a.click()
  URL.revokeObjectURL(url)
}

/* 台账 */
function addPositionRow() {
  posRows.value.push({ stockCode: '', stockName: '', costPrice: null, currentPrice: null, floatPct: null, action: '', plannedAction: '', discipline: '' })
}
async function savePositions() {
  const date = form.tradeDate
  if (!date) return
  savingPos.value = true
  try {
    await recordApi.savePositions(date, posRows.value)
    ElMessage.success('持仓台账已存')
  } catch (e) { /* 拦截器弹 */ } finally { savingPos.value = false }
}

/* 监管人工补录 */
const openSurv = ref(false)
const savingSurv = ref(false)
const survForm = reactive({ code: '', kind: 'SEVERE', title: '' })
async function submitSurveillance() {
  if (!survForm.code) { ElMessage.warning('请填股票代码'); return }
  savingSurv.value = true
  try {
    await reviewApi.manualSurveillance({ ...survForm, date: form.tradeDate })
    ElMessage.success('已补录')
    openSurv.value = false
    survForm.code = ''; survForm.title = ''
    await loadDashboard(form.tradeDate)
  } catch (e) { /* 拦截器弹 */ } finally { savingSurv.value = false }
}

/* 格式化 */
function fmtNum(v) { return v === null || v === undefined ? '—' : Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }
function fmtPct(v) { return v === null || v === undefined ? '—' : (Number(v) / 100).toFixed(2) + '%' }
function fmtPctVal(v) { return v === null || v === undefined ? '—' : Number(v).toFixed(1) + '%' }
function fmtScore(v) { return v === null || v === undefined || v === '' ? '未评' : Number(v).toFixed(1) }
function fmtYi(v) { if (v === null || v === undefined) return '—'; const n = Number(v); const yi = n / 1e8; return yi >= 100 ? yi.toFixed(0) : yi.toFixed(1) }
function nz(v, raw = false) { if (v === null || v === undefined) return '—'; return raw ? String(v) : Number(v).toLocaleString('zh-CN') }
function pctClass(v) { if (v === null || v === undefined) return ''; const n = Number(v); return n > 0 ? 'up' : n < 0 ? 'down' : '' }
function signed(v) { if (v === null || v === undefined) return ''; const n = Number(v); return (n > 0 ? '+' : '') + n.toFixed(2) }
function moneyText(v) { const n = Number(v); if (Number.isNaN(n)) return '—'; if (n >= 1e8) return (n / 1e8).toFixed(2) + ' 亿'; if (n >= 1e4) return (n / 1e4).toFixed(0) + ' 万'; return n.toFixed(0) + ' 元' }
const patternText = (p) => ({ ONE_LINE: '一字', T_SHAPE: 'T字', TURNOVER: '换手' }[p] || p || '—')
const patternClass = (p) => ({ ONE_LINE: 'p-one', T_SHAPE: 'p-t', TURNOVER: 'p-turn' }[p] || '')
const statusClass = (s) => s && /涨停|核按钮/.test(s) ? 'st-danger' : (/断板|分歧/.test(s || '') ? 'st-warn' : '')
const topIndustries = computed(() => (d2.value.industries || []).slice(0, 5))

onMounted(async () => {
  await loadTradingDays()
  handleDateChange(form.tradeDate)
})
</script>

<style scoped>
.review-page { max-width: 1240px; margin: 0 auto; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; flex-wrap: wrap; }
.page-header h2 { margin: 0; color: #e1e8ed; }
.header-spacer { flex: 1; }

/* 拉取进度 */
.fetch-progress { background: #131c28; border: 1px solid #22303f; border-radius: 12px; padding: 12px 16px; margin-bottom: 18px; }
.fp-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.fp-title { color: #8899a6; font-size: 13px; font-weight: 600; }
.fp-live { color: #22d3ee; font-size: 12px; animation: blink 1s steps(2, start) infinite; }
@keyframes blink { to { opacity: .3; } }
.fp-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; }
.fp-chip { display: flex; align-items: center; gap: 6px; padding: 6px 10px; border-radius: 8px; background: #16202e; border: 1px solid #22303f; font-size: 12px; min-width: 0; }
.fp-task { color: #cbd5e0; flex: none; font-weight: 600; }
.fp-icon { flex: none; }
.fp-rows { color: #67e8f9; flex: none; }
.fp-msg { color: #8899a6; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fp-ok { border-color: #166534; }
.fp-warn { border-color: #a16207; }
.fp-fail { border-color: #991b1b; }
.fp-running { border-color: #0e7490; }
.fp-pending { opacity: .5; }

/* 维度块 */
.dim-block { background: #1a2332; border-radius: 12px; padding: 16px 18px 18px; margin-bottom: 16px; border: 1px solid transparent; }
.dim-block.rd-ok-block { }
.dim-block.rd-warn-block { border-color: rgba(217, 119, 6, .5); }
.block-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.block-head h3 { margin: 0; color: #e1e8ed; font-size: 15px; }
.block-head .slice { color: #64748b; font-size: 12px; font-weight: 400; }
.score-chip { background: rgba(8, 145, 178, .12); color: #67e8f9; border: 1px solid rgba(8, 145, 178, .4); border-radius: 6px; padding: 2px 8px; font-size: 12px; }
.score-chip b { color: #22d3ee; margin-right: 4px; }
.rd-badge { font-size: 11px; border-radius: 10px; padding: 2px 8px; }
.rd-ok { color: #4ade80; background: rgba(74, 222, 128, .1); border: 1px solid rgba(74, 222, 128, .4); }
.rd-warn { color: #fbbf24; background: rgba(251, 191, 36, .1); border: 1px solid rgba(251, 191, 36, .4); }

.index-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; margin-bottom: 12px; }
.index-card { background: #131c28; border: 1px solid #22303f; border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.ix-name { color: #8899a6; font-size: 12px; }
.ix-close { color: #e1e8ed; font-size: 18px; font-weight: 700; }
.ix-pct { font-size: 13px; }
.up { color: #f87171; }
.down { color: #34d399; }

.stat-line { display: flex; flex-wrap: wrap; gap: 10px 18px; }
.stat-item { display: flex; flex-direction: column; gap: 2px; }
.sk { color: #8899a6; font-size: 11px; }
.sv { color: #e1e8ed; font-size: 14px; font-weight: 600; }

.dim-table { width: 100%; margin-top: 6px; }
.dim-table :deep(.el-table) { --el-table-bg-color: transparent; --el-table-tr-bg-color: transparent; --el-table-header-bg-color: #16202e; --el-table-border-color: #22303f; --el-table-text-color: #cbd5e0; --el-table-header-text-color: #8899a6; --el-table-row-hover-bg-color: #22303f; }
.dim-table :deep(.el-table::before) { background-color: #22303f; }
.dim-more { color: #64748b; font-size: 11px; margin: 6px 0 0; }
.dim-note { color: #cbd5e0; font-size: 12px; margin: 8px 0 0; line-height: 1.5; }
.dim-note b { color: #dc5b5b; }

.group-line, .ladder-line { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 10px; }
.g-title { color: #8899a6; font-size: 12px; }
.g-chip, .ladder-chip { background: #16202e; border: 1px solid #22303f; border-radius: 8px; padding: 3px 8px; font-size: 12px; color: #cbd5e0; }
.g-chip b { color: #e1e8ed; margin-left: 4px; }
.g-chip i { font-style: normal; color: #f87171; margin-left: 4px; }
.g-chip i.neg { color: #f87171; }

/* D2 日内核心：题材热度 + 核心板块 双列同时展示 */
.d2-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 4px; }
.d2-col { min-width: 0; }
.d2-sub { margin: 0 0 8px; font-size: 13px; color: #e1e8ed; display: flex; align-items: baseline; gap: 6px; }
.d2-sub .muted { font-size: 11px; color: #64748b; font-weight: 400; }
@media (max-width: 860px) { .d2-grid { grid-template-columns: 1fr; } }
.topic-list { display: flex; flex-direction: column; gap: 6px; }
.topic-row { display: flex; align-items: center; gap: 10px; background: #131c28; border: 1px solid #22303f; border-radius: 8px; padding: 7px 10px; }
.topic-rank { width: 20px; height: 20px; border-radius: 6px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700; color: #0b0f14; flex: none; }
.topic-rank.rk1 { background: #fbbf24; }
.topic-rank.rk2 { background: #cbd5e0; }
.topic-rank.rk3 { background: #b45309; color: #fff; }
.topic-rank.rk4, .topic-rank.rk5 { background: #22303f; color: #8899a6; }
.topic-name { color: #e1e8ed; font-weight: 600; font-size: 13px; }
.topic-sub { color: #64748b; font-size: 12px; }

/* D3 连板天梯层级 */
.tier-block { border: 1px solid #22303f; border-radius: 10px; padding: 8px 10px; margin-top: 10px; background: #131c28; }
.tier-block.gap { opacity: .7; border-style: dashed; }
.tier-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.tier-board { color: #fbbf24; font-weight: 700; font-size: 13px; }
.tier-layer { color: #64748b; font-size: 11px; border: 1px solid #22303f; border-radius: 10px; padding: 0 7px; }
.tier-count { color: #8899a6; font-size: 12px; margin-left: auto; }
.tier-rows { display: flex; flex-wrap: wrap; gap: 6px; }
.tier-stock { display: inline-flex; align-items: center; gap: 5px; background: #16202e; border: 1px solid #22303f; border-radius: 7px; padding: 3px 8px; font-size: 12px; color: #cbd5e0; }
.tier-stock .star { color: #fbbf24; margin-right: 1px; }
.ts-name { color: #e1e8ed; }
.ts-pattern { font-size: 10px; padding: 0 4px; border-radius: 4px; }
.p-one { color: #f87171; background: rgba(248, 113, 113, .12); }
.p-t { color: #fbbf24; background: rgba(251, 191, 36, .12); }
.p-turn { color: #67e8f9; background: rgba(103, 232, 249, .12); }
.ts-role { font-style: normal; color: #c084fc; font-size: 11px; }
.ts-break { color: #fbbf24; font-size: 10px; }
.tier-empty { color: #64748b; font-size: 12px; }
.tier-failed { margin-top: 6px; color: #cbd5e0; font-size: 12px; }
.f-title { color: #8899a6; }
.f-chip { color: #64748b; margin-right: 4px; }

/* D5 阵眼 + 监管池 */
.d5-subtitle { color: #8899a6; font-size: 12px; font-weight: 600; margin: 12px 0 8px; }
.anchor-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 8px; margin-bottom: 4px; }
.anchor-card { background: #131c28; border: 1px solid #22303f; border-radius: 10px; padding: 9px 11px; }
.anchor-line1 { display: flex; align-items: center; gap: 6px; }
.anchor-line1 b { color: #e1e8ed; font-size: 13px; }
.role-tag { color: #c084fc; font-size: 10px; border: 1px solid rgba(192, 132, 252, .4); border-radius: 8px; padding: 0 5px; }
.anchor-line2 { display: flex; align-items: center; gap: 8px; margin-top: 4px; color: #cbd5e0; font-size: 12px; }
.ac-act { color: #22d3ee; font-size: 11px; }
.anchor-line3 { color: #64748b; font-size: 11px; margin-top: 4px; }
.mon-status { font-size: 12px; }
.mon-status.st-danger { color: #f87171; }
.mon-status.st-warn { color: #fbbf24; }
.d5-action { margin-top: 12px; }

/* D4 首板封住名单：chip 样式（复用首板生态页封住名单同款） */
.sb-list { margin-top: 8px; }
.list-head { display: flex; align-items: center; gap: 8px; cursor: pointer; user-select: none; border-radius: 8px; padding: 6px 8px; transition: background .15s; border-bottom: 1px dashed #33455a; }
.list-head:hover { background: rgba(255, 255, 255, .025); }
.list-head:hover h4 { color: #fff; }
.list-head .fold-tag { margin-left: 4px; }
.list-head h4 { margin: 0; color: #e1e8ed; font-size: 14px; }
.list-count { font-size: 12px; font-weight: 600; margin-left: auto; }
.ok-text { color: #6ee7b7; }
.fold-tag {
  display: inline-block;
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 600;
  color: #9fb2c6;
  background: #22303f;
  border: 1px solid #3a4d63;
  border-radius: 999px;
  line-height: 1.7;
  transition: color .18s, border-color .18s, background .18s, transform .12s;
  vertical-align: middle;
}
.list-head:hover .fold-tag { color: #ffd166; border-color: #ffd166; background: #2b3d52; }
.list-head:active .fold-tag { transform: translateY(1px); background: #2f4258; }
.list-body { padding: 12px 4px 0; }
.list-empty { color: #6b7c8c; font-size: 12px; padding: 4px 0; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  background: #0f1419; border: 1px solid #2d3748; border-radius: 999px;
  padding: 4px 10px; font-size: 12px;
}
.chip-name { color: #e1e8ed; font-weight: 600; }
.chip-code { color: #6b7c8c; font-size: 11px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.chip-industry { color: #a8b7c4; font-size: 11px; }
.chip-industry.in-main { color: #fbbf24; }
.chip-seal { color: #a8b7c4; font-size: 11px; }
.chip-reseal { color: #7dd3fc; font-size: 11px; background: rgba(56, 189, 248, .12); border-radius: 6px; padding: 1px 6px; }
.chip-pct { font-weight: 600; font-size: 11px; }

/* 评分总览 */
.five-strip { display: flex; flex-wrap: wrap; gap: 12px 20px; margin-bottom: 10px; }
.fv { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.fv span { color: #8899a6; }
.fv b { color: #22d3ee; min-width: 34px; }
.fv i { width: 8px; height: 8px; border-radius: 50%; }
.fv i.done { background: #4ade80; }
.fv i.pending { background: #64748b; }
.score-meta { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #8899a6; }
.score-meta .muted { color: #64748b; }
.signal-line { color: #cbd5e0; font-size: 12px; margin: 8px 0 0; }
.ebb-reason { color: #fca5a5; font-size: 12px; margin: 6px 0 0; }
.empty-note { color: #8899a6; font-size: 12.5px; line-height: 1.5; }

.entry-collapse { margin-top: 18px; }
.entry-collapse :deep(.el-collapse) { border-color: #22303f; }
.entry-collapse :deep(.el-collapse-item__header) { background: #182230; color: #cbd5e0; }
.entry-collapse :deep(.el-collapse-item__wrap) { background: #131c28; }
.entry-body { padding: 6px 2px; }
.entry-title { margin: 14px 0 10px; color: #8899a6; font-size: 13px; font-weight: 600; }
.entry-title:first-child { margin-top: 0; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(210px, 100%), 1fr)); gap: 10px; }
.action-row { display: flex; align-items: center; gap: 12px; margin: 14px 0; }
.saved-score { color: #fbbf24; font-size: 13px; }
.ledger .card-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.ebb-chip { display: flex; gap: 8px; align-items: center; padding: 9px 12px; border-radius: 8px; background: rgba(239, 68, 68, .16); border: 1px solid #ef4444; color: #fecaca; font-size: 12.5px; margin-bottom: 12px; }
.panel-warn { color: #d97706; font-size: 12px; margin: 8px 0; line-height: 1.5; }
</style>