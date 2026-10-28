<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useConsoleStore } from '@/store/console'
import PaperRail from '@/components/console/PaperRail.vue'
import ControlDeck from '@/components/console/ControlDeck.vue'
import AnswerStage from '@/components/console/AnswerStage.vue'
import FlowSteps from '@/components/console/FlowSteps.vue'

const route = useRoute()
const store = useConsoleStore()

const selectedPaper = ref<string | null>(null)

/* 移动端竖屏：三栏改为分段切换（操作 / 进度 / 试卷），桌面端不受影响。
   默认面板跟随任务状态：没任务或待确认 → 操作；做题/结束 → 进度，与直觉对齐。 */
const mobilePanel = ref<'control' | 'answer' | 'papers'>('control')

watch(
  () => store.task?.status,
  (status) => {
    if (!status || status === 'PENDING' || status === 'WAITING_BATCH') {
      mobilePanel.value = 'control'
    } else {
      mobilePanel.value = 'answer'
    }
  },
  { immediate: true },
)

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
    <div class="mobile-tabs">
      <button
        type="button"
        class="mobile-tab"
        :class="{ 'is-on': mobilePanel === 'control' }"
        @click="mobilePanel = 'control'"
      >
        操作
      </button>
      <button
        type="button"
        class="mobile-tab"
        :class="{ 'is-on': mobilePanel === 'answer' }"
        @click="mobilePanel = 'answer'"
      >
        进度
      </button>
      <button
        type="button"
        class="mobile-tab"
        :class="{ 'is-on': mobilePanel === 'papers' }"
        @click="mobilePanel = 'papers'"
      >
        试卷
      </button>
    </div>
    <div class="workspace">
      <PaperRail
        :selected-paper="selectedPaper"
        :class="{ 'is-hidden-mobile': mobilePanel !== 'papers' }"
        @select="(p) => { selectPaper(p); mobilePanel = 'answer' }"
      />
      <ControlDeck
        :class="{ 'is-hidden-mobile': mobilePanel !== 'control' }"
        @go-papers="mobilePanel = 'papers'"
      />
      <AnswerStage
        :selected-paper="selectedPaper"
        :class="{ 'is-hidden-mobile': mobilePanel !== 'answer' }"
      />
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

/* —— 移动端竖屏：分段切换 + 单栏 —— */
.mobile-tabs {
  display: none;
}

@media (max-width: 768px) {
  .mobile-tabs {
    display: flex;
    gap: 8px;
    padding: 10px 14px 12px;
    background: var(--surface);
    border-bottom: 1px solid var(--border);
    flex: none;
  }
  .mobile-tab {
    flex: 1;
    height: 48px;
    border: 1.5px solid var(--border);
    border-radius: 14px;
    background: var(--surface-2);
    color: var(--ink-500);
    font-family: var(--font-sans);
    font-size: 15px;
    font-weight: 700;
    letter-spacing: 0.04em;
    cursor: pointer;
    transition: background-color 0.16s ease, border-color 0.16s ease, color 0.16s ease;
  }
  .mobile-tab:active {
    transform: scale(0.98);
  }
  .mobile-tab.is-on {
    background: var(--accent-100);
    border-color: var(--accent);
    color: var(--accent-700);
    box-shadow: var(--shadow-xs);
  }
  .workspace {
    grid-template-columns: 1fr;
  }
  .is-hidden-mobile {
    display: none;
  }
}
</style>
