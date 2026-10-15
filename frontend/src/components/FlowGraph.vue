<script setup>
/* ==========================================================================
 * 结清方案资金流向图（手绘 SVG）——移植旧版 renderPlanGraph
 * 节点沿椭圆分布，转账为二次贝塞尔箭头；描线动画（dasharray 逐条错峰）
 * ========================================================================== */
import { computed } from 'vue'
import { fmt, netLabel } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const props = defineProps({
  plan: { type: Object, required: true },      // {transfers:[{fromUser,toUser,amount,...}], algorithm}
  balances: { type: Array, default: () => [] },// 用于节点上显示每人净额
})

const auth = useAuthStore()
const uid = Math.random().toString(36).slice(2, 7) // 实例级唯一，防渐变 id 冲突

const W = 560, H = 300
const CX = W / 2, CY = H / 2
const R = Math.min(W, H) / 2 - 70

const model = computed(() => {
  const ids = []
  props.plan.transfers.forEach((t) => {
    if (!ids.includes(t.fromUser)) ids.push(t.fromUser)
    if (!ids.includes(t.toUser)) ids.push(t.toUser)
  })
  const pos = {}
  ids.forEach((id, i) => {
    const a = -Math.PI / 2 + (i * 2 * Math.PI) / ids.length
    pos[id] = { x: CX + R * Math.cos(a) * 1.25, y: CY + R * Math.sin(a) }
  })
  const netOf = (id) => {
    const b = props.balances.find((x) => x.userId === id)
    return b ? Number(b.net) : 0
  }
  const nameOf = (t, u) => (t && (t.fromUser === u ? t.fromNickname : t.toNickname)) || auth.nick(u)

  const flows = props.plan.transfers.map((t, i) => {
    const A = pos[t.fromUser], B = pos[t.toUser]
    // 中点向圆心反方向抬一点形成弧线；双向转账天然分开
    const mx = (A.x + B.x) / 2, my = (A.y + B.y) / 2
    const vx = mx - CX, vy = my - CY, vl = Math.hypot(vx, vy) || 1
    const Cx = mx + (vx / vl) * 26, Cy = my + (vy / vl) * 26
    const lx = (A.x + 2 * Cx + B.x) / 4, ly = (A.y + 2 * Cy + B.y) / 4
    return {
      t, i,
      d: `M ${A.x} ${A.y} Q ${Cx} ${Cy} ${B.x} ${B.y}`,
      lx, ly,
      label: `${nameOf(t, t.fromUser)} → ${nameOf(t, t.toUser)}`,
    }
  })

  const nodes = ids.map((id, i) => {
    const p = pos[id]
    const net = netOf(id)
    const t = props.plan.transfers.find((x) => x.fromUser === id || x.toUser === id)
    /* 演进说明：旧版此处是 (nameOf(...) || '?').slice(0, 4)——固定 4 字截断省空间，
     * 但"王小明"变"王小"难辨认。现改为显示完整昵称，防溢出靠两手：
     * 1) 字号随名字长度分档收缩（12.5px 起，逐步收到 9.5px 封底）；
     * 2) 节点形状按估算宽度横向拉长（圆 -> 椭圆，rx 自适应），文字永不越出节点边界 */
    const name = nameOf(t, id) || '?'
    const chars = Array.from(name)
    const fs = chars.length <= 4 ? 12.5 : Math.max(9.5, 50 / chars.length)
    // 宽度估算：CJK 全角字按 1em，字母数字标点按 0.6em（12.5px 下约等于真实渲染宽）
    const estW = chars.reduce((w, c) => w + (c.charCodeAt(0) > 0x2e7f ? fs : fs * 0.6), 0)
    // ry 固定 27：竖向要容纳"昵称 + 净额"两行文字，与旧圆半径一致
    return { id, i, x: p.x, y: p.y, net, name, fs, rx: Math.max(27, estW / 2 + 9), ry: 27 }
  })

  return { flows, nodes }
})
</script>

<template>
  <svg :viewBox="`0 0 ${W} ${H}`" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient :id="`flowGrad-${uid}`" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" stop-color="#34d399" /><stop offset="1" stop-color="#38bdf8" />
      </linearGradient>
      <linearGradient :id="`nodeGrad-${uid}`" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#34d399" /><stop offset="1" stop-color="#38bdf8" />
      </linearGradient>
      <marker :id="`flowArrow-${uid}`" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
        <path d="M0 0 10 5 0 10Z" fill="#38bdf8" />
      </marker>
    </defs>

    <!-- 轨道虚线椭圆 -->
    <ellipse :cx="CX" :cy="CY" :rx="R * 1.25 + 10" :ry="R + 10" fill="none"
      stroke="rgba(255,255,255,.05)" stroke-dasharray="4 6" />

    <!-- 资金流向箭头 -->
    <g v-for="f in model.flows" :key="f.i">
      <path
        class="flow dash-anim"
        :style="{ animationDelay: `${180 + f.i * 260}ms`, '--len': 100 }"
        :d="f.d" :pathLength="100"
        stroke-dasharray="100" stroke-dashoffset="0" fill="none"
        :stroke="`url(#flowGrad-${uid})`" stroke-width="2.4"
        :marker-end="`url(#flowArrow-${uid})`"
      />
      <text class="flow-label pulse" :style="{ animationDelay: `${380 + f.i * 260}ms` }"
        :x="f.lx" :y="f.ly - 8" text-anchor="middle"
        stroke="#0b1020" stroke-width="5" paint-order="stroke">
        {{ fmt(f.t.amount) }}
      </text>
      <text class="node-sub" :x="f.lx" :y="f.ly + 8" text-anchor="middle">{{ f.label }}</text>
    </g>

    <!-- 人物节点 -->
    <g v-for="n in model.nodes" :key="n.id" class="pulse"
      :style="{ animationDelay: `${n.i * 120}ms`, transformOrigin: `${n.x}px ${n.y}px` }">
      <!-- 椭圆节点：短名近似正圆（rx=27），长昵称自动拉长，字号同步收缩，杜绝溢出 -->
      <ellipse :cx="n.x" :cy="n.y" :rx="n.rx" :ry="n.ry" fill="#131a2e"
        :stroke="`url(#nodeGrad-${uid})`" stroke-width="2" />
      <text class="node-label" :style="{ fontSize: n.fs + 'px' }"
        :x="n.x" :y="n.y - 2" text-anchor="middle">{{ n.name }}</text>
      <text class="node-sub" :x="n.x" :y="n.y + 12" text-anchor="middle"
        :fill="n.net >= 0 ? '#34d399' : '#f87171'">
        {{ netLabel(n.net) }}
      </text>
    </g>
  </svg>
</template>
