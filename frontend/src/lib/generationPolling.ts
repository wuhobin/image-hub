import {api, ApiError} from './api.ts'
import type {Generation} from './types'

/** 任务被其他页面删除后改查活动任务；先清理旧状态，回查失败时也不再轮询已删除的 ID。 */
export async function readPolledGeneration(taskId: string | null, signal: AbortSignal, onMissing: () => void): Promise<Generation | null> {
    if (taskId) {
        try {
            return await api<Generation>('/generations/' + taskId, {signal})
        } catch (error) {
            if (!(error instanceof ApiError) || error.code !== 404) throw error
            signal.throwIfAborted()
            onMissing()
        }
    }
    return api<Generation | null>('/generations/active', {signal})
}
