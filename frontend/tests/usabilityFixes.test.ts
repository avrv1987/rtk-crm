import assert from 'node:assert/strict'
import { test } from 'node:test'
import { attachmentFileProblem } from '../src/features/documents/attachmentRules.ts'
import { courseNotMapped } from '../src/features/enrolment/enrolmentQueueModel.ts'
import { overlappingRules } from '../src/features/interactions/interactionKinds.ts'

test('rules of the same priority that can match one card overlap (Н-15)', () => {
  const rules = [
    { id: 'a', organizationType: 'COLLEGE' as const, programId: null, productId: null, priority: 50 },
    { id: 'b', organizationType: null, programId: 'p1', productId: null, priority: 50 },
    { id: 'c', organizationType: 'UNIVERSITY' as const, programId: null, productId: null, priority: 50 },
    { id: 'd', organizationType: 'COLLEGE' as const, programId: null, productId: null, priority: 10 }
  ]
  const draft = { id: null, organizationType: 'COLLEGE' as const, programId: 'p1', productId: null, priority: 50 }
  assert.deepEqual(overlappingRules(draft, rules).map((rule) => rule.id), ['a', 'b'])
  assert.deepEqual(overlappingRules({ ...draft, id: 'a' }, rules).map((rule) => rule.id), ['b'])
})

test('no Moodle course is not a questionnaire error (Н-11)', () => {
  assert.equal(courseNotMapped('Нет курса Moodle для потока: курс программы не сопоставлен'), true)
  assert.equal(courseNotMapped('Email указан неверно'), false)
  assert.equal(courseNotMapped(null), false)
})

test('file problems are found before upload (Н-4, Н-5)', () => {
  assert.equal(attachmentFileProblem({ name: 'договор.pdf', size: 100 }), null)
  assert.match(attachmentFileProblem({ name: `${'д'.repeat(256)}.pdf`, size: 100 }) ?? '', /Имя файла длиннее 255/)
  assert.match(attachmentFileProblem({ name: 'big.pdf', size: 21 * 1024 * 1024 }) ?? '', /больше 20 МБ/)
})
