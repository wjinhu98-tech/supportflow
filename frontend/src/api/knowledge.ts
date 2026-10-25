import type { KnowledgeArticle, KnowledgeAudit, KnowledgeInput } from '../types'

export const KNOWLEDGE_LIMITS = {
  title: 120,
  keywordCount: 12,
  keyword: 40,
  content: 4000,
} as const

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function timestamp(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d\d-\d\dT/.test(value) &&
    Number.isFinite(Date.parse(value))
  )
}

function integer(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

export function validKnowledgeInput(value: unknown): value is KnowledgeInput {
  if (!record(value)) return false
  return (
    typeof value.title === 'string' &&
    value.title.trim().length > 0 &&
    value.title.length <= KNOWLEDGE_LIMITS.title &&
    typeof value.content === 'string' &&
    value.content.trim().length > 0 &&
    value.content.length <= KNOWLEDGE_LIMITS.content &&
    Array.isArray(value.keywords) &&
    value.keywords.length > 0 &&
    value.keywords.length <= KNOWLEDGE_LIMITS.keywordCount &&
    value.keywords.every(
      (keyword) =>
        typeof keyword === 'string' &&
        keyword.trim().length > 0 &&
        keyword.length <= KNOWLEDGE_LIMITS.keyword,
    )
  )
}

export function validKnowledgeArticle(value: unknown, shopId: string): value is KnowledgeArticle {
  if (!record(value) || !validKnowledgeInput(value)) return false
  const article = value as unknown as Record<string, unknown>
  if (
    typeof article.id !== 'string' ||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(article.id) ||
    article.shopId !== Number(shopId) ||
    !integer(article.version) ||
    !timestamp(article.createdAt) ||
    !timestamp(article.updatedAt) ||
    !['DRAFT', 'PUBLISHED', 'OFFLINE'].includes(String(article.status))
  )
    return false
  if (article.status !== 'PUBLISHED')
    return article.publishedRevision === null && article.publishedSnapshot === null
  return (
    integer(article.publishedRevision) &&
    article.publishedRevision > 0 &&
    article.publishedRevision <= article.version &&
    record(article.publishedSnapshot) &&
    validKnowledgeInput(article.publishedSnapshot) &&
    article.publishedSnapshot.revision === article.publishedRevision &&
    timestamp(article.publishedSnapshot.publishedAt)
  )
}

export function validKnowledgeAudit(value: unknown): value is KnowledgeAudit {
  if (!record(value) || !validKnowledgeInput(value)) return false
  const audit = value as unknown as Record<string, unknown>
  return (
    typeof audit.id === 'string' &&
    audit.id.length > 0 &&
    audit.id.length <= 200 &&
    ['CREATED', 'SAVED', 'PUBLISHED', 'OFFLINE'].includes(String(audit.action)) &&
    typeof audit.actor === 'string' &&
    audit.actor.trim().length > 0 &&
    integer(audit.version) &&
    (audit.publishedRevision === null ||
      (integer(audit.publishedRevision) && audit.publishedRevision > 0)) &&
    timestamp(audit.createdAt)
  )
}
