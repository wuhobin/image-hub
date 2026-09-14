import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileError, formatSize, MAX_BYTES, passwordError, toDateTime } from './rules.ts'

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
