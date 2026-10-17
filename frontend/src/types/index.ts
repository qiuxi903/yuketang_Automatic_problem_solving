// 字段与 docs/接口契约.md 严格一致，改名会导致前后端对不上

export type SessionStatus = 'WAITING_SCAN' | 'SCANNED' | 'EXPIRED' | 'CLOSED'
export type TaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'WAITING_BATCH'
  | 'PAUSED'
  | 'CANCELLED'
  | 'SUCCESS'
  | 'FAILED'
export type TaskItemStatus = 'PENDING' | 'ANSWERING' | 'SUBMITTED' | 'FAILED' | 'SKIPPED' | 'REUSED' | 'COLLECTED'
export type TaskMode = 'answer' | 'collect'
export type UserRole = 'USER' | 'ADMIN'

export interface RegisterResult {
  id: number
  username: string
}

export interface LoginResult {
  token: string
  username: string
  role: UserRole
}

export interface SessionCreated {
  sessionId: number
  status: SessionStatus
  qrcode: string | null
}

export interface SessionInfo {
  sessionId: number
  status: SessionStatus
  qrcode: string | null
  yktName: string | null
  batch: string | null
  mode: TaskMode | null
  expireAt: string | null
  createdAt: string | null
}

export interface PaperMeta {
  name: string
  file: string
  questions: number
  score: string | null
}

export interface BatchMeta {
  batch: string
  date: string
  papersCount: number
  questions: number
  papers: PaperMeta[]
}

export interface PapersResult {
  batches: BatchMeta[]
  summary: string
}

export interface AnswerItem {
  seq: number
  stem: string
  answer: string
}

export interface PaperAnswers {
  batch: string
  paper: string
  questions: number
  answers: AnswerItem[]
}

/* —— 答案获取：单卷 / 整批 / 按日期 三种范围 —— */

export type AnswerScope = 'SINGLE' | 'BATCH' | 'DATE'

export interface AnswerPaperEntry {
  batch: string
  paper: string
  date: string | null
  questions: number
  answers: AnswerItem[]
}

export interface AnswersBundle {
  scope: AnswerScope
  batch: string | null
  paper: string | null
  date: string | null
  papersCount: number
  questions: number
  papers: AnswerPaperEntry[]
  summary: Record<string, unknown>
}

export interface AnswerScopeQuery {
  batch?: string
  paper?: string
  date?: string
}

/** Agent 实时抓到的题目完整信息：题干 + 全部选项 */
export interface QuestionView {
  seq: number
  stem: string
  options: string[]
}

export interface TaskItem {
  paper: string
  examId: string
  status: TaskItemStatus
  score: string | null
  error: string | null
  /** 题目完整信息（含选项），仅 Agent 实时抓题后才有 */
  questions: QuestionView[]
}

/** 浏览器模式：最近一帧雨课堂实时页面截图 */
export interface TaskFrame {
  image: string | null
  url: string | null
  updatedAt: number | null
}

export interface TaskSnapshot {
  taskId: number
  batch: string | null
  mode: TaskMode
  status: TaskStatus
  sessionId: number
  totalPaper: number
  donePaper: number
  currentPaper: string | null
  currentSeq: number
  currentTotal: number
  score: string
  items: TaskItem[]
  message: string
  /** 扫码登录后所在的雨课堂课程名，便于控制台展示账号上下文 */
  course: string | null
  /** Agent 登录后抓到的账号实际作业清单，任务全程保留用于展示 */
  papers: AccountPaper[]
  /** 重做任务只处理这些卷子（普通任务为空数组）。 */
  redoPapers?: string[]
  /** 任务创建时间，任务跟着账号走，「我的任务」列表与刷新后恢复都以此为准 */
  createdAt: string
}

export interface TaskCreated {
  taskId: number
  status: TaskStatus
  totalPaper: number
}

export interface TaskCreatePayload {
  batch?: string | null
  /** 模式后置：建任务时可省略，登录后确认批次时一并提交。 */
  mode?: TaskMode
  /** 会话可空：留空表示沿用 Agent 浏览器里已登录的雨课堂账号（继续作答）。 */
  sessionId?: number
  /** 重做任务：只做这些卷子（来自「取答案未做」的一键重做或清单勾选作答）。 */
  redoPapers?: string[]
  /** 同账号续做提示：Agent 优先派给已登录该雨课堂账号的执行工人（免扫码）。 */
  reuseYkt?: string | null
}

/* —— 批次后置 + 模式后置：登录后按账号作业确认批次与作答模式 —— */

export interface SetBatchPayload {
  batch: string
  mode?: TaskMode
  /** 勾选的卷子清单：只做/只取这些卷子（自动匹配推荐批次）。 */
  redoPapers?: string[]
}

export interface AccountPaper {
  name: string
  questionCount: number | null
  fullScore: number | null
  score: string | null
  status: string | null
  /** 雨课堂学习日志里该卷所属的日期分组（如 2026-10-15），由 Agent 抓取 */
  date?: string | null
}

export interface BatchOption {
  batch: string
  total: number
  questions: number
  matched: number
  percent: number
  matchedPapers: string[]
  missedPapers: string[]
}

export interface BatchOptions {
  papers: AccountPaper[]
  options: BatchOption[]
  recommended: string | null
}

export interface OkResult {
  ok: boolean
}

export interface AnswerApiConfig {
  enabled: boolean
  url: string
  apiKeySet: boolean
  timeoutMs: number
}

export interface AnswerApiConfigPayload {
  enabled: boolean
  url: string
  apiKey?: string
  timeoutMs: number
}

/* —— 答案社区 —— */

/** 答案中心获取记录一行：谁在什么时候以何种方式获取了哪份卷子的答案。 */
export interface ContributionItem {
  username: string
  batch: string | null
  paper: string
  questions: number
  /** collect=结果页新采集；reuse=复用库内答案 */
  source: string | null
  taskId: number | null
  createdAt: string
}

/** 社区搜答案命中一条。 */
export interface SearchHitItem {
  batch: string
  paper: string
  date: string | null
  seq: number
  stem: string
  answer: string
}

/* —— 管理后台 —— */

export interface InviteItem {
  code: string
  used: boolean
  usedBy: string | null
  usedAt: string | null
  disabled: boolean
  createdAt: string
}

export interface AdminBatchBrief {
  batch: string
  papers: number
  questions: number
}

export interface AdminOverview {
  userCount: number
  adminCount: number
  taskCount: number
  taskStatus: Record<string, number>
  paperCount: number
  questionCount: number
  batchCount: number
  sessionCount: number
  sessionStatus: Record<string, number>
  batches: AdminBatchBrief[]
}

export interface AdminUserView {
  id: number
  username: string
  role: UserRole
  taskCount: number
  runningCount: number
  createdAt: string
}

export interface AdminTaskView {
  taskId: number
  userId: number
  username: string | null
  batch: string
  mode: TaskMode
  status: TaskStatus
  totalPaper: number
  donePaper: number
  currentPaper: string | null
  currentSeq: number | null
  currentTotal: number | null
  score: string | null
  message: string | null
  createdAt: string
}

