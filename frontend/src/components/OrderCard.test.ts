import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Order } from '../types'
import OrderCard from './OrderCard.vue'

const order: Order = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'SHIPPED',
  statusDescription: '已发货',
  totalAmount: 199.9,
  currency: 'CNY',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
  receivedAt: null,
}

describe('OrderCard history safety', () => {
  it('offers only a fresh consultation, never a receipt mutation, on a historical snapshot', async () => {
    const wrapper = mount(OrderCard, { props: { order, historical: true } })
    expect(wrapper.text()).toContain('查询最新订单')
    expect(wrapper.findAll('button')).toHaveLength(1)
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('select')).toEqual([[order]])
    expect(wrapper.emitted('confirmReceipt')).toBeUndefined()
  })

  it('retains the explicit receipt action for current shipped orders', async () => {
    const wrapper = mount(OrderCard, { props: { order } })
    const receipt = wrapper.findAll('button').find((button) => button.text() === '确认收货')!
    await receipt.trigger('click')
    expect(wrapper.emitted('confirmReceipt')).toEqual([[order]])
  })

  it('does not let a busy historical card dispatch a new consultation', async () => {
    const wrapper = mount(OrderCard, { props: { order, historical: true, busy: true } })
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
  })
})
