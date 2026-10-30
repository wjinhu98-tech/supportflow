import { effectScope, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from '../api/client'
import { useMerchantSupport } from './useMerchantSupport'

const timestamp = '2026-10-30T00:00:00Z'
const session = {
  id: crypto.randomUUID(),
  status: 'WAITING',
  assigned: false,
  assignedToMe: false,
  createdAt: timestamp,
  updatedAt: timestamp,
  closedAt: null,
}
const scopes: ReturnType<typeof effectScope>[] = []
function setup() {
  const shop = ref('100')
  const allowed = ref(true)
  const client = {
    supportSessions: vi.fn().mockResolvedValue({ items: [session], nextBeforeId: null }),
    merchantSupportSnapshot: vi
      .fn()
      .mockResolvedValue({ session, messages: [], nextBeforeId: null }),
    claimSupport: vi.fn(),
  }
  const report = vi.fn()
  const scope = effectScope()
  scopes.push(scope)
  const desk = scope.run(() =>
    useMerchantSupport({
      client: () => client as unknown as ApiClient,
      scope: () => shop.value,
      shop: () => shop.value,
      allowed: () => allowed.value,
      report,
    }),
  )!
  return { desk, shop, allowed, client, report }
}
afterEach(() => scopes.splice(0).forEach((scope) => scope.stop()))

describe('merchant support queue coordination', () => {
  it('resolves a claim race by reading and never automatically claims again', async () => {
    const { desk, client } = setup()
    await vi.waitFor(() => expect(desk.supportSessions.value).toHaveLength(1))
    desk.openSupport(session.id)
    await vi.waitFor(() => expect(desk.supportSession.value).not.toBeNull())
    client.claimSupport.mockRejectedValueOnce(new ApiError('claimed', 409, 'SUPPORT_CONFLICT'))
    client.merchantSupportSnapshot.mockRejectedValueOnce(new ApiError('other staff', 404))
    client.supportSessions.mockResolvedValue({ items: [], nextBeforeId: null })
    await desk.claimSupport()
    expect(client.claimSupport).toHaveBeenCalledOnce()
    expect(client.merchantSupportSnapshot).toHaveBeenCalledTimes(2)
    expect(desk.supportSelectedId.value).toBeNull()
    expect(desk.supportSessions.value).toHaveLength(0)
  })
  it('discards a late claim after shop change and stops access after capability revocation', async () => {
    const { desk, client, shop, allowed } = setup()
    await vi.waitFor(() => expect(desk.supportSessions.value).toHaveLength(1))
    desk.openSupport(session.id)
    await vi.waitFor(() => expect(desk.supportSession.value).not.toBeNull())
    let resolve!: (value: unknown) => void
    client.claimSupport.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const claim = desk.claimSupport()
    shop.value = '101'
    resolve({
      session: { ...session, status: 'ACTIVE', assigned: true, assignedToMe: true },
      messages: [],
      nextBeforeId: null,
    })
    await claim
    expect(desk.supportSession.value).toBeNull()
    expect(desk.supportSubmitting.value).toBe(false)
    allowed.value = false
    const count = client.supportSessions.mock.calls.length
    await desk.loadSupportSessions()
    desk.openSupport(session.id)
    await desk.claimSupport()
    expect(client.supportSessions).toHaveBeenCalledTimes(count)
    expect(client.claimSupport).toHaveBeenCalledOnce()
  })
  it('reports an expired staff session before clearing transcript scope', async () => {
    const { desk, client, report } = setup()
    await vi.waitFor(() => expect(desk.supportSessions.value).toHaveLength(1))
    client.merchantSupportSnapshot.mockRejectedValueOnce(new ApiError('expired', 401))
    desk.openSupport(session.id)
    await vi.waitFor(() => expect(report).toHaveBeenCalledOnce())
    expect((report.mock.calls[0]![1] as AbortSignal).aborted).toBe(false)
  })
})
