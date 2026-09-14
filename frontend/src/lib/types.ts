export type ImageRecord = {
  id: string
  name: string
  preview: string
  url: string
  size: number
  width: number
  height: number
  createdAt: string
  type: string
}

export type PendingImage = {
  id: string
  file: File
  preview: string
  width: number
  height: number
  status: 'ready' | 'uploading' | 'done' | 'error'
  progress: number
  error?: string
  record?: ImageRecord
}

export type User = { id: string; username: string; email: string }
export type LoginResult = { token: string; expiresIn: number; user: User }
/** MyBatis-Plus 原生分页响应。 */
export type Page<T> = {
  records: T[]
  total: number
  current: number
  size: number
  pages: number
}
export type ImageList = { page: Page<ImageRecord>; totalBytes: number }
export type UploadQuota = { total: number; remaining: number }
