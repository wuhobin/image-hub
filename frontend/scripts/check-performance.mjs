import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { gzipSync } from 'node:zlib'

const dist = new URL('../dist/', import.meta.url)
const manifest = JSON.parse(await readFile(new URL('.vite/manifest.json', dist), 'utf8'))
const visited = new Set()
const scripts = []
const styles = new Set()

// 只跟随静态依赖：异步页面、WebGL、上传进度都不应回流到首屏关键路径。
async function visit(key) {
  if (visited.has(key)) return
  visited.add(key)
  assert(!/Starfield|UploadProgress|animation|pages\/(Auth|History)/i.test(key), '装饰或非首页模块进入首屏: ' + key)
  const chunk = manifest[key]
  assert(chunk, '缺少构建清单条目: ' + key)
  scripts.push(await readFile(new URL(chunk.file, dist)))
  for (const css of chunk.css ?? []) styles.add(css)
  for (const dependency of chunk.imports ?? []) await visit(dependency)
}
await visit('index.html')
const rawBytes = scripts.reduce((sum, script) => sum + script.length, 0)
const gzipBytes = scripts.reduce((sum, script) => sum + gzipSync(script).length, 0)
assert(rawBytes <= 330_000, '首屏 JS 超过 330 KB: ' + rawBytes)
assert(gzipBytes <= 105_000, '首屏 JS gzip 超过 105 KB: ' + gzipBytes)
const html = await readFile(new URL('index.html', dist), 'utf8')
assert(!/<link\b[^>]+(?:href=["']https?:|href=["']\/\/)/i.test(html), '首屏 HTML 引入了外部样式或预加载资源')
for (const file of styles) {
  const css = await readFile(new URL(file, dist), 'utf8')
  assert(!/@import\s*(?:url\()?\s*["']?(?:https?:)?\/\//i.test(css), 'CSS 引入了外部阻塞样式: ' + file)
}
console.log('首屏性能预算通过: ' + rawBytes + ' bytes JS, ' + gzipBytes + ' bytes gzip；动画与非首页路由保持异步加载。')
