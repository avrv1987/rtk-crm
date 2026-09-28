import assert from 'node:assert/strict'
import { test } from 'node:test'
import { deadlineGroup } from '../src/features/work/deadlines.ts'

const now = Date.parse('2026-09-30T10:00:00+03:00')

test('срок раскладывается по группам ленты по московскому календарю', () => {
  assert.equal(deadlineGroup('2026-09-30T09:59:00+03:00', now), 'overdue')
  assert.equal(deadlineGroup('2026-09-30T23:59:00+03:00', now), 'today')
  assert.equal(deadlineGroup('2026-09-30T21:30:00Z', now), 'week')
  assert.equal(deadlineGroup('2026-10-04T23:59:00+03:00', now), 'week')
  assert.equal(deadlineGroup('2026-10-05T00:00:00+03:00', now), 'later')
})

test('в воскресенье неделя заканчивается сегодня', () => {
  const sunday = Date.parse('2026-10-04T12:00:00+03:00')
  assert.equal(deadlineGroup('2026-10-04T20:00:00+03:00', sunday), 'today')
  assert.equal(deadlineGroup('2026-10-05T09:00:00+03:00', sunday), 'later')
})
