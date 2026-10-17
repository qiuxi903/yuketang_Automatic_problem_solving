import type { SessionStatus, TaskItemStatus, TaskStatus } from '@/types'

export type Tone = 'ok' | 'warn' | 'danger' | 'info' | 'accent' | 'neutral'

interface Meta {
  label: string
  tone: Tone
}

const TASK: Record<TaskStatus, Meta> = {
  PENDING: { label: '排队中', tone: 'info' },
  RUNNING: { label: '做题中', tone: 'accent' },
  WAITING_BATCH: { label: '等你确认', tone: 'warn' },
  PAUSED: { label: '已暂停', tone: 'warn' },
  CANCELLED: { label: '已中断', tone: 'neutral' },
  SUCCESS: { label: '全部完成', tone: 'ok' },
  FAILED: { label: '失败', tone: 'danger' },
}

const ITEM: Record<TaskItemStatus, Meta> = {
  PENDING: { label: '待作答', tone: 'neutral' },
  ANSWERING: { label: '作答中', tone: 'accent' },
  SUBMITTED: { label: '已交卷', tone: 'ok' },
  FAILED: { label: '失败', tone: 'danger' },
  SKIPPED: { label: '先做题', tone: 'neutral' },
  REUSED: { label: '直接取答案', tone: 'ok' },
  COLLECTED: { label: '取答案未做', tone: 'warn' },
}

/** 明细状态的悬停解释：把黑话翻译成「这卷子怎么了」。 */
export const ITEM_HINT: Record<TaskItemStatus, string> = {
  PENDING: '还没轮到这套卷子。',
  ANSWERING: '正在自动作答这套卷子。',
  SUBMITTED: '已作答并交卷，成绩已记录。',
  FAILED: '这套卷子作答失败，可重新发起任务重试。',
  SKIPPED: '这套卷子还没交过卷，暂时取不到答案；先正常做一次，之后就能直接取答案。',
  REUSED: '答案库里已有这套卷子的答案，直接取用，不交卷、不消耗作答次数。',
  COLLECTED: '这张卷子之前交过，本次只从结果页取到了答案、没有重新做；任务结束后可一键「再做一遍」刷分。',
}

const SESSION: Record<SessionStatus, Meta> = {
  WAITING_SCAN: { label: '待扫码', tone: 'warn' },
  SCANNED: { label: '已扫码', tone: 'ok' },
  EXPIRED: { label: '二维码过期', tone: 'danger' },
  CLOSED: { label: '已关闭', tone: 'neutral' },
}

export function taskMeta(status: TaskStatus): Meta {
  return TASK[status] ?? { label: status, tone: 'neutral' }
}

export function itemMeta(status: TaskItemStatus): Meta {
  return ITEM[status] ?? { label: status, tone: 'neutral' }
}

export function sessionMeta(status: SessionStatus): Meta {
  return SESSION[status] ?? { label: status, tone: 'neutral' }
}

export const MODE_LABEL: Record<string, string> = {
  answer: '自动做题',
  collect: '只取答案',
}

/** 两种模式的后果说明：差别在于会不会交卷、消不消耗作答次数。 */
export const MODE_HINT: Record<string, string> = {
  answer: '自动做题：打开每套卷子作答并交卷，成绩自动记录，会消耗作答次数。',
  collect: '只取答案：不答题不交卷、零消耗。已交过卷的卷子直接从结果页取答案；没交过的卷子会跳过并提示先做题。',
}
