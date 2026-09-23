<template>
  <div class="node-page">
    <div class="page-header">
      <h2>节点追踪</h2>
      <el-button type="primary" @click="openLadderCreate()">新增节点事件</el-button>
    </div>

    <!-- ② 节点演变路径（六态色带）：看活跃节点 D0 落在周期哪个位置，历史节点按状态标点 -->
    <div class="evolution" v-if="activeNode">
      <div class="evolution-title">节点演变路径</div>
      <div class="evolution-band">
        <div v-for="st in EVOLVE" :key="st.key" class="evo-seg"
             :class="{ active: stageKey(activeNode.d0Cycle) === st.key }"
             :style="{ background: st.color }">
          <span class="evo-label">{{ st.label }}</span>
        </div>
      </div>
      <div class="evolution-hint" v-if="activeNode.d0Cycle">
        活跃节点 D0（{{ activeNode.d0Date }}）落在 <b>{{ activeNode.d0Cycle }}</b>
        <template v-if="activeNode.d0Score != null">，当日情绪 {{ activeNode.d0Score }} 分</template>
      </div>
      <div class="evolution-hint" v-else>该节点未记 D0 情绪分，无法定位周期位置</div>
    </div>

    <!-- ⑤ 活跃节点卡片区（顶替空状态占位）：有活跃节点才出现 -->
    <div class="current-node" v-if="activeNode">
      <div class="current-head">
        <div class="current-tags">
          <el-tag :type="statusType(activeNode.status)" size="small">{{ activeNode.status }}</el-tag>
          <!-- 类型轴：五个具体 node_type；策略没识别出来显示「未识别」，不回落「普通节点」 -->
          <span class="ntag" :class="nodeTypeClass(activeNode)">{{ nodeTypeLabel(activeNode) }}</span>
          <el-tag v-if="activeNode.theme" size="small" type="info">{{ activeNode.theme }}</el-tag>
        </div>
        <span class="recalc">上次复算：{{ fmtTime(activeNode.lastRecalcAt) || '—' }}</span>
      </div>

      <div class="card-grid">
        <div class="card-cell">
          <span class="k">D0 日期</span>
          <span class="v">{{ activeNode.d0Date || '—' }}</span>
        </div>
        <div class="card-cell">
          <span class="k">锚定龙头</span>
          <span class="v">
            <template v-if="activeNode.anchorName">
              {{ activeNode.anchorName }}
              <el-tag size="small" type="success" class="role">{{ activeNode.anchorRoleLabel }}</el-tag>
              <span class="muted" v-if="activeNode.anchorEndDate == null">· 生效中</span>
            </template>
            <span v-else>{{ activeNode.anchorStock || '—' }}</span>
          </span>
        </div>
        <div class="card-cell">
          <span class="k">节点票（当前）</span>
          <span class="v">
            <span v-if="activeNode.nodeStock">{{ activeNode.nodeStock }}</span>
            <span v-else>—</span>
            <el-tag v-if="activeNode.nodeStockStatus" size="small"
              :type="onLadder(activeNode.nodeStockStatus) ? 'success' : 'danger'">
              {{ activeNode.nodeStockStatus }}
            </el-tag>
          </span>
        </div>
        <div class="card-cell">
          <span class="k">D0 情绪</span>
          <span class="v">
            <template v-if="activeNode.d0Score != null">{{ activeNode.d0Score }} 分</template>
            <template v-else>无读数</template>
            <span class="muted" v-if="activeNode.d0Cycle">· {{ activeNode.d0Cycle }}</span>
          </span>
        </div>
        <div class="card-cell">
          <span class="k">T+1 验证</span>
          <span class="v">
            <template v-if="activeNode.suggestion && activeNode.suggestion.ready">
              <el-tag :type="statusType(activeNode.suggestion.suggestedStatus)" size="small">
                建议 {{ activeNode.suggestion.suggestedStatus }}
              </el-tag>
              <span class="muted">（待采纳）</span>
            </template>
            <template v-else>明日待走完 / 判据未齐</template>
          </span>
        </div>
        <div class="card-cell">
          <span class="k">监管标记</span>
          <span class="v">
            <el-tag v-if="activeNode.surveillance" type="danger" size="small">⚠ 监管</el-tag>
            <span v-else>—</span>
          </span>
        </div>
      </div>
      <p class="surv-desc" v-if="activeNode.surveillanceDesc">{{ activeNode.surveillanceDesc }}</p>

      <div class="node-timeline">
        <div class="timeline-step" :class="{ active: true, observe: isObserveDay(activeNode) }">
          <div class="step-dot d0" :class="{ observe: isObserveDay(activeNode) }"></div>
          <div class="step-content">
            <span class="step-label">D0（断板日）</span>
            <span class="step-date">{{ activeNode.d0Date }}</span>
            <span class="step-desc">
              锚定龙头：{{ activeNode.anchorName || activeNode.anchorStock }}（{{ activeNode.anchorMaxBoard }}板）
            </span>
            <div class="candidates" v-if="parsedCandidates.length">
              <el-tag v-for="c in parsedCandidates" :key="c" size="small" type="info">{{ c }}</el-tag>
            </div>
            <!-- 破局日＝观察日：0 候选是规则本身，不是数据缺失，必须显式说清楚 -->
            <div class="observe-note" v-else-if="isObserveDay(activeNode)">◌ 观察日 · 不产候选</div>
          </div>
        </div>
        <div class="timeline-step" :class="{ active: activeNode.t1Date }">
          <div class="step-dot t1"></div>
          <div class="step-content">
            <span class="step-label">T+1（验证日）</span>
            <span class="step-date">{{ activeNode.t1Date || '待验证' }}</span>
            <template v-if="activeNode.t1Date">
              <span class="step-desc">
                老龙反包：{{ t1Repack(activeNode.t1AnchorRepack) }}
              </span>
              <span class="step-desc">
                晋级数量：{{ activeNode.t1PromotionCount }}只（{{ fmtRate(activeNode.t1PromotionRate) }}%）
              </span>
            </template>
          </div>
        </div>
        <div class="timeline-step" :class="{ active: activeNode.nodeStock }">
          <div class="step-dot t2"></div>
          <div class="step-content">
            <span class="step-label">确认</span>
            <span class="step-desc" v-if="activeNode.nodeStock">
              节点票：{{ activeNode.nodeStock }}
              <span v-if="activeNode.nodeStockMaxBoard">（最高{{ activeNode.nodeStockMaxBoard }}板）</span>
            </span>
            <span class="step-desc" v-else>待确认</span>
          </div>
        </div>
      </div>

      <NodeSuggestPanel :node="activeNode" @adopted="loadNodes" />
    </div>

    <!-- 无活跃节点：空状态 + 引导按钮，而不是一个孤立插图 -->
    <div v-else class="empty-box">
      <div class="empty-emoji">◌</div>
      <div class="empty-title">暂无追踪中的节点事件</div>
      <div class="empty-sub">系统里已有历史节点；从今日天梯新增一个节点，或先关联人工阵眼，追踪即从这里开始</div>
      <div class="empty-actions">
        <el-button type="primary" @click="goTianti">从今日天梯新增节点</el-button>
        <el-button @click="goHighEco">关联人工阵眼</el-button>
      </div>
    </div>

    <!-- ③ 历史节点表格（增强版） -->
    <div class="history-section" v-if="nodeList.length">
      <div class="history-head">
        <h3>历史节点</h3>
        <div class="filters">
          <el-select v-model="nodeTypeFilter" size="small" style="width: 130px" placeholder="节点类型">
            <el-option label="全部类型" value="" />
            <el-option v-for="t in NODE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            <el-option label="未识别" :value="NONE_KEY" />
          </el-select>
          <el-select v-model="statusFilter" size="small" style="width: 110px" placeholder="状态">
            <el-option label="全部" value="" />
            <el-option label="待验证" value="待验证" />
            <el-option label="有效" value="有效" />
            <el-option label="失效" value="失效" />
          </el-select>
          <el-date-picker v-model="dateRange" type="daterange" size="small" value-format="YYYY-MM-DD"
            :disabled-date="disabledDate" :cell-class-name="cellClass"
            start-placeholder="起始日期" end-placeholder="结束日期" style="width: 230px" />
          <el-button size="small" @click="resetFilters">重置</el-button>
        </div>
      </div>

      <el-table :data="filteredNodes" style="width: 100%">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-detail">
              <div class="detail-row">状态来路：{{ row.statusNote || '—' }}</div>
              <div class="detail-row" v-if="reasonTags(row).length">
                状态来路细分：
                <el-tag v-for="t in reasonTags(row)" :key="t" size="small"
                  :type="row.status === '失效' ? 'danger' : 'success'">{{ t }}</el-tag>
              </div>
              <div class="detail-row">T+1：{{ row.t1Date || '—' }} | 老龙反包 {{ t1Repack(row.t1AnchorRepack) }} | 晋级 {{ row.t1PromotionCount }}只（{{ fmtRate(row.t1PromotionRate) }}%）| 节点票最高 {{ row.nodeStockMaxBoard ? row.nodeStockMaxBoard + '板' : '—' }}</div>
              <div class="detail-row">前置过滤器：{{ filterLabel(row) }}</div>
              <div class="detail-row">D0情绪：{{ row.d0Score != null ? row.d0Score + '分' : '—' }} <template v-if="row.d0Cycle">· {{ row.d0Cycle }}</template> | 上次复算：{{ fmtTime(row.lastRecalcAt) || '—' }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="d0Date" label="D0日期" width="104" />
        <el-table-column label="节点类型" width="104">
          <template #default="{ row }">
            <span class="ntag" :class="nodeTypeClass(row)">{{ nodeTypeLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="锚定龙头" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="nc">
              <span>{{ row.anchorName || row.anchorStock || '—' }}</span>
              <el-tag size="small" type="success" v-if="row.anchorRoleLabel">{{ row.anchorRoleLabel }}</el-tag>
              <span class="nboard" v-if="row.anchorMaxBoard">{{ row.anchorMaxBoard }}板</span>
              <span class="nc-theme" v-if="row.theme">· {{ row.theme }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="节点票" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="node-stock">
              <div class="ns-name" :title="row.nodeStock || ''">{{ row.nodeStock || '—' }}</div>
              <div class="ns-meta" v-if="row.surveillance || row.nodeStockStatus">
                <el-tag v-if="row.surveillance" type="danger" size="small" class="ns-tag" title="SEVERE/EXCH 监管">⚠ 监管</el-tag>
                <el-tag v-if="row.nodeStockStatus" size="small" class="ns-tag"
                  :type="onLadder(row.nodeStockStatus) ? 'success' : 'danger'">{{ row.nodeStockStatus }}</el-tag>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="126">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
            <div class="reason-tags" v-if="reasonTags(row).length">
              <el-tag v-for="t in reasonTags(row)" :key="t" size="small"
                :type="row.status === '失效' ? 'danger' : 'success'" class="reason-tag">{{ reasonLabel(t) }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="D0情绪" width="62">
          <template #default="{ row }">
            <span v-if="row.d0Score != null">
              <el-tooltip :content="row.d0Cycle || ''" placement="top" :disabled="!row.d0Cycle">
                <span>{{ row.d0Score }}分</span>
              </el-tooltip>
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="120" fixed="right">
          <template #default="{ row }">
            <div class="nc">
              <el-button size="small" text type="primary" @click="openSuggest(row)">复算</el-button>
              <el-button size="small" text type="danger" @click="handleDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-empty v-else-if="!nodeList.length && !activeNode" description="还没有任何节点事件" />

    <el-dialog v-model="showSuggest" title="节点复算" width="780px" class="dark-node-dialog">
      <NodeSuggestPanel v-if="suggestNode" :node="suggestNode" @adopted="onAdopted" />
    </el-dialog>

    <el-dialog v-model="showNodeDialog" title="新增节点事件" width="600px" class="dark-node-dialog">
      <el-form :model="nodeForm" label-position="top">
        <el-form-item label="锚定龙头（从当日生效的人工阵眼里选；也可不关联手填）">
          <el-select v-model="nodeForm.anchorId" clearable filterable placeholder="选择阵眼，或留空手填下方名称"
            style="width: 100%" @change="onAnchorChange">
            <el-option v-for="a in anchorOptions" :key="a.id" :label="anchorLabel(a)" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!nodeForm.anchorId" label="锚定龙头（手填名称）">
          <el-input v-model="nodeForm.anchorStock" placeholder="龙头名称" />
        </el-form-item>
        <el-form-item label="锚定龙头最高板数">
          <el-input-number v-model="nodeForm.anchorMaxBoard" :min="1" :max="30" />
        </el-form-item>
        <el-form-item label="所属题材/板块（可选）">
          <el-input v-model="nodeForm.theme" placeholder="如：元件 / 华为链" />
        </el-form-item>
        <el-form-item label="D0 日期">
          <el-date-picker v-model="nodeForm.d0Date" type="date" value-format="YYYY-MM-DD"
            :disabled-date="disabledDate" :cell-class-name="cellClass"
            style="width: 100%" @change="loadAnchors" />
        </el-form-item>
        <el-form-item label="D0 候选票（逗号分隔）">
          <el-input v-model="nodeForm.candidatesStr" placeholder="如：宝鼎科技,百花医药" />
        </el-form-item>
        <el-form-item label="前置过滤器（D0 当天读数；留空＝未知，不判通过）">
          <el-row :gutter="12">
            <el-col :span="12">
              <div class="filter-field">
                <span class="filter-field-label">跌停家数（&lt;10 通过）</span>
                <el-input-number v-model="nodeForm.limitDownCount" :min="0" :max="9999"
                  :controls="false" placeholder="未知" style="width: 100%" />
              </div>
            </el-col>
            <el-col :span="12">
              <div class="filter-field">
                <span class="filter-field-label">涨停家数（&gt;60 通过）</span>
                <el-input-number v-model="nodeForm.limitUpCount" :min="0" :max="9999"
                  :controls="false" placeholder="未知" style="width: 100%" />
              </div>
            </el-col>
            <el-col :span="12">
              <el-checkbox v-model="nodeForm.filterNoKill">老龙非A杀</el-checkbox>
            </el-col>
            <el-col :span="12">
              <el-checkbox v-model="nodeForm.filterHeightOk">高度未压缩</el-checkbox>
            </el-col>
          </el-row>
        </el-form-item>
        <el-form-item label="状态来路 · 细分原因">
          <el-select v-model="nodeForm.createOrigin" clearable placeholder="选一个细分原因（可选），后续判定会另写结论来路"
            style="width: 100%">
            <el-option v-for="o in ORIGIN_REASONS" :key="o" :label="o" :value="o" />
          </el-select>
          <el-input v-model="nodeForm.createOriginNote" placeholder="补充说明（可选）" clearable
            style="margin-top: 8px" />
        </el-form-item>
        <el-form-item v-if="!nodeForm.anchorId" label="顺带登记为人工阵眼（手动确认）">
          <el-checkbox v-model="nodeForm.registerAnchor">登记为阵眼后，此节点自动继承阵眼血缘</el-checkbox>
          <el-row v-if="nodeForm.registerAnchor" :gutter="8" style="margin-top: 10px">
            <el-col :span="8">
              <el-input v-model="nodeForm.anchorCode" placeholder="股票代码，如 002790" />
            </el-col>
            <el-col :span="8">
              <el-select v-model="nodeForm.anchorRole" style="width: 100%">
                <el-option v-for="r in ANCHOR_ROLES" :key="r.value" :label="r.label" :value="r.value" />
              </el-select>
            </el-col>
            <el-col :span="8">
              <el-select v-model="nodeForm.anchorCycle" clearable placeholder="周期标识（可选）" style="width: 100%">
                <el-option label="周期" value="CYCLE" />
                <el-option label="主升" value="MAIN" />
                <el-option label="冰点" value="ICE" />
              </el-select>
            </el-col>
          </el-row>
          <el-row v-if="nodeForm.registerAnchor" :gutter="8" style="margin-top: 8px">
            <el-col :span="12">
              <el-date-picker v-model="nodeForm.anchorStart" type="date" value-format="YYYY-MM-DD" :disabled-date="disabledDate" :cell-class-name="cellClass" placeholder="生效起始（默认 D0）" style="width: 100%" />
            </el-col>
            <el-col :span="12">
              <el-date-picker v-model="nodeForm.anchorEnd" type="date" value-format="YYYY-MM-DD" :disabled-date="disabledDate" :cell-class-name="cellClass" placeholder="失效日（空=仍在位）" style="width: 100%" />
            </el-col>
          </el-row>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="nodeForm.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showNodeDialog = false">取消</el-button>
        <el-button type="primary" @click="handleCreateNode">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showLadderDialog" title="新增节点事件（从天梯选龙头）" width="760px" class="dark-node-dialog">
      <el-form label-position="top">
        <el-form-item label="选择 D0 日期">
          <el-date-picker v-model="ladderD0Date" type="date" value-format="YYYY-MM-DD"
            :disabled-date="disabledDate" :cell-class-name="cellClass"
            placeholder="默认回落最近交易日" style="width: 100%" @change="onLadderDateChange" />
        </el-form-item>
      </el-form>
      <template v-if="leaderChoices.length">
        <div class="intel-head">
          D0 <b>{{ ladderPreview.date || '—' }}</b> 涨停
          {{ ladderPreview.limitUpCount != null ? ladderPreview.limitUpCount + '家' : '未知' }} · 跌停
          {{ ladderPreview.limitDownCount != null ? ladderPreview.limitDownCount + '家' : '未知' }} · 天梯最高板
          {{ ladderPreview.maxBoard || '—' }} 板
          <span v-if="ladderPreview.prevDate" class="prev-note">｜昨日(T-1) {{ ladderPreview.prevDate }} 最高板 {{ ladderPreview.prevMaxBoard ?? '—' }} 板</span>
        </div>
        <el-form label-position="top">
          <el-form-item label="选择龙头（仅昨日最高板 / D0最高板，默认昨日最高板）">
            <el-select v-model="ladderPickedCode" style="width: 100%" @change="onLadderPick2">
              <el-option v-for="c in leaderChoices" :key="c.ld.code" :label="ladderLabel(c)" :value="c.ld.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="前置过滤器判据">
            <el-checkbox v-model="ladderNoKill">老龙非A杀</el-checkbox>
            <el-checkbox v-model="ladderHeightOk">高度未压缩</el-checkbox>
          </el-form-item>
        </el-form>
        <div class="plan-card">
          <div class="plan-title">方案预览（市场总节点）</div>
          <div class="plan-row">锚定龙头：{{ plan.anchorStock || '—' }}（{{ plan.anchorMaxBoard }}板）</div>
          <div class="plan-row">题材/板块：{{ plan.theme || '（可不填）' }}</div>
          <div class="plan-row">D0 日期：{{ plan.d0Date || '—' }}</div>
          <div class="plan-row">候选票：{{ plan.candsText }}</div>
          <div class="plan-row">前置过滤器：涨停{{ plan.limitUpCount ?? '未知' }} / 跌停{{ plan.limitDownCount ?? '未知' }}</div>
        </div>
      </template>
      <div v-else class="intel-empty">该 D0 无涨停池明细（≥2 板为空），请换日期或先拉取当日行情。</div>
      <template #footer>
        <el-button @click="showLadderDialog = false">取消</el-button>
        <el-button @click="openCreate">高级手填（细分原因/登记阵眼）</el-button>
        <el-button type="primary" :disabled="!ladderPickedCode" @click="createPlan">生成节点</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { nodeApi, anchorsApi } from '../api/modules'
import { useTradingCalendar } from '../utils/tradingCalendar'
import { ElMessage, ElMessageBox } from 'element-plus'
import NodeSuggestPanel from '../components/NodeSuggestPanel.vue'

const { disabledDate, cellClass, loadTradingDays } = useTradingCalendar()

const router = useRouter()
const currentNode = ref(null)
const nodeList = ref([])
const showNodeDialog = ref(false)
const showSuggest = ref(false)
const ladderLeaders = ref([])
const ladderPickedCode = ref('')
const ladderNoKill = ref(true)
const ladderHeightOk = ref(true)
const showLadderDialog = ref(false)
const ladderPreview = ref({})
const ladderD0Date = ref('')
/** 龙头下拉只给两项：D0昨日最高板 / D0最高板。元素形如 { kind:'prev'|'today', ld:Leader }。 */
const leaderChoices = ref([])
const suggestNode = ref(null)
const anchorOptions = ref([])

/** 状态来路·细分原因（创建场景）可选项；已定态节点的结论细分由后端判定/采纳写入 conclusion_reason。 */
const ORIGIN_REASONS = [
  '手动识别·空间板打开',
  '手动识别·天梯最高板',
  '阵眼关联确认',
  '板块发酵',
  '冰点低位启动',
  '连板接力',
  '情绪修复',
  '其他'
]
/** 顺带登记为人工阵眼的可选角色，与 t_anchor 的 ROLE_* 对齐。 */
const ANCHOR_ROLES = [
  { value: 'ZONG', label: '总龙头' },
  { value: 'FENZHI', label: '分支龙' },
  { value: 'BUZHANG', label: '补涨龙' },
  { value: 'FANBAO', label: '反包龙' },
  { value: 'CYCLE', label: '周期阵眼' }
]

const statusFilter = ref('')
const dateRange = ref(null)

/**
 * 类型轴的五个取值（PRD §2）。后端 NodeService.nodeTypeLabel 认的就是这五个，
 * 落库也是这五个原值；筛选器选项与展示文案共用这一份，避免前后端口径漂移。
 * 周期节点 = 启动/分歧/切换（横向），空间节点 = 破局日/破局次日（纵向）。
 */
const NODE_TYPES = [
  { value: 'START', label: '启动日' },
  { value: 'DIVERGE', label: '分歧日' },
  { value: 'SWITCH', label: '切换日' },
  { value: 'SPACE_BREAK', label: '破局日 · 观察' },
  { value: 'SPACE_BREAK_NEXT', label: '破局次日 · 出手' }
]
/** 筛「未识别」的哨兵值：node_type 为 NULL 的行，不能用一个空字符串糊过去。 */
const NONE_KEY = '__NONE__'
const nodeTypeFilter = ref('')

/**
 * 展示文案。后端已按 nodeTypeLabel() 算好中文名；取不到就是「未识别」。
 * 刻意不回落「普通 / 常规 / 其他」：反义定义只说明它不是什么，
 * 而且每加一个 node_type，这个词的所指就要跟着变一次。
 */
function nodeTypeLabel(row) {
  return row?.nodeTypeLabel || '未识别'
}
/** 配色类名，按 node_type 原值拼；没有类型就是 nt-none（灰）。 */
function nodeTypeClass(row) {
  return 'nt-' + (row?.nodeType || 'none')
}
/** 观察日（破局日）：当天 0 候选是规则要求，不是数据缺失。 */
function isObserveDay(row) {
  return row?.nodeType === 'SPACE_BREAK'
}

/** 六态路径：节点在情绪周期里的位置。active 命中 D0 周期。 */
const EVOLVE = [
  { key: '冰点', label: '冰点', color: '#0e7490' },
  { key: '启动', label: '启动', color: '#15803d' },
  { key: '发酵', label: '发酵', color: '#b45309' },
  { key: '高潮', label: '高潮', color: '#b91c1c' },
  { key: '分歧', label: '分歧', color: '#a16207' },
  { key: '退潮', label: '退潮', color: '#475569' }
]

const activeNode = computed(() => currentNode.value)

const parsedCandidates = computed(() => {
  const raw = activeNode.value?.d0Candidates
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return raw.split(',').map(s => s.trim()).filter(Boolean)
  }
})

function openSuggest(row) {
  suggestNode.value = row
  showSuggest.value = true
}
function onAdopted() {
  loadNodes()
}
function openCreate() {
  resetNodeForm()
  ladderLeaders.value = []
  showNodeDialog.value = true
  loadAnchors()
}
function resetNodeForm() {
  nodeForm.anchorId = null
  nodeForm.anchorStock = ''
  nodeForm.theme = ''
  nodeForm.d0Date = ''
  nodeForm.candidatesStr = ''
  nodeForm.anchorMaxBoard = 7
  nodeForm.limitDownCount = null
  nodeForm.limitUpCount = null
  nodeForm.filterNoKill = true
  nodeForm.filterHeightOk = true
  nodeForm.note = ''
  nodeForm.createOrigin = ''
  nodeForm.createOriginNote = ''
  nodeForm.registerAnchor = false
  nodeForm.anchorCode = ''
  nodeForm.anchorRole = 'ZONG'
  nodeForm.anchorCycle = ''
  nodeForm.anchorStart = ''
  nodeForm.anchorEnd = ''
}
/** 从天梯新建：先选 D0 日期，龙头下拉只给「昨日最高板(默认)/D0最高板」两项，选后自动生效各字段并生成一条市场总节点。 */
async function openLadderCreate(date) {
  ladderLeaders.value = []
  leaderChoices.value = []
  ladderPickedCode.value = ''
  ladderNoKill.value = true
  ladderHeightOk.value = true
  ladderPreview.value = {}
  ladderD0Date.value = date || ''
  showLadderDialog.value = true
  try {
    const res = await nodeApi.ladderIntel(date || undefined)
    const intel = res.data || {}
    ladderPreview.value = intel
    ladderD0Date.value = intel.date || date || ''
    ladderLeaders.value = Array.isArray(intel.leaders) ? intel.leaders : []
    const prevTop = (Array.isArray(intel.prevLeaders) ? intel.prevLeaders : [])[0] || null
    const todayTop = ladderLeaders.value[0] || null
    const choices = []
    if (prevTop) choices.push({ kind: 'prev', ld: prevTop })
    if (todayTop) choices.push({ kind: 'today', ld: todayTop })
    leaderChoices.value = choices
    // 默认昨日最高板；无昨日则回落 D0最高板
    if (choices.length) {
      ladderPickedCode.value = (prevTop ? prevTop : todayTop).code
    }
  } catch (e) {
    ladderLeaders.value = []
    leaderChoices.value = []
  }
}
function onLadderDateChange(val) {
  openLadderCreate(val || undefined)
}
function pickedLeader() {
  const c = leaderChoices.value.find(x => x.ld.code === ladderPickedCode.value)
  return c ? c.ld : null
}
/** 天梯方案预览：以所选龙头为锚，候选取自全市场涨停池。板块节点（原系统B）已下线，不再产出同板块收敛版本。 */
const plan = computed(() => buildPlan(pickedLeader()))
function buildPlan(ld) {
  const d0 = ladderPreview.value.date || ''
  const limitUp = ladderPreview.value.limitUpCount
  const limitDown = ladderPreview.value.limitDownCount
  if (!ld) {
    return { anchorStock: '', anchorMaxBoard: 0, theme: '', d0Date: d0, candsText: '', limitUpCount: limitUp, limitDownCount: limitDown, cands: [] }
  }
  const cands = ladderLeaders.value.map(x => x.name)
  const candsText = cands.length
    ? cands.slice(0, 6).join('、') + (cands.length > 6 ? ' 等' + cands.length + '只' : '')
    : '（候选为空）'
  return {
    anchorStock: ld.name,
    anchorMaxBoard: ld.board,
    theme: ld.industry || '',
    d0Date: d0,
    limitUpCount: limitUp,
    limitDownCount: limitDown,
    candsText,
    cands
  }
}
function onLadderPick2() { /* 计划由 computed 自动重算 */ }
function ladderLabel(c) {
  const ld = c.ld
  const pre = c.kind === 'prev' ? '昨日最高板' : 'D0最高板'
  return `${pre} · ${ld.name}（${ld.board}板）` + (ld.industry ? ' · ' + ld.industry : '')
}
function goTianti() {
  openLadderCreate()
}
/** 把方案预览打包成 create 的 payload（判据沿用弹窗勾选，未计数的项保留未知）。systemType 恒为 A——板块节点（B）已下线。 */
function buildLadderPayload(p) {
  const filterDetail = {
    limitDownCount: p.limitDownCount ?? null,
    limitUpCount: p.limitUpCount ?? null,
    anchorKill: !ladderNoKill.value,
    heightCompress: !ladderHeightOk.value
  }
  const flags = [
    filterDetail.limitDownCount == null ? null : filterDetail.limitDownCount < 10,
    filterDetail.limitUpCount == null ? null : filterDetail.limitUpCount > 60,
    !filterDetail.anchorKill,
    !filterDetail.heightCompress
  ]
  return {
    systemType: 'A',
    anchorId: null,
    anchorStock: p.anchorStock,
    anchorMaxBoard: p.anchorMaxBoard,
    theme: p.theme || undefined,
    d0Date: p.d0Date,
    d0Candidates: JSON.stringify(p.cands || []),
    filterPassed: flags.every(f => f === true) ? 1 : 0,
    filterDetail: JSON.stringify(filterDetail),
    status: '待验证',
    note: ''
  }
}
async function createPlan() {
  if (!pickedLeader()) return
  try {
    await nodeApi.create(buildLadderPayload(plan.value))
    ElMessage.success('已生成节点，状态为待验证')
  } catch (e) {
    ElMessage.error('创建失败')
  }
  showLadderDialog.value = false
  loadNodes()
}
function goHighEco() {
  router.push({ name: 'HighEco' })
}
function resetFilters() {
  nodeTypeFilter.value = ''
  statusFilter.value = ''
  dateRange.value = null
}

const nodeForm = reactive({
  anchorId: null,
  anchorStock: '',
  anchorMaxBoard: 7,
  theme: '',
  d0Date: '',
  candidatesStr: '',
  limitDownCount: null,
  limitUpCount: null,
  filterNoKill: true,
  filterHeightOk: true,
  note: '',
  createOrigin: '',
  createOriginNote: '',
  registerAnchor: false,
  anchorCode: '',
  anchorRole: 'ZONG',
  anchorCycle: '',
  anchorStart: '',
  anchorEnd: ''
})

async function loadAnchors() {
  const d0 = nodeForm.d0Date || undefined
  try {
    const res = await anchorsApi.config(d0)
    anchorOptions.value = Array.isArray(res.data) ? res.data : []
  } catch {
    anchorOptions.value = []
  }
}
function onAnchorChange(id) {
  if (id == null) return
  const a = anchorOptions.value.find(x => x.id === id)
  if (a) nodeForm.anchorStock = a.stockName
}
function anchorLabel(a) {
  return `${a.stockName}（${a.roleLabel}）· ${a.startDate}${a.endDate ? '→' + a.endDate : '→今'}`
}

const filteredNodes = computed(() => {
  return nodeList.value.filter(row => {
    if (statusFilter.value && row.status !== statusFilter.value) return false
    if (nodeTypeFilter.value) {
      const hit = nodeTypeFilter.value === NONE_KEY ? !row.nodeType : row.nodeType === nodeTypeFilter.value
      if (!hit) return false
    }
    if (dateRange.value) {
      const [s, e] = dateRange.value
      if (row.d0Date && (row.d0Date < s || row.d0Date > e)) return false
    }
    return true
  })
})

function t1Repack(v) {
  return v == null ? '未知' : (v === 1 ? '是（节点作废）' : '否')
}
function fmtRate(v) {
  return v == null ? '—' : v
}
function fmtTime(t) {
  if (!t) return null
  return String(t).replace('T', ' ').substring(0, 16)
}
function onLadder(status) {
  return !!status && status.startsWith('在梯')
}
function statusType(status) {
  const map = { '待验证': 'warning', '有效': 'success', '失效': 'danger' }
  return map[status] || 'info'
}
function filterLabel(row) {
  if (row.filterPassed == null) return '—'
  return row.filterPassed === 1 ? '全过' : '未全过'
}
/** 状态细分标签：主因(conclusion_reason) + 失效叠加监管/情绪退潮(causeTags)，去重。 */
function reasonTags(row) {
  const tags = []
  if (row.conclusionReason) tags.push(row.conclusionReason)
  ;(row.causeTags || []).forEach(t => { if (!tags.includes(t)) tags.push(t) })
  return tags
}
/** 副标签去重显示：主状态标签已含「有效/失效」，副标签只留强度或机理，避免「有效·强」与「有效」并列重复。 */
function reasonLabel(t) {
  const m = /^(有效|失效)\W?(.*)$/.exec(t)
  if (m && m[2]) return m[2]
  if (t.endsWith('失效') && t !== '失效') return t.slice(0, -2)
  return t
}
/** 周期阶段可能带括注（如"退潮(强制)"），按前缀落到六态色带对应的段上。 */
function stageKey(cycle) {
  if (!cycle) return ''
  const hit = EVOLVE.find(st => cycle.startsWith(st.key))
  return hit ? hit.key : ''
}

// 创建节点时四个过滤判据的唯一定义处，与后端/历史页面共用同一套阈值
const FILTER_RULES = [
  { key: 'limitDownCount', flag: 'limitDownOk', pass: (v) => v < 10 },
  { key: 'limitUpCount', flag: 'limitUpOk', pass: (v) => v > 60 },
  { key: 'anchorKill', flag: 'noKill', pass: (v) => !v },
  { key: 'heightCompress', flag: 'heightOk', pass: (v) => !v }
]
function evaluateFilters(detail) {
  const out = {}
  if (!detail) return out
  for (const rule of FILTER_RULES) {
    const v = detail[rule.key]
    out[rule.flag] = v == null ? null : rule.pass(v)
  }
  return out
}

async function handleDelete(row) {
  const label = `${row.d0Date || ''} ${row.anchorName || row.anchorStock || ''}`.trim()
  try {
    await ElMessageBox.confirm(`确定删除节点「${label}」？此操作不可撤销。`, '删除节点事件', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await nodeApi.deleteNode(row.id)
    ElMessage.success('已删除')
    loadNodes()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

async function loadNodes() {
  try {
    const [currRes, listRes] = await Promise.all([
      nodeApi.getCurrent().catch(() => ({ data: null })),
      nodeApi.list()
    ])
    currentNode.value = currRes.data || null
    nodeList.value = Array.isArray(listRes.data) ? listRes.data : (listRes.data?.list || [])
  } catch (e) { /* ignore */ }
}

async function handleCreateNode() {
  if (!nodeForm.anchorId && !nodeForm.anchorStock) {
    ElMessage.warning('请选择或填写锚定龙头')
    return
  }
  if (!nodeForm.d0Date) {
    ElMessage.warning('请填写 D0 日期')
    return
  }
  // 顺带登记阵眼：需手动勾选 + 填代码；登记成功后节点 anchorId 指向新阵眼
  let anchorId = nodeForm.anchorId
  if (nodeForm.registerAnchor && !nodeForm.anchorId) {
    if (!nodeForm.anchorCode) {
      ElMessage.warning('登记阵眼请填写股票代码')
      return
    }
    if (!nodeForm.anchorStock) {
      ElMessage.warning('登记阵眼请先填写锚定龙头名称')
      return
    }
    try {
      const anchorRes = await anchorsApi.add({
        stockCode: nodeForm.anchorCode,
        stockName: nodeForm.anchorStock,
        role: nodeForm.anchorRole || 'ZONG',
        startDate: nodeForm.anchorStart || nodeForm.d0Date,
        endDate: nodeForm.anchorEnd || null,
        cycleTag: nodeForm.anchorCycle || ''
      })
      anchorId = anchorRes.data?.id
    } catch (e) {
      ElMessage.error('阵眼登记失败，未创建节点')
      return
    }
  }
  const candidates = nodeForm.candidatesStr
    ? JSON.stringify(nodeForm.candidatesStr.split(',').map(s => s.trim()).filter(Boolean))
    : '[]'
  const filterDetail = {
    limitDownCount: nodeForm.limitDownCount,
    limitUpCount: nodeForm.limitUpCount,
    anchorKill: !nodeForm.filterNoKill,
    heightCompress: !nodeForm.filterHeightOk
  }
  const flags = Object.values(evaluateFilters(filterDetail))
  // 状态来路：细分原因标签 + 补充说明合并
  const origin = (nodeForm.createOrigin || '').trim()
  const originNote = (nodeForm.createOriginNote || '').trim()
  let statusNote = ''
  if (origin && originNote) statusNote = origin + '｜' + originNote
  else if (origin) statusNote = origin
  else if (originNote) statusNote = originNote
  const payload = {
    systemType: 'A',
    anchorId: anchorId || null,
    anchorStock: anchorId ? undefined : nodeForm.anchorStock,
    anchorMaxBoard: nodeForm.anchorMaxBoard,
    theme: nodeForm.theme || undefined,
    d0Date: nodeForm.d0Date,
    d0Candidates: candidates,
    filterPassed: flags.every(f => f === true) ? 1 : 0,
    filterDetail: JSON.stringify(filterDetail),
    status: '待验证',
    note: nodeForm.note,
    statusNote: statusNote || undefined
  }
  try {
    await nodeApi.create(payload)
    ElMessage.success('创建成功')
    showNodeDialog.value = false
    loadNodes()
  } catch (e) {
    ElMessage.error('创建失败')
  }
}

onMounted(() => {
  loadTradingDays()
  loadNodes()
})
</script>

<style scoped>
.node-page { max-width: 1060px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-header h2 { margin: 0; color: #e1e8ed; }

/* ② 节点演变路径 */
.evolution { background: #1a2332; border-radius: 12px; padding: 16px 20px; margin-bottom: 20px; }
.evolution-title { color: #8899a6; font-size: 13px; margin-bottom: 10px; }
.evolution-band { display: flex; border-radius: 8px; overflow: hidden; }
.evo-seg { flex: 1; text-align: center; padding: 10px 0; color: #fff; font-size: 13px; opacity: .55; transition: opacity .2s; }
.evo-seg.active { opacity: 1; box-shadow: inset 0 -3px 0 #ffd166; }
.evolution-hint { color: #8899a6; font-size: 12px; margin-top: 8px; }
.evolution-hint b { color: #e1e8ed; }

/* 平安：活跃节点卡片 */
.current-node {
  background: #1a2332; border-radius: 12px; padding: 20px 24px; margin-bottom: 20px;
  border: 1px solid #2a3a52;
}
.current-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.current-tags { display: flex; gap: 8px; }
.recalc { color: #8899a6; font-size: 12px; }
.card-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-bottom: 8px; }
.card-cell { display: flex; flex-direction: column; gap: 4px; }
.card-cell .k { color: #8899a6; font-size: 12px; }
.card-cell .v { color: #e1e8ed; font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.role { margin-left: 2px; }
.muted { color: #8899a6; font-weight: 400; font-size: 12px; }
.surv-desc { color: #f87171; font-size: 12px; margin: 6px 0 0; }

.ntag {
  display: inline-flex; align-items: center; align-self: center; padding: 1px 8px; border-radius: 4px;
  font-size: 12px; line-height: 1.7; white-space: nowrap; border: 1px solid transparent;
}
.nt-SPACE_BREAK { color: var(--node-sb, #a78bfa); background: rgba(167, 139, 250, .14); border-color: rgba(167, 139, 250, .35); }
.nt-SPACE_BREAK_NEXT { color: var(--node-nxt, #34d399); background: rgba(52, 211, 153, .14); border-color: rgba(52, 211, 153, .35); }
.nt-START, .nt-SWITCH, .nt-DIVERGE { color: var(--node-phase, #60a5fa); background: rgba(96, 165, 250, .14); border-color: rgba(96, 165, 250, .35); }
.nt-none { color: var(--node-none, #8899a6); background: rgba(136, 153, 166, .12); border-color: rgba(136, 153, 166, .28); }

.node-timeline { display: flex; position: relative; margin: 16px 0; }
.timeline-step { flex: 1; display: flex; flex-direction: column; align-items: center; position: relative; padding: 0 12px; }
.step-dot { width: 16px; height: 16px; border-radius: 50%; margin-bottom: 12px; z-index: 1; }
.step-dot.d0 { background: #3b82f6; }
.step-dot.t1 { background: #f59e0b; }
.step-dot.t2 { background: #2d8a4e; }
.timeline-step:not(.active) .step-dot { background: #4a5568; }

/* 观察日（破局日）态：空心紫圈 + 虚线连接。
   语义要和上面的灰点彻底分开：灰点＝这一步还没走到；空心紫＝走到了，
   但规则要求这一天不出手。两者复用会让「为什么今天没票」变成一笔糊涂账。 */
.step-dot.observe { background: transparent; border: 3px solid var(--node-sb, #a78bfa); }
.timeline-step.observe::before {
  content: ''; position: absolute; top: 7px; left: 0; width: 100%; height: 0;
  border-top: 1px dashed var(--node-sb, #a78bfa); opacity: .55;
}
.observe-note { color: var(--node-sb, #a78bfa); font-size: 12px; margin-top: 8px; }
.step-content { text-align: center; display: flex; flex-direction: column; gap: 4px; }
.step-label { color: #e1e8ed; font-weight: 600; font-size: 14px; }
.step-date { color: #8899a6; font-size: 13px; }
.step-desc { color: #8899a6; font-size: 12px; }
.candidates { display: flex; flex-wrap: wrap; gap: 4px; justify-content: center; margin-top: 8px; }
.signal { color: #8899a6; font-size: 12px; margin: 0; }

/* 空状态 */
.empty-box {
  background: #1a2332; border-radius: 12px; padding: 48px 24px; margin-bottom: 20px;
  text-align: center; border: 1px dashed #2a3a52;
}
.empty-emoji { color: #3a4d63; font-size: 52px; line-height: 1; }
.empty-title { color: #e1e8ed; font-size: 18px; font-weight: 600; margin: 16px 0 8px; }
.empty-sub { color: #8899a6; font-size: 13px; margin-bottom: 20px; }
.empty-actions { display: flex; gap: 12px; justify-content: center; }

/* ③ 历史节点 */
.history-section { background: #1a2332; border-radius: 12px; padding: 20px 24px; }
.history-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 12px; }
.history-head h3 { margin: 0; color: #e1e8ed; }
.filters { display: flex; gap: 8px; flex-wrap: wrap; }
/* 单元格本身已有 20px 50px 内边距，这里只补少量间距，避免左缘被推到 100px 外 */
.expand-detail { padding: 4px 8px 6px 2px; display: flex; flex-direction: column; gap: 6px; }
.detail-row { color: #8899a6; font-size: 12px; }
.reason-tags { display: flex; flex-wrap: wrap; gap: 3px; margin-top: 5px; }
.reason-tag { margin: 0; font-size: 11px; }
.nc { display: flex; align-items: center; gap: 6px; min-width: 0; }
.nc-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nc .el-tag { margin: 0; }
.nboard { color: #9fb2c6; font-size: 11px; white-space: nowrap; }
.nc-theme { color: #8899a6; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.node-stock { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.ns-name { font-weight: 600; color: #e6ecf2; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ns-meta { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.ns-tag { margin: 0; }

.filter-field { width: 100%; }
.filter-field-label { display: block; font-size: 12px; color: #8899a6; margin-bottom: 4px; }

/* 从今日天梯新增：双方案弹窗 */
.intel-head { color: #8899a6; font-size: 13px; padding: 10px 12px; margin-bottom: 14px;
  background: #0f1720; border: 1px solid #2a3a52; border-radius: 8px; }
.intel-head b { color: #e1e8ed; }
.intel-empty { color: #8899a6; padding: 24px; text-align: center; }
.plan-card { background: #0f1720; border: 1px solid #2a3a52; border-radius: 10px; padding: 14px 16px; }
.plan-title { color: #ffd166; font-weight: 600; font-size: 13px; margin-bottom: 10px; }
.plan-row { color: #e1e8ed; font-size: 12px; line-height: 1.9; }
</style>

<style>
/* 节点页三个弹窗的深色主题：el-dialog teleport 到 body，须用非 scoped 全局选择器。
   自定义 class 会加在 .el-dialog 面板自身上（也可能是其祖先），两种都覆盖。 */
.dark-node-dialog.el-dialog,
.dark-node-dialog .el-dialog {
  background: #1a2332 !important; border: 1px solid #2a3a52; border-radius: 12px;
}
.dark-node-dialog .el-dialog__title { color: #e1e8ed; }
.dark-node-dialog .el-dialog__headerbtn:focus .el-dialog__close,
.dark-node-dialog .el-dialog__headerbtn:hover .el-dialog__close { color: #ffd166; }
.dark-node-dialog .el-form-item__label { color: #9fb2c6; }
.dark-node-dialog .el-input__wrapper,
.dark-node-dialog .el-select__wrapper,
.dark-node-dialog .el-textarea__inner {
  background-color: #0f1720 !important; box-shadow: 0 0 0 1px #2a3a52 inset !important;
}
.dark-node-dialog .el-input__inner,
.dark-node-dialog .el-textarea__inner { color: #e1e8ed !important; }
.dark-node-dialog .el-input__inner::placeholder,
.dark-node-dialog .el-textarea__inner::placeholder { color: #5c6e84; }
.dark-node-dialog .el-radio__label,
.dark-node-dialog .el-checkbox__label { color: #c6d2de; }
.dark-node-dialog .el-date-editor .el-input__wrapper { background: #0f1720; }
.dark-node-dialog .el-input-number .el-input__wrapper { background: #0f1720; }
</style>