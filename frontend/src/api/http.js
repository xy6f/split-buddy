/* ==========================================================================
 * 统一请求封装（axios 实例 + 拦截器）
 *  - 自动挂 Authorization: Bearer <token>（从 Pinia auth store 读取）
 *  - 统一拆 {code,msg,data}：code===0 直接返回 data，否则抛 ApiError
 *  - HTTP 非 2xx / 业务 code!==0 / 网络失败 全部翻译成 ApiError
 *  - 401：清会话并踢回登录页（登录/注册接口自身除外，见 skipAuthRedirect）
 *  - 每次请求（含失败）写入 API 日志抽屉（ui store）
 *  - apiBase 为空串 = 同源相对路径：开发期由 vite proxy 转发 /api → 8081，
 *    生产期由 Nginx 同域反代；也可在顶栏 chip 改成绝对地址（后端 CORS 全开）
 * ========================================================================== */
import axios from 'axios'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'

export class ApiError extends Error {
  constructor(msg, status, code) {
    super(msg)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

/* 会话过期处理函数，由 main.js 注入（避免 http ↔ router 的循环依赖） */
let onUnauthorized = () => {}
export function setUnauthorizedHandler(fn) { onUnauthorized = fn }

const http = axios.create({ timeout: 20000 })

/* 请求拦截：基址 / Bearer / 起始计时 */
http.interceptors.request.use((cfg) => {
  const ui = useUiStore()
  const auth = useAuthStore()
  cfg.baseURL = ui.apiBase || ''
  if (auth.session && auth.session.token) {
    cfg.headers.Authorization = 'Bearer ' + auth.session.token
  }
  cfg.meta = { start: performance.now() }
  return cfg
})

function pushLog(cfg, status, ms, ok, brief) {
  const ui = useUiStore()
  ui.pushLog({
    method: (cfg.method || 'get').toUpperCase(),
    path: cfg.url || '',
    status, ms, ok,
    brief: String(brief || '').slice(0, 140),
  })
}

/* 成功响应（HTTP 2xx）：继续校验业务包体 {code,msg,data} */
http.interceptors.response.use(
  (res) => {
    const cfg = res.config
    const ms = Math.round(performance.now() - (cfg.meta?.start || performance.now()))
    const json = res.data ?? {}
    const ok = json.code === 0
    pushLog(cfg, res.status, ms, ok, ok ? JSON.stringify(json.data ?? json.msg) : (json.msg || res.statusText))
    if (ok) return json.data // 拦截器直接把 data 交给调用方
    if (res.status === 401 && !cfg.skipAuthRedirect) {
      useUiStore().toast('登录已过期，请重新登录', 'err')
      useAuthStore().logout(true)
      onUnauthorized()
    }
    throw new ApiError(json.msg || ('HTTP ' + res.status), res.status, json.code)
  },
  /* 非 2xx 或网络层失败 */
  (err) => {
    const cfg = err.config || { method: 'get', url: String(err.message) }
    const ms = Math.round(performance.now() - (cfg.meta?.start || performance.now()))
    if (!err.response) {
      // 连不上：后端未启动 / 代理未配 / 超时
      const base = useUiStore().apiBase || '同源 /api（dev 走 vite proxy → 8081）'
      pushLog(cfg, 'ERR', ms, false, '网络失败（后端未启动 / 代理不通）: ' + err.message)
      throw new ApiError('连不上后端 ' + base + '，请确认 8081 已启动', 0, 0)
    }
    const res = err.response
    const json = res.data ?? {}
    pushLog(cfg, res.status, ms, false, json.msg || res.statusText || err.message)
    if (res.status === 401 && !cfg.skipAuthRedirect) {
      useUiStore().toast('登录已过期，请重新登录', 'err')
      useAuthStore().logout(true)
      onUnauthorized()
    }
    throw new ApiError(json.msg || ('HTTP ' + res.status), res.status, json.code)
  },
)

/**
 * 业务调用入口：api(method, path, body, opts)
 * opts.skipAuthRedirect —— 登录/注册等接口 401 属"凭证错误"，不该触发登出跳转
 */
export function api(method, path, body, opts = {}) {
  return http.request({
    method,
    url: path,
    data: body,
    skipAuthRedirect: !!opts.skipAuthRedirect,
  })
}

export default http
