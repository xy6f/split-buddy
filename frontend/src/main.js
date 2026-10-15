/* ==========================================================================
 * Split Buddy · Vue 3 + Vite + Element Plus + Pinia 入口
 * Element Plus 暗色主题：index.html 已挂 <html class="dark">，
 * 样式全量引入（含 dark css-vars），组件按需可用 auto-import 之后再演进。
 * ========================================================================== */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

// Element Plus 全量样式 + 暗色变量（顺序不能颠倒：dark css-vars 需覆盖默认值）
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'

// 自家设计令牌 + 全局基础样式（深空玻璃拟态）
import '@/styles/tokens.css'
import '@/styles/base.css'

import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useLedgerStore } from '@/stores/ledger'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// Pinia 就绪后才能给 axios 拦截器注入 store 与 router 行为
setUnauthorizedHandler(() => {
  // 会话过期：清账本数据并踢回登录页
  useLedgerStore().clear()
  router.push('/auth')
})

// 路由守卫需要在 pinia 安装后注册（router/index.js 内 beforeEach 已定义，
// 它引用 store 的时机在导航发生时，届时 pinia 已激活）
app.mount('#app')

// 启动时若本地有会话，静默预拉一次账本（失败不阻断，401 会被 http.js 自动登出）
const auth = useAuthStore()
if (auth.isAuthed) {
  useLedgerStore().refreshLedgers().catch(() => {})
}
