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
