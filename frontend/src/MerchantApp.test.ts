import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MerchantApp from './MerchantApp.vue'

const mocks = vi.hoisted(() => ({
  authenticate: vi.fn(),
  logout: vi.fn(),
  dispose: vi.fn(),
  currentAccount: vi.fn(),
  supportSessions: vi.fn(),
  merchantSupportSnapshot: vi.fn(),
  claimSupport: vi.fn(),
  sendSupportMessage: vi.fn(),
  closeSupport: vi.fn(),
  merchantShops: vi.fn(),
  pendingReviews: vi.fn(),
  reviewHistory: vi.fn(),
  decideReview: vi.fn(),
  retryExecution: vi.fn(),
  knowledgeArticles: vi.fn(),
  knowledgeIndex: vi.fn(),
  warmKnowledgeIndex: vi.fn(),
  knowledgeArticle: vi.fn(),
  knowledgeAudit: vi.fn(),
  createKnowledgeArticle: vi.fn(),
  saveKnowledgeArticle: vi.fn(),
  publishKnowledgeArticle: vi.fn(),
  offlineKnowledgeArticle: vi.fn(),
}))
vi.mock('./api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/client')>()
  return {
    ...actual,
    ApiClient: class {
      authenticate = mocks.authenticate
      logout = mocks.logout
      dispose = mocks.dispose
      currentAccount = mocks.currentAccount
      supportSessions = mocks.supportSessions
      merchantSupportSnapshot = mocks.merchantSupportSnapshot
      claimSupport = mocks.claimSupport
      sendSupportMessage = mocks.sendSupportMessage
      closeSupport = mocks.closeSupport
      merchantShops = mocks.merchantShops
      pendingReviews = mocks.pendingReviews
      reviewHistory = mocks.reviewHistory
      decideReview = mocks.decideReview
      retryExecution = mocks.retryExecution
      knowledgeArticles = mocks.knowledgeArticles
      knowledgeIndex = mocks.knowledgeIndex
      warmKnowledgeIndex = mocks.warmKnowledgeIndex
      knowledgeArticle = mocks.knowledgeArticle
      knowledgeAudit = mocks.knowledgeAudit
      createKnowledgeArticle = mocks.createKnowledgeArticle
      saveKnowledgeArticle = mocks.saveKnowledgeArticle
      publishKnowledgeArticle = mocks.publishKnowledgeArticle
      offlineKnowledgeArticle = mocks.offlineKnowledgeArticle
    },
  }
})
beforeEach(() => {
  for (const mock of Object.values(mocks)) mock.mockReset()
  mocks.logout.mockResolvedValue(undefined)
  mocks.supportSessions.mockResolvedValue({ items: [], nextBeforeId: null })
  mocks.knowledgeArticles.mockResolvedValue({ items: [], nextBeforeId: null })
  mocks.knowledgeIndex.mockResolvedValue({
    status: 'DISABLED',
    expectedChunks: 0,
    indexedChunks: 0,
    modelFingerprint: null,
  })
  mocks.warmKnowledgeIndex.mockResolvedValue({
    status: 'READY',
    expectedChunks: 2,
    indexedChunks: 2,
    modelFingerprint: 'test-model',
  })
  mocks.knowledgeAudit.mockResolvedValue({ items: [], nextBeforeId: null })
  mocks.authenticate.mockResolvedValue(undefined)
  mocks.currentAccount.mockResolvedValue({
    username: 'staff',
    accountType: 'MERCHANT_STAFF',
    capabilities: { reviewAfterSales: true, manageKnowledge: false },
  })
  mocks.merchantShops.mockResolvedValue([
    { id: 100, name: '一号店', merchantId: 1, merchantName: '测试商家' },
  ])
  mocks.pendingReviews.mockResolvedValue([])
  mocks.reviewHistory.mockResolvedValue([])
})
async function login(view: ReturnType<typeof mount>) {
  await view.get('[data-testid="merchant-username"]').setValue('staff')
  await view.get('[data-testid="merchant-password"]').setValue('test')
  await view.get('[data-testid="merchant-login"]').trigger('submit')
  await flushPromises()
}
describe('MerchantApp', () => {
  it('isolates human reception as a dedicated capability and lets its assigned staff reply', async () => {
    const timestamp = '2026-10-30T00:00:00Z'
    const waiting = {
      id: crypto.randomUUID(),
      status: 'WAITING',
      assigned: false,
      assignedToMe: false,
      createdAt: timestamp,
      updatedAt: timestamp,
      closedAt: null,
    }
    const active = { ...waiting, status: 'ACTIVE', assigned: true, assignedToMe: true }
    mocks.currentAccount.mockResolvedValueOnce({
      username: 'chat-only',
      accountType: 'MERCHANT_STAFF',
      capabilities: { reviewAfterSales: false, manageKnowledge: false, serveChats: true },
    })
    mocks.supportSessions.mockResolvedValue({ items: [waiting], nextBeforeId: null })
    mocks.merchantSupportSnapshot.mockResolvedValue({
      session: waiting,
      messages: [],
      nextBeforeId: null,
    })
    mocks.claimSupport.mockResolvedValue({ session: active, messages: [], nextBeforeId: null })
    mocks.sendSupportMessage.mockImplementation(
      async (_shop, _conversation, _session, clientMessageId, text) => ({
        id: crypto.randomUUID(),
        clientMessageId,
        text,
        role: 'STAFF',
        createdAt: timestamp,
      }),
    )
    const view = mount(MerchantApp)
    await login(view)
    expect(view.find('[data-testid="merchant-tab-after-sales"]').exists()).toBe(false)
    expect(view.find('[data-testid="merchant-tab-knowledge"]').exists()).toBe(false)
    expect(view.find('[data-testid="merchant-tab-support"]').exists()).toBe(true)
    await view.get('.human-session').trigger('click')
    await flushPromises()
    expect(view.find('[data-testid="support-reply"]').exists()).toBe(false)
    await view.get('[data-testid="support-claim"]').trigger('click')
    await flushPromises()
    await view.get('[data-testid="support-reply"]').setValue('<script>纯文本</script>')
    await view.get('.human-reply').trigger('submit')
    await flushPromises()
    expect(mocks.sendSupportMessage).toHaveBeenCalledOnce()
    expect(view.get('[data-testid="support-message"]').text()).toContain('<script>纯文本</script>')
    expect(view.find('script').exists()).toBe(false)
    expect(mocks.pendingReviews).not.toHaveBeenCalled()
    view.unmount()
  })
  it.each([
    { reviewAfterSales: true, manageKnowledge: false },
    { reviewAfterSales: false, manageKnowledge: true },
  ])('hides human reception when the server grants only other work: %o', async (capabilities) => {
    mocks.currentAccount.mockResolvedValueOnce({
      username: 'other-role',
      accountType: 'MERCHANT_STAFF',
      capabilities,
    })
    const view = mount(MerchantApp)
    await login(view)
    expect(view.find('[data-testid="merchant-tab-support"]').exists()).toBe(false)
    expect(mocks.supportSessions).not.toHaveBeenCalled()
    view.unmount()
  })
  it('shows index readiness only to knowledge staff and prepares it only after an explicit click', async () => {
    mocks.currentAccount.mockResolvedValueOnce({
      username: 'editor',
      accountType: 'MERCHANT_STAFF',
      capabilities: { reviewAfterSales: false, manageKnowledge: true },
    })
    mocks.knowledgeIndex.mockResolvedValueOnce({
      status: 'PARTIAL',
      expectedChunks: 2,
      indexedChunks: 0,
      modelFingerprint: 'local-test',
    })
    const view = mount(MerchantApp)
    await login(view)
    expect(view.get('[data-testid="knowledge-index-panel"]').text()).toContain('0 / 2')
    expect(mocks.warmKnowledgeIndex).not.toHaveBeenCalled()
    await view.get('[data-testid="knowledge-index-prepare"]').trigger('click')
    await flushPromises()
    expect(mocks.warmKnowledgeIndex).toHaveBeenCalledOnce()
    expect(view.get('[data-testid="knowledge-index-panel"]').text()).toContain('已准备完成')
    view.unmount()
  })
  it('shows only the knowledge partition for knowledge-only staff', async () => {
    mocks.currentAccount.mockResolvedValueOnce({
      username: 'editor',
      accountType: 'MERCHANT_STAFF',
      capabilities: { reviewAfterSales: false, manageKnowledge: true },
    })
    const view = mount(MerchantApp)
    await login(view)
    expect(view.find('[data-testid="merchant-tab-after-sales"]').exists()).toBe(false)
    expect(view.find('[data-testid="merchant-tab-knowledge"]').exists()).toBe(true)
    expect(view.find('[data-testid="knowledge-panel"]').exists()).toBe(true)
    expect(view.find('[data-testid="review-panel"]').exists()).toBe(false)
    expect(mocks.pendingReviews).not.toHaveBeenCalled()
    view.unmount()
  })

  it('does not display knowledge controls to review-only staff', async () => {
    const view = mount(MerchantApp)
    await login(view)
    expect(view.find('[data-testid="merchant-tab-knowledge"]').exists()).toBe(false)
    expect(view.find('[data-testid="knowledge-panel"]').exists()).toBe(false)
    expect(view.find('[data-testid="knowledge-index-panel"]').exists()).toBe(false)
    expect(mocks.knowledgeIndex).not.toHaveBeenCalled()
    expect(view.find('[data-testid="review-panel"]').exists()).toBe(true)
    view.unmount()
  })
  it('uses a dedicated staff login and only renders the authorized shop selector', async () => {
    const view = mount(MerchantApp)
    expect(view.find('[data-testid="merchant-workspace"]').exists()).toBe(false)
    await login(view)
    expect(view.find('[data-testid="merchant-workspace"]').exists()).toBe(true)
    expect(view.find('[data-testid="merchant-login"]').exists()).toBe(false)
    expect(view.findAll('[data-testid="merchant-shop-select"] option')).toHaveLength(1)
    expect(view.get('[data-testid="merchant-shop-select"]').text()).toContain('一号店')
    expect(view.find('[data-testid="connect-form"]').exists()).toBe(false)
    expect(view.find('[data-testid="message-input"]').exists()).toBe(false)
    expect(localStorage.length).toBe(0)
    await view.get('[data-testid="merchant-disconnect"]').trigger('click')
    expect(view.find('[data-testid="merchant-workspace"]').exists()).toBe(false)
    expect((view.get('[data-testid="merchant-password"]').element as HTMLInputElement).value).toBe(
      '',
    )
    view.unmount()
  })

  it('rejects buyer accounts and clears the password field', async () => {
    const view = mount(MerchantApp)
    mocks.currentAccount.mockResolvedValueOnce({
      username: 'buyer',
      accountType: 'BUYER',
      capabilities: { reviewAfterSales: false, manageKnowledge: false },
    })
    await login(view)
    expect(view.get('[role="alert"]').text()).toContain('用户端')
    expect((view.get('[data-testid="merchant-password"]').element as HTMLInputElement).value).toBe(
      '',
    )
    expect(view.find('[data-testid="merchant-workspace"]').exists()).toBe(false)
    expect(mocks.merchantShops).not.toHaveBeenCalled()
    view.unmount()
  })

  it('renders an explicit no-permission state without a queue or arbitrary shop input', async () => {
    const view = mount(MerchantApp)
    mocks.merchantShops.mockResolvedValueOnce([])
    await login(view)
    expect(view.get('[data-testid="merchant-no-shops"]').text()).toContain('尚未获得店铺授权')
    expect(view.find('[data-testid="merchant-shop-select"]').exists()).toBe(false)
    expect(view.find('[data-testid="review-panel"]').exists()).toBe(false)
    expect(mocks.pendingReviews).not.toHaveBeenCalled()
    view.unmount()
  })
})
