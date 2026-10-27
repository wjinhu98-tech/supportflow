import type {
  AfterSaleEligibility,
  AfterSaleExecution,
  AfterSaleTicket,
  ChatResponse,
  ChatKnowledge,
  ConversationContextRef,
  Conversation,
  ConversationPage,
  ConversationMessages,
  ConversationMessagePage,
  ShopConversationWindow,
  ShopOption,
  StoredChatMessage,
  CsrfResponse,
  CurrentAccount,
  MerchantShop,
  KnowledgeArticle,
  KnowledgeInput,
  KnowledgePage,
  KnowledgeAuditPage,
  Order,
  ReceiptConfirmation,
} from '../types'
import { validKnowledgeArticle, validKnowledgeAudit, validKnowledgeInput } from './knowledge'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number = 0,
    readonly code = 'CLIENT_ERROR',
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function validShopId(value: string): boolean {
  return /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value))
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function validOrder(value: unknown): value is Order {
  if (!isRecord(value)) return false
  return (
    typeof value.shopId === 'number' &&
    Number.isSafeInteger(value.shopId) &&
    value.shopId > 0 &&
    ['orderNo', 'status', 'statusDescription', 'currency', 'createdAt', 'updatedAt'].every(
      (key) => typeof value[key] === 'string',
    ) &&
    typeof value.totalAmount === 'number' &&
    Number.isFinite(value.totalAmount) &&
    value.totalAmount >= 0 &&
    (value.receivedAt === undefined || value.receivedAt === null || isTimestamp(value.receivedAt))
  )
}

function validChatKnowledge(value: unknown): value is ChatKnowledge | null {
  if (value === null) return true
  if (
    !isRecord(value) ||
    typeof value.status !== 'string' ||
    !['ANSWERED', 'NO_MATCH', 'UNAVAILABLE'].includes(String(value.status)) ||
    !Array.isArray(value.sources) ||
    value.sources.length > 2 ||
    (value.status === 'ANSWERED' ? value.sources.length === 0 : value.sources.length !== 0)
  )
    return false
  const sourceIds = new Set<string>()
  return value.sources.every((source) => {
    if (
      !isRecord(source) ||
      typeof source.sourceId !== 'string' ||
      source.sourceId.trim() !== source.sourceId ||
      !/^[a-z][a-z0-9-]{1,63}$/.test(source.sourceId) ||
      sourceIds.has(source.sourceId) ||
      typeof source.version !== 'string' ||
      source.version.trim() !== source.version ||
      !/^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$/.test(source.version) ||
      typeof source.title !== 'string' ||
      !source.title.trim() ||
      source.title.length > 240 ||
      typeof source.content !== 'string' ||
      !source.content.trim() ||
      source.content.length > 16_384
    )
      return false
    sourceIds.add(source.sourceId)
    return true
  })
}

function isContextTimestamp(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const parts =
    /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.exec(
      value,
    )
  if (!parts || !Number.isFinite(Date.parse(value))) return false
  const year = Number(parts[1])
  const month = Number(parts[2])
  const day = Number(parts[3])
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return day <= days[month - 1]!
}

function validConversationContext(value: unknown): value is ConversationContextRef | null {
  if (value === null) return true
  if (
    !isRecord(value) ||
    typeof value.kind !== 'string' ||
    !['NONE', 'ORDER', 'KNOWLEDGE'].includes(value.kind) ||
    !Array.isArray(value.sourceIds) ||
    value.sourceIds.length > 2 ||
    value.sourceIds.some(
      (id) => typeof id !== 'string' || id.trim() !== id || !/^[a-z][a-z0-9-]{1,63}$/.test(id),
    ) ||
    new Set(value.sourceIds).size !== value.sourceIds.length
  )
    return false
  if (value.kind === 'NONE')
    return value.orderNo === null && value.sourceIds.length === 0 && value.expiresAt === null
  if (!isContextTimestamp(value.expiresAt)) return false
  if (value.kind === 'ORDER')
    return (
      typeof value.orderNo === 'string' &&
      value.orderNo.length > 0 &&
      value.orderNo.length <= 64 &&
      !/[\s\p{Cc}]/u.test(value.orderNo) &&
      value.sourceIds.length === 0
    )
  return value.orderNo === null && value.sourceIds.length >= 1
}

function parseChat(value: unknown, shopId: string): ChatResponse {
  if (
    !isRecord(value) ||
    typeof value.reply !== 'string' ||
    typeof value.type !== 'string' ||
    !['TEXT', 'ORDER_DETAILS', 'ORDER_SELECTION', 'AFTER_SALE_FORM'].includes(String(value.type)) ||
    !Array.isArray(value.orders) ||
    !value.orders.every(validOrder) ||
    value.orders.some((order) => order.shopId !== Number(shopId)) ||
    (Object.hasOwn(value, 'knowledge') && !validChatKnowledge(value.knowledge)) ||
    (Object.hasOwn(value, 'context') && !validConversationContext(value.context)) ||
    (value.knowledge != null &&
      (value.type !== 'TEXT' || value.orders.length !== 0 || value.nextAfterId !== null)) ||
    (value.type === 'AFTER_SALE_FORM' && (value.orders.length > 1 || value.nextAfterId !== null)) ||
    !(
      value.nextAfterId === null ||
      (typeof value.nextAfterId === 'number' &&
        Number.isSafeInteger(value.nextAfterId) &&
        value.nextAfterId > 0)
    )
  ) {
    throw new ApiError('服务返回的数据格式不正确，请稍后重试。', 0, 'INVALID_RESPONSE')
  }
  const response = value as unknown as ChatResponse
  return {
    ...response,
    orders: response.orders.map((order) => ({ ...order, receivedAt: order.receivedAt ?? null })),
    knowledge: response.knowledge
      ? {
          status: response.knowledge.status,
          sources: response.knowledge.sources.map(({ sourceId, version, title, content }) => ({
            sourceId,
            version,
            title,
            content,
          })),
        }
      : null,
    ...(Object.hasOwn(response, 'context')
      ? {
          context: response.context
            ? {
                kind: response.context.kind,
                orderNo: response.context.orderNo,
                sourceIds: [...response.context.sourceIds],
                expiresAt: response.context.expiresAt,
              }
            : null,
        }
      : {}),
  }
}

const reviewIdPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i

function nullableString(value: unknown): boolean {
  return value === null || typeof value === 'string'
}

function isTimestamp(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d\d-\d\dT/.test(value) &&
    Number.isFinite(Date.parse(value))
  )
}

function parseConversation(value: unknown, shopId: string): Conversation {
  if (
    !isRecord(value) ||
    typeof value.id !== 'string' ||
    !reviewIdPattern.test(value.id) ||
    value.shopId !== Number(shopId) ||
    !isTimestamp(value.createdAt) ||
    !isTimestamp(value.updatedAt)
  ) {
    throw new ApiError('无法读取当前店铺的咨询记录，请刷新后核实。', 0, 'INVALID_RESPONSE')
  }
  return value as unknown as Conversation
}

function parseStoredMessages(value: unknown, shopId: string): StoredChatMessage[] {
  if (!Array.isArray(value) || value.length > 40) {
    throw new ApiError('咨询记录格式不正确，请刷新后核实。', 0, 'INVALID_RESPONSE')
  }
  const ids = new Set<string>()
  return value.map((message) => {
    if (
      !isRecord(message) ||
      typeof message.id !== 'string' ||
      !reviewIdPattern.test(message.id) ||
      ids.has(message.id) ||
      !['USER', 'ASSISTANT'].includes(String(message.role)) ||
      typeof message.text !== 'string' ||
      !isTimestamp(message.createdAt) ||
      (message.role === 'USER' && message.response !== null)
    ) {
      throw new ApiError('咨询记录格式不正确，请刷新后核实。', 0, 'INVALID_RESPONSE')
    }
    ids.add(message.id)
    const response = message.role === 'ASSISTANT' ? parseChat(message.response, shopId) : null
    if (response && response.reply !== message.text) {
      throw new ApiError('咨询回复与记录不一致，请刷新后核实。', 0, 'INVALID_RESPONSE')
    }
    return { ...message, response } as unknown as StoredChatMessage
  })
}

function parseCursor(value: unknown): string | null {
  if (value !== null && (typeof value !== 'string' || !reviewIdPattern.test(value))) {
    throw new ApiError('咨询记录分页信息不正确，请刷新后核实。', 0, 'INVALID_RESPONSE')
  }
  return value as string | null
}

function isEligibility(value: unknown): value is AfterSaleEligibility {
  if (!isRecord(value)) return false
  const hasDates = isTimestamp(value.receivedAt) && isTimestamp(value.deadlineAt)
  return (
    typeof value.shopId === 'number' &&
    Number.isSafeInteger(value.shopId) &&
    value.shopId > 0 &&
    typeof value.orderNo === 'string' &&
    value.orderNo.length > 0 &&
    value.orderNo.length <= 64 &&
    !/[\s\p{Cc}]/u.test(value.orderNo) &&
    ['ELIGIBLE', 'REQUIRES_REVIEW', 'EXPIRED', 'INELIGIBLE'].includes(String(value.status)) &&
    typeof value.canSubmit === 'boolean' &&
    value.canSubmit === ['ELIGIBLE', 'REQUIRES_REVIEW'].includes(String(value.status)) &&
    typeof value.message === 'string' &&
    value.message.trim().length > 0 &&
    isTimestamp(value.checkedAt) &&
    typeof value.windowDays === 'number' &&
    Number.isSafeInteger(value.windowDays) &&
    value.windowDays > 0 &&
    (value.receivedAt === null || isTimestamp(value.receivedAt)) &&
    (value.deadlineAt === null || isTimestamp(value.deadlineAt)) &&
    (!['ELIGIBLE', 'EXPIRED'].includes(String(value.status)) || hasDates) &&
    (value.status !== 'REQUIRES_REVIEW' ||
      hasDates ||
      (value.receivedAt === null && value.deadlineAt === null))
  )
}

function isExecution(value: unknown): value is AfterSaleExecution {
  return (
    isRecord(value) &&
    ['PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'].includes(String(value.status)) &&
    typeof value.amount === 'number' &&
    Number.isFinite(value.amount) &&
    value.amount >= 0 &&
    value.currency === 'CNY' &&
    typeof value.attemptCount === 'number' &&
    Number.isSafeInteger(value.attemptCount) &&
    value.attemptCount >= 0 &&
    typeof value.maxAttempts === 'number' &&
    Number.isSafeInteger(value.maxAttempts) &&
    value.maxAttempts > 0 &&
    value.attemptCount <= value.maxAttempts &&
    nullableString(value.nextAttemptAt) &&
    nullableString(value.completedAt) &&
    nullableString(value.reference) &&
    nullableString(value.lastError) &&
    typeof value.retryable === 'boolean' &&
    (!value.retryable || (value.status === 'FAILED' && value.attemptCount < value.maxAttempts))
  )
}

function isTicket(value: unknown): value is AfterSaleTicket {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    reviewIdPattern.test(value.id) &&
    typeof value.orderNo === 'string' &&
    typeof value.reason === 'string' &&
    typeof value.shopId === 'number' &&
    Number.isSafeInteger(value.shopId) &&
    value.shopId > 0 &&
    ['PENDING_REVIEW', 'APPROVED', 'REJECTED'].includes(String(value.status)) &&
    typeof value.createdAt === 'string' &&
    typeof value.updatedAt === 'string' &&
    (value.reviewer === null || typeof value.reviewer === 'string') &&
    (value.reviewNote === null || typeof value.reviewNote === 'string') &&
    (value.eligibility === undefined ||
      value.eligibility === null ||
      (isEligibility(value.eligibility) &&
        value.eligibility.shopId === value.shopId &&
        value.eligibility.orderNo === value.orderNo)) &&
    (value.execution === undefined ||
      value.execution === null ||
      (value.status === 'APPROVED' && isExecution(value.execution)))
  )
}

function normalizeTicket(ticket: AfterSaleTicket): AfterSaleTicket {
  return { ...ticket, execution: ticket.execution ?? null, eligibility: ticket.eligibility ?? null }
}

function parseTickets(data: unknown, shopId?: string): AfterSaleTicket[] {
  if (
    !Array.isArray(data) ||
    !data.every(isTicket) ||
    (shopId !== undefined && data.some((ticket) => ticket.shopId !== Number(shopId)))
  ) {
    throw new ApiError('售后列表格式不正确。', 0, 'INVALID_RESPONSE')
  }
  return data.map(normalizeTicket)
}

const statusMessages: Record<number, string> = {
  401: '身份验证失败或已失效，请重新连接并检查用户名和密码。',
  403: '无权访问，或 CSRF 校验已失效。请确认账号权限，必要时重新连接。',
  404: '未找到当前店铺内属于你的这笔订单，请检查订单号。',
  409: '申请已处理，或当前订单状态不允许此操作。请刷新后检查。',
  429: '请求过于频繁，请稍后重试。',
  502: '服务暂时不可用，请稍后重试。',
  503: '服务暂时不可用，请稍后重试。',
}

/** One connected account. Credentials live only in memory; every POST obtains fresh CSRF. */
export class ApiClient {
  private readonly authorization: string
  private postQueue: Promise<void> = Promise.resolve()

  constructor(
    username: string,
    password: string,
    private readonly fetcher: typeof fetch = fetch,
  ) {
    if (!username.trim() || /[:\p{Cc}]/u.test(username) || !password) {
      throw new ApiError('请输入有效的用户名和密码；用户名不能包含冒号或控制字符。')
    }
    const bytes = new TextEncoder().encode(`${username}:${password}`)
    this.authorization = `Basic ${btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(''))}`
  }

  async authenticate(signal?: AbortSignal): Promise<void> {
    await this.getCsrf(signal)
  }

  async currentAccount(signal?: AbortSignal): Promise<CurrentAccount> {
    const data = await this.queued(() =>
      this.request('/api/v1/security/me', { method: 'GET' }, signal),
    )
    if (
      !isRecord(data) ||
      typeof data.username !== 'string' ||
      !data.username.trim() ||
      !['BUYER', 'MERCHANT_STAFF'].includes(String(data.accountType)) ||
      !isRecord(data.capabilities) ||
      typeof data.capabilities.reviewAfterSales !== 'boolean' ||
      typeof data.capabilities.manageKnowledge !== 'boolean'
    )
      throw new ApiError('无法确认账号身份，请重新登录。', 0, 'INVALID_RESPONSE')
    return {
      username: data.username,
      accountType: data.accountType,
      capabilities: {
        reviewAfterSales: data.capabilities.reviewAfterSales,
        manageKnowledge: data.capabilities.manageKnowledge,
      },
    } as CurrentAccount
  }

  async merchantShops(signal?: AbortSignal): Promise<MerchantShop[]> {
    const data = await this.queued(() =>
      this.request('/api/v1/merchant/shops', { method: 'GET' }, signal),
    )
    if (
      !Array.isArray(data) ||
      data.length > 500 ||
      !data.every(
        (shop) =>
          isRecord(shop) &&
          typeof shop.id === 'number' &&
          validShopId(String(shop.id)) &&
          typeof shop.merchantId === 'number' &&
          validShopId(String(shop.merchantId)) &&
          typeof shop.name === 'string' &&
          shop.name.trim().length > 0 &&
          typeof shop.merchantName === 'string' &&
          shop.merchantName.trim().length > 0,
      ) ||
      new Set(data.map((shop) => shop.id)).size !== data.length
    )
      throw new ApiError('无法读取已授权店铺，请重新登录或联系管理员。', 0, 'INVALID_RESPONSE')
    return data.map((shop) => ({
      id: shop.id,
      name: shop.name,
      merchantId: shop.merchantId,
      merchantName: shop.merchantName,
    }))
  }

  private merchantReviewPath(shopId: string): string {
    if (!validShopId(shopId)) throw new ApiError('请选择已授权的有效店铺。')
    return `/api/v1/merchant/shops/${shopId}/after-sales`
  }

  private knowledgePath(shopId: string, id?: string): string {
    if (!validShopId(shopId) || (id !== undefined && !reviewIdPattern.test(id)))
      throw new ApiError('请选择当前授权店铺的有效知识文档。')
    return `/api/v1/merchant/shops/${shopId}/knowledge${id === undefined ? '' : '/' + id}`
  }

  async knowledgeArticles(
    shopId: string,
    beforeId: string | null = null,
    signal?: AbortSignal,
  ): Promise<KnowledgePage> {
    const path = this.knowledgePath(shopId)
    const suffix = beforeId === null ? '' : `?beforeId=${this.validConversationId(beforeId)}`
    const data = await this.queued(() => this.request(path + suffix, { method: 'GET' }, signal))
    if (
      !isRecord(data) ||
      !Array.isArray(data.items) ||
      data.items.length > 20 ||
      !data.items.every((item) => validKnowledgeArticle(item, shopId)) ||
      new Set(data.items.map((item) => item.id)).size !== data.items.length
    )
      throw new ApiError('无法读取当前店铺知识列表，请刷新核实。', 0, 'INVALID_RESPONSE')
    return { items: data.items, nextBeforeId: parseCursor(data.nextBeforeId) }
  }

  async knowledgeArticle(
    shopId: string,
    id: string,
    signal?: AbortSignal,
  ): Promise<KnowledgeArticle> {
    const path = this.knowledgePath(shopId, id)
    const data = await this.queued(() => this.request(path, { method: 'GET' }, signal))
    if (!validKnowledgeArticle(data, shopId) || data.id !== id)
      throw new ApiError('知识文档与当前店铺不匹配，请重新读取。', 0, 'INVALID_RESPONSE')
    return data
  }

  async knowledgeAudit(
    shopId: string,
    id: string,
    beforeId: string | null = null,
    signal?: AbortSignal,
  ): Promise<KnowledgeAuditPage> {
    const path = this.knowledgePath(shopId, id)
    if (beforeId !== null && (!beforeId || beforeId.length > 200 || /[\p{Cc}]/u.test(beforeId)))
      throw new ApiError('操作记录分页信息不正确。')
    const suffix = beforeId === null ? '' : `?beforeId=${encodeURIComponent(beforeId)}`
    const data = await this.queued(() =>
      this.request(path + '/audit' + suffix, { method: 'GET' }, signal),
    )
    if (
      !isRecord(data) ||
      !Array.isArray(data.items) ||
      data.items.length > 20 ||
      !data.items.every(validKnowledgeAudit) ||
      new Set(data.items.map((item) => item.id)).size !== data.items.length ||
      !(
        data.nextBeforeId === null ||
        (typeof data.nextBeforeId === 'string' &&
          data.nextBeforeId.length > 0 &&
          data.nextBeforeId.length <= 200 &&
          !/[\p{Cc}]/u.test(data.nextBeforeId))
      )
    )
      throw new ApiError('知识操作记录格式不正确，请刷新核实。', 0, 'INVALID_RESPONSE')
    return { items: data.items, nextBeforeId: data.nextBeforeId }
  }

  async createKnowledgeArticle(
    shopId: string,
    input: KnowledgeInput,
    signal?: AbortSignal,
  ): Promise<KnowledgeArticle> {
    return this.knowledgeMutation(
      'POST',
      this.knowledgePath(shopId),
      shopId,
      input,
      undefined,
      signal,
    )
  }

  async saveKnowledgeArticle(
    shopId: string,
    id: string,
    expectedVersion: number,
    input: KnowledgeInput,
    signal?: AbortSignal,
  ): Promise<KnowledgeArticle> {
    this.validateKnowledgeVersion(expectedVersion)
    return this.knowledgeMutation(
      'PUT',
      this.knowledgePath(shopId, id),
      shopId,
      input,
      { id, expectedVersion },
      signal,
    )
  }

  async publishKnowledgeArticle(
    shopId: string,
    id: string,
    expectedVersion: number,
    signal?: AbortSignal,
  ): Promise<KnowledgeArticle> {
    this.validateKnowledgeVersion(expectedVersion)
    return this.knowledgeMutation(
      'POST',
      this.knowledgePath(shopId, id) + '/publish',
      shopId,
      undefined,
      { id, expectedVersion },
      signal,
    )
  }

  async offlineKnowledgeArticle(
    shopId: string,
    id: string,
    expectedVersion: number,
    signal?: AbortSignal,
  ): Promise<KnowledgeArticle> {
    this.validateKnowledgeVersion(expectedVersion)
    return this.knowledgeMutation(
      'POST',
      this.knowledgePath(shopId, id) + '/offline',
      shopId,
      undefined,
      { id, expectedVersion },
      signal,
    )
  }

  private validateKnowledgeVersion(version: number) {
    if (!Number.isSafeInteger(version) || version < 0)
      throw new ApiError('请重新读取文档版本后操作。')
  }

  private knowledgeMutation(
    method: 'POST' | 'PUT',
    path: string,
    shopId: string,
    input?: KnowledgeInput,
    target?: { id: string; expectedVersion: number },
    signal?: AbortSignal,
  ): Promise<KnowledgeArticle> {
    if (input !== undefined && !validKnowledgeInput(input))
      throw new ApiError('请填写有效标题、1–12 个关键词和正文，注意长度限制。')
    const body = {
      ...(target ? { expectedVersion: target.expectedVersion } : {}),
      ...(input
        ? {
            title: input.title.trim(),
            keywords: input.keywords.map((keyword) => keyword.trim()),
            content: input.content.trim(),
          }
        : {}),
    }
    return this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      try {
        const data = await this.request(
          path,
          {
            method,
            headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
            body: JSON.stringify(body),
          },
          signal,
        )
        if (
          !validKnowledgeArticle(data, shopId) ||
          (target && (data.id !== target.id || data.version !== target.expectedVersion + 1)) ||
          (!target && (data.status !== 'DRAFT' || data.version !== 0)) ||
          (path.endsWith('/publish') && data.status !== 'PUBLISHED') ||
          (path.endsWith('/offline') && data.status !== 'OFFLINE')
        )
          throw new ApiError(
            '操作结果暂时无法确认，请先重新读取当前店铺文档，不要重复提交。',
            0,
            'INVALID_RESPONSE',
          )
        return data
      } catch (failure) {
        if (failure instanceof ApiError && failure.status === 409)
          throw new ApiError(
            failure.code === 'KNOWLEDGE_LIMIT_REACHED'
              ? '本店发布文档已达到上限，请先下线不再需要的内容。'
              : '文档已被他人更新或状态已变化，请先重新读取再操作；本地输入尚未覆盖线上内容。',
            409,
            failure.code,
          )
        throw failure
      }
    })
  }

  async conversations(
    shopId: string,
    beforeId: string | null = null,
    signal?: AbortSignal,
  ): Promise<ConversationPage> {
    const path = this.conversationPath(shopId)
    const suffix = beforeId === null ? '' : `?beforeId=${this.validConversationId(beforeId)}`
    const data = await this.queued(() => this.request(path + suffix, { method: 'GET' }, signal))
    if (!isRecord(data) || !Array.isArray(data.conversations) || data.conversations.length > 20)
      throw new ApiError('咨询列表格式不正确，请刷新后核实。', 0, 'INVALID_RESPONSE')
    const conversations = data.conversations.map((item) => parseConversation(item, shopId))
    if (new Set(conversations.map((item) => item.id)).size !== conversations.length)
      throw new ApiError('咨询列表含有重复记录，请刷新后核实。', 0, 'INVALID_RESPONSE')
    return { conversations, nextBeforeId: parseCursor(data.nextBeforeId) }
  }

  async shops(signal?: AbortSignal): Promise<ShopOption[]> {
    const data = await this.queued(() => this.request('/api/v1/shops', { method: 'GET' }, signal))
    if (
      !Array.isArray(data) ||
      data.length > 50 ||
      data.some(
        (shop) =>
          !isRecord(shop) ||
          !Number.isSafeInteger(shop.shopId) ||
          Number(shop.shopId) <= 0 ||
          typeof shop.name !== 'string' ||
          !shop.name.trim() ||
          shop.name.length > 128,
      ) ||
      new Set(data.map((shop) => shop.shopId)).size !== data.length
    )
      throw new ApiError('店铺列表格式不正确。', 0, 'INVALID_RESPONSE')
    return data.map((shop) => ({ shopId: shop.shopId, name: shop.name }))
  }

  async openShopWindow(shopId: string, signal?: AbortSignal): Promise<Conversation> {
    const path = this.windowPath(shopId)
    return this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      return parseConversation(
        await this.request(
          path,
          {
            method: 'POST',
            headers: { [csrf.headerName]: csrf.token },
          },
          signal,
        ),
        shopId,
      )
    })
  }

  async shopWindowMessages(
    shopId: string,
    beforeId: string | null = null,
    signal?: AbortSignal,
  ): Promise<ShopConversationWindow> {
    const path = this.windowPath(shopId)
    const suffix = beforeId === null ? '' : `?beforeId=${this.validConversationId(beforeId)}`
    const data = await this.queued(() => this.request(path + suffix, { method: 'GET' }, signal))
    if (
      !isRecord(data) ||
      data.shopId !== Number(shopId) ||
      !validConversationContext(data.context)
    )
      throw new ApiError('店铺对话格式不正确。', 0, 'INVALID_RESPONSE')
    return {
      shopId: Number(shopId),
      conversation:
        data.conversation === null ? null : parseConversation(data.conversation, shopId),
      messages: parseStoredMessages(data.messages, shopId),
      nextBeforeId: parseCursor(data.nextBeforeId),
      context: data.context,
    }
  }

  async deleteShopWindow(
    shopId: string,
    conversationId: string,
    signal?: AbortSignal,
  ): Promise<void> {
    const path = `${this.windowPath(shopId)}/${this.validConversationId(conversationId)}`
    await this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      await this.request(
        path,
        { method: 'DELETE', headers: { [csrf.headerName]: csrf.token } },
        signal,
      )
    })
  }

  private windowPath(shopId: string): string {
    if (!validShopId(shopId)) throw new ApiError('店铺 ID 必须为有效的正整数。')
    return `/api/v1/shops/${shopId}/chat/window`
  }

  async createConversation(shopId: string, signal?: AbortSignal): Promise<Conversation> {
    const path = this.conversationPath(shopId)
    return this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      const data = await this.request(
        path,
        { method: 'POST', headers: { [csrf.headerName]: csrf.token } },
        signal,
      )
      return parseConversation(data, shopId)
    })
  }

  async conversationMessages(
    shopId: string,
    conversationId: string,
    beforeId: string | null = null,
    signal?: AbortSignal,
  ): Promise<ConversationMessagePage> {
    const path = `${this.conversationPath(shopId)}/${this.validConversationId(conversationId)}/messages`
    const suffix = beforeId === null ? '' : `?beforeId=${this.validConversationId(beforeId)}`
    const data = await this.queued(() => this.request(path + suffix, { method: 'GET' }, signal))
    if (!isRecord(data)) throw new ApiError('无法读取咨询记录。', 0, 'INVALID_RESPONSE')
    const conversation = parseConversation(data.conversation, shopId)
    if (conversation.id !== conversationId)
      throw new ApiError('咨询记录不匹配，请重新选择会话。', 0, 'INVALID_RESPONSE')
    return {
      conversation,
      messages: parseStoredMessages(data.messages, shopId),
      nextBeforeId: parseCursor(data.nextBeforeId),
    }
  }

  async sendConversationMessage(
    shopId: string,
    conversationId: string,
    clientMessageId: string,
    message: string,
    orderNo?: string,
    signal?: AbortSignal,
    resetContext = false,
  ): Promise<ConversationMessages> {
    const path = `${this.conversationPath(shopId)}/${this.validConversationId(conversationId)}/messages`
    this.validConversationId(clientMessageId)
    if (!message.trim() || message.length > 1000) throw new ApiError('请输入 1–1000 个字符的消息。')
    if (orderNo !== undefined) this.validateOrderTarget(shopId, orderNo)
    if (typeof resetContext !== 'boolean') throw new ApiError('清除话题参数必须为布尔值。')
    return this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      const data = await this.request(
        path,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
          body: JSON.stringify({
            clientMessageId,
            message,
            ...(orderNo ? { orderNo } : {}),
            ...(resetContext ? { resetContext: true } : {}),
          }),
        },
        signal,
        105_000, // The server's Agent deadline is at most 100s, including its bounded retries.
      )
      if (!isRecord(data))
        throw new ApiError('回复结果无法确认，请刷新咨询记录核实。', 0, 'INVALID_RESPONSE')
      const conversation = parseConversation(data.conversation, shopId)
      const messages = parseStoredMessages(data.messages, shopId)
      if (
        conversation.id !== conversationId ||
        messages.length !== 2 ||
        messages[0]?.role !== 'USER' ||
        messages[0].text !== message ||
        messages[1]?.role !== 'ASSISTANT'
      )
        throw new ApiError('回复结果无法确认，请刷新咨询记录核实。', 0, 'INVALID_RESPONSE')
      return { conversation, messages }
    })
  }

  private conversationPath(shopId: string): string {
    if (!validShopId(shopId)) throw new ApiError('店铺 ID 必须为有效的正整数。')
    return `/api/v1/shops/${shopId}/chat/conversations`
  }

  private validConversationId(id: string): string {
    if (!reviewIdPattern.test(id)) throw new ApiError('咨询记录编号格式不正确。')
    return id
  }

  async confirmReceipt(
    shopId: string,
    orderNo: string,
    signal?: AbortSignal,
  ): Promise<ReceiptConfirmation> {
    this.validateOrderTarget(shopId, orderNo)
    return this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      const data = await this.request(
        `/api/v1/shops/${shopId}/orders/${encodeURIComponent(orderNo)}/receipt-confirmation`,
        { method: 'POST', headers: { [csrf.headerName]: csrf.token } },
        signal,
      )
      if (
        !isRecord(data) ||
        !validOrder(data.order) ||
        data.order.shopId !== Number(shopId) ||
        data.order.orderNo !== orderNo ||
        data.order.status !== 'COMPLETED' ||
        !isTimestamp(data.order.receivedAt) ||
        typeof data.alreadyConfirmed !== 'boolean' ||
        !isEligibility(data.eligibility) ||
        data.eligibility.shopId !== Number(shopId) ||
        data.eligibility.orderNo !== orderNo ||
        data.eligibility.receivedAt !== data.order.receivedAt ||
        !isTimestamp(data.eligibility.deadlineAt)
      ) {
        throw new ApiError('无法确认收货结果，请先刷新订单核实状态。', 0, 'INVALID_RESPONSE')
      }
      return data as unknown as ReceiptConfirmation
    })
  }

  async afterSaleEligibility(
    shopId: string,
    orderNo: string,
    signal?: AbortSignal,
  ): Promise<AfterSaleEligibility> {
    this.validateOrderTarget(shopId, orderNo)
    const data = await this.queued(() =>
      this.request(
        `/api/v1/shops/${shopId}/orders/${encodeURIComponent(orderNo)}/after-sale-eligibility`,
        { method: 'GET' },
        signal,
      ),
    )
    if (!isEligibility(data) || data.shopId !== Number(shopId) || data.orderNo !== orderNo)
      throw new ApiError('无法确认该订单的售后期限，请稍后重试。', 0, 'INVALID_RESPONSE')
    return data
  }

  async applyAfterSale(
    shopId: string,
    orderNo: string,
    reason: string,
    signal?: AbortSignal,
  ): Promise<AfterSaleTicket> {
    this.validateOrderTarget(shopId, orderNo)
    const normalized = reason.trim()
    if (!normalized || normalized.length > 500) throw new ApiError('请填写 1–500 字的退款原因。')
    return this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      const data = await this.request(
        `/api/v1/shops/${shopId}/orders/${encodeURIComponent(orderNo)}/after-sales`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
          body: JSON.stringify({ reason: normalized }),
        },
        signal,
      )
      if (!isTicket(data) || data.shopId !== Number(shopId) || data.orderNo !== orderNo)
        throw new ApiError(
          '提交结果暂时无法确认，请先查看售后进度，勿重复提交。',
          0,
          'INVALID_RESPONSE',
        )
      return normalizeTicket(data)
    })
  }

  private validateOrderTarget(shopId: string, orderNo: string) {
    if (!validShopId(shopId) || !orderNo || orderNo.length > 64 || /[\s\p{Cc}]/u.test(orderNo))
      throw new ApiError('请先选择当前店铺的有效订单。')
  }

  async pendingReviews(shopId: string, signal?: AbortSignal): Promise<AfterSaleTicket[]> {
    const data = await this.queued(() =>
      this.request(this.merchantReviewPath(shopId), { method: 'GET' }, signal),
    )
    if (
      !Array.isArray(data) ||
      !data.every(isTicket) ||
      data.some((ticket) => ticket.status !== 'PENDING_REVIEW' || ticket.shopId !== Number(shopId))
    ) {
      throw new ApiError('审核列表格式不正确。', 0, 'INVALID_RESPONSE')
    }
    return data.map(normalizeTicket)
  }

  async reviewHistory(shopId: string, signal?: AbortSignal): Promise<AfterSaleTicket[]> {
    const data = await this.queued(() =>
      this.request(this.merchantReviewPath(shopId) + '/history', { method: 'GET' }, signal),
    )
    return parseTickets(data, shopId)
  }

  async ownedAfterSales(shopId: string, signal?: AbortSignal): Promise<AfterSaleTicket[]> {
    if (!validShopId(shopId)) throw new ApiError('店铺 ID 必须为有效的正整数。')
    const data = await this.queued(() =>
      this.request(`/api/v1/shops/${shopId}/after-sales`, { method: 'GET' }, signal),
    )
    return parseTickets(data, shopId)
  }

  async retryExecution(shopId: string, id: string, signal?: AbortSignal): Promise<AfterSaleTicket> {
    const path = this.merchantReviewPath(shopId)
    if (!reviewIdPattern.test(id)) throw new ApiError('售后单号格式不正确。')
    return this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      const data = await this.request(
        `${path}/${id}/execution/retry`,
        {
          method: 'POST',
          headers: { [csrf.headerName]: csrf.token },
        },
        signal,
      )
      if (
        !isTicket(data) ||
        data.shopId !== Number(shopId) ||
        data.id !== id ||
        data.status !== 'APPROVED' ||
        !data.execution
      ) {
        throw new ApiError('重试结果格式不正确，请刷新处理进度后核对。', 0, 'INVALID_RESPONSE')
      }
      return normalizeTicket(data)
    })
  }

  async decideReview(
    shopId: string,
    id: string,
    decision: 'APPROVE' | 'REJECT',
    note: string,
    signal?: AbortSignal,
  ): Promise<AfterSaleTicket> {
    const path = this.merchantReviewPath(shopId)
    if (
      !reviewIdPattern.test(id) ||
      !['APPROVE', 'REJECT'].includes(decision) ||
      !note.trim() ||
      note.length > 500
    ) {
      throw new ApiError('请填写有效的审核意见与决定。')
    }
    return this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      const data = await this.request(
        `${path}/${id}/decision`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
          body: JSON.stringify({ decision, note: note.trim() }),
        },
        signal,
      )
      if (
        !isTicket(data) ||
        data.id !== id ||
        data.shopId !== Number(shopId) ||
        data.status !== (decision === 'APPROVE' ? 'APPROVED' : 'REJECTED')
      ) {
        throw new ApiError('审核结果格式不正确。', 0, 'INVALID_RESPONSE')
      }
      return normalizeTicket(data)
    })
  }

  private queued<T>(action: () => Promise<T>): Promise<T> {
    const operation = this.postQueue.then(action)
    this.postQueue = operation.then(
      () => undefined,
      () => undefined,
    )
    return operation
  }

  async chat(
    shopId: string,
    message: string,
    orderNo?: string,
    signal?: AbortSignal,
  ): Promise<ChatResponse> {
    if (!validShopId(shopId)) throw new ApiError('店铺 ID 必须为有效的正整数。')
    if (!message.trim() || message.length > 1000) throw new ApiError('请输入 1–1000 个字符的消息。')
    if (orderNo !== undefined && (!orderNo || orderNo.length > 64 || /[\s\p{Cc}]/u.test(orderNo))) {
      throw new ApiError('订单号格式不正确。')
    }
    // Stateless Basic authentication can rotate the session/CSRF token after a request.
    // Serialize GET-token + POST pairs so overlapping list/chat actions cannot race.
    const operation = this.postQueue.then(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      const body = JSON.stringify({ message, ...(orderNo === undefined ? {} : { orderNo }) })
      const data = await this.request(
        `/api/v1/shops/${shopId}/chat/messages`,
        {
          method: 'POST',
          body,
          headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        },
        signal,
      )
      return parseChat(data, shopId)
    })
    // Keep the queue alive on failure, but never automatically replay a rejected POST.
    this.postQueue = operation.then(
      () => undefined,
      () => undefined,
    )
    return operation
  }

  private async getCsrf(signal?: AbortSignal): Promise<CsrfResponse> {
    const data = await this.request('/api/v1/security/csrf', { method: 'GET' }, signal)
    if (
      !isRecord(data) ||
      typeof data.token !== 'string' ||
      !data.token ||
      typeof data.headerName !== 'string' ||
      !/^X-[A-Za-z0-9-]+$/i.test(data.headerName) ||
      typeof data.parameterName !== 'string'
    ) {
      throw new ApiError('无法获取安全校验信息，请重新连接。', 0, 'INVALID_RESPONSE')
    }
    return data as unknown as CsrfResponse
  }

  private async request(
    path: string,
    init: RequestInit,
    signal?: AbortSignal,
    timeoutMillis = 25_000,
  ): Promise<unknown> {
    const deadline = AbortSignal.timeout(timeoutMillis)
    const requestSignal = signal ? AbortSignal.any([signal, deadline]) : deadline
    try {
      // Native browser fetch must not be invoked with the ApiClient instance as `this`.
      const fetchRequest = this.fetcher
      const response = await fetchRequest(path, {
        ...init,
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        signal: requestSignal,
        headers: { ...init.headers, Authorization: this.authorization, Accept: 'application/json' },
      })
      const contentType = response.headers.get('content-type') ?? ''
      if (response.status === 204 && response.ok) return undefined
      let data: unknown = null
      if (contentType.includes('application/json')) {
        try {
          data = await response.json()
        } catch {
          throw new ApiError('服务返回的数据格式不正确，请稍后重试。', 0, 'INVALID_RESPONSE')
        }
      }
      if (!response.ok) {
        const serverMessage =
          isRecord(data) && typeof data.message === 'string' ? data.message : null
        const code = isRecord(data) && typeof data.code === 'string' ? data.code : 'HTTP_ERROR'
        throw new ApiError(
          (response.status === 409 &&
          ['BUSINESS_RULE_VIOLATION', 'ORDER_RECEIPT_CONFLICT'].includes(code) &&
          serverMessage &&
          serverMessage.length <= 500
            ? serverMessage
            : response.status === 404 && code === 'REVIEW_NOT_FOUND'
              ? '未找到该售后申请，请刷新审核列表后重试。'
              : response.status === 404 && code === 'CONVERSATION_NOT_FOUND'
                ? '未找到该店铺对话，可能已被删除，请刷新后继续。'
                : response.status === 409 && code === 'CONVERSATION_CONFLICT'
                  ? '对话状态已变化，或仍有消息正在处理。请刷新后再试。'
                  : response.status === 503 && code === 'MODEL_UNAVAILABLE'
                    ? '智能服务暂时不可用，请稍后重试，或在订单页直接查看订单。'
                    : statusMessages[response.status]) ??
            (response.status === 400 && serverMessage
              ? serverMessage
              : '请求未能完成，请稍后重试。'),
          response.status,
          code,
        )
      }
      if (data === null)
        throw new ApiError('服务返回的数据格式不正确，请检查后端地址。', 0, 'INVALID_RESPONSE')
      return data
    } catch (error) {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      if (error instanceof ApiError) throw error
      if (deadline.aborted) throw new ApiError('请求超时，请稍后重试。', 0, 'TIMEOUT')
      throw new ApiError('无法连接后端服务，请检查后端是否启动及代理地址。', 0, 'NETWORK_ERROR')
    }
  }
}
