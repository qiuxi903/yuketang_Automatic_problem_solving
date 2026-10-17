import client from './client'
import type { OkResult, SessionCreated, SessionInfo, TaskMode } from '@/types'

/** 批次后置 + 模式后置：建会话时不再指定批次与模式，均在扫码登录后确认。 */
export function createSession(mode?: TaskMode) {
  return client.post<unknown, SessionCreated>('/sessions', { mode })
}

export function getSession(id: number) {
  return client.get<unknown, SessionInfo>(`/sessions/${id}`)
}

export function closeSession(id: number) {
  return client.delete<unknown, OkResult>(`/sessions/${id}`)
}
