<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px">
      <span class="page-title" style="margin: 0">P2 连板天梯 {{ data.tradeDate ? `（${data.tradeDate}）` : '' }}</span>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
    </div>

    <el-row :gutter="16" style="margin-bottom: 16px">
      <el-col :span="6">
        <el-card shadow="hover"><el-statistic title="最高板" :value="data.maxBoard ?? 0" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover"><el-statistic title="连板家数" :value="data.count ?? 0" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #title><span class="muted">龙头标签说明</span></template>
          <div style="font-size:12px">🔴总龙 🔵中军 🟢跟风 🟡卡位 🟣反包</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-for="tier in ['HIGH', 'MIDHIGH', 'MID', 'LOW']" :key="tier" class="page-card">
      <template #header><b>{{ TIER_LABELS[tier] || tier }}</b>（{{ (data.tiers?.[tier] || []).length }} 家）</template>
      <el-table :data="data.tiers?.[tier] || []" size="small" stripe>
        <el-table-column label="标签" width="110">
          <template #default="{ row }">
            <span>{{ ROLE_EMOJI[row.dragonRole] || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="tsCode" label="代码" width="110" />
        <el-table-column prop="name" label="名称" width="110" />
        <el-table-column prop="nZones" label="连板数" width="80" />
        <el-table-column label="晋级" width="80">
          <template #default="{ row }">
            <el-tag :type="row.isPromote ? 'success' : 'info'" size="small">{{ row.isPromote ? '晋级' : 'HOLD' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="concept" label="主线" min-width="100" />
        <el-table-column prop="firstLuTime" label="首次涨停" width="90" />
        <el-table-column prop="openTimes" label="开板次数" width="85" />
        <el-table-column label="封单" width="100">
          <template #default="{ row }">{{ money(row.fdAmount) }}</template>
        </el-table-column>
        <el-table-column label="换手率" width="85">
          <template #default="{ row }">{{ fmt(row.turnoverRate, '%', 1) }}</template>
        </el-table-column>
        <el-table-column label="收盘涨幅" width="95">
          <template #default="{ row }">
            <span :style="{ color: (row.closeChg ?? 0) >= 0 ? '#F56C6C' : '#67C23A' }">{{ fmt(row.closeChg, '%', 1) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="监管" width="100">
          <template #default="{ row }">
            <el-tag v-if="MONITOR_LABELS[row.monitorStatus]?.type === 'danger'" type="danger" size="small">{{ MONITOR_LABELS[row.monitorStatus].label }}</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { tiantiApi } from '../api/modules'
import { TIER_LABELS, ROLE_EMOJI, MONITOR_LABELS, money, fmt } from '../utils/format'

const data = reactive({ tiers: {} })
const date = ref(null)

async function load() {
  const res = date.value ? await tiantiApi.byDate(date.value) : await tiantiApi.latest()
  Object.keys(data).forEach(k => delete data[k])
  Object.assign(data, res.data)
}

onMounted(load)
</script>
