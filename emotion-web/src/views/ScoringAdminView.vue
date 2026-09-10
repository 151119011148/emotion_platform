<template>
  <div class="scoring-admin">
    <div class="page-header">
      <h2>打分配置</h2>
      <el-button type="primary" @click="openCreateModel">新增模型</el-button>
    </div>

    <p class="hint">
      维度、权重、子指标与阈值档位都是<b>注册表驱动</b>的：改一维权重、增一个子指标、调一档阈值，仪表盘卡片与复盘页随之变。
      对 <b>five_dim</b> 模型，BAND_LADDER 子直接读这里的规则阶梯；STRATEGY/MANUAL/LAYER 的合成/算法/人工口径由 Java 端
      BoardScoreCalculator 承担，本页面仍能编辑其权重与阈值上限。
      对旧 <b>ultra_short</b>（9 维）模型，规则表仅登记；打分走引擎内置常量。
    </p>

    <!-- 模型表 -->
    <el-card shadow="never" class="block">
      <template #header><span class="block-title">打分模型</span></template>
      <el-table :data="models" highlight-current-row @current-change="onModelRowClick" style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="modelKey" label="标识" width="140" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column label="分母M" width="150">
          <template #default="{ row }">
            <span v-if="row.maxScore == null">现推（权重和×3）</span>
            <span v-else>写死 {{ row.maxScore }}</span>
          </template>
        </el-table-column>
        <el-table-column label="生效" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.active" type="success" size="small">生效中</el-tag>
            <el-tag v-else type="info" size="small">未激活</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" :disabled="row.active" @click="doActivate(row)">激活</el-button>
            <el-button size="small" text @click="openEditModel(row)">编辑</el-button>
            <el-button size="small" text type="danger" @click="doDeleteModel(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 选中模型：维度 + 规则 -->
    <template v-if="detail">
      <el-card shadow="never" class="block">
        <template #header>
          <div class="dim-header">
            <span class="block-title">维度 · {{ detail.model.name }}（{{ detail.model.modelKey }}）</span>
            <div class="formula">
              权重合计 {{ fmt(detail.weightSum) }} ｜
              <span v-if="isFiveDim">总分 = Σ(维分 × 维权)，范围 0-100，直接落到 temperature</span>
              <span v-else>温度 =（加权和 + {{ fmt(detail.effectiveMaxScore) }}）÷（2 × {{ fmt(detail.effectiveMaxScore) }}）× 100（legacy 映射）</span>
            </div>
          </div>
        </template>
        <el-table :data="detail.dims" highlight-current-row @current-change="onDimRowClick" style="width: 100%">
          <el-table-column prop="dimNo" label="第N维" width="80" />
          <el-table-column prop="dimKey" label="维键" width="110" />
          <el-table-column prop="label" label="卡面名" width="120" />
          <el-table-column label="权重" width="170">
            <template #default="{ row }">
              <el-input-number v-model="row.weight" :min="0" :max="10" :step="0.5" :precision="2"
                size="small" controls-position="right" style="width: 120px" />
              <el-button size="small" text type="primary" @click="saveDimWeight(row)">保存</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="sortNo" label="卡序" width="80" />
          <el-table-column label="合成方式" width="180">
            <template #default="{ row }"><el-tag size="small" type="warning">{{ row.ruleEngine }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="recordColumn" label="分列" width="130" />
          <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="showRules(row)">规则</el-button>
              <el-button size="small" text @click="openEditDim(row)">编辑</el-button>
              <el-button size="small" text type="danger" @click="doDeleteDim(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" style="margin-top: 12px" @click="openCreateDim">新增维度</el-button>
      </el-card>

      <!-- 五维模型：中间层子指标 / 四层。旧 ultra_short 模型没有 subs，这一卡片只在 five_dim 下出现 -->
      <el-card shadow="never" class="block" v-if="isFiveDim && selectedDimKey">
        <template #header>
          <div class="dim-header">
            <span class="block-title">子指标 · {{ selectedDimKey }}（引擎读它：改权重/改层权重=改分数）</span>
            <span class="formula">点行看它登记的阶梯；带「└」缩进的是挂在复合子下的层</span>
          </div>
        </template>
        <el-table :data="dimSubs" highlight-current-row @current-change="onSubRowClick" style="width: 100%">
          <el-table-column label="子键" width="200">
            <template #default="{ row }">
              <span :class="{ 'sub-leaf': row.parentSubKey !== '-' }">
                <span v-if="row.parentSubKey !== '-'">└ </span>{{ row.subKey }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="label" label="显示名" width="160" />
          <el-table-column label="权重" width="170">
            <template #default="{ row }">
              <el-input-number v-model="row.weight" :min="0" :max="1" :step="0.05" :precision="4"
                size="small" controls-position="right" style="width: 120px" />
              <el-button size="small" text type="primary" @click="saveSubWeight(row)">保存</el-button>
            </template>
          </el-table-column>
          <el-table-column label="打分方式" width="180">
            <template #default="{ row }"><el-tag size="small" type="warning">{{ row.scoringKind }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="sourceKey" label="读数键" width="160" show-overflow-tooltip />
          <el-table-column prop="note" label="备注" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="showSubLadder(row)">阶梯</el-button>
              <el-button size="small" text @click="openEditSub(row)">编辑</el-button>
              <el-button size="small" text type="danger" @click="doDeleteSub(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="sub-actions">
          <el-button size="small" @click="openCreateSub('-')">新增直属子指标</el-button>
          <el-button v-if="selectedSub && selectedSub.parentSubKey === '-'" size="small"
            @click="openCreateSub(selectedSub.subKey)">为「{{ selectedSub.subKey }}」新增子层</el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="block" v-if="selectedDimKey">
        <template #header>
          <div class="dim-header">
            <span class="block-title">
              计算规则 · {{ selectedDimKey }}
              <span v-if="selectedSubKey"> · {{ selectedSubKey }}</span>
              <span class="rule-hint">（{{ isFiveDim ? '五维引擎读它：改档位阈值 = 改分数' : '旧 ultra_short 登记用，引擎不读' }}）</span>
            </span>
            <el-button v-if="selectedSubKey" size="small" text @click="selectedSubKey = null">显示整维全部规则</el-button>
          </div>
        </template>
        <el-table :data="dimRules" style="width: 100%">
          <el-table-column prop="subKey" label="子档" width="120" />
          <el-table-column prop="ruleNo" label="序" width="70" />
          <el-table-column prop="operator" label="算子" width="110" />
          <el-table-column prop="thresholdLow" label="下界" width="90" />
          <el-table-column prop="thresholdHigh" label="上界" width="90" />
          <el-table-column prop="score" label="给分" width="80" />
          <el-table-column prop="formula" label="判据(人话)" min-width="180" show-overflow-tooltip />
          <el-table-column prop="note" label="备注" min-width="150" show-overflow-tooltip />
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text @click="openEditRule(row)">编辑</el-button>
              <el-button size="small" text type="danger" @click="doDeleteRule(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" style="margin-top: 12px" @click="openCreateRule">新增规则</el-button>
      </el-card>
    </template>
    <el-empty v-else description="点上面任一模型查看它的维度与规则" />

    <!-- 模型弹框 -->
    <el-dialog v-model="modelDialog.visible" :title="modelDialog.isEdit ? '编辑模型' : '新增模型'" width="480px">
      <el-form :model="modelDialog.form" label-position="top">
        <el-form-item label="标识 model_key（幂等种子按它认行，建好别改）">
          <el-input v-model="modelDialog.form.modelKey" :disabled="modelDialog.isEdit" placeholder="如 ultra_short" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="modelDialog.form.name" placeholder="如 超短情绪模型" />
        </el-form-item>
        <el-form-item label="温度分母 M（留空=按权重和×每维满分3现推；写死要正数）">
          <el-input-number v-model="modelDialog.form.maxScore" :min="0.01" :precision="2" :value-on-clear="null"
            controls-position="right" style="width: 100%" placeholder="留空即现推" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="modelDialog.form.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveModel">确定</el-button>
      </template>
    </el-dialog>

    <!-- 维度弹框 -->
    <el-dialog v-model="dimDialog.visible" :title="dimDialog.isEdit ? '编辑维度' : '新增维度'" width="560px">
      <el-form :model="dimDialog.form" label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="维键 dim_key（引擎只认这九个）">
              <el-select v-model="dimDialog.form.dimKey" :disabled="dimDialog.isEdit" style="width: 100%">
                <el-option v-for="k in DIM_KEYS" :key="k" :label="k" :value="k" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="维序 dim_no（1..9，界面印第N维）">
              <el-input-number v-model="dimDialog.form.dimNo" :min="1" :max="20" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="卡面名 label">
              <el-input v-model="dimDialog.form.label" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="权重 weight">
              <el-input-number v-model="dimDialog.form.weight" :min="0" :max="10" :step="0.5" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="卡片展示序 sort_no（留空不上卡）">
              <el-input-number v-model="dimDialog.form.sortNo" :min="1" :max="20" :value-on-clear="null" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分列 record_column">
              <el-input v-model="dimDialog.form.recordColumn" placeholder="如 score_height，可留空" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="合成方式 rule_engine">
              <el-select v-model="dimDialog.form.ruleEngine" style="width: 100%">
                <el-option v-for="e in RULE_ENGINES" :key="e" :label="e" :value="e" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="dimDialog.form.note" type="textarea" :rows="2" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dimDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveDim">确定</el-button>
      </template>
    </el-dialog>

    <!-- 规则弹框 -->
    <el-dialog v-model="ruleDialog.visible" :title="ruleDialog.isEdit ? '编辑规则' : '新增规则'" width="560px">
      <el-form :model="ruleDialog.form" label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="子档 sub_key（单套阶梯用 -）">
              <el-input v-model="ruleDialog.form.subKey" placeholder="-" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="命中顺序 rule_no（小的先判）">
              <el-input-number v-model="ruleDialog.form.ruleNo" :min="1" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="算子 operator">
              <el-select v-model="ruleDialog.form.operator" style="width: 100%">
                <el-option v-for="o in OPERATORS" :key="o" :label="o" :value="o" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="给分 score（GUARD/GROUP/AGG 留空）">
              <el-input-number v-model="ruleDialog.form.score" :min="0" :max="100" :value-on-clear="null" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="阈值下界">
              <el-input-number v-model="ruleDialog.form.thresholdLow" :precision="2" :value-on-clear="null"
                controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="阈值上界">
              <el-input-number v-model="ruleDialog.form.thresholdHigh" :precision="2" :value-on-clear="null"
                controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="判据 formula（压不进三元组的用人话写）">
              <el-input v-model="ruleDialog.form.formula" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="ruleDialog.form.note" type="textarea" :rows="2" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveRule">确定</el-button>
      </template>
    </el-dialog>

    <!-- 子指标 / 层弹框 -->
    <el-dialog v-model="subDialog.visible" :title="subDialog.isEdit ? '编辑子指标' : '新增子指标'" width="560px">
      <el-form :model="subDialog.form" label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="所属维 dim_key">
              <el-input :model-value="subDialog.form.dimKey" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="父 sub_key（'-' 直属维；非 '-' 表示挂在某复合子下的层）">
              <el-input v-model="subDialog.form.parentSubKey" :disabled="subDialog.isEdit" placeholder="-" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="子键 sub_key（同一父下唯一，建好别改）">
              <el-input v-model="subDialog.form.subKey" :disabled="subDialog.isEdit" placeholder="如 jr_mid" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="显示名 label">
              <el-input v-model="subDialog.form.label" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="权重 weight（0..1；维内/层内各自和=1）">
              <el-input-number v-model="subDialog.form.weight" :min="0" :max="1" :step="0.05" :precision="4" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="卡序 sort_no（留空不上卡）">
              <el-input-number v-model="subDialog.form.sortNo" :min="1" :max="30" :value-on-clear="null" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="打分方式 scoring_kind">
              <el-select v-model="subDialog.form.scoringKind" style="width: 100%">
                <el-option v-for="k in SCORING_KINDS" :key="k" :label="k" :value="k" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="读数键 source_key（引擎从 metrics map 取值；MANUAL 对应 manual_* 列名）">
              <el-input v-model="subDialog.form.sourceKey" placeholder="如 jr_mid / turnover_ratio / manual_sector_limit_up" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="subDialog.form.note" type="textarea" :rows="2" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="subDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveSub">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { scoringApi } from '../api/modules'
import { useScoringStore } from '../stores/scoring'

const DIM_KEYS = ['height', 'premium', 'breadth', 'broken', 'loss', 'volume', 'theme', 'anchor', 'surv',
  'market', 'theme_main', 'board', 'first']
const RULE_ENGINES = ['THRESHOLD_BAND', 'WEIGHTED_SUB_BANDS', 'SUBITEM_AVERAGE', 'WORST_OF_MANY', 'MANUAL_PASSTHROUGH',
  'WEIGHTED_SUM', 'BAND_LADDER', 'LAYER_WEIGHTED_BAND', 'STRATEGY', 'MANUAL']
const OPERATORS = ['GTE', 'GT', 'LTE', 'LT', 'EQ', 'BETWEEN', 'ELSE', 'GUARD', 'GROUP', 'AGG', 'COMPOUND']
const SCORING_KINDS = ['WEIGHTED_SUM', 'BAND_LADDER', 'LAYER_WEIGHTED_BAND', 'STRATEGY', 'MANUAL']

const scoringStore = useScoringStore()

const models = ref([])
const detail = ref(null)
const selectedId = ref(null)
const selectedDimKey = ref(null)
const selectedSubKey = ref(null)
const selectedSub = ref(null)

const dimRules = computed(() => {
  const rules = detail.value?.rules || []
  return rules
    .filter((r) => r.dimKey === selectedDimKey.value)
    .filter((r) => !selectedSubKey.value || r.subKey === selectedSubKey.value)
    .slice()
    .sort((a, b) => (a.subKey === b.subKey ? a.ruleNo - b.ruleNo : String(a.subKey).localeCompare(String(b.subKey))))
})

const isFiveDim = computed(() => {
  const k = detail.value?.model?.modelKey
  return k === 'five_dim'
})

const fmt = (v) => (v == null ? '—' : Number(v).toFixed(2))

/** 选中维的所有 subs：先按 parentSubKey='-' 出直属，再把每个直属的 children 紧跟其后（层级视觉扁平化）。 */
const dimSubs = computed(() => {
  const all = detail.value?.subs || []
  const mine = all.filter((s) => s.dimKey === selectedDimKey.value)
  const tops = mine.filter((s) => (s.parentSubKey || '-') === '-').sort((a, b) => (a.sortNo || 0) - (b.sortNo || 0))
  const out = []
  for (const t of tops) {
    out.push({ ...t, parentSubKey: '-' })
    mine.filter((s) => s.parentSubKey === t.subKey)
      .sort((a, b) => (a.sortNo || 0) - (b.sortNo || 0))
      .forEach((c) => out.push(c))
  }
  return out
})

async function loadModels() {
  const res = await scoringApi.listModels()
  models.value = res.data || []
  if (selectedId.value && !models.value.some((m) => m.id === selectedId.value)) {
    selectedId.value = null
    detail.value = null
    selectedDimKey.value = null
  }
}

async function loadDetail(id) {
  const res = await scoringApi.model(id)
  detail.value = res.data
  selectedId.value = id
}

function onModelRowClick(row) {
  if (row) loadDetail(row.id)
}

function showRules(row) {
  selectedDimKey.value = row.dimKey
  selectedSubKey.value = null
  selectedSub.value = null
}

function onDimRowClick(row) {
  if (row) { selectedDimKey.value = row.dimKey; selectedSubKey.value = null; selectedSub.value = null }
}

function onSubRowClick(row) {
  if (!row) return
  selectedSub.value = row
  selectedSubKey.value = row.subKey
}

function showSubLadder(row) {
  selectedSub.value = row
  selectedSubKey.value = row.subKey
}

async function refreshCards() {
  await scoringStore.load(true)
}

// ---- 模型 CRUD ----
const modelDialog = reactive({ visible: false, isEdit: false, form: {} })

function openCreateModel() {
  modelDialog.isEdit = false
  modelDialog.form = { modelKey: '', name: '', maxScore: null, note: '' }
  modelDialog.visible = true
}

function openEditModel(row) {
  modelDialog.isEdit = true
  modelDialog.form = { id: row.id, modelKey: row.modelKey, name: row.name, maxScore: row.maxScore, note: row.note }
  modelDialog.visible = true
}

async function saveModel() {
  const f = modelDialog.form
  try {
    if (modelDialog.isEdit) {
      await scoringApi.updateModel(f.id, f)
      ElMessage.success('已保存模型')
    } else {
      const created = await scoringApi.createModel({ ...f, active: false })
      ElMessage.success('已创建（默认未激活，点“激活”生效）')
      await loadModels()
      await loadDetail(created.data.id)
    }
    modelDialog.visible = false
    if (modelDialog.isEdit && f.id === selectedId.value) await loadDetail(f.id)
  } catch (e) { /* 拦截器已弹错 */ }
}

async function doActivate(row) {
  try {
    await scoringApi.activate(row.id)
    ElMessage.success(`已激活 ${row.name}`)
    await loadModels()
    await loadDetail(row.id)
    await refreshCards()
  } catch (e) { /* noop */ }
}

async function doDeleteModel(row) {
  try {
    await ElMessageBox.confirm(`删除模型「${row.name}」会级联删掉它的全部维度与规则，确定？`, '确认删除', { type: 'warning' })
  } catch { return }
  try {
    await scoringApi.deleteModel(row.id)
    ElMessage.success('已删除')
    if (selectedId.value === row.id) { detail.value = null; selectedId.value = null; selectedDimKey.value = null }
    await loadModels()
    await refreshCards()
  } catch (e) { /* noop */ }
}

// ---- 维度 CRUD ----
const dimDialog = reactive({ visible: false, isEdit: false, form: {} })

function openCreateDim() {
  if (!selectedId.value) { ElMessage.warning('请先选中一个模型'); return }
  dimDialog.isEdit = false
  dimDialog.form = {
    modelId: selectedId.value, dimKey: '', dimNo: (detail.value?.dims?.length || 0) + 1,
    label: '', weight: 1, sortNo: null, recordColumn: '', ruleEngine: 'THRESHOLD_BAND', note: ''
  }
  dimDialog.visible = true
}

function openEditDim(row) {
  dimDialog.isEdit = true
  dimDialog.form = { ...row }
  dimDialog.visible = true
}

async function saveDim() {
  const f = dimDialog.form
  try {
    if (dimDialog.isEdit) await scoringApi.updateDim(f.id, f)
    else await scoringApi.createDim(f)
    ElMessage.success('已保存维度')
    dimDialog.visible = false
    await loadDetail(selectedId.value)
    await refreshCards()
  } catch (e) { /* noop */ }
}

async function saveDimWeight(row) {
  try {
    await scoringApi.updateDim(row.id, row)
    ElMessage.success(`${row.label} 权重已更新`)
    await loadDetail(selectedId.value)
    await refreshCards()
  } catch (e) { /* noop */ }
}

async function doDeleteDim(row) {
  try {
    await ElMessageBox.confirm(`删除维度「${row.label}」会一并删掉它登记的规则，确定？`, '确认删除', { type: 'warning' })
  } catch { return }
  try {
    await scoringApi.deleteDim(row.id)
    ElMessage.success('已删除')
    if (selectedDimKey.value === row.dimKey) selectedDimKey.value = null
    await loadDetail(selectedId.value)
    await refreshCards()
  } catch (e) { /* noop */ }
}

// ---- 子指标 / 层 CRUD ----
const subDialog = reactive({ visible: false, isEdit: false, form: {} })

function openCreateSub(parentSubKey) {
  if (!selectedId.value || !selectedDimKey.value) { ElMessage.warning('请先选一个模型和一个维度'); return }
  subDialog.isEdit = false
  subDialog.form = {
    modelId: selectedId.value, dimKey: selectedDimKey.value,
    subKey: '', parentSubKey: parentSubKey || '-', label: '',
    weight: 0.2, scoringKind: 'BAND_LADDER', sourceKey: '', sortNo: null, note: ''
  }
  subDialog.visible = true
}

function openEditSub(row) {
  subDialog.isEdit = true
  subDialog.form = { ...row }
  subDialog.visible = true
}

async function saveSub() {
  const f = subDialog.form
  try {
    if (subDialog.isEdit) await scoringApi.updateSub(f.id, f)
    else await scoringApi.createSub(f)
    ElMessage.success('已保存子指标')
    subDialog.visible = false
    await loadDetail(selectedId.value)
    await refreshCards()
  } catch (e) { /* noop */ }
}

async function saveSubWeight(row) {
  try {
    await scoringApi.updateSub(row.id, row)
    ElMessage.success(`${row.label || row.subKey} 权重已更新`)
    await loadDetail(selectedId.value)
    await refreshCards()
  } catch (e) { /* noop */ }
}

async function doDeleteSub(row) {
  try {
    await ElMessageBox.confirm(`删除子指标「${row.label || row.subKey}」会级联删掉它下面的层与阶梯，确定？`, '确认删除', { type: 'warning' })
  } catch { return }
  try {
    await scoringApi.deleteSub(row.id)
    ElMessage.success('已删除')
    if (selectedSubKey.value === row.subKey) { selectedSubKey.value = null; selectedSub.value = null }
    await loadDetail(selectedId.value)
    await refreshCards()
  } catch (e) { /* noop */ }
}

// ---- 规则 CRUD ----
const ruleDialog = reactive({ visible: false, isEdit: false, form: {} })

function openCreateRule() {
  if (!selectedDimKey.value) { ElMessage.warning('请先选一个维度看它的规则'); return }
  ruleDialog.isEdit = false
  ruleDialog.form = {
    modelId: selectedId.value, dimKey: selectedDimKey.value,
    subKey: selectedSubKey.value || '-', ruleNo: 1,
    operator: 'GTE', thresholdLow: null, thresholdHigh: null, score: null, formula: '', note: ''
  }
  ruleDialog.visible = true
}

function openEditRule(row) {
  ruleDialog.isEdit = true
  ruleDialog.form = { ...row }
  ruleDialog.visible = true
}

async function saveRule() {
  const f = ruleDialog.form
  try {
    if (ruleDialog.isEdit) await scoringApi.updateRule(f.id, f)
    else await scoringApi.createRule(f)
    ElMessage.success('已保存规则')
    ruleDialog.visible = false
    await loadDetail(selectedId.value)
    // 阶梯阈值是复盘页/卡片 tooltip 命中档的直接来源；只刷管理页自己的 detail 会让别的页面停在旧配置上
    await refreshCards()
  } catch (e) { /* noop */ }
}

async function doDeleteRule(row) {
  try {
    await scoringApi.deleteRule(row.id)
    ElMessage.success('已删除')
    await loadDetail(selectedId.value)
    await refreshCards()
  } catch (e) { /* noop */ }
}

onMounted(async () => {
  await loadModels()
  const active = models.value.find((m) => m.active) || models.value[0]
  if (active) await loadDetail(active.id)
})
</script>

<style scoped>
.scoring-admin {
  max-width: 1100px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.page-header h2 {
  margin: 0;
  color: #e1e8ed;
}
.hint {
  color: #8899a6;
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 20px;
}
.block {
  margin-bottom: 20px;
}
.sub-leaf {
  color: #8899a6;
  padding-left: 12px;
  display: inline-block;
}
.sub-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
.rule-hint {
  color: #8899a6;
  font-size: 12px;
  font-weight: 400;
  margin-left: 8px;
}
.block-title {
  font-weight: 600;
  color: #e1e8ed;
}
.dim-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.formula {
  color: #d97706;
  font-size: 13px;
}
</style>
