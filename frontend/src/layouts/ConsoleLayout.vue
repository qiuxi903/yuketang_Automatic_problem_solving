<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CaretBottom, Coin, Collection, DataLine, Monitor, Moon, Refresh, Setting, Sunny, SwitchButton, User, UserFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/auth'
import { useConsoleStore } from '@/store/console'
import StatusPill from '@/components/ui/StatusPill.vue'
import { toggleTheme, useTheme } from '@/utils/theme'
import { getVipStatus } from '@/api/vip'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const consoleStore = useConsoleStore()
const theme = useTheme()

// 月卡到期提醒：在期且剩 ≤7 天时顶栏亮徽章（点击进 VIP 页续费）
const monthDaysLeft = ref<number | null>(null)

async function loadMonthDays() {
  if (!auth.isLogin) return
  try {
    const s = await getVipStatus()
    monthDaysLeft.value =
      s.monthCardActive && s.monthCardExpiresAt
        ? Math.max(0, Math.ceil((new Date(s.monthCardExpiresAt).getTime() - Date.now()) / 86400000))
        : null
  } catch {
    monthDaysLeft.value = null
  }
}

onMounted(() => {
  void loadMonthDays()
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

function goVip() {
  router.push({ path: '/vip' })
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

        <button
          v-if="monthDaysLeft !== null && monthDaysLeft <= 7"
          class="month-pill"
          type="button"
          title="月卡即将到期，续费自动顺延"
          @click="goVip"
        >
          <el-icon><Coin /></el-icon>
          <span>月卡剩 {{ monthDaysLeft }} 天</span>
        </button>

        <button class="vip-entry" type="button" title="VIP 会员中心" @click="goVip">
          <el-icon><Coin /></el-icon>
          <span class="mobile-hide">VIP</span>
        </button>


        <StatusPill :tone="consoleStore.connected ? 'ok' : 'neutral'" class="conn mobile-hide">
          {{ consoleStore.connected ? '实时在线' : '等待连接' }}
        </StatusPill>

        <el-dropdown trigger="click" class="mobile-hide">
          <span class="user-chip">
            <span class="avatar">
              <img v-if="auth.avatar" :src="auth.avatar" alt="头像" />
              <UserFilled v-else />
            </span>
            <span class="user-name">{{ auth.username }}</span>
            <StatusPill :tone="auth.isAdmin ? 'accent' : 'info'" :dot="false">
              {{ auth.isAdmin ? '管理员' : '用户' }}
            </StatusPill>
            <el-icon class="caret"><CaretBottom /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :icon="User" @click="go('profile')">个人设置</el-dropdown-item>
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
        :class="{ 'is-active': route.path.startsWith('/vip') }"
        type="button"
        @click="goVip"
      >
        <el-icon><Coin /></el-icon>
        <span>VIP</span>
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
      <button
        class="tabbar-item tabbar-me"
        :class="{ 'is-active': route.path.startsWith('/profile') }"
        type="button"
        @click="go('profile')"
      >
        <img v-if="auth.avatar" :src="auth.avatar" alt="" class="tabbar-avatar" />
        <el-icon v-else><User /></el-icon>
        <span>我的</span>
      </button>
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
  flex: none;
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
  /* 平板宽度（769~1100px）空间不足时不许换行堆字：横向滚动兜底 */
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}
.topnav::-webkit-scrollbar {
  display: none;
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
  white-space: nowrap;
  flex: none;
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
  /* 顶栏右侧按钮组永不收缩：否则窄宽度下被导航挤压，按钮裁掉点不到 */
  flex: none;
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


/* —— 月卡到期徽章 —— */
.month-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--amber);
  border-radius: 99px;
  background: var(--amber-050);
  color: var(--amber);
  font-family: var(--font-sans);
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}
.month-pill:hover {
  color: #fff;
  background: var(--amber);
}

/* —— VIP 入口：琥珀金小徽章 —— */
.vip-entry {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--amber);
  border-radius: var(--r-sm);
  background: var(--amber-050);
  color: var(--amber);
  font-family: var(--font-sans);
  font-size: 12.5px;
  font-weight: 800;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
}
.vip-entry:hover {
  color: #fff;
  background: var(--amber);
  box-shadow: var(--shadow-sm);
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
  overflow: hidden;
  background: var(--surface-3);
  color: var(--ink-500);
  font-size: 13px;
}
.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
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
  /* flex 子项默认 min-width:auto 会被宽内容（el-table 等）撑破布局宽度，
     导致手机端整页按内容宽排版、超出部分被裁（页头/栅格全乱）——必须允许收缩 */
  min-width: 0;
}

/* —— 移动端底部标签栏（桌面隐藏） —— */
.tabbar {
  display: none;
}

@media (max-width: 768px) {
  .topbar {
    gap: 10px;
    padding: 0 12px;
    height: 54px;
  }
  /* 手机端导航隐藏后按钮组失去撑开者：靠右对齐，右边不再空一大片 */
  .topbar-right {
    margin-left: auto;
  }
  .brand-text em,
  .user-chip .caret {
    display: none;
  }
  .brand-mark {
    width: 34px;
    height: 34px;
    font-size: 18px;
  }
  .brand-text strong {
    font-size: 16.5px;
  }
  .vip-entry {
    height: 38px;
    padding: 0 11px;
  }
  .month-pill {
    height: 38px;
  }
  .mobile-hide {
    display: none;
  }
  .user-chip {
    padding: 4px 10px 4px 5px;
    height: 38px;
  }
  .user-name {
    font-size: 13.5px;
  }
  /* 移动端隐藏角色徽标，顶栏只留头像+用户名 */
  .user-chip :deep(.pill) {
    display: none;
  }
  .theme-toggle {
    width: 38px;
    height: 38px;
    font-size: 17px;
  }
  .topbar-right {
    gap: 8px;
  }
  .shell-body {
    padding-bottom: 0;
  }
  .tabbar {
    display: flex;
    align-items: stretch;
    height: calc(60px + env(safe-area-inset-bottom));
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
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    transition: color 0.16s ease;
  }
  .tabbar-item .el-icon {
    font-size: 22px;
  }
  .tabbar-item.is-active {
    color: var(--accent-700);
  }
  .tabbar-item.is-active .el-icon {
    color: var(--accent);
  }
  /* 6 项（含管理员）时收紧间距 */
  .tabbar-item {
    padding: 0 2px;
  }
  .tabbar-me .tabbar-avatar {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    object-fit: cover;
    border: 1.5px solid var(--accent);
  }
}
</style>
