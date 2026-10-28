<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useConsoleStore } from '@/store/console'
import { useIsMobile } from '@/utils/useMedia'
import PaperRail from '@/components/console/PaperRail.vue'
import ControlDeck from '@/components/console/ControlDeck.vue'
import AnswerStage from '@/components/console/AnswerStage.vue'
import FlowSteps from '@/components/console/FlowSteps.vue'
import type { TaskMode } from '@/types'

const route = useRoute()
const store = useConsoleStore()
const isMobile = useIsMobile()

const selectedPaper = ref<string | null>(null)
const deckRef = ref<InstanceType<typeof ControlDeck> | null>(null)

/* 移动端：四步流程向导（扫码 → 选卷 → 做题 → 结果），步骤由任务状态自动推导并自动推进；
   桌面端三栏同时可见，不受影响。 */
const mobilePanel = ref<'control' | 'answer' | 'papers'>('control')
/** 用户手动点步骤条的时间：3 秒内不自动抢跳，避免和用户操作打架 */
const manualAt = ref(0)

const STEPS = [
  { n: 1, label: '扫码登录', panel: 'control' as const },
  { n: 2, label: '勾选卷子', panel: 'papers' as const },
  { n: 3, label: '自动做题', panel: 'answer' as const },
  { n: 4, label: '查看结果', panel: 'answer' as const },
]

const step = computed(() => {
  const t = store.task
  if (!t) return 1
  switch (t.status) {
    case 'SUCCESS':
    case 'FAILED':
    case 'CANCELLED':
      return 4
    case 'RUNNING':
    case 'PAUSED':
      return 3
    case 'WAITING_BATCH':
      return 2 // 扫完码：直接落到「勾选卷子」
    default:
      return 1
  }
})

watch(
  step,
  (s) => {
    if (Date.now() - manualAt.value < 3000) return
    mobilePanel.value = STEPS[s - 1].panel
  },
  { immediate: true },
)

function gotoStep(n: number) {
  manualAt.value = Date.now()
  mobilePanel.value = STEPS[n - 1].panel
}

/** 底部动作条只在「选卷」「做题」两步出现（这两步面板里没有唯一的主按钮） */
const showActionBar = computed(
  () => isMobile.value && (step.value === 2 || step.value === 3),
)

async function startChecked(mode: TaskMode) {
  const papers = [...store.selectedPapers]
  const id = await store.continueTask(papers, mode)
  if (id) {
    ElMessage.success(papers.length ? `已发起勾选的 ${papers.length} 套` : '已发起全部作业')
  }
}

function selectPaper(paper: string) {
  selectedPaper.value = paper
}

// 任务接入后自动选中当前试卷，跟随 Agent 的作答进度
watch(
  () => store.task,
  (task) => {
    if (!task) {
      selectedPaper.value = null
      return
    }
    const papers = task.items.map((item) => item.paper)
    if (selectedPaper.value && papers.includes(selectedPaper.value)) return
    selectedPaper.value = task.currentPaper ?? papers[0] ?? null
  },
  { immediate: true },
)

onMounted(async () => {
  const queryTask = Number(route.query.task)
  if (queryTask) {
    try {
      if (queryTask !== store.activeTaskId) await store.attachTask(queryTask)
      return
    } catch {
      // 地址栏里的任务号失效（例如换了账号或后端重启丢数据），回退到账号任务列表
    }
  }
  // 未指定任务：按当前登录账号恢复最近的任务，刷新不再丢任务
  if (!store.hasTask) await store.restoreTask()
})
</script>

<template>
  <div class="console-page">
    <FlowSteps />

    <!-- 移动端：四步流程条（点哪一步看哪一步，当前步高亮、已完成打勾） -->
    <div class="steps">
      <button
        v-for="s in STEPS"
        :key="s.n"
        type="button"
        class="step"
        :class="{ 'is-on': step === s.n, 'is-done': step > s.n }"
        @click="gotoStep(s.n)"
      >
        <span class="step-dot">{{ step > s.n ? '✓' : s.n }}</span>
        <span class="step-label">{{ s.label }}</span>
      </button>
    </div>

    <div class="workspace">
      <PaperRail
        :selected-paper="selectedPaper"
        :class="{ 'is-hidden-mobile': mobilePanel !== 'papers' }"
        @select="(p) => { selectPaper(p); mobilePanel = 'answer' }"
      />
      <ControlDeck
        ref="deckRef"
        :class="{ 'is-hidden-mobile': mobilePanel !== 'control' }"
        @go-papers="mobilePanel = 'papers'"
      />
      <AnswerStage
        :selected-paper="selectedPaper"
        :class="{ 'is-hidden-mobile': mobilePanel !== 'answer' }"
      />
    </div>

    <!-- 移动端底部动作条：永远告诉用户「下一步点什么」 -->
    <div v-if="showActionBar" class="action-bar">
      <template v-if="step === 2">
        <span class="ab-hint">
          {{ store.selectedPapers.length ? `已选 ${store.selectedPapers.length} 套` : '不勾选则处理全部作业' }}
        </span>
        <el-button
          type="primary"
          :loading="store.starting"
          @click="startChecked('answer')"
        >
          {{ store.selectedPapers.length ? '开始做题' : '做全部作业' }}
        </el-button>
        <el-button
          :disabled="!store.selectedPapers.length"
          :loading="store.starting"
          @click="startChecked('collect')"
        >
          只取答案
        </el-button>
      </template>

      <template v-else>
        <span class="ab-hint">{{ store.task?.currentPaper || '准备中…' }}</span>
        <el-button v-if="store.canPause" @click="store.togglePause()">
          {{ store.task?.status === 'PAUSED' ? '继续做题' : '暂停' }}
        </el-button>
        <el-button @click="deckRef?.openQrcode()">二维码</el-button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.console-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  /* 三栏工作台永不整页滚动：各栏内部自己滚 */
  overflow: hidden;
}

.workspace {
  display: grid;
  grid-template-columns: 300px 320px 1fr;
  /* 行高锁死在容器内：列内容再多也只会在各自栏内滚动，绝不撑高网格 */
  grid-template-rows: minmax(0, 1fr);
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

@media (max-width: 1280px) {
  .workspace {
    grid-template-columns: 256px 288px 1fr;
  }
}

/* —— 移动端：四步流程条 + 底部动作条 —— */
.steps {
  display: none;
}

.action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--surface);
  border-top: 1px solid var(--border);
  flex: none;
}
.ab-hint {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.action-bar .el-button {
  flex: none;
}

@media (max-width: 768px) {
  .steps {
    display: flex;
    align-items: stretch;
    gap: 6px;
    padding: 10px 12px;
    background: var(--surface);
    border-bottom: 1px solid var(--border);
    flex: none;
  }
  .step {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 3px;
    padding: 7px 4px;
    border: 1px solid transparent;
    border-radius: 12px;
    background: transparent;
    font-family: var(--font-sans);
    cursor: pointer;
    transition: background-color 0.16s ease, border-color 0.16s ease;
  }
  .step-dot {
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: var(--surface-3);
    color: var(--ink-500);
    font-size: 12px;
    font-weight: 700;
  }
  .step-label {
    font-size: 11.5px;
    font-weight: 600;
    color: var(--ink-400);
  }
  .step.is-on {
    background: var(--accent-050);
    border-color: var(--accent-200);
  }
  .step.is-on .step-dot {
    background: var(--accent);
    color: #fff;
  }
  .step.is-on .step-label {
    color: var(--accent-700);
  }
  .step.is-done .step-dot {
    background: var(--accent-100);
    color: var(--accent-700);
  }
  .step.is-done .step-label {
    color: var(--ink-500);
  }

  .workspace {
    grid-template-columns: 1fr;
  }
  .is-hidden-mobile {
    display: none;
  }
}
</style>
