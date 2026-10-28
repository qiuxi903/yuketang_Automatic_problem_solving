<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { getAdminTask, listAllTasks } from '@/api/admin'
import { formatDateTime } from '@/utils/format'
import { useIsMobile } from '@/utils/useMedia'
import { MODE_LABEL, taskMeta } from '@/utils/status'
import StatusPill from '@/components/ui/StatusPill.vue'
import type { AdminTaskView, TaskSnapshot, TaskStatus } from '@/types'

const isMobile = useIsMobile()
const loading = ref(false)
const rows = ref<AdminTaskView[]>([])
const keyword = ref('')
const statusFilter = ref<'ALL' | TaskStatus>('ALL')

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<TaskSnapshot | null>(null)

const statusOptions: Array<'ALL' | TaskStatus> = [
  'ALL',
  'PENDING',
  'RUNNING',
  'PAUSED',
  'SUCCESS',
  'FAILED',
]

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter((t) => {
    if (statusFilter.value !== 'ALL' && t.status !== statusFilter.value) return false
    if (!kw) return true
    return (
      t.batch.toLowerCase().includes(kw) ||
      String(t.taskId) === kw ||
      (t.username ?? '').toLowerCase().includes(kw)
    )
  })
})

async function load() {
  loading.value = true
  try {
    rows.value = await listAllTasks()
  } finally {
    loading.value = false
  }
}

async function openDetail(row: AdminTaskView) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getAdminTask(row.taskId)
  } catch {
    ElMessage.error('任务详情加载失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

function progressText(row: AdminTaskView) {
  if (row.status === 'PENDING') return '—'
  return `${row.donePaper}/${row.totalPaper}`
}

onMounted(load)
</script>

<template>
  <div class="tasks">
    <header class="page-head">
      <div>
        <span class="eyebrow">任务管理</span>
        <h2>全部作答任务</h2>
        <p class="page-sub">共 {{ rows.length }} 条任务，点击行可查看实时答题明细</p>
      </div>
      <div class="head-tools">
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="搜索批次 / 用户 / 任务号"
          clearable
          style="width: 220px"
        />
        <el-select v-model="statusFilter" style="width: 130px">
          <el-option v-for="s in statusOptions" :key="s" :label="s === 'ALL' ? '全部状态' : taskMeta(s as TaskStatus).label" :value="s" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="card">
      <!-- 手机：卡片列表（点卡片看详情） -->
      <div v-if="isMobile" class="task-cards" v-loading="loading">
        <el-empty v-if="!filtered.length" description="暂无任务" :image-size="70" />
        <div v-for="row in filtered" :key="row.taskId" class="task-card" @click="openDetail(row)">
          <div class="tc-top">
            <span class="tc-id mono-sm">#{{ row.taskId }}</span>
            <StatusPill :tone="taskMeta(row.status).tone">{{ taskMeta(row.status).label }}</StatusPill>
          </div>
          <div class="tc-batch">{{ row.batch || '批次待确认' }}</div>
          <div class="tc-meta">
            <span>{{ row.username ?? `用户 ${row.userId}` }}</span>
            <span>{{ MODE_LABEL[row.mode] ?? row.mode }}</span>
            <span>进度 {{ progressText(row) }}</span>
            <span v-if="row.score">得分 {{ row.score }}</span>
          </div>
          <div class="tc-foot">{{ formatDateTime(row.createdAt) }}</div>
        </div>
      </div>

      <el-table
        v-else
        v-loading="loading"
        :data="filtered"
        stripe
        empty-text="暂无任务"
        @row-click="openDetail"
      >
        <el-table-column prop="taskId" label="任务号" width="92">
          <template #default="{ row }">
            <span class="mono-sm">#{{ row.taskId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="用户" width="140">
          <template #default="{ row }">{{ row.username ?? `用户 ${row.userId}` }}</template>
        </el-table-column>
        <el-table-column prop="batch" label="批次" min-width="150" show-overflow-tooltip />
        <el-table-column label="模式" width="110">
          <template #default="{ row }">
            <StatusPill tone="neutral" :dot="false">{{ MODE_LABEL[row.mode] ?? row.mode }}</StatusPill>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusPill :tone="taskMeta(row.status).tone">{{ taskMeta(row.status).label }}</StatusPill>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="100">
          <template #default="{ row }">
            <span class="mono-sm">{{ progressText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="得分" width="90">
          <template #default="{ row }">
            <span class="mono-sm">{{ row.score || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">
            <span class="mono-sm muted">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="detailVisible" title="任务详情" width="720px">
      <div v-loading="detailLoading" class="detail">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <span class="mono">T{{ detail.taskId }} · S{{ detail.sessionId }}</span>
              <h3>{{ detail.currentPaper ?? '等待开始' }}</h3>
            </div>
            <StatusPill :tone="taskMeta(detail.status).tone">{{ taskMeta(detail.status).label }}</StatusPill>
          </div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="批次">{{ detail.batch }}</el-descriptions-item>
            <el-descriptions-item label="模式">{{ MODE_LABEL[detail.mode] ?? detail.mode }}</el-descriptions-item>
            <el-descriptions-item label="进展">{{ detail.donePaper }}/{{ detail.totalPaper }}</el-descriptions-item>
            <el-descriptions-item label="当前题号">
              {{ detail.currentSeq }}/{{ detail.currentTotal }}
            </el-descriptions-item>
            <el-descriptions-item label="得分">{{ detail.score || '—' }}</el-descriptions-item>
            <el-descriptions-item label="消息">{{ detail.message || '—' }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="detail.items" size="small" max-height="320" empty-text="暂无明细">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="paper" label="试卷" min-width="170" show-overflow-tooltip />
            <el-table-column prop="examId" label="考试ID" width="110" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                {{ row.status }}
              </template>
            </el-table-column>
            <el-table-column prop="score" label="得分" width="90" />
          </el-table>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.tasks {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
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
.head-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.card {
  padding: 18px 20px 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
}
.muted {
  color: var(--ink-400);
}

/* —— 手机卡片列表 —— */
.task-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.task-card {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
  cursor: pointer;
}
.tc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.tc-id {
  font-weight: 700;
  color: var(--ink-800);
}
.tc-batch {
  margin-top: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-900);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tc-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink-500);
}
.tc-foot {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--ink-400);
}

.detail {
  min-height: 120px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.detail-head h3 {
  font-size: 16px;
  margin-top: 3px;
}
</style>
