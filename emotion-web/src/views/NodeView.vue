<template>
  <div class="node-page">
    <div class="page-header">
      <h2>节点理论</h2>
      <el-button type="primary" @click="showNodeDialog = true">新增节点事件</el-button>
    </div>

    <div class="filter-panel" v-if="currentNode">
      <h3>前置过滤器 <span class="src-hint">（你建节点时填的读数；平台复算的那份在下方「平台复算」里）</span></h3>
      <div class="filter-grid">
        <div class="filter-item" :class="{ pass: filterCheck.limitDownOk === true, fail: filterCheck.limitDownOk === false }">
          <span class="filter-label">跌停 &lt; 10家</span>
          <span class="filter-val">{{ fmtCount(filterReading.limitDownCount) }}</span>
        </div>
        <div class="filter-item" :class="{ pass: filterCheck.limitUpOk === true, fail: filterCheck.limitUpOk === false }">
          <span class="filter-label">涨停 &gt; 60家</span>
          <span class="filter-val">{{ fmtCount(filterReading.limitUpCount) }}</span>
        </div>
        <div class="filter-item" :class="{ pass: filterCheck.noKill === true, fail: filterCheck.noKill === false }">
          <span class="filter-label">老龙非A杀</span>
          <span class="filter-val">{{ yesNo(filterReading.anchorKill, 'A杀', '温和') }}</span>
        </div>
        <div class="filter-item" :class="{ pass: filterCheck.heightOk === true, fail: filterCheck.heightOk === false }">
          <span class="filter-label">高度未连续压缩</span>
          <span class="filter-val">{{ yesNo(filterReading.heightCompress, '压缩', '正常') }}</span>
        </div>
      </div>
      <el-tag :type="allFilterPass === null ? 'info' : allFilterPass ? 'success' : 'warning'"
        size="large" style="margin-top: 12px">
        {{ allFilterPass === null ? '读数不全，过滤器结论未知'
          : allFilterPass ? '过滤器通过 → 可进入系统A' : '过滤器部分不通过 → 仅系统B或空仓' }}
      </el-tag>
    </div>

    <div class="current-node" v-if="currentNode">
      <h3>当前追踪</h3>
      <div class="node-timeline">
        <div class="timeline-step" :class="{ active: true }">
          <div class="step-dot d0"></div>
          <div class="step-content">
            <span class="step-label">D0（断板日）</span>
            <span class="step-date">{{ currentNode.d0Date }}</span>
            <span class="step-desc">
              锚定龙头：{{ currentNode.anchorStock }}（{{ currentNode.anchorMaxBoard }}板）
            </span>
            <div class="candidates" v-if="parsedCandidates.length">
              <el-tag v-for="c in parsedCandidates" :key="c" size="small" type="info">{{ c }}</el-tag>
            </div>
          </div>
        </div>
        <div class="timeline-step" :class="{ active: currentNode.t1Date }">
          <div class="step-dot t1"></div>
          <div class="step-content">
            <span class="step-label">T+1（验证日）</span>
            <span class="step-date">{{ currentNode.t1Date || '待验证' }}</span>
            <template v-if="currentNode.t1Date">
              <span class="step-desc">
                老龙反包：{{ currentNode.t1AnchorRepack ? '是（节点作废）' : '否' }}
              </span>
              <span class="step-desc">
                晋级数量：{{ currentNode.t1PromotionCount }}只（{{ currentNode.t1PromotionRate }}%）
              </span>
            </template>
          </div>
        </div>
        <div class="timeline-step" :class="{ active: currentNode.nodeStock }">
          <div class="step-dot t2"></div>
          <div class="step-content">
            <span class="step-label">确认</span>
            <span class="step-desc" v-if="currentNode.nodeStock">
              节点票：{{ currentNode.nodeStock }}
              <span v-if="currentNode.nodeStockMaxBoard">（最高{{ currentNode.nodeStockMaxBoard }}板）</span>
            </span>
            <span class="step-desc" v-else>待确认</span>
          </div>
        </div>
      </div>
      <div class="node-status">
        <el-tag :type="nodeStatusType(currentNode.status)" size="large">{{ currentNode.status }}</el-tag>
        <el-tag>{{ currentNode.systemType === 'A' ? '系统A · 市场总节点' : '系统B · 板块节点' }}</el-tag>
      </div>
      <NodeSuggestPanel :node="currentNode" @adopted="loadNodes" />
    </div>
    <el-empty v-else description="暂无追踪中的节点事件" />

    <div class="history-section" v-if="nodeList.length">
      <h3>历史节点</h3>
      <el-table :data="nodeList" style="width: 100%">
        <el-table-column prop="d0Date" label="D0日期" width="120" />
        <el-table-column prop="systemType" label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ row.systemType === 'A' ? '总节点' : '板块节点' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="anchorStock" label="锚定龙头" width="120" />
        <el-table-column prop="anchorMaxBoard" label="板数" width="70" />
        <el-table-column prop="nodeStock" label="节点票" width="120" />
        <el-table-column prop="nodeStockMaxBoard" label="最高板" width="80" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="nodeStatusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="statusNote" label="状态来路" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="note-text">{{ row.statusNote || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openSuggest(row)">复算</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="showSuggest" title="节点复算" width="760px">
      <NodeSuggestPanel v-if="suggestNode" :node="suggestNode" @adopted="onAdopted" />
    </el-dialog>

    <el-dialog v-model="showNodeDialog" title="新增节点事件" width="560px">
      <el-form :model="nodeForm" label-position="top">
        <el-form-item label="系统类型">
          <el-radio-group v-model="nodeForm.systemType">
            <el-radio value="A">系统A · 市场总节点</el-radio>
            <el-radio value="B">系统B · 板块节点</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="锚定龙头">
          <el-input v-model="nodeForm.anchorStock" placeholder="龙头名称" />
        </el-form-item>
        <el-form-item label="锚定龙头最高板数">
          <el-input-number v-model="nodeForm.anchorMaxBoard" :min="1" :max="30" />
        </el-form-item>
        <el-form-item label="D0 日期">
          <el-date-picker v-model="nodeForm.d0Date" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
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
        <el-form-item label="备注">
          <el-input v-model="nodeForm.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showNodeDialog = false">取消</el-button>
        <el-button type="primary" @click="handleCreateNode">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { nodeApi } from '../api/modules'
import NodeSuggestPanel from '../components/NodeSuggestPanel.vue'
import { ElMessage } from 'element-plus'

const currentNode = ref(null)
const nodeList = ref([])
const showNodeDialog = ref(false)
const showSuggest = ref(false)
const suggestNode = ref(null)

function openSuggest(row) {
  suggestNode.value = row
  showSuggest.value = true
}

/** 采纳完只刷列表：弹窗留着，里面那格面板自己会重新复算一遍给他看结果。 */
function onAdopted() {
  loadNodes()
}

const nodeForm = reactive({
  systemType: 'A',
  anchorStock: '',
  anchorMaxBoard: 7,
  d0Date: '',
  candidatesStr: '',
  limitDownCount: null,
  limitUpCount: null,
  filterNoKill: true,
  filterHeightOk: true,
  note: ''
})

const parsedCandidates = computed(() => {
  const raw = currentNode.value?.d0Candidates
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return raw.split(',').map(s => s.trim()).filter(Boolean)
  }
})

/**
 * filter_detail 在库里就是一个 JSON 串（后端直接回实体，不做 VO），
 * 不 parse 就取字段等于每个读数都是 undefined——面板上那四个格子一直都是空的。
 */
const filterReading = computed(() => {
  const raw = currentNode.value?.filterDetail
  if (!raw) return {}
  if (typeof raw !== 'string') return raw
  try {
    const obj = JSON.parse(raw)
    return obj && typeof obj === 'object' ? obj : {}
  } catch {
    return {}
  }
})

function fmtCount(v) {
  return v == null ? '未知' : `${v}家`
}

function yesNo(v, onTrue, onFalse) {
  return v == null ? '未知' : v ? onTrue : onFalse
}

/**
 * 四条判据只有一个定义处：读侧渲染和建节点时算的 filter_passed 必须出自同一张表，
 * 这两处已经漂过一次了（一边存极限值、一边读数，谁都没红脸）。
 */
const FILTER_RULES = [
  { key: 'limitDownCount', flag: 'limitDownOk', pass: (v) => v < 10 },
  { key: 'limitUpCount', flag: 'limitUpOk', pass: (v) => v > 60 },
  { key: 'anchorKill', flag: 'noKill', pass: (v) => !v },
  { key: 'heightCompress', flag: 'heightOk', pass: (v) => !v }
]

/** 三态：null = 那天这个读数没有。兜成 0 会把"未知"判成"跌停 0 家，通过"。 */
function evaluateFilters(detail) {
  const out = {}
  for (const rule of FILTER_RULES) {
    const v = detail?.[rule.key]
    out[rule.flag] = v == null ? null : rule.pass(v)
  }
  return out
}

const filterCheck = computed(() => evaluateFilters(filterReading.value))

const allFilterPass = computed(() => {
  const flags = Object.values(filterCheck.value)
  return flags.some((f) => f === null) ? null : flags.every((f) => f)
})

function nodeStatusType(status) {
  const map = { '待验证': 'warning', '有效': 'success', '失效': 'danger' }
  return map[status] || 'info'
}

async function loadNodes() {
  try {
    const [currRes, listRes] = await Promise.all([
      nodeApi.getCurrent().catch(() => ({ data: null })),
      nodeApi.list()
    ])
    currentNode.value = currRes.data
    nodeList.value = Array.isArray(listRes.data) ? listRes.data : (listRes.data?.list || [])
  } catch (e) { /* ignore */ }
}

async function handleCreateNode() {
  if (!nodeForm.anchorStock || !nodeForm.d0Date) {
    ElMessage.warning('请填写锚定龙头和D0日期')
    return
  }
  const candidates = nodeForm.candidatesStr
    ? JSON.stringify(nodeForm.candidatesStr.split(',').map(s => s.trim()).filter(Boolean))
    : '[]'
  // 存读数本体，键名跟读侧那四条一字不差；勾选是"非A杀"，存的是"是否A杀"，在这唯一一处取反
  const filterDetail = {
    limitDownCount: nodeForm.limitDownCount,
    limitUpCount: nodeForm.limitUpCount,
    anchorKill: !nodeForm.filterNoKill,
    heightCompress: !nodeForm.filterHeightOk
  }
  const flags = Object.values(evaluateFilters(filterDetail))
  const payload = {
    systemType: nodeForm.systemType,
    anchorStock: nodeForm.anchorStock,
    anchorMaxBoard: nodeForm.anchorMaxBoard,
    d0Date: nodeForm.d0Date,
    d0Candidates: candidates,
    filterPassed: flags.every((f) => f === true) ? 1 : 0,
    filterDetail: JSON.stringify(filterDetail),
    status: '待验证',
    note: nodeForm.note
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
  loadNodes()
})
</script>

<style scoped>
.node-page {
  max-width: 1000px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}
.page-header h2 {
  margin: 0;
  color: #e1e8ed;
}

.filter-panel {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}
.filter-panel h3 {
  margin: 0 0 16px;
  color: #e1e8ed;
  font-size: 16px;
}
.src-hint {
  color: #8899a6;
  font-size: 12px;
  font-weight: 400;
}
.filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.filter-item {
  padding: 12px;
  border-radius: 8px;
  text-align: center;
  background: #0f1419;
}
.filter-field {
  width: 100%;
}
.filter-field-label {
  display: block;
  font-size: 12px;
  color: #8899a6;
  margin-bottom: 4px;
}
.filter-item.pass {
  border: 1px solid #2d8a4e;
}
.filter-item.fail {
  border: 1px solid #dc2626;
}
.filter-label {
  display: block;
  color: #8899a6;
  font-size: 12px;
  margin-bottom: 4px;
}
.filter-val {
  color: #e1e8ed;
  font-weight: 600;
  font-size: 14px;
}

.current-node {
  background: #1a2332;
  border-radius: 12px;
  padding: 24px;
  margin-bottom: 20px;
}
.current-node h3 {
  margin: 0 0 20px;
  color: #e1e8ed;
}
.node-timeline {
  display: flex;
  gap: 0;
  position: relative;
}
.timeline-step {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
  padding: 0 12px;
}
.step-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  margin-bottom: 12px;
  z-index: 1;
}
.step-dot.d0 { background: #3b82f6; }
.step-dot.t1 { background: #f59e0b; }
.step-dot.t2 { background: #2d8a4e; }
.timeline-step:not(.active) .step-dot {
  background: #4a5568;
}
.step-content {
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.step-label {
  color: #e1e8ed;
  font-weight: 600;
  font-size: 14px;
}
.step-date {
  color: #8899a6;
  font-size: 13px;
}
.step-desc {
  color: #8899a6;
  font-size: 12px;
}
.candidates {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  justify-content: center;
  margin-top: 8px;
}
.node-status {
  display: flex;
  gap: 12px;
  margin-top: 20px;
  justify-content: center;
}

.history-section {
  background: #1a2332;
  border-radius: 12px;
  padding: 24px;
}
.history-section h3 {
  margin: 0 0 16px;
  color: #e1e8ed;
}
.note-text {
  color: #8899a6;
  font-size: 12px;
}
</style>
