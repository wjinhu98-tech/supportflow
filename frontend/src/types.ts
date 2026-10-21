/** Public backend DTOs only. Identity and internal order IDs are never UI input. */
export interface Order {
  shopId: number
  orderNo: string
  status: string
  statusDescription: string
  totalAmount: number
  currency: string
  createdAt: string
  updatedAt: string
}

export type ChatReplyType = 'TEXT' | 'ORDER_DETAILS' | 'ORDER_SELECTION'

export interface ChatResponse {
  reply: string
  type: ChatReplyType
  orders: Order[]
  nextAfterId: number | null
}

export interface CsrfResponse {
  headerName: string
  parameterName: string
  token: string
}

export interface ChatMessage {
  id: number
  role: 'assistant' | 'user'
  text: string
  createdAt: string
  type?: ChatReplyType
  orders?: Order[]
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
