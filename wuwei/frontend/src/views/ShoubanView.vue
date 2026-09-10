<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px">
      <span class="page-title" style="margin: 0">P3 首板池 {{ data.tradeDate ? `（${data.tradeDate}）` : '' }}</span>
      <div>
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
      </div>
    </div>

    <el-card class="page-card">
      <template #header><b>首板封住名单</b>（{{ (data.sealed || []).length }} 家）</template>
      <el-table :data="data.sealed || []" size="small" stripe>
        <el-table-column prop="tsCode" label="代码" width="110" />
        <el-table-column prop="name" label="名称" width="110" />
        <el-table-column prop="concept" label="题材" min-width="100" />
        <el-table-column prop="firstLuTime" label="首次涨停" width="90" />
        <el-table-column prop="openTimes" label="开板次数" width="85" />
        <el-table-column label="封单" width="100">
          <template #default="{ row }">{{ money(row.fdAmount) }}</template>
        </el-table-column>
        <el-table-column label="成交额" width="100">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="换手率" width="85">
          <template #default="{ row }">{{ fmt(row.turnoverRate, '%', 1) }}</template>
        </el-table-column>
        <el-table-column label="收盘涨幅" width="95">
          <template #default="{ row }">{{ fmt(row.closeChg, '%', 1) }}</template>
        </el-table-column>
        <el-table-column label="次日开盘溢价" width="115">
          <template #default="{ row }">
            <span :style="{ color: (row.nextOpenChg ?? 0) >= 0 ? '#F56C6C' : '#67C23A' }">{{ fmt(row.nextOpenChg, '%', 2) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="page-card">
      <template #header><b>当日炸板名单</b>（{{ (data.bombed || []).length }} 家）</template>
      <el-table :data="data.bombed || []" size="small" stripe>
        <el-table-column prop="tsCode" label="代码" width="110" />
        <el-table-column prop="name" label="名称" width="110" />
        <el-table-column prop="concept" label="题材" min-width="100" />
        <el-table-column prop="openTimes" label="开板次数" width="85" />
        <el-table-column label="收盘涨幅" width="95">
          <template #default="{ row }">
            <span :style="{ color: (row.closeChg ?? 0) >= 0 ? '#F56C6C' : '#67C23A' }">{{ fmt(row.closeChg, '%', 1) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="次日开盘溢价" width="115">
          <template #default="{ row }">
            <span :style="{ color: (row.nextOpenChg ?? 0) >= 0 ? '#F56C6C' : '#67C23A' }">{{ fmt(row.nextOpenChg, '%', 2) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { tiantiApi } from '../api/modules'
import { money, fmt } from '../utils/format'

const data = reactive({})
const date = ref(null)

async function load() {
  const res = date.value ? await tiantiApi.shoubanByDate(date.value) : await tiantiApi.shoubanLatest()
  Object.keys(data).forEach(k => delete data[k])
  Object.assign(data, res.data)
}

onMounted(load)
</script>
