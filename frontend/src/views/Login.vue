<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Camera, Connection, Monitor, Postcard } from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  password: '',
  inviteCode: '',
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度为 3-32 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度为 6-64 位', trigger: 'blur' },
  ],
}

/** 注册模式的强校验：用户名格式、密码强度、邀请码必填 */
const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[一-龥A-Za-z0-9_]{2,20}$/, message: '2-20 位，只能用中文、字母、数字或下划线', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v && /^\d+$/.test(v)) cb(new Error('不能是纯数字，太简单容易被猜'))
        else cb()
      },
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码至少 8 位', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v && !(/[A-Za-z]/.test(v) && /\d/.test(v))) cb(new Error('需同时包含字母和数字'))
        else cb()
      },
      trigger: 'blur',
    },
  ],
  inviteCode: [{ required: true, message: '请输入邀请码（向管理员索取）', trigger: 'blur' }],
}

const steps = [
  { icon: Camera, title: '发起任务并扫码', desc: 'Agent 自动打开雨课堂登录页，二维码过期会自动刷新' },
  { icon: Connection, title: '实时自动作答', desc: '答案库命中即作答，未命中回退到兜底 API' },
  { icon: Postcard, title: '导出作答结果', desc: '试卷得分与答案记录一键导出，全程可视化' },
]

function switchMode(next: 'login' | 'register') {
  mode.value = next
  formRef.value?.clearValidate()
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    if (mode.value === 'register') {
      await auth.register(form.username, form.password, form.inviteCode)
      ElMessage.success('注册成功，请登录')
      mode.value = 'login'
      return
    }

    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    const redirect = route.query.redirect as string | undefined
    router.replace(redirect || { name: 'console' })
  } catch {
    // 错误提示由 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <aside class="pitch">
      <div class="pitch-head">
        <span class="pitch-mark"><Monitor /></span>
        <div class="pitch-brand">
          <strong>雨课堂</strong>
          <em>自动化做题平台</em>
        </div>
      </div>

      <div class="pitch-copy">
        <span class="eyebrow eyebrow-inv">扫码即用 · 无需手动答题</span>
        <h1>你只需扫一次码，<br />剩下的交给 Agent。</h1>
        <p>
          基于真实后端与浏览器 Agent 的答题工作台，左侧看题目清单，
          中间控制流程，右侧实时呈现答题过程与任务令牌。
        </p>
      </div>

      <ul class="pitch-steps">
        <li v-for="step in steps" :key="step.title">
          <span class="step-icon"><component :is="step.icon" /></span>
          <span class="step-text">
            <strong>{{ step.title }}</strong>
            <em>{{ step.desc }}</em>
          </span>
        </li>
      </ul>

      <div class="pitch-foot">平台账号与雨课堂账号相互独立</div>
    </aside>

    <main class="form-side">
      <div class="form-card">
        <header class="form-head">
          <span class="eyebrow">{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</span>
          <h2>{{ mode === 'login' ? '登录做题台' : '注册新账号' }}</h2>
          <p>使用平台账号登录，做题时再扫码登录雨课堂。</p>
        </header>

        <div class="mode-switch">
          <button
            type="button"
            :class="{ 'is-on': mode === 'login' }"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            :class="{ 'is-on': mode === 'register' }"
            @click="switchMode('register')"
          >
            注册
          </button>
        </div>

        <el-form
          ref="formRef"
          :model="form"
          :rules="mode === 'register' ? registerRules : rules"
          label-position="top"
          @submit.prevent="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              size="large"
              clearable
            />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              size="large"
              show-password
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-form-item v-if="mode === 'register'" label="邀请码" prop="inviteCode">
            <el-input
              v-model="form.inviteCode"
              placeholder="向管理员索取"
              size="large"
            />
          </el-form-item>
          <el-button
            type="primary"
            class="submit"
            size="large"
            :loading="loading"
            @click="submit"
          >
            {{ mode === 'login' ? '登录' : '注册' }}
          </el-button>
        </el-form>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  height: 100%;
  background: var(--paper);
}

/* —— 左侧横幅 —— */
.pitch {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 30px;
  padding: 46px 52px;
  background: linear-gradient(155deg, var(--accent-700), var(--accent) 58%, #17705a);
  color: #eef5f2;
  overflow: hidden;
}
.pitch::after {
  content: '';
  position: absolute;
  right: -120px;
  bottom: -140px;
  width: 340px;
  height: 340px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.16), transparent 68%);
}

.pitch-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.pitch-mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  font-size: 20px;
}
.pitch-brand {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}
.pitch-brand strong {
  font-family: var(--font-display);
  font-size: 17px;
  color: #fff;
}
.pitch-brand em {
  font-style: normal;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
}

.pitch-copy {
  max-width: 420px;
}
.eyebrow-inv {
  color: rgba(255, 255, 255, 0.72);
}
.pitch-copy h1 {
  margin: 10px 0 14px;
  font-size: 34px;
  line-height: 1.28;
  color: #fff;
  letter-spacing: -0.02em;
}
.pitch-copy p {
  margin: 0;
  font-size: 14px;
  line-height: 1.75;
  color: rgba(255, 255, 255, 0.8);
}

.pitch-steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 440px;
}
.pitch-steps li {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.step-icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-size: 16px;
}
.step-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.step-text strong {
  font-size: 13.5px;
  color: #fff;
}
.step-text em {
  font-style: normal;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.72);
  line-height: 1.6;
}

.pitch-foot {
  margin-top: auto;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
}

/* —— 右侧表单 —— */
.form-side {
  display: grid;
  place-items: center;
  padding: 40px;
}
.form-card {
  width: 100%;
  max-width: 384px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.form-head h2 {
  font-size: 24px;
  margin: 8px 0 6px;
}
.form-head p {
  margin: 0;
  font-size: 13px;
  color: var(--ink-500);
}

.mode-switch {
  display: flex;
  padding: 4px;
  border-radius: var(--r-md);
  background: var(--surface-3);
  gap: 4px;
}
.mode-switch button {
  flex: 1;
  padding: 9px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  font-family: var(--font-sans);
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-500);
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
}
.mode-switch button.is-on {
  background: var(--surface);
  color: var(--accent-700);
  box-shadow: var(--shadow-xs);
}

.submit {
  width: 100%;
  margin-top: 4px;
}

@media (max-width: 900px) {
  .login {
    grid-template-columns: 1fr;
  }
  .pitch {
    padding: 32px;
    gap: 20px;
  }
  .pitch-copy h1 {
    font-size: 26px;
  }
  .pitch-steps {
    display: none;
  }
}

@media (max-width: 768px) {
  .login {
    grid-template-columns: 1fr;
  }
  .pitch {
    display: none;
  }
  .form-side {
    padding: 24px 16px;
  }
  .form-card {
    padding: 20px 16px;
  }
}
</style>
