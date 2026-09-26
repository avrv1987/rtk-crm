import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  changedDraft,
  conflictKeys,
  editedKeys,
  keepDraftValues,
  mergedValues,
  parseBasedDraft,
  takeCurrentValues
} from '../src/features/interactions/draftBase.ts'

type Contact = { name: string; phone: string; email: string }

const asContact = (value: unknown) => (typeof value === 'object' && value !== null ? value as Contact : null)

const loaded: Contact = { name: 'Ирина', phone: '+7 000 000-00-01', email: 'irina@example.test' }

test('восстановленный черновик не откатывает чужую правку поля, которое пользователь не менял', () => {
  const draft = changedDraft(null, loaded, { email: 'irina.new@example.test' })
  const stored = parseBasedDraft(JSON.parse(JSON.stringify(draft)), asContact)
  const changedByLeader: Contact = { ...loaded, phone: '+7 000 000-00-02' }

  assert.deepEqual(editedKeys(stored), ['email'])
  assert.deepEqual(conflictKeys(stored, changedByLeader), [])
  assert.deepEqual(mergedValues(stored, changedByLeader), {
    name: 'Ирина',
    phone: '+7 000 000-00-02',
    email: 'irina.new@example.test'
  })
})

test('правка того же поля другим сотрудником показывается как конфликт и решается явно', () => {
  const draft = changedDraft(null, loaded, { phone: '+7 000 000-00-03' })
  const changedByLeader: Contact = { ...loaded, phone: '+7 000 000-00-02', name: 'Ирина Петровна' }

  assert.deepEqual(conflictKeys(draft, changedByLeader), ['phone'])

  const kept = keepDraftValues(draft, changedByLeader)
  assert.deepEqual(conflictKeys(kept, changedByLeader), [])
  assert.deepEqual(mergedValues(kept, changedByLeader), { ...changedByLeader, phone: '+7 000 000-00-03' })

  const taken = takeCurrentValues(draft, changedByLeader)
  assert.deepEqual(editedKeys(taken), [])
  assert.deepEqual(mergedValues(taken, changedByLeader), changedByLeader)
})

test('одинаковая правка у обоих не считается конфликтом, повреждённый черновик отбрасывается', () => {
  const draft = changedDraft(changedDraft(null, loaded, { name: 'Ирина Петровна' }), loaded, { phone: '+7 000 000-00-04' })
  assert.deepEqual(conflictKeys(draft, { ...loaded, name: 'Ирина Петровна' }), [])
  assert.deepEqual(draft.base, loaded)
  assert.equal(parseBasedDraft({ values: loaded }, asContact), null)
  assert.equal(parseBasedDraft('текст', asContact), null)
})
