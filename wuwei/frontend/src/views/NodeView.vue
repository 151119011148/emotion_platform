<template>
  <div>
    <div class="page-title">P5 节点演变</div>
    <el-card class="page-card">
      <div ref="chartRef" style="height: 260px" />
    </el-card>

    <el-card class="page-card">
      <template #header><b>逐日节点记录</b></template>
      <el-collapse v-model="opened">
        <el-collapse-item v-for="r in rows" :key="r.tradeDate" :name="r.tradeDate">
          <template #title>
            <div style="display:flex;align-items:center;gap:10px;width:100%">
              <span style="width:100px">{{ r.tradeDate }}</span>
              <el-tag :color="nodeColor(r.node)" effect="dark" style="border:none;width:64px;text-align:center">{{ r.node }}</el-tag>
              <span class="muted" style="width:150px">{{ r.transition }}</span>
              <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ r.triggerReason }}</span>
            </div>
          </template>
          <el-descriptions :column="2" border size="small" style="margin-bottom:10px">
            <el-descriptions-item label="主线">{{ r.mainConcept || '—' }}（{{ r.mainStage || '—' }}）</el-descriptions-item>
            <el-descriptions-item label="明日推演">{{ r.forecast || '—' }}</el-descriptions-item>
          </el-descriptions>
          <div v-if="r.watchPoints && r.watchPoints.length" style="margin-bottom:8px">
            <b>观察哨：</b>
            <el-tag v-for="(w, i) in r.watchPoints" :key="i" type="warning" effect="plain" style="margin:2px 6px 2px 0">{{ w }}</el-tag>
          </div>
          <div v-if="r.rotationSignals && r.rotationSignals.length">
            <b>轮动信号：</b>
            <el-tag v-for="(s, i) in r.rotationSignals" :key="i" type="danger" effect="dark" style="margin:2px 6px 2px 0">{{ s.message }}</el-tag>
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { nodeApi } from '../api/modules'
import { nodeColor } from '../utils/format'

const rows = ref([])
const opened = ref([])
const chartRef = ref(null)
let chart = null

onMounted(async () => {
  const res = await nodeApi.history()
  rows.value = (res.data.rows || []).slice().reverse()
  opened.value = rows.value.length ? [rows.value[0].tradeDate] : []
  if (chart) chart.dispose()
  chart = echarts.init(chartRef.value)
  const dates = rows.value.map(r => r.tradeDate).reverse()
  const values = rows.value.map(r => r.node).reverse()
  const nodeOrder = ['冰点', '启动', '发酵', '高潮', '分歧', '退潮']
  chart.setOption({
    tooltip: { formatter: p => `${p.axisValue}<br/>节点：<b>${p.value}</b>` },
    grid: { left: 50, right: 30, bottom: 40, top: 20 },
    xAxis: { type: 'category', data: dates, axisLabel: { formatter: v => v.slice(5), interval: 0 } },
    yAxis: { type: 'category', data: nodeOrder, inverse: true },
    series: [{
      type: 'scatter',
      symbolSize: 22,
      data: values.map(v => ({ value: v, itemStyle: { color: nodeColor(v) } })),
      label: { show: true, formatter: p => p.value, color: '#fff', fontSize: 10 }
    }]
  })
})

onBeforeUnmount(() => chart?.dispose())
</script>
