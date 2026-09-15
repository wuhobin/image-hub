export const GENERATION_RESOLUTIONS = ['1K', '2K', '4K'] as const

// 保持精确比例：按最长边选择档位，超出总像素上限时等比缩小；尺寸始终对齐 16 像素。
export function calculateGenerationSize(ratio: string, resolution: typeof GENERATION_RESOLUTIONS[number]) {
  if (!/^[1-9]\d?:[1-9]\d?$/.test(ratio)) return ''
  let [width, height] = ratio.split(':').map(Number)
  if (Math.max(width, height) > 3 * Math.min(width, height)) return ''
  let a = width, b = height
  while (b) [a, b] = [b, a % b]
  width /= a
  height /= a
  const long = Math.max(width, height), short = Math.min(width, height)
  // 不同比例采用约定的分辨率基准，横竖版共用计算；不能统一按 1024/2048 最长边截断。
  const edges = long === 3 && short === 2 ? { '1K': 1536, '2K': 2160, '4K': 3840 }
    : long === 16 && short === 9 ? { '1K': 1280, '2K': 2560, '4K': 3840 }
    : { '1K': 1024, '2K': 2048, '4K': 3840 }
  const edge = edges[resolution]
  let scale = Math.floor(edge / Math.max(width, height) / 16) * 16
  if (width * height * scale * scale > 8294400) {
    // 超限缩图使用 32 像素的比例单位，得到 1:1 的 2880²、4:3 的 3200×2400。
    scale = Math.floor(Math.sqrt(8294400 / (width * height)) / 32) * 32
  }
  if (width * height * scale * scale < 655360) {
    scale = Math.ceil(Math.sqrt(655360 / (width * height)) / 16) * 16
  }
  return `${width * scale}x${height * scale}`
}

export const GENERATION_PRESETS = ['1:1', '3:2', '2:3', '16:9', '9:16', '4:3', '3:4', '21:9'].map(ratio => ({
  ratio,
  '1K': calculateGenerationSize(ratio, '1K'),
  '2K': calculateGenerationSize(ratio, '2K'),
  '4K': calculateGenerationSize(ratio, '4K'),
}))
export const GENERATION_SIZES = GENERATION_PRESETS.flatMap(preset => GENERATION_RESOLUTIONS.map(tier => preset[tier]))

export function generationResolution(size: string) {
  const preset = GENERATION_PRESETS.find(item => Object.values(item).includes(size))
  return GENERATION_RESOLUTIONS.find(tier => preset?.[tier] === size) || ''
}

// 保留可用的同档位；比例不支持时优先降至最近档位，并始终受服务端白名单约束。
export function generationSizeForRatio(sizes: string[], ratio: string, previous: string) {
  const candidates = sizes.filter(size => imageAspectRatio(size) === ratio)
  const tier = generationResolution(previous)
  return candidates.find(size => generationResolution(size) === tier)
    || candidates.filter(size => generationResolution(size) < tier).sort((a, b) => generationResolution(b).localeCompare(generationResolution(a)))[0]
    || candidates[0] || ''
}

export function imageAspectRatio(size: string) {
  if (!/^[1-9]\d{0,3}x[1-9]\d{0,3}$/.test(size)) return size
  const [width, height] = size.split('x').map(Number)
  let a = width, b = height
  while (b) [a, b] = [b, a % b]
  const ratio = `${width / a}:${height / a}`
  return ratio === '7:3' ? '21:9' : ratio
}

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
