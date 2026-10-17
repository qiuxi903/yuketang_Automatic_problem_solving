import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createSession, getSession, closeSession } from '@/api/session'
import { listPapers } from '@/api/paper'
import {
  createTask,
  getTask,
  getBatchOptions,
  listTasks,
  setTaskBatch,
  pauseTask,
  resumeTask,
  interruptTask,
  deleteTask,
  exportTask,
  getTaskFrame,
} from '@/api/task'
import type {
  BatchOptions,
  SessionInfo,
  TaskFrame,
  TaskMode,
  TaskSnapshot,
  TaskStatus,
} from '@/types'
import router from '@/router'
import { itemMeta, MODE_LABEL, sessionMeta, taskMeta, ITEM_HINT, type Tone } from '@/utils/status'
import { formatDateTime } from '@/utils/format'

export type EventLevel = 'info' | 'ok' | 'warn' | 'danger' | 'agent'

export interface ConsoleEvent {
  id: number
  at: number
  level: EventLevel
  title: string
  detail?: string
}

/** 试卷全量清单的一行：任务明细 ∪ 账号作业，是否完成都保留 */
export interface ResultRow {
  seq: number
  name: string
  label: string
  tone: Tone
  score: string
  done: boolean
  /** 悬停解释：把状态翻译成「这套卷子怎么了」 */
  hint: string
  /** 是否可勾选作答：仅当前任务批次内的卷子（其它批次的卷子发起任务会匹配不到） */
  selectable: boolean
}

/**
 * 工作台状态：承载「题目列表 / 对话菜单 / 答题区」三栏所需的全部数据。
 * - 会话轮询与任务 WebSocket 都收敛在这里，视图层只做展示。
 * - 事件流由任务快照 diff 生成，避免后端额外提供接口。
 */
export const useConsoleStore = defineStore('console', () => {
  const activeTaskId = ref<number | null>(null)
  const task = ref<TaskSnapshot | null>(null)
  const session = ref<SessionInfo | null>(null)
  const events = ref<ConsoleEvent[]>([])
  const connected = ref(false)
  const starting = ref(false)
  const busy = ref(false)
  const batchOptions = ref<BatchOptions | null>(null)
  const selectedBatch = ref('')
  const batchLoading = ref(false)
  // 浏览器模式：Agent 回传的最近一帧雨课堂实时页面
  const frame = ref<TaskFrame | null>(null)
  const frameLoading = ref(false)
  // 活跃度：最近一次收到任务快照/事件的时刻，用于「已 Xs 无更新」卡顿提示
  const lastActivityAt = ref<number>(Date.now())
  // 我的任务：跟着登录账号走，而不是跟着浏览器会话
  const myTasks = ref<TaskSnapshot[]>([])
  const myTasksLoading = ref(false)
  // 试卷清单勾选：勾中的卷子可通过「作答勾选」单独发起任务
  const selectedPapers = ref<string[]>([])
  // 卷名 → 答案库日期（批次日期），试卷清单按日期分组用
  const paperDates = ref<Record<string, string>>({})

  /** 未结束的任务状态：刷新后优先恢复这类任务。 */
  const ACTIVE_STATUS: TaskStatus[] = ['PENDING', 'RUNNING', 'WAITING_BATCH', 'PAUSED']

  const MAX_EVENTS = 160
  const FRAME_POLL_MS = 1000
  let eventSeq = 0
  let pollTimer: number | undefined
  let retryTimer: number | undefined
  let frameTimer: number | undefined
  let socket: WebSocket | null = null
  let retryCount = 0
  let detached = true

  const hasTask = computed(() => task.value !== null)
  const isRunning = computed(() => task.value?.status === 'RUNNING')
  const canPause = computed(
    () => task.value?.status === 'RUNNING' || task.value?.status === 'PAUSED',
  )
  const progressPercent = computed(() => {
    const current = task.value
    if (!current || !current.totalPaper) return 0
    return Math.min(100, Math.round((current.donePaper / current.totalPaper) * 100))
  })
  const items = computed(() => task.value?.items ?? [])

  const waitingBatch = computed(() => task.value?.status === 'WAITING_BATCH')
  // 账号作业清单随任务快照持久保存，登录后任何阶段都可展示；批次接口作为兜底
  const accountPapers = computed(() => task.value?.papers ?? batchOptions.value?.papers ?? [])
  const loggedIn = computed(
    () => session.value?.status === 'SCANNED' || accountPapers.value.length > 0,
  )
  // 账号上下文：登录后常驻，任务结束也不消失
  const showAccount = computed(() => loggedIn.value || !!task.value?.batch)

  // 账号名兜底：已扫码但名字未回传时，不要误显示成「待扫码」
  const accountNameText = computed(() => {
    const name = session.value?.yktName
    if (name) return name
    if (session.value?.status === 'SCANNED') return '已登录（名称读取中）'
    return '待扫码'
  })

  // 账号完整信息：任务令牌与会话创建时间
  const taskToken = computed(() => {
    const current = task.value
    if (!current) return '—'
    return `T${current.taskId}·S${current.sessionId}`
  })
  const sessionCreated = computed(() => formatDateTime(session.value?.createdAt))

  // 未入答案库的新卷子分组名（resultRows 与 paperDateOf 共用）
  const NEW_GROUP = '新卷子（未入答案库）'

  // 试卷全量清单：账号作业 ∪ 任务明细，按「已完成 / 未完成」分组，是否完成都展示
  const resultRows = computed<ResultRow[]>(() => {
    const rows: Omit<ResultRow, 'seq'>[] = []
    const taskItems = task.value?.items ?? []
    for (const item of taskItems) {
      if (!item?.paper) continue
      const meta = itemMeta(item.status)
      const account = accountPapers.value.find(
        (paper) => paper.name.includes(item.paper) || item.paper.includes(paper.name),
      )
      const score = item.score || account?.score || ''
      rows.push({
        name: item.paper,
        label: meta.label,
        tone: meta.tone,
        score,
        done:
          item.status === 'SUBMITTED' ||
          item.status === 'REUSED' ||
          item.status === 'COLLECTED' ||
          (!!score && item.status !== 'FAILED'),
        // 失败的卷子优先展示真实报错（后端已落库），其余展示状态解释
        hint: item.status === 'FAILED' && item.error ? item.error : ITEM_HINT[item.status] ?? '',
        selectable: true,
      })
    }
    // 账号作业补齐任务尚未覆盖的试卷，登录后即可全量可见
    for (const paper of accountPapers.value) {
      if (!paper?.name) continue
      const covered = rows.some(
        (row) => row.name.includes(paper.name) || paper.name.includes(row.name),
      )
      if (covered) continue
      const score = paper.score || ''
      const inLib = !!paperDateOf(paper.name) && paperDateOf(paper.name) !== NEW_GROUP
      rows.push({
        name: paper.name,
        label: !inLib ? '新卷子' : score ? '已作答' : '其他地方做过',
        tone: !inLib ? 'accent' : score ? 'ok' : 'info',
        score,
        done: !!score,
        hint: !inLib
          ? '答案库还没有这套卷子：勾选后点「做题」，先做一遍，答案会自动采回答案库。'
          : score
            ? '该卷此前已交卷，得分已记录。'
            : '已在答案库（其他账号采集过），可直接套用：勾选后点「做题」会自动复用答案，或点「取答案」直接读取。',
        selectable: true,
      })
    }
    return rows.map((row, index) => ({ ...row, seq: index + 1 }))
  })
  const doneRows = computed(() => resultRows.value.filter((row) => row.done))
  const todoRows = computed(() => resultRows.value.filter((row) => !row.done))

  function log(level: EventLevel, title: string, detail?: string) {
    events.value.push({ id: ++eventSeq, at: Date.now(), level, title, detail })
    if (events.value.length > MAX_EVENTS) events.value.splice(0, events.value.length - MAX_EVENTS)
  }

  function clearEvents() {
    events.value = []
    eventSeq = 0
  }

  /* —— 任务快照 —— */

  function applySnapshot(snapshot: TaskSnapshot) {
    const prev = task.value
    task.value = snapshot
    lastActivityAt.value = Date.now()

    if (!prev) {
      const scope = snapshot.batch ? `批次 ${snapshot.batch}` : '批次待确认'
      log('info', `任务 #${snapshot.taskId} 已接入`, `${scope} · ${MODE_LABEL[snapshot.mode]}`)
      return
    }
    if (prev.status !== snapshot.status) {
      const meta = taskMeta(snapshot.status)
      log(snapshot.status === 'FAILED' ? 'danger' : 'info', `任务状态更新：${meta.label}`)
      if (snapshot.status === 'WAITING_BATCH') void loadBatchOptions()
    }
    if (snapshot.currentPaper && prev.currentPaper !== snapshot.currentPaper) {
      log('agent', `开始作答：${snapshot.currentPaper}`)
    }
    if (snapshot.donePaper > prev.donePaper) {
      log('ok', `完成第 ${snapshot.donePaper} / ${snapshot.totalPaper} 套试卷`)
    }
    if (snapshot.message && snapshot.message !== prev.message) {
      log('info', snapshot.message)
    }
  }

  async function loadTask(id: number) {
    task.value = await getTask(id)
  }

  /* —— 会话 & 扫码 —— */

  function stopPolling() {
    if (pollTimer) {
      window.clearInterval(pollTimer)
      pollTimer = undefined
    }
  }

  function startPolling() {
    stopPolling()
    pollTimer = window.setInterval(async () => {
      if (!session.value) return
      try {
        const info = await getSession(session.value.sessionId)
        const previous = session.value.status
        session.value = info
        if (info.status !== previous) {
          log(info.status === 'SCANNED' ? 'ok' : info.status === 'EXPIRED' ? 'danger' : 'info',
            `扫码状态：${sessionMeta(info.status).label}`,
            info.status === 'SCANNED' && info.yktName ? `雨课堂账号 ${info.yktName}` : undefined)
        }
        if (info.status === 'WAITING_SCAN') return
        stopPolling()
      } catch {
        stopPolling()
      }
    }, 1000)
  }

  async function loadSession() {
    const current = task.value
    if (!current?.sessionId) return
    session.value = await getSession(current.sessionId)
    if (session.value.status === 'WAITING_SCAN') startPolling()
    else stopPolling()
  }

  async function refreshSession() {
    const current = task.value
    if (!current) return
    busy.value = true
    try {
      // Agent 在等扫码期间每 ~12s 回传新二维码，重拉会话即可拿到新图。
      // （此前新建会话是错的：任务仍绑旧会话，新会话成了孤儿，码永远没人扫。）
      await loadSession()
      if (session.value?.status === 'WAITING_SCAN') startPolling()
      log('info', '已重新获取二维码', '若仍显示旧码，请稍等几秒再试（Agent 每 12 秒换新码）')
      ElMessage.success('二维码已刷新')
    } finally {
      busy.value = false
    }
  }

  /* —— 批次后置：登录后确认批次 —— */

  async function loadBatchOptions() {
    const current = task.value
    if (!current) return
    batchLoading.value = true
    try {
      const options = await getBatchOptions(current.taskId)
      batchOptions.value = options
      if (!selectedBatch.value) {
        selectedBatch.value = options.recommended ?? options.options[0]?.batch ?? ''
      }
      log('info', '已获取账号作业清单', `${options.papers.length} 套作业，${options.options.length} 个候选批次`)
    } catch {
      batchOptions.value = null
    } finally {
      batchLoading.value = false
    }
  }

  async function confirmBatch(batch: string, mode: TaskMode = 'answer') {
    const current = task.value
    if (!current || !batch) {
      ElMessage.warning('请先选择批次')
      return
    }
    busy.value = true
    try {
      await setTaskBatch(current.taskId, { batch, mode })
      log('ok', '批次与模式已确认', `${batch} · ${MODE_LABEL[mode]}，Agent 继续作答`)
      await loadTask(current.taskId)
      await loadSession()
    } finally {
      busy.value = false
    }
  }

  async function closeActiveSession() {
    if (!session.value) {
      ElMessage.warning('当前没有可关闭的会话（未扫码或已关闭）')
      return
    }
    try {
      await ElMessageBox.confirm('关闭后本次扫码失效，需要重新获取二维码。确认关闭当前会话？', '关闭会话', {
        confirmButtonText: '关闭会话',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return // 取消
    }
    busy.value = true
    try {
      await closeSession(session.value.sessionId)
      log('warn', '会话已关闭', `#${session.value.sessionId}`)
      ElMessage.success('会话已关闭')
      await loadSession()
    } finally {
      busy.value = false
    }
  }

  /* —— WebSocket —— */

  function subscribe(id: number) {
    unsubscribe()
    detached = false
    connect(id)
  }

  function connect(id: number) {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${protocol}://${window.location.host}/ws/tasks/${id}`)
    socket = ws

    ws.onopen = () => {
      if (socket !== ws) return
      connected.value = true
      retryCount = 0
    }
    ws.onmessage = (event) => {
      // 切换任务后旧连接的残余帧必须丢弃，否则会把面板刷回上一个任务
      if (socket !== ws) return
      try {
        applySnapshot(JSON.parse(event.data as string) as TaskSnapshot)
      } catch {
        // 非法帧忽略
      }
    }
    ws.onclose = () => {
      if (socket !== ws) return
      connected.value = false
      scheduleReconnect(id, ws)
    }
    ws.onerror = () => ws.close()
  }

  function scheduleReconnect(id: number, ws: WebSocket) {
    // 只有当前活跃连接才允许重连：切换任务时旧 socket 的 close 不应再拉起旧任务
    if (detached || socket !== ws) return
    retryCount += 1
    const delay = Math.min(1000 * 2 ** (retryCount - 1), 15000)
    retryTimer = window.setTimeout(() => {
      if (socket === ws && !detached) connect(id)
    }, delay)
  }

  function unsubscribe() {
    detached = true
    retryCount = 0
    if (retryTimer) window.clearTimeout(retryTimer)
    retryTimer = undefined
    const current = socket
    socket = null
    current?.close()
    connected.value = false
  }

  /* —— 浏览器模式：轮询 Agent 回传的实时页面帧 —— */

  async function loadFrame() {
    const current = task.value
    if (!current) return
    frameLoading.value = true
    try {
      frame.value = await getTaskFrame(current.taskId)
    } catch {
      // 单帧失败忽略，等待下一次轮询
    } finally {
      frameLoading.value = false
    }
  }

  function startFramePolling() {
    stopFramePolling()
    void loadFrame()
    frameTimer = window.setInterval(() => {
      void loadFrame()
    }, FRAME_POLL_MS)
  }

  function stopFramePolling() {
    if (frameTimer) {
      window.clearInterval(frameTimer)
      frameTimer = undefined
    }
  }

  /* —— 对外动作 —— */

  async function startTask() {
    starting.value = true
    try {
      detach()
      clearEvents()
      log('info', '发起做题任务', '扫码登录后选择批次与作答模式')
      const createdSession = await createSession()
      log('info', '会话已创建', `#${createdSession.sessionId}，等待 Agent 回传二维码`)
      const createdTask = await createTask({ sessionId: createdSession.sessionId })
      log('ok', `任务 #${createdTask.taskId} 已排队`, '扫码登录后按账号实际作业选择批次与作答模式')
      await attachTask(createdTask.taskId)
      return createdTask.taskId
    } finally {
      starting.value = false
    }
  }

  /** 「取答案未做」的一键重做：为指定卷子创建自动做题任务（扫码后直接开做）。 */
  async function startRedoTask(papers: string[]) {
    const batch = task.value?.batch
    if (!batch || !papers.length) return null
    const reuseYkt = session.value?.yktName ?? null
    starting.value = true
    try {
      detach()
      clearEvents()
      log('info', '发起重做任务', `${papers.length} 套：${papers.join('、')}；沿用当前登录直接开做`)
      // 会话复用：优先当前/任务绑定会话，仅在没有时才新建
      const s0 = session.value
      const sessionId = s0 && s0.status !== 'CLOSED'
        ? s0.sessionId
        : task.value?.sessionId ?? (await createSession('answer')).sessionId
      const createdTask = await createTask({
        sessionId,
        batch,
        mode: 'answer',
        redoPapers: papers,
        reuseYkt,
      })
      log('ok', `重做任务 #${createdTask.taskId} 已创建`, '请扫码登录雨课堂，Agent 将直接开始重做')
      await attachTask(createdTask.taskId)
      return createdTask.taskId
    } finally {
      starting.value = false
    }
  }

  /**
   * 勾选卷子后的一键开始（做题 / 取答案）。
   * - 会话复用：当前有未过期会话就沿用（不新建，登录不受影响）；仅无会话时才新建
   * - 任务未确认批次（刚扫码）→ 确认推荐批次 + 勾选清单，直接开始
   * - 任务已确认批次 → 新建一个只做勾选卷子的任务（沿用登录）
   */
  async function continueTask(papers: string[], mode: TaskMode = 'answer') {
    const reuseYkt = session.value?.yktName ?? null
    starting.value = true
    try {
      const verb = mode === 'collect' ? '只取答案' : '做题'
      // 1) 会话：绝不轻易新建——优先沿用当前会话，其次沿用任务绑定的会话；
      //    只有两者都无（或你手动关闭过）才创建。Agent 浏览器还登着的话，
      //    任务领取后会自动把会话置回已扫码，登录不会消失。
      const s = session.value
      const bound = task.value?.sessionId ?? null
      let sessionId: number
      let how: string
      if (s && s.status !== 'CLOSED') {
        sessionId = s.sessionId
        how = `沿用当前会话 #${sessionId}（${sessionMeta(s.status).label}）`
      } else if (bound) {
        sessionId = bound
        how = `沿用任务绑定的会话 #${sessionId}`
      } else {
        const created = await createSession(mode)
        sessionId = created.sessionId
        how = `新建会话 #${sessionId}`
      }
      log('info', `会话：${how}，登录不受影响`)

      // 2) 批次：已确认 → 新建勾选任务；未确认（刚扫码）→ 确认推荐批次
      const t = task.value
      const waitingConfirm = !t || t.status === 'WAITING_BATCH' || !t.batch
      const options = batchOptions.value?.options ?? []
      const best = batchOptions.value?.recommended ?? options[0]?.batch ?? ''

      if (waitingConfirm) {
        if (!best) {
          ElMessage.warning('没有答案库批次能覆盖当前账号作业，请联系管理员补充答案库')
          return null
        }
        const tid = t?.taskId ?? activeTaskId.value
        if (!tid) return null
        await setTaskBatch(tid, { batch: best, mode, redoPapers: papers })
        log('ok', `任务 #${tid} 已开始（${verb}）`,
          `自动匹配答案库批次 ${best}；勾选 ${papers.length} 套`)
        await loadTask(tid)
        await loadSession()
        await loadMyTasks()
        return tid
      }

      // 3) 已确认批次的任务 → 新建勾选任务（沿用登录）
      const createdTask = await createTask({
        sessionId,
        batch: t.batch,
        mode,
        redoPapers: papers.length ? papers : undefined,
        reuseYkt,
      })
      log('ok', `任务 #${createdTask.taskId} 已创建`, `勾选 ${papers.length} 套 · ${verb}；沿用当前登录直接开做`)
      await attachTask(createdTask.taskId)
      return createdTask.taskId
    } finally {
      starting.value = false
    }
  }

  /** 拉答案库清单，建立 卷名→日期 映射（试卷清单按日期分组展示）。 */
  function toggleSelected(name: string) {
    const i = selectedPapers.value.indexOf(name)
    if (i >= 0) selectedPapers.value.splice(i, 1)
    else selectedPapers.value.push(name)
  }

  /** 拉答案库清单，建立 卷名→日期 映射（试卷清单按日期分组展示）。 */
  async function loadPaperDates() {
    if (Object.keys(paperDates.value).length) return
    try {
      const result = await listPapers()
      const map: Record<string, string> = {}
      for (const b of result.batches) {
        for (const p of b.papers) map[p.name] = b.date || b.batch
      }
      paperDates.value = map
    } catch {
      paperDates.value = {}
    }
  }

  /** 试卷清单行的日期归属：优先雨课堂学习日志的日期（Agent 抓取），其次答案库批次日期，未命中归「新卷子」。 */
  function paperDateOf(name: string): string {
    const own = (task.value?.papers ?? []).find(
      (p) => p.name === name || (p.name && name.includes(p.name)) || (p.name && p.name.includes(name)),
    )
    if (own?.date) return own.date
    const exact = paperDates.value[name]
    if (exact) return exact
    for (const [libName, date] of Object.entries(paperDates.value)) {
      if (name.includes(libName) || libName.includes(name)) return date
    }
    return NEW_GROUP
  }

  async function attachTask(id: number) {
    activeTaskId.value = id
    batchOptions.value = null
    selectedBatch.value = ''
    selectedPapers.value = []
    frame.value = null
    await loadTask(id)
    void loadPaperDates()
    await loadSession()
    subscribe(id)
    if (task.value?.status === 'WAITING_BATCH') await loadBatchOptions()
    syncUrl(id)
  }

  /* —— 我的任务：绑定登录账号，刷新后可恢复 —— */

  /** 把当前任务号写进地址栏，刷新后 Console 能据此恢复同一个任务。 */
  function syncUrl(id: number | null) {
    const current = router.currentRoute.value
    if (current.name !== 'console') return
    const next = id ? String(id) : ''
    if (String(current.query.task ?? '') === next) return
    const query = { ...current.query }
    if (id) query.task = next
    else delete query.task
    void router.replace({ name: 'console', query })
  }

  /** 拉取当前登录账号的全部任务（后端按 userId 过滤，与浏览器无关）。 */
  async function loadMyTasks() {
    myTasksLoading.value = true
    try {
      myTasks.value = await listTasks()
      return myTasks.value
    } catch {
      myTasks.value = []
      return []
    } finally {
      myTasksLoading.value = false
    }
  }

  /** 刷新/重新进入工作台时恢复任务：优先未结束的最新任务，其次最近一次任务。 */
  async function restoreTask() {
    const list = await loadMyTasks()
    if (!list.length) return null
    const target = list.find((item) => ACTIVE_STATUS.includes(item.status)) ?? list[0]
    if (target && target.taskId !== activeTaskId.value) {
      await attachTask(target.taskId)
      log('info', `已恢复任务 #${target.taskId}`, `账号最近的未结束任务`)
    }
    return target?.taskId ?? null
  }

  /** 在「我的任务」里切换任务。 */
  async function switchTask(id: number) {
    if (id === activeTaskId.value) return
    clearEvents()
    await attachTask(id)
  }

  /** 中断任务：二次确认后停止执行并置为终端态「已中断」；若为当前任务则刷新其快照。 */
  async function interruptTaskById(id: number) {
    try {
      await ElMessageBox.confirm(
        `中断后任务 #${id} 将不再继续执行，已完成的试卷保持不变。确认中断？`,
        '中断任务',
        { confirmButtonText: '中断任务', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
    busy.value = true
    try {
      await interruptTask(id)
      log('warn', `任务 #${id} 已中断`, 'Agent 将不再继续该任务')
      ElMessage.warning(`任务 #${id} 已中断`)
      if (id === activeTaskId.value) await loadTask(id)
      await loadMyTasks()
    } finally {
      busy.value = false
    }
  }

  /** 删除任务：二次确认后从账号任务列表移除；若为当前任务则清空工作台并移除 URL 上的任务号。 */
  async function deleteTaskById(id: number) {
    try {
      await ElMessageBox.confirm(
        `删除后任务 #${id} 及其作答明细将无法恢复。确认删除？`,
        '删除任务',
        { confirmButtonText: '删除任务', cancelButtonText: '取消', type: 'error' },
      )
    } catch {
      return
    }
    busy.value = true
    try {
      await deleteTask(id)
      log('warn', `任务 #${id} 已删除`)
      ElMessage.warning(`任务 #${id} 已删除`)
      if (id === activeTaskId.value) detach()
      await loadMyTasks()
    } finally {
      busy.value = false
    }
  }

  async function togglePause() {
    const current = task.value
    if (!current) return
    busy.value = true
    try {
      if (current.status === 'PAUSED') {
        await resumeTask(current.taskId)
        log('info', '已恢复任务')
      } else {
        await pauseTask(current.taskId)
        log('warn', '已暂停任务')
      }
      await loadTask(current.taskId)
    } finally {
      busy.value = false
    }
  }

  async function exportAnswers() {
    const current = task.value
    if (!current) return
    busy.value = true
    try {
      const blob = await exportTask(current.taskId)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `task-${current.taskId}-answers.md`
      link.click()
      URL.revokeObjectURL(url)
      log('ok', '答案已导出', `task-${current.taskId}-answers.md`)
    } finally {
      busy.value = false
    }
  }

  function detach() {
    stopPolling()
    stopFramePolling()
    unsubscribe()
    task.value = null
    session.value = null
    activeTaskId.value = null
    batchOptions.value = null
    selectedBatch.value = ''
    frame.value = null
    syncUrl(null)
  }

  function reset() {
    detach()
    clearEvents()
    // 退出登录：清空账号维度的任务列表，避免切换账号时串数据
    myTasks.value = []
  }

  return {
    activeTaskId,
    task,
    session,
    events,
    connected,
    starting,
    busy,
    batchOptions,
    selectedBatch,
    batchLoading,
    frame,
    frameLoading,
    lastActivityAt,
    myTasks,
    myTasksLoading,
    hasTask,
    isRunning,
    canPause,
    progressPercent,
    items,
    waitingBatch,
    accountPapers,
    loggedIn,
    showAccount,
    accountNameText,
    taskToken,
    sessionCreated,
    resultRows,
    doneRows,
    todoRows,
    log,
    clearEvents,
    attachTask,
    startTask,
    startRedoTask,
    continueTask,
    selectedPapers,
    toggleSelected,
    paperDates,
    loadPaperDates,
    paperDateOf,
    NEW_GROUP,
    loadTask,
    loadMyTasks,
    restoreTask,
    switchTask,
    interruptTaskById,
    deleteTaskById,
    loadSession,
    refreshSession,
    loadBatchOptions,
    confirmBatch,
    closeActiveSession,
    togglePause,
    exportAnswers,
    loadFrame,
    startFramePolling,
    stopFramePolling,
    detach,
    reset,
  }
})
