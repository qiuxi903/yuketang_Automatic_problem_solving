import client from './client'
import type { LoginResult, RegisterResult } from '@/types'

export function register(username: string, password: string, inviteCode: string) {
  return client.post<unknown, RegisterResult>('/auth/register', { username, password, inviteCode })
}

export function login(username: string, password: string) {
  return client.post<unknown, LoginResult>('/auth/login', { username, password })
}
