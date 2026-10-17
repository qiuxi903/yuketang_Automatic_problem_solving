import client from './client'
import type { ContributionItem, SearchHitItem } from '@/types'

/** 答案社区：最新贡献流与搜答案。 */

export function listContributions(limit = 20) {
  return client.get<never, ContributionItem[]>('/community/contributions', {
    params: { limit },
  })
}

export function searchAnswers(q: string, limit = 50) {
  return client.get<never, SearchHitItem[]>('/community/search', {
    params: { q, limit },
  })
}
