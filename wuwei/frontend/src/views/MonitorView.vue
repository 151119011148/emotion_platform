<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px">
      <span class="page-title" style="margin: 0">P4 异动监管池</span>
      <div>
        <el-tag type="success" effect="plain" style="margin-right: 8px">在管 {{ stats.active }} 家</el-tag>
        <el-tag type="danger" effect="plain">严重/重点 {{ stats.serious }} 家</el-tag>
      </div>
    </div>

    <el-card class="page-card">
      <el-table :data="rows" size="small" stripe>
        <el-table-column prop="tsCode" label="代码" width="110" />
        <el-table-column prop="name" label="名称" width="110" />
        <el-table-column label="监管状态" width="110">
          <template #default="{ row }">
            <el-tag :type="tagType(row.status)" size="small">{{ row.statusLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enterDate" label="入场日期" width="110" />
        <el-table-column label="预计解除" width="110">
          <template #default="{ row }">{{ row.exitDate || '持续监控' }}</template>
        </el-table-column>
        <el-table-column prop="relatedConcept" label="关联题材" width="120" />
        <el-table-column label="高位股" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.isHighPosition" type="danger" size="small" effect="plain">高位</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="当前状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'warning' : 'info'" size="small" effect="plain">{{ row.active ? '在管' : '已解除' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { monitorApi } from '../api/modules'

const rows = ref([])
const stats = reactive({ active: 0, serious: 0 })

function tagType(status) {
  if (status === 'SERIOUS' || status === 'KEY_MONITOR' || status === 'SUSPEND') return 'danger'
  if (status === 'ORDINARY') return 'warning'
  return 'info'
}

onMounted(async () => {
  const res = await monitorApi.pool()
  rows.value = res.data.rows || []
  stats.active = res.data.activeCount ?? 0
  stats.serious = res.data.seriousCount ?? 0
})
</script>
