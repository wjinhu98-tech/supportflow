import type { KnowledgeArticle, KnowledgeAudit } from '../types'

export const knowledgeDraft: KnowledgeArticle = {
  id: '00000000-0000-4000-8000-000000000101',
  shopId: 100,
  title: '本店配送说明',
  keywords: ['配送', '送达时间'],
  content: '本店通常在发货后三日内送达。',
  status: 'DRAFT',
  version: 0,
  publishedRevision: null,
  publishedSnapshot: null,
  createdAt: '2026-10-24T00:00:00Z',
  updatedAt: '2026-10-24T00:00:00Z',
}
export const knowledgePublished: KnowledgeArticle = {
  ...knowledgeDraft,
  status: 'PUBLISHED',
  version: 1,
  publishedRevision: 1,
  publishedSnapshot: {
    revision: 1,
    title: knowledgeDraft.title,
    keywords: [...knowledgeDraft.keywords],
    content: knowledgeDraft.content,
    publishedAt: '2026-10-24T00:01:00Z',
  },
}
export const knowledgeSaved: KnowledgeArticle = {
  ...knowledgePublished,
  version: 2,
  content: '新草稿：本店通常两日内送达。',
}
export const knowledgeRepublished: KnowledgeArticle = {
  ...knowledgeSaved,
  version: 3,
  publishedRevision: 2,
  publishedSnapshot: {
    ...knowledgePublished.publishedSnapshot!,
    revision: 2,
    content: knowledgeSaved.content,
    publishedAt: '2026-10-24T00:02:00Z',
  },
}
export const knowledgeOffline: KnowledgeArticle = {
  ...knowledgeRepublished,
  status: 'OFFLINE',
  version: 4,
  publishedRevision: null,
  publishedSnapshot: null,
}
export const knowledgeAudit: KnowledgeAudit = {
  id: 'opaque-audit-id-1',
  action: 'CREATED',
  actor: 'editor',
  version: 0,
  publishedRevision: null,
  title: knowledgeDraft.title,
  keywords: [...knowledgeDraft.keywords],
  content: knowledgeDraft.content,
  createdAt: knowledgeDraft.createdAt,
}
