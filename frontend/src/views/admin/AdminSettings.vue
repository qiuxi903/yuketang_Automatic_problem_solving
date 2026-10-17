<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAnswerApi, updateAnswerApi } from '@/api/settings'
import StatusPill from '@/components/ui/StatusPill.vue'
import type { AnswerApiConfig } from '@/types'

const loading = ref(false)
const saving = ref(false)
const saved = ref(false)

const form = reactive({
  enabled: false,
  url: '',
  apiKey: '',
  timeoutMs: 30000,
})

const meta = ref<AnswerApiConfig | null>(null)

async function load() {
  loading.value = true
  try {
    const cfg = await getAnswerApi()
    meta.value = cfg
    form.enabled = cfg.enabled
    form.url = cfg.url
    form.timeoutMs = cfg.timeoutMs
    form.apiKey = ''
  } finally {
    loading.value = false
  }
}

async function save() {
  if (form.enabled && !form.url.trim()) {
    ElMessage.warning('启用兜底 API 前请填写接口地址')
    return
  }
  saving.value = true
  saved.value = false
  try {
    const cfg = await updateAnswerApi({
      enabled: form.enabled,
      url: form.url.trim(),
      timeoutMs: Number(form.timeoutMs) || 30000,
      ...(form.apiKey ? { apiKey: form.apiKey } : {}),
    })
    meta.value = cfg
    form.enabled = cfg.enabled
    form.url = cfg.url
    form.timeoutMs = cfg.timeoutMs
    form.apiKey = ''
    saved.value = true
    ElMessage.success('设置已保存')
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="settings">
    <header class="page-head">
      <div>
        <span class="eyebrow">系统设置</span>
        <h2>兜底答题 API</h2>
        <p class="page-sub">当本地答案库未命中时，Agent 会回退到该接口获取答案</p>
      </div>
      <StatusPill :tone="meta?.enabled ? 'ok' : 'neutral'">
        {{ meta?.enabled ? '已启用' : '未启用' }}
      </StatusPill>
    </header>

    <section v-loading="loading" class="card">
      <div class="form">
        <label class="field field-switch">
          <div class="field-text">
            <strong>启用兜底 API</strong>
            <span>关闭后将仅使用本地答案库作答</span>
          </div>
          <el-switch v-model="form.enabled" />
        </label>

        <label class="field">
          <div class="field-text">
            <strong>接口地址</strong>
            <span>支持 POST，返回体需包含答案字段</span>
          </div>
          <el-input
            v-model="form.url"
            placeholder="https://example.com/api/answer"
            :disabled="!form.enabled"
          />
        </label>

        <label class="field">
          <div class="field-text">
            <strong>API Key</strong>
            <span>
              {{ meta?.apiKeySet ? '已配置，留空表示不修改' : '尚未配置' }}
            </span>
          </div>
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            placeholder="留空则不修改现有密钥"
            :disabled="!form.enabled"
          />
        </label>

        <label class="field">
          <div class="field-text">
            <strong>超时时间</strong>
            <span>单次请求最长等待毫秒数</span>
          </div>
          <el-input-number
            v-model="form.timeoutMs"
            :min="1000"
            :max="120000"
            :step="1000"
            :disabled="!form.enabled"
            controls-position="right"
          />
        </label>
      </div>

      <div class="foot">
        <span v-if="saved" class="saved mono-sm">已保存</span>
        <div class="foot-actions">
          <el-button @click="load">重置</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.settings {
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

.card {
  padding: 22px 24px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.field {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 20px;
  align-items: center;
}
.field-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.field-text strong {
  font-size: 13.5px;
  color: var(--ink-800);
}
.field-text span {
  font-size: 12px;
  color: var(--ink-400);
}
.field-switch {
  align-items: center;
}

.foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}
.saved {
  color: var(--ok);
}
.foot-actions {
  display: flex;
  gap: 10px;
}

@media (max-width: 900px) {
  .field {
    grid-template-columns: 1fr;
    gap: 8px;
  }
}
</style>
