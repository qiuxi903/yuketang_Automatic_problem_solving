<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Medal, Download } from '@element-plus/icons-vue'
import { listContributions, searchAnswers } from '@/api/community'
import { exportAnswersBundle, getAnswersBundle, listPapers } from '@/api/paper'
import StatusPill from '@/components/ui/StatusPill.vue'
import type {
  AnswerPaperEntry,
  AnswersBundle,
  BatchMeta,
  ContributionItem,
  SearchHitItem,
} from '@/types'
import { formatDateTime } from '@/utils/format'

/**
 * 答案中心：平台全部答案的唯一入口。
 * - 全部试卷（默认）：列出答案库所有卷子，点开直接看答案、一键导出
 * - 搜答案：按题干/答案关键词搜全库
 * - 获取记录：任务获取到的答案自动记录（新采集 / 复用都算）
 */
const tab = ref<'papers' | 'search' | 'feed'>('papers')

/* —— 全部试卷：按日期分组，可展开收起 —— */
const batches = ref<BatchMeta[]>([])
const manifestLoading = ref(false)
const paperFilter = ref('')          // 按卷名过滤
const expandedDates = ref<string[]>([])  // 展开中的日期组

interface PaperRow {
  batch: string
  name: string
  questions: number
}

interface DateGroup {
  date: string
  papers: PaperRow[]
  questions: number
}

const dateGroups = computed<DateGroup[]>(() => {
  const map = new Map<string, PaperRow[]>()
  for (const b of batches.value) {
    const key = b.date || b.batch // 无日期的批次用批次号兜底
    const list = map.get(key) ?? []
    for (const p of b.papers) {
      list.push({ batch: b.batch, name: p.name, questions: p.questions })
    }
    map.set(key, list)
  }
  const groups = [...map.entries()].map(([date, papers]) => ({
    date,
    papers,
    questions: papers.reduce((n, p) => n + p.questions, 0),
  }))
  // 日期新的在上
  return groups.sort((a, b) => (a.date < b.date ? 1 : -1))
})

const visibleGroups = computed(() => {
  const kw = paperFilter.value.trim().toLowerCase()
  if (!kw) return dateGroups.value
  return dateGroups.value
    .map((g) => ({
      ...g,
      papers: g.papers.filter(
        (p) => p.name.toLowerCase().includes(kw) || p.batch.toLowerCase().includes(kw),
      ),
    }))
    .filter((g) => g.papers.length > 0)
})

const summary = computed(() => {
  const papers = dateGroups.value.reduce((n, g) => n + g.papers.length, 0)
  const questions = dateGroups.value.reduce((n, g) => n + g.questions, 0)
  return { papers, questions, dates: dateGroups.value.length }
})

function toggleDate(date: string) {
  const i = expandedDates.value.indexOf(date)
  if (i >= 0) expandedDates.value.splice(i, 1)
  else expandedDates.value.push(date)
}

const activePaper = ref<PaperRow | null>(null)
const answers = ref<AnswerPaperEntry | null>(null)
const answersLoading = ref(false)
const exporting = ref(false)

async function loadManifest(force = false) {
  if (batches.value.length && !force) return
  manifestLoading.value = true
  try {
    const result = await listPapers()
    batches.value = result.batches
    // 默认展开全部日期组（保持「默认显示所有卷子」的口径）
    expandedDates.value = dateGroups.value.map((g) => g.date)
  } catch {
    batches.value = []
  } finally {
    manifestLoading.value = false
  }
}

async function openPaper(row: PaperRow) {
  activePaper.value = row
  answers.value = null
  answersLoading.value = true
  try {
    const bundle: AnswersBundle = await getAnswersBundle({ batch: row.batch, paper: row.name })
    answers.value = bundle.papers[0] ?? null
    if (!bundle.papersCount) ElMessage.warning('该卷暂无答案库记录')
  } catch {
    ElMessage.error('读取答案失败')
  } finally {
    answersLoading.value = false
  }
}

function safeName(value: string) {
  return (value || 'all').replace(/[\\/:*?"<>|\s]+/g, '_')
}

async function download(params: { batch?: string; paper?: string; date?: string }, label: string) {
  exporting.value = true
  try {
    const blob = await exportAnswersBundle(params)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `answers-${safeName(params.paper ?? params.date ?? params.batch ?? 'all')}.md`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${label}`)
  } finally {
    exporting.value = false
  }
}

function exportCurrent() {
  if (!activePaper.value) return
  void download({ batch: activePaper.value.batch, paper: activePaper.value.name }, activePaper.value.name)
}

function exportDate(group: DateGroup) {
  void download({ date: group.date }, `${group.date} 全部试卷`)
}

/* —— 搜答案 —— */
const keyword = ref('')
const searching = ref(false)
const hits = ref<SearchHitItem[] | null>(null)

async function doSearch() {
  const q = keyword.value.trim()
  if (!q) {
    ElMessage.warning('请输入要搜索的题目关键词')
    return
  }
  searching.value = true
  try {
    hits.value = await searchAnswers(q)
  } finally {
    searching.value = false
  }
}

/* —— 获取记录 —— */
const contributions = ref<ContributionItem[]>([])
const feedLoading = ref(false)

async function loadFeed() {
  feedLoading.value = true
  try {
    contributions.value = await listContributions(20)
  } finally {
    feedLoading.value = false
  }
}

onMounted(() => {
  void loadManifest()
  void loadFeed()
})

watch(tab, (t) => {
  if (t === 'feed' && !contributions.value.length) void loadFeed()
})
</script>

<template>
  <div class="center">
    <div class="hero">
      <div class="hero-text">
        <h2>答案中心</h2>
        <p>
          平台全部答案的唯一入口：做题获取到的答案<b>自动</b>进入这里（新采集与复用都有记录）；
          任何人再做同一套卷子会先在这里匹配，<b>匹配到直接用，不消耗作答次数</b>。
        </p>
      </div>
    </div>

    <el-tabs v-model="tab" class="tabs">
      <!-- 全部试卷（默认，按日期分组可展开收起） -->
      <el-tab-pane label="全部试卷" name="papers">
        <div class="papers-pane" v-loading="manifestLoading">
          <div class="papers-toolbar">
            <el-input
              v-model="paperFilter"
              class="paper-filter"
              clearable
              placeholder="按卷名过滤"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <span class="sub summary">
              {{ summary.dates }} 个日期 · {{ summary.papers }} 份 · {{ summary.questions }} 题
            </span>
            <el-button :icon="Refresh" text :loading="manifestLoading" @click="loadManifest(true)">
              刷新
            </el-button>
          </div>

          <div class="papers-body">
            <aside class="paper-list scroll-area">
              <section v-for="group in visibleGroups" :key="group.date" class="date-group">
                <header class="date-head" @click="toggleDate(group.date)">
                  <span class="arrow" :class="{ open: expandedDates.includes(group.date) }">▸</span>
                  <span class="date-text">{{ group.date }}</span>
                  <span class="sub mono-sm">{{ group.papers.length }} 份 · {{ group.questions }} 题</span>
                  <button
                    class="date-export"
                    type="button"
                    title="导出该日期全部答案"
                    :disabled="exporting"
                    @click.stop="exportDate(group)"
                  >
                    <el-icon><Download /></el-icon>
                  </button>
                </header>
                <button
                  v-for="p in group.papers"
                  v-show="expandedDates.includes(group.date)"
                  :key="`${p.batch}-${p.name}`"
                  type="button"
                  class="paper-row"
                  :class="{ 'is-active': activePaper?.name === p.name && activePaper?.batch === p.batch }"
                  @click="openPaper(p)"
                >
                  <span class="p-name">{{ p.name }}</span>
                  <span class="p-q mono-sm">{{ p.questions }} 题</span>
                </button>
              </section>
              <p v-if="!visibleGroups.length && !manifestLoading" class="empty-tip">
                没有匹配的试卷。
              </p>
            </aside>

            <section class="answer-pane scroll-area">
              <template v-if="activePaper">
                <div class="answer-head">
                  <div class="answer-title">
                    <h4>{{ activePaper.name }}</h4>
                    <span class="sub">{{ activePaper.batch }} · {{ activePaper.questions }} 题</span>
                  </div>
                  <el-button
                    type="primary"
                    size="small"
                    :icon="Download"
                    :loading="exporting"
                    @click="exportCurrent"
                  >
                    导出 Markdown
                  </el-button>
                </div>
                <el-skeleton v-if="answersLoading" :rows="6" animated />
                <ol v-else-if="answers" class="answer-list">
                  <li v-for="item in answers.answers" :key="item.seq">
                    <div class="answer-stem">
                      <span class="seq">{{ item.seq }}</span>
                      <span>{{ item.stem }}</span>
                    </div>
                    <div class="answer-value">{{ item.answer || '（空）' }}</div>
                  </li>
                </ol>
                <el-empty
                  v-else-if="!answersLoading"
                  description="该卷暂无答案记录"
                  :image-size="66"
                />
              </template>
              <div v-else class="pane-empty">
                <h4>从左侧选择一套试卷</h4>
                <p>点击即看答案，右上角可导出 Markdown。</p>
              </div>
            </section>
          </div>
        </div>
      </el-tab-pane>

      <!-- 搜答案 -->
      <el-tab-pane label="搜答案" name="search">
        <div class="pane">
          <div class="search-bar">
            <el-input
              v-model="keyword"
              size="large"
              clearable
              placeholder="输入题干关键词或答案文本，搜索整个答案库"
              @keyup.enter="doSearch"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-button type="primary" size="large" :icon="Search" :loading="searching" @click="doSearch">
              搜索
            </el-button>
          </div>

          <template v-if="hits !== null">
            <div class="result-head">
              找到 <b>{{ hits.length }}</b> 条匹配
              <span v-if="hits.length >= 100" class="sub">（已达上限，可换更具体的关键词）</span>
            </div>
            <ul v-if="hits.length" class="hit-list">
              <li v-for="(hit, i) in hits" :key="i" class="hit">
                <div class="hit-stem"><span class="seq">{{ hit.seq }}</span><span>{{ hit.stem }}</span></div>
                <div class="hit-ans">{{ hit.answer }}</div>
                <div class="hit-from">{{ hit.paper }}<template v-if="hit.batch"> · {{ hit.batch }}</template></div>
              </li>
            </ul>
            <el-empty v-else description="没有搜到相关题目；等相应卷子被做过一次后，答案会自动进入答案中心" :image-size="72" />
          </template>
          <p v-else class="tip">答案库共 {{ summary.questions || '…' }} 道题可搜。</p>
        </div>
      </el-tab-pane>

      <!-- 获取记录 -->
      <el-tab-pane label="获取记录" name="feed">
        <div class="pane">
          <div class="feed-head">
            <span class="sub">任务里获取到的答案自动记录在这里；「新采集」= 从结果页抓到的最新答案，「复用」= 直接取用了库里已有的答案</span>
            <el-button :icon="Refresh" text :loading="feedLoading" @click="loadFeed">刷新</el-button>
          </div>
          <el-empty
            v-if="!contributions.length && !feedLoading"
            description="还没有记录；完成一个任务后，获取到的答案会自动出现在这里"
            :image-size="72"
          />
          <ul v-else class="feed">
            <li v-for="(item, i) in contributions" :key="i" class="feed-row">
              <span class="medal"><el-icon><Medal /></el-icon></span>
              <span class="who">{{ item.username }}</span>
              <StatusPill :tone="item.source === 'reuse' ? 'info' : 'ok'" :dot="false">
                {{ item.source === 'reuse' ? '复用' : '新采集' }}
              </StatusPill>
              <span class="verb">获取了</span>
              <span class="paper">{{ item.paper }}</span>
              <span class="mono-sm sub">{{ item.questions }} 题</span>
              <span class="when">{{ formatDateTime(item.createdAt) }}</span>
            </li>
          </ul>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.center {
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 24px 32px 40px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: 100%;
}

.hero {
  padding: 20px 24px;
  border-radius: var(--r-lg);
  background: linear-gradient(135deg, var(--accent-100), var(--surface));
  border: 1px solid var(--border);
}
.hero h2 {
  margin: 0 0 8px;
  font-size: 20px;
  color: var(--ink-900);
}
.hero p {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--ink-600);
  max-width: 760px;
}
.hero b {
  color: var(--accent-700);
}

.tabs {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  padding: 6px 18px 16px;
  min-height: 560px;
}

.pane {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 6px;
}

/* —— 全部试卷 —— */
.papers-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-top: 6px;
  flex-wrap: wrap;
}
.paper-filter {
  width: 260px;
}
.summary {
  margin-left: auto;
}
.papers-body {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 14px;
  margin-top: 12px;
  height: calc(100vh - 380px);
  min-height: 380px;
}
.paper-list {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.date-group {
  display: flex;
  flex-direction: column;
}
.date-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 6px 5px;
  margin: 6px 0 3px;
  border-bottom: 1px solid var(--border);
  border-radius: 0;
  background: var(--surface-2); /* 与列表面板同色，吸顶不透内容 */
  cursor: pointer;
  user-select: none;
  position: sticky;
  top: 0;
  z-index: 1;
}
.date-head:hover .date-text {
  color: var(--accent);
}
.arrow {
  flex: none;
  font-size: 11px;
  color: var(--ink-500);
  transition: transform 0.15s ease;
  display: inline-block;
}
.arrow.open {
  transform: rotate(90deg);
}
.date-text {
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--ink-500);
}
.date-head .sub {
  margin-left: auto;
}
.date-export {
  flex: none;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-400);
  cursor: pointer;
  font-size: 13px;
}
.date-export:hover {
  background: var(--accent-100);
  color: var(--accent-700);
}
.paper-row {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 7px 10px 7px 26px;
  border: 1px solid transparent;
  border-radius: var(--r-xs);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}
.paper-row:hover {
  background: var(--surface);
}
.paper-row.is-active {
  background: var(--accent-100);
  border-color: var(--accent);
}
.p-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.p-q {
  font-size: 11px;
  color: var(--ink-500);
}
.empty-tip {
  margin: 20px 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--ink-400);
}

.answer-pane {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
  padding: 14px 16px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.answer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 10px;
  margin-bottom: 10px;
  border-bottom: 1px dashed var(--border);
}
.answer-title h4 {
  margin: 0;
  font-size: 15px;
  color: var(--ink-900);
}
.answer-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.answer-list li {
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
}
.answer-stem {
  display: flex;
  gap: 8px;
  font-size: 12.5px;
  color: var(--ink-700);
}
.answer-value {
  margin: 5px 0 0 28px;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent-700);
}
.pane-empty {
  margin: auto;
  text-align: center;
  color: var(--ink-400);
}
.pane-empty h4 {
  font-size: 14px;
  color: var(--ink-600);
  margin-bottom: 6px;
}
.pane-empty p {
  font-size: 12.5px;
  margin: 0;
}

/* —— 搜答案 —— */
.search-bar {
  display: flex;
  gap: 10px;
}
.search-bar .el-input {
  flex: 1;
}
.tip {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-400);
}
.sub {
  font-size: 12px;
  color: var(--ink-400);
}
.result-head {
  font-size: 13px;
  color: var(--ink-600);
}
.result-head b {
  color: var(--accent-700);
}
.hit-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.hit {
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
}
.hit-stem {
  display: flex;
  gap: 10px;
  font-size: 13.5px;
  color: var(--ink-800);
  line-height: 1.6;
}
.seq {
  flex: none;
  min-width: 24px;
  height: 22px;
  padding: 0 4px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  background: var(--surface-3);
  font-size: 11.5px;
  color: var(--ink-600);
}
.hit-ans {
  margin: 6px 0 4px 34px;
  display: inline-block;
  padding: 2px 12px;
  border-radius: 99px;
  background: var(--ok-100);
  color: var(--ok);
  font-size: 13px;
  font-weight: 600;
}
.hit-from {
  margin-left: 34px;
  font-size: 12px;
  color: var(--ink-400);
}

/* —— 获取记录 —— */
.feed-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.feed {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}
.feed-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 6px;
  border-bottom: 1px dashed var(--border);
  font-size: 13.5px;
}
.feed-row:last-child {
  border-bottom: none;
}
.medal {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--accent-100);
  color: var(--accent-700);
  font-size: 14px;
}
.who {
  font-weight: 700;
  color: var(--ink-800);
}
.verb {
  color: var(--ink-400);
}
.paper {
  color: var(--ink-700);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
}
.when {
  margin-left: auto;
  flex: none;
  font-size: 12px;
  color: var(--ink-300);
}

@media (max-width: 768px) {
  .center {
    padding: 14px 12px 28px;
  }
  .tabs {
    padding: 4px 10px 12px;
  }
  .papers-body {
    grid-template-columns: 1fr;
    height: auto;
    min-height: 0;
  }
  .paper-list {
    max-height: 34vh;
  }
  /* 答案面板不内滚：整页顺滑滚动，触屏更跟手 */
  .answer-pane {
    max-height: none;
    min-height: 200px;
    overflow-y: visible;
  }
  .date-export {
    width: 36px;
    height: 36px;
    font-size: 16px;
  }
  .hero p {
    display: none;
  }
  .hero h2 {
    font-size: 18px;
    margin-bottom: 0;
  }
  .hero {
    padding: 14px 16px;
  }
  .search-bar {
    flex-direction: column;
  }
  .search-bar .el-input {
    width: 100%;
  }
  .papers-toolbar {
    gap: 8px;
  }
  .paper-filter {
    width: 100%;
  }
  .summary {
    display: none;
  }
  .feed-row {
    flex-wrap: wrap;
  }
  .paper {
    max-width: 220px;
  }
}
</style>
