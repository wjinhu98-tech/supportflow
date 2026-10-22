import { effectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AfterSaleEligibility, AfterSaleTicket, ChatResponse, Order } from '../types'

const mocks = vi.hoisted(() => ({
  authenticate: vi.fn(),
  chat: vi.fn(),
  pendingReviews: vi.fn(),
  decideReview: vi.fn(),
  reviewHistory: vi.fn(),
  retryExecution: vi.fn(),
  ownedAfterSales: vi.fn(),
  afterSaleEligibility: vi.fn(),
  applyAfterSale: vi.fn(),
}))
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    ApiClient: class {
      authenticate = mocks.authenticate
      chat = mocks.chat
      pendingReviews = mocks.pendingReviews
      decideReview = mocks.decideReview
      reviewHistory = mocks.reviewHistory
      retryExecution = mocks.retryExecution
      ownedAfterSales = mocks.ownedAfterSales
      afterSaleEligibility = mocks.afterSaleEligibility
      applyAfterSale = mocks.applyAfterSale
    },
  }
})
import { ApiError } from '../api/client'
import { useSupportDesk } from './useSupportDesk'

const order: Order = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'SHIPPED',
  statusDescription: '已发货',
  totalAmount: 199.9,
  currency: 'CNY',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
}
const selection = (orders = [order], nextAfterId: number | null = null): ChatResponse => ({
  reply: '请选择订单',
  type: 'ORDER_SELECTION',
  orders,
  nextAfterId,
})
const textReply: ChatResponse = { reply: '你好', type: 'TEXT', orders: [], nextAfterId: null }
const ticket: AfterSaleTicket = {
  id: 'a5be2bac-e14a-4810-959e-c1b7f3be24e7',
  shopId: 100,
  orderNo: 'SF10001',
  reason: '商品破损',
  status: 'PENDING_REVIEW',
  reviewer: null,
  reviewNote: null,
  createdAt: '2026-10-20T00:00:00Z',
  updatedAt: '2026-10-20T00:00:00Z',
  execution: null,
  eligibility: null,
}
const eligible: AfterSaleEligibility = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'ELIGIBLE',
  canSubmit: true,
  message: '订单在售后期限内，请填写退款原因。',
  receivedAt: '2026-10-20T00:00:00Z',
  deadlineAt: '2026-11-04T00:00:00Z',
  checkedAt: '2026-10-21T00:00:00Z',
  windowDays: 15,
}
const reviewedTicket: AfterSaleTicket = {
  ...ticket,
  status: 'APPROVED',
  reviewer: 'reviewer',
  reviewNote: '核对订单后批准',
}
const decision = { id: ticket.id, decision: 'APPROVE' as const, note: reviewedTicket.reviewNote! }
const scopes: ReturnType<typeof effectScope>[] = []
const createDesk = () => {
  const scope = effectScope()
  scopes.push(scope)
  return scope.run(useSupportDesk)!
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

beforeEach(() => {
  window.history.replaceState(null, '', '/?shopId=100')
  localStorage.clear()
  sessionStorage.clear()
  mocks.authenticate.mockReset().mockResolvedValue(undefined)
  mocks.chat.mockReset().mockResolvedValue(selection())
  mocks.pendingReviews.mockReset().mockResolvedValue([ticket])
  mocks.decideReview.mockReset().mockResolvedValue(reviewedTicket)
  mocks.reviewHistory.mockReset().mockResolvedValue([reviewedTicket])
  mocks.retryExecution.mockReset()
  mocks.ownedAfterSales.mockReset().mockResolvedValue([reviewedTicket])
  mocks.afterSaleEligibility.mockReset().mockResolvedValue(eligible)
  mocks.applyAfterSale.mockReset().mockResolvedValue({ ...ticket, eligibility: eligible })
})
afterEach(() => {
  for (const scope of scopes.splice(0)) scope.stop()
})

describe('useSupportDesk', () => {
  it('opens the server selected refund form and checks eligibility without submitting', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.chat.mockResolvedValueOnce({ ...textReply, type: 'AFTER_SALE_FORM', orders: [order] })
    await desk.sendMessage('将该订单退款')
    expect(desk.refundOpen.value).toBe(true)
    expect(desk.refundOrder.value).toEqual(order)
    expect(desk.refundEligibility.value).toEqual(eligible)
    expect(mocks.afterSaleEligibility).toHaveBeenCalledWith(
      '100',
      order.orderNo,
      expect.any(AbortSignal),
    )
    expect(mocks.applyAfterSale).not.toHaveBeenCalled()
    desk.refundReason.value = '商品破损'
    desk.closeRefundForm()
    expect(desk.refundReason.value).toBe('')
    expect(mocks.applyAfterSale).not.toHaveBeenCalled()
  })

  it('requires a new order choice when the server reports an ambiguous refund target', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    desk.selectedOrder.value = order
    mocks.chat.mockResolvedValueOnce({ ...textReply, type: 'AFTER_SALE_FORM', orders: [] })
    await desk.sendMessage('申请退款')
    expect(desk.refundOrder.value).toBeNull()
    expect(mocks.afterSaleEligibility).not.toHaveBeenCalled()
    expect(mocks.applyAfterSale).not.toHaveBeenCalled()
  })

  it.each(['close', 'shop switch', 'logout'] as const)(
    'ignores a late eligibility result after %s',
    async (transition) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      const pending = deferred<AfterSaleEligibility>()
      mocks.afterSaleEligibility.mockReturnValueOnce(pending.promise)
      const opening = desk.openRefundForm(order)
      const signal = mocks.afterSaleEligibility.mock.calls[0]?.[2] as AbortSignal
      if (transition === 'close') desk.closeRefundForm()
      else if (transition === 'shop switch') await desk.changeShop('101')
      else desk.disconnect()
      pending.resolve(eligible)
      await opening
      expect(signal.aborted).toBe(true)
      expect(desk.refundOpen.value).toBe(false)
      expect(desk.refundEligibility.value).toBeNull()
      expect(mocks.applyAfterSale).not.toHaveBeenCalled()
    },
  )

  it('does not attach an old eligibility response to a newly chosen order', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<AfterSaleEligibility>()
    mocks.afterSaleEligibility.mockReturnValueOnce(pending.promise)
    const first = desk.openRefundForm(order)
    const other = { ...order, orderNo: 'SF10002' }
    const otherEligibility = {
      ...eligible,
      orderNo: other.orderNo,
      status: 'EXPIRED' as const,
      canSubmit: false,
    }
    mocks.afterSaleEligibility.mockResolvedValueOnce(otherEligibility)
    await desk.chooseRefundOrder(other)
    pending.resolve(eligible)
    await first
    expect(desk.refundOrder.value).toEqual(other)
    expect(desk.refundEligibility.value).toEqual(otherEligibility)
  })

  it.each(['EXPIRED', 'INELIGIBLE'] as const)(
    'prevents a %s refund despite a filled reason',
    async (status) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      mocks.afterSaleEligibility.mockResolvedValueOnce({ ...eligible, status, canSubmit: false })
      await desk.openRefundForm(order)
      desk.refundReason.value = '商品破损'
      await desk.submitRefund()
      expect(mocks.applyAfterSale).not.toHaveBeenCalled()
    },
  )

  it('submits a manually reviewable request only on confirmation, trims reason and prevents double clicks', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const manual: AfterSaleEligibility = {
      ...eligible,
      status: 'REQUIRES_REVIEW',
      receivedAt: null,
      deadlineAt: null,
    }
    mocks.afterSaleEligibility.mockResolvedValueOnce(manual)
    await desk.openRefundForm(order)
    desk.refundReason.value = '   '
    await desk.submitRefund()
    expect(mocks.applyAfterSale).not.toHaveBeenCalled()
    desk.refundReason.value = '  商品破损  '
    const pending = deferred<AfterSaleTicket>()
    mocks.applyAfterSale.mockReturnValueOnce(pending.promise)
    const submitting = desk.submitRefund()
    await desk.submitRefund()
    desk.clearConversation()
    expect(desk.refundSubmitting.value).toBe(true)
    expect(mocks.applyAfterSale).toHaveBeenCalledTimes(1)
    expect(mocks.applyAfterSale).toHaveBeenCalledWith(
      '100',
      order.orderNo,
      '商品破损',
      expect.any(AbortSignal),
    )
    pending.resolve({ ...ticket, eligibility: manual })
    await submitting
    expect(desk.refundOpen.value).toBe(false)
    expect(desk.ownedAfterSales.value).toHaveLength(1)
    expect(desk.messages.value.at(-1)?.text).toContain('核实收货时间和售后期限')
  })

  it('preserves the reason and reports a server deadline conflict without replaying', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.openRefundForm(order)
    desk.refundReason.value = '商品破损'
    mocks.applyAfterSale.mockRejectedValueOnce(
      new ApiError('已超过售后申请期限', 409, 'BUSINESS_RULE_VIOLATION'),
    )
    await desk.submitRefund()
    expect(desk.refundError.value).toBe('已超过售后申请期限')
    expect(desk.refundReason.value).toBe('商品破损')
    expect(desk.refundOpen.value).toBe(true)
    expect(mocks.applyAfterSale).toHaveBeenCalledTimes(1)
    expect(desk.ownedAfterSales.value).toEqual([])
  })

  it('discards a late refund submission response after changing shop', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.openRefundForm(order)
    desk.refundReason.value = '商品破损'
    const pending = deferred<AfterSaleTicket>()
    mocks.applyAfterSale.mockReturnValueOnce(pending.promise)
    const submitting = desk.submitRefund()
    await desk.changeShop('101')
    pending.resolve({ ...ticket, eligibility: eligible })
    await submitting
    expect(desk.ownedAfterSales.value).toEqual([])
    expect(desk.messages.value).toEqual([])
    expect(desk.refundOpen.value).toBe(false)
  })

  it('loads buyer progress from the current shop without requiring review access', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.loadOwnedAfterSales()
    expect(mocks.ownedAfterSales).toHaveBeenCalledWith('100', expect.any(AbortSignal))
    expect(desk.ownedAfterSales.value).toEqual([reviewedTicket])
    expect(desk.afterSalesLoading.value).toBe(false)
    expect(mocks.pendingReviews).not.toHaveBeenCalled()
  })

  it.each(['logout', 'shop switch'] as const)(
    'discards an old buyer progress result after %s',
    async (transition) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      const pending = deferred<AfterSaleTicket[]>()
      mocks.ownedAfterSales.mockReturnValueOnce(pending.promise)
      const request = desk.loadOwnedAfterSales()
      if (transition === 'logout') desk.disconnect()
      else await desk.changeShop('101')
      pending.resolve([reviewedTicket])
      await request
      expect(desk.ownedAfterSales.value).toEqual([])
      expect(desk.afterSalesLoading.value).toBe(false)
    },
  )

  it('retains a successful decision in progress history without triggering execution retry', async () => {
    const desk = createDesk()
    await desk.connect('reviewer', 'test')
    await desk.submitReview(decision)
    expect(desk.reviewHistoryTickets.value).toEqual([reviewedTicket])
    expect(mocks.retryExecution).not.toHaveBeenCalled()
    await desk.loadReviews('history')
    expect(desk.reviewView.value).toBe('history')
    expect(mocks.reviewHistory).toHaveBeenCalledTimes(1)
  })

  it('retries only an explicitly selected retryable failed task and prevents double clicks', async () => {
    const desk = createDesk()
    await desk.connect('reviewer', 'test')
    const failed: AfterSaleTicket = {
      ...reviewedTicket,
      execution: {
        status: 'FAILED',
        amount: 199.9,
        currency: 'CNY',
        attemptCount: 1,
        maxAttempts: 5,
        nextAttemptAt: null,
        completedAt: null,
        reference: null,
        lastError: '渠道暂不可用',
        retryable: true,
      },
    }
    mocks.reviewHistory.mockResolvedValueOnce([failed])
    await desk.loadReviews('history')
    expect(mocks.retryExecution).not.toHaveBeenCalled()
    const pending = deferred<AfterSaleTicket>()
    mocks.retryExecution.mockReturnValueOnce(pending.promise)
    const request = desk.retryExecution(failed.id)
    await desk.retryExecution(failed.id)
    expect(mocks.retryExecution).toHaveBeenCalledTimes(1)
    const result: AfterSaleTicket = {
      ...failed,
      execution: { ...failed.execution!, status: 'PENDING', retryable: false },
    }
    pending.resolve(result)
    await request
    expect(desk.reviewHistoryTickets.value).toEqual([result])
    expect(desk.reviewSubmitting.value).toBe(false)
    await desk.retryExecution(failed.id)
    expect(mocks.retryExecution).toHaveBeenCalledTimes(1)
  })

  it('preserves a failed task on retry errors without replaying it', async () => {
    const desk = createDesk()
    await desk.connect('reviewer', 'test')
    const failed: AfterSaleTicket = {
      ...reviewedTicket,
      execution: {
        status: 'FAILED',
        amount: 199.9,
        currency: 'CNY',
        attemptCount: 1,
        maxAttempts: 5,
        nextAttemptAt: null,
        completedAt: null,
        reference: null,
        lastError: '渠道暂不可用',
        retryable: true,
      },
    }
    mocks.reviewHistory.mockResolvedValueOnce([failed])
    await desk.loadReviews('history')
    mocks.retryExecution.mockRejectedValueOnce(new ApiError('状态已变更，请刷新', 409))
    await desk.retryExecution(failed.id)
    expect(desk.reviewHistoryTickets.value).toEqual([failed])
    expect(desk.reviewError.value).toBe('状态已变更，请刷新')
    expect(mocks.retryExecution).toHaveBeenCalledTimes(1)
    expect(desk.reviewSubmitting.value).toBe(false)
  })

  it('clearing chat resets a cancelled progress load without keeping its late response', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<AfterSaleTicket[]>()
    mocks.ownedAfterSales.mockReturnValueOnce(pending.promise)
    const request = desk.loadOwnedAfterSales()
    desk.clearConversation()
    expect(desk.afterSalesLoading.value).toBe(false)
    pending.resolve([reviewedTicket])
    await request
    expect(desk.ownedAfterSales.value).toEqual([])
    await desk.loadOwnedAfterSales()
    expect(desk.ownedAfterSales.value).toEqual([reviewedTicket])
  })
  it('initializes from shop context without treating it as authenticated identity', () => {
    window.history.replaceState(null, '', '/?shopId=101')
    const desk = createDesk()
    expect(desk.shopId.value).toBe('101')
    expect(desk.connected.value).toBe(false)
    expect(desk.orders.value).toEqual([])
  })

  it('reports invalid shop links and falls back to a safe context', () => {
    window.history.replaceState(null, '', '/?shopId=../../')
    const desk = createDesk()
    expect(desk.shopId.value).toBe('100')
    expect(desk.error.value).toContain('无效')
  })

  it('loads current-shop orders after authentication without writing credentials to storage', async () => {
    const desk = createDesk()
    expect(await desk.connect('buyer', 'public-test-only')).toBe(true)
    expect(desk.orders.value).toEqual([order])
    expect(mocks.chat).toHaveBeenCalledWith('100', '我的订单', undefined, expect.any(AbortSignal))
    expect(desk.messages.value).toHaveLength(0)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('uses the returned opaque cursor, not page number arithmetic, and deduplicates', async () => {
    const desk = createDesk()
    mocks.chat
      .mockResolvedValueOnce(selection([order], 7347))
      .mockResolvedValueOnce(selection([order, { ...order, orderNo: 'SF10002' }]))
    await desk.connect('buyer', 'test')
    await desk.loadOrders(true)
    expect(mocks.chat.mock.calls[1]?.[1]).toBe('我的订单 7347')
    expect(desk.orders.value.map((item) => item.orderNo)).toEqual(['SF10001', 'SF10002'])
    expect(desk.nextAfterId.value).toBeNull()
  })

  it('does not request additional pages when there is no next cursor', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.loadOrders(true)
    expect(mocks.chat).toHaveBeenCalledTimes(1)
  })

  it('selects an order and carries it on subsequent messages', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.chat.mockResolvedValue({ ...textReply, type: 'ORDER_DETAILS', orders: [order] })
    await desk.selectOrder(order)
    await desk.sendMessage('发货了吗')
    expect(mocks.chat.mock.calls[1]?.slice(0, 3)).toEqual(['100', '咨询此订单', 'SF10001'])
    expect(mocks.chat.mock.calls[2]?.slice(0, 3)).toEqual(['100', '发货了吗', 'SF10001'])
    expect(desk.messages.value.map((item) => item.role)).toEqual([
      'user',
      'assistant',
      'user',
      'assistant',
    ])
  })

  it('refuses selecting a card from another shop', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.selectOrder({ ...order, shopId: 101 })
    expect(desk.selectedOrder.value).toBeNull()
    expect(mocks.chat).toHaveBeenCalledTimes(1)
  })

  it('resets order and conversation context when changing shop', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.chat.mockResolvedValueOnce({ ...textReply, type: 'ORDER_DETAILS', orders: [order] })
    await desk.selectOrder(order)
    mocks.chat.mockResolvedValueOnce(selection([{ ...order, shopId: 101 }]))
    await desk.changeShop('101')
    expect(desk.selectedOrder.value).toBeNull()
    expect(desk.messages.value).toHaveLength(0)
    expect(desk.orders.value[0]?.shopId).toBe(101)
    expect(new URL(window.location.href).searchParams.get('shopId')).toBe('101')
  })

  it('ignores a late chat result after switching shops', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ChatResponse>()
    mocks.chat.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(selection([]))
    const oldRequest = desk.sendMessage('你好')
    await desk.changeShop('101')
    pending.resolve(textReply)
    await oldRequest
    expect(desk.messages.value).toHaveLength(0)
    expect(desk.busy.value).toBe(false)
  })

  it('ignores a late order-list result after disconnecting', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ChatResponse>()
    mocks.chat.mockReturnValueOnce(pending.promise)
    const oldRequest = desk.loadOrders()
    desk.disconnect()
    pending.resolve(selection())
    await oldRequest
    expect(desk.connected.value).toBe(false)
    expect(desk.orders.value).toHaveLength(0)
  })

  it('prevents double submissions while a message is in flight', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ChatResponse>()
    mocks.chat.mockReturnValueOnce(pending.promise)
    const first = desk.sendMessage('第一条')
    await desk.sendMessage('第二条')
    expect(desk.messages.value).toHaveLength(1)
    pending.resolve(textReply)
    await first
    expect(desk.messages.value).toHaveLength(2)
  })

  it('clears sensitive UI state on a 401 while preserving a readable login error', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.chat.mockRejectedValueOnce(new ApiError('重新连接', 401))
    await desk.sendMessage('你好')
    expect(desk.connected.value).toBe(false)
    expect(desk.orders.value).toHaveLength(0)
    expect(desk.messages.value).toHaveLength(0)
    expect(desk.username.value).toBe('')
    expect(desk.loginError.value).toBe('重新连接')
  })

  it('does not pretend a 403 is successful, and does not resend automatically', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.chat.mockRejectedValueOnce(new ApiError('权限不足', 403))
    await desk.sendMessage('你好')
    expect(desk.error.value).toBe('权限不足')
    expect(desk.connected.value).toBe(true)
    expect(mocks.chat).toHaveBeenCalledTimes(2)
  })

  it('clearing local conversation aborts a pending reply and selected context', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ChatResponse>()
    mocks.chat.mockReturnValueOnce(pending.promise)
    const oldRequest = desk.selectOrder(order)
    desk.clearConversation()
    pending.resolve(textReply)
    await oldRequest
    expect(desk.messages.value).toHaveLength(0)
    expect(desk.selectedOrder.value).toBeNull()
    expect(desk.orders.value).toHaveLength(1)
  })

  it('retains the current shop on an invalid switch attempt', async () => {
    const desk = createDesk()
    await desk.changeShop('0')
    expect(desk.shopId.value).toBe('100')
    expect(desk.error.value).toContain('正整数')
  })

  it('does not authenticate again concurrently', async () => {
    const desk = createDesk()
    const pending = deferred<void>()
    mocks.authenticate.mockReturnValueOnce(pending.promise)
    const first = desk.connect('buyer', 'test')
    expect(await desk.connect('other', 'test')).toBe(false)
    pending.resolve()
    await first
    expect(desk.username.value).toBe('buyer')
  })

  it('does not retain a late authentication after disconnect', async () => {
    const desk = createDesk()
    const pending = deferred<void>()
    mocks.authenticate.mockReturnValueOnce(pending.promise)
    const request = desk.connect('buyer', 'test')
    desk.disconnect()
    pending.resolve()
    expect(await request).toBe(false)
    expect(desk.connected.value).toBe(false)
  })

  it('loads the review queue and settles its loading state', async () => {
    const desk = createDesk()
    await desk.connect('reviewer', 'test')
    const pending = deferred<AfterSaleTicket[]>()
    mocks.pendingReviews.mockReturnValueOnce(pending.promise)
    const request = desk.loadReviews()
    expect(desk.reviewLoading.value).toBe(true)
    expect(desk.reviewError.value).toBeNull()
    pending.resolve([ticket])
    await request
    expect(mocks.pendingReviews).toHaveBeenCalledWith(expect.any(AbortSignal))
    expect(desk.reviewTickets.value).toEqual([ticket])
    expect(desk.reviewLoading.value).toBe(false)
  })

  it('shows review access denial without disconnecting the buyer or retrying', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.pendingReviews.mockRejectedValueOnce(new ApiError('没有审核权限', 403))
    await desk.loadReviews()
    expect(desk.reviewError.value).toBe('没有审核权限')
    expect(desk.reviewLoading.value).toBe(false)
    expect(desk.reviewTickets.value).toEqual([])
    expect(desk.connected.value).toBe(true)
    expect(desk.username.value).toBe('buyer')
    expect(mocks.pendingReviews).toHaveBeenCalledTimes(1)
  })

  it('removes only the successfully reviewed item from the current pending queue', async () => {
    const desk = createDesk()
    await desk.connect('reviewer', 'test')
    const otherTicket = {
      ...ticket,
      id: 'b5be2bac-e14a-4810-959e-c1b7f3be24e7',
      orderNo: 'SF10002',
    }
    mocks.pendingReviews.mockResolvedValueOnce([ticket, otherTicket])
    await desk.loadReviews()
    await desk.submitReview(decision)
    expect(mocks.decideReview).toHaveBeenCalledWith(
      ticket.id,
      'APPROVE',
      decision.note,
      expect.any(AbortSignal),
    )
    expect(desk.reviewTickets.value).toEqual([otherTicket])
    expect(desk.reviewSubmitting.value).toBe(false)
    expect(desk.reviewError.value).toBeNull()
  })

  it('keeps a failed decision visible without assuming success or automatically resubmitting', async () => {
    const desk = createDesk()
    await desk.connect('reviewer', 'test')
    await desk.loadReviews()
    mocks.decideReview.mockRejectedValueOnce(new ApiError('服务暂不可用，请刷新后核对状态', 503))
    await desk.submitReview(decision)
    expect(desk.reviewTickets.value).toEqual([ticket])
    expect(desk.reviewError.value).toBe('服务暂不可用，请刷新后核对状态')
    expect(desk.reviewSubmitting.value).toBe(false)
    expect(desk.connected.value).toBe(true)
    expect(mocks.decideReview).toHaveBeenCalledTimes(1)
  })

  it.each(['logout', 'shop switch'] as const)(
    'ignores late review-list results after %s without overwriting the new queue',
    async (transition) => {
      const desk = createDesk()
      await desk.connect('reviewer', 'test')
      const pending = deferred<AfterSaleTicket[]>()
      mocks.pendingReviews.mockReturnValueOnce(pending.promise)
      const oldRequest = desk.loadReviews()
      const oldSignal = mocks.pendingReviews.mock.calls[0]?.[0] as AbortSignal
      if (transition === 'logout') {
        desk.disconnect()
        await desk.connect('new-reviewer', 'test')
      } else {
        await desk.changeShop('101')
      }
      const currentTicket = { ...ticket, id: 'c5be2bac-e14a-4810-959e-c1b7f3be24e7' }
      mocks.pendingReviews.mockResolvedValueOnce([currentTicket])
      await desk.loadReviews()
      expect(oldSignal.aborted).toBe(true)
      pending.resolve([ticket])
      await oldRequest
      expect(desk.reviewTickets.value).toEqual([currentTicket])
      expect(desk.reviewLoading.value).toBe(false)
      expect(desk.reviewError.value).toBeNull()
    },
  )

  it.each(['logout', 'shop switch'] as const)(
    'ignores a late decision after %s instead of removing an item from the new context',
    async (transition) => {
      const desk = createDesk()
      await desk.connect('reviewer', 'test')
      await desk.loadReviews()
      const pending = deferred<AfterSaleTicket>()
      mocks.decideReview.mockReturnValueOnce(pending.promise)
      const oldRequest = desk.submitReview(decision)
      const oldSignal = mocks.decideReview.mock.calls[0]?.[3] as AbortSignal
      if (transition === 'logout') {
        desk.disconnect()
        await desk.connect('new-reviewer', 'test')
      } else {
        await desk.changeShop('101')
      }
      await desk.loadReviews()
      expect(oldSignal.aborted).toBe(true)
      pending.resolve(reviewedTicket)
      await oldRequest
      expect(desk.reviewTickets.value).toEqual([ticket])
      expect(desk.reviewSubmitting.value).toBe(false)
      expect(desk.reviewError.value).toBeNull()
    },
  )

  it('clearing chat resets an aborted review load and allows a fresh load to complete', async () => {
    const desk = createDesk()
    await desk.connect('reviewer', 'test')
    const previous = deferred<AfterSaleTicket[]>()
    const current = deferred<AfterSaleTicket[]>()
    mocks.pendingReviews.mockReturnValueOnce(previous.promise).mockReturnValueOnce(current.promise)
    const previousRequest = desk.loadReviews()
    const previousSignal = mocks.pendingReviews.mock.calls[0]?.[0] as AbortSignal
    desk.clearConversation()
    expect(previousSignal.aborted).toBe(true)
    expect(desk.reviewLoading.value).toBe(false)
    expect(desk.connected.value).toBe(true)
    expect(desk.orders.value).toEqual([order])
    const currentRequest = desk.loadReviews()
    expect(mocks.pendingReviews).toHaveBeenCalledTimes(2)
    expect(desk.reviewLoading.value).toBe(true)
    previous.resolve([ticket])
    await previousRequest
    expect(desk.reviewTickets.value).toEqual([])
    expect(desk.reviewLoading.value).toBe(true)
    current.resolve([ticket])
    await currentRequest
    expect(desk.reviewTickets.value).toEqual([ticket])
    expect(desk.reviewLoading.value).toBe(false)
  })

  it('does not clear chat or abort an in-flight business decision', async () => {
    const desk = createDesk()
    await desk.connect('reviewer', 'test')
    await desk.loadReviews()
    mocks.chat.mockResolvedValueOnce({ ...textReply, type: 'ORDER_DETAILS', orders: [order] })
    await desk.selectOrder(order)
    const messages = [...desk.messages.value]
    const pending = deferred<AfterSaleTicket>()
    mocks.decideReview.mockReturnValueOnce(pending.promise)
    const request = desk.submitReview(decision)
    const signal = mocks.decideReview.mock.calls[0]?.[3] as AbortSignal
    expect(desk.reviewSubmitting.value).toBe(true)
    desk.clearConversation()
    expect(signal.aborted).toBe(false)
    expect(desk.reviewSubmitting.value).toBe(true)
    expect(desk.messages.value).toEqual(messages)
    expect(desk.selectedOrder.value).toEqual(order)
    await desk.submitReview(decision)
    expect(mocks.decideReview).toHaveBeenCalledTimes(1)
    pending.resolve(reviewedTicket)
    await request
    expect(desk.reviewTickets.value).toEqual([])
    expect(desk.reviewSubmitting.value).toBe(false)
  })
})
