<script setup>
/* 应用外壳：顶栏（品牌/API 基址/身份徽章）+ 左导航（含演示区）+ 路由内容区
   + 底部 API 日志抽屉 + Toast 宿主 + API 基址设置弹层 */
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import IdentitySwitcher from '@/components/IdentitySwitcher.vue'
import ApiLogDrawer from '@/components/ApiLogDrawer.vue'
import ToastHost from '@/components/ToastHost.vue'
import DemoZone from '@/components/DemoZone.vue'

const ui = useUiStore()
const route = useRoute()

const NAV = [
  { view: 'overview', label: '概览', to: '/overview', icon: 'M4 13a8 8 0 0 1 16 0M4 13v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4M12 13l3-4' },
  { view: 'expense', label: '记账', to: '/expense', icon: 'M6 3h12v18l-3-2-3 2-3-2-3 2V3Zm4 6h4M10 13h4' },
  { view: 'settlement', label: '结清', to: '/settlement', icon: 'M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3M18 3.2v4h-4M6 20.8v-4h4' },
  { view: 'members', label: '成员', to: '/members', icon: 'M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm7.5.5a3 3 0 1 0 0-6m-11 15a4.5 4.5 0 0 1 9 0m2-4.2a4.5 4.5 0 0 1 4 4.2' },
]

/* API 基址弹层：空串 = 同源 /api（dev 经 vite proxy，prod 走 Nginx 同域反代） */
const baseModal = ref(false)
const baseInput = ref('')
function openBase() {
  baseInput.value = ui.apiBase
  baseModal.value = true
}
function saveBase() {
  const v = baseInput.value.trim().replace(/\/+$/, '')
  if (v && !/^https?:\/\/.+/.test(v)) {
    ui.toast('请输入 http(s):// 开头的地址（或留空用同源 /api）', 'err')
    return
  }
  ui.setApiBase(v)
  baseModal.value = false
  ui.toast(v ? 'API 基址已切换：' + v : '已切回同源 /api（proxy / 反代）', 'ok')
}
</script>

<template>
  <!-- 顶部栏：品牌 / API 基址 / 身份徽章 -->
  <header class="topbar">
    <div class="brand">
      <span class="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="26" height="26">
          <path d="M7 15.5 12 6l5 9.5" stroke="url(#bg1)" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M8.6 13h6.8" stroke="#38bdf8" stroke-width="2.4" stroke-linecap="round" />
          <defs>
            <linearGradient id="bg1" x1="0" y1="1" x2="1" y2="0">
              <stop offset="0" stop-color="#34d399" /><stop offset="1" stop-color="#38bdf8" />
            </linearGradient>
          </defs>
        </svg>
      </span>
      <span class="brand-name">Split&nbsp;Buddy</span>
      <span class="brand-sub">AA 记账 · Vue3 前后端分离演示端</span>
    </div>

    <div class="topbar-right">
      <button
        class="chip"
        title="点击修改后端 API 基址（默认同源 /api：开发经 vite proxy → 8081，生产走 Nginx 同域反代）"
        @click="openBase"
      >
        <svg viewBox="0 0 24 24" width="14" height="14">
          <path d="M9 15 15 9M10.5 7 12 5.5a4.5 4.5 0 0 1 6.4 6.4L17 13.3M13.5 17 12 18.5A4.5 4.5 0 0 1 5.6 12L7 10.7" stroke="currentColor" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span>{{ ui.apiBase || '同源 /api（vite proxy → 8081）' }}</span>
      </button>
      <IdentitySwitcher />
    </div>
  </header>

  <!-- 主布局：左导航 + 内容区 -->
  <div class="layout">
    <nav class="sidenav" aria-label="主导航">
      <RouterLink
        v-for="n in NAV"
        :key="n.view"
        :to="n.to"
        class="nav-item"
        :class="{ active: route.path === n.to }"
      >
        <svg viewBox="0 0 24 24" width="18" height="18">
          <path :d="n.icon" stroke="currentColor" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span>{{ n.label }}</span>
      </RouterLink>
      <div class="sidenav-foot">
        <DemoZone />
      </div>
    </nav>

    <main class="main" role="main">
      <RouterView v-slot="{ Component }">
        <component :is="Component" :key="route.fullPath" />
      </RouterView>
    </main>
  </div>

  <!-- 底部 API 日志抽屉 + Toast 宿主 -->
  <ApiLogDrawer />
  <ToastHost />

  <!-- API 基址编辑弹层 -->
  <el-dialog v-model="baseModal" class="sb-dialog" title="设置后端 API 基址" width="420px" append-to-body>
    <p class="muted" style="margin-top:0">
      留空 = 同源相对路径 <b class="mono">/api</b>（开发期由 vite proxy 转发到 8081，生产期由 Nginx 同域反代）。
      填绝对地址则绕过代理直连后端（后端 CORS 已允许所有来源）。
    </p>
    <el-input v-model="baseInput" class="mono" placeholder="留空或 http://localhost:8081" spellcheck="false" />
    <template #footer>
      <button class="btn ghost" @click="baseModal = false">取消</button>
      <button class="btn primary" style="margin-left:10px" @click="saveBase">保存</button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 导航链接继承按钮观感，去掉浏览器默认下划线 */
.nav-item { text-decoration: none; color: var(--text-dim); }
</style>
