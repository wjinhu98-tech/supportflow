import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ReviewPanel from './ReviewPanel.vue'
import type { AfterSaleTicket } from '../types'

const ticket: AfterSaleTicket = {
  id: '00000000-0000-4000-8000-000000000001',
  shopId: 100,
  orderNo: 'SF10001',
  reason: '损坏',
  status: 'PENDING_REVIEW',
  reviewer: null,
  reviewNote: null,
  createdAt: '2026-10-23T00:00:00Z',
  updatedAt: '2026-10-23T00:00:00Z',
  execution: null,
  eligibility: null,
}
const props = {
  tickets: [ticket],
  historyTickets: [],
  view: 'pending' as const,
  loading: false,
  submitting: false,
  error: null,
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = vi.fn()
  HTMLDialogElement.prototype.close = vi.fn()
})
describe('merchant review panel', () => {
  it('embeds the scoped workspace without modal or global-platform claims', () => {
    const view = mount(ReviewPanel, { props: { ...props, embedded: true, shopName: '一号店' } })
    expect(view.get('[data-testid="review-panel"]').element.tagName).toBe('SECTION')
    expect(HTMLDialogElement.prototype.showModal).not.toHaveBeenCalled()
    expect(view.find('.review-close').exists()).toBe(false)
    expect(view.text()).toContain('一号店')
    expect(view.text()).not.toContain('全平台')
    expect(view.text()).not.toContain('Java')
    view.unmount()
    expect(HTMLDialogElement.prototype.close).not.toHaveBeenCalled()
  })

  it('emits a decision only with an explicit valid audit note', async () => {
    const view = mount(ReviewPanel, { props: { ...props, embedded: true } })
    await view.get('[data-testid="review-note"]').setValue('  核实商品损坏  ')
    await view.get('[data-testid="approve-review"]').trigger('click')
    expect(view.emitted('decide')).toEqual([
      [{ id: ticket.id, decision: 'APPROVE', note: '核实商品损坏' }],
    ])
    view.unmount()
  })

  it('permits read refresh but prevents new business writes while an earlier result needs verification', async () => {
    const view = mount(ReviewPanel, { props: { ...props, embedded: true, writeBlocked: true } })
    expect(view.get('[data-testid="approve-review"]').attributes('disabled')).toBeDefined()
    expect(view.get('[data-testid="reject-review"]').attributes('disabled')).toBeDefined()
    expect(view.get('[data-testid="refresh-reviews"]').attributes('disabled')).toBeUndefined()
    await view.get('[data-testid="refresh-reviews"]').trigger('click')
    expect(view.emitted('refresh')).toEqual([['pending']])
    expect(view.emitted('decide')).toBeUndefined()
    view.unmount()
  })
})
