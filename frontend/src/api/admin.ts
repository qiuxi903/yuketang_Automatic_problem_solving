import client from './client'
import type { AdminOverview, AdminTaskView, AdminUserView, TaskSnapshot, UserRole } from '@/types'

/** 管理员接口，后端 /api/admin/** 已限定 ADMIN 角色。 */

export function getOverview() {
  return client.get<unknown, AdminOverview>('/admin/overview')
}

export function listUsers() {
  return client.get<unknown, AdminUserView[]>('/admin/users')
}

export function updateUserRole(id: number, role: UserRole) {
  return client.put<unknown, AdminUserView>(`/admin/users/${id}/role`, { role })
}

export function listAllTasks() {
  return client.get<unknown, AdminTaskView[]>('/admin/tasks')
}

export function getAdminTask(id: number) {
  return client.get<unknown, TaskSnapshot>(`/admin/tasks/${id}`)
}

/* —— 邀请码 —— */

export interface InviteUseEntry {
  username: string
  usedAt: string
}

export interface InviteItem {
  code: string
  used: boolean
  usedBy: string | null
  usedAt: string | null
  disabled: boolean
  createdAt: string
  /** 允许注册的最大次数；0 = 不限 */
  maxUses: number
  usedCount: number
  uses: InviteUseEntry[]
}

export function listInvites() {
  return client.get<unknown, InviteItem[]>('/admin/invites')
}

/** maxUses：1 一码一用；0 不限次数；>1 一个码可邀请多人 */
export function generateInvites(count: number, maxUses = 1) {
  return client.post<unknown, { codes: string[] }>('/admin/invites', { count, maxUses })
}

export function disableInvite(code: string) {
  return client.put<unknown, void>(`/admin/invites/${encodeURIComponent(code)}/disable`)
}

export function enableInvite(code: string) {
  return client.put<unknown, void>(`/admin/invites/${encodeURIComponent(code)}/enable`)
}

export function deleteInvite(code: string) {
  return client.delete<unknown, void>(`/admin/invites/${encodeURIComponent(code)}`)
}

export function deleteUsedInvites() {
  return client.delete<unknown, { deleted: number }>('/admin/invites/used')
}

/** 批量操作：disable / enable / delete，一次请求处理多选中的邀请码。 */
export function batchInvites(action: 'disable' | 'enable' | 'delete', codes: string[]) {
  return client.post<unknown, { affected: number }>('/admin/invites/batch', { action, codes })
}
