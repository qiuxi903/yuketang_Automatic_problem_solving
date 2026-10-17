// 时间/文本格式化工具，统一全站展示口径

function toDate(value: string | number | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** 仅时分秒，用于日志时间戳 */
export function formatClock(value: string | number | Date | null | undefined): string {
  const date = toDate(value)
  if (!date) return '—'
  return date.toLocaleTimeString('zh-CN', { hour12: false })
}

/** 完整日期时间 */
export function formatDateTime(value: string | number | Date | null | undefined): string {
  const date = toDate(value)
  if (!date) return '—'
  const y = date.getFullYear()
  const m = `${date.getMonth() + 1}`.padStart(2, '0')
  const d = `${date.getDate()}`.padStart(2, '0')
  return `${y}-${m}-${d} ${formatClock(date)}`
}

/** 相对时间，用于事件流 */
export function relativeTime(at: number): string {
  const diff = Date.now() - at
  if (diff < 5000) return '刚刚'
  if (diff < 60000) return `${Math.floor(diff / 1000)} 秒前`
  if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`
  return formatClock(at)
}

/**
 * 倒计时（mm:ss）。目标时间已过返回 { text: '已过期', expired: true }。
 */
export function countdown(
  target: string | number | null | undefined,
  now: number = Date.now(),
): { text: string; expired: boolean; seconds: number } {
  const date = toDate(target)
  if (!date) return { text: '—', expired: false, seconds: 0 }
  const seconds = Math.floor((date.getTime() - now) / 1000)
  if (seconds <= 0) return { text: '已过期', expired: true, seconds: 0 }
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return { text: `${`${m}`.padStart(2, '0')}:${`${s}`.padStart(2, '0')}`, expired: false, seconds }
}
