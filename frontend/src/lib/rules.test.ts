import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileError, MAX_BYTES, passwordError } from './rules.ts'

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

