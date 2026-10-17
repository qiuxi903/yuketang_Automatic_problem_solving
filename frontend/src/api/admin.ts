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

export interface InviteItem {
  code: string
  used: boolean
  usedBy: string | null
  usedAt: string | null
  disabled: boolean
  createdAt: string
}

export function listInvites() {
  return client.get<unknown, InviteItem[]>('/admin/invites')
}

export function generateInvites(count: number) {
  return client.post<unknown, { codes: string[] }>('/admin/invites', { count })
}

export function disableInvite(code: string) {
  return client.put<unknown, void>(`/admin/invites/${encodeURIComponent(code)}/disable`)
}

export function deleteInvite(code: string) {
  return client.delete<unknown, void>(`/admin/invites/${encodeURIComponent(code)}`)
}

export function deleteUsedInvites() {
  return client.delete<unknown, { deleted: number }>('/admin/invites/used')
}
