<script setup>
/* 侧栏底部"双人演示模式"：一键初始化 alice/bob 演示数据 + 步骤进度（旧版 §11） */
import { useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { DEMO_STEPS, demoState, demoInit } from '@/demo'

const ui = useUiStore()
const router = useRouter()

function run() {
  ui.withBusy(() => demoInit(router))
}
</script>

<template>
  <div class="badge ok" style="justify-content: center; padding: 6px">双人演示模式</div>
  <button class="btn sm" style="width: 100%" :disabled="ui.busy" @click="run">
    <svg viewBox="0 0 24 24" width="14" height="14">
      <path d="M13 2 4.5 13.5H11L9.5 22 18 10.5h-6.5L13 2Z" fill="currentColor" />
    </svg>
    一键初始化
  </button>
  <div v-if="demoState.step >= 0" class="stack" style="gap: 2px">
    <div
      v-for="(s, i) in DEMO_STEPS"
      :key="s"
      class="demo-step"
      :class="{ done: i < demoState.step, active: i === demoState.step }"
    >
      <span class="dot"></span><span>{{ s }}</span>
    </div>
  </div>
</template>
