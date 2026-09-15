import test from 'node:test'
import assert from 'node:assert/strict'
import { api, ApiError, SESSION_EXPIRED, TOKEN_KEY, unwrap, uploadImage } from './api.ts'
import { ADMIN_SESSION, adminApi } from './admin/api.ts'

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
  const originalXhr = globalThis.XMLHttpRequest
  values.set(TOKEN_KEY, 'test-token')
  try {
    globalThis.fetch = async (url, init) => {
      assert.equal(url, '/api/app/auth/me')
      assert.equal(new Headers(init?.headers).get('Authorization'), 'Bearer test-token')
      return new Response(JSON.stringify({ code: 200, data: { username: 'alice' } }))
    }
    assert.deepEqual(await api('/auth/me'), { username: 'alice' })
    const page = { records: [], total: 0, current: 1, size: 24, pages: 0 }
    const images = { page, totalBytes: 0 }
    globalThis.fetch = async (url) => {
      assert.equal(url, '/api/app/images')
      return new Response(JSON.stringify({ code: 200, data: images }))
    }
    assert.deepEqual(await api('/images'), images)
    globalThis.fetch = async () => new Response(JSON.stringify({ code: 409, message: 'already registered' }))
    await assert.rejects(api('/auth/register'), /already registered/)
    // 同域名的管理入口必须独立发送、清理自己的 Token。
    values.set(ADMIN_SESSION.tokenKey, 'admin-token')
    let adminExpired = 0
    browser.addEventListener(ADMIN_SESSION.expiredEvent, () => adminExpired++)
    globalThis.fetch = async (url, init) => {
      assert.equal(url, '/api/admin/users')
      assert.equal(new Headers(init?.headers).get('Authorization'), 'Bearer admin-token')
      return new Response(JSON.stringify({ code: 401, message: 'admin expired' }))
    }
    await assert.rejects(adminApi('/users'), /admin expired/)
    assert.equal(values.get(TOKEN_KEY), 'test-token')
    assert.equal(values.has(ADMIN_SESSION.tokenKey), false)
    assert.equal(adminExpired, 1)
    assert.equal(expired, 1)
    values.set(ADMIN_SESSION.tokenKey, 'new-admin-token')
    assert.throws(() => unwrap(200, { code: 401 }, 'old-admin-token', ADMIN_SESSION), ApiError)
    assert.equal(values.get(ADMIN_SESSION.tokenKey), 'new-admin-token')
    // 上传走 XHR，也必须使用用户端前缀及用户 Token。
    globalThis.XMLHttpRequest = class {
      upload = {}
      status = 200
      responseText = JSON.stringify({ code: 200, data: { id: 'uploaded' } })
      onload = () => {}
      open(method: string, url: string) {
        assert.equal(method, 'POST')
        assert.equal(url, '/api/app/images')
      }
      setRequestHeader(name: string, value: string) {
        assert.equal(name, 'Authorization')
        assert.equal(value, 'Bearer test-token')
      }
      send(data: FormData) { assert.equal((data.get('file') as File).name, 'test.png'); this.onload() }
    } as unknown as typeof XMLHttpRequest
    assert.deepEqual(await uploadImage(new File(['test'], 'test.png'), () => {}, new AbortController().signal), { id: 'uploaded' })
  } finally { globalThis.fetch = original; globalThis.XMLHttpRequest = originalXhr }
})
