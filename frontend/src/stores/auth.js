/* ==========================================================================
 * auth store：登录态 + 双身份演示 + 本地通讯录
 *  - session：当前身份 {token,userId,nickname,username}
 *  - identities：多身份登记表 {username: session}（一键演示注册 alice/bob 后，
 *    顶栏"切换"按钮在这些令牌之间轮转，模拟两个真人来回记账）
 *  - directory：userId -> nickname（登录/净额接口累积的本地通讯录）
 * 令牌只进 sessionStorage —— 关标签即失效，贴合"登录态"语义（与旧版一致）
 * ========================================================================== */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { Backend } from '@/api'

const K = {
  session: 'sb.session',
  identities: 'sb.identities',
  directory: 'sb.directory',
}

function load(key, fallback) {
  try { return JSON.parse(sessionStorage.getItem(key)) ?? fallback }
  catch { return fallback }
}

export const useAuthStore = defineStore('auth', () => {
  const session = ref(load(K.session, null))          // 当前身份
  const identities = ref(load(K.identities, {}))      // {username: session}
  const directory = ref(load(K.directory, {}))        // userId -> nickname

  const isAuthed = computed(() => !!session.value)
  const hasMultiple = computed(() => Object.keys(identities.value).length > 1)

  function persist() {
    if (session.value) sessionStorage.setItem(K.session, JSON.stringify(session.value))
    else sessionStorage.removeItem(K.session)
    sessionStorage.setItem(K.identities, JSON.stringify(identities.value))
    sessionStorage.setItem(K.directory, JSON.stringify(directory.value))
  }

  function nick(id) {
    return directory.value[id] || ('用户#' + id)
  }

  function setIdentity(sess) {
    session.value = sess
    if (sess) {
      directory.value[sess.userId] = sess.nickname
      persist()
    }
  }

  /* ---------- 认证动作 ---------- */
  async function doLogin(username, password) {
    const d = await Backend.login({ username, password })
    const sess = { token: d.token, userId: d.userId, nickname: d.nickname, username }
    setIdentity(sess)
    return sess
  }

  async function doRegister(username, password, nickname) {
    await Backend.register({ username, password, nickname })
  }

  /* ---------- 双身份切换：在已登录身份间轮转 ---------- */
  function switchIdentity() {
    const list = Object.values(identities.value)
    if (!list.length || !session.value) return null
    const idx = list.findIndex((x) => x.token === session.value.token)
    const next = list[(idx + 1) % list.length]
    setIdentity(next)
    return next
  }

  function rememberIdentity(sess) {
    if (sess && sess.username) identities.value[sess.username] = sess
    persist()
  }

  function logout(silent) {
    session.value = null
    identities.value = {}
    persist()
    if (!silent) return '已退出登录'
  }

  return {
    session, identities, directory,
    isAuthed, hasMultiple,
    persist, nick, setIdentity, doLogin, doRegister,
    switchIdentity, rememberIdentity, logout,
  }
})
