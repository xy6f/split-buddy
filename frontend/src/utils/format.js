/* 格式化工具（移植自旧版 §2 Utils） */
export function fmt(n) {
  const v = Number(n || 0)
  return '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function todayStr() {
  return new Date().toISOString().slice(0, 10)
}

export function cls(n) {
  return n > 0 ? 'pos' : n < 0 ? 'neg' : 'zero'
}

export function netLabel(n) {
  const v = Number(n)
  if (v > 0) return '+' + fmt(v)
  if (v < 0) return '−' + fmt(-v)
  return fmt(0)
}
