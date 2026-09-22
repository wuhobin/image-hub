import test from 'node:test'
import assert from 'node:assert/strict'
import {createQuotaCache} from './quotaCache.ts'
import type {UploadQuota} from './types'

test('积分缓存合并查询、30秒过期，并隔离强制刷新、失败重试及退出后的旧响应', async t => {
    let now = 1_000
    t.mock.method(Date, 'now', () => now)
    const requests: {
        signal: AbortSignal;
        resolve: (value: UploadQuota) => void;
        reject: (error: Error) => void
    }[] = []
    const cache = createQuotaCache(signal => new Promise((resolve, reject) => requests.push({signal, resolve, reject})))
    const balance = (remaining: number): UploadQuota => ({total: 100, remaining, used: 100 - remaining, reserved: 0})

    const first = cache.read()
    assert.equal(cache.read(), first, '并发页面查询必须复用同一个请求')
    requests[0].resolve(balance(100))
    await first
    now += 29_999
    assert.equal((await cache.read()).remaining, 100)
    assert.equal(requests.length, 1, '频繁切回页面不应请求')
    now++
    const expired = cache.read()
    assert.equal(requests.length, 2, '30秒后需要读取最新积分')
    requests[1].resolve(balance(90))
    await expired

    const beforeMutation = cache.read(true)
    const oldRejected = assert.rejects(beforeMutation, {name: 'AbortError'})
    const afterMutation = cache.read(true)
    assert.equal(requests[2].signal.aborted, true)
    requests[3].resolve(balance(80))
    await afterMutation
    requests[2].resolve(balance(90))
    await oldRejected
    assert.equal((await cache.read()).remaining, 80, '晚返回的旧余额不能覆盖消费后的余额')

    const failed = cache.read(true)
    const failure = assert.rejects(failed, /offline/)
    requests[4].reject(new Error('offline'))
    await failure
    const retry = cache.read()
    assert.equal(requests.length, 6, '失败不得缓存，下一次允许重试')
    requests[5].resolve(balance(70))
    await retry

    const previousAccount = cache.read(true)
    const cancelled = assert.rejects(previousAccount, {name: 'AbortError'})
    cache.reset()
    const nextAccount = cache.read()
    requests[6].resolve(balance(70))
    await cancelled
    assert.equal(cache.read(), nextAccount, '旧请求结束不能清掉新会话的进行中请求')
    requests[7].resolve(balance(10))
    await nextAccount
    assert.equal((await cache.read()).remaining, 10)
})
