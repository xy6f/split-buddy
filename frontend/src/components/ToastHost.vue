<script setup>
/* Toast 宿主（右上角滑入）：渲染 ui.toasts，动画与自动消失由 ui store 驱动 */
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
</script>

<template>
  <div class="toasts" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in ui.toasts" :key="t.id" class="toast" :class="[t.kind, { out: t.out }]">
        <span class="tdot"></span>
        <span>{{ t.msg }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
/* .toast 自身的 enter/out 动画在 base.css；这里给 TransitionGroup 出列留位 */
.toast-leave-active { position: absolute; left: 0; right: 0; }
.toast-leave-to { opacity: 0; }
</style>
