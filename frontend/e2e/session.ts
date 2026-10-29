import { expect, request, test, type APIRequestContext } from '@playwright/test'

/** Isolated cookie jar for direct test API calls; it never changes the browser page's identity. */
export async function sessionAccount(
  username: string,
  password: string,
): Promise<APIRequestContext> {
  const api = await request.newContext({
    baseURL: test.info().project.use.baseURL,
    extraHTTPHeaders: { 'X-Session-Account': username },
  })
  try {
    const csrfResponse = await api.get('/api/v1/security/csrf')
    expect(csrfResponse.status()).toBe(200)
    const csrf = await csrfResponse.json()
    const login = await api.post('/api/v1/security/login', {
      data: { username, password },
      headers: { [csrf.headerName]: csrf.token },
    })
    expect(login.status()).toBe(204)
    expect((await api.get('/api/v1/security/csrf')).status()).toBe(200)
    const identity = await api.get('/api/v1/security/me')
    expect(identity.status()).toBe(200)
    expect((await identity.json()).username).toBe(username)
    return api
  } catch (failure) {
    await api.dispose()
    throw failure
  }
}
