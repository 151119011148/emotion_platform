<template>
  <section class="dim-score-block block" v-loading="scoring.detailLoading">
    <div class="block-head">
      <h3>{{ title }}</h3>
      <span v-if="dim" class="dim-score" :class="bandClass(dim.score)">
        {{ dim.score == null ? '未评' : Number(dim.score).toFixed(2) + ' 分' }}
      </span>
    </div>
    <el-empty v-if="!dim" :description="`当日读数未取到，${title}未评（不计入分母）`" :image-size="60" />
    <el-table v-else :data="dim.children || []" size="small">
      <el-table-column prop="label" label="子项" width="120" />
      <el-table-column label="权重" width="80">
        <template #default="{ row }">×{{ row.weight }}</template>
      </el-table-column>
      <el-table-column label="得分" width="90" align="right">
        <template #default="{ row }">
          <span v-if="row.score == null" class="missing">未评</span>
          <span v-else :class="bandClass(row.score)">{{ Number(row.score).toFixed(0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="读数 / 命中档" min-width="260">
        <template #default="{ row }">
          <span class="band-hit">{{ row.bandHit || (row.scoringKind === 'STRATEGY' ? '策略算法计算' : '—') }}</span>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useScoringStore } from '../stores/scoring'
import { fiveDimBandClassOf } from '../utils/scores'

/**
 * 各生态页共用的「维度打分块」：按日期现拉 score-detail，抽出指定维的维分与子项树。
 * 与仪表盘五维卡完全同源，只是平铺成表。日期变化自动重拉。
 */
const props = defineProps({
  date: { type: String, required: true },
  dimKey: { type: String, required: true },
  title: { type: String, default: '维度打分' }
})

const scoring = useScoringStore()
const dim = computed(() => (scoring.detail?.dims || []).find((d) => d.key === props.dimKey) || null)

function bandClass(score) {
  return fiveDimBandClassOf(score)
}

watch(() => props.date, (d) => {
  scoring.loadDetail(d, true)
}, { immediate: true })
</script>

<style scoped>
.block {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}
.block-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.block-head h3 {
  margin: 0;
  font-size: 15px;
  color: #e1e8ed;
}
.dim-meta {
  font-size: 12px;
  color: #8899a6;
  font-weight: 400;
  margin-left: 4px;
}
.dim-score {
  font-size: 16px;
  font-weight: 700;
  color: #e1e8ed;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  line-height: 1.2;
}
.layer-raw.unscored {
  color: #4b5a68;
  font-weight: 500;
}
.band-hit {
  font-size: 12px;
  color: #a8b7c4;
}
.missing {
  font-size: 12px;
  color: #8899a6;
  line-height: 1.6;
}

.none-hint {
  color: #6b7c8c;
  font-size: 12px;
  padding: 8px 0;
}
.b-ebb { color: #94a3b8; }
.b-chaos { color: #60a5fa; }
.b-ferment { color: #fbbf24; }
.b-climax { color: #f87171; }
</style>
