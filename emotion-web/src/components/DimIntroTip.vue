<template>
  <span class="dim-tip" role="button" tabindex="0" aria-label="维度说明">
    ?
    <span class="dim-tip-text">
      <b v-if="title" class="dim-tip-title">{{ title }}</b>
      <span v-if="body" class="dim-tip-body">{{ body }}</span>
    </span>
  </span>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  body: { type: String, default: '' }
})
</script>

<style scoped>
.dim-tip {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  margin-left: 7px;
  border-radius: 50%;
  background: #22303f;
  border: 1px solid #3a4d63;
  color: #9fb2c6;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  cursor: help;
  vertical-align: middle;
  transition: color .18s, border-color .18s, background .18s;
}
.dim-tip:hover,
.dim-tip:focus {
  color: #ffd166;
  border-color: #ffd166;
  background: #2b3d52;
  outline: none;
}
.dim-tip-text {
  position: absolute;
  left: 0;
  top: 22px;
  z-index: 40;
  width: max-content;
  max-width: 340px;
  padding: 8px 11px;
  background: #1a2332;
  border: 1px solid #3a4d63;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, .45);
  text-align: left;
  font-size: 12px;
  font-weight: 400;
  line-height: 1.6;
  color: #8899a6;
  opacity: 0;
  visibility: hidden;
  transform: translateY(-4px);
  transition: opacity .16s, visibility .16s, transform .16s;
}
.dim-tip:hover .dim-tip-text,
.dim-tip:focus .dim-tip-text {
  opacity: 1;
  visibility: visible;
  transform: translateY(0);
}
.dim-tip-title {
  display: block;
  color: #e1e8ed;
  font-weight: 600;
  margin-bottom: 3px;
}
.dim-tip-body {
  display: block;
}
</style>