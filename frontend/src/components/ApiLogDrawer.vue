<script setup>
/* 底部 API 日志抽屉：每次请求一行，展开时自动滚到最新（移植旧版 ApiLog） */
import { nextTick, ref, watch } from 'vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const bodyEl = ref(null)

watch(
  () => [ui.apiLogs.length, ui.drawerOpen],
  async () => {
    if (!ui.drawerOpen) return
    await nextTick()
    if (bodyEl.value) bodyEl.value.scrollTop = bodyEl.value.scrollHeight
  },
  { flush: 'post' },
)
</script>

<template>
  <div class="drawer" :class="{ open: ui.drawerOpen }">
    <button class="drawer-head" :aria-expanded="ui.drawerOpen" @click="ui.toggleDrawer()">
      <svg viewBox="0 0 24 24" width="15" height="15">
        <path d="m8 9 3 3-3 3M13 15h3M5 5h14a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Z" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <span>API 调用日志</span>
      <span class="drawer-count">{{ ui.logCount }}</span>
      <span class="drawer-caret">▾</span>
    </button>
    <pre ref="bodyEl" class="drawer-body">
      <div v-for="(l, i) in ui.apiLogs" :key="i" :class="l.ok ? 'ok' : 'err'">
        <span class="m">{{ l.method }}</span> {{ l.path }} → {{ l.status }} ({{ l.ms }}ms)
        <span style="color:var(--text-faint)">· {{ l.brief }}</span>
      </div>
    </pre>
  </div>
</template>

<style scoped>
/* pre 内保留逐行文本，但不渲染模板缩进空白 */
.drawer-body { white-space: normal; }
.drawer-body > div { white-space: pre-wrap; }
</style>
