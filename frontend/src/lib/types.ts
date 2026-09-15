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
  sourceType: 'UPLOAD' | 'AI'
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
export type UploadQuota = { total: number; remaining: number; used: number; reserved: number }

export type AiModel = { id: number; name: string; sizes: string[]; defaultSize: string; qualities: string[]; defaultQuality: string }
export type AiModelConfig = AiModel & { modelCode: string; baseUrl: string; imagesPath: string; keyConfigured: boolean; enabled: boolean; sortOrder: number; updateTime: string }
export type Generation = {
  id: string; requestId: string; modelName: string; prompt: string; size: string; quality: string
  status: 'QUEUED' | 'GENERATING' | 'SAVING' | 'SAVE_FAILED' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED' | 'ABANDONED'
  errorMessage: string | null; createTime: string; resultExpiresAt: string | null; image: ImageRecord | null
}
