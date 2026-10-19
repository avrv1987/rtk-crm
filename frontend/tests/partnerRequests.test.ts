import assert from 'node:assert/strict'
import test from 'node:test'
import { answerable, partnerRequestActions, partnerRequestGroups } from '../src/features/partner/partnerRequestModel.ts'

test('кабинет координатора: «требуется от вуза», «отправлено нами» и закрытые запросы разделены', () => {
  const groups = partnerRequestGroups([
    { id: 'a', status: 'AWAITING_RESPONSE' as const },
    { id: 'b', status: 'RETURNED' as const },
    { id: 'c', status: 'RESPONDED' as const },
    { id: 'd', status: 'CLARIFICATION_NEEDED' as const },
    { id: 'e', status: 'ACCEPTED' as const },
    { id: 'f', status: 'CANCELLED' as const }
  ])
  assert.deepEqual(groups.required.map((item) => item.id), ['a', 'b'])
  assert.deepEqual(groups.sent.map((item) => item.id), ['c', 'd'])
  assert.deepEqual(groups.closed.map((item) => item.id), ['e', 'f'])
})

test('ответить можно на ожидающий и возвращённый запрос; КАМ проверяет только переданный ответ', () => {
  assert.equal(answerable('AWAITING_RESPONSE'), true)
  assert.equal(answerable('RETURNED'), true)
  assert.equal(answerable('RESPONDED'), false)
  assert.equal(answerable('CLARIFICATION_NEEDED'), true)
  assert.deepEqual(partnerRequestActions('RESPONDED'), { review: true, clarify: false, cancel: true })
  assert.deepEqual(partnerRequestActions('CLARIFICATION_NEEDED'), { review: false, clarify: true, cancel: true })
  assert.deepEqual(partnerRequestActions('ACCEPTED'), { review: false, clarify: false, cancel: false })
})
