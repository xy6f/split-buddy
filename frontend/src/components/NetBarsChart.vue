<script setup>
/* ==========================================================================
 * 净额发散条形图（手绘 SVG，不引 echarts）——移植旧版 renderBalancesInto
 *  - 中轴 0：右绿 = 别人欠我（net>0），左红 = 我欠别人（net<0）
 *  - fromRows → rows 补间：宽度和数字一起 650ms easeOutCubic 走过去
 *    （执行结清后传 fromRows=旧净额、rows=新净额，即"归零动画"）
 * ========================================================================== */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { netLabel, cls, fmt } from '@/utils/format'

const props = defineProps({
  rows: { type: Array, required: true },       // [{userId,nickname,net}] 目标
  fromRows: { type: Array, default: () => [] },// 动画起点（可省略 = 从 0 长出）
})

const W = 620
const ROW_H = 34
const TX0 = 96, TX1 = 520            // 轨道左右端
const AXIS = (TX0 + TX1) / 2         // 0 中轴
const HALF = (TX1 - TX0) / 2
const DUR = 650

/* 合并 from/to 键集合：被移除的人从旧值收拢到 0 */
const series = computed(() => {
  const map = new Map()
  props.fromRows.forEach((r) => {
    map.set(r.userId, { userId: r.userId, nickname: r.nickname, from: Number(r.net), to: 0 })
  })
  props.rows.forEach((r) => {
    const f = map.get(r.userId)
    map.set(r.userId, { userId: r.userId, nickname: r.nickname, from: f ? f.from : 0, to: Number(r.net) })
  })
  const all = [...map.values()]
  const maxAbs = Math.max(0.01, ...all.map((k) => Math.max(Math.abs(k.from), Math.abs(k.to))))
  return { keys: all, maxAbs }
})

/* rAF 补间：live[i] = 当前瞬时值 */
const live = ref([])
let raf = 0

function runTween() {
  cancelAnimationFrame(raf)
  const { keys, maxAbs } = series.value
  live.value = keys.map((k) => k.from)
  const t0 = performance.now()
  const step = (now) => {
    const t = Math.min(1, (now - t0) / DUR)
    const e = 1 - Math.pow(1 - t, 3) // easeOutCubic
    live.value = keys.map((k) => k.from + (k.to - k.from) * e)
    if (t < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
}

function barRect(v) {
  const w = Math.abs(v) / series.value.maxAbs * HALF
  return v >= 0 ? { x: AXIS, width: w } : { x: AXIS - w, width: w }
}

watch(series, runTween, { immediate: true })
onBeforeUnmount(() => cancelAnimationFrame(raf))

const height = computed(() => Math.max(1, series.value.keys.length) * ROW_H + 8)
</script>

<template>
  <svg class="chart-wrap" :viewBox="`0 0 ${W} ${height}`" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="nb-pos" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" stop-color="#34d39933" /><stop offset="1" stop-color="#34d399" />
      </linearGradient>
      <linearGradient id="nb-neg" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" stop-color="#f87171" /><stop offset="1" stop-color="#f8717133" />
      </linearGradient>
    </defs>
    <g v-for="(k, i) in series.keys" :key="k.userId" :transform="`translate(0 ${8 + i * ROW_H})`">
      <text x="0" y="16" class="bal-name">{{ k.nickname }}</text>
      <!-- 轨道中轴 -->
      <line :x1="AXIS" y1="-4" :x2="AXIS" :y2="ROW_H - 10" stroke="var(--line-strong)" stroke-width="1" />
      <line :x1="TX0" y1="11" :x2="TX1" y2="11" stroke="var(--line)" stroke-width="1" />
      <!-- 发散条：pos 从中轴向右，neg 从中轴向左 -->
      <rect
        :x="barRect(live[i] ?? 0).x" y="3"
        :width="Math.max(barRect(live[i] ?? 0).width, 0.01)" height="16" rx="4"
        :fill="(live[i] ?? 0) >= 0 ? 'url(#nb-pos)' : 'url(#nb-neg)'"
      />
      <text x="616" y="16" text-anchor="end" class="bar-value" :class="cls(live[i] ?? 0)">
        {{ netLabel(live[i] ?? 0) }}
      </text>
    </g>
  </svg>
</template>

<style scoped>
.bal-name { font: 600 13px "Inter", system-ui, sans-serif; fill: var(--text); }
.bar-value { font: 700 13px "Inter", system-ui, sans-serif; font-variant-numeric: tabular-nums; }
.bar-value.pos { fill: var(--grad-a); }
.bar-value.neg { fill: var(--danger); }
.bar-value.zero { fill: var(--text-dim); }
</style>
