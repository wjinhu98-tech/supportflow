import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Order } from '../types'
import OrderDeletionDialog from './OrderDeletionDialog.vue'
import OrderCard from './OrderCard.vue'

const order: Order = {
  shopId: 100,
  orderNo: 'SFDELETE01',
  status: 'COMPLETED',
  statusDescription: '已完成',
  totalAmount: 99,
  currency: 'CNY',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = vi.fn()
  HTMLDialogElement.prototype.close = vi.fn()
})

describe('OrderDeletionDialog', () => {
  it('requires an explicit second confirmation and explains retained historical records', async () => {
    const view = mount(OrderDeletionDialog, { props: { order, submitting: false, error: null } })
    expect(view.text()).toContain('历史聊天快照、商家记录及售后凭证会保留')
    expect(view.text()).toContain('有待处理售后的订单暂不能删除')
    expect(view.emitted('confirm')).toBeUndefined()
    await view.get('dialog').trigger('cancel')
    expect(view.emitted('close')).toEqual([[]])
    expect(view.emitted('confirm')).toBeUndefined()
    await view.get('[data-testid="confirm-order-deletion"]').trigger('click')
    expect(view.emitted('confirm')).toEqual([[]])
    view.unmount()
  })

  it('blocks dismissal and duplicate submits while waiting and displays a backend conflict', async () => {
    const view = mount(OrderDeletionDialog, { props: { order, submitting: true, error: null } })
    await view.get('dialog').trigger('cancel')
    await view.get('[data-testid="confirm-order-deletion"]').trigger('click')
    expect(view.emitted('confirm')).toBeUndefined()
    expect(view.emitted('close')).toBeUndefined()
    expect(
      view.findAll('button').every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true)
    await view.setProps({ submitting: false, error: '该订单仍有待处理售后，请处理完成后再删除。' })
    expect(view.get('[role="alert"]').text()).toContain('待处理售后')
    view.unmount()
  })
})

describe('OrderCard deletion action', () => {
  it.each(['COMPLETED', 'CANCELLED'])(
    'offers deletion only as an explicit action for a current %s order',
    async (status) => {
      const current = { ...order, status }
      const view = mount(OrderCard, { props: { order: current } })
      expect(view.emitted('deleteOrder')).toBeUndefined()
      await view.get(`[aria-label="删除订单 ${order.orderNo}"]`).trigger('click')
      expect(view.emitted('deleteOrder')).toEqual([[current]])
    },
  )

  it.each(['CREATED', 'PAID', 'SHIPPED'])(
    'does not offer deletion for a current %s order',
    (status) => {
      const view = mount(OrderCard, { props: { order: { ...order, status } } })
      expect(view.find(`[aria-label="删除订单 ${order.orderNo}"]`).exists()).toBe(false)
    },
  )

  it('does not offer mutation on historical cards and blocks consultation for a known deleted order', async () => {
    const view = mount(OrderCard, { props: { order, historical: true } })
    expect(view.find(`[aria-label="删除订单 ${order.orderNo}"]`).exists()).toBe(false)
    await view.setProps({ deleted: true })
    await view.get(`[aria-label="咨询此订单 ${order.orderNo}"]`).trigger('click')
    expect(view.emitted('select')).toBeUndefined()
    expect(view.text()).toContain('此订单已删除')
  })

  it('disables the deletion action when an after-sale is known to be pending', async () => {
    const view = mount(OrderCard, { props: { order, deletionBlocked: true } })
    const action = view.get(`[aria-label="删除订单 ${order.orderNo}"]`)
    await action.trigger('click')
    expect(action.attributes('disabled')).toBeDefined()
    expect(view.text()).toContain('售后处理中')
    expect(view.emitted('deleteOrder')).toBeUndefined()
  })
})
