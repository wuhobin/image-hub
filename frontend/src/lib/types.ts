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
export type QuotaUsage = {
  id: number
    scene: 'IMAGE_UPLOAD' | 'AI_GENERATION' | 'DAILY_CHECK_IN' | 'CHECK_IN_BONUS'
    direction: 'INCOME' | 'EXPENSE'
  bizId: string
  amount: number
  description: string
  createTime: string
}

export type UploadQuota = { total: number; remaining: number; used: number; reserved: number }

export type CheckIn = {
    date: string; signedIn: boolean; consecutiveDays: number
    dailyPoints: number; bonusPoints: number; rewardPoints: number; nextResetAt: number
}

export type AiModel = { id: number; name: string; sizes: string[]; defaultSize: string; qualities: string[]; defaultQuality: string; pointsCost: number }
export type AiModelConfig = AiModel & { modelCode: string; baseUrl: string; imagesPath: string; keyConfigured: boolean; enabled: boolean; sortOrder: number; updateTime: string }
export type GenerationHistoryFilter = {
    status: 'done' | 'running' | 'failed'
    keyword: string
    order: 'asc' | 'desc'
}

export type Generation = {
  id: string; requestId: string; modelName: string; prompt: string; size: string; quality: string
  status: 'QUEUED' | 'GENERATING' | 'SAVING' | 'SAVE_FAILED' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED' | 'ABANDONED'
  errorMessage: string | null; createTime: string; durationSeconds: number | null; resultExpiresAt: string | null; image: ImageRecord | null
    shareId: string | null;
    shareStatus: 'PRIVATE' | 'PUBLIC' | 'BLOCKED';
    promptPublic: boolean
}

export type SharedCreation = {
    shareId: string; shareStatus: 'PUBLIC' | 'BLOCKED'; authorName: string
    imageUrl: string; width: number; height: number; modelId: number; modelName: string
    size: string; quality: string; promptPublic: boolean; prompt: string | null; publishedTime: string
}

export type CreationPreset = { modelId: number; size: string; quality: string }
