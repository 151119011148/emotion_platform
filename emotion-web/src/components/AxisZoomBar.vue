<template>
  <div class="zoom-bar">
    <button
      v-for="b in buttons"
      :key="b.ev"
      type="button"
      class="zb"
      :disabled="!b.ok"
      :title="b.title"
      @click="emit(b.ev)"
    >{{ b.icon }}</button>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 曲线坐标轴工具条：≪ ≫ 平移 X 轴窗口、+ − 缩放 X 轴窗口（Y 轴跟着按可见数据重算）。
 * 只做按钮与禁用态，窗口状态一律在调用方（useCurveZoom），这里不持有任何数据。
 */
const props = defineProps({
  canZoomIn: { type: Boolean, default: false },
  canZoomOut: { type: Boolean, default: false },
  canPanLeft: { type: Boolean, default: false },
  canPanRight: { type: Boolean, default: false }
})
const emit = defineEmits(['zoom-in', 'zoom-out', 'pan-left', 'pan-right'])

const buttons = computed(() => [
  { ev: 'pan-left', icon: '≪', title: '坐标轴左移（看更早）', ok: props.canPanLeft },
  { ev: 'zoom-in', icon: '+', title: '放大（收窄横轴）', ok: props.canZoomIn },
  { ev: 'zoom-out', icon: '−', title: '缩小（放宽横轴）', ok: props.canZoomOut },
  { ev: 'pan-right', icon: '≫', title: '坐标轴右移（看更新）', ok: props.canPanRight }
])
</script>

<style scoped>
.zoom-bar {
  display: flex;
  justify-content: center;
  gap: 6px;
  margin-top: 8px;
}
.zb {
  width: 30px;
  height: 22px;
  line-height: 1;
  padding: 0;
  background: #10192b;
  border: 1px solid #2d3748;
  border-radius: 5px;
  color: #b9c6d3;
  font-size: 13px;
  cursor: pointer;
}
.zb:hover:not(:disabled) {
  border-color: #3b82f6;
  color: #e1e8ed;
}
.zb:disabled {
  opacity: 0.35;
  cursor: default;
}
</style>
