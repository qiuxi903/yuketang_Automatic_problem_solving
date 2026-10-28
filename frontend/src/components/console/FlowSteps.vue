<script setup lang="ts">
import { computed } from 'vue'
import { Check } from '@element-plus/icons-vue'
import { useConsoleStore } from '@/store/console'

/**
 * 主流程步骤条：让页面任何时刻都回答「现在到哪一步、接下来干什么」。
 * 由任务状态自动推导，不需要用户点击。
 */
const store = useConsoleStore()

const STEPS = [
  { title: '新建任务并扫码', hint: '用手机扫码登录雨课堂' },
  { title: '勾选卷子', hint: '在试卷清单勾选，点做题或取答案' },
  { title: '自动做题中', hint: '进度实时更新，可暂停' },
  { title: '完成导出', hint: '查看成绩，导出答案' },
] as const

const FAILED_LABEL: Record<string, string> = {
  FAILED: '任务失败',
  CANCELLED: '任务已中断',
}

const index = computed(() => {
  const t = store.task
  if (!t) return 0
  if (t.status === 'WAITING_BATCH') return 1
  if (t.status === 'SUCCESS' || t.status === 'FAILED' || t.status === 'CANCELLED') return 3
  // 任务一创建后端就置 RUNNING（Agent 正在等你扫码）：还没登录就仍算「扫码」这一步
  if (!store.loggedIn) return 0
  return 2
})

/** 终态（失败/中断）时第四步显示原因而不是「完成导出」。 */
const lastTitle = computed(() => {
  const status = store.task?.status
  return (status && FAILED_LABEL[status]) || STEPS[3].title
})

type StepTone = 'done' | 'active' | 'todo' | 'bad'

const tones = computed<StepTone[]>(() =>
  STEPS.map((_, i) => {
    if (i < index.value) return 'done'
    if (i === index.value) {
      const status = store.task?.status
      return status === 'FAILED' || status === 'CANCELLED' ? 'bad' : 'active'
    }
    return 'todo'
  }),
)
</script>

<template>
  <div class="flow" :class="{ 'is-idle': !store.hasTask }">
    <template v-for="(step, i) in STEPS" :key="step.title">
      <div v-if="i > 0" class="flow-line" :class="{ passed: i <= index }" />
      <div class="flow-step" :class="tones[i]">
        <span class="flow-dot">
          <el-icon v-if="tones[i] === 'done'"><Check /></el-icon>
          <template v-else>{{ i + 1 }}</template>
        </span>
        <span class="flow-text">
          <b>{{ i === 3 ? lastTitle : step.title }}</b>
          <em>{{ step.hint }}</em>
        </span>
      </div>
    </template>

    <span v-if="store.task && store.task.totalPaper" class="flow-count">
      已完成 <strong>{{ store.task.donePaper }}</strong> / {{ store.task.totalPaper }} 套
    </span>
  </div>
</template>

<style scoped>
.flow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 20px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  flex: none;
  overflow-x: auto;
}

.flow-step {
  display: flex;
  align-items: center;
  gap: 9px;
  flex: none;
}
.flow-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  white-space: nowrap;
}
.flow-text b {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-300);
}
.flow-text em {
  font-style: normal;
  font-size: 11px;
  color: var(--ink-300);
}

.flow-dot {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1.5px solid var(--ink-200);
  color: var(--ink-300);
  font-size: 11.5px;
  font-weight: 700;
  flex: none;
  background: var(--surface);
}

.flow-step.active .flow-dot {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
  box-shadow: 0 0 0 4px var(--accent-100);
}
.flow-step.active .flow-text b {
  color: var(--accent-700);
}
.flow-step.active .flow-text em {
  color: var(--ink-500);
}

.flow-step.done .flow-dot {
  border-color: var(--accent);
  background: var(--accent-100);
  color: var(--accent-700);
}
.flow-step.done .flow-text b {
  color: var(--ink-600);
}
.flow-step.done .flow-text em {
  color: var(--ink-400);
}

.flow-step.bad .flow-dot {
  border-color: var(--danger);
  background: var(--danger);
  color: #fff;
}
.flow-step.bad .flow-text b {
  color: var(--danger);
}

.flow-line {
  flex: 1;
  min-width: 18px;
  height: 1.5px;
  background: var(--ink-100);
}
.flow-line.passed {
  background: var(--accent-200);
}

.flow-count {
  margin-left: auto;
  flex: none;
  padding: 4px 12px;
  border-radius: 99px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  font-size: 12px;
  color: var(--ink-500);
  white-space: nowrap;
}
.flow-count strong {
  font-family: var(--font-mono);
  color: var(--accent-700);
}

@media (max-width: 768px) {
  /* 移动端隐藏步骤条：任务状态已在各面板内展示，少一层视觉噪音 */
  .flow {
    display: none;
  }
}
</style>
