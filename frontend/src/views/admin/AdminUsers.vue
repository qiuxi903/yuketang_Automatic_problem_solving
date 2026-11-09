<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import {
  getUserLoginLogs,
  getUserOrders,
  getUserTasks,
  listUsers,
  resetUserPassword,
  setUserDisabled,
  updateUserRole,
} from '@/api/admin'
import { exportOrdersCsv, type VipOrder } from '@/api/vip'
import { formatDateTime } from '@/utils/format'
import { useIsMobile } from '@/utils/useMedia'
import StatusPill from '@/components/ui/StatusPill.vue'
import { taskMeta, MODE_LABEL } from '@/utils/status'
import type { AdminTaskView, AdminUserView, UserRole } from '@/types'

/** 用户管理：账号状态、改密/封禁、详情（做题记录/充值记录/登录记录）。 */

const isMobile = useIsMobile()
const loading = ref(false)
const savingId = ref<number | null>(null)
const rows = ref<AdminUserView[]>([])
const keyword = ref('')
const roleFilter = ref<'ALL' | UserRole>('ALL')
const statusFilter = ref<'ALL' | 'OK' | 'DISABLED'>('ALL')

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter((u) => {
    if (roleFilter.value !== 'ALL' && u.role !== roleFilter.value) return false
    if (statusFilter.value !== 'ALL') {
      const banned = u.disabled || statusFilter.value === 'DISABLED'
      if ((statusFilter.value === 'DISABLED') !== u.disabled) return false
      void banned
    }
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

/* —— 重置密码 —— */
async function resetPassword(row: AdminUserView) {
  let password = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `为「${row.username}」设置新密码（至少 8 位、含字母和数字）。重置后该用户所有登录态立即失效。`,
      '重置密码',
      { confirmButtonText: '重置', cancelButtonText: '取消', inputPlaceholder: '新密码', inputType: 'password' },
    )
    password = (value || '').trim()
  } catch {
    return
  }
  if (password.length < 8) {
    ElMessage.warning('密码至少 8 位')
    return
  }
  savingId.value = row.id
  try {
    await resetUserPassword(row.id, password)
    ElMessage.success(`已重置 ${row.username} 的密码，请把新密码告知用户`)
  } catch {
    /* 拦截器已提示 */
  } finally {
    savingId.value = null
  }
}

/* —— 封禁 / 解封 —— */
async function toggleDisabled(row: AdminUserView) {
  const next = !row.disabled
  try {
    await ElMessageBox.confirm(
      next
        ? `封禁「${row.username}」？封禁后该账号无法登录，且所有登录态立即失效。`
        : `解封「${row.username}」？解封后可正常登录。`,
      next ? '封禁账号' : '解封账号',
      { confirmButtonText: next ? '封禁' : '解封', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  savingId.value = row.id
  try {
    await setUserDisabled(row.id, next)
    row.disabled = next
    ElMessage.success(next ? '已封禁' : '已解封')
  } catch {
    /* 拦截器已提示 */
  } finally {
    savingId.value = null
  }
}

/* —— 用户详情（做题 / 充值 / 登录记录） —— */
const detailVisible = ref(false)
const detailUser = ref<AdminUserView | null>(null)
const detailTab = ref<'tasks' | 'orders' | 'logs'>('tasks')
const detailLoading = ref(false)
const detailTasks = ref<AdminTaskView[]>([])
const detailOrders = ref<VipOrder[]>([])
const detailLogs = ref<import('@/api/admin').LoginLogView[]>([])

const detailPaid = computed(() =>
  detailOrders.value
    .filter((o) => o.status === 'PAID')
    .reduce((sum, o) => sum + o.payableFen, 0),
)

async function openDetail(row: AdminUserView) {
  detailUser.value = row
  detailVisible.value = true
  detailTab.value = 'tasks'
  detailLoading.value = true
  try {
    const [tasks, orders, logs] = await Promise.all([
      getUserTasks(row.id),
      getUserOrders(row.id),
      getUserLoginLogs(row.id),
    ])
    detailTasks.value = tasks
    detailOrders.value = orders
    detailLogs.value = logs
  } finally {
    detailLoading.value = false
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
          共 {{ rows.length }} 个账号，管理员 {{ adminCount }} 人
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
        <el-radio-group v-model="statusFilter" size="default">
          <el-radio-button value="ALL">全部状态</el-radio-button>
          <el-radio-button value="OK">正常</el-radio-button>
          <el-radio-button value="DISABLED">已封禁</el-radio-button>
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
            <StatusPill :tone="row.disabled ? 'danger' : row.role === 'ADMIN' ? 'accent' : 'neutral'">
              {{ row.disabled ? '已封禁' : row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </StatusPill>
          </div>
          <div class="uc-meta">
            <span>ID {{ row.id }}</span>
            <span>任务 {{ row.taskCount }}</span>
            <span>充值 ¥{{ ((row.paidTotalFen || 0) / 100).toFixed(2) }}</span>
            <span>{{ formatDateTime(row.createdAt) }}</span>
          </div>
          <div class="uc-meta">
            <template v-if="row.lastLoginAt">
              <span>{{ row.lastLoginIp }}</span>
              <span>{{ row.lastLoginLocation }}</span>
              <span>{{ formatDateTime(row.lastLoginAt) }}</span>
            </template>
            <span v-else class="muted">从未登录</span>
          </div>
          <div class="uc-actions">
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <el-radio-group
              :model-value="row.role"
              size="small"
              :disabled="savingId === row.id"
              @change="(val: UserRole) => changeRole(row, val)"
            >
              <el-radio-button value="USER">普通</el-radio-button>
              <el-radio-button value="ADMIN">管理员</el-radio-button>
            </el-radio-group>
            <el-button size="small" @click="resetPassword(row)">改密</el-button>
            <el-button
              size="small"
              :type="row.disabled ? 'success' : 'danger'"
              plain
              :loading="savingId === row.id"
              @click="toggleDisabled(row)"
            >
              {{ row.disabled ? '解封' : '封禁' }}
            </el-button>
          </div>
        </div>
      </div>

      <el-table v-else v-loading="loading" :data="filtered" stripe empty-text="暂无账号">
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.disabled" type="danger" effect="light" size="small">已封禁</el-tag>
            <el-tag v-else type="success" effect="light" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="96">
          <template #default="{ row }">
            <StatusPill :tone="row.role === 'ADMIN' ? 'accent' : 'neutral'">
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </StatusPill>
          </template>
        </el-table-column>
        <el-table-column label="充值" width="120">
          <template #default="{ row }">
            <span class="money">¥{{ ((row.paidTotalFen || 0) / 100).toFixed(2) }}</span>
            <span v-if="row.paidOrders" class="muted">（{{ row.paidOrders }} 单）</span>
          </template>
        </el-table-column>
        <el-table-column label="任务" width="110">
          <template #default="{ row }">
            <span class="mono-sm">{{ row.taskCount }}</span>
            <span v-if="row.runningCount" class="running">· 进行中 {{ row.runningCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="上次登录" min-width="220">
          <template #default="{ row }">
            <template v-if="row.lastLoginAt">
              <div class="mono-sm">{{ row.lastLoginIp }} · {{ row.lastLoginLocation }}</div>
              <div class="muted">{{ formatDateTime(row.lastLoginAt) }}</div>
            </template>
            <span v-else class="muted">从未登录</span>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="150">
          <template #default="{ row }">
            <span class="mono-sm muted">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="270" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <el-button size="small" @click="resetPassword(row)">改密</el-button>
            <el-button
              size="small"
              :type="row.disabled ? 'success' : 'danger'"
              plain
              :loading="savingId === row.id"
              @click="toggleDisabled(row)"
            >
              {{ row.disabled ? '解封' : '封禁' }}
            </el-button>
            <el-radio-group
              :model-value="row.role"
              size="small"
              style="margin-top: 4px"
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

    <!-- 用户详情：做题 / 充值 / 登录记录 -->
    <el-dialog v-model="detailVisible" width="860px" align-center top="6vh">
      <template #header>
        <span class="d-title">
          用户「{{ detailUser?.username }}」· ID {{ detailUser?.id }}
        </span>
      </template>
      <div v-loading="detailLoading">
        <div class="d-summary">
          <span>充值合计 <b class="money">¥{{ (detailPaid / 100).toFixed(2) }}</b></span>
          <span>做题 <b>{{ detailTasks.length }}</b> 次</span>
          <span>上次登录 <b>{{ detailUser?.lastLoginIp || '—' }}（{{ detailUser?.lastLoginLocation || '未知' }}）</b></span>
        </div>
        <el-tabs v-model="detailTab">
          <el-tab-pane label="做题记录" name="tasks">
            <el-table :data="detailTasks" size="small" max-height="380">
              <el-table-column prop="taskId" label="ID" width="70" />
              <el-table-column prop="batch" label="批次" width="90" />
              <el-table-column label="模式" width="80">
                <template #default="{ row }">{{ MODE_LABEL[row.mode] || row.mode }}</template>
              </el-table-column>
              <el-table-column label="状态" width="92">
                <template #default="{ row }">
                  <el-tag :type="taskMeta(row.status).tone === 'ok' ? 'success' : taskMeta(row.status).tone === 'warn' ? 'warning' : taskMeta(row.status).tone === 'danger' ? 'danger' : 'info'" effect="light" size="small">
                    {{ taskMeta(row.status).label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="进度" width="100">
                <template #default="{ row }">{{ row.donePaper }}/{{ row.totalPaper }}</template>
              </el-table-column>
              <el-table-column label="创建时间" min-width="150">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="充值 / 订单记录" name="orders">
            <el-table :data="detailOrders" size="small" max-height="380">
              <el-table-column prop="orderNo" label="订单号" min-width="150" show-overflow-tooltip />
              <el-table-column label="商品" width="130">
                <template #default="{ row }">
                  {{ row.kind === 'DATE_PASS' ? `单日 · ${row.targetBatch}` : '月卡' }}
                </template>
              </el-table-column>
              <el-table-column label="实付" width="100">
                <template #default="{ row }">
                  <span class="money">¥{{ (row.payableFen / 100).toFixed(2) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="92">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'PAID' ? 'success' : row.status === 'CREATED' ? 'warning' : 'info'" effect="light" size="small">
                    {{ row.status === 'PAID' ? '已支付' : row.status === 'CREATED' ? '待支付' : row.status === 'EXPIRED' ? '已过期' : row.status === 'REJECTED' ? '已拒单' : '已取消' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="时间" min-width="150">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
            <el-button
              v-if="detailOrders.length"
              size="small"
              class="d-export"
              @click="exportOrdersCsv(); ElMessage.success('全部订单流水已下载')"
            >导出全部订单 CSV</el-button>
          </el-tab-pane>
          <el-tab-pane label="登录记录" name="logs">
            <el-table :data="detailLogs" size="small" max-height="380">
              <el-table-column label="时间" width="150">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column prop="ip" label="IP" width="130" />
              <el-table-column prop="location" label="归属地" min-width="150" />
              <el-table-column label="结果" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.success ? 'success' : 'danger'" effect="light" size="small">
                    {{ row.success ? '成功' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="message" label="备注" min-width="120" show-overflow-tooltip />
              <el-table-column prop="userAgent" label="设备" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>
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
  font-size: 11.5px;
}
.running {
  margin-left: 6px;
  font-size: 11.5px;
  color: var(--accent-700);
}
.money {
  font-family: var(--font-mono, monospace);
  font-weight: 700;
  color: var(--amber);
}

.d-title {
  font-size: 15px;
  font-weight: 700;
}
.d-summary {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding: 10px 14px;
  background: var(--surface-2);
  border-radius: var(--r-md);
  font-size: 13px;
  color: var(--ink-600);
}
.d-summary b {
  color: var(--ink-900);
}
.d-export {
  margin-top: 10px;
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
.uc-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
}

@media (max-width: 768px) {
  .users {
    gap: 12px;
  }
}
</style>
