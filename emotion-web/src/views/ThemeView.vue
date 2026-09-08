<template>
  <div class="theme-page">
    <div class="page-header">
      <h2>主线龙头追踪</h2>
      <div class="header-tools">
        <el-date-picker v-model="viewDate" type="date" value-format="YYYY-MM-DD" :clearable="false"
                        size="small" style="width: 140px" @change="loadDay" />
        <el-button type="primary" @click="showThemeDialog = true">新增主线</el-button>
      </div>
    </div>

    <div class="chart-block">
      <div class="chart-head">
        <h3>情绪温度</h3>
        <el-radio-group v-model="days" size="small" @change="loadCurve">
          <el-radio-button :value="20">近20日</el-radio-button>
          <el-radio-button :value="60">近60日</el-radio-button>
        </el-radio-group>
      </div>
      <TemperatureChart :data="curveData" />
    </div>

    <AnchorSpanChart class="span-block" :iso-dates="isoDates" :spans="spans" :series="anchorSeries" />

    <p v-if="recalcing" class="recalc-hint">阵眼改了，正在按新的第 8 维重算全期温度与段号…</p>
    <AnchorPanel :record="viewRecord" :anchor="anchorDay" @changed="afterAnchorChange" />

    <div class="theme-list" v-if="themes.length">
      <div v-for="theme in themes" :key="theme.id" class="theme-card">
        <div class="theme-header">
          <div class="theme-info">
            <h3>{{ theme.name }}</h3>
            <el-tag :type="themeStatusType(theme.status)" size="small">{{ theme.status }}</el-tag>
          </div>
          <div class="theme-meta">
            <span>启动：{{ theme.startDate }}</span>
            <span>强度：{{ theme.strength }}%</span>
          </div>
        </div>
        <el-progress :percentage="theme.strength" :color="getStrengthColor(theme.strength)"
          :show-text="false" style="margin: 12px 0" />

        <div class="stock-section">
          <div class="stock-header">
            <span>龙头股</span>
            <el-button text size="small" @click="openStockDialog(theme.id)">+ 添加</el-button>
          </div>
          <div class="stock-list">
            <div v-for="stock in getStocks(theme.id)" :key="stock.id" class="stock-item">
              <span class="stock-name">{{ stock.name }}</span>
              <el-tag size="small" :type="roleTagType(stock.role)">{{ stock.role }}</el-tag>
              <span class="stock-board" v-if="stock.maxConsecutive">{{ stock.maxConsecutive }}板</span>
              <span class="stock-status">{{ stock.status }}</span>
            </div>
            <div v-if="!getStocks(theme.id).length" class="no-stock">暂无龙头</div>
          </div>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无主线，点击右上角添加" />

    <el-dialog v-model="showThemeDialog" title="新增主线" width="460px">
      <el-form :model="themeForm" label-position="top">
        <el-form-item label="题材名称">
          <el-input v-model="themeForm.name" placeholder="如：AI、新能源、医药" />
        </el-form-item>
        <el-form-item label="启动日期">
          <el-date-picker v-model="themeForm.startDate" type="date" value-format="YYYY-MM-DD"
            style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="themeForm.status" style="width: 100%">
            <el-option label="萌芽" value="萌芽" />
            <el-option label="确认" value="确认" />
            <el-option label="扩散" value="扩散" />
            <el-option label="亢奋" value="亢奋" />
            <el-option label="退潮" value="退潮" />
          </el-select>
        </el-form-item>
        <el-form-item label="强度(0-100)">
          <el-slider v-model="themeForm.strength" :max="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showThemeDialog = false">取消</el-button>
        <el-button type="primary" @click="handleCreateTheme">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showStockDialog" title="添加龙头股" width="460px">
      <el-form :model="stockForm" label-position="top">
        <el-form-item label="股票名称">
          <el-input v-model="stockForm.name" placeholder="股票名称" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="stockForm.role" style="width: 100%">
            <el-option label="总龙头" value="总龙头" />
            <el-option label="中军" value="中军" />
            <el-option label="跟风" value="跟风" />
            <el-option label="卡位" value="卡位" />
            <el-option label="反包龙" value="反包龙" />
          </el-select>
        </el-form-item>
        <el-form-item label="最高连板">
          <el-input-number v-model="stockForm.maxConsecutive" :min="0" :max="30" />
        </el-form-item>
        <el-form-item label="状态">
          <el-input v-model="stockForm.status" placeholder="如：加速中、已断板" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showStockDialog = false">取消</el-button>
        <el-button type="primary" @click="handleAddStock">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { themeApi, recordApi, anchorApi } from '../api/modules'
import { ElMessage } from 'element-plus'
import TemperatureChart from '../components/TemperatureChart.vue'
import AnchorSpanChart from '../components/AnchorSpanChart.vue'
import AnchorPanel from '../components/AnchorPanel.vue'

const themes = ref([])
const stocksMap = ref({})
const showThemeDialog = ref(false)
const showStockDialog = ref(false)
const currentThemeId = ref(null)

const days = ref(20)
const viewDate = ref('')
const curveData = ref({ dates: [], temperatures: [], stages: [] })
const spans = ref([])
const anchorSeries = ref([])
const anchorDay = ref(null)
const recalcing = ref(false)

// 跨度色带要对齐温度曲线的轴，轴的本体是 ISO 串（MM/dd 跨年不可序）
const isoDates = computed(() => {
  const c = curveData.value
  return c.isoDates && c.isoDates.length === c.dates.length ? c.isoDates : c.dates
})
const viewRecord = computed(() => (viewDate.value ? { tradeDate: viewDate.value } : null))

const themeForm = reactive({
  name: '',
  startDate: '',
  status: '萌芽',
  strength: 50
})

const stockForm = reactive({
  name: '',
  role: '总龙头',
  maxConsecutive: 0,
  status: ''
})

function themeStatusType(status) {
  const map = { '萌芽': '', '确认': 'success', '扩散': 'warning', '亢奋': 'danger', '退潮': 'info' }
  return map[status] || ''
}

function roleTagType(role) {
  const map = { '总龙头': 'danger', '中军': 'warning', '跟风': '', '卡位': 'success', '反包龙': 'info' }
  return map[role] || ''
}

function getStrengthColor(val) {
  if (val < 30) return '#3b82f6'
  if (val < 60) return '#f59e0b'
  return '#ef4444'
}

function getStocks(themeId) {
  return stocksMap.value[themeId] || []
}

function openStockDialog(themeId) {
  currentThemeId.value = themeId
  stockForm.name = ''
  stockForm.role = '总龙头'
  stockForm.maxConsecutive = 0
  stockForm.status = ''
  showStockDialog.value = true
}

async function loadThemes() {
  try {
    const res = await themeApi.list()
    themes.value = Array.isArray(res.data) ? res.data : (res.data?.list || [])
    for (const t of themes.value) {
      try {
        const sRes = await themeApi.listStocks(t.id)
        stocksMap.value[t.id] = Array.isArray(sRes.data) ? sRes.data : (sRes.data?.list || [])
      } catch (e) { /* ignore */ }
    }
  } catch (e) { /* ignore */ }
}

async function loadCurve() {
  try {
    const res = await recordApi.getCurve(days.value)
    curveData.value = res.data
  } catch (e) { /* ignore */ }
  // 查看日先落在有数据的最后一天：默认今天的话，周末进来第 8 维就是一句"当日无行情"
  if (!viewDate.value) {
    const dates = isoDates.value
    viewDate.value = dates.length ? dates[dates.length - 1] : new Date().toLocaleDateString('en-CA')
  }
  loadSpans()
}

/** 跨度与阵眼序列只跟窗口有关，失败就等于图上没有那两条，不影响别的块。 */
async function loadSpans() {
  try {
    const res = await anchorApi.spans(days.value + 15)
    spans.value = res.data || []
  } catch (e) { /* ignore */ }
  try {
    const res = await anchorApi.series(days.value + 15)
    anchorSeries.value = res.data || []
  } catch (e) { /* 上游抽风：柱子没有，跨度底色照画 */ }
}

async function loadDay() {
  if (!viewDate.value) {
    anchorDay.value = null
    return
  }
  anchorDay.value = null
  try {
    const res = await anchorApi.list(viewDate.value)
    anchorDay.value = res.data
  } catch (e) { /* 未设阵眼就是 available=false，这里只管请求本身失败 */ }
}

/**
 * 阵眼一改，跨度里每一天的第 8 维都跟着变、段号整条重排，只重算当天会让曲线上的点
 * 各自停留在两套口径里。改完还要把窗口重新拉一遍——新阵眼的跨度可能整个在窗口外。
 */
async function afterAnchorChange() {
  recalcing.value = true
  try {
    await recordApi.recalcAll()
  } catch (e) { /* 重算失败：下面照常按库里的现状刷新 */ }
  finally {
    recalcing.value = false
  }
  await loadCurve()
  loadDay()
}

async function handleCreateTheme() {
  if (!themeForm.name) {
    ElMessage.warning('请输入题材名称')
    return
  }
  try {
    await themeApi.create(themeForm)
    ElMessage.success('添加成功')
    showThemeDialog.value = false
    loadThemes()
  } catch (e) {
    ElMessage.error('添加失败')
  }
}

async function handleAddStock() {
  if (!stockForm.name) {
    ElMessage.warning('请输入股票名称')
    return
  }
  try {
    await themeApi.addStock(currentThemeId.value, stockForm)
    ElMessage.success('添加成功')
    showStockDialog.value = false
    loadThemes()
  } catch (e) {
    ElMessage.error('添加失败')
  }
}

onMounted(() => {
  loadThemes()
  loadCurve().then(loadDay)
})
</script>

<style scoped>
.theme-page {
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
.header-tools {
  display: flex;
  align-items: center;
  gap: 12px;
}
.chart-block {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}
.chart-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.chart-head h3 {
  margin: 0;
  color: #e1e8ed;
  font-size: 15px;
}
.span-block {
  margin-bottom: 20px;
}
.recalc-hint {
  margin: -6px 0 14px;
  font-size: 12px;
  color: #fbbf24;
}
.theme-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.theme-card {
  background: #1a2332;
  border-radius: 12px;
  padding: 24px;
}
.theme-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.theme-info {
  display: flex;
  align-items: center;
  gap: 12px;
}
.theme-info h3 {
  margin: 0;
  color: #e1e8ed;
  font-size: 18px;
}
.theme-meta {
  display: flex;
  gap: 16px;
  color: #8899a6;
  font-size: 13px;
}
.stock-section {
  margin-top: 16px;
}
.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.stock-header span {
  color: #8899a6;
  font-size: 14px;
  font-weight: 600;
}
.stock-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.stock-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: #0f1419;
  border-radius: 8px;
}
.stock-name {
  color: #e1e8ed;
  font-weight: 600;
  min-width: 80px;
}
.stock-board {
  color: #f59e0b;
  font-size: 13px;
  font-weight: 600;
}
.stock-status {
  color: #8899a6;
  font-size: 13px;
  margin-left: auto;
}
.no-stock {
  color: #4a5568;
  font-size: 13px;
  padding: 8px;
}
</style>
