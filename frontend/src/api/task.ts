import client from './client'
import type {
  BatchOptions,
  OkResult,
  SetBatchPayload,
  TaskCreatePayload,
  TaskCreated,
  TaskFrame,
  TaskSnapshot,
} from '@/types'

export function createTask(payload: TaskCreatePayload) {
  return client.post<unknown, TaskCreated>('/tasks', payload)
}

export function listTasks() {
  return client.get<unknown, TaskSnapshot[]>('/tasks')
}

export function getTask(id: number) {
  return client.get<unknown, TaskSnapshot>(`/tasks/${id}`)
}

/** 登录后按账号实际作业列出可选批次与覆盖率。 */
export function getBatchOptions(id: number) {
  return client.get<unknown, BatchOptions>(`/tasks/${id}/batch-options`)
}

/** 确认批次与作答模式，任务交回 Agent 继续作答。 */
export function setTaskBatch(id: number, payload: SetBatchPayload) {
  return client.post<unknown, OkResult>(`/tasks/${id}/batch`, payload)
}

export function pauseTask(id: number) {
  return client.post<unknown, OkResult>(`/tasks/${id}/pause`)
}

export function resumeTask(id: number) {
  return client.post<unknown, OkResult>(`/tasks/${id}/resume`)
}

/** 中断任务：停止后续执行，任务进入终端态「已中断」。 */
export function interruptTask(id: number) {
  return client.post<unknown, OkResult>(`/tasks/${id}/interrupt`)
}

/** 删除任务：连同任务明细一并删除，不可恢复。 */
export function deleteTask(id: number) {
  return client.delete<unknown, OkResult>(`/tasks/${id}`)
}

export function exportTask(id: number) {
  return client.get<unknown, Blob>(`/tasks/${id}/export`, { responseType: 'blob' })
}

/** 浏览器模式：拉取当前任务最近一帧雨课堂实时页面截图。 */
export function getTaskFrame(id: number) {
  return client.get<unknown, TaskFrame>(`/tasks/${id}/frame`)
}
