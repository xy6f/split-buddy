<script setup>
/* 结清（移植旧版 §8）：最少转账方案 + 资金流向图 + 一键执行 + 净额归零动画 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Backend } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useLedgerStore } from '@/stores/ledger'
import { useUiStore } from '@/stores/ui'
import { fmt } from '@/utils/format'
import FlowGraph from '@/components/FlowGraph.vue'
import NetBarsChart from '@/components/NetBarsChart.vue'
import EmptyState from '@/components/EmptyState.vue'

const auth = useAuthStore()
const ledger = useLedgerStore()
const ui = useUiStore()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const plan = ref(null)
/* 执行成功后的"归零动画"状态：fromRows=旧净额快照，rows=新净额（全 0） */
const zeroed = ref(false)
const fromSnapshot = ref([])

async function loadPlan() {
  loading.value = true
  error.value = ''
  zeroed.value = false
  plan.value = null
  if (!ledger.currentLedgerId) {
    loading.value = false
    return
  }
  try {
    await ledger.refreshLedgerData()
    plan.value = await Backend.plan(ledger.currentLedgerId)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(loadPlan)

async function execute() {
  await ui.withBusy(async () => {
    const rows = await Backend.executePlan(ledger.currentLedgerId)
    ui.toast(`结清完成：${rows.length} 笔转账已入账`, 'ok')
    // 保留旧净额作为动画起点 → 新数据全为 0 → 条形收拢归零
    fromSnapshot.value = ledger.balances.map((b) => ({ ...b }))
    await Promise.all([ledger.refreshLedgers(), ledger.refreshLedgerData()])
    zeroed.value = true
  })
}
</script>

<template>
  <div>
    <div class="view-head">
      <div>
        <h2>结清</h2>
        <p class="muted">最少转账次数方案（贪心 / 零和子集 DP），一键执行清账</p>
      </div>
    </div>

    <!-- 未选账本 -->
    <EmptyState
      v-if="!loading && !ledger.currentLedgerId"
      title="还没有选中账本"
      hint="去「概览」选一个账本"
    >
      <template #action>
        <button class="btn primary sm" @click="router.push('/overview')">去概览</button>
      </template>
    </EmptyState>

    <div v-else-if="loading" class="glass card loading-block">
      <div class="spinner"></div>
      <div class="muted">正在计算结清方案…</div>
    </div>

    <!-- 执行完成：归零动画 -->
    <div v-else-if="zeroed" class="glass card stack">
      <b style="font-size: 14.5px">净额归零动画</b>
      <div class="chart-wrap">
        <NetBarsChart :rows="ledger.balances" :from-rows="fromSnapshot" />
      </div>
      <div class="row between">
        <span class="badge ok">全部结清</span>
        <button class="btn primary sm" @click="loadPlan">返回结清页</button>
      </div>
    </div>

    <div v-else-if="error" class="glass card muted">{{ error }}</div>

    <!-- 已结清的账本 -->
    <EmptyState
      v-else-if="plan && !plan.transfers?.length"
      title="本账本已全部结清 🎉"
      hint="净额归零，无人欠人。可以记新的支出再回来看看"
    />

    <!-- 方案展示 -->
    <div v-else-if="plan" class="grid cols2">
      <div class="glass card chart-wrap">
        <div class="row between mb16">
          <b style="font-size: 14.5px">资金流向图</b>
          <span class="badge ok">{{ plan.algorithm || 'greedy' }}</span>
        </div>
        <FlowGraph :plan="plan" :balances="ledger.balances" />
        <p class="muted" style="margin: 6px 0 0">
          箭头 = 谁转给谁；共 <b>{{ plan.totalTransfers }}</b> 笔即可清零
        </p>
      </div>
      <div class="stack">
        <div class="glass card stack" style="gap: 10px">
          <b style="font-size: 14.5px">转账清单</b>
          <div v-for="(t, i) in plan.transfers" :key="i" class="transfer-item">
            <span class="transfer-who">
              {{ t.fromNickname || auth.nick(t.fromUser) }}
              <svg viewBox="0 0 24 24" width="16" height="16">
                <path d="M4 12h14m0 0-5-5m5 5-5 5" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              {{ t.toNickname || auth.nick(t.toUser) }}
            </span>
            <span class="transfer-amt money pos">{{ fmt(t.amount) }}</span>
          </div>
        </div>
        <button class="btn danger" style="justify-content: center; padding: 12px" :disabled="ui.busy" @click="execute">
          <svg viewBox="0 0 24 24" width="14" height="14">
            <path d="M13 2 4.5 13.5H11L9.5 22 18 10.5h-6.5L13 2Z" fill="currentColor" />
          </svg>
          一键执行结清（写账，confirm=true）
        </button>
        <p class="faint">执行 = 后端把每笔转账落库并更新净额，随后净额图将动画归零。</p>
      </div>
    </div>
  </div>
</template>
