<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleClose,
  Collection,
  Document,
  Download,
  Promotion,
  Refresh,
  Select,
  VideoPause,
  VideoPlay,
} from '@element-plus/icons-vue'
import { useConsoleStore } from '@/store/console'
import { countdown, formatClock } from '@/utils/format'
import { MODE_HINT, MODE_LABEL, sessionMeta, taskMeta } from '@/utils/status'
import StatusPill from '@/components/ui/StatusPill.vue'
import type { TaskMode } from '@/types'
import { useRouter } from 'vue-router'

const store = useConsoleStore()
const router = useRouter()

/** 移动端「去勾选卷子」：请外层把分段视图切到试卷页。 */
const emit = defineEmits<{ goPapers: [] }>()

/** 答案查询统一入口收敛到「答案中心」页（搜索 / 按批次浏览导出 / 获取记录） */
function goAnswerCenter() {
  void router.push({ name: 'community' })
}

const form = ref<{ mode: TaskMode }>({ mode: 'answer' })

const qrVisible = ref(false)
// 操作与事件不再常驻中栏，收进「运行日志」抽屉按需查看
const logVisible = ref(false)
const lastSeen = ref(0)
const feedRef = ref<HTMLElement>()
const now = ref(Date.now())

const latest = computed(() => store.events[store.events.length - 1] ?? null)
const newCount = computed(() => Math.max(0, store.events.length - lastSeen.value))

const sessionStatus = computed(() =>
  store.session ? sessionMeta(store.session.status) : null,
)
const taskStatusMeta = computed(() => (store.task ? taskMeta(store.task.status) : null))
const remain = computed(() => countdown(store.session?.expireAt, now.value))
const canExport = computed(() => (store.task?.donePaper ?? 0) > 0)
const batchCandidates = computed(() => store.batchOptions?.options ?? [])
// 账号已交卷的套数：这些卷子「只取答案」也能直接拿到；未交卷的只能先做题
const submittedCount = computed(
  () => store.accountPapers.filter((paper) => !!paper.score).length,
)

const LEVEL_LABEL: Record<string, string> = {
  info: '信息',
  ok: '成功',
  warn: '提示',
  danger: '异常',
  agent: 'Agent',
}

let clock: number | undefined
onMounted(() => {
  clock = window.setInterval(() => (now.value = Date.now()), 1000)
})
onBeforeUnmount(() => {
  if (clock) window.clearInterval(clock)
})

// 事件流只在「运行日志」抽屉展开时滚动并标记已读，避免常驻中栏干扰操作
watch(
  () => store.events.length,
  async () => {
    if (!logVisible.value) return
    lastSeen.value = store.events.length
    await nextTick()
    feedRef.value?.scrollTo({ top: feedRef.value.scrollHeight, behavior: 'smooth' })
  },
)

// 扫码成功后自动关闭扫码弹窗，无需手动点「关闭」
watch(
  () => store.session?.status,
  (status) => {
    if (status === 'SCANNED' && qrVisible.value) {
      qrVisible.value = false
      lastSeen.value = store.events.length
      ElMessage.success('扫码成功！请勾选要做的卷子，再点下方「开始做题」')
    }
  },
)

/* 任务结束弹窗：可重做刷满分的卷子，询问是否一键重做（每个任务只问一次）。
   - COLLECTED：取到答案但没做
   - SUBMITTED 且未满分：做了但没拿满分（答案已入库，重做基本满分）
   仅纳入「答案已进答案库」的卷子——否则重做也没有答案可用。 */
const promptedTasks = new Set<number>()

/** 分数是否未满（"12/50" → true；读不到分数 → 不纳入，避免误报）。 */
function isBelowFull(score?: string | null): boolean {
  if (!score) return false
  const m = /(\d+)\s*\/\s*(\d+)/.exec(score)
  if (!m) return false
  return Number(m[1]) < Number(m[2])
}

async function maybePromptRedo() {
  const t = store.task
  if (!t) return
  const terminal = t.status === 'SUCCESS' || t.status === 'FAILED' || t.status === 'CANCELLED'
  if (!terminal || promptedTasks.has(t.taskId)) return
  promptedTasks.add(t.taskId)

  // 刚采集的答案要反映到「是否已入库」，先刷新答案库清单
  await store.refreshPaperDates()

  const collected: string[] = []
  const belowFull: string[] = []
  for (const it of t.items) {
    if (!it.paper) continue
    if (it.status === 'COLLECTED') {
      collected.push(it.paper)
    } else if (it.status === 'SUBMITTED' && store.inLib(it.paper) && isBelowFull(it.score)) {
      belowFull.push(it.paper)
    }
  }
  const papers = [...collected, ...belowFull]
  if (!papers.length) return

  const preview = papers.slice(0, 5).join('、') + (papers.length > 5 ? ' 等' : '')
  const parts: string[] = []
  if (collected.length) parts.push(`${collected.length} 套已取到答案但没做`)
  if (belowFull.length) parts.push(`${belowFull.length} 套已作答但没拿满分`)
  try {
    await ElMessageBox.confirm(
      `检测到 ${parts.join('、')}：${preview}（共 ${papers.length} 套）。` +
        '答案都已进库，现在重做基本满分（每套会消耗一次作答次数）。要把这些再做一遍吗？',
      '可以重做刷满分',
      {
        confirmButtonText: '把这些再做一遍',
        cancelButtonText: '暂不重做',
        type: 'info',
        distinguishCancelAndClose: true,
      },
    )
  } catch {
    return // 暂不重做
  }
  const id = await store.startRedoTask(papers)
  if (id) {
    qrVisible.value = true
    ElMessage.success('重做任务已创建，请扫码登录雨课堂')
  }
}

// 只在任务「本次会话内」跑到终态时询问一次；打开页面时已有的历史任务不打扰
watch(() => store.task?.status, () => void maybePromptRedo())

async function openLog() {
  logVisible.value = true
  lastSeen.value = store.events.length
  await nextTick()
  feedRef.value?.scrollTo({ top: feedRef.value.scrollHeight })
}

async function start() {
  const id = await store.startTask()
  if (id) {
    qrVisible.value = true
    ElMessage.success('任务已创建，请扫码登录雨课堂')
  }
}

async function rescan() {
  await store.refreshSession()
  qrVisible.value = true
}

async function copyToken() {
  const current = store.task
  if (!current) return
  const text = `task=${current.taskId} session=${current.sessionId} batch=${current.batch ?? '待确认'}`
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制任务信息')
  } catch {
    ElMessage.warning('当前环境不支持自动复制')
  }
}

/** 任务已结束（未暂停）：可沿用同一雨课堂账号继续做剩余卷子，无需重新扫码。 */
const taskFinished = computed(() => {
  const s = store.task?.status
  return s === 'SUCCESS' || s === 'FAILED' || s === 'CANCELLED'
})

async function continueAnswer(papers: string[] = []) {
  const id = await store.continueTask(papers)
  if (id) {
    qrVisible.value = true
    ElMessage.success(
      papers.length
        ? `已发起勾选的 ${papers.length} 套，若登录仍有效将直接开做`
        : '继续作答已发起，若登录仍有效将直接开做（无需扫码）',
    )
  }
}

/** WAITING_BATCH 面板的两个开始按钮：勾选了就只做勾选的，没勾选就做全部剩余。 */
function startChecked(mode: TaskMode) {
  const papers = store.selectedPapers.length ? [...store.selectedPapers] : []
  void store.continueTask(papers, mode).then((id) => {
    if (id) ElMessage.success('已发起，扫码后自动按勾选执行')
  })
}

defineExpose({
  openQrcode: () => (qrVisible.value = true),
  rescan,
})
</script>

<template>
  <section class="deck">
    <div class="deck-head">
      <div class="deck-title">
        <span class="eyebrow">控制台 · 任务操作</span>
        <h3>操作菜单</h3>
      </div>
      <StatusPill v-if="taskStatusMeta" :tone="taskStatusMeta.tone">
        {{ taskStatusMeta.label }}
      </StatusPill>
      <StatusPill v-else tone="neutral">未开始</StatusPill>
    </div>

    <!-- 状态摘要条：无任务时手机上整行隐藏，减少噪音 -->
    <div class="log-bar" :class="{ 'mobile-hide': !store.hasTask }">
      <div class="log-latest">
        <span class="log-dot" :class="`l-${latest?.level ?? 'info'}`" />
        <div class="log-text">
          <div class="log-title">{{ latest ? latest.title : '暂无运行事件' }}</div>
          <div class="log-detail">
            <template v-if="latest">
              {{ LEVEL_LABEL[latest.level] }} · {{ formatClock(latest.at) }}
            </template>
            <template v-else>新建任务并扫码后，事件会在这里滚动更新</template>
          </div>
        </div>
      </div>
      <el-button class="log-btn" :icon="Document" @click="openLog">
        运行日志
        <span v-if="newCount > 0" class="log-badge">{{ newCount }}</span>
      </el-button>
    </div>

    <!-- 菜单区 -->
    <div class="menu">
      <template v-if="!store.hasTask">
        <div class="menu-group">
          <span class="eyebrow">开始做题</span>
          <p class="hint hint-long">
            点下面按钮创建任务，然后用手机扫码登录雨课堂。登录后平台会读取你账号里的全部作业，再由你确认用哪个答案库、怎么处理。
          </p>
          <p class="hint hint-short mobile-only">创建任务后扫码登录，剩下的交给 Agent。</p>
        </div>
        <el-button
          type="primary"
          class="full"
          size="large"
          :icon="Promotion"
          :loading="store.starting"
          @click="start"
        >
          新建任务，扫码登录雨课堂
        </el-button>
        <!-- 答案查询统一入口：答案中心页（搜索/浏览/导出/获取记录），不再单独弹窗 -->
        <div class="menu-group mobile-hide">
          <span class="eyebrow">不动手做，只想查答案？</span>
          <p class="hint">
            到「答案中心」搜索或按批次浏览全部答案，可直接导出 Markdown。不建任务、不扫码、不消耗作答次数。
          </p>
          <el-button class="full" :icon="Collection" @click="goAnswerCenter">
            前往答案中心
          </el-button>
        </div>
      </template>

      <template v-else-if="store.waitingBatch">
        <div class="menu-group">
          <span class="eyebrow">已登录 · 等你勾选</span>
          <p class="hint hint-long">
            已登录 <strong>{{ store.session?.yktName || '雨课堂账号' }}</strong
            >，账号作业已列在左侧「试卷清单」。勾选要处理的卷子，然后点下方按钮即可开始。
          </p>
          <p class="hint hint-long">
            新卷子（未入答案库）也可以勾选：点「做题」先做一遍，答案会自动采回答案库。
          </p>
          <p class="hint hint-short mobile-only">
            已登录 {{ store.session?.yktName || '雨课堂账号' }}。到「试卷」页勾选卷子，回这里点开始。
          </p>
          <div class="mobile-only">
            <el-button class="full" :icon="Select" @click="emit('goPapers')">
              去「试卷」页勾选卷子
            </el-button>
          </div>
        </div>

        <!-- 手机端开始按钮统一在底部动作条，这里隐藏避免重复 -->
        <div class="menu-group mobile-hide">
          <span class="eyebrow">开始方式（二选一）</span>
          <el-button
            type="primary"
            class="full"
            size="large"
            :icon="Promotion"
            :disabled="!store.selectedPapers.length"
            :loading="store.starting"
            @click="startChecked('answer')"
          >
            {{ store.selectedPapers.length ? `做勾选的 ${store.selectedPapers.length} 卷` : '做全部作业' }}
          </el-button>
          <el-button
            class="full"
            :icon="Collection"
            :disabled="!store.selectedPapers.length"
            :loading="store.starting"
            @click="startChecked('collect')"
          >
            {{ store.selectedPapers.length ? '取勾选卷子的答案' : '先在清单勾选卷子' }}
          </el-button>
          <p class="hint">
            没勾选就直接点的话，会处理所选批次的全部卷子，外加账号里未入答案库的新卷子
            （新卷子用兜底 API 作答，交卷后答案自动入库）。
          </p>
        </div>
      </template>

      <template v-else>
        <div class="menu-group">
          <span class="eyebrow">任务控制</span>
          <el-button
            v-if="taskFinished"
            type="primary"
            class="full"
            :icon="Promotion"
            :loading="store.starting"
            @click="continueAnswer()"
          >
            继续作答（沿用当前账号）
          </el-button>
          <el-button
            class="full"
            :icon="Download"
            :disabled="!canExport"
            :loading="store.busy"
            @click="store.exportAnswers()"
          >
            导出任务答案
          </el-button>
          <el-button class="full mobile-hide" :icon="Collection" @click="goAnswerCenter">
            前往答案中心
          </el-button>
          <el-button
            v-if="store.canPause"
            class="full"
            :icon="store.task?.status === 'PAUSED' ? VideoPlay : VideoPause"
            :loading="store.busy"
            @click="store.togglePause()"
          >
            {{ store.task?.status === 'PAUSED' ? '继续做题' : '暂停做题' }}
          </el-button>
        </div>

        <div class="menu-group">
          <span class="eyebrow">扫码 / 会话</span>
          <div class="btn-grid">
            <el-button :icon="Promotion" @click="qrVisible = true">查看二维码</el-button>
            <el-button :icon="Refresh" :loading="store.busy" @click="rescan">
              重新获取二维码
            </el-button>
          </div>
          <el-button class="full" :icon="CircleClose" :disabled="!store.session" @click="store.closeActiveSession()">
            关闭会话（二维码作废）
          </el-button>
          <p class="hint">二维码过期时点「重新获取二维码」；会话关闭后需重新扫码。</p>
        </div>

        <button class="ghost-link mobile-hide" type="button" @click="copyToken">复制任务信息（排查用）</button>
      </template>
    </div>

    <!-- 扫码弹窗：挂到 body，避免手机端面板切换（display:none）时弹窗跟着消失 -->
    <el-dialog v-model="qrVisible" title="扫码登录雨课堂" width="420px" align-center append-to-body>
      <div class="qr-wrap">
        <div class="qr-box">
          <img v-if="store.session?.qrcode" :src="store.session.qrcode" alt="雨课堂登录二维码" />
          <div v-else class="qr-pending">
            <el-icon class="spin"><Refresh /></el-icon>
            <span>二维码生成中…</span>
          </div>
        </div>

        <div class="qr-meta">
          <div class="qr-row">
            <span>会话状态</span>
            <StatusPill v-if="sessionStatus" :tone="sessionStatus.tone">
              {{ sessionStatus.label }}
            </StatusPill>
          </div>
          <div class="qr-row">
            <span>剩余有效</span>
            <strong class="mono-sm" :class="{ expired: remain.expired }">{{ remain.text }}</strong>
          </div>
          <div class="qr-row">
            <span>雨课堂账号</span>
            <strong>{{ store.session?.yktName || '待扫码' }}</strong>
          </div>
          <div class="qr-row">
            <span>任务模式</span>
            <strong>{{
              store.task?.batch ? (MODE_LABEL[store.task?.mode ?? ''] ?? '—') : '待选择'
            }}</strong>
          </div>
        </div>

        <p class="qr-tip">
          请使用微信或雨课堂 App 扫码。扫码成功后本弹窗会自动关闭；二维码过期后点「重新获取二维码」即可刷新。
        </p>
      </div>

      <template #footer>
        <el-button @click="qrVisible = false">关闭</el-button>
        <el-button type="primary" :loading="store.busy" @click="rescan">重新获取二维码</el-button>
      </template>
    </el-dialog>

    <!-- 运行日志抽屉：只看事件记录；批次与模式确认都在中栏菜单完成 -->
    <el-drawer v-model="logVisible" title="运行日志" size="420px" :append-to-body="true">
      <div class="log-drawer">
        <div ref="feedRef" class="feed scroll-area">
          <template v-if="store.events.length > 0">
            <div
              v-for="event in store.events"
              :key="event.id"
              class="bubble"
              :class="`l-${event.level}`"
            >
              <div class="bubble-head">
                <span class="tag">{{ LEVEL_LABEL[event.level] }}</span>
                <time class="mono-sm">{{ formatClock(event.at) }}</time>
              </div>
              <div class="bubble-title">{{ event.title }}</div>
              <div v-if="event.detail" class="bubble-detail">{{ event.detail }}</div>
            </div>
          </template>

          <div v-else class="feed-empty">
            <h4>暂无事件</h4>
            <p>新建任务、扫码登录与作答过程中的全部操作记录都会显示在这里。</p>
          </div>
        </div>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.deck {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--surface-2);
  border-right: 1px solid var(--border);
}

.deck-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 18px 18px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}
.deck-title h3 {
  font-size: 16px;
  margin-top: 3px;
}

/* —— 状态摘要条：中栏只留最近一条事件 —— */
.log-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}
.log-latest {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}
.log-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-300);
}
.log-dot.l-ok {
  background: var(--ok);
}
.log-dot.l-warn {
  background: var(--warn);
}
.log-dot.l-danger {
  background: var(--danger);
}
.log-dot.l-agent {
  background: var(--accent);
}
.log-dot.l-info {
  background: var(--info);
}
.log-text {
  min-width: 0;
}
.log-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.log-detail {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--ink-400);
}
.log-btn {
  flex: none;
}
.log-badge {
  display: inline-block;
  min-width: 18px;
  height: 18px;
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}

/* —— 运行日志抽屉 —— */
:deep(.el-drawer__body) {
  display: flex;
  flex-direction: column;
  padding: 16px;
  overflow: hidden;
}
.log-drawer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  height: 100%;
}
.log-drawer :deep(.el-scrollbar),
.log-drawer .feed {
  flex: 1;
  min-height: 0;
}

.feed {
  flex: 1;
  min-height: 0;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
}

.bubble {
  position: relative;
  padding: 10px 12px 10px 14px;
  border-radius: var(--r-sm);
  background: var(--surface);
  border: 1px solid var(--border);
  animation: rise 0.24s ease both;
}
@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
}
.bubble::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  border-radius: 3px 0 0 3px;
  background: var(--ink-200);
}
.bubble.l-ok::before {
  background: var(--ok);
}
.bubble.l-warn::before {
  background: var(--warn);
}
.bubble.l-danger::before {
  background: var(--danger);
}
.bubble.l-agent::before {
  background: var(--accent);
}
.bubble.l-info::before {
  background: var(--info);
}

.bubble-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.tag {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-400);
  letter-spacing: 0.06em;
}
.bubble.l-ok .tag {
  color: var(--ok);
}
.bubble.l-warn .tag {
  color: var(--warn);
}
.bubble.l-danger .tag {
  color: var(--danger);
}
.bubble.l-agent .tag {
  color: var(--accent-700);
}
.bubble-head time {
  font-size: 11px;
  color: var(--ink-300);
}
.bubble-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
}
.bubble-detail {
  margin-top: 2px;
  font-size: 12px;
  color: var(--ink-500);
  word-break: break-all;
}

.feed-empty {
  margin: auto;
  text-align: center;
  padding: 24px 12px;
  color: var(--ink-400);
}
.feed-empty h4 {
  font-size: 14px;
  color: var(--ink-600);
  margin-bottom: 6px;
}
.feed-empty p {
  font-size: 12.5px;
  line-height: 1.7;
  margin: 0;
}

.hint-short {
  display: none;
}

@media (max-width: 768px) {
  /* 移动端减负：标题行隐藏（分段标签已表明当前页），状态行只留状态点 */
  .deck-title {
    display: none;
  }
  .hint-long {
    display: none;
  }
  .hint-short {
    display: block;
  }
  .deck-head {
    padding: 16px 16px 13px;
  }
  .deck-title h3 {
    font-size: 18px;
    margin-top: 4px;
  }
  .log-bar {
    padding: 12px 14px;
  }
  .log-title {
    font-size: 13.5px;
  }
  .log-detail {
    font-size: 12px;
  }
  .log-btn {
    min-height: 38px;
  }
  .menu {
    padding: 16px 14px 24px;
    gap: 14px;
  }
  .menu-group {
    gap: 9px;
  }
  /* 触控目标加大：手机上按钮统一 46px 高 */
  .menu .el-button {
    height: 46px;
    font-size: 15px;
  }
  .menu .el-button.is-large {
    height: 50px;
  }
  .hint {
    font-size: 13px;
    line-height: 1.8;
    color: var(--ink-500);
  }
  .btn-grid {
    grid-template-columns: 1fr;
    gap: 10px;
  }
  .ghost-link {
    font-size: 13px;
    padding: 10px 0;
  }
  .batch-row {
    padding: 12px 10px;
  }
  .b-name {
    font-size: 13px;
  }
  .mode-hint {
    font-size: 13px;
    line-height: 1.8;
  }
  /* 扫码弹窗：二维码放大 + 信息行加高，扫码成功前看得清楚 */
  .qr-box {
    width: 232px;
    height: 232px;
  }
  .qr-meta {
    width: 100%;
    gap: 10px;
  }
  .qr-row {
    font-size: 14px;
  }
  .qr-tip {
    font-size: 12.5px;
    line-height: 1.8;
  }
}

/* —— 待确认批次：候选批次直接在中栏选择 —— */
.batch-scroll {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 240px;
  overflow-y: auto;
}
.panel-empty {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-400);
}


.batch-row {
  display: grid;
  grid-template-columns: 1fr 72px 52px;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px;
  border-radius: var(--r-xs);
  border: 1px solid var(--border);
  background: var(--surface-2);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}
.batch-row:hover {
  border-color: var(--accent-200);
}
.batch-row.is-picked {
  border-color: var(--accent);
  background: var(--accent-050);
}
.b-name {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.b-rec {
  font-style: normal;
  margin-left: 5px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-family: var(--font-sans);
  font-size: 10.5px;
  font-weight: 600;
  vertical-align: 1px;
}
.b-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--ink-100);
  overflow: hidden;
}
.b-bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: var(--accent);
}
.b-num {
  font-size: 11px;
  color: var(--ink-500);
  text-align: right;
}

.menu {
  border-top: 1px solid var(--border);
  background: var(--surface);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.menu-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.full {
  width: 100%;
}
.hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-400);
}
.mode-hint {
  padding: 8px 10px;
  border-radius: var(--r-xs);
  background: var(--surface-2);
  color: var(--ink-600);
}
.mode :deep(.el-radio-button) {
  flex: 1;
}
.mode :deep(.el-radio-button__inner) {
  width: 100%;
}

.btn-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.btn-grid :deep(.el-button) {
  margin: 0;
  width: 100%;
}

.ghost-link {
  border: none;
  background: transparent;
  color: var(--ink-400);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 0;
  text-align: center;
}
.ghost-link:hover {
  color: var(--accent);
}

.qr-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.qr-box {
  display: grid;
  place-items: center;
  width: 208px;
  height: 208px;
  border-radius: var(--r-md);
  border: 1px dashed var(--border-strong);
  background: var(--surface-2);
  overflow: hidden;
}
.qr-box img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.qr-pending {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: var(--ink-400);
  font-size: 13px;
}
.spin {
  font-size: 22px;
  animation: spin 1.1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.qr-meta {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.qr-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}
.qr-row > span:first-child {
  color: var(--ink-500);
}
.qr-row strong {
  color: var(--ink-900);
}
.qr-row strong.expired {
  color: var(--danger);
}

.qr-tip {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-400);
  text-align: center;
}
</style>
