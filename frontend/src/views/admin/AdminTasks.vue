<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Refresh, Search, VideoPlay } from '@element-plus/icons-vue'
import { getAdminTask, listAllTasks } from '@/api/admin'
import { deleteTask, getTaskFrame } from '@/api/task'
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
/** 正常/异常快筛：正常=排队/做题/暂停/已完成；异常=失败/已取消 */
const healthFilter = ref<'ALL' | 'OK' | 'BAD'>('ALL')

const selected = ref<AdminTaskView[]>([])
const tableRef = ref()

const NORMAL_STATUSES: TaskStatus[] = ['PENDING', 'RUNNING', 'WAITING_BATCH', 'PAUSED', 'SUCCESS']

function isNormal(status: TaskStatus): boolean {
  return NORMAL_STATUSES.includes(status)
}

/** 进行中的任务要先中断才能删（后端同样校验） */
function deletable(status: TaskStatus): boolean {
  return status !== 'PENDING' && status !== 'RUNNING' && status !== 'WAITING_BATCH'
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<TaskSnapshot | null>(null)

/** 浏览器实时画面（仅运行中的任务有帧） */
const frameVisible = ref(false)
const frameTask = ref<AdminTaskView | null>(null)
const frameSrc = ref('')
const frameNote = ref('')
let frameTimer: number | undefined

const statusOptions: Array<'ALL' | TaskStatus> = [
  'ALL',
  'PENDING',
  'RUNNING',
  'WAITING_BATCH',
  'PAUSED',
  'SUCCESS',
  'FAILED',
  'CANCELLED',
]

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter((t) => {
    if (healthFilter.value === 'OK' && !isNormal(t.status)) return false
    if (healthFilter.value === 'BAD' && isNormal(t.status)) return false
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

function onSelectionChange(rows: AdminTaskView[]) {
  selected.value = rows
}

/** 批量删除/单行删除：进行中的任务后端会拒绝并逐条提示 */
async function removeMany(list: AdminTaskView[]) {
  if (!list.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${list.length} 个任务？删除后明细与答题记录一并清除，不可恢复。`, '删除任务', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  let ok = 0
  const busy: string[] = []
  for (const t of list) {
    try {
      await deleteTask(t.taskId)
      ok += 1
    } catch {
      busy.push(`#${t.taskId}`)
    }
  }
  if (ok) ElMessage.success(`已删除 ${ok} 个任务`)
  if (busy.length) ElMessage.warning(`进行中的任务未删除：${busy.join('、')}（请先中断）`)
  selected.value = []
  tableRef.value?.clearSelection?.()
  await load()
}

/** 打开任务实时画面：仅运行中的任务有帧，1.5s 轮询 */
function openFrame(row: AdminTaskView) {
  frameTask.value = row
  frameVisible.value = true
  frameSrc.value = ''
  frameNote.value = ''
  const tick = async () => {
    if (!frameVisible.value || !frameTask.value) return
    try {
      const f = await getTaskFrame(frameTask.value.taskId)
      if (f.image) {
        frameSrc.value = f.image
        frameNote.value = f.url ? (f.url as string).slice(0, 90) : ''
      } else if (!frameSrc.value) {
        frameNote.value = '还没有画面：Agent 尚未打开该任务的页面（排队/刚创建时正常，轮到即出画面）'
      }
    } catch {
      /* 任务删除/无帧时静默，保持上一帧 */
    }
  }
  void tick()
  frameTimer = window.setInterval(tick, 1500)
}

function closeFrame() {
  frameVisible.value = false
  frameTask.value = null
  if (frameTimer) {
    window.clearInterval(frameTimer)
    frameTimer = undefined
  }
}

watch(frameVisible, (v) => {
  if (!v) closeFrame()
})
onBeforeUnmount(() => {
  if (frameTimer) window.clearInterval(frameTimer)
})

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
        <el-radio-group v-model="healthFilter" size="small">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="OK">正常</el-radio-button>
          <el-radio-button value="BAD">异常</el-radio-button>
        </el-radio-group>
        <el-select v-model="statusFilter" style="width: 130px">
          <el-option v-for="s in statusOptions" :key="s" :label="s === 'ALL' ? '全部状态' : taskMeta(s as TaskStatus).label" :value="s" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="card">
      <div class="toolbar">
        <span class="muted">已选 {{ selected.length }} 个</span>
        <el-button
          size="small"
          type="danger"
          plain
          :icon="Delete"
          :disabled="!selected.length"
          @click="removeMany(selected)"
        >
          删除选中
        </el-button>
        <span class="muted tip">进行中的任务需先中断才能删除；正常=排队/做题/暂停/已完成，异常=失败/已取消</span>
      </div>

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
          <div class="tc-foot">
            {{ formatDateTime(row.createdAt) }}
            <span class="tc-actions">
              <el-button
                v-if="row.status === 'RUNNING'"
                size="small"
                text
                type="primary"
                :icon="VideoPlay"
                @click.stop="openFrame(row)"
              >
                画面
              </el-button>
              <el-button
                v-if="deletable(row.status)"
                size="small"
                text
                type="danger"
                :icon="Delete"
                @click.stop="removeMany([row])"
              >
                删除
              </el-button>
            </span>
          </div>
        </div>
      </div>

      <el-table
        v-else
        ref="tableRef"
        v-loading="loading"
        :data="filtered"
        stripe
        empty-text="暂无任务"
        @row-click="openDetail"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="42" />
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
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'RUNNING'"
              size="small"
              text
              type="primary"
              :icon="VideoPlay"
              @click.stop="openFrame(row)"
            >
              画面
            </el-button>
            <el-button
              v-if="deletable(row.status)"
              size="small"
              text
              type="danger"
              :icon="Delete"
              @click.stop="removeMany([row])"
            >
              删除
            </el-button>
            <span v-if="row.status === 'RUNNING' && !deletable(row.status)" class="muted">—</span>
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

    <el-dialog
      :model-value="frameVisible"
      :title="`浏览器画面 · 任务 #${frameTask?.taskId ?? ''}（${frameTask?.username ?? ''}）`"
      width="720px"
      @close="closeFrame"
    >
      <div class="frame-box">
        <img v-if="frameSrc" :src="frameSrc" class="frame-img" alt="任务实时画面" />
        <el-empty v-else description="等待画面…" :image-size="70" />
      </div>
      <p v-if="frameNote" class="frame-note mono-sm">{{ frameNote }}</p>
      <p class="frame-note muted">每 1.5 秒自动刷新；仅运行中的任务有画面，暂停/排队中的任务无实时帧。</p>
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

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.toolbar .tip {
  font-size: 11.5px;
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.tc-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.frame-box {
  min-height: 200px;
  display: grid;
  place-items: center;
  background: var(--ink-950);
  border-radius: var(--r-md);
  overflow: hidden;
}
.frame-img {
  width: 100%;
  display: block;
}
.frame-note {
  margin: 8px 0 0;
  word-break: break-all;
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
