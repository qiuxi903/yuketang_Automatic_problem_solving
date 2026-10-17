<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  CaretBottom,
  Collection,
  DataLine,
  Document,
  Monitor,
  Moon,
  Refresh,
  Setting,
  Sunny,
  SwitchButton,
  UserFilled,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/auth'
import { useConsoleStore } from '@/store/console'
import { toggleTheme, useTheme } from '@/utils/theme'
import StatusPill from '@/components/ui/StatusPill.vue'
import type { TaskStatus } from '@/types'
import { MODE_LABEL, taskMeta } from '@/utils/status'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const consoleStore = useConsoleStore()
const theme = useTheme()

// 我的任务：跟着账号走，随时可查看该账号的全部任务并切换
const tasksOpen = ref(false)

async function openTasks() {
  tasksOpen.value = true
  await consoleStore.loadMyTasks()
}

async function pickTask(id: number) {
  tasksOpen.value = false
  if (route.name !== 'console') await router.push({ name: 'console' })
  await consoleStore.switchTask(id)
}

/** 仅未结束的任务可中断；已完成 / 失败 / 已中断均不可再中断。 */
const INTERRUPTIBLE: TaskStatus[] = ['PENDING', 'RUNNING', 'WAITING_BATCH', 'PAUSED']
function canInterrupt(status: TaskStatus) {
  return INTERRUPTIBLE.includes(status)
}

onMounted(() => {
  void consoleStore.loadMyTasks()
})

const navItems = computed(() => {
  const items = [
    { name: 'console', label: '做题工作台' },
    { name: 'community', label: '答案中心' },
  ]
  if (auth.isAdmin) {
    items.push({ name: 'admin-overview', label: '管理后台' })
    items.push({ name: 'admin-settings', label: '系统设置' })
  }
  return items
})

const activeNav = computed(() => {
  if (route.path.startsWith('/admin')) {
    return route.name === 'admin-settings' ? 'admin-settings' : 'admin-overview'
  }
  if (route.path.startsWith('/community')) {
    return 'community'
  }
  return 'console'
})

function go(name: string) {
  router.push({ name })
}

const activeTab = computed(() => {
  if (route.path.startsWith('/admin')) return 'admin'
  if (route.path.startsWith('/community')) return 'community'
  return 'console'
})

function handleLogout() {
  consoleStore.reset()
  auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="console-shell">
    <header class="topbar">
      <div class="brand" @click="go('console')">
        <span class="brand-mark"><Monitor /></span>
        <span class="brand-text">
          <strong>雨课堂</strong>
          <em>自动化做题台</em>
        </span>
      </div>

      <nav class="topnav mobile-hide">
        <button
          v-for="item in navItems"
          :key="item.name"
          class="navlink"
          :class="{ 'is-active': activeNav === item.name }"
          type="button"
          @click="go(item.name)"
        >
          {{ item.label }}
        </button>
      </nav>

      <div class="topbar-right">
        <button
          class="theme-toggle"
          type="button"
          :title="theme === 'dark' ? '切换到亮色' : '切换到暗色'"
          @click="toggleTheme"
        >
          <el-icon><Sunny v-if="theme === 'dark'" /><Moon v-else /></el-icon>
        </button>

        <button class="mytasks" type="button" @click="openTasks">
          <el-icon><Document /></el-icon>
          <span>我的任务</span>
          <em v-if="consoleStore.myTasks.length" class="count">{{ consoleStore.myTasks.length }}</em>
        </button>

        <StatusPill :tone="consoleStore.connected ? 'ok' : 'neutral'" class="conn mobile-hide">
          {{ consoleStore.connected ? '实时在线' : '等待连接' }}
        </StatusPill>

        <el-dropdown trigger="click">
          <span class="user-chip">
            <span class="avatar"><UserFilled /></span>
            <span class="user-name">{{ auth.username }}</span>
            <StatusPill :tone="auth.isAdmin ? 'accent' : 'info'" :dot="false">
              {{ auth.isAdmin ? '管理员' : '用户' }}
            </StatusPill>
            <el-icon class="caret"><CaretBottom /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="auth.isAdmin" :icon="Setting" @click="go('admin-settings')">
                系统设置
              </el-dropdown-item>
              <el-dropdown-item :icon="SwitchButton" divided @click="handleLogout">
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <el-drawer
      v-model="tasksOpen"
      class="tasks-drawer"
      title="我的任务"
      direction="rtl"
      size="380px"
    >
      <div class="tasks-head">
        <p class="tasks-hint">
          任务绑定当前登录账号「{{ auth.username }}」，与浏览器无关；刷新或换设备登录后依旧可以在这里找到并继续。
        </p>
        <el-button
          size="small"
          :icon="Refresh"
          :loading="consoleStore.myTasksLoading"
          @click="consoleStore.loadMyTasks()"
        >
          刷新
        </el-button>
      </div>

      <el-empty
        v-if="!consoleStore.myTasks.length && !consoleStore.myTasksLoading"
        description="该账号还没有任务"
        :image-size="86"
      />

      <ul v-else class="tasks-list">
        <li
          v-for="item in consoleStore.myTasks"
          :key="item.taskId"
          class="task-row"
          :class="{ 'is-active': item.taskId === consoleStore.activeTaskId }"
          @click="pickTask(item.taskId)"
        >
          <div class="task-top">
            <span class="task-id">#{{ item.taskId }}</span>
            <span class="task-batch">{{ item.batch || '批次待确认' }}</span>
            <StatusPill :tone="taskMeta(item.status).tone">{{ taskMeta(item.status).label }}</StatusPill>
          </div>
          <div class="task-sub">
            <span>{{ MODE_LABEL[item.mode] }}</span>
            <span>进度 {{ item.donePaper }}/{{ item.totalPaper }} 套</span>
          </div>
          <div class="task-foot">
            <span class="task-time">创建于 {{ formatDateTime(item.createdAt) }}</span>
            <div class="task-actions">
              <button
                v-if="canInterrupt(item.status)"
                class="task-act"
                type="button"
                @click.stop="consoleStore.interruptTaskById(item.taskId)"
              >
                中断
              </button>
              <button
                class="task-act is-danger"
                type="button"
                @click.stop="consoleStore.deleteTaskById(item.taskId)"
              >
                删除
              </button>
            </div>
          </div>
        </li>
      </ul>
    </el-drawer>

    <main class="shell-body">
      <router-view />
    </main>

    <!-- 移动端底部标签栏：竖屏手机的主要导航 -->
    <nav class="tabbar">
      <button
        class="tabbar-item"
        :class="{ 'is-active': activeTab === 'console' }"
        type="button"
        @click="go('console')"
      >
        <el-icon><Monitor /></el-icon>
        <span>做题</span>
      </button>
      <button
        class="tabbar-item"
        :class="{ 'is-active': activeTab === 'community' }"
        type="button"
        @click="go('community')"
      >
        <el-icon><Collection /></el-icon>
        <span>答案</span>
      </button>
      <template v-if="auth.isAdmin">
        <button
          class="tabbar-item"
          :class="{ 'is-active': activeTab === 'admin' && route.name !== 'admin-settings' }"
          type="button"
          @click="go('admin-overview')"
        >
          <el-icon><DataLine /></el-icon>
          <span>管理</span>
        </button>
        <button
          class="tabbar-item"
          :class="{ 'is-active': activeTab === 'admin' && route.name === 'admin-settings' }"
          type="button"
          @click="go('admin-settings')"
        >
          <el-icon><Setting /></el-icon>
          <span>设置</span>
        </button>
      </template>
    </nav>
  </div>
</template>

<style scoped>
.console-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--paper);
}

.topbar {
  display: flex;
  align-items: center;
  gap: 28px;
  height: 58px;
  padding: 0 20px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  flex: none;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: var(--accent);
  color: #fff;
  font-size: 17px;
  box-shadow: var(--shadow-sm);
}

.brand-text {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.brand-text strong {
  font-family: var(--font-display);
  font-size: 16px;
  color: var(--ink-900);
  letter-spacing: -0.01em;
}
.brand-text em {
  font-style: normal;
  font-size: 12px;
  color: var(--ink-400);
}

.topnav {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
}

.navlink {
  border: none;
  background: transparent;
  padding: 7px 14px;
  border-radius: var(--r-sm);
  font-family: var(--font-sans);
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-500);
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}
.navlink:hover {
  color: var(--ink-800);
  background: var(--surface-3);
}
.navlink.is-active {
  color: var(--accent-700);
  background: var(--accent-100);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.theme-toggle {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--ink-500);
  font-size: 16px;
  cursor: pointer;
  transition: color 0.16s ease, border-color 0.16s ease, background-color 0.16s ease;
}
.theme-toggle:hover {
  color: var(--accent);
  border-color: var(--accent-200);
  background: var(--accent-050);
}

.mytasks {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--ink-700);
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease, border-color 0.16s ease;
}
.mytasks:hover {
  color: var(--accent-700);
  border-color: var(--accent);
  background: var(--accent-100);
}
.mytasks .count {
  font-style: normal;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 99px;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  display: grid;
  place-items: center;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px 5px 6px;
  border-radius: 99px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  cursor: pointer;
  outline: none;
}
.avatar {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--surface-3);
  color: var(--ink-500);
  font-size: 13px;
}
.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
}
.caret {
  font-size: 12px;
  color: var(--ink-400);
}

.shell-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

.shell-body > :deep(*) {
  flex: 1;
  min-height: 0;
}

/* —— 移动端底部标签栏（桌面隐藏） —— */
.tabbar {
  display: none;
}

@media (max-width: 768px) {
  .topbar {
    gap: 10px;
    padding: 0 12px;
    height: 52px;
  }
  .brand-text em,
  .user-chip .caret {
    display: none;
  }
  .mytasks span {
    display: none;
  }
  .conn {
    display: none;
  }
  .mobile-hide {
    display: none;
  }
  .user-chip {
    padding: 4px 8px 4px 4px;
  }
  /* 移动端隐藏角色徽标，顶栏只留头像+用户名 */
  .user-chip :deep(.pill) {
    display: none;
  }
  .theme-toggle {
    width: 38px;
    height: 38px;
  }
  .shell-body {
    padding-bottom: 0;
  }
  .tabbar {
    display: flex;
    align-items: stretch;
    height: calc(56px + env(safe-area-inset-bottom));
    padding-bottom: env(safe-area-inset-bottom);
    background: var(--surface);
    border-top: 1px solid var(--border);
    flex: none;
  }
  .tabbar-item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    border: none;
    background: transparent;
    color: var(--ink-400);
    font-family: var(--font-sans);
    font-size: 11px;
    font-weight: 600;
    cursor: pointer;
  }
  .tabbar-item .el-icon {
    font-size: 20px;
  }
  .tabbar-item.is-active {
    color: var(--accent);
  }
  .shell-body {
    padding-bottom: 0;
  }
}

/* —— 我的任务抽屉 —— */
.tasks-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
}
.tasks-hint {
  flex: 1;
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink-500);
}
.tasks-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.task-row {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface);
  cursor: pointer;
  transition: border-color 0.16s ease, box-shadow 0.16s ease, background-color 0.16s ease;
}
.task-row:hover {
  border-color: var(--accent);
  box-shadow: var(--shadow-sm);
}
.task-row.is-active {
  border-color: var(--accent);
  background: var(--accent-100);
}
.task-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.task-id {
  font-family: var(--font-mono, var(--font-sans));
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-800);
}
.task-batch {
  flex: 1;
  font-size: 12.5px;
  color: var(--ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-sub {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 7px;
  font-size: 12.5px;
  color: var(--ink-600);
}
.task-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 8px;
}
.task-time {
  font-size: 12px;
  color: var(--ink-400);
}
.task-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.task-act {
  padding: 3px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--ink-600);
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease, border-color 0.16s ease;
}
.task-act:hover {
  color: var(--accent-700);
  border-color: var(--accent);
  background: var(--accent-100);
}
.task-act.is-danger:hover {
  color: var(--danger);
  border-color: var(--danger);
  background: var(--danger-100);
}
</style>
