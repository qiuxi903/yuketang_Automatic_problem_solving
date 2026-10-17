<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, Refresh } from '@element-plus/icons-vue'
import {
  deleteInvite,
  deleteUsedInvites,
  disableInvite,
  generateInvites,
  listInvites,
} from '@/api/admin'
import type { InviteItem } from '@/types'
import { formatDateTime } from '@/utils/format'

/** 邀请码管理：批量生成、复制、禁用、删除、一键清理已使用；支持状态筛选。 */

const invites = ref<InviteItem[]>([])
const loading = ref(false)
const genCount = ref(5)
const generating = ref(false)
const filter = ref<'all' | 'unused' | 'used' | 'disabled'>('all')

const filtered = computed(() =>
  invites.value.filter((i) => {
    if (filter.value === 'unused') return !i.used && !i.disabled
    if (filter.value === 'used') return i.used
    if (filter.value === 'disabled') return i.disabled
    return true
  }),
)

const stats = computed(() => ({
  unused: invites.value.filter((i) => !i.used && !i.disabled).length,
  used: invites.value.filter((i) => i.used).length,
  disabled: invites.value.filter((i) => i.disabled).length,
}))

async function load() {
  loading.value = true
  try {
    invites.value = await listInvites()
  } finally {
    loading.value = false
  }
}

async function generate() {
  generating.value = true
  try {
    const res = await generateInvites(genCount.value)
    ElMessage.success(`已生成 ${res.codes.length} 个邀请码`)
    await load()
  } finally {
    generating.value = false
  }
}

async function copyAll() {
  const unused = invites.value.filter((i) => !i.used && !i.disabled).map((i) => i.code)
  if (!unused.length) {
    ElMessage.warning('没有可复制的未用邀请码')
    return
  }
  await navigator.clipboard.writeText(unused.join('\n'))
  ElMessage.success(`已复制 ${unused.length} 个未用邀请码`)
}

async function copyOne(code: string) {
  await navigator.clipboard.writeText(code)
  ElMessage.success(`已复制 ${code}`)
}

async function disable(code: string) {
  await disableInvite(code)
  ElMessage.success(`已禁用 ${code}`)
  await load()
}

async function removeOne(code: string) {
  try {
    await ElMessageBox.confirm(`确定删除 ${code}？删除后无法恢复。`, '删除邀请码', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await deleteInvite(code)
  ElMessage.success(`已删除 ${code}`)
  await load()
}

async function clearUsed() {
  if (!stats.value.used) {
    ElMessage.warning('没有已使用的邀请码')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除全部 ${stats.value.used} 个已使用的邀请码？注册记录不受影响。`,
      '清理已使用',
      { type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const res = await deleteUsedInvites()
  ElMessage.success(`已清理 ${res.deleted} 个已使用邀请码`)
  await load()
}

onMounted(load)
</script>

<template>
  <div class="invites">
    <div class="head">
      <div>
        <h2>注册邀请码</h2>
        <p>新用户注册时必须携带有效邀请码；一码一用，用完即失效。</p>
      </div>
      <div class="head-actions">
        <el-input-number v-model="genCount" :min="1" :max="50" />
        <el-button type="primary" :loading="generating" @click="generate">生成</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button :icon="CopyDocument" @click="copyAll">复制未用</el-button>
        <el-button type="danger" plain :icon="Delete" @click="clearUsed">
          清理已使用（{{ stats.used }}）
        </el-button>
      </div>
    </div>

    <div class="toolbar">
      <span class="stats">
        未使用 <b class="ok">{{ stats.unused }}</b>
        <el-divider direction="vertical" />
        已使用 <b>{{ stats.used }}</b>
        <el-divider direction="vertical" />
        已禁用 <b class="bad">{{ stats.disabled }}</b>
        <el-divider direction="vertical" />
        共 {{ invites.length }} 个
      </span>
      <el-radio-group v-model="filter" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="unused">未使用</el-radio-button>
        <el-radio-button value="used">已使用</el-radio-button>
        <el-radio-button value="disabled">已禁用</el-radio-button>
      </el-radio-group>
    </div>

    <el-table :data="filtered" v-loading="loading" size="large">
      <el-table-column prop="code" label="邀请码" min-width="180" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.disabled" type="danger">已禁用</el-tag>
          <el-tag v-else-if="row.used" type="info">已使用</el-tag>
          <el-tag v-else type="success">未使用</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="usedBy" label="使用者" min-width="120" />
      <el-table-column label="使用时间" min-width="160">
        <template #default="{ row }">
          {{ row.usedAt ? formatDateTime(row.usedAt) : '—' }}
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="160">
        <template #default="{ row }">
          {{ formatDateTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="!row.used && !row.disabled"
            size="small"
            :icon="CopyDocument"
            @click="copyOne(row.code)"
          >
            复制
          </el-button>
          <el-button
            v-if="!row.used && !row.disabled"
            size="small"
            type="warning"
            plain
            @click="disable(row.code)"
          >
            禁用
          </el-button>
          <el-button size="small" type="danger" plain :icon="Delete" @click="removeOne(row.code)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
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
  color: var(--el-text-color-secondary);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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
</style>
