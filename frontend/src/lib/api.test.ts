import test from 'node:test'
import assert from 'node:assert/strict'
import {api, ApiError, SESSION_EXPIRED, TOKEN_KEY, unwrap, uploadImage, readUploadResult} from './api.ts'
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

test('upload retries keep their request id and confirm ambiguous results without resending', async () => {
    const originalFetch = globalThis.fetch
    const originalXhr = globalThis.XMLHttpRequest
    const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'localStorage', {configurable: true, value: {getItem: () => 'upload-token'}})
    const requestId = 'e52995a0-1689-4ce8-b7ef-19cfd402825c'
    const sent: string[] = []
    let mode: 'ok' | 'timeout' | 'server-error' | 'denied' = 'ok'
    let reads = 0
    const record = {id: 'saved-image'}
    globalThis.XMLHttpRequest = class {
        upload = {}
        status = 200
        responseText = ''
        onload = () => {
        }
        ontimeout = () => {
        }

        open() {
        }

        setRequestHeader() {
        }

        send(data: FormData) {
            sent.push(String(data.get('requestId')))
            assert.equal((data.get('file') as File).name, 'photo.png')
            if (mode === 'timeout') {
                this.ontimeout();
                return
            }
            this.responseText = JSON.stringify(mode === 'ok'
                ? {code: 200, data: record} : mode === 'server-error'
                    ? {code: 500, message: '保存后回读失败'} : {code: 40301, message: '积分不足'})
            this.onload()
        }
    } as unknown as typeof XMLHttpRequest
    try {
        globalThis.fetch = async (url, init) => {
            reads++
            assert.equal(url, '/api/app/images/uploads/' + requestId)
            assert.equal(new Headers(init?.headers).get('Authorization'), 'Bearer upload-token')
            return new Response(JSON.stringify({code: 200, data: record}))
        }
        const file = new File(['image-bytes'], 'photo.png', {type: 'image/png'})
        const signal = new AbortController().signal
        assert.deepEqual(await uploadImage(file, () => {
        }, signal, requestId), record)
        assert.equal(reads, 0)
        mode = 'timeout'
        assert.deepEqual(await uploadImage(file, () => {
        }, signal, requestId), record)
        assert.deepEqual(sent, [requestId, requestId])
        assert.equal(reads, 1)
        mode = 'server-error'
        assert.deepEqual(await uploadImage(file, () => {
        }, signal, requestId), record)
        assert.equal(reads, 2)
        mode = 'timeout'

        // 尚未落库不代表原请求已终止，保持错误并由用户使用原ID重试。
        globalThis.fetch = async () => new Response(JSON.stringify({code: 404, message: '尚未找到上传结果'}))
        assert.equal(await readUploadResult(requestId, signal), null)
        await assert.rejects(uploadImage(file, () => {
        }, signal, requestId), /上传超时/)
        assert.equal(sent.at(-1), requestId)

        globalThis.fetch = async () => new Response(JSON.stringify({code: 410, message: '该图片已删除'}))
        await assert.rejects(readUploadResult(requestId, signal), (error: unknown) => error instanceof ApiError && error.code === 410)
        globalThis.fetch = async () => new Response(JSON.stringify({code: 503, message: '查询暂不可用'}))
        await assert.rejects(readUploadResult(requestId, signal), (error: unknown) => error instanceof ApiError && error.code === 503)

        mode = 'denied'
        globalThis.fetch = async () => {
            throw new Error('业务拒绝不应查询或自动重传')
        }
        await assert.rejects(uploadImage(file, () => {
        }, signal, requestId), /积分不足/)
        const controller = new AbortController()
        controller.abort()
        const sentBeforeAbort = sent.length
        await assert.rejects(uploadImage(file, () => {
        }, controller.signal, requestId), {name: 'AbortError'})
        assert.equal(sent.length, sentBeforeAbort)
    } finally {
        globalThis.fetch = originalFetch
        globalThis.XMLHttpRequest = originalXhr
        if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
        else Reflect.deleteProperty(globalThis, 'localStorage')
    }
})
