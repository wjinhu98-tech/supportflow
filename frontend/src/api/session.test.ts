import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from './client'

const csrf = (token = 'fresh-csrf') =>
  json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token })
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
const account = {
  username: 'buyer',
  accountType: 'BUYER',
  capabilities: { reviewAfterSales: false, manageKnowledge: false },
}
const sessions: ApiClient[] = []
const session = (fetch: typeof globalThis.fetch, invalidated?: () => void) => {
  const api = new ApiClient('buyer', 'one-time-secret', fetch, {
    authentication: 'session',
    onSessionInvalidated: invalidated,
  })
  sessions.push(api)
  return api
}
afterEach(() => {
  for (const api of sessions.splice(0)) api.dispose()
  vi.unstubAllGlobals()
})

describe('browser session authentication', () => {
  it('exchanges a password once and uses cookie identity and fresh CSRF for logout', async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(csrf('anonymous-csrf'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(csrf('rotated-csrf'))
      .mockResolvedValueOnce(json(account))
      .mockResolvedValueOnce(csrf('logout-csrf'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    const api = session(fetch)
    await api.authenticate()
    await api.authenticate()
    await expect(api.currentAccount()).resolves.toEqual(account)
    await api.logout()
    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/security/csrf',
      '/api/v1/security/login',
      '/api/v1/security/csrf',
      '/api/v1/security/me',
      '/api/v1/security/csrf',
      '/api/v1/security/logout',
    ])
    for (const [, init] of fetch.mock.calls) {
      expect(init?.credentials).toBe('same-origin')
      expect(new Headers(init?.headers).has('Authorization')).toBe(false)
    }
    const login = fetch.mock.calls[1]![1]!
    expect(login.method).toBe('POST')
    expect(JSON.parse(String(login.body))).toEqual({
      username: 'buyer',
      password: 'one-time-secret',
    })
    expect(new Headers(login.headers).get('X-CSRF-TOKEN')).toBe('anonymous-csrf')
    expect(new Headers(fetch.mock.calls[3]![1]!.headers).get('X-Session-Account')).toBe('buyer')
    expect(new Headers(fetch.mock.calls[5]![1]!.headers).get('X-CSRF-TOKEN')).toBe('logout-csrf')
    await expect(api.currentAccount()).rejects.toMatchObject({ status: 401 })
    expect(fetch).toHaveBeenCalledTimes(6)
  })

  it('does not use an existing browser identity before explicit login or fall back to Basic after a failed login', async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ code: 'UNAUTHENTICATED' }, 401))
    const api = session(fetch)
    await expect(api.currentAccount()).rejects.toMatchObject({ status: 401 })
    expect(fetch).not.toHaveBeenCalled()
    await expect(api.authenticate()).rejects.toMatchObject({ status: 401 })
    await expect(api.authenticate()).rejects.toMatchObject({ status: 401 })
    await expect(api.currentAccount()).rejects.toMatchObject({ status: 401 })
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(
      fetch.mock.calls.every(([, init]) => !new Headers(init?.headers).has('Authorization')),
    ).toBe(true)
  })

  it('does not re-submit credentials or replay a request when the server session expires', async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ code: 'UNAUTHENTICATED' }, 401))
    const api = session(fetch)
    await api.authenticate()
    await expect(api.currentAccount()).rejects.toMatchObject({ status: 401 })
    expect(fetch.mock.calls.filter(([url]) => url === '/api/v1/security/login')).toHaveLength(1)
    expect(fetch).toHaveBeenCalledTimes(4)
  })

  it('finishes cancelled login cleanup before a new account session starts', async () => {
    let completeLogin!: (response: Response) => void
    const login = new Promise<Response>((resolve) => {
      completeLogin = resolve
    })
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(csrf())
      .mockReturnValueOnce(login)
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    const first = session(fetch)
    const abort = new AbortController()
    const connecting = first.authenticate(abort.signal)
    const rejected = expect(connecting).rejects.toMatchObject({ name: 'AbortError' })
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    abort.abort()
    const logout = first.logout()
    const secondFetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(csrf())
    const second = session(secondFetch)
    const nextLogin = second.authenticate()
    expect(secondFetch).not.toHaveBeenCalled()
    completeLogin(new Response(null, { status: 204 }))
    await rejected
    await logout
    await nextLogin
    expect(fetch.mock.calls.at(-1)?.[0]).toBe('/api/v1/security/logout')
    expect(secondFetch.mock.calls[0]?.[0]).toBe('/api/v1/security/csrf')
  })

  it('abandons a stale tab on session replacement without logging out the new tab', async () => {
    const close = vi.fn()
    let receive!: (event: MessageEvent) => void
    vi.stubGlobal(
      'BroadcastChannel',
      class {
        set onmessage(handler: (event: MessageEvent) => void) {
          receive = handler
        }
        postMessage = vi.fn()
        close = close
      },
    )
    const invalidated = vi.fn()
    const fetch = vi.fn<typeof globalThis.fetch>()
    const api = session(fetch, invalidated)
    receive(new MessageEvent('message', { data: { source: 'another-tab', type: 'changed' } }))
    expect(invalidated).toHaveBeenCalledOnce()
    expect(close).toHaveBeenCalledOnce()
    await expect(api.currentAccount()).rejects.toMatchObject({ status: 401 })
    expect(fetch).not.toHaveBeenCalled()
  })
})
