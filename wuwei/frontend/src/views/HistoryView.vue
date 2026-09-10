<template>
  <div>
    <div class="page-title">P7 历史回顾</div>

    <el-card class="page-card">
      <template #header><b>总分曲线与节点分布</b></template>
      <div ref="chartRef" style="height: 320px" />
    </el-card>

    <el-card class="page-card">
      <template #header><b>历史评分明细</b></template>
      <el-table :data="tableRows" size="small" stripe>
        <el-table-column prop="date" label="交易日" width="110" />
        <el-table-column label="总分" width="90">
          <template #default="{ row }">
            <b :style="{ color: row.total >= 70 ? '#F56C6C' : row.total >= 40 ? '#E6A23C' : '#909399' }">{{ row.total.toFixed(1) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="节点" width="80">
          <template #default="{ row }">
            <el-tag :color="nodeColor(row.node)" effect="dark" style="border:none">{{ row.node }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大盘生态" width="90">
          <template #default="{ row }">{{ show(row.market) }}</template>
        </el-table-column>
        <el-table-column label="主线明确度" width="95">
          <template #default="{ row }">{{ show(row.concept) }}</template>
        </el-table-column>
        <el-table-column label="连板生态" width="90">
          <template #default="{ row }">{{ show(row.lianban) }}</template>
        </el-table-column>
        <el-table-column label="首板生态" width="90">
          <template #default="{ row }">{{ show(row.shouban) }}</template>
        </el-table-column>
        <el-table-column label="阵眼" width="80">
          <template #default="{ row }">{{ show(row.zhenyan) }}</template>
        </el-table-column>
        <el-table-column prop="main" label="当日主线" min-width="110" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { sentimentApi } from '../api/modules'
import { nodeColor } from '../utils/format'

const curve = ref({})
const chartRef = ref(null)
let chart = null

const tableRows = computed(() => (curve.value.dates || []).map((d, i) => ({
  date: d,
  total: curve.value.totals?.[i] ?? 0,
  node: curve.value.nodes?.[i] || '—',
  market: curve.value.scores?.market?.[i],
  concept: curve.value.scores?.concept?.[i],
  lianban: curve.value.scores?.lianban?.[i],
  shouban: curve.value.scores?.shouban?.[i],
  zhenyan: curve.value.scores?.zhenyan?.[i],
  main: curve.value.mainConcepts?.[i] || '—'
})))

function show(v) {
  return v === null || v === undefined ? '—' : (+v).toFixed(1)
}

function render() {
  if (!chart) return
  const c = curve.value
  if (!c.dates || !c.dates.length) return
  const colorMap = { '冰点': '#909399', '启动': '#409EFF', '发酵': '#67C23A', '高潮': '#F56C6C', '分歧': '#E6A23C', '退潮': '#606266' }
  chart.setOption({
    tooltip: { trigger: 'axis', formatter: params => {
      const idx = params[0].dataIndex
      return `${c.dates[idx]}<br/>总分：<b>${c.totals[idx]}</b><br/>节点：<b>${c.nodes[idx] || '—'}</b><br/>主线：<b>${c.mainConcepts[idx] || '—'}</b>`
    } },
    grid: { left: 40, right: 20, bottom: 40, top: 30 },
    xAxis: { type: 'category', data: c.dates, axisLabel: { formatter: v => v.slice(5), interval: 0 } },
    yAxis: { type: 'value', max: 100 },
    series: [
      {
        name: '总分', type: 'bar', data: c.totals, barWidth: '45%',
        itemStyle: {
          color: p => {
            const t = c.totals[p.dataIndex]
            return t >= 70 ? '#F56C6C' : t >= 40 ? '#E6A23C' : '#909399'
          }
        }
      },
      {
        name: '节点', type: 'scatter', symbolSize: 12,
        data: c.dates.map((d, i) => ({ value: [d, c.totals[i]], name: c.nodes[i], itemStyle: { color: colorMap[c.nodes[i]] || '#909399' } })),
        tooltip: { formatter: p => `${p.axisValue}<br/>节点：<b>${p.data.name}</b>` }
      }
    ]
  })
}

onMounted(async () => {
  const res = await sentimentApi.curve()
  curve.value = res.data || {}
  chart = echarts.init(chartRef.value)
  render()
})

onBeforeUnmount(() => chart?.dispose())
</script>
