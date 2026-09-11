<template>
  <!-- inline 模式：不渲染外层 stat-card div，只渲染 value/input（嵌入双值卡 stat-value 内部用） -->
  <template v-if="inline">
    <div v-if="modelValue != null && !editing" class="stat-value-inline" :class="$attrs" @click="editing = true">
      {{ fmtValue }}
    </div>
    <el-input-number v-else
      v-model="editModel"
      :min="min" :max="max" :precision="precision" :step="step"
      controls-position="right" size="small"
      :placeholder="ph"
      class="stat-input-inline"
      @blur="onBlur"
      @keyup.enter="onBlur"
    />
  </template>

  <!-- 正常模式：完整 stat-card -->
  <div v-else class="stat-card" :class="[pctClass, bandClass, { filled: modelValue != null }]">
    <span class="stat-label">{{ label }}<span v-if="unit" class="stat-unit"> ({{ unit }})</span></span>

    <!-- 有值时：大字展示，点击进入编辑 -->
    <div v-if="modelValue != null && !editing" class="stat-display" @click="editing = true">
      <span class="stat-value">{{ fmtValue }}</span>
      <span v-if="rawSub" class="stat-sub">{{ rawSub }}</span>
    </div>

    <!-- 空值 or 编辑中：输入框 -->
    <el-input-number v-else
      v-model="editModel"
      :min="min" :max="max" :precision="precision" :step="step"
      controls-position="right" size="small"
      :placeholder="ph"
      class="stat-input"
      @blur="onBlur"
      @keyup.enter="onBlur"
    />

    <span v-if="bandHit && modelValue != null" class="stat-band">{{ bandHit }}</span>
    <span v-else-if="hint" class="stat-hint">{{ hint }}</span>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { fiveDimBandClassOf } from '../utils/scores'

defineOptions({ inheritAttrs: false })

const props = defineProps({
  modelValue: { type: Number, default: null },
  label: { type: String, default: '' },
  unit: { type: String, default: '' },
  min: { type: Number, default: 0 },
  max: { type: Number, default: 100 },
  precision: { type: Number, default: 2 },
  step: { type: Number, default: 1 },
  ph: { type: String, default: '未取到' },
  hint: { type: String, default: '' },
  bandHit: { type: String, default: '' },
  /** 涨跌着色的参考值（正红负蓝）。不传时只显示数值 */
  refPct: { type: Number, default: null },
  /** bandClass 直接传（比如 D1 量能档/广度档）。如果传了就用来着色 stat-value */
  score: { type: Number, default: null },
  /** inline 模式：只渲染 value/input，不渲染外层 stat-card div（嵌入双值卡内部用） */
  inline: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const editing = ref(false)
const editModel = ref(null)

watch(() => props.modelValue, (v) => { editModel.value = v }, { immediate: true })

function onBlur() {
  const v = editModel.value
  // 如果跟原值一样，直接退出编辑模式不 emit
  if (v === props.modelValue) {
    editing.value = false
    return
  }
  emit('update:modelValue', v)
  editing.value = false
}

const fmtValue = computed(() => {
  if (props.modelValue == null) return '—'
  const n = Number(props.modelValue)
  if (!Number.isFinite(n)) return '—'
  // 整数不带 .0
  if (props.precision === 0) return String(n)
  return n.toFixed(props.precision)
})

const pctClass = computed(() => {
  if (props.refPct == null) return ''
  const n = Number(props.refPct)
  if (!Number.isFinite(n)) return ''
  return n > 0 ? 'up' : n < 0 ? 'down' : ''
})

const bandClass = computed(() => {
  if (props.score == null) return ''
  return fiveDimBandClassOf(props.score)
})

const rawSub = computed(() => {
  if (!props.bandHit || props.modelValue == null) return ''
  // bandHit 例子: "14.3 [10,20) → 68" 或 量比 "0.8633"
  return props.bandHit.length > 28 ? props.bandHit.slice(0, 28) + '…' : props.bandHit
})
</script>

<style scoped>
.stat-card {
  background: #1a2332;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 86px;
  transition: background .15s;
}
.stat-card.filled:hover {
  background: #243040;
  cursor: text;
}
.stat-label {
  font-size: 12px;
  color: #8899a6;
}
.stat-unit {
  color: #6b7c8c;
  font-size: 11px;
}
.stat-display {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: #e1e8ed;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  line-height: 1.2;
}
.stat-value.up { color: #ef4444; }
.stat-value.down { color: #3b82f6; }
.stat-value.b-ebb { color: #94a3b8; }
.stat-value.b-chaos { color: #60a5fa; }
.stat-value.b-ferment { color: #fbbf24; }
.stat-value.b-climax { color: #f87171; }
.stat-sub {
  font-size: 11px;
  color: #6b7c8c;
  line-height: 1.3;
}
.stat-band {
  font-size: 11px;
  color: #a8b7c4;
}
.stat-hint {
  font-size: 11px;
  color: #6b7c8c;
  line-height: 1.4;
}
.stat-input {
  width: 100%;
}
.stat-input :deep(.el-input-number) {
  width: 100%;
}
.stat-input :deep(.el-input__wrapper) {
  background: #0f1419;
  box-shadow: none;
  border-radius: 6px;
}

/* === inline 模式 === */
.stat-value-inline {
  font-size: 20px;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #e1e8ed;
  display: inline-block;
  cursor: text;
  min-width: 40px;
  text-align: right;
}
.stat-value-inline.up { color: #ef4444; }
.stat-value-inline.down { color: #3b82f6; }
.stat-input-inline {
  display: inline-block;
  width: auto;
}
.stat-input-inline :deep(.el-input-number) {
  width: 100px;
}
.stat-input-inline :deep(.el-input__wrapper) {
  background: #0f1419;
  box-shadow: none;
  border-radius: 6px;
}
</style>
