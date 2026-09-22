import type {UploadQuota} from './types'

/** 单个登录会话的积分查询缓存；页面切换不取消共享请求，积分变化和会话结束时主动失效。 */
export function createQuotaCache(request: (signal: AbortSignal) => Promise<UploadQuota>) {
    let cached: UploadQuota | null = null
    let expiresAt = 0
    let pending: { controller: AbortController; promise: Promise<UploadQuota> } | null = null

    function reset() {
        pending?.controller.abort()
        pending = null
        cached = null
        expiresAt = 0
    }

    function read(force = false): Promise<UploadQuota> {
        // 强制刷新不能复用积分变化之前发出的请求。
        if (force) reset()
        if (pending) return pending.promise
        if (cached && Date.now() < expiresAt) return Promise.resolve(cached)
        const controller = new AbortController()
        const promise = request(controller.signal).then(result => {
            // 即使传输层未及时响应取消，也不让旧会话或旧余额写回缓存。
            controller.signal.throwIfAborted()
            cached = result
            expiresAt = Date.now() + 30_000
            return result
        }).finally(() => {
            if (pending?.controller === controller) pending = null
        })
        pending = {controller, promise}
        return promise
    }

    return {read, reset}
}
