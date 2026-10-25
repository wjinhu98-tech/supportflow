import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ConversationContextNotice from './ConversationContextNotice.vue'
import type { ConversationContextRef } from '../types'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())
const orderContext: ConversationContextRef = {
  kind: 'ORDER',
  orderNo: 'SF10001',
  sourceIds: [],
  expiresAt: '2026-10-25T00:30:00Z',
}
const props = { context: orderContext, pendingReset: false, expired: false, disabled: false }

describe('ConversationContextNotice', () => {
  it.each([
    null,
    { kind: 'NONE', orderNo: null, sourceIds: [], expiresAt: null } as ConversationContextRef,
  ])('hides an empty topic: %s', (context) => {
    const wrapper = mount(ConversationContextNotice, { props: { ...props, context } })
    expect(wrapper.find('[data-testid="conversation-context"]').exists()).toBe(false)
  })

  it('shows an order reference without treating it as a fact or automatically running any action', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(ConversationContextNotice, { props })
    expect(wrapper.get('[data-testid="context-kind"]').text()).toContain('SF10001')
    expect(wrapper.text()).toContain('重新核实')
    expect(wrapper.findAll('form,a,input')).toHaveLength(0)
    await wrapper.get('[data-testid="context-clear"]').trigger('click')
    expect(wrapper.emitted('clear')).toEqual([[]])
    expect(fetch).not.toHaveBeenCalled()
  })

  it('shows knowledge source count without displaying or refetching source content', () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(ConversationContextNotice, {
      props: {
        ...props,
        context: {
          kind: 'KNOWLEDGE',
          orderNo: null,
          sourceIds: ['kb-gift', 'kb-wash'],
          expiresAt: orderContext.expiresAt,
        },
      },
    })
    expect(wrapper.get('[data-testid="context-kind"]').text()).toContain('2')
    expect(wrapper.text()).toContain('当前发布')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('explains a pending clear will take effect with the next successful message', () => {
    const wrapper = mount(ConversationContextNotice, {
      props: { ...props, context: null, pendingReset: true },
    })
    expect(wrapper.get('[data-testid="context-kind"]').text()).toContain('待发送')
    expect(wrapper.text()).toContain('下一条消息不沿用上次话题')
    expect(wrapper.text()).toContain('发送成功后生效')
    expect(wrapper.text()).toContain('刷新或切换会话不会保存')
    expect(wrapper.find('[data-testid="context-clear"]').exists()).toBe(false)
  })

  it('marks expired pointers and prevents clearing during another operation', async () => {
    const wrapper = mount(ConversationContextNotice, {
      props: { ...props, expired: true, disabled: true },
    })
    expect(wrapper.get('[data-testid="context-kind"]').text()).toContain('已过期')
    expect(wrapper.get('[data-testid="context-clear"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="context-clear"]').trigger('click')
    expect(wrapper.emitted('clear')).toBeUndefined()
  })

  it('renders pointer text literally without markup execution or links', () => {
    const markup = '<img/src=x><script>alert(1)</script>'
    const wrapper = mount(ConversationContextNotice, {
      props: { ...props, context: { ...orderContext, orderNo: markup } },
    })
    expect(wrapper.get('[data-testid="context-kind"]').text()).toContain(markup)
    expect(wrapper.findAll('img,script,a')).toHaveLength(0)
  })
})
