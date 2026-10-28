<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { Download, Refresh } from '@element-plus/icons-vue'
import { getPaperAnswers } from '@/api/paper'
import { useConsoleStore } from '@/store/console'
import { countdown, formatClock, relativeTime } from '@/utils/format'
import { MODE_HINT, MODE_LABEL, sessionMeta, taskMeta } from '@/utils/status'
import StatusPill from '@/components/ui/StatusPill.vue'
import type { PaperAnswers } from '@/types'

const props = defineProps<{ selectedPaper: string | null }>()

const store = useConsoleStore()

const sheet = ref<PaperAnswers | null>(null)
const sheetLoading = ref(false)
const sheetError = ref('')
const now = ref(Date.now())
// 右侧视图：答题卡 / 浏览器模式（雨课堂原始页面）
const view = ref<'sheet' | 'browser'>('sheet')

const task = computed(() => store.task)
const sessionStatus = computed(() => (store.session ? sessionMeta(store.session.status) : null))
const taskStatusMeta = computed(() => (task.value ? taskMeta(task.value.status) : null))
const remain = computed(() => countdown(store.session?.expireAt, now.value))

const activePaper = computed(() => props.selectedPaper || task.value?.currentPaper || null)
const isLivePaper = computed(() => activePaper.value !== null && activePaper.value === task.value?.currentPaper)

/** 任务进入终态后的收尾摘要：完成给导出入口，失败/中断给原因与重开入口。 */
const finished = computed(() => {
  const t = task.value
  if (!t) return null
  if (t.status === 'SUCCESS') return { kind: 'ok', title: '全部试卷已完成' }
  if (t.status === 'FAILED') return { kind: 'danger', title: '任务失败' }
  if (t.status === 'CANCELLED') return { kind: 'neutral', title: '任务已中断' }
  return null
})

const token = computed(() => {
  const current = task.value
  if (!current) return '—'
  return `T${current.taskId}·S${current.sessionId}`
})

const questionPercent = computed(() => {
  const current = task.value
  if (!current?.currentTotal) return 0
  return Math.min(100, Math.round((current.currentSeq / current.currentTotal) * 100))
})

// 当前试卷对应的任务明细：Agent 实时抓到的题目原文（含选项）就挂在这里
const currentItem = computed(() => {
  const paper = activePaper.value
  if (!paper) return null
  const items = task.value?.items ?? []
  return (
    items.find((it) => it.paper === paper) ??
    items.find((it) => it.paper.includes(paper) || paper.includes(it.paper)) ??
    null
  )
})

/** 距 Agent 最近一次动静的秒数：>10s 说明当前步骤耗时较长（心跳会说明在干什么）。 */
const idleSeconds = computed(() => Math.floor((now.value - store.lastActivityAt) / 1000))
const taskActive = computed(() => task.value?.status === 'RUNNING')

// 题目完整信息：本地答案库答案 + Agent 抓取的题干/选项，按题号合并
const rows = computed(() => {
  const questions = currentItem.value?.questions ?? []
  const qmap = new Map(questions.map((q) => [q.seq, q]))
  const answers = sheet.value?.answers ?? []
  if (answers.length) {
    return answers.map((a) => ({
      seq: a.seq,
      stem: a.stem || qmap.get(a.seq)?.stem || '',
      options: qmap.get(a.seq)?.options ?? [],
      answer: a.answer || '',
    }))
  }
  // 本地无答案库记录时，退回 Agent 实时抓到的题目原文
  return questions.map((q) => ({ seq: q.seq, stem: q.stem, options: q.options, answer: '' }))
})
const hasRows = computed(() => rows.value.length > 0)

// 浏览器模式：最近一帧的更新时刻
const frameAge = computed(() =>
  store.frame?.updatedAt ? relativeTime(store.frame.updatedAt) : '等待画面',
)

// 切换到浏览器模式时轮询实时画面，返回答题卡时停止
watch(view, (next) => {
  if (next === 'browser') store.startFramePolling()
  else store.stopFramePolling()
})

const clock = window.setInterval(() => (now.value = Date.now()), 1000)
onUnmounted(() => {
  window.clearInterval(clock)
  store.stopFramePolling()
})

async function loadSheet() {
  const batch = task.value?.batch
  const paper = activePaper.value
  sheet.value = null
  sheetError.value = ''
  if (!batch || !paper) return

  sheetLoading.value = true
  try {
    sheet.value = await getPaperAnswers(batch, paper)
  } catch {
    sheetError.value = '该试卷暂无本地答案库记录，可能由兜底 API 或 Agent 临时作答。'
  } finally {
    sheetLoading.value = false
  }
}

watch([() => task.value?.batch, activePaper, () => task.value?.taskId], loadSheet, { immediate: true })
</script>

<template>
  <section class="stage">
    <div class="stage-head">
      <div class="stage-title">
        <span class="eyebrow">答题区 · 实时过程</span>
        <h3>{{ task ? `${task.batch || '等你确认答案库'} · ${MODE_LABEL[task.mode]}` : '等待任务' }}</h3>
      </div>
      <div class="stage-head-right">
        <StatusPill v-if="taskStatusMeta" :tone="taskStatusMeta.tone">
          {{ taskStatusMeta.label }}
        </StatusPill>
        <el-radio-group v-model="view" size="small" class="view-switch">
          <el-radio-button value="sheet">答题卡</el-radio-button>
          <el-radio-button value="browser">浏览器模式</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <div class="stage-scroll scroll-area">
      <template v-if="task">
        <!-- 浏览器模式：直接查看雨课堂原始页面（Agent 实时回传画面） -->
        <div v-if="view === 'browser'" class="browser">
          <div class="browser-bar">
            <span class="eyebrow">浏览器模式 · 雨课堂原始页面</span>
            <span class="mono-sm">{{ frameAge }}</span>
            <el-button
              size="small"
              :icon="Refresh"
              :loading="store.frameLoading"
              @click="store.loadFrame()"
            >
              刷新画面
            </el-button>
          </div>
          <div class="browser-view">
            <img v-if="store.frame?.image" :src="store.frame.image" alt="雨课堂实时页面" />
            <div v-else class="browser-empty">
              <el-icon class="spin"><Refresh /></el-icon>
              <span>暂无实时画面，Agent 打开雨课堂页面后会自动投屏</span>
            </div>
          </div>
          <div v-if="store.frame?.url" class="browser-url mono-sm">{{ store.frame.url }}</div>
        </div>

        <template v-else>
        <!-- 任务收尾总结卡：完成 / 失败 / 中断时第一时间给出结果与下一步 -->
        <div v-if="finished" class="done-card" :class="`k-${finished.kind}`">
          <div class="done-main">
            <h3>{{ finished.title }}</h3>
            <p class="done-stat">
              完成 {{ task.donePaper }}/{{ task.totalPaper }} 套
              <template v-if="task.score"> · 累计得分 <strong>{{ task.score }}</strong></template>
            </p>
            <p v-if="task.status === 'FAILED' && task.message" class="done-reason">{{ task.message }}</p>
          </div>
          <div class="done-actions">
            <el-button
              type="primary"
              :icon="Download"
              :disabled="task.donePaper <= 0"
              :loading="store.busy"
              @click="store.exportAnswers()"
            >
              导出答案
            </el-button>
            <el-button @click="store.detach()">再发起一次任务</el-button>
          </div>
        </div>

        <!-- 过程主卡 -->
        <div class="hero">
          <div class="hero-left">
            <span class="hero-label">当前作答试卷</span>
            <h2 class="hero-paper">{{ task.currentPaper || '等待 Agent 进入试卷' }}</h2>
            <div class="hero-tags">
              <StatusPill tone="accent" :dot="false">{{ MODE_LABEL[task.mode] ?? task.mode }}</StatusPill>
              <span class="hero-score">累计得分 <strong>{{ task.score || '—' }}</strong></span>
            </div>
            <div v-if="task.message" class="hero-action">
              <span class="action-text">{{ task.message }}</span>
              <span v-if="taskActive && idleSeconds > 10" class="action-idle">
                已 {{ idleSeconds }}s 无新进展
              </span>
            </div>
            <p v-if="MODE_HINT[task.mode]" class="mode-note">{{ MODE_HINT[task.mode] }}</p>
          </div>
          <div class="hero-ring">
            <svg viewBox="0 0 120 120" class="ring">
              <circle class="ring-track" cx="60" cy="60" r="52" />
              <circle
                class="ring-fill"
                cx="60"
                cy="60"
                r="52"
                :stroke-dasharray="`${store.progressPercent * 3.27} 327`"
              />
            </svg>
            <div class="ring-text">
              <strong>{{ store.progressPercent }}<i>%</i></strong>
              <span>{{ task.donePaper }}/{{ task.totalPaper }} 套</span>
            </div>
          </div>
        </div>

        <!-- 题号进度 -->
        <div class="q-progress">
          <div class="q-line">
            <span class="q-label">题号进度</span>
            <span class="mono-sm">
              {{ task.currentTotal ? `${task.currentSeq} / ${task.currentTotal}` : '—' }}
            </span>
          </div>
          <el-progress
            :percentage="questionPercent"
            :show-text="false"
            :stroke-width="10"
            striped
            striped-flow
          />
        </div>

        <!-- 账号与会话等技术细节：默认折叠，需要排查时展开 -->
        <details class="account-panel">
          <summary>
            <span class="account-title">运行详情 · 账号 / 会话 / 令牌</span>
            <StatusPill :tone="store.loggedIn ? 'ok' : 'neutral'" :dot="false">
              {{ store.loggedIn ? '已登录' : '未登录' }}
            </StatusPill>
          </summary>
          <div class="info-grid">
            <div class="info-cell">
              <span class="info-label">雨课堂账号</span>
              <strong>{{ store.accountNameText }}</strong>
            </div>
            <div class="info-cell">
              <span class="info-label">课程</span>
              <strong>{{ task.course || '—' }}</strong>
            </div>
            <div class="info-cell">
              <span class="info-label">批次</span>
              <strong>{{ task.batch || '待确认' }}</strong>
            </div>
            <div class="info-cell">
              <span class="info-label">任务令牌</span>
              <strong class="mono-sm">{{ token }}</strong>
            </div>
            <div class="info-cell">
              <span class="info-label">会话状态</span>
              <StatusPill v-if="sessionStatus" :tone="sessionStatus.tone">
                {{ sessionStatus.label }}
              </StatusPill>
              <strong v-else class="mono-sm">—</strong>
            </div>
            <div class="info-cell">
              <span class="info-label">会话创建</span>
              <strong class="mono-sm">{{ store.sessionCreated }}</strong>
            </div>
            <div class="info-cell">
              <span class="info-label">
                {{ store.session?.status === 'WAITING_SCAN' ? '二维码截止' : '会话截止' }}
              </span>
              <strong class="mono-sm" :class="{ expired: remain.expired }">{{ remain.text }}</strong>
            </div>
            <div class="info-cell">
              <span class="info-label">账号作业</span>
              <strong class="mono-sm">{{ store.accountPapers.length }} 套</strong>
            </div>
            <div class="info-cell">
              <span class="info-label">作答进度</span>
              <strong class="mono-sm">{{ store.doneRows.length }}/{{ store.resultRows.length }} 套</strong>
            </div>
          </div>
        </details>

        <!-- 答题卡 -->
        <div class="sheet">
          <div class="sheet-head">
            <span class="eyebrow">答题卡</span>
            <h4>{{ activePaper || '未选择试卷' }}</h4>
            <StatusPill v-if="isLivePaper" tone="accent">正在作答</StatusPill>
          </div>

          <el-skeleton v-if="sheetLoading" :rows="5" animated class="sheet-skeleton" />

          <el-alert
            v-else-if="sheetError"
            type="info"
            :closable="false"
            show-icon
            :title="sheetError"
          />

          <template v-else-if="hasRows">
            <div class="sheet-summary mono-sm">
              共 {{ rows.length }} 题
            </div>
            <ol class="answer-list">
              <li
                v-for="row in rows"
                :key="row.seq"
                class="answer"
                :class="{ live: isLivePaper && row.seq === task.currentSeq }"
              >
                <span class="seq mono-sm">{{ row.seq }}</span>
                <div class="answer-body">
                  <p class="stem">{{ row.stem || '（题干缺失）' }}</p>
                  <ul v-if="row.options.length" class="opts">
                    <li v-for="(opt, idx) in row.options" :key="idx" class="opt">{{ opt }}</li>
                  </ul>
                  <span class="ans">{{ row.answer || '—' }}</span>
                </div>
              </li>
            </ol>
          </template>

          <el-empty v-else-if="activePaper" :image-size="66" description="暂无题目内容" />
          <el-empty v-else :image-size="66" description="从左侧选择一套试卷查看答题卡" />
        </div>

        <div v-if="task.message" class="stage-msg">
          <span class="eyebrow">最新消息</span>
          <p>{{ task.message }}</p>
        </div>
        </template>
      </template>

      <div v-else class="stage-empty">
        <div class="stage-empty-mark">
          <span class="pulse" />
        </div>
        <h3>三步开始自动做题</h3>
        <p>点中间栏「新建任务，扫码登录雨课堂」按钮开始。登录后确认答案库与做题方式，这里就会实时展示答题过程与成绩。</p>
        <div class="stage-empty-hints">
          <div class="hint"><b>1</b> 新建任务并扫码</div>
          <div class="hint"><b>2</b> 确认答案库与方式</div>
          <div class="hint"><b>3</b> 看进度，导出答案</div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.stage {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
  background: var(--paper);
}

.stage-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  flex: none;
}
.stage-title h3 {
  font-size: 17px;
  margin-top: 3px;
}
.stage-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stage-scroll {
  flex: 1;
  min-height: 0;
  padding: 20px 24px 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 22px 24px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
}
.hero-label {
  font-size: 12px;
  color: var(--ink-400);
}
.hero-paper {
  font-size: 22px;
  margin: 6px 0 12px;
  letter-spacing: -0.015em;
}
.hero-tags {
  display: flex;
  align-items: center;
  gap: 10px;
}
.hero-score {
  font-size: 12.5px;
  color: var(--ink-500);
}
.hero-score strong {
  color: var(--ok);
  font-family: var(--font-mono);
}
.mode-note {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-400);
}

/* —— 当前动作行：Agent 正在干什么 + 卡顿提示 —— */
.hero-action {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 6px 12px;
  border-radius: var(--r-xs);
  background: var(--surface-2);
  max-width: 520px;
}
.action-text {
  font-size: 12.5px;
  color: var(--ink-700);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.action-idle {
  flex: none;
  padding: 1px 8px;
  border-radius: 99px;
  background: var(--warn-100);
  color: var(--warn);
  font-size: 11.5px;
  font-weight: 600;
}

/* —— 任务收尾总结卡 —— */
.done-card {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 18px 22px;
  border: 1px solid var(--border);
  border-left-width: 4px;
  border-radius: var(--r-lg);
  background: var(--surface);
  box-shadow: var(--shadow-xs);
}
.done-card.k-ok {
  border-left-color: var(--ok);
}
.done-card.k-danger {
  border-left-color: var(--danger);
}
.done-card.k-neutral {
  border-left-color: var(--ink-300);
}
.done-main {
  flex: 1;
  min-width: 0;
}
.done-main h3 {
  font-size: 17px;
  color: var(--ink-900);
}
.done-card.k-ok .done-main h3 {
  color: var(--ok);
}
.done-card.k-danger .done-main h3 {
  color: var(--danger);
}
.done-stat {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--ink-600);
}
.done-stat strong {
  font-family: var(--font-mono);
  color: var(--ok);
}
.done-reason {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--danger);
  word-break: break-word;
}
.done-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: none;
}

.hero-ring {
  position: relative;
  width: 116px;
  height: 116px;
  flex: none;
}
.ring {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}
.ring-track {
  fill: none;
  stroke: var(--surface-3);
  stroke-width: 10;
}
.ring-fill {
  fill: none;
  stroke: var(--accent);
  stroke-width: 10;
  stroke-linecap: round;
  transition: stroke-dasharray 0.5s ease;
}
.ring-text {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
}
.ring-text strong {
  font-family: var(--font-display);
  font-size: 24px;
  color: var(--ink-900);
}
.ring-text strong i {
  font-style: normal;
  font-size: 13px;
  color: var(--ink-400);
  margin-left: 1px;
}
.ring-text span {
  font-size: 11.5px;
  color: var(--ink-400);
}

.q-progress {
  padding: 16px 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
}
.q-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.q-label {
  font-size: 12.5px;
  color: var(--ink-500);
}

.account-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px 18px 14px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
}
.account-panel summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  cursor: pointer;
  list-style: none;
  user-select: none;
}
.account-panel summary::-webkit-details-marker {
  display: none;
}
.account-panel[open] summary {
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--border);
}
.account-title {
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--ink-400);
  font-weight: 600;
}
.account-title::before {
  content: '▸ ';
}
.account-panel[open] .account-title::before {
  content: '▾ ';
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.info-cell {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 13px 15px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
}
.info-label {
  font-size: 11.5px;
  color: var(--ink-400);
}
.info-cell strong {
  font-size: 13.5px;
  color: var(--ink-900);
}
.info-cell strong.expired {
  color: var(--danger);
}

.sheet {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  padding: 18px 20px 22px;
}
.sheet-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.sheet-head h4 {
  font-size: 15px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sheet-skeleton {
  padding: 8px 0;
}
.sheet-summary {
  color: var(--ink-400);
  font-size: 12px;
  margin-bottom: 10px;
}

.answer-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.answer {
  display: grid;
  grid-template-columns: 30px 1fr;
  gap: 12px;
  padding: 11px 13px;
  border-radius: var(--r-sm);
  background: var(--surface-2);
  border: 1px solid transparent;
  transition: background-color 0.2s ease, border-color 0.2s ease;
}
.answer.live {
  background: var(--accent-050);
  border-color: var(--accent-200);
}
.seq {
  display: grid;
  place-items: center;
  height: 22px;
  border-radius: 6px;
  background: var(--surface-3);
  color: var(--ink-500);
  font-size: 11.5px;
}
.answer.live .seq {
  background: var(--accent);
  color: #fff;
}
.answer-body {
  min-width: 0;
}
.stem {
  margin: 0;
  font-size: 13px;
  color: var(--ink-700);
  line-height: 1.6;
  word-break: break-word;
}
.ans {
  display: inline-block;
  margin-top: 6px;
  padding: 1px 10px;
  border-radius: 99px;
  background: var(--ok-100);
  color: var(--ok);
  font-size: 12.5px;
  font-weight: 600;
  font-family: var(--font-mono);
}

/* —— 题目选项（Agent 实时抓取）—— */
.opts {
  list-style: none;
  margin: 7px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.opt {
  padding: 5px 10px;
  border-radius: var(--r-xs);
  background: var(--surface);
  border: 1px solid var(--border);
  font-size: 12.5px;
  color: var(--ink-600);
  line-height: 1.6;
  word-break: break-word;
}

/* —— 浏览器模式 —— */
.view-switch {
  flex: none;
}
.browser {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.browser-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
}
.browser-bar .mono-sm {
  flex: 1;
  color: var(--ink-400);
}
.browser-view {
  height: 62vh;
  min-height: 380px;
  display: grid;
  place-items: center;
  padding: 10px;
  background: var(--ink-900);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  overflow: hidden;
}
.browser-view img {
  max-width: 100%;
  max-height: 100%;
  width: auto;
  height: auto;
  object-fit: contain;
  border-radius: var(--r-sm);
}
.browser-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: rgba(255, 255, 255, 0.6);
  font-size: 13px;
  text-align: center;
}
.browser-empty .spin {
  font-size: 24px;
}
.spin {
  animation: spin 1.1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.browser-url {
  padding: 8px 12px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  color: var(--ink-400);
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stage-msg {
  padding: 14px 18px;
  background: var(--info-100);
  border-radius: var(--r-md);
}
.stage-msg p {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--info);
}

.stage-empty {
  margin: auto;
  max-width: 460px;
  text-align: center;
  padding: 40px 20px;
}
.stage-empty-mark {
  display: grid;
  place-items: center;
  width: 60px;
  height: 60px;
  margin: 0 auto 18px;
  border-radius: 50%;
  background: var(--surface);
  border: 1px solid var(--border);
}
.stage-empty-mark .pulse {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 1.8s infinite;
}
@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 var(--accent-200);
  }
  70% {
    box-shadow: 0 0 0 12px transparent;
  }
  100% {
    box-shadow: 0 0 0 0 rgba(31, 111, 92, 0);
  }
}
.stage-empty h3 {
  font-size: 18px;
  margin-bottom: 10px;
}
.stage-empty p {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--ink-500);
  margin: 0 0 20px;
}
.stage-empty-hints {
  display: flex;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}
.hint {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 13px;
  border-radius: 99px;
  background: var(--surface);
  border: 1px solid var(--border);
  font-size: 12.5px;
  color: var(--ink-600);
}
.hint b {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--accent-100);
  color: var(--accent-700);
  font-size: 11px;
}

@media (max-width: 1440px) {
  .info-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .stage-head {
    padding: 13px 14px;
    flex-wrap: wrap;
    gap: 8px;
  }
  /* 移动端减负：标题行隐藏（分段标签已表明当前页），只留状态与视图切换 */
  .stage-title {
    display: none;
  }
  .stage-head-right {
    width: 100%;
    justify-content: space-between;
  }
  .view-switch {
    margin-left: auto;
  }
  .stage-scroll {
    padding: 14px 14px 28px;
    gap: 14px;
  }
  .hero {
    flex-direction: column;
    align-items: stretch;
    gap: 18px;
    padding: 20px 18px;
  }
  .hero-paper {
    font-size: 21px;
  }
  .hero-ring {
    width: 108px;
    height: 108px;
    align-self: center;
  }
  .hero-action {
    font-size: 13.5px;
    max-width: none;
    padding: 9px 12px;
  }
  .action-text {
    white-space: normal;
    line-height: 1.6;
  }
  .mode-note {
    display: none;
  }
  .q-progress {
    padding: 14px 16px;
  }
  .done-card {
    flex-direction: column;
    align-items: stretch;
    gap: 14px;
  }
  .done-actions {
    flex-direction: row;
  }
  .done-actions .el-button {
    flex: 1;
  }
  .info-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .browser-view {
    height: 48vh;
    min-height: 280px;
  }
  .sheet {
    padding: 16px 14px 20px;
  }
  .answer {
    grid-template-columns: 30px 1fr;
    padding: 12px 12px;
  }
  .stem {
    font-size: 13.5px;
  }
  .opt {
    font-size: 13px;
    padding: 6px 10px;
  }
  .stage-empty {
    padding: 32px 16px;
  }
  .stage-empty h3 {
    font-size: 19px;
  }
}
</style>
