export const MAX_FILES = 9
export const MAX_BYTES = 10 * 1024 * 1024
export const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']

export function fileError(file: { type: string; size: number }) {
  if (!ACCEPTED_TYPES.includes(file.type)) return '支持 JPG、PNG、WebP 和 GIF 格式'
  if (!file.size) return '图片文件为空，请重新选择'
  if (file.size > MAX_BYTES) return '单张图片不能超过 10 MB'
  return ''
}

export function passwordError(password: string) {
  return password.length < 6 ? '密码至少需要 6 位' : new TextEncoder().encode(password).length > 72 ? '密码的 UTF-8 长度不能超过 72 字节' : ''
}

export function formatSize(bytes: number) {
  if (bytes === 0) return '0 KB'
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

// 新接口返回北京时间；补齐 ISO 时区以避免浏览器按本地时区猜测，兼容原有 ISO 返回值。
export function toDateTime(value: string) {
  return value.includes(' ') ? value.replace(' ', 'T') + '+08:00' : value
}
