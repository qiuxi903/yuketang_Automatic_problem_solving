<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, Link, Refresh } from '@element-plus/icons-vue'
import {
  batchInvites,
  deleteInvite,
  deleteUsedInvites,
  disableInvite,
  enableInvite,
  generateInvites,
  listInvites,
  type InviteItem,
} from '@/api/admin'
import { formatDateTime } from '@/utils/format'
import { useIsMobile } from '@/utils/useMedia'

/** 邀请码管理：批量生成（可一码多人）、多选批量操作、复制码/邀请链接、启停、删除、清理。 */

const isMobile = useIsMobile()

const invites = ref<InviteItem[]>([])
const loading = ref(false)
const filter = ref<'all' | 'available' | 'exhausted' | 'disabled'>('all')
/** 多选中的邀请码（桌面表格与手机卡片共用）。 */
const selected = ref<string[]>([])

/* —— 生成对话框 —— */
const genVisible = ref(false)
const genCount = ref(5)
const genMaxUses = ref(1)
const genUnlimited = ref(false)
const generating = ref(false)

type Status = 'available' | 'exhausted' | 'disabled'
function statusOf(i: InviteItem): Status {
  if (i.disabled) return 'disabled'
  return i.used ? 'exhausted' : 'available'
}

const filtered = computed(() =>
  invites.value.filter((i) => {
    if (filter.value === 'available') return statusOf(i) === 'available'
    if (filter.value === 'exhausted') return statusOf(i) === 'exhausted'
    if (filter.value === 'disabled') return statusOf(i) === 'disabled'
    return true
  }),
)

const stats = computed(() => ({
  available: invites.value.filter((i) => statusOf(i) === 'available').length,
  exhausted: invites.value.filter((i) => statusOf(i) === 'exhausted').length,
  disabled: invites.value.filter((i) => statusOf(i) === 'disabled').length,
}))

const allChecked = computed(
  () => filtered.value.length > 0 && filtered.value.every((i) => selected.value.includes(i.code)),
)

/** 邀请链接：新用户打开即自动填入邀请码注册。 */
function linkOf(code: string) {
  return `${window.location.origin}/login?invite=${encodeURIComponent(code)}`
}

function usageText(i: InviteItem) {
  return i.maxUses === 0 ? `${i.usedCount}/不限` : `${i.usedCount}/${i.maxUses}`
}
function usagePercent(i: InviteItem) {
  if (i.maxUses === 0) return i.usedCount > 0 ? 100 : 0
  return Math.min(100, Math.round((i.usedCount / i.maxUses) * 100))
}
/** 使用者展示：优先使用历史，旧数据回退到最近使用者。 */
function userText(i: InviteItem) {
  if (i.uses.length) {
    return i.uses[0].username + (i.uses.length > 1 ? ` +${i.uses.length - 1}` : '')
  }
  return i.usedBy ?? ''
}
function userTime(i: InviteItem) {
  return i.uses.length ? i.uses[0].usedAt : i.usedAt
}

/* —— 选择 —— */
function toggleOne(code: string) {
  const i = selected.value.indexOf(code)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(code)
}
function toggleAll() {
  selected.value = allChecked.value ? [] : filtered.value.map((i) => i.code)
}
function onTableSelect(rows: InviteItem[]) {
  selected.value = rows.map((r) => r.code)
}

async function load() {
  loading.value = true
  try {
    invites.value = await listInvites()
    // 清掉已不存在的选择
    const codes = new Set(invites.value.map((i) => i.code))
    selected.value = selected.value.filter((c) => codes.has(c))
  } finally {
    loading.value = false
  }
}

async function generate() {
  generating.value = true
  try {
    const maxUses = genUnlimited.value ? 0 : Math.max(1, genMaxUses.value)
    const res = await generateInvites(genCount.value, maxUses)
    ElMessage.success(`已生成 ${res.codes.length} 个邀请码（${maxUses === 0 ? '不限次数' : `每个可用 ${maxUses} 次`}）`)
    genVisible.value = false
    await load()
  } finally {
    generating.value = false
  }
}

async function copyText(text: string, tip: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(tip)
  } catch {
    ElMessage.warning('当前环境不支持自动复制，请手动复制')
  }
}

async function copyOne(code: string) {
  await copyText(code, `已复制 ${code}`)
}
async function copyLinkOne(code: string) {
  await copyText(linkOf(code), '已复制邀请链接，发给朋友即可注册')
}
async function copyAllLinks() {
  const usable = invites.value.filter((i) => statusOf(i) === 'available')
  if (!usable.length) {
    ElMessage.warning('没有可用的邀请码链接')
    return
  }
  await copyText(usable.map((i) => linkOf(i.code)).join('\n'), `已复制 ${usable.length} 条邀请链接`)
}
/** 批量复制所选邀请码的链接。 */
async function copySelectedLinks() {
  if (!selected.value.length) return
  await copyText(selected.value.map(linkOf).join('\n'), `已复制所选 ${selected.value.length} 条邀请链接`)
}

/* —— 单个操作 —— */
async function disable(code: string) {
  await disableInvite(code)
  ElMessage.success(`已禁用 ${code}`)
  await load()
}
async function enable(code: string) {
  await enableInvite(code)
  ElMessage.success(`已启用 ${code}`)
  await load()
}
async function removeOne(code: string) {
  try {
    await ElMessageBox.confirm(`确定删除 ${code}？删除后无法恢复。`, '删除邀请码', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await deleteInvite(code)
  ElMessage.success(`已删除 ${code}`)
  await load()
}

/* —— 批量操作 —— */
async function batch(action: 'disable' | 'enable' | 'delete') {
  const codes = [...selected.value]
  if (!codes.length) return
  const label = { disable: '禁用', enable: '启用', delete: '删除' }[action]
  if (action === 'delete') {
    try {
      await ElMessageBox.confirm(
        `确定删除所选的 ${codes.length} 个邀请码？删除后无法恢复，已注册用户不受影响。`,
        '批量删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  const res = await batchInvites(action, codes)
  ElMessage.success(`已${label} ${res.affected} 个邀请码`)
  selected.value = []
  await load()
}

async function clearUsed() {
  if (!stats.value.exhausted) {
    ElMessage.warning('没有已用完的邀请码')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除全部 ${stats.value.exhausted} 个已用完的邀请码？已注册的用户不受影响。`,
      '清理已用完', { type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const res = await deleteUsedInvites()
  ElMessage.success(`已清理 ${res.deleted} 个已用完邀请码`)
  await load()
}

onMounted(load)
</script>

<template>
  <div class="invites">
    <div class="head">
      <div>
        <h2>注册邀请码</h2>
        <p>
          一个邀请码可允许多人注册（生成时设定次数，可选不限）；复制「邀请链接」发给新用户，
          打开注册页会自动填好邀请码。
        </p>
      </div>
      <div class="head-actions">
        <el-button type="primary" @click="genVisible = true">生成邀请码</el-button>
        <el-button :icon="Link" @click="copyAllLinks">复制可用链接</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="danger" plain :icon="Delete" @click="clearUsed">
          清理已用完（{{ stats.exhausted }}）
        </el-button>
      </div>
    </div>

    <div class="toolbar">
      <span class="stats">
        可用 <b class="ok">{{ stats.available }}</b>
        <el-divider direction="vertical" />
        已用完 <b>{{ stats.exhausted }}</b>
        <el-divider direction="vertical" />
        已禁用 <b class="bad">{{ stats.disabled }}</b>
        <el-divider direction="vertical" />
        共 {{ invites.length }} 个
      </span>
      <el-radio-group v-model="filter" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="available">可用</el-radio-button>
        <el-radio-button value="exhausted">已用完</el-radio-button>
        <el-radio-button value="disabled">已禁用</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 多选操作条：选中任意邀请码后出现 -->
    <div v-if="selected.length" class="batch-bar">
      <span class="batch-count">已选 <b>{{ selected.length }}</b> 个</span>
      <div class="batch-actions">
        <el-button size="small" :icon="Link" @click="copySelectedLinks">复制链接</el-button>
        <el-button size="small" type="warning" plain @click="batch('disable')">禁用</el-button>
        <el-button size="small" type="success" plain @click="batch('enable')">启用</el-button>
        <el-button size="small" type="danger" plain :icon="Delete" @click="batch('delete')">删除</el-button>
        <el-button size="small" text @click="selected = []">取消选择</el-button>
      </div>
    </div>

    <!-- 桌面：表格多选 -->
    <el-table
      v-if="!isMobile"
      :data="filtered"
      v-loading="loading"
      size="large"
      row-key="code"
      @selection-change="onTableSelect"
    >
      <el-table-column type="selection" width="46" />
      <el-table-column label="邀请码" min-width="180">
        <template #default="{ row }">
          <span class="code mono-sm" @click="copyOne(row.code)">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag v-if="row.disabled" type="danger">已禁用</el-tag>
          <el-tag v-else-if="row.used" type="info">已用完</el-tag>
          <el-tag v-else type="success">可用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="用量" width="140">
        <template #default="{ row }">
          <div class="usage">
            <span class="usage-text mono-sm">{{ usageText(row) }}</span>
            <el-progress
              v-if="row.maxUses > 0"
              :percentage="usagePercent(row)"
              :show-text="false"
              :stroke-width="6"
              :status="row.used ? 'success' : undefined"
            />
          </div>
        </template>
      </el-table-column>
      <el-table-column label="使用者" min-width="140">
        <template #default="{ row }">
          <template v-if="userText(row)">
            <el-tooltip placement="top" :disabled="row.uses.length <= 1">
              <template #content>
                <div v-for="u in row.uses" :key="u.username + u.usedAt" class="use-line">
                  {{ u.username }} · {{ formatDateTime(u.usedAt) }}
                </div>
              </template>
              <span class="who">{{ userText(row) }}</span>
            </el-tooltip>
            <div v-if="userTime(row)" class="when">{{ formatDateTime(userTime(row)) }}</div>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="150">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="statusOf(row) !== 'disabled'"
            size="small" type="warning" plain @click="disable(row.code)"
          >禁用</el-button>
          <el-button v-else size="small" type="success" plain @click="enable(row.code)">启用</el-button>
          <el-button
            v-if="statusOf(row) === 'available'"
            size="small" :icon="Link" @click="copyLinkOne(row.code)"
          >复制链接</el-button>
          <el-button size="small" type="danger" plain :icon="Delete" @click="removeOne(row.code)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 手机：卡片列表（勾选即多选，操作按钮直接可见） -->
    <div v-else class="cards" v-loading="loading">
      <div class="cards-toolbar">
        <el-checkbox :model-value="allChecked" @change="toggleAll">
          全选（{{ filtered.length }}）
        </el-checkbox>
      </div>
      <el-empty v-if="!filtered.length" description="没有符合条件的邀请码" :image-size="70" />
      <div
        v-for="row in filtered"
        :key="row.code"
        class="invite-card"
        :class="{ 'is-picked': selected.includes(row.code) }"
        @click="toggleOne(row.code)"
      >
        <div class="card-top">
          <el-checkbox
            :model-value="selected.includes(row.code)"
            @click.stop
            @change="toggleOne(row.code)"
          />
          <span class="code mono-sm">{{ row.code }}</span>
          <el-tag v-if="row.disabled" type="danger" size="small">已禁用</el-tag>
          <el-tag v-else-if="row.used" type="info" size="small">已用完</el-tag>
          <el-tag v-else type="success" size="small">可用</el-tag>
        </div>
        <div class="card-meta">
          <span>用量 <b>{{ usageText(row) }}</b></span>
          <span v-if="userText(row)">使用者 {{ userText(row) }}</span>
          <span v-else class="muted">未使用</span>
        </div>
        <div class="card-foot">
          <span class="when">{{ formatDateTime(row.createdAt) }}</span>
          <div class="card-actions" @click.stop>
            <el-button
              v-if="statusOf(row) === 'available'"
              size="small" :icon="Link" @click="copyLinkOne(row.code)"
            >链接</el-button>
            <el-button
              v-if="statusOf(row) !== 'disabled'"
              size="small" type="warning" plain @click="disable(row.code)"
            >禁用</el-button>
            <el-button v-else size="small" type="success" plain @click="enable(row.code)">启用</el-button>
            <el-button size="small" type="danger" plain :icon="Delete" @click="removeOne(row.code)">删除</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 生成对话框：数量 + 每码可用次数 -->
    <el-dialog v-model="genVisible" title="生成邀请码" width="420px" align-center>
      <el-form label-position="top" class="gen-form">
        <el-form-item label="生成数量">
          <el-input-number v-model="genCount" :min="1" :max="50" />
        </el-form-item>
        <el-form-item label="每个码允许注册的人数">
          <div class="gen-uses">
            <el-input-number v-model="genMaxUses" :min="1" :max="9999" :disabled="genUnlimited" />
            <el-checkbox v-model="genUnlimited">不限人数</el-checkbox>
          </div>
          <p class="gen-hint">
            设为 3 即一个码可以邀请 3 位好友注册；不限人数的码可一直用，除非禁用或删除。
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="genVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="generate">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.invites {
  padding: 20px 24px;
}
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.head h2 {
  margin: 0 0 6px;
}
.head p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
  max-width: 560px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.stats {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.stats b {
  color: var(--el-text-color-primary);
  font-size: 15px;
}
.stats b.ok {
  color: var(--el-color-success);
}
.stats b.bad {
  color: var(--el-color-danger);
}

/* —— 多选操作条 —— */
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid var(--accent-200);
  border-radius: var(--r-md);
  background: var(--accent-050);
}
.batch-count {
  font-size: 13px;
  color: var(--ink-600);
}
.batch-count b {
  color: var(--accent-700);
  font-size: 15px;
}
.batch-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.code {
  font-weight: 700;
  color: var(--ink-800);
  cursor: copy;
}
.code:hover {
  color: var(--accent);
}
.usage {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.usage-text {
  color: var(--ink-700);
}
.who {
  font-weight: 600;
  color: var(--ink-800);
}
.when {
  font-size: 11.5px;
  color: var(--ink-400);
  margin-top: 1px;
}
.muted {
  color: var(--ink-300);
}
.use-line {
  line-height: 1.8;
}

/* —— 手机卡片列表 —— */
.cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.cards-toolbar {
  padding: 2px 2px 4px;
}
.invite-card {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface);
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}
.invite-card.is-picked {
  border-color: var(--accent);
  background: var(--accent-050);
}
.card-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-top .code {
  flex: 1;
  font-size: 14px;
}
.card-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 8px;
  font-size: 12.5px;
  color: var(--ink-600);
}
.card-meta b {
  color: var(--ink-800);
}
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.card-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.card-actions .el-button + .el-button {
  margin-left: 0;
}

.gen-uses {
  display: flex;
  align-items: center;
  gap: 12px;
}
.gen-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

@media (max-width: 768px) {
  .invites {
    padding: 14px 12px 28px;
  }
  .head {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .head p {
    display: none;
  }
  .head-actions {
    flex-wrap: wrap;
  }
  .head-actions .el-button {
    margin: 0;
  }
  .toolbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  .batch-bar {
    position: sticky;
    top: 0;
    z-index: 2;
  }
}
</style>
