import client from './client'
import type { LoginResult, RegisterResult } from '@/types'

export function register(username: string, password: string, inviteCode: string) {
  return client.post<unknown, RegisterResult>('/auth/register', { username, password, inviteCode })
}

export function login(username: string, password: string) {
  return client.post<unknown, LoginResult>('/auth/login', { username, password })
}

export interface InviteInfo {
  valid: boolean
  disabled: boolean
  exhausted: boolean
  /** 剩余可用次数；-1 = 不限 */
  remaining: number
  /** 允许注册的最大次数；0 = 不限 */
  maxUses: number
}

/** 邀请码公开查询：邀请链接落地注册页时校验并展示剩余次数（无需登录）。 */
export function getInviteInfo(code: string) {
  return client.get<unknown, InviteInfo>('/auth/invite-info', { params: { code } })
}

/* —— 账号中心（个人设置） —— */

export interface MeInfo {
  id: number
  username: string
  role: string
  /** base64 dataURL；空 = 用用户名首字占位 */
  avatar: string | null
  createdAt: string
  lastLoginIp: string | null
  lastLoginLocation: string | null
  lastLoginAt: string | null
}

export interface LoginLogView {
  id: number
  ip: string
  location: string | null
  userAgent: string | null
  success: boolean
  message: string | null
  createdAt: string
}

export function getMe() {
  return client.get<unknown, MeInfo>('/auth/me')
}

/** 修改用户名（登录名同步变更，改名后用新用户名登录）。 */
export function changeUsername(username: string) {
  return client.put<unknown, { ok: boolean; username: string }>('/auth/username', { username })
}

export function changePassword(oldPassword: string, newPassword: string) {
  return client.put<unknown, { ok: boolean }>('/auth/password', { oldPassword, newPassword })
}

export function setAvatar(avatar: string) {
  return client.put<unknown, { ok: boolean }>('/auth/avatar', { avatar })
}

/** 我的登录记录（最近 50 条）。 */
export function getMyLoginLogs() {
  return client.get<unknown, LoginLogView[]>('/auth/login-logs')
}
