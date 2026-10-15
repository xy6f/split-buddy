<script setup>
/* 空状态插画（深空账本 + 星点），slot action 可放操作按钮 */
defineProps({
  title: { type: String, required: true },
  hint: { type: String, default: '' },
})
</script>

<template>
  <div class="empty glass card">
    <svg width="132" height="96" viewBox="0 0 132 96" fill="none">
      <rect x="26" y="18" width="60" height="64" rx="8" stroke="#38bdf8" stroke-opacity=".45" stroke-width="2" />
      <path d="M38 34h36M38 46h36M38 58h22" stroke="#34d399" stroke-opacity=".5" stroke-width="2" stroke-linecap="round" />
      <circle cx="99" cy="30" r="14" stroke="#34d399" stroke-opacity=".6" stroke-width="2" />
      <path d="m93 30 4 4 8-9" stroke="#34d399" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      <path d="M14 12l1.6 4.4L20 18l-4.4 1.6L14 24l-1.6-4.4L8 18l4.4-1.6L14 12Z" fill="#38bdf8" fill-opacity=".7" />
      <path d="M116 62l1.2 3.2 3.2 1.2-3.2 1.2-1.2 3.2-1.2-3.2-3.2-1.2 3.2-1.2 1.2-3.2Z" fill="#34d399" fill-opacity=".6" />
    </svg>
    <div>
      <b>{{ title }}</b>
      <div class="muted">{{ hint }}</div>
    </div>
    <div><slot name="action" /></div>
  </div>
</template>
