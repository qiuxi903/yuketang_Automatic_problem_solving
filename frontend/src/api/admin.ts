import client from './client'
import type { VipOrder } from './vip'
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

/* —— 账号系统（管理员用户管理） —— */

export interface LoginLogView {
  id: number
  userId: number
  username: string
  ip: string
  location: string | null
  userAgent: string | null
  success: boolean
  message: string | null
  createdAt: string
}

/** 管理员重置用户密码（重置后该用户所有登录态失效）。 */
export function resetUserPassword(id: number, password: string) {
  return client.put<unknown, { ok: boolean }>(`/admin/users/${id}/password`, { password })
}

/** 封禁 / 解封用户。 */
export function setUserDisabled(id: number, disabled: boolean) {
  return client.put<unknown, { ok: boolean; disabled: boolean }>(`/admin/users/${id}/disabled`, { disabled })
}

/** 用户登录记录（最近 50 条：IP/归属地/结果）。 */
export function getUserLoginLogs(id: number) {
  return client.get<unknown, LoginLogView[]>(`/admin/users/${id}/login-logs`)
}

/** 按用户过滤做题记录。 */
export function getUserTasks(id: number) {
  return client.get<unknown, import('@/types').AdminTaskView[]>(`/admin/users/${id}/tasks`)
}

/** 用户充值/订单记录。 */
export function getUserOrders(id: number) {
  return client.get<unknown, VipOrder[]>(`/admin/vip/users/${id}/orders`)
}

/* —— 管理操作审计 —— */

export interface AuditLogView {
  id: number
  adminId: number
  adminUsername: string
  action: string
  target: string
  detail: string
  ip: string
  createdAt: string
}

/** 最近管理操作审计（谁/何时/对谁/做了什么/IP）。 */
export function listAuditLogs(limit = 100) {
  return client.get<unknown, AuditLogView[]>('/admin/audit-logs', { params: { limit } })
}
