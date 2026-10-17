import client from './client'
import type { AnswersBundle, AnswerScopeQuery, PaperAnswers, PapersResult } from '@/types'

export function listPapers() {
  return client.get<unknown, PapersResult>('/papers')
}

export function getPaperAnswers(batch: string, name: string) {
  return client.get<unknown, PaperAnswers>(
    `/papers/${encodeURIComponent(batch)}/${encodeURIComponent(name)}/answers`,
  )
}

/** 答案获取：单卷（batch+paper）/ 整批（batch）/ 按日期（date）。 */
export function getAnswersBundle(params: AnswerScopeQuery) {
  return client.get<unknown, AnswersBundle>('/papers/answers', { params })
}

/** 把选定范围的答案导出为 Markdown 附件。 */
export function exportAnswersBundle(params: AnswerScopeQuery) {
  return client.get<unknown, Blob>('/papers/export', { params, responseType: 'blob' })
}
