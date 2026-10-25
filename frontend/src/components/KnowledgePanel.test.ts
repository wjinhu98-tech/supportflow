import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import KnowledgePanel from './KnowledgePanel.vue'
import {
  knowledgeDraft as draft,
  knowledgeSaved as saved,
  knowledgeAudit as audit,
} from '../test/knowledgeFixtures'

const props = {
  shopName: '一号店',
  articles: [draft],
  selected: null,
  editing: false,
  title: '',
  keywords: '',
  content: '',
  loading: false,
  detailLoading: false,
  auditLoading: false,
  submitting: false,
  error: null,
  notice: null,
  needsVerification: false,
  dirty: false,
  hasUnpublishedChanges: false,
  more: false,
  audit: [],
  auditMore: false,
}
describe('KnowledgePanel', () => {
  it('keeps create, list navigation and cancellation as explicit UI events', async () => {
    const view = mount(KnowledgePanel, { props })
    await view.get('[data-testid="knowledge-new"]').trigger('click')
    await view.get('[data-testid="knowledge-open"]').trigger('click')
    expect(view.emitted('create')).toEqual([[]])
    expect(view.emitted('open')).toEqual([[draft.id]])
    expect(view.emitted('save')).toBeUndefined()
    view.unmount()
  })
  it('renders saved and published versions separately as pure text, never executing document HTML', () => {
    const text = '<img src=x onerror="alert(1)"><script>bad()</script>'
    const article = {
      ...saved,
      title: text,
      content: '新的草稿',
      publishedSnapshot: { ...saved.publishedSnapshot!, content: text },
    }
    const view = mount(KnowledgePanel, {
      props: {
        ...props,
        articles: [article],
        selected: article,
        editing: true,
        title: text,
        keywords: '配送',
        content: article.content,
        hasUnpublishedChanges: true,
        audit: [{ ...audit, content: text }],
      },
    })
    expect(view.find('img').exists()).toBe(false)
    expect(view.find('script').exists()).toBe(false)
    expect(view.get('[data-testid="knowledge-published-snapshot"]').text()).toContain(text)
    expect(view.get('[data-testid="knowledge-publication-note"]').text()).toContain('仍检索旧快照')
    expect(view.get('[data-testid="knowledge-audit"]').text()).toContain(text)
    expect(view.get('[data-testid="knowledge-version"]').text()).toContain('2')
    view.unmount()
  })
  it('retains field limits and exposes editable text through v-model events only', async () => {
    const view = mount(KnowledgePanel, { props: { ...props, editing: true } })
    expect(view.get('[data-testid="knowledge-title"]').attributes('maxlength')).toBe('120')
    expect(view.get('[data-testid="knowledge-content"]').attributes('maxlength')).toBe('4000')
    await view.get('[data-testid="knowledge-title"]').setValue('新标题')
    expect(view.emitted('update:title')).toEqual([['新标题']])
    await view.get('[data-testid="knowledge-cancel"]').trigger('click')
    expect(view.emitted('cancel')).toEqual([[]])
    expect(view.emitted('save')).toBeUndefined()
    view.unmount()
  })
  it('disables publish/offline until local edits are saved, and keeps reload available after conflict', () => {
    const view = mount(KnowledgePanel, {
      props: { ...props, selected: saved, editing: true, dirty: true, needsVerification: true },
    })
    for (const id of ['knowledge-save', 'knowledge-publish', 'knowledge-offline'])
      expect(view.get(`[data-testid="${id}"]`).attributes('disabled')).toBeDefined()
    expect(view.get('[data-testid="knowledge-reload"]').attributes('disabled')).toBeUndefined()
    expect(view.get('[data-testid="knowledge-cancel"]').attributes('disabled')).toBeUndefined()
    view.unmount()
  })
  it('locks all write/navigation/cancel controls while a mutation is pending', () => {
    const view = mount(KnowledgePanel, {
      props: {
        ...props,
        selected: saved,
        editing: true,
        submitting: true,
        hasUnpublishedChanges: true,
      },
    })
    for (const id of [
      'knowledge-new',
      'knowledge-open',
      'knowledge-save',
      'knowledge-publish',
      'knowledge-offline',
      'knowledge-cancel',
      'knowledge-reload',
    ])
      expect(view.get(`[data-testid="${id}"]`).attributes('disabled')).toBeDefined()
    view.unmount()
  })
  it('allows selecting an observed result when a lost create needs verification', () => {
    const view = mount(KnowledgePanel, {
      props: { ...props, editing: true, dirty: true, needsVerification: true },
    })
    expect(view.get('[data-testid="knowledge-open"]').attributes('disabled')).toBeUndefined()
    expect(view.get('[data-testid="knowledge-save"]').attributes('disabled')).toBeDefined()
    view.unmount()
  })
})
