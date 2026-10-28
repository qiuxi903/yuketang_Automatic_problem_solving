<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { getPaperAnswers, listPapers } from '@/api/paper'
import { useIsMobile } from '@/utils/useMedia'
import StatusPill from '@/components/ui/StatusPill.vue'
import type { BatchMeta, PaperAnswers, PaperMeta } from '@/types'

const isMobile = useIsMobile()
const loading = ref(false)
const batches = ref<BatchMeta[]>([])
const activeBatch = ref<string | null>(null)
const keyword = ref('')

const answersVisible = ref(false)
const answersLoading = ref(false)
const currentPaper = ref<PaperMeta | null>(null)
const answers = ref<PaperAnswers | null>(null)

const totalPapers = computed(() => batches.value.reduce((sum, b) => sum + b.papersCount, 0))
const totalQuestions = computed(() => batches.value.reduce((sum, b) => sum + b.questions, 0))

const activePapers = computed(() => {
  const batch = batches.value.find((b) => b.batch === activeBatch.value)
  if (!batch) return []
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return batch.papers
  return batch.papers.filter((p) => p.name.toLowerCase().includes(kw))
})

function pickBatch(batch: BatchMeta) {
  activeBatch.value = batch.batch
  keyword.value = ''
}

async function load() {
  loading.value = true
  try {
    const result = await listPapers()
    batches.value = result.batches
    if (!activeBatch.value && result.batches.length) {
      activeBatch.value = result.batches[0].batch
    }
  } finally {
    loading.value = false
  }
}

async function openAnswers(paper: PaperMeta) {
  if (!activeBatch.value) return
  answersVisible.value = true
  answersLoading.value = true
  currentPaper.value = paper
  answers.value = null
  try {
    answers.value = await getPaperAnswers(activeBatch.value, paper.name)
  } catch {
    ElMessage.error('答案加载失败')
  } finally {
    answersLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="library">
    <header class="page-head">
      <div>
        <span class="eyebrow">答案库</span>
        <h2>批次与试卷</h2>
        <p class="page-sub">
          {{ batches.length }} 个批次 · {{ totalPapers }} 份试卷 · {{ totalQuestions }} 道题目
        </p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </header>

    <div class="library-body">
      <!-- 手机：批次横向胶囊条 -->
      <div v-if="isMobile" class="batch-chips">
        <button
          v-for="batch in batches"
          :key="batch.batch"
          class="batch-chip"
          :class="{ 'is-active': batch.batch === activeBatch }"
          @click="pickBatch(batch)"
        >
          {{ batch.batch }}
          <em>{{ batch.papersCount }} 份</em>
        </button>
      </div>

      <aside v-else class="batch-list card">
        <div class="card-head">
          <h3>批次</h3>
        </div>
        <el-scrollbar max-height="calc(100vh - 280px)">
          <button
            v-for="batch in batches"
            :key="batch.batch"
            class="batch-item"
            :class="{ 'is-active': batch.batch === activeBatch }"
            @click="pickBatch(batch)"
          >
            <span class="batch-name">{{ batch.batch }}</span>
            <span class="batch-meta">
              {{ batch.papersCount }} 份 · {{ batch.questions }} 题
            </span>
            <span class="batch-date mono-sm">{{ batch.date }}</span>
          </button>
        </el-scrollbar>
      </aside>

      <section class="paper-panel card">
        <div class="card-head">
          <h3>{{ activeBatch ?? '选择批次' }}</h3>
          <el-input
            v-model="keyword"
            :prefix-icon="Search"
            placeholder="搜索试卷"
            clearable
            style="width: 190px"
          />
        </div>

        <!-- 手机：试卷卡片列表 -->
        <div v-if="isMobile" class="paper-cards" v-loading="loading">
          <el-empty v-if="!activePapers.length" description="该批次暂无试卷" :image-size="70" />
          <div v-for="(row, idx) in activePapers" :key="row.name" class="paper-card">
            <div class="pc-top">
              <span class="pc-idx mono-sm">{{ idx + 1 }}</span>
              <span class="pc-name">{{ row.name }}</span>
            </div>
            <div class="pc-foot">
              <span class="pc-meta mono-sm">{{ row.questions }} 题 · 满分 {{ row.score || '—' }}</span>
              <el-button size="small" link type="primary" @click="openAnswers(row)">查看答案</el-button>
            </div>
          </div>
        </div>

        <el-table v-else v-loading="loading" :data="activePapers" stripe empty-text="该批次暂无试卷">
          <el-table-column type="index" label="#" width="56" />
          <el-table-column prop="name" label="试卷" min-width="220" show-overflow-tooltip />
          <el-table-column label="题目数" width="100">
            <template #default="{ row }">
              <span class="mono-sm">{{ row.questions }}</span>
            </template>
          </el-table-column>
          <el-table-column label="满分" width="100">
            <template #default="{ row }">
              <span class="mono-sm">{{ row.score || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openAnswers(row)">查看答案</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <el-dialog v-model="answersVisible" title="试卷答案" width="680px">
      <div v-loading="answersLoading" class="answers">
        <template v-if="answers">
          <div class="answers-head">
            <h3>{{ answers.paper }}</h3>
            <StatusPill tone="accent" :dot="false">{{ answers.questions }} 题</StatusPill>
          </div>
          <ol class="answer-list">
            <li v-for="item in answers.answers" :key="item.seq">
              <div class="answer-stem">
                <span class="seq">{{ item.seq }}</span>
                <span>{{ item.stem }}</span>
              </div>
              <div class="answer-value">{{ item.answer || '（空）' }}</div>
            </li>
          </ol>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.library {
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

.library-body {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 16px;
  align-items: start;
}

/* —— 手机：批次胶囊条 + 试卷卡片 —— */
.batch-chips {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}
.batch-chip {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink-600);
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.batch-chip em {
  font-style: normal;
  font-size: 11px;
  color: var(--ink-400);
}
.batch-chip.is-active {
  border-color: var(--accent);
  background: var(--accent-100);
  color: var(--accent-700);
}
.batch-chip.is-active em {
  color: var(--accent-700);
}

.paper-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.paper-card {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
}
.pc-top {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.pc-idx {
  color: var(--ink-400);
  font-size: 12px;
}
.pc-name {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--ink-900);
  word-break: break-word;
}
.pc-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 8px;
}
.pc-meta {
  font-size: 12px;
  color: var(--ink-500);
}

.card {
  padding: 16px 18px 18px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.card-head h3 {
  font-size: 15px;
}

.batch-list {
  display: flex;
  flex-direction: column;
}
.batch-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 9px 11px;
  margin-bottom: 4px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  background: transparent;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}
.batch-item:hover {
  background: var(--surface-2);
}
.batch-item.is-active {
  background: var(--accent-100);
  border-color: var(--accent);
}
.batch-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
}
.batch-meta {
  font-size: 11.5px;
  color: var(--ink-500);
}
.batch-date {
  color: var(--ink-400);
  font-size: 11px;
}

.answers {
  min-height: 120px;
}
.answers-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.answers-head h3 {
  font-size: 16px;
}
.answer-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.answer-list li {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
}
.answer-stem {
  display: flex;
  gap: 8px;
  font-size: 13px;
  color: var(--ink-700);
}
.seq {
  flex: none;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  background: var(--surface-3);
  font-size: 11.5px;
  color: var(--ink-600);
}
.answer-value {
  margin-top: 6px;
  margin-left: 28px;
  font-size: 13.5px;
  color: var(--accent-700);
  font-weight: 600;
}

@media (max-width: 1080px) {
  .library-body {
    grid-template-columns: 1fr;
  }
}
</style>
