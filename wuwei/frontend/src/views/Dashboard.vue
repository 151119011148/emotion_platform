<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px">
      <span class="page-title" style="margin: 0">P1 仪表盘 {{ vo.tradeDate ? `（${vo.tradeDate}）` : '' }}</span>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="10">
        <el-card class="page-card">
          <template #header><b>五维评分</b></template>
          <div ref="radarRef" style="height: 300px" />
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card class="page-card">
          <template #header><b>总分与情绪节点</b></template>
          <el-row :gutter="12" style="margin-bottom: 12px">
            <el-col :span="6">
              <el-statistic title="今日总分" :value="vo.totalScore ?? 0" :precision="1">
                <template #suffix><span style="font-size:13px;color:#909399"> /100</span></template>
              </el-statistic>
            </el-col>
            <el-col :span="6">
              <el-statistic title="昨日总分" :value="vo.prevTotal ?? 0" :precision="1" />
            </el-col>
            <el-col :span="6">
              <div style="margin-bottom:4px" class="muted">当前节点</div>
              <el-tag :color="nodeColor(vo.node)" effect="dark" style="border:none;font-size:15px;font-weight:700">{{ vo.node || '—' }}</el-tag>
            </el-col>
            <el-col :span="6">
              <div style="margin-bottom:4px" class="muted">节点切换</div>
              <span style="font-weight:600">{{ vo.transition || '—' }}</span>
            </el-col>
          </el-row>
          <div style="background:#f5f7fa;border-radius:6px;padding:10px 12px;margin-bottom:8px">
            <b>触发依据：</b>{{ vo.triggerReason || '—' }}
          </div>
          <div style="background:#f5f7fa;border-radius:6px;padding:10px 12px;margin-bottom:8px">
            <b>明日推演：</b>{{ vo.forecast || '—' }}
          </div>
          <div v-if="vo.forceExit" style="background:#fef0f0;border-radius:6px;padding:10px 12px;color:#F56C6C;font-weight:700">
            ⚠️ 强制退潮：{{ vo.forceReason }}
          </div>
          <div v-if="vo.watchPoints && vo.watchPoints.length" style="margin-top:8px">
            <b>观察哨：</b>
            <el-tag v-for="(w, i) in vo.watchPoints" :key="i" type="warning" effect="plain" style="margin: 2px 6px 2px 0">{{ w }}</el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="page-card">
      <template #header><b>操作指令</b></template>
      <el-row :gutter="12">
        <el-col :span="6"><div class="muted">仓位建议</div><b style="font-size:16px">{{ instruction.position || '—' }}</b></el-col>
        <el-col :span="6"><div class="muted">进攻方向</div><b style="font-size:16px">{{ instruction.direction || '—' }}</b></el-col>
        <el-col :span="6"><div class="muted">禁区</div><b style="font-size:16px;color:#F56C6C">{{ instruction.forbidden || '—' }}</b></el-col>
        <el-col :span="6"><div class="muted">阵眼锚点</div><b style="font-size:16px">{{ instruction.anchor || '—' }}</b></el-col>
      </el-row>
    </el-card>

    <el-row :gutter="16">
      <el-col :span="16">
        <el-card class="page-card">
          <template #header><b>主线状态</b></template>
          <el-descriptions :column="3" border>
            <el-descriptions-item label="主线">{{ vo.mainConcept || '—' }}</el-descriptions-item>
            <el-descriptions-item label="阶段">{{ vo.mainStage || '—' }}</el-descriptions-item>
            <el-descriptions-item label="催化硬度">
              <el-rate :model-value="vo.catalystHardness ?? 0" disabled size="small" />
            </el-descriptions-item>
          </el-descriptions>
          <div style="margin-top:12px">
            <b>🐉 龙头状态：</b>
            <template v-if="vo.leaderName">
              <el-tag effect="dark" type="danger" style="margin-right:8px">{{ vo.leaderName }} {{ vo.leaderBoard }} 板</el-tag>
              <el-tag type="warning" effect="plain">{{ vo.leaderAction || '—' }}</el-tag>
            </template>
            <span v-else class="muted">暂无总龙头</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card class="page-card">
          <template #header><b>🔄 轮动信号</b></template>
          <template v-if="vo.rotationSignals && vo.rotationSignals.length">
            <div v-for="(s, i) in vo.rotationSignals" :key="i" style="margin-bottom:8px">
              <el-alert :title="signalTitle(s.type)" :description="s.message" type="warning" show-icon :closable="false" />
            </div>
          </template>
          <el-empty v-else description="暂无轮动信号" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <el-card class="page-card">
      <template #header><b>总分与节点曲线</b></template>
      <div ref="curveRef" style="height: 300px" />
    </el-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import { sentimentApi } from '../api/modules'
import { nodeColor } from '../utils/format'

const vo = reactive({})
const instruction = ref({})
const loading = ref(false)
const radarRef = ref(null)
const curveRef = ref(null)
let radarChart = null
let curveChart = null

const SIGNAL_TITLES = {
  OLD_MAIN_DECLINE: '老主线退潮',
  NEW_THEME_SEED: '新题材种子',
  HIGH_TO_LOW_SWITCH: '高低切'
}
function signalTitle(t) {
  return SIGNAL_TITLES[t] || t
}

async function load() {
  loading.value = true
  try {
    const [today, curve] = await Promise.all([sentimentApi.today(), sentimentApi.curve()])
    Object.assign(vo, today.data)
    instruction.value = today.data.instruction || {}
    renderRadar(today.data)
    renderCurve(curve.data)
  } finally {
    loading.value = false
  }
}

function renderRadar(d) {
  if (!radarChart) radarChart = echarts.init(radarRef.value)
  radarChart.setOption({
    radar: {
      indicator: ['大盘生态', '主线明确度', '连板生态', '首板生态', '阵眼'].map(n => ({ name: n, max: 100 })),
      radius: '65%'
    },
    series: [{
      type: 'radar',
      areaStyle: { opacity: 0.25 },
      data: [{
        value: [d.scoreMarket, d.scoreConcept, d.scoreLianban, d.scoreShouban, d.scoreZhenyan].map(v => +(v ?? 0).toFixed(1)),
        name: d.tradeDate
      }]
    }]
  })
}

function renderCurve(c) {
  if (!curveChart) curveChart = echarts.init(curveRef.value)
  const colorMap = { '冰点': '#909399', '启动': '#409EFF', '发酵': '#67C23A', '高潮': '#F56C6C', '分歧': '#E6A23C', '退潮': '#606266' }
  curveChart.setOption({
    tooltip: { trigger: 'axis', formatter: params => {
      const idx = params[0].dataIndex
      return `${c.dates[idx]}<br/>总分：<b>${c.totals[idx]}</b><br/>节点：<b>${c.nodes[idx] || '—'}</b><br/>主线：<b>${c.mainConcepts[idx] || '—'}</b>`
    } },
    grid: { left: 40, right: 20, bottom: 30, top: 40 },
    xAxis: {
      type: 'category', data: c.dates,
      axisLabel: { formatter: val => val.slice(5), interval: 0 }
    },
    yAxis: { type: 'value', max: 100 },
    series: [
      {
        name: '总分', type: 'line', data: c.totals, smooth: true,
        lineStyle: { width: 3, color: '#409EFF' }, itemStyle: { color: '#409EFF' },
        areaStyle: { opacity: 0.08 }
      },
      {
        name: '节点', type: 'scatter', data: c.dates.map((d, i) => ({ value: [d, c.totals[i]], name: c.nodes[i] })),
        symbolSize: 10,
        itemStyle: { color: p => colorMap[p.data.name] || '#909399' },
        tooltip: { formatter: p => `${p.axisValue}<br/>节点：<b>${p.data.name}</b>` }
      }
    ]
  })
}

onMounted(load)
onBeforeUnmount(() => {
  radarChart?.dispose()
  curveChart?.dispose()
})
</script>
