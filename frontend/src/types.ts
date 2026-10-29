/** Public backend DTOs only. Identity and internal order IDs are never UI input. */
export interface OrderItem {
  productName: string
  specification: string | null
  quantity: number
  imageUrl?: string | null
}

export interface Order {
  shopId: number
  orderNo: string
  status: string
  statusDescription: string
  totalAmount: number
  currency: string
  createdAt: string
  updatedAt: string
  receivedAt?: string | null
  /** Purchase-time snapshots; legacy orders and historical replies may omit these fields. */
  items?: OrderItem[]
  itemKinds?: number
}

export interface ReceiptConfirmation {
  order: Order & { receivedAt: string; status: 'COMPLETED' }
  alreadyConfirmed: boolean
  eligibility: AfterSaleEligibility
}

export type ChatReplyType = 'TEXT' | 'ORDER_DETAILS' | 'ORDER_SELECTION' | 'AFTER_SALE_FORM'
export type OrderSelectionIntent = 'ORDER_STATUS' | 'LOGISTICS'

/** Immutable evidence carried with this reply, never a URL to fetch current article content. */
export interface ChatKnowledgeSource {
  sourceId: string
  version: string
  title: string
  content: string
}

export interface ChatKnowledge {
  status: 'ANSWERED' | 'NO_MATCH' | 'UNAVAILABLE'
  sources: ChatKnowledgeSource[]
}

/** Server-issued subject references, not authorization or reusable business facts. */
export interface ConversationContextRef {
  kind: 'NONE' | 'ORDER' | 'KNOWLEDGE'
  orderNo: string | null
  sourceIds: string[]
  expiresAt: string | null
}

export interface ChatResponse {
  reply: string
  type: ChatReplyType
  orders: Order[]
  nextAfterId: number | null
  knowledge: ChatKnowledge | null
  /** Optional for historical replies written before controlled context was introduced. */
  context?: ConversationContextRef | null
  /** Read-only continuation for a current candidate list; never a replayable message. */
  selectionIntent?: OrderSelectionIntent | null
}

export interface CsrfResponse {
  headerName: string
  parameterName: string
  token: string
}

export interface ChatMessage {
  id: number | string
  role: 'assistant' | 'user'
  text: string
  createdAt: string
  type?: ChatReplyType
  orders?: Order[]
  knowledge?: ChatKnowledge | null
  context?: ConversationContextRef | null
  selectionIntent?: OrderSelectionIntent | null
  /** Restored order cards are immutable historical snapshots, never current business state. */
  historical?: boolean
  pending?: boolean
}

export interface Conversation {
  id: string
  shopId: number
  createdAt: string
  updatedAt: string
}

export interface ConversationPage {
  conversations: Conversation[]
  nextBeforeId: string | null
}

export interface ShopOption {
  shopId: number
  name: string
}

export interface ShopConversationWindow {
  shopId: number
  conversation: Conversation | null
  messages: StoredChatMessage[]
  nextBeforeId: string | null
  context: ConversationContextRef | null
}

export interface StoredChatMessage {
  id: string
  role: 'USER' | 'ASSISTANT'
  text: string
  createdAt: string
  response: ChatResponse | null
}

export interface ConversationMessages {
  conversation: Conversation
  messages: StoredChatMessage[]
}

export interface ConversationMessagePage extends ConversationMessages {
  nextBeforeId: string | null
}

export interface AfterSaleTicket {
  id: string
  shopId: number
  orderNo: string
  reason: string
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'
  reviewer: string | null
  reviewNote: string | null
  createdAt: string
  updatedAt: string
  execution: AfterSaleExecution | null
  eligibility: AfterSaleEligibility | null
}

export interface AfterSaleEligibility {
  shopId: number
  orderNo: string
  status: 'ELIGIBLE' | 'REQUIRES_REVIEW' | 'EXPIRED' | 'INELIGIBLE'
  canSubmit: boolean
  message: string
  receivedAt: string | null
  deadlineAt: string | null
  checkedAt: string
  windowDays: number
}

export interface AfterSaleExecution {
  status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'
  amount: number
  currency: 'CNY'
  attemptCount: number
  maxAttempts: number
  nextAttemptAt: string | null
  completedAt: string | null
  reference: string | null
  lastError: string | null
  retryable: boolean
}

/** Server-authenticated account type; never inferred from names or UI routes. */
export interface CurrentAccount {
  username: string
  accountType: 'BUYER' | 'MERCHANT_STAFF'
  capabilities: AccountCapabilities
}

export interface AccountCapabilities {
  reviewAfterSales: boolean
  manageKnowledge: boolean
}

export interface MerchantShop {
  id: number
  name: string
  merchantId: number
  merchantName: string
}

export interface KnowledgeInput {
  title: string
  keywords: string[]
  content: string
}

export interface KnowledgeSnapshot extends KnowledgeInput {
  revision: number
  publishedAt: string
}

export interface KnowledgeArticle extends KnowledgeInput {
  id: string
  shopId: number
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
  version: number
  publishedRevision: number | null
  publishedSnapshot: KnowledgeSnapshot | null
  createdAt: string
  updatedAt: string
}

export interface KnowledgePage {
  items: KnowledgeArticle[]
  nextBeforeId: string | null
}

export interface KnowledgeIndex {
  status: 'DISABLED' | 'EMPTY' | 'PARTIAL' | 'READY' | 'UNAVAILABLE' | 'BUSY'
  expectedChunks: number
  indexedChunks: number
  modelFingerprint: string | null
}

export interface KnowledgeImportPreview {
  title: string
  content: string
  characterCount: number
  byteCount: number
}

export interface KnowledgeImportResult {
  articleId: string
  reused: boolean
}

export interface KnowledgeAudit extends KnowledgeInput {
  id: string
  action: 'CREATED' | 'SAVED' | 'PUBLISHED' | 'OFFLINE'
  actor: string
  version: number
  publishedRevision: number | null
  createdAt: string
}

export interface KnowledgeAuditPage {
  items: KnowledgeAudit[]
  nextBeforeId: string | null
}
