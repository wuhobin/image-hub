import type { ImageRecord } from './types'

export const TOKEN_KEY = 'imagehub.token'
export const SESSION_EXPIRED = 'imagehub:session-expired'
export const getToken = () => localStorage.getItem(TOKEN_KEY)

export class ApiError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

export function unwrap<T>(status: number, body: unknown, token: string | null): T {
  const result = body as { code?: number; message?: string; data?: T } | null
  const code = status >= 200 && status < 300 ? result?.code : status
  if (code === 401 && token && getToken() === token) {
    localStorage.removeItem(TOKEN_KEY)
    window.dispatchEvent(new Event(SESSION_EXPIRED))
  }
  if (code !== 200) throw new ApiError(code || 502, result?.message || '服务暂不可用，请稍后重试')
  return result?.data as T
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken()
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  let response: Response
  try {
    response = await fetch('/api' + path, { ...init, headers })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, '无法连接服务器，请检查网络后重试')
  }
  const body: unknown = await response.json().catch(() => null)
  return unwrap<T>(response.status, body, token)
}

export function uploadImage(file: File, progress: (value: number) => void, signal: AbortSignal): Promise<ImageRecord> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    const token = getToken()
    xhr.open('POST', '/api/images')
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
    xhr.onerror = () => reject(new ApiError(0, '上传连接中断，请检查上传记录后重试'))
    xhr.ontimeout = () => reject(new ApiError(0, '上传超时，请检查上传记录后重试'))
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
