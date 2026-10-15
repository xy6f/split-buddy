<script setup>
/* 成员（移植旧版 §9）：成员名单 + 按用户名邀请
 * 演进说明：后端补 GET /api/ledgers/{id}/members 之前，本页只能用"净额记录 + 本浏览器
 * 登录过的身份"倒推成员（降级逻辑已删除，git 历史可查）。降级有两个硬伤：
 * 零净额成员（刚入伙还没结转过的人）会被漏掉，且换一台浏览器名单就缺人。
 * 现在直接读真接口，名单权威来源是 t_ledger_member join t_user。 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Backend } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useLedgerStore } from '@/stores/ledger'
import { useUiStore } from '@/stores/ui'
import EmptyState from '@/components/EmptyState.vue'

const auth = useAuthStore()
const ledger = useLedgerStore()
const ui = useUiStore()
const router = useRouter()

const loading = ref(true)
const members = ref([]) // MemberVO[]：{userId, nickname, joinedAt}
const inviteName = ref('')

async function loadMembers() {
  const lid = ledger.currentLedgerId
  if (!lid) {
    members.value = []
    return
  }
  members.value = await Backend.members(lid)
  // 顺手把名单汇入本地通讯录：资金流向图等仍在用 auth.nick() 的地方也一并更准
  members.value.forEach((m) => { auth.directory[m.userId] = m.nickname })
  auth.persist()
}

onMounted(async () => {
  try {
    if (!ledger.ledgers.length) await ledger.refreshLedgers()
    if (ledger.currentLedgerId) {
      await Promise.all([
        loadMembers().catch(() => {}),
        ledger.refreshLedgerData().catch(() => {}),
      ])
    }
  } finally {
    loading.value = false
  }
})

async function invite() {
  await ui.withBusy(async () => {
    const username = inviteName.value.trim()
    if (!username) throw new Error('请输入用户名')
    await Backend.invite(ledger.currentLedgerId, username)
    ui.toast('已邀请 ' + username, 'ok')
    inviteName.value = ''
    await Promise.all([
      loadMembers().catch(() => {}),
      ledger.refreshLedgerData().catch(() => {}),
    ])
  })
}
</script>

<template>
  <div>
    <!-- 未选账本 -->
    <template v-if="!loading && !ledger.currentLedgerId">
      <div class="view-head"><h2>成员</h2></div>
      <EmptyState title="还没有选中账本" hint="去「概览」选择或新建账本">
        <template #action>
          <button class="btn primary" @click="router.push('/overview')">去概览</button>
        </template>
      </EmptyState>
    </template>

    <template v-else>
      <div class="view-head">
        <div>
          <h2>成员</h2>
          <p class="muted">账本「{{ ledger.currentLedger?.name || ledger.currentLedgerId }}」· 按用户名邀请</p>
        </div>
      </div>

      <div class="grid cols2">
        <div class="glass card stack">
          <b style="font-size: 14.5px">成员名单</b>
          <p class="muted" style="margin: -6px 0 6px">
            来源：GET /api/ledgers/{id}/members 真接口（join 成员表 + 用户表，按加入时间排序）
          </p>
          <div class="stack" style="gap: 8px">
            <div v-for="m in members" :key="m.userId" class="split-row on">
              <span class="avatar" style="width: 26px; height: 26px; font-size: 11px">
                {{ m.nickname.slice(0, 1) }}
              </span>
              <span class="split-name">{{ m.nickname }}</span>
              <span class="faint mono">id={{ m.userId }}</span>
              <span class="faint mono">{{ (m.joinedAt || '').slice(0, 10) }}</span>
              <span v-if="m.userId === auth.session?.userId" class="badge ok">我</span>
            </div>
            <div v-if="!members.length && !loading" class="muted">（暂无记录）</div>
            <div v-if="loading" class="muted">加载中…</div>
          </div>
        </div>

        <div class="glass card stack">
          <b style="font-size: 14.5px">邀请成员</b>
          <label class="field">对方的用户名（需已注册）</label>
          <el-input v-model="inviteName" class="mono" placeholder="如：charlie" @keyup.enter="invite" />
          <div class="row end">
            <button class="btn primary" :disabled="ui.busy" @click="invite">
              <svg viewBox="0 0 24 24" width="15" height="15">
                <path d="M4 12h13m0 0-5-5m5 5-5 5" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              邀请入伙
            </button>
          </div>
          <p class="faint">邀请即时生效，无需对方确认（MVP 简化）。</p>
        </div>
      </div>
    </template>
  </div>
</template>
