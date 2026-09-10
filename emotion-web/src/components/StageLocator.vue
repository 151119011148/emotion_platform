<template>
  <div class="stage-locator">
    <h3>周期阶段定位</h3>
    <div class="stages-bar">
      <div
        v-for="s in stages"
        :key="s.name"
        class="stage-item"
        :class="{ active: isActive(s.name), forced: isForced(s.name) }"
        :style="slotStyle(s.name)"
      >
        <div class="stage-dot" :style="{ background: dotColor(s.name) }"></div>
        <span class="stage-name">{{ s.name }}</span>
        <span class="forced-badge" v-if="isForced(s.name)">强制</span>
      </div>
    </div>
    <div class="direction-info" v-if="stage">
      方向: <span :class="directionClass">{{ direction || '横盘' }}</span>
    </div>
    <div class="basis-info" v-if="basis">
      来路: <span class="basis">{{ basis }}</span>
    </div>
    <div class="forced-info" v-if="forcedReason">
      强制退潮原因：{{ forcedReason }}
    </div>
    <div class="signal-info" v-if="signals.length">
      结构信号 {{ signals.length }} 项：{{ signals.join('、') }}
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { STAGES, FORCED_EBB_STAGE, FORCED_EBB_COLOR } from '../utils/stages'
import { stageBasis, topSignals } from '../utils/cycleReading'

const props = defineProps({
  stage: { type: String, default: '' },
  direction: { type: String, default: '' },
  /** 当日整条记录：来路 / 顶哨 / 强制退潮原因都从里面现读，不另发请求 */
  record: { type: Object, default: null }
})

const stages = STAGES

/** "退潮(强制)" 归一化到"退潮" slot 做高亮判断。 */
function normalized() {
  return props.stage === FORCED_EBB_STAGE ? '退潮' : props.stage
}
function isActive(name) {
  return normalized() === name
}
function isForced(name) {
  return props.stage === FORCED_EBB_STAGE && name === '退潮'
}
function slotStyle(name) {
  if (!isActive(name)) return { background: 'transparent' }
  return { background: isForced(name) ? FORCED_EBB_COLOR : stageColor(name) }
}
function dotColor(name) {
  return isForced(name) ? FORCED_EBB_COLOR : stageColor(name)
}
function stageColor(name) {
  const s = STAGES.find((x) => x.name === name)
  return s ? s.color : ''
}

const basis = computed(() => stageBasis(props.record))
const signals = computed(() => topSignals(props.record))
const forcedReason = computed(() => {
  if (!props.record || props.record.forcedEbb !== 1) return ''
  return props.record.forcedEbbReason || ''
})

const directionClass = computed(() => {
  if (props.direction === '上升') return 'rising'
  if (props.direction === '下降') return 'falling'
  return ''
})
</script>

<style scoped>
.stage-locator {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
}
.stage-locator h3 {
  margin: 0 0 16px 0;
  font-size: 15px;
  color: #e1e8ed;
}
.stages-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.stage-item {
  /* flex:1 的 flex-basis 是 0，窄容器里会被压到比"冰点"两个字还窄，
     中文没有词边界可断，只能一个字竖着排一行。min-width 兜住标签宽度，装不下就换行。 */
  flex: 1 1 auto;
  min-width: max-content;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 4px;
  border-radius: 8px;
  transition: all 0.3s;
}
.stage-item.active {
  color: white;
}
.stage-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-bottom: 6px;
}
.stage-name {
  font-size: 12px;
  color: #8899a6;
  white-space: nowrap;
}
.stage-item.active .stage-name {
  color: white;
  font-weight: 600;
}
.direction-info {
  margin-top: 12px;
  font-size: 13px;
  color: #8899a6;
}
.basis-info {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: #8899a6;
}
.basis-info .basis {
  color: #cbd5e1;
}
.forced-badge {
  margin-top: 4px;
  padding: 1px 6px;
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  background: #dc2626;
  border-radius: 8px;
  letter-spacing: 1px;
}
.forced-info {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: #fecaca;
}
.signal-info {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: #fca5a5;
}
.rising { color: #ef4444; font-weight: 600; }
.falling { color: #3b82f6; font-weight: 600; }
</style>
