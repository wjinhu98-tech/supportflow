import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import KnowledgeSources from './KnowledgeSources.vue'
import type { ChatKnowledge } from '../types'

enableAutoUnmount(afterEach)

const knowledge: ChatKnowledge = {
  status: 'ANSWERED',
  sources: [
    { sourceId: 'kb-gift', version: 'v1', title: '礼盒与贺卡', content: '礼盒可搭配贺卡。' },
  ],
}

afterEach(() => vi.unstubAllGlobals())

describe('KnowledgeSources', () => {
  it('shows the title and version while keeping source text collapsed by default', async () => {
    const wrapper = mount(KnowledgeSources, { props: { knowledge }, attachTo: document.body })
    const toggle = wrapper.get('[data-testid="knowledge-source-toggle"]')
    const content = wrapper.get('[data-testid="knowledge-source-content"]')
    expect(toggle.text()).toContain('礼盒与贺卡')
    expect(toggle.text()).toContain('版本 v1')
    expect(toggle.element.tagName).toBe('BUTTON')
    expect(toggle.attributes('type')).toBe('button')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.attributes('aria-controls')).toBe(content.attributes('id'))
    expect(content.isVisible()).toBe(false)
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(content.isVisible()).toBe(true)
    expect(content.text()).toContain('礼盒可搭配贺卡。')
    await toggle.trigger('click')
    expect(content.isVisible()).toBe(false)
  })

  it('renders source text literally and never makes external links or executes markup', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const markup = '<img src=x onerror=alert(1)><script>alert(1)</script> https://example.com'
    const wrapper = mount(KnowledgeSources, {
      props: {
        knowledge: {
          status: 'ANSWERED',
          sources: [{ ...knowledge.sources[0]!, title: markup, content: markup }],
        },
        historical: true,
      },
    })
    await wrapper.get('[data-testid="knowledge-source-toggle"]').trigger('click')
    expect(wrapper.findAll('img,script,a,iframe')).toHaveLength(0)
    expect(wrapper.get('[data-testid="knowledge-source-content"]').text()).toContain(markup)
    expect(wrapper.get('[data-testid="knowledge-historical-snapshot"]').text()).toContain(
      '历史引用快照',
    )
    expect(fetch).not.toHaveBeenCalled()
  })

  it('keeps expansions per source and per reply and resets when a historical snapshot replaces data', async () => {
    const two: ChatKnowledge = {
      status: 'ANSWERED',
      sources: [
        knowledge.sources[0]!,
        { ...knowledge.sources[0]!, sourceId: 'kb-wash', title: '洗护说明' },
      ],
    }
    const wrapper = mount(KnowledgeSources, { props: { knowledge: two }, attachTo: document.body })
    const other = mount(KnowledgeSources, { props: { knowledge: two }, attachTo: document.body })
    await wrapper.findAll('[data-testid="knowledge-source-toggle"]')[0]!.trigger('click')
    expect(
      wrapper
        .findAll('[data-testid="knowledge-source-content"]')
        .map((content) => content.isVisible()),
    ).toEqual([true, false])
    expect(
      other
        .findAll('[data-testid="knowledge-source-content"]')
        .every((content) => !content.isVisible()),
    ).toBe(true)
    await wrapper.setProps({ historical: true })
    expect(
      wrapper
        .findAll('[data-testid="knowledge-source-content"]')
        .every((content) => !content.isVisible()),
    ).toBe(true)
  })

  it('assigns distinct accessible content IDs to different replies in the same application', () => {
    const wrapper = mount({
      components: { KnowledgeSources },
      setup: () => ({ knowledge }),
      template:
        '<div><KnowledgeSources :knowledge="knowledge" /><KnowledgeSources :knowledge="knowledge" /></div>',
    })
    const ids = wrapper
      .findAll('[data-testid="knowledge-source-content"]')
      .map((content) => content.attributes('id'))
    expect(ids).toHaveLength(2)
    expect(new Set(ids).size).toBe(2)
  })

  it.each(['NO_MATCH', 'UNAVAILABLE'] as const)('renders no source cards for %s', (status) => {
    const wrapper = mount(KnowledgeSources, { props: { knowledge: { status, sources: [] } } })
    expect(wrapper.find('[data-testid="knowledge-sources"]').exists()).toBe(false)
  })
})
