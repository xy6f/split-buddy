<script setup>
/* 顶栏身份徽章：头像 + 昵称 + ID/用户名；演示模式下（≥2 个身份）出现"切换"，
   在 alice/bob 令牌间轮转并刷新账本数据（移植旧版 renderBadge） */
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useLedgerStore } from '@/stores/ledger'
import { useUiStore } from '@/stores/ui'

const auth = useAuthStore()
const ledger = useLedgerStore()
const ui = useUiStore()
const router = useRouter()

function switchId() {
  const next = auth.switchIdentity()
  if (!next) return
  ui.toast('已切换身份：' + next.nickname, 'ok')
  ledger.clear()
  ledger.refreshLedgers()
    .then(() => ledger.refreshLedgerData())
    .then(() => router.push('/overview'))
    .catch((e) => ui.toast(e.message, 'err'))
}

function doLogout() {
  auth.logout(false)
  ledger.clear()
  ui.toast('已退出登录', 'info')
  router.push('/auth')
}
</script>

<template>
  <div v-if="auth.session" class="identity">
    <span class="avatar">{{ (auth.session.nickname || '?').slice(0, 1) }}</span>
    <span class="who">
      {{ auth.session.nickname }}
      <small>ID {{ auth.session.userId }} · {{ auth.session.username }}</small>
    </span>
    <button
      v-if="auth.hasMultiple"
      class="btn sm ghost"
      title="切换演示身份"
      :disabled="ui.busy"
      @click="switchId"
    >
      <svg viewBox="0 0 24 24" width="14" height="14">
        <path d="M7 8h11l-3-3m3 3-3 3M17 16H6l3-3m-3 3 3 3" stroke="currentColor" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      切换
    </button>
    <button class="btn sm ghost icon" title="退出登录" @click="doLogout">
      <svg viewBox="0 0 24 24" width="14" height="14">
        <path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4M10 8l-4 4 4 4M6 12h9" stroke="currentColor" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>
  </div>
</template>
