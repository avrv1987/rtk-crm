import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  crmEventKindLabels,
  crmEventStatusLabels,
  crmPreviewOutcomeLabels,
  crmRecordTypeLabels,
  previewNeedsAttention,
  queueSummary,
  retryAvailable,
  retryHint
} from '../src/features/sources/crmExchange.ts'

test('every event kind, status and preview outcome has a Russian label without service codes', () => {
  const labels = [
    ...Object.values(crmEventKindLabels),
    ...Object.values(crmEventStatusLabels),
    ...Object.values(crmPreviewOutcomeLabels),
    ...Object.values(crmRecordTypeLabels)
  ]
  assert.equal(Object.keys(crmEventKindLabels).length, 4)
  assert.equal(Object.keys(crmEventStatusLabels).length, 3)
  assert.equal(Object.keys(crmPreviewOutcomeLabels).length, 9)
  for (const label of labels) {
    assert.match(label, /[А-Яа-я]/)
    assert.doesNotMatch(label, /[A-Z_]{4,}/)
  }
})

test('preview marks only records that need an administrator decision', () => {
  const attention = Object.keys(crmPreviewOutcomeLabels).filter((outcome) => previewNeedsAttention(outcome as never))
  assert.deepEqual(attention.sort(), ['AMBIGUOUS', 'INVALID', 'NOT_FOUND', 'ORGANIZATION_UNRESOLVED', 'RESTRICTED'])
})

test('queue summary and retry offer follow the counters', () => {
  assert.equal(queueSummary({ queued: 2, sent: 14, error: 1 }), 'В очереди: 2, отправлено: 14, с ошибкой: 1')
  assert.equal(retryAvailable({ error: 0 }), false)
  assert.equal(retryHint({ error: 0 }), null)
  assert.equal(retryAvailable({ error: 3 }), true)
  assert.match(retryHint({ error: 3 }) ?? '', /Верните их в очередь/)
})
