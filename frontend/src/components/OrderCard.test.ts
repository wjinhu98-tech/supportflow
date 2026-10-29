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
  it('shows purchase names, specifications and quantities before the order number', () => {
    const productOrder: Order = {
      ...order,
      items: [
        { productName: '无线耳机', specification: '白色', quantity: 1 },
        { productName: '耳机收纳盒', specification: null, quantity: 2 },
        { productName: '充电线', specification: '1米', quantity: 1 },
      ],
      itemKinds: 5,
    }
    const wrapper = mount(OrderCard, { props: { order: productOrder } })
    expect(
      wrapper.findAll('[data-testid="order-product-name"]').map((name) => name.text()),
    ).toEqual(['无线耳机', '耳机收纳盒', '充电线'])
    expect(wrapper.text()).toContain('白色 · 数量 1')
    expect(wrapper.text()).toContain('数量 2')
    expect(wrapper.text()).toContain('另有 2 种商品')
    expect(wrapper.text().indexOf('无线耳机')).toBeLessThan(wrapper.text().indexOf(order.orderNo))
    expect(wrapper.text()).toContain('合计')
    expect(wrapper.text()).not.toContain('null')
    expect(wrapper.get('.order-consult').attributes('aria-label')).toBe(
      `咨询此订单 ${order.orderNo}`,
    )
  })

  it('keeps a legacy card useful without adding a fictitious product', () => {
    const wrapper = mount(OrderCard, { props: { order } })
    expect(wrapper.find('[data-testid="order-product-name"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('SF10001')
    expect(wrapper.text()).toContain('已发货')
    expect(wrapper.text()).not.toContain('另有')
    expect(wrapper.find('.order-product-image').exists()).toBe(false)
  })

  it('shows a lazy product image with a useful alt and labels the known demo asset', () => {
    const wrapper = mount(OrderCard, {
      props: {
        order: {
          ...order,
          items: [
            {
              productName: '无线耳机',
              specification: '黑色',
              quantity: 1,
              imageUrl: '/products/demo/black-earbuds.png',
            },
          ],
        },
      },
    })
    expect(wrapper.get('.order-product-image img').attributes()).toMatchObject({
      src: '/products/demo/black-earbuds.png',
      alt: '无线耳机',
      loading: 'lazy',
      decoding: 'async',
      width: '72',
      height: '72',
    })
    expect(wrapper.get('.order-product-demo').text()).toBe('演示图')
    expect(wrapper.find('.order-product-placeholder').exists()).toBe(false)
  })

  it('uses the same placeholder for missing and failed images while retaining purchase details', async () => {
    const productOrder: Order = {
      ...order,
      items: [
        {
          productName: '无线耳机',
          specification: '黑色',
          quantity: 2,
          imageUrl: '/products/demo/black-earbuds.png',
        },
        { productName: '耳机收纳盒', specification: null, quantity: 1 },
        { productName: '充电线', specification: '1米', quantity: 1, imageUrl: null },
      ],
    }
    const wrapper = mount(OrderCard, { props: { order: productOrder } })
    expect(wrapper.findAll('.order-product-placeholder')).toHaveLength(2)
    await wrapper.get('.order-product-image img').trigger('error')
    expect(wrapper.findAll('.order-product-placeholder')).toHaveLength(3)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('.order-product-demo').exists()).toBe(false)
    expect(wrapper.get('.order-product-placeholder').text()).toBe('暂无图片')
    expect(wrapper.text()).toContain('无线耳机')
    expect(wrapper.text()).toContain('黑色 · 数量 2')

    await wrapper.setProps({ order: { ...productOrder, orderNo: 'SF10002' } })
    expect(wrapper.get('.order-product-image img').attributes('src')).toBe(
      '/products/demo/black-earbuds.png',
    )
  })

  it('does not label other accepted image paths as the supplied demo assets', () => {
    const wrapper = mount(OrderCard, {
      props: {
        order: {
          ...order,
          items: [
            {
              productName: '用户商品',
              specification: null,
              quantity: 1,
              imageUrl: '/products/demo/customer-product.jpg',
            },
          ],
        },
      },
    })
    expect(wrapper.get('.order-product-image img').attributes('alt')).toBe('用户商品')
    expect(wrapper.find('.order-product-demo').exists()).toBe(false)
  })

  it('never loads an unsafe source even when mounted without API validation', () => {
    const wrapper = mount(OrderCard, {
      props: {
        order: {
          ...order,
          items: [
            {
              productName: '无线耳机',
              specification: null,
              quantity: 1,
              imageUrl: 'https://example.com/product.png',
            },
          ],
        },
      },
    })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('.order-product-placeholder').text()).toBe('暂无图片')
  })

  it('renders purchase text as text rather than HTML', () => {
    const wrapper = mount(OrderCard, {
      props: {
        order: {
          ...order,
          items: [
            {
              productName: '<img src=x onerror=alert(1)>',
              specification: '<b>白色</b>',
              quantity: 1,
              imageUrl: '/products/demo/black-earbuds.png',
            },
          ],
          itemKinds: 1,
        },
      },
    })
    expect(wrapper.findAll('img')).toHaveLength(1)
    expect(wrapper.get('img').attributes('src')).toBe('/products/demo/black-earbuds.png')
    expect(wrapper.get('img').attributes('alt')).toBe('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img[src="x"]').exists()).toBe(false)
    expect(wrapper.find('[onerror]').exists()).toBe(false)
    expect(wrapper.find('b').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.text()).toContain('<b>白色</b>')
  })

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
