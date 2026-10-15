<script setup>
/* 概览（移植旧版 §6）：账本卡片列表 + 当前账本净额发散条形图 + 新建账本 */
import { onMounted, ref } from 'vue'
import { Backend } from '@/api'
import { useLedgerStore } from '@/stores/ledger'
import { useUiStore } from '@/stores/ui'
import { netLabel, cls } from '@/utils/format'
import NetBarsChart from '@/components/NetBarsChart.vue'
import EmptyState from '@/components/EmptyState.vue'

const ledger = useLedgerStore()
const ui = useUiStore()
const loading = ref(true)

const newDialog = ref(false)
const newName = ref('')

onMounted(async () => {
  try {
    if (!ledger.ledgers.length) await ledger.refreshLedgers()
    if (ledger.currentLedgerId) await ledger.refreshLedgerData().catch(() => {})
  } catch (e) {
    ui.toast(e.message, 'err')
  } finally {
    loading.value = false
  }
})

async function pick(id) {
  await ui.withBusy(async () => {
    await ledger.selectLedger(id)
  })
}

async function createLedger() {
  const name = newName.value.trim()
  if (!name) return
  await ui.withBusy(async () => {
    const l = await Backend.createLedger(name)
    await ledger.refreshLedgers()
    await ledger.selectLedger(l.id)
    ui.toast('账本「' + l.name + '」已创建', 'ok')
    newDialog.value = false
    newName.value = ''
  })
}
</script>

<template>
  <div>
    <div class="view-head">
      <div>
        <h2>概览</h2>
        <p class="muted">我的账本与净额一览</p>
      </div>
      <button class="btn primary" @click="newDialog = true">
        <svg viewBox="0 0 24 24" width="15" height="15">
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
        </svg>
        新建账本
      </button>
    </div>

    <div v-if="loading" class="glass card loading-block">
      <div class="spinner"></div>
      <div class="muted">加载中…</div>
    </div>

    <div v-else class="grid cols2">
      <!-- 左：账本列表 -->
      <div class="stack">
        <div class="glass card">
          <EmptyState v-if="!ledger.ledgers.length" title="还没有账本" hint="建一个账本，邀请朋友一起 AA 吧" />
          <template v-else>
            <div class="row between mb16">
              <b style="font-size: 14px">我的账本（{{ ledger.ledgers.length }}）</b>
              <span class="badge ok">实时净额</span>
            </div>
            <div class="grid" style="grid-template-columns: repeat(auto-fill, minmax(210px, 1fr))">
              <div
                v-for="l in ledger.ledgers"
                :key="l.id"
                class="glass card hoverable ledger-card"
                :class="{ selected: l.id === ledger.currentLedgerId }"
                @click="pick(l.id)"
              >
                <span class="ledger-net" :class="cls(l.myNet)">{{ netLabel(l.myNet) }}</span>
                <h3>{{ l.name }}</h3>
                <div class="faint">ID {{ l.id }} · 币种 {{ l.currency || 'CNY' }}</div>
                <div class="muted" style="margin-top: 8px; font-size: 12px">
                  {{ l.id === ledger.currentLedgerId ? '● 当前查看' : '点击查看' }}
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- 右：净额发散条形图 -->
      <div class="stack">
        <div class="glass card">
          <b style="font-size: 14px">当前账本净额</b>
          <p class="muted" style="margin: 2px 0 10px">中轴为 0，右绿=别人欠我，左红=我欠别人</p>
          <div class="chart-wrap">
            <NetBarsChart v-if="ledger.balances.length" :rows="ledger.balances" />
            <EmptyState v-else title="暂无净额记录" hint="记一笔支出后，这里会出现每人净额" />
          </div>
        </div>
      </div>
    </div>

    <!-- 新建账本弹层 -->
    <el-dialog v-model="newDialog" class="sb-dialog" title="新建账本" width="400px" append-to-body>
      <label class="field">账本名称</label>
      <el-input v-model="newName" placeholder="如：周末聚餐 AA" maxlength="64" @keyup.enter="createLedger" />
      <template #footer>
        <button class="btn ghost" @click="newDialog = false">取消</button>
        <button class="btn primary" style="margin-left: 10px" :disabled="!newName.trim() || ui.busy" @click="createLedger">创建</button>
      </template>
    </el-dialog>
  </div>
</template>
