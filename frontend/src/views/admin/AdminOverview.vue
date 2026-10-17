<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getOverview } from '@/api/admin'
import { formatDateTime } from '@/utils/format'
import { sessionMeta, taskMeta } from '@/utils/status'
import StatusPill from '@/components/ui/StatusPill.vue'
import type { AdminOverview, TaskStatus, SessionStatus } from '@/types'

const loading = ref(false)
const data = ref<AdminOverview | null>(null)

const stats = computed(() => {
  const d = data.value
  if (!d) return []
  return [
    { label: '平台用户', value: d.userCount, sub: `管理员 ${d.adminCount}` },
    { label: '作答任务', value: d.taskCount, sub: `会话 ${d.sessionCount}` },
    { label: '试卷总数', value: d.paperCount, sub: `批次 ${d.batchCount}` },
    { label: '题目总数', value: d.questionCount, sub: '本地答案库' },
  ]
})

const taskStatusRows = computed(() => {
  const d = data.value
  if (!d) return []
  const total = d.taskCount || 1
  return Object.entries(d.taskStatus).map(([status, count]) => {
    const meta = taskMeta(status as TaskStatus)
    return { status, count, percent: Math.round((count / total) * 100), meta }
  })
})

const sessionStatusRows = computed(() => {
  const d = data.value
  if (!d) return []
  const total = d.sessionCount || 1
  return Object.entries(d.sessionStatus).map(([status, count]) => {
    const meta = sessionMeta(status as SessionStatus)
    return { status, count, percent: Math.round((count / total) * 100), meta }
  })
})

async function load() {
  loading.value = true
  try {
    data.value = await getOverview()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="overview">
    <header class="page-head">
      <div>
        <span class="eyebrow">概览</span>
        <h2>平台运行总览</h2>
        <p class="page-sub">数据实时取自后端，最近刷新 {{ formatDateTime(new Date()) }}</p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </header>

    <div class="stat-grid">
      <div v-for="stat in stats" :key="stat.label" class="stat-card">
        <span class="stat-label">{{ stat.label }}</span>
        <strong class="stat-value">{{ stat.value }}</strong>
        <span class="stat-sub">{{ stat.sub }}</span>
      </div>
    </div>

    <div class="split">
      <section class="card">
        <div class="card-head">
          <h3>任务状态分布</h3>
          <span class="mono-sm muted">合计 {{ data?.taskCount ?? 0 }}</span>
        </div>
        <div v-if="taskStatusRows.length" class="dist">
          <div v-for="row in taskStatusRows" :key="row.status" class="dist-row">
            <StatusPill :tone="row.meta.tone">{{ row.meta.label }}</StatusPill>
            <div class="bar">
              <span class="bar-fill" :style="{ width: `${row.percent}%` }" />
            </div>
            <span class="dist-count mono-sm">{{ row.count }}</span>
          </div>
        </div>
        <el-empty v-else :image-size="60" description="暂无任务" />
      </section>

      <section class="card">
        <div class="card-head">
          <h3>会话状态分布</h3>
          <span class="mono-sm muted">合计 {{ data?.sessionCount ?? 0 }}</span>
        </div>
        <div v-if="sessionStatusRows.length" class="dist">
          <div v-for="row in sessionStatusRows" :key="row.status" class="dist-row">
            <StatusPill :tone="row.meta.tone">{{ row.meta.label }}</StatusPill>
            <div class="bar">
              <span class="bar-fill alt" :style="{ width: `${row.percent}%` }" />
            </div>
            <span class="dist-count mono-sm">{{ row.count }}</span>
          </div>
        </div>
        <el-empty v-else :image-size="60" description="暂无会话" />
      </section>
    </div>

    <section class="card">
      <div class="card-head">
        <h3>答案库批次</h3>
        <span class="mono-sm muted">{{ data?.batches.length ?? 0 }} 个批次</span>
      </div>
      <el-table :data="data?.batches ?? []" stripe empty-text="暂无批次">
        <el-table-column type="index" label="#" width="60" />
        <el-table-column prop="batch" label="批次" min-width="140" />
        <el-table-column prop="papers" label="试卷数" width="120" />
        <el-table-column prop="questions" label="题目数" width="120" />
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.page-head h2 {
  font-size: 21px;
  margin: 4px 0 6px;
}
.page-sub {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-400);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 18px 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
}
.stat-label {
  font-size: 12.5px;
  color: var(--ink-400);
}
.stat-value {
  font-family: var(--font-display);
  font-size: 30px;
  line-height: 1.1;
  color: var(--ink-900);
}
.stat-sub {
  font-size: 12px;
  color: var(--ink-500);
}

.split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.card {
  padding: 18px 20px 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.card-head h3 {
  font-size: 15px;
}
.muted {
  color: var(--ink-400);
  font-size: 12px;
}

.dist {
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.dist-row {
  display: grid;
  grid-template-columns: 96px 1fr 40px;
  align-items: center;
  gap: 12px;
}
.bar {
  height: 8px;
  border-radius: 99px;
  background: var(--surface-3);
  overflow: hidden;
}
.bar-fill {
  display: block;
  height: 100%;
  border-radius: 99px;
  background: var(--accent);
  transition: width 0.5s ease;
}
.bar-fill.alt {
  background: var(--amber);
}
.dist-count {
  text-align: right;
  color: var(--ink-600);
  font-size: 12.5px;
}

@media (max-width: 1280px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .split {
    grid-template-columns: 1fr;
  }
}
</style>
