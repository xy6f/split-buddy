<script setup>
/* 登录 / 注册视图（移植旧版 renderAuth）：tab 切换 + 一键双人演示入口 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useLedgerStore } from '@/stores/ledger'
import { useUiStore } from '@/stores/ui'
import { demoInit } from '@/demo'

const auth = useAuthStore()
const ledger = useLedgerStore()
const ui = useUiStore()
const router = useRouter()

const tab = ref('login') // login / register
const form = reactive({ username: '', password: '', nickname: '' })

async function go() {
  await ui.withBusy(async () => {
    const username = form.username.trim()
    if (!username || !form.password) throw new Error('请输入用户名和密码')
    let sess
    if (tab.value === 'login') {
      sess = await auth.doLogin(username, form.password)
      ui.toast('欢迎回来，' + sess.nickname, 'ok')
    } else {
      await auth.doRegister(username, form.password, form.nickname.trim() || username)
      sess = await auth.doLogin(username, form.password)
      ui.toast('注册成功，已登录：' + sess.nickname, 'ok')
    }
    await ledger.refreshLedgers()
    await ledger.refreshLedgerData().catch(() => {})
    router.push('/overview')
  })
}

function quickDemo() {
  ui.withBusy(() => demoInit(router))
}
</script>

<template>
  <div class="auth-shell">
    <div class="glass card auth-card">
      <div style="text-align: center; margin-bottom: 16px">
        <div class="brand-mark" style="margin: 0 auto 10px; width: 48px; height: 48px">
          <svg viewBox="0 0 24 24" width="30" height="30">
            <path d="M7 15.5 12 6l5 9.5" stroke="#34d399" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M8.6 13h6.8" stroke="#38bdf8" stroke-width="2.4" stroke-linecap="round" />
          </svg>
        </div>
        <h2 style="margin: 0; font-size: 20px">Split Buddy</h2>
        <p class="muted" style="margin: 4px 0 0">朋友间共同开销，记清、算明、结掉</p>
      </div>

      <div class="auth-tabs">
        <button :class="{ active: tab === 'login' }" @click="tab = 'login'">登录</button>
        <button :class="{ active: tab === 'register' }" @click="tab = 'register'">注册</button>
      </div>

      <form class="stack" @submit.prevent="go">
        <div>
          <label class="field">用户名</label>
          <el-input v-model="form.username" autocomplete="username" spellcheck="false" />
        </div>
        <div>
          <label class="field">密码（≥6 位）</label>
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
        </div>
        <div v-if="tab === 'register'">
          <label class="field">昵称</label>
          <el-input v-model="form.nickname" maxlength="64" />
        </div>
        <button class="btn primary" type="submit" :disabled="ui.busy" style="width: 100%">
          {{ tab === 'login' ? '登录' : '注册并登录' }}
        </button>
      </form>

      <div style="margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--line)">
        <button class="btn sm ghost" style="width: 100%" :disabled="ui.busy" @click="quickDemo">
          <svg viewBox="0 0 24 24" width="14" height="14">
            <path d="M13 2 4.5 13.5H11L9.5 22 18 10.5h-6.5L13 2Z" fill="currentColor" />
          </svg>
          不想填表？一键初始化双人演示（alice/bob）
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
form.stack { gap: 10px; }
</style>
