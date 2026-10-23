import test from 'node:test'
import assert from 'node:assert/strict'
import {ApiError} from './api.ts'
import {readPolledGeneration} from './generationPolling.ts'

const response = (data: unknown, code = 200) => new Response(JSON.stringify({code, data, message: '任务不存在'}))

test('已删除任务退出轮询，并重新确认其他页面的活动任务', async t => {
    const storage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'localStorage', {configurable: true, value: {getItem: () => null}})
    t.after(() => {
        if (storage) Object.defineProperty(globalThis, 'localStorage', storage)
        else Reflect.deleteProperty(globalThis, 'localStorage')
    })

    await t.test('404 清理旧活动状态；没有新任务时返回空闲', async t => {
        const paths: string[] = []
        let taskId: string | null = 'deleted'
        t.mock.method(globalThis, 'fetch', async (url: string) => {
            paths.push(url)
            return url.endsWith('/deleted') ? response(null, 404) : response(null)
        })
        const observed = await readPolledGeneration(taskId, new AbortController().signal, () => {
            taskId = null
        })
        assert.equal(taskId, null)
        assert.equal(observed, null)
        assert.deepEqual(paths, ['/api/app/generations/deleted', '/api/app/generations/active'])
    })

    await t.test('404 后发现其他页面的新任务，下一轮只查询新 ID', async t => {
        const paths: string[] = []
        const running = {id: 'replacement', status: 'GENERATING'}
        let cleared = 0
        t.mock.method(globalThis, 'fetch', async (url: string) => {
            paths.push(url)
            return url.endsWith('/deleted') ? response(null, 404) : response(running)
        })
        const signal = new AbortController().signal
        const observed = await readPolledGeneration('deleted', signal, () => {
            cleared++
        })
        assert.deepEqual(observed, running)
        assert.deepEqual(await readPolledGeneration(observed!.id, signal, () => {
            cleared++
        }), running)
        assert.equal(cleared, 1)
        assert.deepEqual(paths, ['/api/app/generations/deleted', '/api/app/generations/active', '/api/app/generations/replacement'])
    })

    await t.test('活动任务回查断网后重试 active，不重新请求已删除任务', async t => {
        const paths: string[] = []
        let taskId: string | null = 'deleted'
        t.mock.method(globalThis, 'fetch', async (url: string) => {
            paths.push(url)
            if (url.endsWith('/deleted')) return response(null, 404)
            if (paths.length === 2) throw new TypeError('network unavailable')
            return response(null)
        })
        const signal = new AbortController().signal
        await assert.rejects(readPolledGeneration(taskId, signal, () => {
                taskId = null
            }),
            (error: unknown) => error instanceof ApiError && error.code === 0)
        assert.equal(taskId, null)
        assert.equal(await readPolledGeneration(taskId, signal, () => assert.fail('重复清理')), null)
        assert.deepEqual(paths, ['/api/app/generations/deleted', '/api/app/generations/active', '/api/app/generations/active'])
    })

    await t.test('普通网络错误保留已知任务，恢复后仍查询原任务', async t => {
        const paths: string[] = []
        const running = {id: 'running', status: 'GENERATING'}
        t.mock.method(globalThis, 'fetch', async (url: string) => {
            paths.push(url)
            if (paths.length === 1) throw new TypeError('network unavailable')
            return response(running)
        })
        const signal = new AbortController().signal
        const onMissing = () => assert.fail('断网不能清理活动任务')
        await assert.rejects(readPolledGeneration('running', signal, onMissing),
            (error: unknown) => error instanceof ApiError && error.code === 0)
        assert.deepEqual(await readPolledGeneration('running', signal, onMissing), running)
        assert.deepEqual(paths, ['/api/app/generations/running', '/api/app/generations/running'])
    })

    await t.test('已取消请求的迟到 404 不清理页面，也不启动回查', async t => {
        const paths: string[] = []
        t.mock.method(globalThis, 'fetch', async (url: string) => {
            paths.push(url)
            return response(null, 404)
        })
        const controller = new AbortController()
        controller.abort()
        await assert.rejects(readPolledGeneration('deleted', controller.signal, () => assert.fail('不能更新已退出页面')),
            (error: unknown) => error instanceof DOMException && error.name === 'AbortError')
        assert.deepEqual(paths, ['/api/app/generations/deleted'])
    })
})
