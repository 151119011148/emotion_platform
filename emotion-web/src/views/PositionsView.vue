<template>
  <div class="positions-page">
    <div class="page-header">
      <div>
        <h2>持仓与台账</h2>
        <div class="page-sub">本页可就地改快照日的了结与纪律 · 代码/成本/现价与次日四档仍在每日复盘页录 · 快照日 {{ latestDate || '—' }}</div>
      </div>
      <el-button :loading="loading" @click="loadAll">刷新</el-button>
    </div>

    <!-- 顶部页签：切换四类视图 -->
    <div class="tabs">
      <div v-for="t in TABS" :key="t.key" class="tab" :class="{ on: tab === t.key }" @click="tab = t.key">{{ t.label }}</div>
    </div>

    <!-- 行内编辑的落点：改动先攒在本地，点保存才 PUT；不点就什么都没发生。
         写口是「整日替换」，所以这一天的每一行都会原样带上——包括没动的行。 -->
    <div class="edit-bar" v-if="dirtyCount">
      <b>{{ dirtyCount }} 行有改动</b>
      <span class="mini" v-if="pendingMoves.length">清仓按今天记账：{{ pendingMoves.length }} 行将在今天（{{ localToday() }}）建清仓行，{{ latestDate }} 快照恢复持仓中</span>
      <span class="mini" v-else>将整日重写 {{ latestDate }} 的 {{ todayRows.length }} 行持仓（旧行服务端自动快照进历史表）</span>
      <el-button size="small" type="primary" plain :loading="saving" @click="saveEdits">保存</el-button>
      <el-button size="small" text @click="edits.clear()">放弃</el-button>
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
        <div class="x">{{ weekExits.length }} 笔了结 · {{ weekExitsReal.length }} 笔有成交价</div>
      </div>
      <div class="card">
        <div class="k">平均纪律分</div>
        <div class="v" :class="scoreClass(weekAvgScore)">{{ weekAvgScore == null ? '—' : weekAvgScore }}</div>
        <div class="x">近7日 {{ weekRows.length }} 行快照</div>
      </div>
      <div class="card">
        <div class="k">近7日胜率</div>
        <div class="v">{{ weekWinRate == null ? '—' : weekWinRate + '%' }}</div>
        <div class="x">{{ weekExits.length }} 笔：{{ weekWins }} 盈 {{ weekExits.length - weekWins }} 亏</div>
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
        <div class="note">现价自动取行情表 · 卖价/卖出量/状态/纪律可就地改 · 状态改「今日清仓」＝按今天真实清仓日记账（快照日早于今天时清仓行自动搬到今天）；更正历史某天请回那天的复盘页</div>
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
          <el-table-column label="股数" width="86">
            <template #default="{ row }">
              <el-input-number :model-value="fieldOf(row, 'quantity')" size="small" :min="1" :step="100" :controls="false"
                style="width: 100%" placeholder="股数" @change="(v) => setField(row, 'quantity', v)" />
            </template>
          </el-table-column>
          <!-- 卖价是真卖出去那一笔的成交价，「现价」那列是行情给的收盘价——两列不是一回事，所以行内只改前者 -->
          <el-table-column label="卖价" width="92">
            <template #default="{ row }">
              <el-input-number :model-value="fieldOf(row, 'sellPrice')" size="small" :min="0" :precision="2" :controls="false"
                style="width: 100%" :placeholder="row.currentPrice ? String(row.currentPrice) : '成交'"
                @change="(v) => setField(row, 'sellPrice', v)" />
              <div v-if="realizedOf(view(row))" class="mini" :class="pctClass(realizedOf(view(row)).pct)">
                已实现 {{ fmtPct(realizedOf(view(row)).pct) }}<template v-if="realizedOf(view(row)).amount !== null"> · {{ yuan(realizedOf(view(row)).amount) }}</template>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="卖出量" width="88">
            <template #default="{ row }">
              <el-input-number :model-value="fieldOf(row, 'sellQty')" size="small" :min="1" :step="100" :controls="false"
                style="width: 100%" :placeholder="fieldOf(row, 'status') === '今日清仓' && row.quantity ? String(row.quantity) : '减仓'"
                @change="(v) => onSellQtyChange(row, v)" />
            </template>
          </el-table-column>
          <el-table-column label="浮动" width="86">
            <template #default="{ row }">
              <span :class="pctClass(row.floatPct)">{{ fmtPct(row.floatPct) }}</span>
              <div v-if="pnlOf(row)" class="mini" :class="pctClass(pnlOf(row).amount)">{{ yuan(pnlOf(row).amount) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="112">
            <template #default="{ row }">
              <el-select :model-value="fieldOf(row, 'status')" size="small" @change="(v) => onStatusChange(row, v)">
                <el-option label="持仓中" value="持仓中" />
                <el-option label="今日清仓" value="今日清仓" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="纪律" width="118">
            <template #default="{ row }">
              <div class="disc-cell">
                <span class="disc-dot" :class="discClass(fieldOf(row, 'discipline'))"></span>
                <el-select :model-value="fieldOf(row, 'discipline')" size="small" clearable placeholder="未填"
                  @change="(v) => setField(row, 'discipline', v)">
                  <el-option v-for="d in DISCIPLINES" :key="d" :label="d" :value="d" />
                </el-select>
              </div>
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
      <div class="sec-hd"><h2>📋 今日清仓 / 交易流水</h2><div class="note">{{ latestDate || '—' }} · 盈亏列有成交价按已实现算，没有则退回手记浮动%</div></div>
      <div class="sec-bd">
        <el-table :data="closedRows" style="width: 100%" empty-text="快照日无清仓记录">
          <el-table-column label="名称" min-width="100">
            <template #default="{ row }"><b>{{ row.stockName }}</b></template>
          </el-table-column>
          <el-table-column label="动作" min-width="110">
            <template #default="{ row }">
              <el-input :model-value="fieldOf(row, 'action')" size="small" placeholder="今日动作"
                @change="(v) => setField(row, 'action', v)" />
            </template>
          </el-table-column>
          <el-table-column label="板数" width="70">
            <template #default="{ row }">{{ row.boardNum ? (row.boardNum > 1 ? row.boardNum + '板' : '首板') : '—' }}</template>
          </el-table-column>
          <el-table-column label="股数" width="86">
            <template #default="{ row }">
              <el-input-number :model-value="fieldOf(row, 'quantity')" size="small" :min="1" :step="100" :controls="false"
                style="width: 100%" placeholder="股数" @change="(v) => setField(row, 'quantity', v)" />
            </template>
          </el-table-column>
          <el-table-column label="卖价" width="92">
            <template #default="{ row }">
              <el-input-number :model-value="fieldOf(row, 'sellPrice')" size="small" :min="0" :precision="2" :controls="false"
                style="width: 100%" :placeholder="row.currentPrice ? String(row.currentPrice) : '成交'"
                @change="(v) => setField(row, 'sellPrice', v)" />
              <!-- 没填成交价就是没填：不拿收盘价（现价那列）冒充，只在旁边说明缺的是什么 -->
              <div v-if="fieldOf(row, 'sellPrice') == null && row.currentPrice" class="mini">收盘 {{ row.currentPrice }}</div>
            </template>
          </el-table-column>
          <el-table-column label="卖出量" width="88">
            <template #default="{ row }">
              <el-input-number :model-value="fieldOf(row, 'sellQty')" size="small" :min="1" :step="100" :controls="false"
                style="width: 100%" :placeholder="row.quantity ? String(row.quantity) : '减仓'"
                @change="(v) => onSellQtyChange(row, v)" />
            </template>
          </el-table-column>
          <el-table-column label="盈亏" width="96">
            <template #default="{ row }">
              <template v-if="realizedOf(view(row))">
                <span :class="pctClass(realizedOf(view(row)).pct)">{{ fmtPct(realizedOf(view(row)).pct) }}</span>
                <div v-if="realizedOf(view(row)).amount !== null" class="mini" :class="pctClass(realizedOf(view(row)).amount)">{{ yuan(realizedOf(view(row)).amount) }}</div>
                <div v-else class="mini">缺卖出量</div>
              </template>
              <template v-else>
                <span :class="pctClass(row.floatPct)">{{ fmtPct(row.floatPct) }}</span>
                <div v-if="pnlOf(row)" class="mini" :class="pctClass(pnlOf(row).amount)">{{ yuan(pnlOf(row).amount) }}</div>
                <div v-else class="mini">手记浮动 · 无成交价</div>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="应做" min-width="110">
            <template #default="{ row }">
              <el-input :model-value="fieldOf(row, 'plannedAction')" size="small" placeholder="计划"
                @change="(v) => setField(row, 'plannedAction', v)" />
            </template>
          </el-table-column>
          <el-table-column label="状态" width="112">
            <template #default="{ row }">
              <el-select :model-value="fieldOf(row, 'status')" size="small" @change="(v) => onStatusChange(row, v)">
                <el-option label="持仓中" value="持仓中" />
                <el-option label="今日清仓" value="今日清仓" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="延迟" width="80">
            <template #default="{ row }">
              <el-input-number :model-value="fieldOf(row, 'delayDays')" size="small" :min="0" :controls="false" style="width: 100%"
                :disabled="view(row).status !== '今日清仓'" :placeholder="view(row).status !== '今日清仓' ? '清仓' : ''"
                @change="(v) => setField(row, 'delayDays', v)" />
            </template>
          </el-table-column>
          <el-table-column label="纪律分" width="86">
            <template #default="{ row }">
              <el-input-number :model-value="fieldOf(row, 'disciplineScore')" size="small" :min="0" :max="100" :controls="false"
                style="width: 100%" :disabled="view(row).status !== '今日清仓'"
                :placeholder="view(row).status !== '今日清仓' ? '清仓' : ''" @change="(v) => setField(row, 'disciplineScore', v)" />
            </template>
          </el-table-column>
          <el-table-column label="评价" width="118">
            <template #default="{ row }">
              <div class="disc-cell">
                <span class="disc-dot" :class="discClass(fieldOf(row, 'discipline'))"></span>
                <el-select :model-value="fieldOf(row, 'discipline')" size="small" clearable placeholder="未填"
                  @change="(v) => setField(row, 'discipline', v)">
                  <el-option v-for="d in DISCIPLINES" :key="d" :label="d" :value="d" />
                </el-select>
              </div>
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
                <div class="dec-cards" style="margin-top: 10px; grid-template-columns: repeat(5, 1fr)">
                  <div class="dec-i"><div class="c">快照数</div><div class="a">{{ row.timeline.length }}</div></div>
                  <div class="dec-i"><div class="c">最新浮动</div><div class="a" :class="pctClass(row.lastFloat)">{{ fmtPct(row.lastFloat) }}</div></div>
                  <div class="dec-i"><div class="c">已实现</div><div class="a" :class="pctClass(row.realizedAmount)">{{ row.realizedAmount === null ? (row.exitCount ? '缺成本/量' : '—') : yuan(row.realizedAmount) }}</div></div>
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
          <el-table-column label="卖出日" width="108">
            <template #default="{ row }">{{ sellDateText(row) }}</template>
          </el-table-column>
          <el-table-column label="卖价" width="96">
            <template #default="{ row }">
              <span v-if="row.lastExit" class="n">{{ row.lastExit.sellPrice }}</span>
              <span v-if="row.lastExit" class="mini"> × {{ qtyText(row.lastExit.sellQty) }}</span>
              <div v-if="row.exitCount" class="mini">{{ row.exitCount }} 笔了结</div>
              <span v-else class="mini">—</span>
            </template>
          </el-table-column>
          <el-table-column label="板数" width="70">
            <template #default="{ row }">{{ row.boardNum ? (row.boardNum > 1 ? row.boardNum + '板' : '首板') : '—' }}</template>
          </el-table-column>
          <el-table-column label="盈亏" width="96">
            <template #default="{ row }">
              <span :class="pctClass(row.lastFloat)">{{ fmtPct(row.lastFloat) }}</span>
              <div v-if="row.realizedAmount !== null" class="mini" :class="pctClass(row.realizedAmount)">
                已实现 {{ yuan(row.realizedAmount) }}
              </div>
              <div v-else-if="row.exitCount" class="mini">成交价缺量/缺成本</div>
            </template>
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
          <el-table-column label="操作" width="96">
            <template #default="{ row }">
              <el-button v-if="row.status === '持仓'" size="small" text type="primary" @click="openClearDialog(row)">编辑卖出</el-button>
              <span v-else class="mini">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 个股「编辑卖出」：向所选卖出日追加一条今日清仓行（整日替换 API），修正生命周期状态并计盈亏 -->
    <el-dialog v-model="clearDlg.open" :title="`编辑卖出 · ${clearDlg.target?.stockName || ''} ${clearDlg.target?.stockCode || ''}`" width="420px">
      <el-form label-position="top">
        <el-form-item label="卖出日">
          <el-date-picker v-model="clearDlg.sellDate" type="date" value-format="YYYY-MM-DD" placeholder="选择卖出日" style="width: 100%" />
        </el-form-item>
        <el-form-item label="卖价">
          <el-input-number v-model="clearDlg.sellPrice" :min="0" :precision="2" :controls="false" placeholder="成交价" style="width: 100%" />
        </el-form-item>
        <el-form-item label="卖出量">
          <el-input-number v-model="clearDlg.sellQty" :min="1" :step="100" :controls="false" placeholder="默认全部股数" style="width: 100%" />
        </el-form-item>
        <div class="mini">卖出量 ≥ 股数即视为清仓；保存后该票在生命周期显示「已清」并计盈亏。原买入日快照保持不动。</div>
      </el-form>
      <template #footer>
        <el-button @click="clearDlg.open = false">取消</el-button>
        <el-button type="primary" :loading="clearDlg.saving" @click="saveClear">保存</el-button>
      </template>
    </el-dialog>

    <!-- ⑤ 卖出流水：一笔「了结」一行，减仓也算。这是「哪天卖的、卖多少钱」唯一的凭据。 -->
    <div class="sec" v-if="tab !== 'stats' && exitRows.length">
      <div class="sec-hd">
        <h2>💸 卖出流水（{{ exitRows.length }} 笔）</h2>
        <div class="note">近 {{ STAT_DAYS }} 日内 {{ exitPriced }} 笔有成交价 · 其余只知清仓不知价格</div>
      </div>
      <div class="sec-bd">
        <el-table :data="exitRows" style="width: 100%" size="small" empty-text="没有卖出记录；在每日复盘页的持仓台账里填「卖价」后这里自动长出来">
          <el-table-column prop="tradeDate" label="卖出日" width="100" />
          <el-table-column label="标的" min-width="120">
            <template #default="{ row }">
              <div class="sn"><b>{{ row.stockName }}</b></div>
              <div class="mini">{{ row.stockCode }} · 买入 {{ row.buyDate }}</div>
            </template>
          </el-table-column>
          <el-table-column label="性质" width="76">
            <template #default="{ row }">
              <el-tag :type="row.kind === '清仓' ? 'success' : 'warning'" size="small">{{ row.kind }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成本基准" width="88">
            <template #default="{ row }">{{ row.basis ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="卖价 × 量" width="112">
            <template #default="{ row }">
              <span v-if="row.sellPrice" class="n">{{ row.sellPrice }}</span>
              <span v-if="row.sellPrice" class="mini"> × {{ qtyText(row.sellQty) }}</span>
              <span v-else class="mini red">未填</span>
            </template>
          </el-table-column>
          <el-table-column label="已实现" width="128">
            <template #default="{ row }">
              <template v-if="row.real">
                <span :class="pctClass(row.real.pct)">{{ fmtPct(row.real.pct) }}</span>
                <div v-if="row.real.amount !== null" class="mini" :class="pctClass(row.real.amount)">{{ yuan(row.real.amount) }}</div>
                <div v-else class="mini">缺卖出量</div>
              </template>
              <div v-else class="mini">{{ row.sellPrice ? '缺成本' : '只知清仓' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="持有" width="70">
            <template #default="{ row }">{{ row.holdDays }}天</template>
          </el-table-column>
          <el-table-column label="当天动作" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.action || row.actualAction || '—' }}</template>
          </el-table-column>
        </el-table>
      </div>
      <div class="foot">口径：已实现 =（卖价 − 成本基准）× 卖出量。成本基准先用本行成本，没填则取同标的最近一条有成本的快照（清仓行常常只填股数）。缺成交价一律留空——现价是收盘价，不是成交价。</div>
    </div>

    <!-- ⑥ 纪律统计与认知沉淀 -->
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
      <div class="foot">口径说明：纪律分为每行快照的自评分均值；违约=该做没做（discipline=违约）；延迟=清仓距应做时点的天数；已实现只在填了<b>成交价</b>的行上计算，收盘价不算。</div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { recordApi } from '../api/modules'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTradingCalendar } from '../utils/tradingCalendar'
import { pnlOf, yuan, qtyText, realizedOf } from '../utils/money'

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

/* ---------------- 行内编辑：只改快照日这两张表 ---------------- */
/**
 * 必须与后端 PositionRequest 的字段一一对齐：保存是「整日替换」，
 * 少发一个键不是"这格不动"，而是把这一列在库里抹掉。
 */
const POS_KEYS = ['stockCode', 'stockName', 'costPrice', 'currentPrice', 'quantity', 'sellPrice', 'sellQty', 'floatPct', 'action',
  'plannedAction', 'discipline', 'industry', 'boardNum', 'status', 'delayDays', 'disciplineScore',
  'nextDayPlan', 'planOpen', 'planBreak', 'planLow', 'planFall', 'executed', 'actualAction']
const DISCIPLINES = ['遵守', '违约', '待执行']

/**
 * 改动叠在 rows 之上、按行 id 记账，不直接写 rows：
 * 生命周期/卖出流水读的是同一批对象，写脏了会在保存前就把没落库的数字摆上台面。
 */
const edits = reactive(new Map())
const saving = ref(false)
const dirtyCount = computed(() => edits.size)

function norm(v) {
  return v === undefined || v === null || v === '' ? null : v
}
/** 某一格的当前值（改动优先）。 */
function fieldOf(row, key) {
  const e = edits.get(row.id)
  return e && key in e ? e[key] : row[key]
}
/** 整行的当前视图，给已实现/浮动这类派生列用。 */
function view(row) {
  const e = edits.get(row.id)
  return e ? { ...row, ...e } : row
}
function setField(row, key, val) {
  const next = norm(val)
  const e = { ...(edits.get(row.id) || {}) }
  if (next === norm(row[key])) delete e[key]
  else e[key] = next
  if (Object.keys(e).length) edits.set(row.id, e)
  else edits.delete(row.id)
}
/** 与复盘页同规则：延迟天/纪律分只对清仓行有意义，改回持仓中就清掉，别留没人看的脏值。 */
function onStatusChange(row, val) {
  setField(row, 'status', val)
  if (norm(val) !== '今日清仓') {
    setField(row, 'delayDays', null)
    setField(row, 'disciplineScore', null)
  }
}
/** 卖出量 ≥ 股数 = 这一笔走干净了，自动切「今日清仓」，省得再手拉下拉框。 */
function onSellQtyChange(row, val) {
  setField(row, 'sellQty', val)
  const r = view(row)
  const q = Number(r.quantity) || 0
  const s = Number(r.sellQty) || 0
  if (q > 0 && s >= q) onStatusChange(row, '今日清仓')
  else if (r.status === '今日清仓' && s < q) onStatusChange(row, '持仓中')
}
function discClass(v) {
  return v === '遵守' ? 'grn' : v === '违约' ? 'red' : v === '待执行' ? 'yel' : ''
}

/** 本地日期（非 UTC）：toISOString 在 0-8 点会掉到昨天，这里按本地时区拼。 */
function localToday() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 一行转成整日替换的 payload：改动优先，缺省补 null（少一个键＝那一列被抹掉）。 */
function payloadOf(r) {
  const o = {}
  POS_KEYS.forEach(k => { o[k] = fieldOf(r, k) === undefined ? null : fieldOf(r, k) })
  return o
}

/** 清仓发生时的属性格：状态翻「今日清仓」时它们跟着清仓行搬到今天，不留在历史快照上。 */
const CLEAR_KEYS = ['sellPrice', 'sellQty', 'delayDays', 'disciplineScore']

/** 「今天点的清仓」：快照日早于今天、状态被改成今日清仓的行——保存时清仓会记到今天。 */
const pendingMoves = computed(() => todayRows.value.filter(r => {
  const e = edits.get(r.id)
  return !!e && e.status === '今日清仓' && r.tradeDate < localToday()
}))

async function saveEdits() {
  const date = latestDate.value
  if (!date || !edits.size) return
  const today = localToday()
  saving.value = true
  try {
    if (!pendingMoves.value.length) {
      const payload = todayRows.value.map(payloadOf)
      await recordApi.savePositions(date, payload)
      edits.clear()
      ElMessage.success(`已整日重写 ${date} 的 ${payload.length} 行（旧行已自动快照）`)
    } else {
      const movedIds = new Set(pendingMoves.value.map(r => r.id))
      // 旧快照日：被搬走的行恢复「持仓中」，清仓属性还原为原值；其余编辑照常保留
      const oldPayload = todayRows.value.map(r => {
        const o = {}
        POS_KEYS.forEach(k => {
          o[k] = movedIds.has(r.id) && CLEAR_KEYS.includes(k)
            ? (r[k] === undefined ? null : r[k])
            : (fieldOf(r, k) === undefined ? null : fieldOf(r, k))
        })
        if (movedIds.has(r.id)) o.status = '持仓中'
        return o
      })
      // 今天：已有行原样带上；搬过去的行按当前视图复制成今天的清仓行
      const newPayload = rows.value.filter(r => r.tradeDate === today).map(payloadOf)
      for (const r of pendingMoves.value) {
        const o = payloadOf(view(r))
        o.status = '今日清仓'
        const dup = newPayload.find(x => x.stockCode === r.stockCode)
        if (dup) Object.assign(dup, o)   // 今天已有该标的的行：并入，不重复建行
        else newPayload.push(o)
      }
      await recordApi.savePositions(date, oldPayload)
      await recordApi.savePositions(today, newPayload)
      edits.clear()
      ElMessage.success(`清仓已记到今天 ${today}（${pendingMoves.value.length} 行），${date} 快照恢复持仓中`)
    }
    await loadAll()
  } catch (e) { /* 拦截器已弹：改动留着，别让人重敲一遍 */ } finally { saving.value = false }
}

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
/**
 * 一笔了结的收益率：填了成交价就按 (卖价 − 成本基准) 算；没填就退回手记的浮动%，
 * 并标出它是退回来的（两个口径不一样，混在一起不吭声等于把「不知道」写成「知道」）。
 */
function exitPctOf(row) {
  const r = realizedOf(row)
  if (r) return { pct: r.pct, real: true }
  if (row.floatPct !== null && row.floatPct !== undefined && row.floatPct !== '') {
    return { pct: Number(row.floatPct), real: false }
  }
  return null
}

/** 近7日「了结」= 清仓行 ∪ 填了卖价的行（减仓也算），按 id 去重、按日期倒序。 */
const weekRows = computed(() => withinDays(rows.value, WEEK_DAYS))
const weekExits = computed(() => {
  const out = []
  const seen = new Set()
  for (const r of withinDays(rows.value, WEEK_DAYS)) {
    const priced = r.sellPrice !== null && r.sellPrice !== undefined && Number(r.sellPrice) > 0
    if (r.status !== '今日清仓' && !priced) continue
    if (seen.has(r.id)) continue
    seen.add(r.id)
    out.push(r)
  }
  return out.reverse()
})
const weekExitsReal = computed(() => weekExits.value.filter(r => realizedOf(r)))
const weekRealizedAvg = computed(() => avgOf(weekExits.value.map(r => exitPctOf(r)?.pct)))
const weekWins = computed(() => weekExits.value.filter(r => {
  const p = exitPctOf(r)
  return p && p.pct > 0
}).length)
const weekWinRate = computed(() => (weekExits.value.length
  ? Math.round((weekWins.value / weekExits.value.length) * 100) : null))
const weekAvgScore = computed(() => avgOf(withinDays(rows.value, WEEK_DAYS).map(r => r.disciplineScore)))

/**
 * 卖出流水（全窗口，不止近 7 日）：每笔了结带上买入日、成本基准与持有快照数，
 * 页面只管展示，算法全在 realizedOf 里——和复盘页同一套口径。
 */
const exitRows = computed(() => {
  const first = new Map()
  const datesByCode = new Map()
  for (const r of rows.value) {
    if (!first.has(r.stockCode)) first.set(r.stockCode, r.tradeDate)
    if (!datesByCode.has(r.stockCode)) datesByCode.set(r.stockCode, [])
    datesByCode.get(r.stockCode).push(r.tradeDate)
  }
  const out = []
  for (const r of [...rows.value].reverse()) {
    const priced = r.sellPrice !== null && r.sellPrice !== undefined && Number(r.sellPrice) > 0
    if (r.status !== '今日清仓' && !priced) continue
    const held = (datesByCode.get(r.stockCode) || []).filter(d => d <= r.tradeDate)
    out.push({
      ...r,
      kind: r.status === '今日清仓' ? '清仓' : '减仓',
      buyDate: first.get(r.stockCode) || r.tradeDate,
      basis: realizedOf(r)?.basis ?? null,
      real: realizedOf(r),
      holdDays: new Set(held).size
    })
  }
  return out
})
const exitPriced = computed(() => exitRows.value.filter(r => r.real).length)

/** 卖出日：清仓日优先；只减过仓的显示最后一次了结日并标「减」。 */
function sellDateText(row) {
  if (row.sellDate) return row.sellDate
  if (row.lastExit) return row.lastExit.tradeDate + ' 减'
  return '—'
}

/** 次日处理决策：快照日持仓中且有决策内容的行；四档有值用四档，否则整段 nextDayPlan 兜底。 */
const nextDayDecisions = computed(() =>
  holdingRows.value.filter(r => r.nextDayPlan || r.planOpen || r.planBreak || r.planLow || r.planFall))
const hasPlanColumns = computed(() => nextDayDecisions.value.some(r => r.planOpen || r.planBreak || r.planLow || r.planFall))

/**
 * 标的维度聚合：同一 stockCode 的各日快照串成一条生命周期。
 * 买入日=最早快照日，卖出日=最后一条"今日清仓"快照日（只减过仓的退到最后一笔成交价，标「减」）；
 * 持有天数=去重快照日数；已实现额=该标的各笔有成交价的卖出相加。
 */
const lifecycleRows = computed(() => {
  const map = new Map()
  for (const r of rows.value) {
    let g = map.get(r.stockCode)
    if (!g) {
      g = { stockCode: r.stockCode, stockName: r.stockName, industry: '', boardNum: null, buyDate: r.tradeDate, sellDate: '', exits: [], timeline: [], lastRow: r }
      map.set(r.stockCode, g)
    }
    if (r.tradeDate < g.buyDate) g.buyDate = r.tradeDate
    if (r.industry) g.industry = r.industry
    if (r.boardNum != null) g.boardNum = r.boardNum
    if (r.stockName) g.stockName = r.stockName
    if (r.status === '今日清仓' && r.tradeDate > g.sellDate) g.sellDate = r.tradeDate
    if (r.sellPrice !== null && r.sellPrice !== undefined && Number(r.sellPrice) > 0) g.exits.push(r)
    g.timeline.push(r)
    g.lastRow = r
  }
  return Array.from(map.values()).map(g => {
    const dates = new Set(g.timeline.map(t => t.tradeDate))
    const scores = g.timeline.map(t => t.disciplineScore).filter(v => v != null)
    const floats = g.timeline.map(t => t.floatPct).filter(v => v != null)
    const amounts = g.exits.map(t => realizedOf(t)?.amount).filter(v => v !== null && v !== undefined)
    return {
      ...g,
      holdDays: dates.size,
      lastExit: g.exits.length ? g.exits[g.exits.length - 1] : null,
      exitCount: g.exits.length,
      realizedAmount: amounts.length ? amounts.reduce((a, b) => a + Number(b), 0) : null,
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
/** 生命周期时间轴：把一行快照翻译成 事件/注脚。 */
function lifeEvent(t) {
  if (t.status === '今日清仓') return t.action || '清仓'
  // 状态还是持仓中但当天有成交价 = 走掉了一部分，这一笔以前只能糊在动作文本里
  if (t.sellPrice !== null && t.sellPrice !== undefined && Number(t.sellPrice) > 0) return t.action || '减仓'
  if (t.action) return t.action
  return '持有'
}
function lifeNote(t) {
  const parts = []
  if (t.industry) parts.push(t.industry)
  if (t.disciplineScore != null) parts.push('纪律 ' + t.disciplineScore)
  // 成交价与卖量优先于浮动：这一行如果做了了结，看的是真卖了多少
  if (t.sellPrice !== null && t.sellPrice !== undefined && Number(t.sellPrice) > 0) {
    parts.push('卖 ' + t.sellPrice + (t.sellQty ? '×' + t.sellQty : ''))
  }
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

/* ---------------- 个股「编辑卖出」弹窗 ---------------- */
/** 把一只还在「持仓」的票记一笔清仓：向所选卖出日追加一条今日清仓行。 */
const clearDlg = reactive({ open: false, saving: false, target: null, sellDate: '', sellPrice: null, sellQty: null })

function openClearDialog(row) {
  clearDlg.target = row
  clearDlg.sellDate = localToday()
  clearDlg.sellPrice = row.lastExit ? row.lastExit.sellPrice : null
  clearDlg.sellQty = row.lastRow && row.lastRow.quantity != null ? row.lastRow.quantity : null
  clearDlg.open = true
}

async function saveClear() {
  const { target, sellDate, sellPrice, sellQty } = clearDlg
  if (!target) return
  if (!sellDate) { ElMessage.warning('请选择卖出日'); return }
  const base = target.lastRow || {}
  const q = Number(base.quantity) || 0
  const s = Number(sellQty) || 0
  if (!s) { ElMessage.warning('请填写卖出量'); return }
  if (q > 0 && s > q) { ElMessage.warning('卖出量不能大于股数'); return }
  clearDlg.saving = true
  try {
    // 取回卖出日当天现有行，原样 round-trip（payloadOf 带全 POS_KEYS，少一个键＝那列被抹掉）
    const res = await recordApi.getPositions(sellDate)
    const existing = Array.isArray(res.data) ? res.data : []
    const arr = existing.map(payloadOf)
    const clear = {
      stockCode: target.stockCode,
      stockName: base.stockName || target.stockName,
      costPrice: base.costPrice ?? null,
      currentPrice: base.currentPrice ?? null,
      quantity: base.quantity ?? null,
      floatPct: base.floatPct ?? null,
      industry: base.industry ?? null,
      boardNum: base.boardNum ?? null,
      sellPrice: sellPrice ?? null,
      sellQty: sellQty ?? null,
      status: '今日清仓',
      executed: 0,
      actualAction: null
    }
    const full = {}
    POS_KEYS.forEach(k => { full[k] = clear[k] === undefined ? null : clear[k] })
    const i = arr.findIndex(r => r.stockCode === target.stockCode)
    if (i >= 0) arr[i] = { ...arr[i], ...full }   // 卖出日已有该标的行：并入，不重复建行
    else arr.push(full)
    await recordApi.savePositions(sellDate, arr)
    ElMessage.success(`「${target.stockName}」清仓已记到 ${sellDate}`)
    clearDlg.open = false
    await loadAll()
  } catch (e) { /* 拦截器已弹后端校验错；弹窗留着让人改 */ } finally { clearDlg.saving = false }
}

async function loadAll() {
  loading.value = true
  // 整日替换后行 id 会变，叠在旧 id 上的改动既渲染不出来也不会进 payload——先清掉，别留幽灵改动
  edits.clear()
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

/* 行内编辑的保存条：只在有未保存改动时占位，平时不挤版面 */
.edit-bar { display: flex; align-items: center; gap: 10px; background: rgba(59, 130, 246, 0.08); border: 1px solid rgba(59, 130, 246, 0.3); border-left: 3px solid #3b82f6; border-radius: 6px; padding: 8px 12px; margin-bottom: 14px; font-size: 12px; color: #c9d1d9; }
.edit-bar b { color: #58a6ff; white-space: nowrap; }
.edit-bar .mini { flex: 1; }

/* 纪律下拉前的色点：下拉框本身不带颜色语义，违约/遵守还是得一眼扫出来 */
.disc-cell { display: flex; align-items: center; gap: 5px; }
.disc-cell .el-select { flex: 1; }
.disc-dot { width: 7px; height: 7px; border-radius: 50%; background: #4b5866; flex-shrink: 0; }
.disc-dot.grn { background: #3fb950; }
.disc-dot.red { background: #f85149; }
.disc-dot.yel { background: #d29922; }

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
