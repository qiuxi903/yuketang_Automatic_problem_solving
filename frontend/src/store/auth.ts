import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import type { UserRole } from '@/types'

const TOKEN_KEY = 'ykt_token'
const USERNAME_KEY = 'ykt_username'
const ROLE_KEY = 'ykt_role'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const username = ref<string>(localStorage.getItem(USERNAME_KEY) ?? '')
  const role = ref<UserRole>((localStorage.getItem(ROLE_KEY) as UserRole) ?? 'USER')

  const isLogin = computed(() => token.value.length > 0)
  const isAdmin = computed(() => role.value === 'ADMIN')

  function setAuth(newToken: string, newUsername: string, newRole: UserRole) {
    token.value = newToken
    username.value = newUsername
    role.value = newRole
    localStorage.setItem(TOKEN_KEY, newToken)
    localStorage.setItem(USERNAME_KEY, newUsername)
    localStorage.setItem(ROLE_KEY, newRole)
  }

  async function login(name: string, password: string) {
    const res = await authApi.login(name, password)
    setAuth(res.token, res.username, res.role)
    return res
  }

  async function register(name: string, password: string, inviteCode: string) {
    await authApi.register(name, password, inviteCode)
  }

  function logout() {
    token.value = ''
    username.value = ''
    role.value = 'USER'
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USERNAME_KEY)
    localStorage.removeItem(ROLE_KEY)
  }

  return { token, username, role, isLogin, isAdmin, setAuth, login, register, logout }
})
