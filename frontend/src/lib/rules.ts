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

export const GEMINI_IMAGE_RESOLUTIONS = ['512', ...GENERATION_RESOLUTIONS] as const

// Gemini 3.1 Flash Image 使用官方离散尺寸，不能套用 GPT 的等比计算或用像素最大公约数识别名义比例。
// https://ai.google.dev/gemini-api/docs/image-generation#aspect_ratios_and_image_size
export const GEMINI_IMAGE_PRESETS = [
    ['1:1', '512x512', '1024x1024', '2048x2048', '4096x4096'],
    ['1:4', '256x1024', '512x2048', '1024x4096', '2048x8192'],
    ['1:8', '192x1536', '384x3072', '768x6144', '1536x12288'],
    ['2:3', '424x632', '848x1264', '1696x2528', '3392x5056'],
    ['3:2', '632x424', '1264x848', '2528x1696', '5056x3392'],
    ['3:4', '448x600', '896x1200', '1792x2400', '3584x4800'],
    ['4:1', '1024x256', '2048x512', '4096x1024', '8192x2048'],
    ['4:3', '600x448', '1200x896', '2400x1792', '4800x3584'],
    ['4:5', '464x576', '928x1152', '1856x2304', '3712x4608'],
    ['5:4', '576x464', '1152x928', '2304x1856', '4608x3712'],
    ['8:1', '1536x192', '3072x384', '6144x768', '12288x1536'],
    ['9:16', '384x688', '768x1376', '1536x2752', '3072x5504'],
    ['16:9', '688x384', '1376x768', '2752x1536', '5504x3072'],
    // 官网 512 档的 21:9 标为 792×168；暂按原表保留，不自行推算替换。
    ['21:9', '792x168', '1584x672', '3168x1344', '6336x2688'],
].map(([ratio, ...sizes]) => ({ratio, sizes}))

export const GEMINI_IMAGE_SIZES = GEMINI_IMAGE_PRESETS.flatMap(preset => preset.sizes)

export function generationSizesForModel(modelCode: string) {
    return /^gemini-3\.1-flash-image(?:-|$)/.test(modelCode.trim()) ? GEMINI_IMAGE_SIZES : GENERATION_SIZES
}

export function generationResolution(size: string) {
    const gemini = GEMINI_IMAGE_PRESETS.find(item => item.sizes.includes(size))
    if (gemini) return GEMINI_IMAGE_RESOLUTIONS[gemini.sizes.indexOf(size)]
  const preset = GENERATION_PRESETS.find(item => Object.values(item).includes(size))
  return GENERATION_RESOLUTIONS.find(tier => preset?.[tier] === size) || ''
}

// 保留可用的同档位；比例不支持时优先降至最近档位，并始终受服务端白名单约束。
export function generationSizeForRatio(sizes: string[], ratio: string, previous: string) {
  const candidates = sizes.filter(size => imageAspectRatio(size) === ratio)
  const tier = generationResolution(previous)
    const rank = (size: string) => GEMINI_IMAGE_RESOLUTIONS.findIndex(item => item === generationResolution(size))
  return candidates.find(size => generationResolution(size) === tier)
      || candidates.filter(size => rank(size) < rank(previous)).sort((a, b) => rank(b) - rank(a))[0]
    || candidates[0] || ''
}

export function imageAspectRatio(size: string) {
    const gemini = GEMINI_IMAGE_PRESETS.find(item => item.sizes.includes(size))
    if (gemini) return gemini.ratio
    if (!/^[1-9]\d{0,4}x[1-9]\d{0,4}$/.test(size)) return size
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
