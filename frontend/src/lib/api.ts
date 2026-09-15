import type { ImageRecord } from './types'

export const TOKEN_KEY = 'imagehub.token'
export const SESSION_EXPIRED = 'imagehub:session-expired'
export type ApiSession = { basePath: string; tokenKey: string; expiredEvent: string }
const USER_SESSION: ApiSession = { basePath: '/api/app', tokenKey: TOKEN_KEY, expiredEvent: SESSION_EXPIRED }
export const getToken = (session: ApiSession = USER_SESSION) => localStorage.getItem(session.tokenKey)

export class ApiError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

export function unwrap<T>(status: number, body: unknown, token: string | null, session: ApiSession = USER_SESSION): T {
  const result = body as { code?: number; message?: string; data?: T } | null
  const code = status >= 200 && status < 300 ? result?.code : status
  if (code === 401 && token && getToken(session) === token) {
    localStorage.removeItem(session.tokenKey)
    window.dispatchEvent(new Event(session.expiredEvent))
  }
  if (code !== 200) throw new ApiError(code || 502, result?.message || '服务暂不可用，请稍后重试')
  return result?.data as T
}

export async function api<T>(path: string, init: RequestInit = {}, session: ApiSession = USER_SESSION): Promise<T> {
  const token = getToken(session)
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  let response: Response
  try {
    response = await fetch(session.basePath + path, { ...init, headers })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, '无法连接服务器，请检查网络后重试')
  }
  const body: unknown = await response.json().catch(() => null)
  return unwrap<T>(response.status, body, token, session)
}

export function uploadImage(file: File, progress: (value: number) => void, signal: AbortSignal): Promise<ImageRecord> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    const token = getToken()
    xhr.open('POST', USER_SESSION.basePath + '/images')
    xhr.timeout = 120000
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    const abort = () => xhr.abort()
    signal.addEventListener('abort', abort, { once: true })
    xhr.onloadend = () => signal.removeEventListener('abort', abort)
    xhr.upload.onprogress = event => {
      if (event.lengthComputable) progress(Math.min(99, Math.round(event.loaded / event.total * 100)))
    }
    xhr.onload = () => {
      let body: unknown = null
      try { body = JSON.parse(xhr.responseText) } catch { /* Handled by unwrap. */ }
      try { resolve(unwrap<ImageRecord>(xhr.status, body, token)) } catch (error) { reject(error) }
    }
    xhr.onerror = () => reject(new ApiError(0, '上传连接中断，请检查“我的图片”后重试'))
    xhr.ontimeout = () => reject(new ApiError(0, '上传超时，请检查“我的图片”后重试'))
    xhr.onabort = () => reject(new DOMException('上传已取消', 'AbortError'))
    if (signal.aborted) {
      signal.removeEventListener('abort', abort)
      reject(new DOMException('上传已取消', 'AbortError'))
      return
    }
    const data = new FormData()
    data.append('file', file)
    xhr.send(data)
  })
}
