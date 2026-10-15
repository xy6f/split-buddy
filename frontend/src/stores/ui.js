/* ==========================================================================
 * ui store：Toast / API 日志抽屉 / API 基址 / 全局 busy
 * ========================================================================== */
import { defineStore } from 'pinia'
import { ref } from 'vue'

let toastSeq = 0

export const useUiStore = defineStore('ui', () => {
  /* API 基址：空串 = 同源相对路径（dev 由 vite proxy 转发，prod 走 Nginx 反代）。
     顶栏 chip 仍可改成绝对地址（后端 CORS 全开），选择存 localStorage。 */
  const apiBase = ref(localStorage.getItem('sb.apiBase') || '')

  /* Toast 队列 */
  const toasts = ref([])
  function toast(msg, kind = 'info') {
    const t = { id: ++toastSeq, msg, kind, out: false }
    toasts.value.push(t)
    setTimeout(() => {
      t.out = true
      setTimeout(() => {
        const i = toasts.value.findIndex((x) => x.id === t.id)
        if (i >= 0) toasts.value.splice(i, 1)
      }, 320)
    }, 3400)
  }

  /* API 日志抽屉 */
  const apiLogs = ref([])
  const logCount = ref(0)
  const drawerOpen = ref(false)
  function pushLog(entry) {
    logCount.value++
    apiLogs.value.push({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), ...entry })
    while (apiLogs.value.length > 300) apiLogs.value.shift()
  }
  function toggleDrawer() { drawerOpen.value = !drawerOpen.value }

  /* 全局忙碌锁（防止连点提交；与旧版 State.busy 等价） */
  const busy = ref(false)
  async function withBusy(fn) {
    if (busy.value) return
    busy.value = true
    try {
      await fn()
    } catch (e) {
      toast(e?.message || '操作失败', 'err')
    } finally {
      busy.value = false
    }
  }

  function setApiBase(v) {
    apiBase.value = v
    if (v) localStorage.setItem('sb.apiBase', v)
    else localStorage.removeItem('sb.apiBase')
  }

  return {
    apiBase, setApiBase,
    toasts, toast,
    apiLogs, logCount, drawerOpen, pushLog, toggleDrawer,
    busy, withBusy,
  }
})
