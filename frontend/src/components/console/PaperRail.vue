<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TaskMode } from '@/types'
import { useConsoleStore } from '@/store/console'
import { MODE_LABEL } from '@/utils/status'
import StatusPill from '@/components/ui/StatusPill.vue'

/** 左栏试卷清单：按答案库日期分组（可收起/展开），支持勾选卷子单独作答。 */
const props = defineProps<{ selectedPaper: string | null }>()

const emit = defineEmits<{ select: [paper: string] }>()

const ANSWER_LABEL: Record<string, string> = { answer: '做这些卷子', collect: '只取这些卷子的答案' }

const store = useConsoleStore()

const total = computed(() => store.resultRows.length)
const doneCount = computed(() => store.doneRows.length)

interface DateGroup {
  date: string
  rows: typeof store.resultRows
  done: number
}

const expandedDates = ref<string[]>([])

const dateGroups = computed<DateGroup[]>(() => {
  const map = new Map<string, typeof store.resultRows>()
  for (const row of store.resultRows) {
    const date = store.paperDateOf(row.name)
    const list = map.get(date) ?? []
    list.push(row)
    map.set(date, list)
  }
  const NEW = '新卷子（未入答案库）'
  return [...map.entries()]
    // 日期新的在上；未入答案库的新卷子固定最后
    .sort((a, b) => {
      if (a[0] === NEW) return 1
      if (b[0] === NEW) return -1
      return a[0] < b[0] ? 1 : -1
    })
    .map(([date, rows]) => ({ date, rows, done: rows.filter((r) => r.done).length }))
})

// 日期组变化时默认全展开（保持「默认能看到全部卷子」）
watch(
  dateGroups,
  (groups) => {
    expandedDates.value = groups.map((g) => g.date)
  },
  { immediate: true },
)

function toggleDate(date: string) {
  const i = expandedDates.value.indexOf(date)
  if (i >= 0) expandedDates.value.splice(i, 1)
  else expandedDates.value.push(date)
}

/** 分组全选：勾选/取消该日期组下的全部卷子。 */
function toggleGroup(date: string) {
  const group = dateGroups.value.find((g) => g.date === date)
  if (!group) return
  const names = group.rows.map((r) => r.name)
  const all = names.every((n) => store.selectedPapers.includes(n))
  store.selectedPapers = all
    ? store.selectedPapers.filter((n) => !names.includes(n))
    : [...new Set([...store.selectedPapers, ...names])]
}

function groupAllChecked(date: string) {
  const group = dateGroups.value.find((g) => g.date === date)
  if (!group || !group.rows.length) return false
  return group.rows.every((r) => store.selectedPapers.includes(r.name))
}

const allChecked = computed(
  () => total.value > 0 && store.selectedPapers.length === total.value,
)

function toggleAll() {
  store.selectedPapers = allChecked.value ? [] : store.resultRows.map((r) => r.name)
}

/** 勾选即开干：点击直接为勾选的卷子发起任务（自动确认推荐批次、沿用登录、免二级菜单）。 */
async function startChecked(mode: TaskMode) {
  if (!store.selectedPapers.length) {
    ElMessage.warning('先勾选要处理的卷子')
    return
  }
  const n = store.selectedPapers.length
  const verb = mode === 'collect' ? '只取答案' : '做题'
  try {
    await ElMessageBox.confirm(
      `对勾选的 ${n} 套卷子执行「${verb}」？` + (mode === 'collect'
        ? '已交卷的直接读答案入库，未交卷的会跳过。'
        : '会消耗这些卷子的作答次数。'),
      mode === 'collect' ? '获取答案' : '开始做题',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }
  await store.continueTask([...store.selectedPapers], mode as TaskMode)
  ElMessage.success(`已发起：勾选的 ${n} 套 · ${verb}（登录有效时自动开始）`)
}
</script>

<template>
  <aside class="rail">
    <div class="rail-head">
      <div class="rail-title">
        <span class="eyebrow">答题进度</span>
        <h3>试卷清单</h3>
      </div>
      <span class="counter mono-sm">
        {{ doneCount }}<i>/</i>{{ total }}
      </span>
    </div>

    <div class="rail-meta">
      <template v-if="store.task?.batch">
        <StatusPill tone="accent" :dot="false">
          {{ MODE_LABEL[store.task?.mode ?? ''] ?? store.task?.mode }}
        </StatusPill>
        <span class="batch mono-sm">答案库：{{ store.task.batch }}</span>
      </template>
      <span v-else class="batch muted">登录后确认答案库</span>
    </div>

    <div v-if="total" class="pick-bar">
      <label class="pick-all" @click.prevent="toggleAll">
        <span class="box" :class="{ on: allChecked }">{{ allChecked ? '✓' : '' }}</span>
        全选
      </label>
      <span class="pick-count">{{ store.selectedPapers.length ? `已选 ${store.selectedPapers.length} 套` : '勾选卷子后点右侧按钮' }}</span>
      <button class="pick-go" type="button" :disabled="!store.selectedPapers.length" @click="startChecked('answer')">
        做题
      </button>
      <button class="pick-go pick-ans" type="button" :disabled="!store.selectedPapers.length" @click="startChecked('collect')">
        取答案
      </button>
    </div>

    <div class="rail-body scroll-area">
      <el-empty
        v-if="total === 0"
        :image-size="66"
        description="扫码登录后，账号里的全部试卷会显示在这里"
      />

      <template v-else>
        <section v-for="group in dateGroups" :key="group.date" class="date-group">
          <header class="date-head" @click="toggleDate(group.date)">
            <span class="arrow" :class="{ open: expandedDates.includes(group.date) }">▸</span>
            <span class="date-text">{{ group.date }}</span>
            <span class="mono-sm sub">{{ group.done }}/{{ group.rows.length }} 完成</span>
            <span
              class="group-check"
              :class="{ on: groupAllChecked(group.date) }"
              title="全选该组卷子"
              @click.stop="toggleGroup(group.date)"
            >{{ groupAllChecked(group.date) ? '✓' : '' }}</span>
          </header>
          <ul v-show="expandedDates.includes(group.date)" class="paper-list">
            <li
              v-for="row in group.rows"
              :key="row.name"
              class="paper"
              :class="{
                'is-done': row.done,
                'is-current': row.name === store.task?.currentPaper,
                'is-selected': row.name === props.selectedPaper,
              }"
              @click="emit('select', row.name)"
            >
              <span
                class="check"
                :class="{ on: store.selectedPapers.includes(row.name) }"
                title="勾选后到中间栏点「作答勾选的 N 卷」单独作答"
                @click.stop="store.toggleSelected(row.name)"
              >{{ store.selectedPapers.includes(row.name) ? '✓' : '' }}</span>
              <span class="idx mono-sm">{{ `${row.seq}`.padStart(2, '0') }}</span>
              <span class="paper-main">
                <span class="paper-name">{{ row.name }}</span>
                <em class="chip" :class="`s-${row.tone}`" :title="row.hint">{{ row.label }}</em>
              </span>
              <span v-if="row.score" class="score">{{ row.score }}</span>
            </li>
          </ul>
        </section>
      </template>
    </div>

    <div v-if="store.task?.currentPaper" class="rail-foot">
      <span class="pulse" />
      <span class="foot-text">正在作答</span>
      <span class="foot-paper">{{ store.task.currentPaper }}</span>
    </div>
  </aside>
</template>

<style scoped>
.rail {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--surface);
  border-right: 1px solid var(--border);
}

.rail-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding: 18px 18px 10px;
}
.rail-title h3 {
  font-size: 16px;
  margin-top: 3px;
}
.counter {
  color: var(--ink-400);
  font-size: 13px;
}
.counter i {
  font-style: normal;
  margin: 0 2px;
  color: var(--ink-300);
}

.rail-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 18px 12px;
  border-bottom: 1px solid var(--border);
}
.batch {
  color: var(--ink-400);
  font-size: 12px;
}
.muted {
  color: var(--ink-300);
  font-size: 12px;
}

/* —— 勾选条 —— */
.pick-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
}
.pick-all {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-600);
  cursor: pointer;
  user-select: none;
}
.box {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border: 1.5px solid var(--ink-300);
  border-radius: 4px;
  font-size: 11px;
  line-height: 1;
  color: #fff;
  transition: background-color 0.12s ease, border-color 0.12s ease;
}
.box.on {
  background: var(--accent);
  border-color: var(--accent);
}
.pick-count {
  flex: 1;
  font-size: 11.5px;
  color: var(--ink-400);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pick-go {
  flex: none;
  height: 24px;
  display: inline-flex;
  align-items: center;
  padding: 0 12px;
  border: 1px solid var(--accent-200);
  border-radius: var(--r-xs);
  background: var(--accent-050);
  color: var(--accent-700);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.12s ease, border-color 0.12s ease;
}
.pick-go:hover:not(:disabled) {
  background: var(--accent-100);
  border-color: var(--accent-400);
}
.pick-go:disabled {
  border-color: var(--ink-200);
  background: var(--ink-100);
  color: var(--ink-300);
  cursor: not-allowed;
}

.rail-body {
  flex: 1;
  padding: 10px 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
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
  background: var(--surface); /* 与栏同色，吸顶滚动时内容从线下穿过 */
  cursor: pointer;
  user-select: none;
  position: sticky;
  top: 0;
  z-index: 1;
}
.date-head:hover .date-text {
  color: var(--accent);
}
.group-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-left: 4px;
  border: 1.5px solid var(--ink-300);
  border-radius: 4px;
  color: #fff;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition: background-color 0.12s ease, border-color 0.12s ease;
}
.group-check:hover,
.group-check.on {
  background: var(--accent);
  border-color: var(--accent);
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
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--ink-500);
}
.date-head .sub {
  margin-left: auto;
  color: var(--ink-400);
  font-size: 11px;
}

.paper-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.paper {
  display: grid;
  grid-template-columns: 18px 22px 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  border: 1px solid transparent;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}
.paper:hover {
  background: var(--surface-2);
}
.paper.is-current {
  background: var(--accent-050);
  border-color: var(--accent-200);
}
.paper.is-selected {
  border-color: var(--accent);
  background: var(--accent-100);
}

.check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border: 1.5px solid var(--ink-300);
  border-radius: 4px;
  font-size: 11px;
  line-height: 1;
  color: #fff;
  transition: background-color 0.12s ease, border-color 0.12s ease;
}
.check:hover {
  border-color: var(--accent);
}
.check.on {
  background: var(--accent);
  border-color: var(--accent);
}

.idx {
  color: var(--ink-300);
  font-size: 12px;
  text-align: center;
}
.paper.is-current .idx,
.paper.is-selected .idx {
  color: var(--accent-700);
}

.paper-main {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  min-width: 0;
}
.paper-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}

.chip {
  font-style: normal;
  font-size: 11px;
  line-height: 1.6;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--ink-100);
  color: var(--ink-500);
}
.chip.s-ok {
  background: var(--ok-100);
  color: var(--ok);
}
.chip.s-accent {
  background: var(--accent-100);
  color: var(--accent-700);
}
.chip.s-warn {
  background: var(--warn-100);
  color: var(--warn);
}
.chip.s-danger {
  background: var(--danger-100);
  color: var(--danger);
}

.score {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 600;
  color: var(--ok);
}

.rail-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
  background: var(--surface-2);
  flex: none;
}
.pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 0 var(--accent-200);
  animation: pulse 1.8s infinite;
  flex: none;
}
@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 var(--accent-200);
  }
  70% {
    box-shadow: 0 0 0 9px transparent;
  }
  100% {
    box-shadow: 0 0 0 0 transparent;
  }
}
.foot-text {
  font-size: 12px;
  color: var(--ink-500);
  flex: none;
}
.foot-paper {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

@media (max-width: 768px) {
  .rail-head {
    padding: 16px 16px 10px;
  }
  .pick-bar {
    padding: 10px 14px;
  }
  .pick-go {
    height: 30px;
    padding: 0 14px;
    font-size: 13px;
  }
  .date-head {
    padding: 10px 8px;
  }
  .date-text {
    font-size: 13px;
  }
  .paper {
    padding: 12px 10px;
    grid-template-columns: 22px 22px 1fr auto;
  }
  .check,
  .box {
    width: 20px;
    height: 20px;
  }
  .grid-cols-mobile {
    grid-template-columns: 22px 22px 1fr auto;
  }
  .paper-name {
    font-size: 14px;
  }
  .chip {
    font-size: 11.5px;
  }
  .rail-body {
    padding: 10px 12px;
  }
}
