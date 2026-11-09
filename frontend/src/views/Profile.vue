<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Coin, Key, User } from '@element-plus/icons-vue'
import {
  changePassword,
  changeUsername,
  getMe,
  setAvatar,
  type MeInfo,
} from '@/api/auth'
import { getVipStatus, type VipStatus } from '@/api/vip'
import { formatDateTime } from '@/utils/format'
import { useAuthStore } from '@/store/auth'
import { useRouter } from 'vue-router'

const auth = useAuthStore()
const router = useRouter()

const loading = ref(true)
const me = ref<MeInfo | null>(null)
const vip = ref<VipStatus | null>(null)

/* —— 改用户名 —— */
const nameForm = ref({ username: '' })
const nameSaving = ref(false)

/* —— 改密码 —— */
const pwdForm = ref({ oldPassword: '', newPassword: '', confirm: '' })
const pwdSaving = ref(false)

async function load() {
  try {
    const [m, v] = await Promise.all([getMe(), getVipStatus()])
    me.value = m
    vip.value = v
    nameForm.value.username = m.username
    auth.setAvatarUrl(m.avatar)
  } finally {
    loading.value = false
  }
}

/* —— 退出登录 —— */
async function logoutNow() {
  try {
    await ElMessageBox.confirm('确定退出当前账号吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  auth.logout()
  router.push({ name: 'login' })
}

/* —— 头像上传：本地压缩到 256×256 再上传 —— */
const avatarInput = ref<HTMLInputElement>()
function pickAvatar() {
  avatarInput.value?.click()
}
async function onAvatarFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  ;(e.target as HTMLInputElement).value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  const dataUrl = await compressImage(file, 256)
  if (dataUrl.length > 300_000) {
    ElMessage.warning('图片压缩后仍过大（约 300KB 上限），请换小图')
    return
  }
  try {
    await setAvatar(dataUrl)
    me.value = { ...(me.value as MeInfo), avatar: dataUrl }
    auth.setAvatarUrl(dataUrl)
    ElMessage.success('头像已更新')
  } catch {
    /* 拦截器已提示 */
  }
}

/** 图片压成正方形 256×256 JPEG（中心裁剪）。 */
function compressImage(file: File, size: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const img = new Image()
      img.onload = () => {
        const canvas = document.createElement('canvas')
        canvas.width = size
        canvas.height = size
        const ctx = canvas.getContext('2d')!
        const side = Math.min(img.width, img.height)
        ctx.drawImage(
          img,
          (img.width - side) / 2,
          (img.height - side) / 2,
          side,
          side,
          0,
          0,
          size,
          size,
        )
        resolve(canvas.toDataURL('image/jpeg', 0.85))
      }
      img.onerror = () => reject(new Error('图片读取失败'))
      img.src = String(reader.result)
    }
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsDataURL(file)
  })
}

async function saveUsername() {
  const name = nameForm.value.username.trim()
  if (!me.value || name === me.value.username) return
  nameSaving.value = true
  try {
    await changeUsername(name)
    ElMessage.success('用户名已更新，以后请用新用户名登录')
    auth.setUsernameSafe(name)
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    nameSaving.value = false
  }
}

async function savePassword() {
  const { oldPassword, newPassword, confirm } = pwdForm.value
  if (!oldPassword || !newPassword) {
    ElMessage.warning('请填写旧密码和新密码')
    return
  }
  if (newPassword.length < 8 || !(/[A-Za-z]/.test(newPassword) && /\d/.test(newPassword))) {
    ElMessage.warning('新密码至少 8 位，且需同时包含字母和数字')
    return
  }
  if (newPassword !== confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwdSaving.value = true
  try {
    await changePassword(oldPassword, newPassword)
    pwdForm.value = { oldPassword: '', newPassword: '', confirm: '' }
    try {
      await ElMessageBox.confirm(
        '密码已修改，其它设备的登录态已失效。当前浏览器需要重新登录吗？',
        '密码已更新',
        { confirmButtonText: '重新登录', cancelButtonText: '留在本页', type: 'success' },
      )
      auth.logout()
      router.push({ name: 'login' })
      return
    } catch {
      return
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    pwdSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="profile">
    <div class="page-inner">
      <header class="page-head">
        <div>
          <span class="eyebrow">个人中心</span>
          <h2>我的账号</h2>
          <p class="page-sub">头像、用户名、密码，都在这里管理。</p>
        </div>
        <div class="head-actions">
          <el-button type="danger" plain @click="logoutNow">退出登录</el-button>
        </div>
      </header>

      <div class="grid">
        <!-- 头像与身份 -->
        <div class="panel-card">
          <h4><el-icon><User /></el-icon> 头像与身份</h4>
          <div class="id-row">
            <div class="avatar-wrap" @click="pickAvatar">
              <img v-if="me?.avatar" :src="me.avatar" alt="头像" />
              <span v-else class="avatar-fallback">{{ (me?.username || '?').slice(0, 1) }}</span>
              <span class="avatar-edit">更换</span>
            </div>
            <input ref="avatarInput" type="file" accept="image/*" hidden @change="onAvatarFile" />
            <div class="id-info">
              <div class="id-name">{{ me?.username || '—' }}</div>
              <div class="id-sub">
                ID {{ me?.id || '—' }} · {{ me?.role === 'ADMIN' ? '管理员' : '普通用户' }} ·
                注册于 {{ formatDateTime(me?.createdAt) }}
              </div>
            </div>
          </div>
          <p class="hint">点击头像更换（自动裁成正方形）。头像仅自己与后台可见。</p>

          <el-form label-position="top" @submit.prevent="saveUsername">
            <el-form-item label="用户名（登录名，改后用新名字登录）">
              <div class="name-row">
                <el-input v-model="nameForm.username" maxlength="20" show-word-limit />
                <el-button
                  type="primary"
                  :loading="nameSaving"
                  :disabled="!nameForm.username || nameForm.username === me?.username"
                  @click="saveUsername"
                >
                  改名
                </el-button>
              </div>
            </el-form-item>
          </el-form>
        </div>

        <!-- 修改密码 -->
        <div class="panel-card">
          <h4><el-icon><Key /></el-icon> 修改密码</h4>
          <el-form label-position="top" @submit.prevent="savePassword">
            <el-form-item label="旧密码">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码（至少 8 位，含字母和数字）">
              <el-input v-model="pwdForm.newPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input v-model="pwdForm.confirm" type="password" show-password />
            </el-form-item>
            <el-button type="primary" :loading="pwdSaving" @click="savePassword">修改密码</el-button>
            <p class="hint">修改成功后其它设备的登录态会立即失效。</p>
          </el-form>
        </div>
      </div>

      <!-- VIP 状态摘要 -->
      <div v-if="vip" class="panel-card vip-strip">
        <h4><el-icon><Coin /></el-icon> VIP 状态</h4>
        <div class="vip-facts">
          <span>月卡 <b>{{ vip.monthCardActive ? '生效中' : '未开通' }}</b></span>
          <span>免费额度 <b>{{ vip.freeQuota }} 次</b></span>
          <span>已解锁日期 <b>{{ vip.unlockedBatches.length }} 个</b></span>
          <el-button size="small" type="primary" plain @click="router.push({ path: '/vip' })">
            去 VIP 会员中心
          </el-button>
        </div>
      </div>

      <!-- 手机端：退出登录放内容末尾（顶部红条太扎眼） -->
      <div class="logout-mobile mobile-only">
        <el-button type="danger" plain size="large" @click="logoutNow">退出登录</el-button>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
export default { name: 'ProfilePage' }
</script>

<style scoped>
.profile {
  height: 100%;
  overflow-y: auto;
  background: var(--paper);
  padding: 20px 24px 48px;
}
/* 居中版心：与全站 page-inner 一致，宽视口不再整条铺满靠左 */
.page-inner {
  max-width: 860px;
  margin: 0 auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.page-head .eyebrow {
  color: var(--amber);
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
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.panel-card {
  padding: 18px 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
}
.panel-card h4 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 14px;
  font-size: 15px;
}
.panel-card h4 .el-icon {
  color: var(--accent-700);
}
.hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--ink-400);
}

.id-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 10px;
}
.avatar-wrap {
  position: relative;
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid var(--border);
  background: var(--surface-3);
}
.avatar-wrap img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.avatar-fallback {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  font-size: 30px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(155deg, var(--accent-700), var(--accent));
}
.avatar-edit {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  text-align: center;
  font-size: 11px;
  padding: 2px 0;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
}
.id-name {
  font-size: 18px;
  font-weight: 800;
  color: var(--ink-900);
}
.id-sub {
  font-size: 12px;
  color: var(--ink-500);
  margin-top: 3px;
}
.name-row {
  display: flex;
  gap: 10px;
}

.vip-strip {
  margin-top: 18px;
}
.vip-strip .vip-facts {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--ink-600);
}
.vip-strip b {
  color: var(--ink-900);
}

@media (max-width: 768px) {
  .profile {
    padding: 16px 14px 34px;
  }
  .grid {
    grid-template-columns: 1fr;
  }
  .page-head {
    flex-direction: column;
    align-items: stretch;
    text-align: center;
  }
  /* 顶部退出按钮手机端隐藏：已挪到内容底部，顶部不再扎眼 */
  .head-actions {
    display: none;
  }
  /* 头像身份卡居中排版，更大气 */
  .id-row {
    flex-direction: column;
    align-items: center;
    text-align: center;
  }
  .avatar-wrap {
    width: 88px;
    height: 88px;
  }
  .id-name {
    font-size: 20px;
  }
  .name-row {
    flex-direction: column;
  }
  .name-row .el-button {
    width: 100%;
  }
  /* 底部退出按钮全宽 */
  .logout-mobile {
    margin-top: 14px;
  }
  .logout-mobile .el-button {
    width: 100%;
  }
  .name-row {
    flex-direction: column;
  }
  .vip-facts {
    gap: 10px;
  }
}
</style>
