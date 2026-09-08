<template>
  <div class="suggest" v-loading="loading">
    <div class="row">
      <span class="title">平台复算</span>
      <template v-if="s">
        <el-tag :type="statusType(s.suggestedStatus)" size="small">建议 {{ s.suggestedStatus }}</el-tag>
        <el-tag v-if="!s.ready" type="info" size="small">判据不齐，不能采纳</el-tag>
        <span class="same" v-if="sameAsStored">与库里那条一字不差</span>
      </template>
      <span class="spacer"></span>
      <el-button size="small" @click="load" :loading="loading">重新复算</el-button>
      <el-button size="small" type="primary" :disabled="!s || !s.ready" :loading="adopting" @click="adopt">
        采纳
      </el-button>
    </div>

    <p class="reason" v-if="s">{{ s.reason }}</p>

    <ul class="list missing" v-if="s && s.missing.length">
      <li v-for="(m, i) in s.missing" :key="'m' + i">{{ m }}</li>
    </ul>
    <ul class="list warn" v-if="s && s.warnings.length">
      <li v-for="(w, i) in s.warnings" :key="'w' + i">{{ w }}</li>
    </ul>

    <p class="anchor" v-if="s && s.anchor && s.anchor.name">
      锚定龙头：你写的「{{ s.anchor.input || '—' }}」→ 匹配到
      {{ s.anchor.name }}（{{ s.anchor.code }}）{{ s.anchor.industry ? ' · ' + s.anchor.industry : '' }}，
      最后一次涨停 {{ s.anchor.lastLimitDate || '—' }}
      <template v-if="s.anchor.lastConsecutive">（{{ s.anchor.lastConsecutive }}板）</template>。
      不是这一只的话别说采纳，先把名字改对再复算。
    </p>

    <template v-if="s && s.promotion">
      <p class="basis">{{ s.promotion.basis }}</p>
      <p class="basis">判据：{{ s.promotion.threshold }}</p>
      <el-table v-if="s.promotion.items && s.promotion.items.length" :data="s.promotion.items"
        size="small" max-height="260" class="cands">
        <el-table-column prop="name" label="名称" width="110" />
        <el-table-column prop="code" label="代码" width="80" />
        <el-table-column prop="industry" label="行业" width="100" />
        <el-table-column label="T+1" width="70">
          <template #default="{ row }">{{ t1Cell(row) }}</template>
        </el-table-column>
        <el-table-column label="之后最高" width="90">
          <template #default="{ row }">{{ row.maxBoard == null ? '—' : row.maxBoard + '板' }}</template>
        </el-table-column>
        <el-table-column label="晋级">
          <template #default="{ row }">
            <span :class="row.promoted === true ? 'up' : row.promoted === false ? 'down' : ''">
              {{ row.promoted === true ? '是' : row.promoted === false ? '否' : '未知' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-collapse v-if="s && s.filter && s.filter.length" class="fold">
      <el-collapse-item :title="`前置过滤器（平台复算） ${filterSummary}`" name="filter">
        <div class="filter" v-for="f in s.filter" :key="f.key">
          <span class="f-label" :class="passClass(f.pass)">{{ f.label }}</span>
          <span class="f-actual">{{ f.actual }}</span>
          <span class="f-source">{{ f.source }}</span>
        </div>
      </el-collapse-item>
    </el-collapse>

    <p class="note" v-if="s && s.nodeStock">
      节点票 {{ s.nodeStock }}{{ s.nodeStockMaxBoard ? `（最高 ${s.nodeStockMaxBoard} 板）` : '' }}
    </p>
    <p class="hint">
      采纳只写这一条节点自己的八个字段，不动你填的备注；
      点了「有效/失效」之后这条会从「当前追踪」进「历史节点」。
    </p>
  </div>
</template>

<script setup>
/**
 * 一条节点的复算面板：建议、来路、缺哪一样、四格过滤器、候选票逐只。
 *
 * 当前卡和历史表都用它。判据的原文与阈值都在后端一处（NodeSuggestService），
 * 这里只负责把它们摆得能读——所以没有任何一条阈值在这里写第二遍，改判据不会出现两边不一致。
 */
import { ref, computed, watch } from 'vue'
import { nodeApi } from '../api/modules'
import { ElMessage } from 'element-plus'

const props = defineProps({
  node: { type: Object, required: true }
})
const emit = defineEmits(['adopted'])

const s = ref(null)
const loading = ref(false)
const adopting = ref(false)

async function load() {
  if (!props.node?.id) return
  loading.value = true
  try {
    const res = await nodeApi.suggest(props.node.id)
    s.value = res.data || null
  } catch (e) {
    // 复算失败保持上次结论不动，红条由拦截器弹过了
  } finally {
    loading.value = false
  }
}

async function adopt() {
  if (!s.value?.ready) return
  adopting.value = true
  try {
    await nodeApi.adopt(props.node.id, s.value.fingerprint)
    ElMessage.success('已采纳，状态与会写进去的读数一起落库')
    emit('adopted')
    await load()
  } catch (e) {
    // 服务端重算发现盘面变了会整条拒绝；那句中文原因拦截器已经弹过，这里只把建议刷新
    await load()
  } finally {
    adopting.value = false
  }
}

function statusType(status) {
  return { 有效: 'success', 失效: 'danger', 待验证: 'warning' }[status] || 'info'
}

/** T+1 未定那天的明细根本没拉过：说"未涨停"是替一只票下了结论，说"—"才是没数过。 */
function t1Cell(row) {
  if (!s.value?.t1Date) return '—'
  return row.t1Consecutive == null ? '未涨停' : `${row.t1Consecutive}板`
}

function passClass(pass) {
  return pass === true ? 'f-pass' : pass === false ? 'f-fail' : 'f-unknown'
}

const filterSummary = computed(() => {
  const items = s.value?.filter || []
  const passed = items.filter((f) => f.pass === true).length
  const failed = items.filter((f) => f.pass === false).length
  const unknown = items.length - passed - failed
  return `通过 ${passed} · 不通过 ${failed}${unknown ? ` · 无读数 ${unknown}` : ''}`
})

const sameAsStored = computed(() => {
  const n = props.node
  return !!s.value && s.value.ready && s.value.status === n.status
    && String(s.value.t1PromotionCount) === String(n.t1PromotionCount)
})

watch(() => props.node?.id, load, { immediate: true })
</script>

<style scoped>
.suggest {
  margin-top: 16px;
  padding: 14px 16px;
  background: #0f1419;
  border: 1px solid #2b3a4a;
  border-radius: 10px;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.title {
  color: #e1e8ed;
  font-weight: 600;
  font-size: 14px;
}
.spacer {
  flex: 1;
}
.same {
  color: #2d8a4e;
  font-size: 12px;
}
.reason {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #cbd5e1;
}
.list {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.7;
}
.list.missing li {
  color: #f59e0b;
}
.list.warn li {
  color: #8899a6;
}
.anchor,
.basis {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #8899a6;
}
.cands {
  margin-top: 10px;
}
.cands :deep(.el-table) {
  background: transparent;
}
.up {
  color: #2d8a4e;
}
.down {
  color: #dc2626;
}
.fold {
  margin-top: 10px;
  border-top: none;
}
.fold :deep(.el-collapse-item__header) {
  background: transparent;
  border-color: #2b3a4a;
  color: #8899a6;
  font-size: 12px;
  height: 34px;
}
.fold :deep(.el-collapse-item__wrap) {
  background: transparent;
  border-color: #2b3a4a;
}
.fold :deep(.el-collapse-item__content) {
  padding-bottom: 10px;
}
.filter {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 12px;
  padding: 4px 0;
  border-bottom: 1px dashed #1f2b38;
}
.filter:last-child {
  border-bottom: none;
}
.f-label {
  min-width: 150px;
}
.f-pass {
  color: #2d8a4e;
}
.f-fail {
  color: #dc2626;
}
.f-unknown {
  color: #8899a6;
}
.f-actual {
  color: #e1e8ed;
  min-width: 90px;
}
.f-source {
  color: #8899a6;
  font-size: 11px;
  flex: 1;
}
.note {
  margin: 10px 0 0;
  font-size: 12px;
  color: #cbd5e1;
}
.hint {
  margin: 10px 0 0;
  font-size: 11px;
  color: #5f7488;
  line-height: 1.6;
}
</style>
