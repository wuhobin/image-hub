import test from 'node:test'
import assert from 'node:assert/strict'
import { api, ApiError, SESSION_EXPIRED, TOKEN_KEY, unwrap } from './api.ts'

test('API client checks business codes, sends Bearer tokens and ignores stale 401 responses', async () => {
  const values = new Map<string, string>()
  const browser = new EventTarget()
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: (key: string) => values.get(key) || null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: browser })
  let expired = 0
  browser.addEventListener(SESSION_EXPIRED, () => expired++)
  values.set(TOKEN_KEY, 'current-token')
  assert.throws(() => unwrap(200, { code: 401, message: 'expired' }, 'old-token'), ApiError)
  assert.equal(values.get(TOKEN_KEY), 'current-token')
  assert.equal(expired, 0)
  assert.throws(() => unwrap(200, { code: 401, message: 'expired' }, 'current-token'), ApiError)
  assert.equal(values.has(TOKEN_KEY), false)
  assert.equal(expired, 1)
  assert.throws(() => unwrap(413, null, null), (error: unknown) => error instanceof ApiError && error.code === 413)

  const original = globalThis.fetch
  values.set(TOKEN_KEY, 'test-token')
  try {
    globalThis.fetch = async (url, init) => {
      assert.equal(url, '/api/auth/me')
      assert.equal(new Headers(init?.headers).get('Authorization'), 'Bearer test-token')
      return new Response(JSON.stringify({ code: 200, data: { username: 'alice' } }))
    }
    assert.deepEqual(await api('/auth/me'), { username: 'alice' })
    const page = { records: [], total: 0, current: 1, size: 24, pages: 0 }
    const images = { page, totalBytes: 0 }
    globalThis.fetch = async () => new Response(JSON.stringify({ code: 200, data: images }))
    assert.deepEqual(await api('/images'), images)
    globalThis.fetch = async () => new Response(JSON.stringify({ code: 409, message: 'already registered' }))
    await assert.rejects(api('/auth/register'), /already registered/)
  } finally { globalThis.fetch = original }
})
