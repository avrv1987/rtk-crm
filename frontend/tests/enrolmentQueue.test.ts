import assert from 'node:assert/strict'
import test from 'node:test'
import { lmsStatusText, queueKinds, queueOrder } from '../src/features/enrolment/enrolmentQueueModel.ts'

test('у каждой очереди зачисления есть следующее действие и ссылка', () => {
  assert.equal(queueOrder.length, 8)
  for (const kind of queueOrder) {
    assert.ok(queueKinds[kind].next.length > 0, kind)
    assert.ok(['learner', 'stream', 'batch', 'reconcile'].includes(queueKinds[kind].action), kind)
  }
  assert.equal(queueKinds.MOVED_AFTER_TRANSFER.action, 'reconcile')
  assert.equal(queueKinds.AWAITING_RESULT.action, 'batch')
})

test('сформированный файл и отметка «передано» не называются принятыми LMS', () => {
  assert.notEqual(lmsStatusText.EXPORTED, lmsStatusText.TRANSFERRED)
  assert.ok(!lmsStatusText.SENT.includes('Принято'))
  assert.ok(!lmsStatusText.EXPORTED.includes('Передано'))
})
