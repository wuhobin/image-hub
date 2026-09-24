import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
    calculateGenerationSize,
    fileError,
    formatSize,
    GENERATION_PRESETS,
    GENERATION_SIZES,
    GEMINI_IMAGE_PRESETS,
    GEMINI_IMAGE_SIZES,
    GEMINI_IMAGE_RESOLUTIONS,
    generationSizesForModel,
    generationResolution,
    generationSizeForRatio,
    imageAspectRatio,
    MAX_BYTES,
    passwordError,
    toDateTime
} from './rules.ts'

test('resolution presets retain exact aspect ratios and stay inside GPT Image 2 limits', () => {
  assert.deepEqual(GENERATION_PRESETS.map(item => item.ratio), ['1:1', '3:2', '2:3', '16:9', '9:16', '4:3', '3:4', '21:9'])
  assert.equal(new Set(GENERATION_SIZES).size, 24)
  assert.deepEqual(['1K', '2K', '4K'].map(tier => calculateGenerationSize('1:1', tier as '1K' | '2K' | '4K')), ['1024x1024', '2048x2048', '2880x2880'])
  assert.deepEqual(['1K', '2K', '4K'].map(tier => calculateGenerationSize('4:3', tier as '1K' | '2K' | '4K')), ['1024x768', '2048x1536', '3200x2400'])
  const expected = [
    ['1:1', '1024x1024', '2048x2048', '2880x2880'],
    ['3:2', '1536x1024', '2160x1440', '3456x2304'],
    ['2:3', '1024x1536', '1440x2160', '2304x3456'],
    ['16:9', '1280x720', '2560x1440', '3840x2160'],
    ['9:16', '720x1280', '1440x2560', '2160x3840'],
    ['4:3', '1024x768', '2048x1536', '3200x2400'],
    ['3:4', '768x1024', '1536x2048', '2400x3200'],
    ['21:9', '1344x576', '2016x864', '3808x1632'],
  ]
  assert.deepEqual(GENERATION_PRESETS.map(preset => [preset.ratio, preset['1K'], preset['2K'], preset['4K']]), expected)
  for (const preset of GENERATION_PRESETS) {
    const [rw, rh] = preset.ratio.split(':').map(Number)
    for (const tier of ['1K', '2K', '4K'] as const) {
      const size = preset[tier]
      if (!size) continue
      const [width, height] = size.split('x').map(Number)
      assert.equal(width * rh, height * rw)
      assert.equal(imageAspectRatio(size), preset.ratio)
      assert.equal(generationResolution(size), tier)
      assert.equal(width % 16, 0)
      assert.equal(height % 16, 0)
      assert.ok(Math.max(width, height) <= 3840)
      assert.ok(Math.max(width, height) <= 3 * Math.min(width, height))
      assert.ok(width * height >= 655360 && width * height <= 8294400)
    }
  }
  assert.equal(generationSizeForRatio(GENERATION_SIZES, '16:9', '1024x1024'), '1280x720')
  assert.equal(generationSizeForRatio(GENERATION_SIZES, '16:9', '2048x2048'), '2560x1440')
  assert.equal(generationSizeForRatio(GENERATION_SIZES, '9:16', '3840x2160'), '2160x3840')
  assert.equal(generationSizeForRatio(GENERATION_SIZES, '1:1', '3840x2160'), '2880x2880')
  assert.equal(generationSizeForRatio(['1280x720'], '16:9', '2048x2048'), '1280x720')
  assert.equal(generationSizeForRatio(['1536x864'], '16:9', '2048x2048'), '1536x864')
  assert.equal(generationSizeForRatio([], '1:1', '2048x2048'), '')
  assert.equal(generationResolution('1536x864'), '')
  assert.equal(imageAspectRatio('2560x1080'), '64:27')
  for (const size of ['', 'auto', '0x0', '1024x', 'bad']) assert.equal(imageAspectRatio(size), size)
})

test('Gemini presets preserve official nominal ratios, four tiers and extreme dimensions without changing GPT options', () => {
    assert.deepEqual(GEMINI_IMAGE_PRESETS.map(item => item.ratio), ['1:1', '1:4', '1:8', '2:3', '3:2', '3:4', '4:1', '4:3', '4:5', '5:4', '8:1', '9:16', '16:9', '21:9'])
    assert.equal(new Set(GEMINI_IMAGE_SIZES).size, 56)
    for (const code of ['gemini-3.1-flash-image', 'gemini-3.1-flash-image-preview']) {
        assert.equal(generationSizesForModel(code), GEMINI_IMAGE_SIZES)
    }
    for (const code of ['gpt-image-2', 'gpt-image-2-preview', 'gemini-3.1-flash-imageother']) {
        assert.equal(generationSizesForModel(code), GENERATION_SIZES)
    }
    for (const preset of GEMINI_IMAGE_PRESETS) {
        preset.sizes.forEach((size, index) => {
            const [width, height] = size.split('x').map(Number)
            assert.equal(imageAspectRatio(size), preset.ratio)
            assert.equal(generationResolution(size), GEMINI_IMAGE_RESOLUTIONS[index])
            assert.ok(width * height <= 18874368)
        })
    }
    assert.equal(imageAspectRatio('848x1264'), '2:3')
    assert.equal(imageAspectRatio('1376x768'), '16:9')
    assert.equal(generationResolution('4096x4096'), '4K')
    assert.equal(generationSizeForRatio(GEMINI_IMAGE_SIZES, '1:8', '4096x4096'), '1536x12288')
    assert.equal(generationSizeForRatio(GEMINI_IMAGE_SIZES, '8:1', '1536x12288'), '12288x1536')
    assert.equal(generationSizeForRatio(GEMINI_IMAGE_SIZES, '1:8', '512x512'), '192x1536')
    assert.equal(generationSizeForRatio(['192x1536', '384x3072'], '1:8', '4096x4096'), '384x3072')
    assert.equal(generationSizeForRatio(['192x1536'], '1:8', '1024x1024'), '192x1536')
    assert.equal(generationSizeForRatio(GENERATION_SIZES, '1:8', '1024x1024'), '')
})

test('empty storage displays zero while nonempty files retain their size formatting', () => {
  assert.equal(formatSize(0), '0 KB')
  assert.equal(formatSize(100), '1 KB')
  assert.equal(formatSize(2048), '2 KB')
  assert.equal(formatSize(1024 * 1024), '1.0 MB')
})

test('upload boundaries reject empty, unsupported and oversized files; passwords have a minimum length', () => {
  assert.equal(fileError({ type: 'image/png', size: MAX_BYTES }), '')
  assert.ok(fileError({ type: 'image/png', size: MAX_BYTES + 1 }))
  assert.ok(fileError({ type: 'image/png', size: 0 }))
  assert.ok(fileError({ type: 'image/svg+xml', size: 20 }))
  assert.ok(fileError({ type: 'text/plain', size: 20 }))
  for (const type of ['image/jpeg', 'image/webp', 'image/gif']) assert.equal(fileError({ type, size: 20 }), '')
  assert.ok(passwordError('12345'))
  assert.equal(passwordError('123456'), '')
})

test('server dates preserve their instant across the Shanghai date boundary and legacy ISO responses', () => {
  assert.equal(new Date(toDateTime('2026-09-14 01:57:42')).toISOString(), '2026-09-13T17:57:42.000Z')
  assert.equal(toDateTime('2026-09-14T01:57:42Z'), '2026-09-14T01:57:42Z')
})
