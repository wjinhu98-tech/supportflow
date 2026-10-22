import type {
  AfterSaleEligibility,
  AfterSaleExecution,
  AfterSaleTicket,
  ChatResponse,
  CsrfResponse,
  Order,
} from '../types'

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
    value.totalAmount >= 0
  )
}

function parseChat(value: unknown, shopId: string): ChatResponse {
  if (
    !isRecord(value) ||
    typeof value.reply !== 'string' ||
    !['TEXT', 'ORDER_DETAILS', 'ORDER_SELECTION', 'AFTER_SALE_FORM'].includes(String(value.type)) ||
    !Array.isArray(value.orders) ||
    !value.orders.every(validOrder) ||
    value.orders.some((order) => order.shopId !== Number(shopId)) ||
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
  return value as unknown as ChatResponse
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

  async pendingReviews(signal?: AbortSignal): Promise<AfterSaleTicket[]> {
    const data = await this.queued(() =>
      this.request('/api/v1/reviews/after-sales', { method: 'GET' }, signal),
    )
    if (
      !Array.isArray(data) ||
      !data.every(isTicket) ||
      data.some((ticket) => ticket.status !== 'PENDING_REVIEW')
    ) {
      throw new ApiError('审核列表格式不正确。', 0, 'INVALID_RESPONSE')
    }
    return data.map(normalizeTicket)
  }

  async reviewHistory(signal?: AbortSignal): Promise<AfterSaleTicket[]> {
    const data = await this.queued(() =>
      this.request('/api/v1/reviews/after-sales/history', { method: 'GET' }, signal),
    )
    return parseTickets(data)
  }

  async ownedAfterSales(shopId: string, signal?: AbortSignal): Promise<AfterSaleTicket[]> {
    if (!validShopId(shopId)) throw new ApiError('店铺 ID 必须为有效的正整数。')
    const data = await this.queued(() =>
      this.request(`/api/v1/shops/${shopId}/after-sales`, { method: 'GET' }, signal),
    )
    return parseTickets(data, shopId)
  }

  async retryExecution(id: string, signal?: AbortSignal): Promise<AfterSaleTicket> {
    if (!reviewIdPattern.test(id)) throw new ApiError('售后单号格式不正确。')
    return this.queued(async () => {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const csrf = await this.getCsrf(signal)
      const data = await this.request(
        `/api/v1/reviews/after-sales/${id}/execution/retry`,
        {
          method: 'POST',
          headers: { [csrf.headerName]: csrf.token },
        },
        signal,
      )
      if (!isTicket(data) || data.id !== id || data.status !== 'APPROVED' || !data.execution) {
        throw new ApiError('重试结果格式不正确，请刷新处理进度后核对。', 0, 'INVALID_RESPONSE')
      }
      return normalizeTicket(data)
    })
  }

  async decideReview(
    id: string,
    decision: 'APPROVE' | 'REJECT',
    note: string,
    signal?: AbortSignal,
  ): Promise<AfterSaleTicket> {
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
        `/api/v1/reviews/after-sales/${id}/decision`,
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

  private async request(path: string, init: RequestInit, signal?: AbortSignal): Promise<unknown> {
    const deadline = AbortSignal.timeout(25_000)
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
          code === 'BUSINESS_RULE_VIOLATION' &&
          serverMessage &&
          serverMessage.length <= 500
            ? serverMessage
            : response.status === 404 && code === 'REVIEW_NOT_FOUND'
              ? '未找到该售后申请，请刷新审核列表后重试。'
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
