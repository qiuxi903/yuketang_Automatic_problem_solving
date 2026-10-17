import client from './client'
import type { AnswerApiConfig, AnswerApiConfigPayload } from '@/types'

export function getAnswerApi() {
  return client.get<unknown, AnswerApiConfig>('/settings/answer-api')
}

export function updateAnswerApi(payload: AnswerApiConfigPayload) {
  return client.put<unknown, AnswerApiConfig>('/settings/answer-api', payload)
}
