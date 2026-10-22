import { mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AfterSaleEligibility, Order } from '../types'
import RefundDialog from './RefundDialog.vue'

const order: Order = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'COMPLETED',
  statusDescription: '已完成',
  totalAmount: 199.9,
  currency: 'CNY',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
}
const eligibility: AfterSaleEligibility = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'ELIGIBLE',
  canSubmit: true,
  message: '在售后期限内，请填写退款原因。',
  receivedAt: '2026-10-20T00:00:00Z',
  deadlineAt: '2026-11-04T00:00:00Z',
  checkedAt: '2026-10-21T00:00:00Z',
  windowDays: 15,
}
const props = {
  orders: [order],
  order,
  reason: '',
  eligibility,
  loading: false,
  submitting: false,
  error: null,
  more: false,
  ordersLoading: false,
}

beforeEach(() => {
  HTMLDialogElement.prototype.showModal = vi.fn()
  HTMLDialogElement.prototype.close = vi.fn()
})

describe('RefundDialog', () => {
  async function syncReason(view: VueWrapper) {
    const emitted = view.emitted('update:reason')?.at(-1)?.[0]
    if (typeof emitted === 'string') await view.setProps({ reason: emitted })
  }

  async function category(view: VueWrapper, name: string) {
    const label = view.findAll('label').find((item) => item.text() === name)
    expect(label, `missing reason category ${name}`).toBeDefined()
    await label!.get('input[type="radio"]').setValue(true)
    await syncReason(view)
  }

  async function detail(view: VueWrapper, label: string) {
    const select = view.findAll('select').at(-1)!
    const option = select.findAll('option').find((item) => item.text() === label)
    expect(option, `missing detail ${label}`).toBeDefined()
    await select.setValue(option!.element.value)
    await syncReason(view)
  }

  it('requires category and specific reason; selecting never submits automatically', async () => {
    const view = mount(RefundDialog, { props })
    expect(view.text()).toContain('不会实际返款')
    expect(view.text()).not.toMatch(/Java|SQL|Tool|PENDING_REVIEW/)
    expect(view.findAll('input[type="radio"]')).toHaveLength(3)
    expect(view.findAll('input[type="radio"]:checked')).toHaveLength(0)
    expect(view.find('textarea').exists()).toBe(false)
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await category(view, '商铺原因')
    expect(view.find('textarea').exists()).toBe(false)
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await detail(view, '商品质量问题')
    expect(view.emitted('update:reason')?.at(-1)).toEqual(['商铺原因：商品质量问题'])
    expect(view.emitted('submit')).toBeUndefined()
    await view.get('form').trigger('submit')
    expect(view.emitted('submit')).toEqual([[]])
  })

  it('clears old detail and free text when switching reason categories', async () => {
    const view = mount(RefundDialog, { props })
    await category(view, '个人原因')
    await detail(view, '不想要了')
    expect(view.emitted('update:reason')?.at(-1)).toEqual(['个人原因：不想要了'])
    await category(view, '商铺原因')
    expect(view.emitted('update:reason')?.at(-1)).toEqual([''])
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await category(view, '其他')
    await view.get('textarea').setValue('需补充说明')
    await syncReason(view)
    expect(view.emitted('update:reason')?.at(-1)).toEqual(['其他：需补充说明'])
    await category(view, '个人原因')
    expect(view.emitted('update:reason')?.at(-1)).toEqual([''])
    await category(view, '其他')
    expect((view.get('textarea').element as HTMLTextAreaElement).value).toBe('')
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(view.emitted('submit')).toBeUndefined()
  })

  it('accepts free text only under Other and keeps the complete reason within 500 characters', async () => {
    const view = mount(RefundDialog, { props })
    await category(view, '其他')
    const textarea = view.get('textarea')
    expect(textarea.attributes('maxlength')).toBe('497')
    expect(textarea.attributes('required')).toBeDefined()
    await textarea.setValue('   ')
    await syncReason(view)
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await textarea.setValue('原'.repeat(497))
    await syncReason(view)
    expect(view.emitted('update:reason')?.at(-1)).toEqual([`其他：${'原'.repeat(497)}`])
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    await textarea.setValue('原'.repeat(498))
    await syncReason(view)
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(view.emitted('submit')).toBeUndefined()
  })

  it('clears all selections when choosing a different order', async () => {
    const view = mount(RefundDialog, { props })
    await category(view, '商铺原因')
    await detail(view, '商品质量问题')
    const other = { ...order, orderNo: 'SF10002' }
    await view.setProps({ order: other, eligibility: { ...eligibility, orderNo: other.orderNo } })
    await syncReason(view)
    expect(view.emitted('update:reason')?.at(-1)).toEqual([''])
    expect(view.findAll('input[type="radio"]:checked')).toHaveLength(0)
    expect(view.find('textarea').exists()).toBe(false)
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('renders server expiry and blocks a filled refund form', async () => {
    const view = mount(RefundDialog, {
      props: {
        ...props,
        eligibility: {
          ...eligibility,
          status: 'EXPIRED',
          canSubmit: false,
          message: '已超过确认收货后15天的申请期限。',
        },
      },
    })
    await category(view, '个人原因')
    await detail(view, '不想要了')
    expect(view.text()).toContain('已超过确认收货后15天')
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await view.get('form').trigger('submit')
    expect(view.emitted('submit')).toBeUndefined()
  })

  it('allows missing receipt with a clear manual verification explanation', async () => {
    const view = mount(RefundDialog, {
      props: {
        ...props,
        eligibility: {
          ...eligibility,
          status: 'REQUIRES_REVIEW',
          receivedAt: null,
          deadlineAt: null,
          message: '缺少收货时间，提交后由客服核实售后期限。',
        },
      },
    })
    await category(view, '个人原因')
    await detail(view, '不想要了')
    expect(view.text()).toContain('客服核实售后期限')
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    expect(view.text()).not.toContain('申请截止：')
  })

  it('blocks escape, editing and repeat confirmation while submitting', async () => {
    const view = mount(RefundDialog, { props })
    await category(view, '其他')
    await view.get('textarea').setValue('补充原因')
    await syncReason(view)
    await view.setProps({ submitting: true })
    await view.get('dialog').trigger('cancel')
    await view.get('form').trigger('submit')
    expect(view.emitted('close')).toBeUndefined()
    expect(view.emitted('submit')).toBeUndefined()
    expect(view.get('textarea').attributes('disabled')).toBeDefined()
    expect(view.get('select').attributes('disabled')).toBeDefined()
    expect(
      view.findAll('input[type="radio"]').every((input) => input.element.matches(':disabled')),
    ).toBe(true)
  })
})
