import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test'
import type { ChatResponse, KnowledgeArticle, KnowledgeAuditPage } from '../src/types'
import { connect, send } from './helpers'

// Public isolated-launcher identities, never daily business credentials.
const password = 'Frontend-Test-Only!'
const base = '/api/v1/merchant/shops/100/knowledge'

async function account(username: string): Promise<APIRequestContext> {
  return request.newContext({
    baseURL: 'http://127.0.0.1:5173',
    extraHTTPHeaders: {
      Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`,
    },
  })
}

async function call<T>(
  api: APIRequestContext,
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  body?: unknown,
  expected = 200,
): Promise<T> {
  const headers: Record<string, string> = {}
  if (method !== 'GET') {
    const csrfResponse = await api.get('/api/v1/security/csrf')
    expect(csrfResponse.status()).toBe(200)
    const csrf = await csrfResponse.json()
    headers[csrf.headerName] = csrf.token
  }
  const response = await api.fetch(path, { method, data: body, headers })
  expect(response.status(), `${method} ${path}`).toBe(expected)
  return response.json()
}

async function answer(api: APIRequestContext, shopId: string, message: string): Promise<string> {
  const response = await call<{ reply: string }>(
    api,
    'POST',
    `/api/v1/shops/${shopId}/chat/messages`,
    { message },
  )
  return response.reply
}

async function login(page: Page, username = 'frontend-editor') {
  await page.goto('/merchant')
  await page.getByTestId('merchant-username').fill(username)
  await page.getByTestId('merchant-password').fill(password)
  await page.getByTestId('merchant-connect').click()
  await expect(page.getByTestId('merchant-workspace')).toBeVisible()
}

async function newDraft(page: Page, title: string, keyword: string, content: string) {
  await page.getByTestId('knowledge-new').click()
  await page.getByTestId('knowledge-title').fill(title)
  await page.getByTestId('knowledge-keywords').fill(keyword)
  await page.getByTestId('knowledge-content').fill(content)
  const created = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === base,
  )
  await page.getByTestId('knowledge-save').click()
  const response = await created
  expect(response.status()).toBe(200)
  const article = (await response.json()) as KnowledgeArticle
  await expect(page.getByTestId('knowledge-publish')).toBeEnabled()
  return article
}

async function act(page: Page, id: string, action: 'publish' | 'offline') {
  const responded = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `${base}/${id}/${action}`,
  )
  await page.getByTestId(`knowledge-${action}`).click()
  const response = await responded
  expect(response.status()).toBe(200)
  return (await response.json()) as KnowledgeArticle
}

test('knowledge-only staff publish, edit drafts without replacing live snapshots, and take articles offline', async ({
  page,
}, testInfo) => {
  test.setTimeout(60_000)
  const buyer = await account('frontend-buyer')
  const editor = await account('frontend-editor')
  try {
    for (const whitespace of ['\u00A0', '\u2007', '\u202F']) {
      await call(
        editor,
        'POST',
        base,
        {
          title: 'Invalid normalized-empty keyword',
          keywords: [whitespace],
          content: 'Not a valid draft',
        },
        400,
      )
    }
    await login(page)
    await expect(page.getByTestId('merchant-tab-after-sales')).toHaveCount(0)
    await expect(page.getByTestId('knowledge-panel')).toBeVisible()
    const token = `礼盒${Date.now()}`
    const query = `请介绍星河礼盒包装${token}`
    const original = `星河礼盒使用纸袋。发布识别码A-${token}。\n\n购买满两件才可附赠贺卡。`
    const edited = `星河礼盒使用可回收纸盒。发布识别码B-${token}。`
    const draft = await newDraft(page, `星河礼盒包装${token}`, `星河礼盒,${token}`, original)
    expect(await answer(buyer, '100', query)).not.toContain(`A-${token}`)
    const published = await act(page, draft.id, 'publish')
    expect(published.publishedSnapshot?.content).toBe(original)
    const firstAnswer = await call<ChatResponse>(buyer, 'POST', '/api/v1/shops/100/chat/messages', {
      message: query,
    })
    const firstReply = firstAnswer.reply
    expect(firstReply).toContain(`A-${token}`)
    expect(firstReply).toContain('购买满两件才可附赠贺卡。')
    expect(firstAnswer.knowledge?.status).toBe('ANSWERED')
    expect(firstAnswer.knowledge?.sources).toContainEqual({
      sourceId: `kb-${draft.id}`,
      version: `v${published.publishedRevision}`,
      title: draft.title,
      content: original,
    })
    await page.getByTestId('knowledge-content').fill(edited)
    const saved = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === `${base}/${draft.id}`,
    )
    await page.getByTestId('knowledge-save').click()
    expect((await saved).status()).toBe(200)
    await expect(page.getByTestId('knowledge-published-snapshot')).toContainText(original)
    const whileDraft = await answer(buyer, '100', query)
    expect(whileDraft).toContain(`A-${token}`)
    expect(whileDraft).not.toContain(`B-${token}`)
    await expect(page.getByTestId('knowledge-publish')).toBeEnabled()
    const republished = await act(page, draft.id, 'publish')
    expect(republished.publishedRevision).toBeGreaterThan(published.publishedRevision!)
    expect(await answer(buyer, '100', query)).toContain(`B-${token}`)
    await expect(page.getByTestId('knowledge-audit-entry').first()).toBeVisible()
    await page.screenshot({
      path: testInfo.outputPath('knowledge-management-desktop.png'),
      fullPage: true,
    })
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    )
    await page.screenshot({
      path: testInfo.outputPath('knowledge-management-mobile.png'),
      fullPage: true,
    })
    await page.setViewportSize({ width: 1440, height: 1000 })
    const offline = await act(page, draft.id, 'offline')
    expect(offline.status).toBe('OFFLINE')
    expect(await answer(buyer, '100', query)).not.toContain(`B-${token}`)
    expect(firstReply).toContain(`A-${token}`)
    const audit = await call<KnowledgeAuditPage>(editor, 'GET', `${base}/${draft.id}/audit`)
    expect(audit.items.map((entry) => entry.action).sort()).toEqual(
      ['CREATED', 'OFFLINE', 'PUBLISHED', 'PUBLISHED', 'SAVED'].sort(),
    )
  } finally {
    await buyer.dispose()
    await editor.dispose()
  }
})

test('published shop FAQ never crosses shops and knowledge authority cannot approve refunds', async () => {
  const editor = await account('frontend-editor')
  const other = await account('frontend-editor-other')
  const buyer = await account('frontend-buyer')
  const reviewer = await account('frontend-review-only')
  try {
    const keyword = `星光纸箱${Date.now()}`
    const input = {
      title: `${keyword}使用说明`,
      keywords: [keyword],
      content: `店铺100专属原文-${keyword}`,
    }
    const own = await call<KnowledgeArticle>(editor, 'POST', base, input)
    await call(editor, 'POST', `${base}/${own.id}/publish`, { expectedVersion: own.version })
    const otherBase = '/api/v1/merchant/shops/101/knowledge'
    const foreign = await call<KnowledgeArticle>(other, 'POST', otherBase, {
      ...input,
      content: `店铺101专属原文-${keyword}`,
    })
    await call(other, 'POST', `${otherBase}/${foreign.id}/publish`, {
      expectedVersion: foreign.version,
    })
    const ownReply = await answer(buyer, '100', `请介绍${keyword}`)
    expect(ownReply).toContain(`店铺100专属原文-${keyword}`)
    expect(ownReply).not.toContain(`店铺101专属原文-${keyword}`)
    const foreignReply = await answer(buyer, '101', `请介绍${keyword}`)
    expect(foreignReply).toContain(`店铺101专属原文-${keyword}`)
    expect(foreignReply).not.toContain(`店铺100专属原文-${keyword}`)
    await call(editor, 'GET', otherBase, undefined, 403)
    await call(other, 'GET', `${otherBase}/${own.id}`, undefined, 404)
    await call(buyer, 'GET', base, undefined, 403)
    await call(reviewer, 'GET', base, undefined, 403)
    await call(editor, 'GET', '/api/v1/merchant/shops/100/after-sales', undefined, 403)
  } finally {
    await Promise.all([editor.dispose(), other.dispose(), buyer.dispose(), reviewer.dispose()])
  }
})

test('review-only staff have no knowledge tab or accidental knowledge API requests', async ({
  page,
}) => {
  const knowledgeRequests: string[] = []
  page.on('request', (req) => {
    const path = new URL(req.url()).pathname
    if (path.startsWith('/api/v1/merchant/') && /\/knowledge(?:\/|$)/.test(path))
      knowledgeRequests.push(req.url())
  })
  await login(page, 'frontend-review-only')
  await expect(page.getByTestId('review-panel')).toBeVisible()
  await expect(page.getByTestId('merchant-tab-knowledge')).toHaveCount(0)
  await expect(page.getByTestId('knowledge-panel')).toHaveCount(0)
  expect(knowledgeRequests).toHaveLength(0)
})

test('a stale draft receives 409 without overwriting server changes or losing local input', async ({
  page,
}) => {
  const editor = await account('frontend-editor')
  try {
    await login(page)
    const token = `并发包装${Date.now()}`
    const draft = await newDraft(page, token, token, '初始纸袋说明')
    await call(editor, 'PUT', `${base}/${draft.id}`, {
      title: token,
      keywords: [token],
      content: '另一个员工已保存的新说明',
      expectedVersion: draft.version,
    })
    await page.getByTestId('knowledge-content').fill('当前页面尚未提交的编辑内容')
    const rejected = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === `${base}/${draft.id}`,
    )
    await page.getByTestId('knowledge-save').click()
    expect((await rejected).status()).toBe(409)
    await expect(page.getByTestId('knowledge-content')).toHaveValue('当前页面尚未提交的编辑内容')
    await expect(page.getByTestId('knowledge-save')).toBeDisabled()
    const current = await call<KnowledgeArticle>(editor, 'GET', `${base}/${draft.id}`)
    expect(current.content).toBe('另一个员工已保存的新说明')
    await page.getByTestId('knowledge-reload').click()
    await expect(page.getByTestId('knowledge-content')).toHaveValue('当前页面尚未提交的编辑内容')
    await expect(page.getByTestId('knowledge-save')).toBeEnabled()
    expect((await call<KnowledgeArticle>(editor, 'GET', `${base}/${draft.id}`)).content).toBe(
      current.content,
    )
  } finally {
    await editor.dispose()
  }
})

test('a lost publish response is read back without replaying publication', async ({ page }) => {
  const editor = await account('frontend-editor')
  try {
    await login(page)
    const token = `断线发布${Date.now()}`
    const draft = await newDraft(page, token, token, '只有一个正式发布快照')
    let publications = 0
    await page.route(`**${base}/${draft.id}/publish`, async (route) => {
      publications++
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      await route.abort('failed')
    })
    await page.getByTestId('knowledge-publish').click()
    await expect
      .poll(async () => (await call<KnowledgeArticle>(editor, 'GET', `${base}/${draft.id}`)).status)
      .toBe('PUBLISHED')
    await expect(page.getByTestId('knowledge-reload')).toBeEnabled()
    await page.getByTestId('knowledge-reload').click()
    await expect(page.getByTestId('knowledge-published-snapshot')).toContainText(
      '只有一个正式发布快照',
    )
    const audit = await call<KnowledgeAuditPage>(editor, 'GET', `${base}/${draft.id}/audit`)
    expect(audit.items.filter((entry) => entry.action === 'PUBLISHED')).toHaveLength(1)
    expect(publications).toBe(1)
  } finally {
    await editor.dispose()
  }
})

test('synonym FAQ evidence is collapsible plain text and history keeps the cited revision after offline', async ({
  page,
}, testInfo) => {
  test.setTimeout(75_000)
  const editor = await account('frontend-editor')
  const marker = `evidence-${Date.now()}`
  const original =
    `以下仅为学习环境的演示内容，不代表实际服务承诺。\n\n演示贺卡可手写祝福语。` +
    `\n\n标签示例：<img src="/untrusted-${marker}" onerror="alert(1)">。` +
    `\n\n其他说明：${'请以实际商品说明为准。'.repeat(80)}`
  const title = `【演示】贺卡书写说明-${marker}`
  let unexpectedSourceRequests = 0
  page.on('request', (req) => {
    const pathname = new URL(req.url()).pathname
    if (
      pathname.includes(`/untrusted-${marker}`) ||
      /\/api\/v1\/merchant\/.*\/knowledge/.test(pathname)
    )
      unexpectedSourceRequests++
  })
  try {
    const draft = await call<KnowledgeArticle>(editor, 'POST', base, {
      title,
      keywords: ['贺卡', marker],
      content: original,
    })
    const published = await call<KnowledgeArticle>(editor, 'POST', `${base}/${draft.id}/publish`, {
      expectedVersion: draft.version,
    })
    await page.goto('/?shopId=100')
    await connect(page, 'frontend-buyer', password)
    const question = '知识库：能放张祝福卡写祝福语吗？'
    const responded = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        /\/chat\/conversations\/[^/]+\/messages$/.test(new URL(response.url()).pathname) &&
        response.request().postDataJSON()?.message === question,
    )
    await send(page, question)
    const turnResponse = await responded
    expect(turnResponse.status()).toBe(200)
    const turn = (await turnResponse.json()) as {
      messages: { role: string; response: ChatResponse | null }[]
    }
    const result = turn.messages.find((message) => message.role === 'ASSISTANT')!.response!
    expect(result.knowledge?.status).toBe('ANSWERED')
    expect(result.reply.length).toBeLessThan(1000)
    expect(result.reply).not.toContain(original)
    expect(result.reply).toContain('展开来源')
    expect(result.reply).not.toContain('演示贺卡可手写祝福语')
    expect(result.knowledge?.sources).toContainEqual({
      sourceId: `kb-${draft.id}`,
      version: `v${published.publishedRevision}`,
      title,
      content: original,
    })
    const message = page.getByTestId('chat-message').last()
    const source = message.getByTestId('knowledge-source').filter({ hasText: title })
    await expect(source).toHaveCount(1)
    await expect(source.getByTestId('knowledge-source-content')).not.toBeVisible()
    await source.getByTestId('knowledge-source-toggle').click()
    await expect(source.getByTestId('knowledge-source-content')).toBeVisible()
    await expect(source.getByTestId('knowledge-source-content')).toContainText('<img')
    await expect(source.locator('img,script,iframe,a')).toHaveCount(0)
    await page.screenshot({
      path: testInfo.outputPath('knowledge-evidence-desktop.png'),
      fullPage: true,
    })
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    )
    await source.getByTestId('knowledge-source-toggle').scrollIntoViewIfNeeded()
    await page.screenshot({
      path: testInfo.outputPath('knowledge-evidence-mobile.png'),
      fullPage: true,
    })
    await call(editor, 'POST', `${base}/${draft.id}/offline`, {
      expectedVersion: published.version,
    })

    await page.reload()
    await connect(page, 'frontend-buyer', password)
    const restored = page
      .getByTestId('chat-message')
      .last()
      .getByTestId('knowledge-source')
      .filter({ hasText: title })
    await expect(restored).toHaveCount(1)
    await expect(
      page.getByTestId('chat-message').last().getByTestId('knowledge-sources'),
    ).toContainText(/历史|快照/)
    await expect(restored.getByTestId('knowledge-source-content')).not.toBeVisible()
    await restored.getByTestId('knowledge-source-toggle').click()
    await expect(restored.getByTestId('knowledge-source-content')).toContainText(original)
    expect(unexpectedSourceRequests).toBe(0)
  } finally {
    await editor.dispose()
  }
})

test('an unsupported knowledge question returns no evidence instead of fabricated source cards', async ({
  page,
}) => {
  await page.goto('/?shopId=100')
  await connect(page, 'frontend-buyer', password)
  const question = `知识库：火星荧光蘑菇培养方案-${Date.now()}`
  const responded = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      /\/chat\/conversations\/[^/]+\/messages$/.test(new URL(response.url()).pathname) &&
      response.request().postDataJSON()?.message === question,
  )
  await send(page, question)
  const response = await responded
  expect(response.status()).toBe(200)
  const turn = (await response.json()) as {
    messages: { role: string; response: ChatResponse | null }[]
  }
  const result = turn.messages.find((message) => message.role === 'ASSISTANT')!.response!
  expect(result.knowledge?.status).toBe('NO_MATCH')
  expect(result.knowledge?.sources).toEqual([])
  await expect(page.getByTestId('chat-message').last().getByTestId('knowledge-source')).toHaveCount(
    0,
  )
})
