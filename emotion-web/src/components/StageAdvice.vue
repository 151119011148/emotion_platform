<template>
  <div class="stage-advice" v-if="advice">
    <h3>操作建议 · {{ advice.stage }}</h3>
    <div class="advice-signal" v-if="signals.length">
      <el-icon><WarningFilled /></el-icon>
      <span>
        顶哨 {{ signals.length }} 项：{{ signals.join('、') }}
        —— 下面的建议是这一阶段的默认动作，不是"今天可以放心上"
      </span>
    </div>
    <div class="advice-grid">
      <div class="advice-item">
        <span class="label">基调</span>
        <span class="value">{{ advice.tone }}</span>
      </div>
      <div class="advice-item">
        <span class="label">仓位</span>
        <span class="value">{{ advice.position }}</span>
      </div>
    </div>
    <div class="advice-action">
      <span class="label">动作</span>
      <p>{{ advice.action }}</p>
    </div>
    <div class="advice-warning">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ advice.warning }}</span>
    </div>
  </div>
  <div class="stage-advice empty" v-else>
    <p>暂无数据，请先录入今日指标</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { topSignals } from '../utils/cycleReading'

const props = defineProps({
  advice: { type: Object, default: null },
  /** 当日整条记录：只用来数顶哨，不参与建议映射 */
  record: { type: Object, default: null }
})

const signals = computed(() => topSignals(props.record))
</script>

<style scoped>
.stage-advice {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
}
.stage-advice h3 {
  margin: 0 0 16px 0;
  font-size: 15px;
  color: #e1e8ed;
}
.stage-advice.empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8899a6;
}
.advice-signal {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 16px;
  padding: 12px;
  background: rgba(239, 68, 68, 0.18);
  border: 1px solid #7f1d1d;
  border-radius: 8px;
  color: #fecaca;
  font-size: 13px;
  line-height: 1.5;
}
.advice-signal .el-icon {
  color: #ef4444;
  margin-top: 2px;
}
.advice-grid {
  display: grid;
  /* 中文 min-content 仅一字宽，不加 minmax(0,..) 会竖排成一个字一行 */
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.advice-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.advice-item .label, .advice-action .label {
  font-size: 12px;
  color: #8899a6;
}
.advice-item .value {
  font-size: 16px;
  font-weight: 600;
  color: #f59e0b;
}
.advice-action {
  margin-bottom: 16px;
}
.advice-action p {
  margin: 4px 0 0 0;
  color: #e1e8ed;
  font-size: 14px;
  line-height: 1.6;
}
.advice-warning {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px;
  background: rgba(239, 68, 68, 0.1);
  border-radius: 8px;
  color: #fca5a5;
  font-size: 13px;
  line-height: 1.5;
}
.advice-warning .el-icon {
  color: #ef4444;
  margin-top: 2px;
}
</style>
