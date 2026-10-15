/* ==========================================================================
 * 双人演示模式（移植自旧版 §11）
 * 一键初始化：注册 alice/bob → 双账号登录 → 建账本 → 邀请乙 → 记两笔均摊支出
 * 已存在的账号/账本/支出容错复用，重复点击不炸。
 * demoState.step 供 DemoZone 渲染步骤进度（-1 未开始）
 * ========================================================================== */
import { reactive } from 'vue'
import { Backend } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useLedgerStore } from '@/stores/ledger'
import { useUiStore } from '@/stores/ui'
import { todayStr } from '@/utils/format'

export const DEMO_PEOPLE = [
  { username: 'alice', password: 'alice123', nickname: '甲 Alice' },
  { username: 'bob', password: 'bob123', nickname: '乙 Bob' },
]
export const DEMO_STEPS = ['注册 alice / bob', '双账号登录', '创建账本', '邀请入伙', '记两笔支出', '净额就绪']

export const demoState = reactive({ step: -1 })

export async function demoInit(router) {
  const auth = useAuthStore()
  const ledger = useLedgerStore()
  const ui = useUiStore()
  const mark = (i) => { demoState.step = i }

  mark(0)
  for (const p of DEMO_PEOPLE) {
    try {
      await Backend.register(p)
    } catch (e) {
      // 用户名已被占用 = 之前初始化过，直接复用旧账号继续
      if (!(e.status === 409 || String(e.message).includes('占用'))) {
        throw new Error('注册失败：' + e.message)
      }
    }
  }

  mark(1)
  // 依次登录两个账号，各自令牌登记进 identities（顶栏出现"切换"按钮）
  // 容错：同名账号若被历史数据占用且密码并非演示密码（401），自动回退到 *_demo 专用账号，
  // 保证"重复点击不炸 / 共享环境下演示可跑通"；正常环境下仍走 alice/bob 主路径。
  const sessions = []
  for (const p of DEMO_PEOPLE) {
    let s
    try {
      s = await auth.doLogin(p.username, p.password)
    } catch (e) {
      if (!(e.status === 401 || e.code === 40101)) throw new Error('演示登录失败：' + e.message)
      const fb = { username: p.username + '_demo', password: p.password, nickname: p.nickname }
      try {
        await Backend.register(fb)
      } catch (e2) {
        if (!(e2.status === 409 || String(e2.message).includes('占用'))) {
          throw new Error('回退账号注册失败：' + e2.message)
        }
      }
      s = await auth.doLogin(fb.username, fb.password)
      ui.toast('演示账号 ' + p.username + ' 已存在且密码非演示密码，已用 ' + fb.username + ' 继续', 'info')
    }
    sessions.push(s)
    auth.rememberIdentity(s)
  }

  mark(2)
  // 以甲的身份建账本（创建人自动是成员）
  auth.setIdentity(sessions[0])
  await ledger.refreshLedgers()
  let l = ledger.ledgers.find((x) => x.name === '旅行 AA · 演示')
  if (!l) l = await Backend.createLedger('旅行 AA · 演示')
  await ledger.selectLedger(l.id)

  mark(3)
  try {
    await Backend.invite(l.id, sessions[1].username)
    ui.toast('已邀请 乙 Bob 入伙', 'ok')
  } catch (e) {
    // 已是成员等情况容错跳过
    if (e.status !== 400 && e.status !== 409 && !/存在|already/i.test(e.message)) throw e
  }

  // 取 bob 的 userId（登录响应里就有——这正是分离前端可自取的成员信息）
  const bobId = sessions[1].userId
  const aliceId = sessions[0].userId

  mark(4)
  const eq = (uids) => uids.map((u) => ({ userId: u, shareType: 'EQUAL', share: 0 }))
  const existing = await Backend.expensesPage(l.id, 1, 50).catch(() => ({ records: [] }))
  const has = (title) => (existing.records || []).some((r) => r.title === title)
  if (!has('高铁票')) {
    await Backend.addExpense(l.id, { title: '高铁票', amount: 100, paidBy: aliceId, expenseDate: todayStr(), splits: eq([aliceId, bobId]) })
  }
  if (!has('景区门票')) {
    await Backend.addExpense(l.id, { title: '景区门票', amount: 60, paidBy: bobId, expenseDate: todayStr(), splits: eq([aliceId, bobId]) })
  }

  mark(5)
  await ledger.refreshLedgers()
  await ledger.refreshLedgerData()
  ui.toast('演示数据就绪：切「概览」看净额图，进「结清」生成方案并执行', 'ok')
  if (router) router.push('/overview')
}
