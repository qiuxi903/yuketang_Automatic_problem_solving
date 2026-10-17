<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Collection, Odometer, Postcard, Setting, Tickets, User } from '@element-plus/icons-vue'
import StatusPill from '@/components/ui/StatusPill.vue'

const route = useRoute()

const navItems = [
  { name: 'admin-overview', label: '概览', icon: Odometer, hint: '平台运行总览' },
  { name: 'admin-users', label: '用户管理', icon: User, hint: '账号与角色' },
  { name: 'admin-tasks', label: '任务管理', icon: Tickets, hint: '全部作答任务' },
  { name: 'admin-library', label: '答案库', icon: Collection, hint: '批次与试卷' },
  { name: 'admin-invites', label: '邀请码', icon: Postcard, hint: '注册邀请码管理' },
  { name: 'admin-settings', label: '系统设置', icon: Setting, hint: '兜底答题 API' },
]

const activeName = computed(() => route.name as string)
</script>

<template>
  <div class="admin">
    <aside class="admin-side">
      <div class="side-head">
        <span class="eyebrow">管理员后台</span>
        <h3>平台控制台</h3>
      </div>

      <nav class="side-nav">
        <router-link
          v-for="item in navItems"
          :key="item.name"
          :to="{ name: item.name }"
          class="side-link"
          :class="{ 'is-active': activeName === item.name }"
        >
          <span class="side-icon"><component :is="item.icon" /></span>
          <span class="side-text">
            <strong>{{ item.label }}</strong>
            <em>{{ item.hint }}</em>
          </span>
        </router-link>
      </nav>

      <div class="side-foot">
        <StatusPill tone="accent" :dot="false">管理员权限</StatusPill>
      </div>
    </aside>

    <div class="admin-body scroll-area">
      <router-view />
    </div>
  </div>
</template>

<style scoped>
.admin {
  display: flex;
  flex: 1;
  min-height: 0;
}

.admin-side {
  display: flex;
  flex-direction: column;
  width: 236px;
  flex: none;
  background: var(--surface);
  border-right: 1px solid var(--border);
}

.side-head {
  padding: 20px 18px 14px;
}
.side-head h3 {
  font-size: 16px;
  margin-top: 3px;
}

.side-nav {
  flex: 1;
  padding: 4px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  overflow-y: auto;
}

.side-link {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 9px 11px;
  border-radius: var(--r-sm);
  color: var(--ink-600);
  transition: background-color 0.15s ease, color 0.15s ease;
}
.side-link:hover {
  background: var(--surface-2);
}
.side-link.is-active {
  background: var(--accent-100);
  color: var(--accent-700);
}
.side-icon {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: var(--surface-3);
  font-size: 15px;
  flex: none;
}
.side-link.is-active .side-icon {
  background: var(--accent);
  color: #fff;
}
.side-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.side-text strong {
  font-size: 13.5px;
  font-weight: 600;
}
.side-text em {
  font-style: normal;
  font-size: 11px;
  color: var(--ink-400);
}

.side-foot {
  padding: 14px 18px;
  border-top: 1px solid var(--border);
}

.admin-body {
  flex: 1;
  min-width: 0;
  padding: 22px 26px 34px;
}

@media (max-width: 768px) {
  .admin {
    flex-direction: column;
  }
  .admin-side {
    width: 100%;
    flex-direction: row;
    align-items: center;
    border-right: none;
    border-bottom: 1px solid var(--border);
    padding: 8px 10px;
    gap: 6px;
    overflow-x: auto;
  }
  .side-head,
  .side-foot {
    display: none;
  }
  .side-nav {
    flex-direction: row;
    gap: 6px;
  }
  .side-link {
    white-space: nowrap;
    padding: 7px 12px;
  }
}
</style>
