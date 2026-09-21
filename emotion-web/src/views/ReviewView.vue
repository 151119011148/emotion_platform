<template>
  <div class="review-page">
    <!-- ============ 顶栏：日期 + 一键拉取 + 导出 + 就绪状态 ============ -->
    <div class="page-header">
      <h2>每日复盘<el-tooltip placement="top" effect="dark">
          <template #content>
            <div style="max-width: 340px; line-height: 1.6;">
              按下「一键拉取行情」后按序执行：<br>
              T1 回补历史缺口 → T2/T3 拉全市场池子 → T4 连板天梯 → T5 行业/题材聚合 →<br>
              T6 监管信号 → T7 动态整理 → T8 五维（D1-D5）重算与就绪度点亮。<br>
              全程 SSE 实时回传，任一步失败只标黄、不中断后续。
            </div>
          </template>
          <span class="fp-help">?</span>
        </el-tooltip></h2>
      <el-date-picker v-model="pickerDate" type="date" value-format="YYYY-MM-DD"
        :disabled-date="disabledDate" :cell-class-name="cellClass" placeholder="选择交易日" style="width: 160px" />
      <span class="header-spacer"></span>
      <el-tag v-if="fetchOverall" :type="overallTag" effect="dark">{{ overallText }}</el-tag>
      <el-button type="primary" :loading="fetching" @click="handleFetch">🔄 一键拉取行情</el-button>
      <el-button :loading="exportingDoc" :disabled="!form.tradeDate" @click="handleExportDoc">
        导出复盘文档
      </el-button>
    </div>

    <!-- ============ 外溢②：昨日（T-1）遗留决策 —— 只留一条入口，
         明细与「标记执行」都在下方持仓台账里，不再复制出第二套 DOM ============ -->
    <div v-if="pendingCarry.length" class="pend-jump">
      <span class="pend-jump-tag">⚠️ 待裁决 {{ pendingCarry.length }} 条</span>
      <span class="dim-muted">{{ pendingCarryDate }} 写下的次日决策，今日执行</span>
      <el-button size="small" type="warning" plain @click="scrollToLedger">去台账处理</el-button>
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

    <!-- ============ 五维评分总览（置顶，先看全局结论再看各维细节） ============ -->
    <section class="dim-block score-overview">
      <div class="block-head">
        <h3>五维评分</h3>
        <span class="header-spacer"></span>
        <ScoreChip :score="score.total" label="总分" />
        <el-button type="primary" :loading="saving" :disabled="!form.tradeDate" @click="handleSave">
          💾 保存复盘
        </el-button>
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

    <!-- ============ 持仓台账（整表替换当天行） ============ -->
    <section class="entry-collapse" ref="ledgerRef">
      <div class="entry-body">
        <h4 class="entry-title">
          持仓台账 <span class="dim-muted">整表替换当天行</span>
          <span v-if="posDirty" class="dirty-tag">● 未保存</span>
        </h4>

        <!-- 外溢②：昨日遗留决策的执行闭环统一收在台账里；页面顶部只留一条跳转入口，
             不再复制出第二套 DOM（同一批数据两套 UI 是之前最容易改漏一处的地方）。 -->
        <div v-if="pendingCarry.length" class="pend-block">
          <div class="pend-head">⚠️ 昨日遗留决策（{{ pendingCarryDate }} 写，今日执行）</div>
          <div v-for="p in pendingCarry" :key="p.id" class="pend-row">
            <span class="pend-stock"><b>{{ p.stockName }}</b> <span class="muted">{{ p.stockCode }}</span>
              <span v-if="p.boardNum" class="pend-board">{{ p.boardNum }}板</span>
              <span v-if="p.industry" class="pend-ind">{{ p.industry }}</span></span>
            <span class="pend-plan">
              <span v-if="p.planOpen" class="pend-tier">高开→{{ p.planOpen }}</span>
              <span v-if="p.planBreak" class="pend-tier">炸板→{{ p.planBreak }}</span>
              <span v-if="p.planLow" class="pend-tier">平开/低开→{{ p.planLow }}</span>
              <span v-if="p.planFall" class="pend-tier">跌停→{{ p.planFall }}</span>
              <span v-if="!p.planOpen && !p.planBreak && !p.planLow && !p.planFall && p.nextDayPlan"
                    class="pend-tier">{{ p.nextDayPlan }}</span>
            </span>
            <span class="pend-act">
              <el-input v-model="pendingAct[p.id]" size="small" placeholder="今日实际动作" style="width: 150px" />
              <el-button size="small" type="primary" plain :loading="markingPend" @click="markExecuted(p)">标记执行</el-button>
            </span>
          </div>
          <div class="pend-done">✅ 执行后点「标记执行」，自动回填实际动作并关闭该裁决。</div>
        </div>

        <!-- 汇总两条口径并存，各吃自己吃得下的行：
             ① 金额口径（市值/盈亏额/占比）——只收填了股数、且成本现价都在的行；
             ② 等权口径（平均浮动%/盈亏家数）——只看浮动%，缺股数的行仍在这里。
             缺股数时绝不把股价相加当成本：10 元的票 + 100 元的票 = 110，那不是成本合计。 -->
        <div v-if="posRows.length" class="ledger-summary">
          <span>持仓 <b>{{ holdingRows.length }}</b> 只<template v-if="clearedRows.length"> · 清仓 <b>{{ clearedRows.length }}</b> 只</template></span>
          <span v-if="ledgerSummary.qtyCount">
            市值 <b>{{ yuan(ledgerSummary.marketValue) }}</b>
            <span class="dim-muted">{{ ledgerSummary.qtyCount }}/{{ posRows.length }} 有股数</span>
          </span>
          <span v-if="ledgerSummary.pnlAmount !== null">
            浮动盈亏 <b :class="pctClass(ledgerSummary.pnlAmount)">{{ yuan(ledgerSummary.pnlAmount) }}</b>
            <span v-if="ledgerSummary.pnlPct !== null" :class="pctClass(ledgerSummary.pnlPct)">{{ signed(ledgerSummary.pnlPct) }}%</span>
          </span>
          <span v-if="ledgerSummary.avgFloat !== null">
            平均浮动 <b :class="pctClass(ledgerSummary.avgFloat)">{{ signed(ledgerSummary.avgFloat) }}%</b>
            <span class="dim-muted">等权 · {{ ledgerSummary.priced }}/{{ posRows.length }} 有价</span>
          </span>
          <span>盈 <b class="up">{{ ledgerSummary.winCount }}</b> · 亏 <b class="down">{{ ledgerSummary.loseCount }}</b></span>
          <span>次日预案 <b>{{ ledgerSummary.planCount }}/{{ posRows.length }}</b></span>
        </div>

        <div class="ledger">
          <div class="card-head">
            <el-radio-group v-model="posTab" size="small">
              <el-radio-button value="all">全部 {{ posRows.length }}</el-radio-button>
              <el-radio-button value="hold">持仓中 {{ holdingRows.length }}</el-radio-button>
              <el-radio-button value="cleared">今日清仓 {{ clearedRows.length }}</el-radio-button>
            </el-radio-group>
            <span v-if="undoTip" class="undo-chip">
              {{ undoTip }}
              <el-button size="small" text type="primary" @click="applyUndo">撤销</el-button>
            </span>
            <span class="header-spacer"></span>
            <el-button size="small" plain :loading="fillingPrice" @click="fillCurrentPrices">补现价</el-button>
            <el-button size="small" plain @click="openImportDialog">批量带入</el-button>
            <el-button size="small" @click="addPositionRow">加一行</el-button>
            <el-button size="small" type="primary" plain :loading="savingPos" :disabled="!posDirty" @click="savePositions">
              保存台账
            </el-button>
          </div>

          <!-- 一张表吃三种视图：Tab 只筛选行，列集合三者完全一致。
               以前 ①/② 各换一张表、列还不一样，想填「延迟天」得先改状态换 Tab。 -->
          <el-table :data="tableRows" size="small" class="dim-table"
            empty-text="暂无持仓，点「加一行」或用「批量带入」录入">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="plan-tiers">
                  <span class="pt-label">{{ row.boardNum ? row.boardNum + '板面临几进几，按分档填：' : '次日竞价分档：' }}</span>
                  <span class="pt-cell">高开→<el-input v-model="row.planOpen" size="small" placeholder="冲高减半" /></span>
                  <span class="pt-cell">炸板→<el-input v-model="row.planBreak" size="small" placeholder="板砸" /></span>
                  <span class="pt-cell">平开/低开→<el-input v-model="row.planLow" size="small" placeholder="竞价走" /></span>
                  <span class="pt-cell">跌停→<el-input v-model="row.planFall" size="small" placeholder="割" /></span>
                </div>
                <div class="plan-foot">
                  💡 四档是②仪表盘「待裁决」卡的取数源，写一格即可外溢。
                  <span v-if="row.executed" class="exec-done">已执行：{{ row.actualAction || '（未填实际动作）' }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="股票（代码/名称）" min-width="190">
              <template #default="{ row }">
                <el-select v-model="row.stockCode" filterable remote clearable size="small"
                  :remote-method="(q) => searchStocks(q, row)" :loading="row._loading"
                  placeholder="输代码或名称搜索" @change="pickStock(row)" @clear="row.stockName = ''">
                  <el-option v-for="s in row._results" :key="s.code" :value="s.code" :label="s.code + ' ' + s.name" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="板块" width="88">
              <template #default="{ row }"><el-input v-model="row.industry" size="small" placeholder="板块" /></template>
            </el-table-column>
            <el-table-column label="板数" width="62">
              <template #default="{ row }">
                <el-input-number v-model="row.boardNum" size="small" :min="1" :max="10" :controls="false" style="width: 100%" placeholder="板" />
              </template>
            </el-table-column>
            <el-table-column label="成本" width="82">
              <template #default="{ row }">
                <el-input-number v-model="row.costPrice" size="small" :min="0" :precision="2" :controls="false"
                  style="width: 100%" @change="syncFloat(row, 'cost')" />
              </template>
            </el-table-column>
            <el-table-column label="现价" width="82">
              <template #default="{ row }">
                <el-input-number v-model="row.currentPrice" size="small" :min="0" :precision="2" :controls="false"
                  style="width: 100%" @change="syncFloat(row, 'price')" />
              </template>
            </el-table-column>
            <el-table-column label="股数" width="76">
              <template #default="{ row }">
                <el-input-number v-model="row.quantity" size="small" :min="1" :step="100" :controls="false"
                  style="width: 100%" placeholder="股数" />
              </template>
            </el-table-column>
            <el-table-column label="浮动%" width="78">
              <template #default="{ row }">
                <el-input-number v-model="row.floatPct" size="small" :precision="2" :controls="false" style="width: 100%"
                  :placeholder="row.costPrice && row.currentPrice ? '' : '手填'" @change="syncFloat(row, 'pct')" />
              </template>
            </el-table-column>
            <!-- 盈亏额只读：股数×现价 − 股数×成本。没填股数就是「—」，不拿股价差凑数。 -->
            <el-table-column label="盈亏" width="104" align="right">
              <template #default="{ row }">
                <span v-if="pnlOf(row)" :class="pctClass(pnlOf(row).amount)">{{ yuan(pnlOf(row).amount) }}</span>
                <span v-else class="dim-muted">—</span>
                <span v-if="pnlOf(row) && ledgerSummary.marketValue" class="dim-muted" style="display: block">
                  占比 {{ (pnlOf(row).value / ledgerSummary.marketValue * 100).toFixed(1) }}%
                </span>
              </template>
            </el-table-column>
            <el-table-column label="今日动作" width="90">
              <template #default="{ row }"><el-input v-model="row.action" size="small" placeholder="今日" /></template>
            </el-table-column>
            <el-table-column label="应做" width="90">
              <template #default="{ row }"><el-input v-model="row.plannedAction" size="small" placeholder="计划" /></template>
            </el-table-column>
            <el-table-column label="纪律" width="84">
              <template #default="{ row }">
                <el-select v-model="row.discipline" size="small" clearable placeholder="未填">
                  <el-option v-for="d in DISCIPLINES" :key="d" :label="d" :value="d" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="延迟天" width="64">
              <template #default="{ row }">
                <el-input-number v-model="row.delayDays" size="small" :min="0" :controls="false" style="width: 100%"
                  :disabled="row.status !== '今日清仓'" :placeholder="row.status !== '今日清仓' ? '清仓' : ''" />
              </template>
            </el-table-column>
            <el-table-column label="纪律分" width="66">
              <template #default="{ row }">
                <el-input-number v-model="row.disciplineScore" size="small" :min="0" :max="100" :controls="false"
                  style="width: 100%" :disabled="row.status !== '今日清仓'" :placeholder="row.status !== '今日清仓' ? '清仓' : ''" />
              </template>
            </el-table-column>
            <el-table-column label="次日决策" min-width="160">
              <template #default="{ row }">
                <div class="plan-cell">
                  <el-input v-model="row.nextDayPlan" size="small" placeholder="综合预案（可留空）" />
                  <span class="tier-flag" :class="tierCount(row) ? 'ok' : ''"
                    :title="'四档已填 ' + tierCount(row) + '/4，点行首箭头展开编辑'">{{ tierCount(row) }}/4</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="88">
              <template #default="{ row }">
                <el-select v-model="row.status" size="small" @change="onStatusChange(row)">
                  <el-option label="持仓中" value="持仓中" />
                  <el-option label="今日清仓" value="今日清仓" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="64" align="center">
              <template #default="{ row }">
                <el-button size="small" text type="danger" @click="removePos(row)">×</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </section>

    <!-- 批量带入：从昨日未平持仓 / 当日涨停池 / 阵眼勾选取数，省掉逐只手搜代码 -->
    <el-dialog v-model="openImport" title="批量带入持仓" width="600px">
      <el-radio-group v-model="importSource" size="small" style="margin-bottom: 10px">
        <el-radio-button value="carry">昨日未平持仓</el-radio-button>
        <el-radio-button value="pool">当日涨停池</el-radio-button>
        <el-radio-button value="anchor">阵眼</el-radio-button>
      </el-radio-group>
      <el-table :data="importCandidates" size="small" height="320"
        :empty-text="importLoading ? '加载中…' : '该来源当天没有可带入的标的'" @selection-change="onImportSelect">
        <el-table-column type="selection" width="42" />
        <el-table-column label="代码" prop="code" width="80" />
        <el-table-column label="名称" prop="name" min-width="110" />
        <el-table-column label="板块" prop="industry" width="96" />
        <el-table-column label="板数" prop="boardNum" width="60" />
        <el-table-column label="股数" prop="quantity" width="72" />
      </el-table>
      <template #footer>
        <span class="dim-muted" style="float: left">已选 {{ importSelected.length }} 只（已在台账里的会自动跳过）</span>
        <el-button @click="openImport = false">取消</el-button>
        <el-button type="primary" :disabled="!importSelected.length" @click="applyImport">带入</el-button>
      </template>
    </el-dialog>

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
import { ref, reactive, computed, nextTick, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { recordApi, importApi, reviewApi, prdApi, d5Api, marketApi, anchorsApi } from '../api/modules'
import { useTradingCalendar } from '../utils/tradingCalendar'
import { pnlOf, yuan } from '../utils/money'
import { ElMessage, ElMessageBox } from 'element-plus'
import EditableStatCard from '../components/EditableStatCard.vue'

// useRoute 必须在 setup 顶层取一次：放进普通函数会在 watch/生命周期回调里 inject 失败（返回 undefined 抛 TypeError）
const route = useRoute()

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

/* 可复盘交易日集合（降序，[0]=最近交易日）：共享工具统一「周末+官方休市日」置灰 */
const { tradingDays, loadTradingDays, disabledDate, cellClass, isNonTrading, fallbackRecentTradingDay } = useTradingCalendar()
function initTradingDay() {
  const q = route.query?.date
  // 本地归一：交易日集合在手→必须落到集合内(不在则取最近交易日)；
  // 集合未就绪(接口慢/挂起/失败)→先兜底到最近一个工作日，避免默认值停在“今天”的休市日。
  const normalize = (d) => {
    if (!d) return d
    if (tradingDays.value.length) return tradingDays.value.includes(d) ? d : tradingDays.value[0]
    return fallbackRecentTradingDay(d)
  }
  // 同步设默认值：绝不 await 异步交易日历（否则接口慢/挂起时默认会停在今天的休市日）。
  if (q) {
    const norm = normalize(q)
    if (norm) form.tradeDate = norm
  } else {
    form.tradeDate = normalize(form.tradeDate) || form.tradeDate
  }
  // 异步加载完成后，用真实交易日集合精确回校（集合已就绪时随即便命中）。
  loadTradingDays().then(() => {
    if (!tradingDays.value.length) return
    if (!tradingDays.value.includes(form.tradeDate)) {
      form.tradeDate = tradingDays.value[0]   // 触发下方 watch → handleDateChange
    }
  })
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
const posTab = ref('hold')
const savingPos = ref(false)
/* 外溢②：昨日遗留决策带出 */
const pendingCarry = ref([])
const pendingCarryDate = ref('')
const pendingAct = ref({})
const markingPend = ref(false)

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
  // 仪表盘「待裁决 → 去处理」跳来时带着 to=ledger：等台账行渲染完直接滚到持仓台账，不停页面顶部
  const wantLedger = route.query?.to === 'ledger'
  dashboard.value = null
  fetchTasks.value = []
  fetchOverall.value = ''
  hasRun.value = false
  loadRecord(date)
  loadDashboard(date)
  loadReuse(date)
  loadPositions(date).then(() => {
    if (wantLedger) nextTick(() => setTimeout(scrollToLedger, 100))
  }).catch(() => {})
  loadPendingCarry(date)
  loadPoolIndex(date)   // 台账选股时自动带出板块/板数的索引
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
  if (isNonTrading(date)) { ElMessage.warning('请选择交易日，非交易日不可拉取'); return }
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
    autoScore(date)             // 当天还没有复盘记录时，用拉到的客观数据自动算分落库，让顶部五维评分回显
    ElMessage.success('拉取完成，五维数据已刷新')
  } catch (e) {
    fetchOverall.value = 'FAILED'
  } finally {
    fetching.value = false
  }
}

/**
 * 拉取只落原始数据；但顶部「五维评分」读的是已保存记录的派生列。
 * 拉取完成后用当天客观指标重算落库，让分数/温度/阶段拉完即可见。
 * body 只带 tradeDate：createOrUpdate 只重算派生列，不覆盖任何人手填的字段，
 * 所以即便当天已有记录（用户手填到一半）也安全，语义等价于「重新落一版」。
 */
async function autoScore(date) {
  if (!date) return
  try {
    const res = await reviewApi.save({ tradeDate: date })
    savedRecord.value = res.data
    recordId.value = res.data?.id || null
    formReady.value = true
    await loadDashboard(date)
  } catch (e) { /* 自动落库失败不阻塞拉取流程 */ }
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

/* ======================================================================= */
/* 持仓台账                                                                */
/* ======================================================================= */

/**
 * 落库字段白名单：脏检查与提交都按它走，两边必须一致。
 * _results/_loading/id 是纯前端态，不参与指纹，否则「搜索了一下」就会被判成脏。
 */
const POS_KEYS = ['stockCode', 'stockName', 'costPrice', 'currentPrice', 'quantity', 'floatPct', 'action',
  'plannedAction', 'discipline', 'industry', 'boardNum', 'status', 'delayDays', 'disciplineScore',
  'nextDayPlan', 'planOpen', 'planBreak', 'planLow', 'planFall', 'executed', 'actualAction']

function blankRow(status = '持仓中') {
  return {
    id: null, stockCode: '', stockName: '', costPrice: null, currentPrice: null, quantity: null,
    floatPct: null,
    action: '', plannedAction: '', discipline: '', industry: '', boardNum: null, status,
    delayDays: null, disciplineScore: null, nextDayPlan: '',
    planOpen: '', planBreak: '', planLow: '', planFall: '',
    executed: 0, actualAction: '', _results: [], _loading: false
  }
}
function toPayload(rows) {
  return rows.map((r) => {
    const o = {}
    POS_KEYS.forEach(k => { o[k] = r[k] === undefined ? null : r[k] })
    return o
  })
}
/** 可落库字段的指纹串：这是「脏不脏」的唯一判据 */
function posFingerprint() { return JSON.stringify(toPayload(posRows.value)) }

const posSnapshot = ref('[]')
const posDirty = computed(() => posFingerprint() !== posSnapshot.value)

/* ---- 撤销：保存/删行都不再拦确认弹窗，改成事后可撤销 ---- */
const undoTip = ref('')
let undoFn = null
let undoTimer = null
function pushUndo(tip, fn, ms = 10000) {
  undoFn = fn
  undoTip.value = tip
  if (undoTimer) clearTimeout(undoTimer)
  undoTimer = setTimeout(clearUndo, ms)
}
function clearUndo() {
  if (undoTimer) clearTimeout(undoTimer)
  undoTimer = null
  undoFn = null
  undoTip.value = ''
}
async function applyUndo() {
  const fn = undoFn
  clearUndo()
  if (fn) await fn()
}

function addPositionRow() {
  // 跟随当前视图：在「今日清仓」里加行就建清仓行，否则新行会跑去看不见的 Tab
  posRows.value.push(blankRow(posTab.value === 'cleared' ? '今日清仓' : '持仓中'))
}
function removePos(row) {
  const i = posRows.value.indexOf(row)
  if (i < 0) return
  const backup = posRows.value.slice()
  const label = row.stockName || row.stockCode || '空行'
  posRows.value.splice(i, 1)
  pushUndo('已移除 ' + label, () => { posRows.value = backup })
}
/** 延迟天/纪律分只对清仓行有意义：切回持仓时清掉，避免留下没人看的脏值 */
function onStatusChange(row) {
  if (row.status !== '今日清仓') { row.delayDays = null; row.disciplineScore = null }
}

function round2(v) { return Math.round(Number(v) * 100) / 100 }

/**
 * 成本 / 现价 / 浮动% 两两推导：填两个推第三个。
 * 以前 placeholder 写着「自动」却没有任何自动行为，同一条信息要手输两遍还可能自相矛盾。
 */
function syncFloat(row, from) {
  const num = (v) => (v === null || v === undefined || v === '' ? null : Number(v))
  const c = num(row.costPrice), p = num(row.currentPrice), f = num(row.floatPct)
  if (from === 'pct') {
    if (c && f !== null) { row.currentPrice = round2(c * (1 + f / 100)); return }
    if (p && f !== null && f !== -100) { row.costPrice = round2(p / (1 + f / 100)); return }
    return
  }
  if (c && p && c > 0) row.floatPct = round2((p - c) / c * 100)
}
function tierCount(row) {
  return [row.planOpen, row.planBreak, row.planLow, row.planFall].filter(x => (x || '').trim()).length
}

async function savePositions() {
  const date = form.tradeDate
  if (!date) return
  const keep = posRows.value.filter(r => (r.stockCode || '').trim())
  const dropped = posRows.value.length - keep.length
  if (dropped > 0) ElMessage.warning('有 ' + dropped + ' 行没填股票代码，不会保存')
  if (!keep.length) { ElMessage.warning('没有可保存的持仓行'); return }
  savingPos.value = true
  try {
    const before = posRows.value.slice()
    await recordApi.savePositions(date, toPayload(keep))
    // 整表替换前服务端已把旧行快照进 t_position_history，
    // 所以拦一道确认弹窗是多余的——改成保存后给一次撤销（撤销=原样再存一次）。
    pushUndo('台账已保存', () => restoreRows(before, date))
    ElMessage.success('持仓台账已存（旧行已自动快照）')
    await loadPositions(date)
    loadPendingCarry(date)
  } catch (e) { /* 拦截器已弹 */ } finally { savingPos.value = false }
}
async function restoreRows(rows, date) {
  try {
    await recordApi.savePositions(date, toPayload(rows.filter(r => (r.stockCode || '').trim())))
    await loadPositions(date)
    ElMessage.success('已撤销到保存前')
  } catch (e) { /* 拦截器已弹 */ }
}

/* 加载某日持仓台账回填编辑表；顺带把指纹存下来作为脏检查基线 */
async function loadPositions(date) {
  const res = await recordApi.getPositions(date).catch(() => null)
  if (form.tradeDate !== date) return
  posRows.value = (res?.data || []).map((r) => {
    const row = blankRow(r.status || '持仓中')
    POS_KEYS.forEach(k => { row[k] = r[k] === undefined || r[k] === null ? row[k] : r[k] })
    row.id = r.id || null
    return row
  })
  posSnapshot.value = posFingerprint()
  clearUndo()
}

const holdingRows = computed(() => posRows.value.filter((r) => !r.status || r.status === '持仓中'))
const clearedRows = computed(() => posRows.value.filter((r) => r.status === '今日清仓'))
/** Tab 只筛行，不换表：三种视图共用同一套列 */
const tableRows = computed(() =>
  posTab.value === 'hold' ? holdingRows.value
    : posTab.value === 'cleared' ? clearedRows.value
      : posRows.value)

/* 外溢②：加载昨日遗留决策（未执行、且日期在当前交易日之前/等于当天） */
async function loadPendingCarry(date) {
  if (!date) return
  const res = await recordApi.pendingPositions(date, 5).catch(() => null)
  if (form.tradeDate !== date) return
  const list = res?.data || []
  pendingCarry.value = list
  pendingCarryDate.value = list.length ? (list[0].tradeDate || '').slice(0, 10) : ''
  const acts = {}
  for (const p of list) acts[p.id] = p.actualAction || ''
  pendingAct.value = acts
}

/* 外溢闭环：标记执行，回填实际动作，关闭裁决 */
async function markExecuted(p) {
  markingPend.value = true
  try {
    await recordApi.markPositionExecuted(p.id, pendingAct.value[p.id] || '')
    ElMessage.success(`${p.stockName} 今日处理已记录，裁决关闭`)
    await loadPendingCarry(form.tradeDate)
    loadPositions(form.tradeDate).catch(() => {})
  } catch (e) { /* 拦截器弹 */ } finally { markingPend.value = false }
}

/* 股票远程搜索下拉 */
const searchStocks = async (q, row) => {
  if (!q || !q.trim()) { row._results = []; return }
  row._loading = true
  try {
    const d = await reviewApi.searchStocks(q.trim())
    row._results = (Array.isArray(d) ? d : (d?.data || [])).slice(0, 12)
  } catch (e) { row._results = [] } finally { row._loading = false }
}

/* 当日涨停池索引：code -> {industry, boardNum, name}，选中股票时用来自动带出板块与板数 */
const poolIndex = ref({})
async function loadPoolIndex(date) {
  poolIndex.value = {}
  if (!date) return
  const res = await marketApi.stocks(date).catch(() => null)
  const ladder = res?.data?.ladder
  if (!ladder) return
  const idx = {}
  for (const tier of ladder) {
    for (const s of (tier.stocks || [])) {
      if (!s.code) continue
      idx[s.code] = { industry: s.industry || '', boardNum: tier.board || null, name: s.name || '' }
    }
  }
  poolIndex.value = idx
}
function pickStock(row) {
  const hit = (row._results || []).find((s) => s.code === row.stockCode)
  if (hit) row.stockName = hit.name
  const p = poolIndex.value[row.stockCode]
  if (!p) return
  if (!row.stockName && p.name) row.stockName = p.name
  if (!row.industry && p.industry) row.industry = p.industry
  if (!row.boardNum && p.boardNum) row.boardNum = p.boardNum
}

/** 裸代码 → 腾讯 symbol：6 沪、4/8/9 北、其余深（与后端 TencentClient.symbolOf 同口径） */
function symbolOf(code) {
  if (!code || code.length !== 6) return null
  const h = code.charAt(0)
  if (h === '6') return 'sh' + code
  return (h === '4' || h === '8' || h === '9') ? 'bj' + code : 'sz' + code
}
/**
 * 补现价：只认 date 精确匹配那一根日 K。
 * 腾讯个股日 K 当天常滞后（16:00 拉仍只到 T-3），缺就留空——绝不用相邻交易日的收盘顶替。
 */
const fillingPrice = ref(false)
async function fillCurrentPrices() {
  const date = form.tradeDate
  const targets = posRows.value.filter(r => (r.stockCode || '').trim().length === 6 && r.currentPrice == null)
  if (!targets.length) { ElMessage.info('没有待补的行（需有 6 位代码且现价为空）'); return }
  fillingPrice.value = true
  let filled = 0
  let missed = 0
  try {
    for (const r of targets) {
      const res = await marketApi.dailyBars(symbolOf(r.stockCode), date, date).catch(() => null)
      const bar = (res?.data || []).find(b => (b.date || '').slice(0, 10) === date)
      if (bar && bar.close != null) { r.currentPrice = Number(bar.close); filled++ } else { missed++ }
      syncFloat(r, 'price')
    }
    if (filled && !missed) ElMessage.success('已补 ' + filled + ' 只现价')
    else if (filled) ElMessage.warning('已补 ' + filled + ' 只，' + missed + ' 只当日日 K 未取到（个股当天滞后，请手填）')
    else ElMessage.warning(missed + ' 只当日日 K 未取到（个股当天滞后，请手填）')
  } finally { fillingPrice.value = false }
}

/* ---- 批量带入：昨日未平持仓 / 当日涨停池 / 阵眼 ---- */
const openImport = ref(false)
const importSource = ref('carry')
const importLoading = ref(false)
const importCandidates = ref([])
const importSelected = ref([])
function onImportSelect(sel) { importSelected.value = sel }
async function openImportDialog() {
  openImport.value = true
  importSelected.value = []
  importCandidates.value = []
  await loadImportCandidates()
}
async function loadImportCandidates() {
  const date = form.tradeDate
  importLoading.value = true
  importCandidates.value = []
  try {
    if (importSource.value === 'carry') {
      const res = await recordApi.allPositions(30).catch(() => null)
      const all = res?.data || []
      // 最近一个早于当天的交易日里仍在持仓的票 = 昨日未平，今日大概率还要处理
      const days = [...new Set(all.map(r => (r.tradeDate || '').slice(0, 10)))]
        .filter(d => d && d < date).sort()
      const last = days[days.length - 1]
      const seen = {}
      const out = []
      for (const r of all) {
        if (!last || (r.tradeDate || '').slice(0, 10) !== last) continue
        if (r.status === '今日清仓' || !r.stockCode || seen[r.stockCode]) continue
        seen[r.stockCode] = 1
        out.push({ code: r.stockCode, name: r.stockName, industry: r.industry || '', boardNum: r.boardNum || null, quantity: r.quantity || null })
      }
      importCandidates.value = out
    } else if (importSource.value === 'pool') {
      const res = await marketApi.stocks(date).catch(() => null)
      const out = []
      for (const tier of (res?.data?.ladder || [])) {
        for (const s of (tier.stocks || [])) {
          out.push({ code: s.code, name: s.name, industry: s.industry || '', boardNum: tier.board || null })
        }
      }
      importCandidates.value = out
    } else {
      const res = await anchorsApi.config(date).catch(() => null)
      importCandidates.value = (res?.data || [])
        .filter(a => a.stockCode)
        .map(a => ({ code: a.stockCode, name: a.stockName || '', industry: '', boardNum: null }))
    }
  } finally { importLoading.value = false }
}
watch(importSource, () => { if (openImport.value) loadImportCandidates() })
function applyImport() {
  const inTable = new Set(posRows.value.map(r => r.stockCode))
  let n = 0
  for (const c of importSelected.value) {
    if (!c.code || inTable.has(c.code)) continue
    const row = blankRow('持仓中')
    row.stockCode = c.code
    row.stockName = c.name || ''
    row.industry = c.industry || ''
    row.boardNum = c.boardNum || null
    row.quantity = c.quantity || null
    posRows.value.push(row)
    inTable.add(c.code)
    n++
  }
  openImport.value = false
  ElMessage.success(n ? '带入 ' + n + ' 只' : '没有新增：选中的都已在台账里')
}

/**
 * 汇总：两条口径并存，各吃自己吃得下的行——
 * ① 金额口径（成本额/市值/盈亏额）：只收填了股数、成本现价都在的行；
 * ② 等权口径（平均浮动%/盈亏家数）：只看浮动%，与股数无关，缺股数的行仍在这里。
 * 所以「有股数 N/M」要一并显示出来，否则会以为盈亏额已经把全部持仓算进去了。
 */
const ledgerSummary = computed(() => {
  const list = posRows.value
  const floats = list.map(r => r.floatPct)
    .filter(v => v !== null && v !== undefined && v !== '')
    .map(Number).filter(n => !Number.isNaN(n))
  let costValue = 0
  let marketValue = 0
  let qtyCount = 0
  for (const r of list) {
    const m = pnlOf(r)
    if (!m) continue
    costValue += m.cost
    marketValue += m.value
    qtyCount++
  }
  const pnlAmount = qtyCount ? marketValue - costValue : null
  return {
    avgFloat: floats.length ? floats.reduce((a, b) => a + b, 0) / floats.length : null,
    priced: floats.length,
    winCount: floats.filter(n => n > 0).length,
    loseCount: floats.filter(n => n < 0).length,
    planCount: list.filter(r => tierCount(r) > 0 || (r.nextDayPlan || '').trim()).length,
    qtyCount,
    costValue: qtyCount ? costValue : null,
    marketValue: qtyCount ? marketValue : null,
    pnlAmount,
    pnlPct: pnlAmount !== null && costValue > 0 ? (pnlAmount / costValue) * 100 : null
  }
})


/* ======================================================================= */
/* 未保存护栏：切日期 / 关页面 / 离开路由 三处统一拦截                       */
/* ======================================================================= */
const ledgerRef = ref(null)
function scrollToLedger() {
  ledgerRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function onBeforeUnload(e) {
  if (!posDirty.value) return
  e.preventDefault()
  e.returnValue = ''
}
onBeforeRouteLeave(async () => {
  if (!posDirty.value) return true
  try {
    await ElMessageBox.confirm('持仓台账有未保存的改动，离开本页会丢失。', '台账未保存',
      { confirmButtonText: '放弃并离开', cancelButtonText: '留在本页', type: 'warning' })
    return true
  } catch (e) { return false }
})

/**
 * 日期选择的写入口：切日期会整表重读台账，所以有未保存改动时先拦一道。
 * 确认=保存后切换；取消=放弃改动并切换；关窗口=留在当天。
 *
 * 刻意不用 computed setter：取消切换时 form.tradeDate 没变、传给 picker 的 prop 也没变，
 * picker 不会回弹、界面会停在用户刚点的那个日期上。用独立 ref，取消时显式写回旧值才拉得回来。
 */
const pickerDate = ref(form.tradeDate)
watch(() => form.tradeDate, (v) => { if (pickerDate.value !== v) pickerDate.value = v })
watch(pickerDate, async (v) => {
  if (!v || v === form.tradeDate) return
  if (posDirty.value) {
    try {
      await ElMessageBox.confirm(
        `「${form.tradeDate}」的持仓台账有未保存改动，切换日期会丢掉它们（关闭本窗口 = 留在当天）。`,
        '台账未保存',
        {
          confirmButtonText: '保存后切换', cancelButtonText: '放弃改动并切换',
          distinguishCancelAndClose: true, type: 'warning'
        }
      )
      await savePositions()
    } catch (e) {
      if (e === 'close') { pickerDate.value = form.tradeDate; return }   // 关窗口 = 取消切换
    }
  }
  form.tradeDate = v
})

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
  window.addEventListener('beforeunload', onBeforeUnload)
  await initTradingDay()
  handleDateChange(form.tradeDate)
})
onUnmounted(() => window.removeEventListener('beforeunload', onBeforeUnload))
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
.fp-help { display: inline-flex; align-items: center; justify-content: center; width: 16px; height: 16px; margin-left: 4px; border-radius: 50%; background: #2a3b4d; color: #67e8f9; font-size: 11px; font-weight: 700; line-height: 1; cursor: help; vertical-align: middle; }
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
.ledger-summary { display: flex; flex-wrap: wrap; gap: 16px 22px; margin-bottom: 10px; padding: 9px 12px; background: #121c2a; border: 1px solid #22303f; border-radius: 8px; font-size: 13px; color: #c7d3df; }
.ledger-summary b { color: #fff; font-weight: 600; }
.ledger-summary .up { color: #e3534f; }
.ledger-summary .down { color: #22c55e; }
.ledger .card-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.ebb-chip { display: flex; gap: 8px; align-items: center; padding: 9px 12px; border-radius: 8px; background: rgba(239, 68, 68, .16); border: 1px solid #ef4444; color: #fecaca; font-size: 12.5px; margin-bottom: 12px; }
.panel-warn { color: #d97706; font-size: 12px; margin: 8px 0; line-height: 1.5; }
.pend-block { background: linear-gradient(135deg, rgba(245,158,11,.12), rgba(245,158,11,.04)); border: 1px solid #b45309; border-radius: 12px; padding: 12px 14px; margin-bottom: 16px; }
.pend-head { color: #fbbf24; font-size: 13.5px; font-weight: 700; margin-bottom: 8px; }
.pend-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; border-top: 1px dashed rgba(180,83,9,.35); flex-wrap: wrap; }
.pend-row:first-of-type { border-top: none; }
.pend-stock { color: #fff; font-size: 13px; min-width: 140px; }
.pend-board { color: #f59e0b; font-size: 12px; margin-left: 6px; }
.pend-ind { color: #94a3b8; font-size: 12px; margin-left: 6px; }
.pend-plan { display: flex; gap: 10px; flex-wrap: wrap; flex: 1; }
.pend-tier { background: #1c2a3a; color: #e2e8f0; font-size: 12px; padding: 2px 8px; border-radius: 6px; border: 1px solid #2a3b4f; }
.pend-tip { color: #94a3b8; font-size: 12px; }
.pend-act { display: flex; gap: 8px; align-items: center; }
.pend-done { color: #22c55e; font-size: 12px; margin-top: 8px; }
.plan-tiers { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.pt-label { color: #94a3b8; font-size: 12px; }
.pt-cell { display: inline-flex; align-items: center; gap: 4px; color: #cbd5e0; font-size: 12px; }
.pt-cell .el-input { width: 108px; }
.pos-industry { color: #94a3b8; font-size: 12px; margin-left: 8px; }
.pos-board { color: #f59e0b; font-size: 12px; margin-left: 6px; }

/* ---- 持仓台账（P0/P1/P2 改版） ---- */
/* dim-muted 之前只有类名没有规则（全项目就本页在用），这里补成真正的弱化文字 */
.dim-muted { color: #7a8b9c; font-size: 12px; }
.dirty-tag { margin-left: 8px; font-size: 12px; color: #ef9f27; }
.undo-chip { display: inline-flex; align-items: center; gap: 2px; margin-left: 12px; padding: 2px 4px 2px 10px;
  background: #16202e; border: 1px solid #2d3748; border-radius: 12px; font-size: 12px; color: #9fb3c8; }
.pend-jump { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px;
  padding: 9px 14px; background: #1d1a12; border: 1px solid #4a3a12; border-radius: 10px; }
.pend-jump-tag { font-size: 13px; color: #ef9f27; font-weight: 500; }
.plan-cell { display: flex; align-items: center; gap: 6px; }
.tier-flag { flex: none; padding: 1px 6px; border-radius: 8px; font-size: 11px; cursor: help;
  background: #22303f; color: #7a8b9c; border: 1px solid #2d3748; }
.tier-flag.ok { background: #123324; color: #4ecb8f; border-color: #1f5c3a; }
.plan-foot { margin-top: 8px; font-size: 12px; color: #7a8b9c; display: flex; gap: 10px; align-items: center; }
.exec-done { color: #4ecb8f; }
</style>