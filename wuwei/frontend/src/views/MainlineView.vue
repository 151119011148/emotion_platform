<template>
  <div>
    <div class="page-title">P6 主线详情</div>

    <el-card class="page-card">
      <template #header>
        <div style="display:flex;align-items:center;gap:12px">
          <b style="font-size:16px">🐉 {{ data.name || '当前无主线' }}</b>
          <el-tag effect="dark" type="danger">{{ data.stage || '—' }}</el-tag>
          <span class="muted">截至 {{ data.asOfDate }}</span>
        </div>
      </template>
      <el-row :gutter="24" style="margin-bottom: 16px">
        <el-col :span="8">
          <div class="muted" style="margin-bottom:4px">催化硬度</div>
          <el-rate :model-value="data.catalystHardness ?? 0" disabled size="large" />
        </el-col>
        <el-col :span="8">
          <div class="muted" style="margin-bottom:4px">持续天数</div>
          <b style="font-size:22px">{{ data.continuousDays ?? 0 }} 天</b>
        </el-col>
        <el-col :span="8">
          <div class="muted" style="margin-bottom:4px">启动日期</div>
          <b style="font-size:22px">{{ data.activeSince || '—' }}</b>
        </el-col>
      </el-row>

      <div class="muted" style="margin-bottom:8px">📈 生命周期</div>
      <el-steps :active="data.lifecycleIndex ?? 0" align-center finish-status="success" style="margin-bottom: 8px">
        <el-step v-for="s in (data.lifecycle || [])" :key="s" :title="s" />
      </el-steps>
    </el-card>

    <el-card class="page-card">
      <template #header><b>👑 龙头分工</b></template>
      <el-row :gutter="16">
        <el-col :span="8" v-for="d in (data.dragons || [])" :key="d.role" style="margin-bottom:12px">
          <el-card shadow="hover" :body-style="{ padding: '14px' }">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
              <span>{{ ROLE_EMOJI[d.role] || d.role }}</span>
              <el-tag v-if="d.action" size="small" :type="d.action === '晋级' ? 'success' : d.action === '断板' ? 'danger' : 'warning'">{{ d.action }}</el-tag>
            </div>
            <template v-if="d.name">
              <div style="font-size:17px;font-weight:700">{{ d.name }}</div>
              <div style="margin-top:4px" class="muted">{{ d.tsCode }} · {{ d.nZones }} 板</div>
              <div :style="{ color: (d.closeChg ?? 0) >= 0 ? '#F56C6C' : '#67C23A', marginTop: '4px' }">
                {{ d.closeChg !== null && d.closeChg !== undefined ? d.closeChg.toFixed(1) + '%' : '' }}
              </div>
            </template>
            <div v-else class="muted" style="line-height:34px">暂无担当</div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <el-card class="page-card">
      <template #header><b>🔄 轮动监测</b></template>
      <template v-if="(data.rotationSignals || []).length">
        <el-alert v-for="(s, i) in data.rotationSignals" :key="i" :title="s.message" type="warning" show-icon :closable="false" style="margin-bottom:8px" />
      </template>
      <el-empty v-else description="暂无轮动信号，主线延续中" :image-size="60" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue'
import { conceptApi } from '../api/modules'
import { ROLE_EMOJI } from '../utils/format'

const data = reactive({})

onMounted(async () => {
  const res = await conceptApi.main()
  Object.assign(data, res.data)
})
</script>
