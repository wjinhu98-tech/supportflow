import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Order, ReceiptConfirmation } from '../types'
import ReceiptConfirmationDialog from './ReceiptConfirmationDialog.vue'
import OrderCard from './OrderCard.vue'

const order: Order = {
  shopId: 100,
  orderNo: 'SFRECEIPT01',
  status: 'SHIPPED',
  statusDescription: '已发货',
  totalAmount: 99,
  currency: 'CNY',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  receivedAt: null,
}
const result: ReceiptConfirmation = {
  order: { ...order, status: 'COMPLETED', receivedAt: '2026-10-22T12:00:00Z' },
  alreadyConfirmed: false,
  eligibility: {
    shopId: 100,
    orderNo: order.orderNo,
    status: 'ELIGIBLE',
    canSubmit: true,
    message: '确认收货后15天内可申请售后。',
    receivedAt: '2026-10-22T12:00:00Z',
    deadlineAt: '2026-11-06T12:00:00Z',
    checkedAt: '2026-10-22T12:00:00Z',
    windowDays: 15,
  },
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = vi.fn()
  HTMLDialogElement.prototype.close = vi.fn()
})

describe('ReceiptConfirmationDialog', () => {
  it('requires a second explicit confirmation and allows cancellation without confirming', async () => {
    const view = mount(ReceiptConfirmationDialog, {
      props: { order, submitting: false, error: null, result: null },
    })
    expect(view.text()).toContain('请确认你已经收到')
    expect(view.text()).toContain('计算售后申请期限')
    expect(view.emitted('confirm')).toBeUndefined()
    await view.get('dialog').trigger('cancel')
    expect(view.emitted('close')).toEqual([[]])
    expect(view.emitted('confirm')).toBeUndefined()
    await view.get('[data-testid="confirm-receipt-submit"]').trigger('click')
    expect(view.emitted('confirm')).toEqual([[]])
  })

  it('prevents closing and repeat confirmation while the server request is pending', async () => {
    const view = mount(ReceiptConfirmationDialog, {
      props: { order, submitting: true, error: null, result: null },
    })
    await view.get('dialog').trigger('cancel')
    await view.get('[data-testid="confirm-receipt-submit"]').trigger('click')
    expect(view.emitted('confirm')).toBeUndefined()
    expect(view.emitted('close')).toBeUndefined()
    expect(
      view.findAll('button').every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true)
  })

  it('shows backend receipt and deadline without offering another confirmation', () => {
    const view = mount(ReceiptConfirmationDialog, {
      props: {
        order: result.order,
        submitting: false,
        error: null,
        result: { ...result, alreadyConfirmed: true },
      },
    })
    expect(view.text()).toContain('保留原确认时间')
    expect(view.text()).toContain('2026/10/22')
    expect(view.text()).toContain('2026/11/06')
    expect(view.text()).toContain('售后申请截止')
    expect(view.find('[data-testid="confirm-receipt-submit"]').exists()).toBe(false)
  })
})

describe('OrderCard receipt action', () => {
  it('offers confirmation only for shipped orders without a receipt timestamp', async () => {
    const view = mount(OrderCard, { props: { order } })
    await view.get('[aria-label="确认收货 SFRECEIPT01"]').trigger('click')
    expect(view.emitted('confirmReceipt')).toEqual([[order]])
    await view.setProps({ order: { ...order, status: 'COMPLETED', receivedAt: null } })
    expect(view.find('[aria-label="确认收货 SFRECEIPT01"]').exists()).toBe(false)
    await view.setProps({ order: result.order })
    expect(view.find('[aria-label="确认收货 SFRECEIPT01"]').exists()).toBe(false)
    expect(view.text()).toContain('确认收货：')
  })
})
