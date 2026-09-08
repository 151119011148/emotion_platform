<template>
  <div class="history-page">
    <div class="page-header">
      <h2>历史回看</h2>
      <el-date-picker v-model="month" type="month" value-format="YYYY-MM" placeholder="选择月份"
        @change="loadMonth" style="width: 180px" />
    </div>

    <div class="calendar-grid">
      <div class="weekday-header">
        <span v-for="d in weekdays" :key="d">{{ d }}</span>
      </div>
      <div class="calendar-cells">
        <div v-for="(cell, idx) in calendarCells" :key="idx"
          class="calendar-cell" :class="{ empty: !cell.date, clickable: cell.date }"
          @click="cell.date && selectDate(cell.date)">
          <template v-if="cell.date">
            <span class="cell-date">{{ cell.day }}</span>
            <div v-if="cell.record" class="cell-temp"
              :class="{ missing: cell.record.temperature == null }"
              :style="{ background: cellColor(cell.record) }">
              {{ cell.record.temperature == null ? '未出' : Number(cell.record.temperature).toFixed(0) + '°' }}
            </div>
            <span class="cell-stage" v-if="cell.record?.stage">{{ cell.record.stage }}</span>
          </template>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="selectedDate" width="600px">
      <div v-if="selectedRecord" class="detail-dialog">
        <div class="detail-header">
          <span class="detail-temp" :style="{ color: cellColor(selectedRecord) }">
            {{ selectedRecord.temperature == null ? '未出' : Number(selectedRecord.temperature).toFixed(1) + '°' }}
          </span>
          <el-tag v-if="selectedRecord.stage">{{ selectedRecord.stage }}</el-tag>
          <span v-else class="detail-stage-missing">
            仅 {{ selectedRecord.scoredDims || 0 }} 维参与打分，不足 5 维不出阶段
          </span>
          <span class="detail-score">{{ scoreText }}</span>
        </div>

        <el-descriptions :column="3" border size="small" class="detail-desc">
          <el-descriptions-item label="连板高度">{{ selectedRecord.maxConsecutiveLimit }}</el-descriptions-item>
          <el-descriptions-item label="涨停家数">{{ selectedRecord.limitUpCount }}</el-descriptions-item>
          <el-descriptions-item label="跌停家数">{{ selectedRecord.limitDownCount }}</el-descriptions-item>
          <el-descriptions-item label="昨日溢价">{{ selectedRecord.yesterdayLimitPremium }}%</el-descriptions-item>
          <el-descriptions-item label="炸板率">{{ selectedRecord.brokenBoardRate }}%</el-descriptions-item>
          <el-descriptions-item label="大面数">{{ selectedRecord.bigLossCount }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-theme" v-if="selectedRecord.mainTheme">
          <span>主线：{{ selectedRecord.mainTheme }}</span>
          <span>龙头：{{ selectedRecord.leadingStock }}</span>
          <span>状态：{{ selectedRecord.leadingStockStatus }}</span>
        </div>

        <div class="detail-notes" v-if="selectedRecord.rotationNote">
          <h4>轮动观察</h4>
          <p>{{ selectedRecord.rotationNote }}</p>
        </div>
        <div class="detail-notes" v-if="selectedRecord.reviewNote">
          <h4>对答案</h4>
          <p>{{ selectedRecord.reviewNote }}</p>
        </div>
        <div class="detail-notes" v-if="selectedRecord.tomorrowPlan">
          <h4>明日计划</h4>
          <p>{{ selectedRecord.tomorrowPlan }}</p>
        </div>
      </div>
      <el-empty v-else description="该日无记录" />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { recordApi } from '../api/modules'
import { stageColorOf } from '../utils/stages'

const weekdays = ['一', '二', '三', '四', '五', '六', '日']
// 不能用 toISOString()：那是 UTC，每月头一天早上会打开上一个月的日历
const month = ref(new Date().toLocaleDateString('en-CA').slice(0, 7))
const monthRecords = ref([])
const dialogVisible = ref(false)
const selectedDate = ref('')
const selectedRecord = ref(null)

const recordMap = computed(() => {
  const map = {}
  monthRecords.value.forEach(r => {
    const d = typeof r.tradeDate === 'string' ? r.tradeDate : r.tradeDate?.slice(0, 10)
    if (d) map[d] = r
  })
  return map
})

const calendarCells = computed(() => {
  const [y, m] = month.value.split('-').map(Number)
  const firstDay = new Date(y, m - 1, 1)
  const lastDay = new Date(y, m, 0)
  let startWeekday = firstDay.getDay()
  if (startWeekday === 0) startWeekday = 7
  const cells = []
  for (let i = 1; i < startWeekday; i++) {
    cells.push({ date: null })
  }
  for (let d = 1; d <= lastDay.getDate(); d++) {
    const dateStr = `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
    cells.push({
      date: dateStr,
      day: d,
      record: recordMap.value[dateStr] || null
    })
  }
  return cells
})

function cellColor(record) {
  // 按阶段着色而不是按温度水位：分歧/退潮 是"运动状态"，
  // 用温度分档会给一个退潮的日子涂上启动绿，和旁边那两个字当场打架。
  return stageColorOf(record?.stage)
}

/** 分母跟着参与打分的维数走，写死 21 会把"没评"说成"分了很低"。 */
const scoreText = computed(() => {
  const r = selectedRecord.value
  if (!r || r.totalScore == null) return '未打分'
  const dims = r.scoredDims || 0
  return `${r.totalScore} / ${dims * 3}（${dims} 维）`
})

async function loadMonth() {
  if (!month.value) return
  const [y, m] = month.value.split('-').map(Number)
  const start = `${y}-${String(m).padStart(2, '0')}-01`
  const lastDay = new Date(y, m, 0).getDate()
  const end = `${y}-${String(m).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`
  try {
    const res = await recordApi.getRange(start, end)
    monthRecords.value = Array.isArray(res.data) ? res.data : (res.data?.records || [])
  } catch (e) { /* ignore */ }
}

function selectDate(date) {
  selectedDate.value = date
  selectedRecord.value = recordMap.value[date] || null
  dialogVisible.value = true
}

onMounted(() => {
  loadMonth()
})
</script>

<style scoped>
.history-page {
  max-width: 1000px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}
.page-header h2 {
  margin: 0;
  color: #e1e8ed;
}
.calendar-grid {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
}
.weekday-header {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  text-align: center;
  margin-bottom: 8px;
}
.weekday-header span {
  color: #8899a6;
  font-size: 13px;
  padding: 8px 0;
}
.calendar-cells {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 4px;
}
.calendar-cell {
  aspect-ratio: 1;
  border-radius: 8px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 80px;
}
.calendar-cell.empty {
  background: transparent;
}
.calendar-cell.clickable {
  background: #0f1419;
  cursor: pointer;
  transition: background 0.2s;
}
.calendar-cell.clickable:hover {
  background: #2d3748;
}
.cell-date {
  font-size: 13px;
  color: #8899a6;
}
.cell-temp {
  font-size: 18px;
  font-weight: 700;
  color: white;
  padding: 2px 8px;
  border-radius: 6px;
  margin: 4px 0;
}
/* "未出" 两个字按 18px 会把格子撑开，缩到和阶段标签同一档 */
.cell-temp.missing {
  font-size: 12px;
  padding: 3px 8px;
}
.detail-stage-missing {
  color: #8899a6;
  font-size: 13px;
}
.cell-stage {
  font-size: 11px;
  color: #8899a6;
}

.detail-dialog {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.detail-header {
  display: flex;
  align-items: center;
  gap: 16px;
}
.detail-temp {
  font-size: 36px;
  font-weight: 700;
}
.detail-score {
  color: #8899a6;
  font-size: 14px;
}
.detail-theme {
  display: flex;
  gap: 16px;
  color: #e1e8ed;
  font-size: 14px;
}
.detail-notes {
  background: #f7f8fa;
  border-radius: 8px;
  padding: 12px;
}
.detail-notes h4 {
  margin: 0 0 8px;
  color: #333;
  font-size: 13px;
}
.detail-notes p {
  margin: 0;
  color: #555;
  font-size: 14px;
  line-height: 1.6;
}
</style>
