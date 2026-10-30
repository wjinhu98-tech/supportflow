import { effectScope, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { useSupportChannel } from './useSupportChannel'
import type { SupportMessage, SupportSession, SupportSnapshot } from '../types'

const timestamp = '2026-10-30T00:00:00Z'
const waiting: SupportSession = {
  id: crypto.randomUUID(),
  status: 'WAITING',
  assigned: false,
  assignedToMe: false,
  createdAt: timestamp,
  updatedAt: timestamp,
  closedAt: null,
}
const message = (id = crypto.randomUUID()): SupportMessage => ({
  id: crypto.randomUUID(),
  clientMessageId: id,
  role: 'BUYER',
  text: '商品有问题',
  createdAt: timestamp,
})
const snapshot = (
  session: SupportSession | null = waiting,
  messages: SupportMessage[] = [],
): SupportSnapshot => ({ session, messages, nextBeforeId: null })
const scopes: ReturnType<typeof effectScope>[] = []
function setup(merchant = false) {
  const key = ref('buyer/shop100/conversation1')
  const visible = ref(true)
  const read = vi.fn().mockResolvedValue(snapshot())
  const send = vi.fn().mockImplementation(async (_session, id, text) => ({ ...message(id), text }))
  const close = vi
    .fn()
    .mockResolvedValue(snapshot({ ...waiting, status: 'CLOSED', closedAt: timestamp }))
  const onClosed = vi.fn()
  const scope = effectScope()
  scopes.push(scope)
  const channel = scope.run(() =>
    useSupportChannel({
      scope: () => key.value,
      enabled: () => !!key.value,
      visible: () => visible.value,
      read,
      send,
      close,
      canWrite: (session) => !merchant || (session.status === 'ACTIVE' && session.assignedToMe),
      onClosed,
    }),
  )!
  return { channel, key, visible, read, send, close, onClosed, scope }
}
beforeEach(() => {
  vi.useFakeTimers()
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
})
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop())
  vi.useRealTimers()
})

describe('scoped human transcript', () => {
  it('fences a delayed read before close or a confirmed claim so old status cannot return', async () => {
    const { channel, read } = setup()
    await vi.waitFor(() => expect(channel.ready.value).toBe(true))
    let resolve!: (value: SupportSnapshot) => void
    read.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const beforeClose = channel.refresh()
    await channel.close()
    resolve(snapshot())
    await beforeClose
    expect(channel.session.value?.status).toBe('CLOSED')
    read.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const beforeClaim = channel.refresh()
    channel.accept(snapshot({ ...waiting, status: 'ACTIVE', assigned: true, assignedToMe: true }))
    resolve(snapshot())
    await beforeClaim
    expect(channel.session.value?.status).toBe('ACTIVE')
    expect(channel.session.value?.assignedToMe).toBe(true)
  })
  it('retains closed-session history and adopts a cursor when a later page contains an unseen gap', async () => {
    const { channel, read, key } = setup()
    await vi.waitFor(() => expect(channel.ready.value).toBe(true))
    const first = message()
    channel.accept(snapshot({ ...waiting, status: 'CLOSED', closedAt: timestamp }, [first]))
    const cursor = crypto.randomUUID()
    const latest = Array.from({ length: 30 }, () => message())
    const active = {
      ...waiting,
      id: crypto.randomUUID(),
      status: 'ACTIVE' as const,
      assigned: true,
    }
    read.mockResolvedValue({ ...snapshot(active, latest), nextBeforeId: cursor })
    await channel.refresh()
    expect(channel.nextBeforeId.value).toBe(cursor)
    expect(channel.messages.value.some((item) => item.id === first.id)).toBe(true)
    const missing = message()
    read.mockResolvedValueOnce({ ...snapshot(active, [first, missing]), nextBeforeId: null })
    await channel.refresh(true)
    expect(read.mock.calls.at(-1)![1]).toBe(cursor)
    expect(channel.messages.value).toHaveLength(32)
    expect(channel.nextBeforeId.value).toBeNull()
    await channel.refresh()
    expect(channel.messages.value).toHaveLength(32)
    // Returning to the same shop reloads its latest bounded page and older cursor.
    key.value = 'buyer/shop101/conversation2'
    await vi.waitFor(() => expect(channel.ready.value).toBe(true))
    key.value = 'buyer/shop100/conversation1'
    await vi.waitFor(() => expect(channel.ready.value).toBe(true))
    expect(channel.messages.value).toHaveLength(30)
    expect(channel.nextBeforeId.value).toBe(cursor)
  })
  it('sends buyer waiting messages, merges repeated reads and closes before restoring AI', async () => {
    const { channel, read, send, close, onClosed } = setup()
    await vi.waitFor(() => expect(channel.ready.value).toBe(true))
    expect(channel.active.value).toBe(true)
    await channel.send('商品有问题')
    expect(send).toHaveBeenCalledOnce()
    const sent = channel.messages.value[0]!
    read.mockResolvedValue(snapshot(waiting, [sent]))
    await channel.refresh()
    await channel.refresh()
    expect(channel.messages.value).toHaveLength(1)
    await channel.close()
    expect(close).toHaveBeenCalledOnce()
    expect(channel.active.value).toBe(false)
    expect(channel.messages.value).toEqual([sent])
    expect(onClosed).toHaveBeenCalledOnce()
    const count = read.mock.calls.length
    await vi.advanceTimersByTimeAsync(12000)
    expect(read).toHaveBeenCalledTimes(count)
  })
  it('keeps uncertain message text and UUID and retries only on explicit action', async () => {
    const { channel, send, read } = setup()
    await vi.waitFor(() => expect(channel.ready.value).toBe(true))
    send.mockRejectedValueOnce(new ApiError('lost response', 0, 'NETWORK_ERROR'))
    await channel.send('商品有问题')
    const pending = channel.uncertainMessage.value!
    expect(pending.text).toBe('商品有问题')
    await channel.send('不可另发')
    await vi.advanceTimersByTimeAsync(8000)
    expect(read.mock.calls.length).toBeGreaterThan(1)
    expect(send).toHaveBeenCalledOnce()
    await channel.send('', true)
    expect(send.mock.calls[1]![1]).toBe(pending.id)
    expect(send.mock.calls[1]![2]).toBe(pending.text)
    expect(channel.uncertainMessage.value).toBeNull()
  })
  it('rejects stale scope responses and stops hidden and disposed polling', async () => {
    const { channel, key, read, visible, scope } = setup()
    await vi.waitFor(() => expect(channel.ready.value).toBe(true))
    let resolve!: (value: SupportSnapshot) => void
    read.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const oldRead = channel.refresh()
    key.value = 'buyer/shop101/conversation2'
    await vi.waitFor(() => expect(channel.ready.value).toBe(true))
    resolve(snapshot(waiting, [message()]))
    await oldRead
    expect(channel.messages.value).toHaveLength(0)
    visible.value = false
    await nextTick()
    const count = read.mock.calls.length
    await vi.advanceTimersByTimeAsync(16000)
    expect(read).toHaveBeenCalledTimes(count)
    scope.stop()
    await vi.advanceTimersByTimeAsync(16000)
    expect(read).toHaveBeenCalledTimes(count)
  })
  it('merchant must claim before writing and cannot write a peer-owned session', async () => {
    const { channel, send, close } = setup(true)
    await vi.waitFor(() => expect(channel.ready.value).toBe(true))
    await channel.send('未接待不得发送')
    await channel.close()
    expect(send).not.toHaveBeenCalled()
    expect(close).not.toHaveBeenCalled()
    channel.accept(snapshot({ ...waiting, status: 'ACTIVE', assigned: true, assignedToMe: false }))
    await channel.send('他人的接待')
    expect(send).not.toHaveBeenCalled()
    channel.accept(snapshot({ ...waiting, status: 'ACTIVE', assigned: true, assignedToMe: true }))
    await channel.send('您好，正在为您核实')
    expect(send).toHaveBeenCalledOnce()
  })
})
