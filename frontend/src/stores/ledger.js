/* ==========================================================================
 * ledger store：账本列表 / 当前账本 / 净额 / 支出明细分页
 * currentLedgerId 进 sessionStorage（刷新后回到上次看的账本，与旧版一致）
 * ========================================================================== */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { Backend } from '@/api'
import { useAuthStore } from './auth'

export const useLedgerStore = defineStore('ledger', () => {
  const ledgers = ref([])                     // LedgerVO[]
  const currentLedgerId = ref(Number(sessionStorage.getItem('sb.cur')) || null)
  const balances = ref([])                    // [{userId,nickname,net}]
  const expenses = ref({ records: [], total: 0, page: 1, size: 10 })

  const currentLedger = computed(
    () => ledgers.value.find((l) => l.id === currentLedgerId.value) || null,
  )

  async function refreshLedgers() {
    ledgers.value = await Backend.myLedgers()
    if (!ledgers.value.some((l) => l.id === currentLedgerId.value)) {
      currentLedgerId.value = ledgers.value.length ? ledgers.value[0].id : null
    }
    persistCur()
  }

  /** 并行拉净额 + 明细分页；顺手把 userId->nickname 汇入本地通讯录 */
  async function refreshLedgerData() {
    const lid = currentLedgerId.value
    if (!lid) {
      balances.value = []
      expenses.value = { records: [], total: 0, page: 1, size: 10 }
      return
    }
    const auth = useAuthStore()
    const [bal, page] = await Promise.all([
      Backend.balances(lid),
      Backend.expensesPage(lid, expenses.value.page, expenses.value.size),
    ])
    balances.value = bal
    expenses.value = page
    bal.forEach((b) => { auth.directory[b.userId] = b.nickname })
    auth.persist()
  }

  async function selectLedger(id) {
    currentLedgerId.value = Number(id)
    expenses.value.page = 1
    persistCur()
    await refreshLedgerData()
  }

  function persistCur() {
    if (currentLedgerId.value) sessionStorage.setItem('sb.cur', String(currentLedgerId.value))
    else sessionStorage.removeItem('sb.cur')
  }

  function clear() {
    ledgers.value = []
    currentLedgerId.value = null
    balances.value = []
    expenses.value = { records: [], total: 0, page: 1, size: 10 }
    persistCur()
  }

  return {
    ledgers, currentLedgerId, balances, expenses, currentLedger,
    refreshLedgers, refreshLedgerData, selectLedger, clear,
  }
})
