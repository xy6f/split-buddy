<script setup>
/* 记账（移植旧版 §7）：新增支出表单（EQUAL 均摊 / AMOUNT 按金额）+ 明细分页表 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Backend } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useLedgerStore } from '@/stores/ledger'
import { useUiStore } from '@/stores/ui'
import { fmt, todayStr } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'

const auth = useAuthStore()
const ledger = useLedgerStore()
const ui = useUiStore()
const router = useRouter()

const loading = ref(true)
const splitMode = ref('EQUAL') // EQUAL=均摊 / AMOUNT=按金额指定

const form = reactive({
  title: '',
  amount: '',
  date: todayStr(),
  paidBy: null,
})
/* 参与分摊：uid -> { picked: bool, amt: number } */
const splitSel = reactive({})

/* 候选成员：净额记录 + 本机登录过的身份 + 当前自己（与旧版 memberCandidates 一致） */
const candidates = computed(() => {
  const ids = new Set()
  ledger.balances.forEach((b) => ids.add(b.userId))
  Object.values(auth.identities).forEach((i) => ids.add(i.userId))
  if (auth.session) ids.add(auth.session.userId)
  return [...ids].map((id) => ({ userId: id, nickname: auth.nick(id) }))
})

function syncSel() {
  const selfId = auth.session?.userId
  candidates.value.forEach((m) => {
    if (!(m.userId in splitSel)) {
      splitSel[m.userId] = { picked: m.userId === selfId, amt: 0 }
    }
  })
  if (form.paidBy == null) form.paidBy = selfId
}

syncSel() // setup 阶段先补齐选中态，避免首帧渲染时 splitSel 为空
onMounted(async () => {
  try {
    if (!ledger.ledgers.length) await ledger.refreshLedgers()
    if (ledger.currentLedgerId) await ledger.refreshLedgerData().catch(() => {})
    syncSel()
  } finally {
    loading.value = false
  }
})

watch(candidates, syncSel)

/* AMOUNT 模式合计提示 */
const pickedRows = computed(() =>
  candidates.value.filter((m) => splitSel[m.userId]?.picked),
)
const amtSum = computed(() =>
  pickedRows.value.reduce((a, m) => a + (Number(splitSel[m.userId].amt) || 0), 0),
)
const sumOk = computed(() => {
  const total = Number(form.amount) || 0
  return amtSum.value === total && total > 0
})

async function switchLedger(id) {
  await ui.withBusy(async () => {
    await ledger.selectLedger(id)
    syncSel()
  })
}

async function submit() {
  await ui.withBusy(async () => {
    const title = form.title.trim()
    const amount = Number(form.amount)
    const paidBy = Number(form.paidBy)
    const picked = pickedRows.value.map((m) => m.userId)
    if (!title) throw new Error('标题不能为空')
    if (!(amount > 0)) throw new Error('金额必须大于 0')
    if (!picked.length) throw new Error('至少选择一位参与分摊的人')
    let splits
    if (splitMode.value === 'EQUAL') {
      splits = picked.map((uid) => ({ userId: uid, shareType: 'EQUAL', share: 0 }))
    } else {
      splits = picked.map((uid) => ({
        userId: uid,
        shareType: 'AMOUNT',
        share: Number(splitSel[uid].amt) || 0,
      }))
      if (Math.abs(amtSum.value - amount) > 0.004) {
        throw new Error('按金额分摊的合计必须等于总金额')
      }
    }
    await Backend.addExpense(ledger.currentLedgerId, {
      title, amount, paidBy, expenseDate: form.date || todayStr(), splits,
    })
    ui.toast('已记一笔：' + title, 'ok')
    form.title = ''
    form.amount = ''
    await Promise.all([
      ledger.refreshLedgers(),
      ledger.refreshLedgerData(),
    ]).catch(() => {})
  })
}

async function del(row) {
  try {
    await ElMessageBox.confirm('删除这笔支出？', '确认删除', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  await ui.withBusy(async () => {
    await Backend.deleteExpense(row.id, row.version)
    ui.toast('已删除', 'ok')
    await Promise.all([ledger.refreshLedgers(), ledger.refreshLedgerData()])
  })
}

async function gotoPage(delta) {
  const p = ledger.expenses
  p.page += delta
  await ui.withBusy(() => ledger.refreshLedgerData())
}

const pages = computed(() =>
  Math.max(1, Math.ceil(ledger.expenses.total / ledger.expenses.size)),
)
</script>

<template>
  <div>
    <!-- 无账本降级：引导去概览 -->
    <template v-if="!loading && !ledger.ledgers.length && !ledger.currentLedgerId">
      <div class="view-head"><h2>记账</h2></div>
      <EmptyState title="先有一个账本" hint="去「概览」页新建一个账本再来记一笔">
        <template #action>
          <button class="btn primary" @click="router.push('/overview')">去概览建账本</button>
        </template>
      </EmptyState>
    </template>

    <template v-else>
      <div class="view-head">
        <div>
          <h2>记账</h2>
          <p class="muted">记一笔共同支出，选择付款人与参与人</p>
        </div>
        <el-select
          :model-value="ledger.currentLedgerId"
          style="width: 220px"
          @change="switchLedger"
        >
          <el-option v-for="l in ledger.ledgers" :key="l.id" :label="l.name" :value="l.id" />
        </el-select>
      </div>

      <div class="grid cols2">
        <!-- 左：新增支出 -->
        <div class="glass card stack">
          <b style="font-size: 14.5px">新增支出</b>
          <div class="form-grid">
            <div>
              <label class="field">标题</label>
              <el-input v-model="form.title" placeholder="如：海底捞一顿" maxlength="128" />
            </div>
            <div>
              <label class="field">金额（元）</label>
              <el-input v-model="form.amount" type="number" min="0.01" step="0.01" placeholder="0.00" />
            </div>
            <div>
              <label class="field">日期</label>
              <input v-model="form.date" class="input" type="date" />
            </div>
            <div>
              <label class="field">付款人</label>
              <select v-model="form.paidBy" class="input">
                <option v-for="m in candidates" :key="m.userId" :value="m.userId">{{ m.nickname }}</option>
              </select>
            </div>
          </div>

          <div class="row between" style="margin-top: 4px">
            <label class="field" style="margin: 0">分摊方式</label>
            <div class="auth-tabs" style="margin: 0; width: auto">
              <button :class="{ active: splitMode === 'EQUAL' }" @click="splitMode = 'EQUAL'">均摊</button>
              <button :class="{ active: splitMode === 'AMOUNT' }" @click="splitMode = 'AMOUNT'">按金额</button>
            </div>
          </div>

          <div class="stack" style="gap: 8px">
            <div
              v-for="m in candidates"
              :key="m.userId"
              class="split-row"
              :class="splitSel[m.userId]?.picked ? 'on' : 'off'"
            >
              <el-checkbox v-model="splitSel[m.userId].picked" />
              <span class="split-name">
                {{ m.nickname }}
                <span v-if="m.userId === auth.session?.userId" class="badge">我</span>
              </span>
              <span v-if="splitMode === 'EQUAL'" class="split-eq">均摊</span>
              <el-input
                v-else
                v-model="splitSel[m.userId].amt"
                type="number" min="0" step="0.01" style="width: 110px" placeholder="0.00"
              />
            </div>
            <div v-if="!candidates.length" class="muted">
              暂无可选成员：先在「成员」页邀请，或用左下演示模式初始化。
            </div>
          </div>

          <div class="row between">
            <span
              v-if="splitMode === 'AMOUNT'"
              class="faint"
              :style="{ color: sumOk ? 'var(--grad-a)' : 'var(--warn)' }"
            >
              已指定 {{ fmt(amtSum) }} / 总额 {{ fmt(Number(form.amount) || 0) }}
              <template v-if="sumOk"> ✓</template>
            </span>
            <span v-else></span>
            <button class="btn primary" :disabled="ui.busy || loading" @click="submit">
              <svg viewBox="0 0 24 24" width="14" height="14">
                <path d="M13 2 4.5 13.5H11L9.5 22 18 10.5h-6.5L13 2Z" fill="currentColor" />
              </svg>
              记一笔
            </button>
          </div>
        </div>

        <!-- 右：支出明细（分页 + 乐观锁删除） -->
        <div class="glass card">
          <div class="row between mb16">
            <b style="font-size: 14.5px">支出明细</b>
            <span class="badge">共 {{ ledger.expenses.total }} 笔</span>
          </div>
          <EmptyState v-if="!ledger.expenses.records.length" title="还没有支出" hint="在左侧记下第一笔共同开销吧" />
          <template v-else>
            <table class="tbl">
              <thead>
                <tr>
                  <th>日期</th><th>标题</th><th class="t-right">金额</th>
                  <th>付款人</th><th>参与</th><th class="t-right">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="e in ledger.expenses.records" :key="e.id">
                  <td class="faint mono">{{ e.expenseDate }}</td>
                  <td><b>{{ e.title }}</b></td>
                  <td class="t-right money"><b>{{ fmt(e.amount) }}</b></td>
                  <td>{{ auth.nick(e.paidBy) }}</td>
                  <td class="faint">{{ (e.splits || []).map((s) => auth.nick(s.userId)).join('、') }}</td>
                  <td class="t-right">
                    <button
                      class="btn sm ghost icon"
                      :title="`删除（乐观锁 version=${e.version}）`"
                      @click="del(e)"
                    >
                      <svg viewBox="0 0 24 24" width="14" height="14">
                        <path d="M5 7h14M10 4h4m-7 3 .6 13a2 2 0 0 0 2 1.9h3.6a2 2 0 0 0 2-1.9L18 7M10 11v6m4-6v6" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
            <div class="pager">
              <button class="btn sm ghost" :disabled="ledger.expenses.page <= 1" @click="gotoPage(-1)">← 上一页</button>
              <span class="muted">第 {{ ledger.expenses.page }} / {{ pages }} 页</span>
              <button class="btn sm ghost" :disabled="ledger.expenses.page >= pages" @click="gotoPage(1)">下一页 →</button>
            </div>
          </template>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* EP 复选框贴齐自家配色 */
.split-row :deep(.el-checkbox__inner) {
  background: transparent;
  border-color: var(--line-strong);
}
.split-row.on :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background: var(--grad);
  border-color: transparent;
}
</style>
