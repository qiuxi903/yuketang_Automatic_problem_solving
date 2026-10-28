<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { listUsers, updateUserRole } from '@/api/admin'
import { formatDateTime } from '@/utils/format'
import { useIsMobile } from '@/utils/useMedia'
import StatusPill from '@/components/ui/StatusPill.vue'
import type { AdminUserView, UserRole } from '@/types'

const isMobile = useIsMobile()
const loading = ref(false)
const savingId = ref<number | null>(null)
const rows = ref<AdminUserView[]>([])
const keyword = ref('')
const roleFilter = ref<'ALL' | UserRole>('ALL')

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter((u) => {
    if (roleFilter.value !== 'ALL' && u.role !== roleFilter.value) return false
    if (!kw) return true
    return u.username.toLowerCase().includes(kw) || String(u.id) === kw
  })
})

const adminCount = computed(() => rows.value.filter((u) => u.role === 'ADMIN').length)

async function load() {
  loading.value = true
  try {
    rows.value = await listUsers()
  } finally {
    loading.value = false
  }
}

async function changeRole(row: AdminUserView, role: UserRole) {
  if (role === row.role) return
  savingId.value = row.id
  try {
    const updated = await updateUserRole(row.id, role)
    Object.assign(row, updated)
    ElMessage.success(`已将 ${row.username} 设为${role === 'ADMIN' ? '管理员' : '普通用户'}`)
  } catch {
    /* 错误提示由拦截器统一处理 */
  } finally {
    savingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="users">
    <header class="page-head">
      <div>
        <span class="eyebrow">用户管理</span>
        <h2>账号与角色</h2>
        <p class="page-sub">
          共 {{ rows.length }} 个账号，其中管理员 {{ adminCount }} 人
        </p>
      </div>
      <div class="head-tools">
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="搜索用户名 / ID"
          clearable
          style="width: 200px"
        />
        <el-radio-group v-model="roleFilter" size="default">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="ADMIN">管理员</el-radio-button>
          <el-radio-button value="USER">普通用户</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="card">
      <!-- 手机：卡片列表 -->
      <div v-if="isMobile" class="user-cards" v-loading="loading">
        <el-empty v-if="!filtered.length" description="暂无账号" :image-size="70" />
        <div v-for="row in filtered" :key="row.id" class="user-card">
          <div class="uc-top">
            <span class="uc-name">{{ row.username }}</span>
            <StatusPill :tone="row.role === 'ADMIN' ? 'accent' : 'neutral'">
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </StatusPill>
          </div>
          <div class="uc-meta">
            <span>ID {{ row.id }}</span>
            <span>任务 {{ row.taskCount }}<template v-if="row.runningCount"> · 进行中 {{ row.runningCount }}</template></span>
            <span>{{ formatDateTime(row.createdAt) }}</span>
          </div>
          <div class="uc-foot">
            <el-radio-group
              :model-value="row.role"
              size="small"
              :disabled="savingId === row.id"
              @change="(val: UserRole) => changeRole(row, val)"
            >
              <el-radio-button value="USER">普通</el-radio-button>
              <el-radio-button value="ADMIN">管理员</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </div>

      <el-table v-else v-loading="loading" :data="filtered" stripe empty-text="暂无账号">
        <el-table-column prop="id" label="ID" width="72" />
        <el-table-column prop="username" label="用户名" min-width="180" />
        <el-table-column label="角色" width="130">
          <template #default="{ row }">
            <StatusPill :tone="row.role === 'ADMIN' ? 'accent' : 'neutral'">
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </StatusPill>
          </template>
        </el-table-column>
        <el-table-column label="任务" width="120">
          <template #default="{ row }">
            <span class="mono-sm">{{ row.taskCount }}</span>
            <span v-if="row.runningCount" class="running">· 进行中 {{ row.runningCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" min-width="180">
          <template #default="{ row }">
            <span class="mono-sm muted">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="角色调整" width="200" align="right">
          <template #default="{ row }">
            <el-radio-group
              :model-value="row.role"
              size="small"
              :disabled="savingId === row.id"
              @change="(val: UserRole) => changeRole(row, val)"
            >
              <el-radio-button value="USER">普通</el-radio-button>
              <el-radio-button value="ADMIN">管理员</el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.users {
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
.running {
  margin-left: 6px;
  font-size: 11.5px;
  color: var(--accent-700);
}

/* —— 手机卡片列表 —— */
.user-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.user-card {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
}
.uc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.uc-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-900);
}
.uc-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink-500);
}
.uc-foot {
  margin-top: 10px;
}
</style>
