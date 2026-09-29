import assert from 'node:assert/strict'
import { test } from 'node:test'
import { historyGroups, historyTitle, kindsOfGroup } from '../src/features/organizations/organizationHistory.ts'
import type { OrganizationHistoryItem } from '../src/shared/api/client.ts'

const item = (patch: Partial<OrganizationHistoryItem>): OrganizationHistoryItem => ({
  id: '1',
  kind: 'COMMENTED',
  occurredAt: '2026-09-12T07:00:00Z',
  actorName: 'Ирина',
  interactionId: null,
  interactionTitle: null,
  fromStageName: null,
  stageName: null,
  description: null,
  comment: null,
  contactId: null,
  contactName: null,
  changes: null,
  ...patch
})

test('группы фильтра покрывают каждый вид события ровно один раз', () => {
  const kinds = historyGroups.flatMap((group) => group.kinds)
  assert.equal(new Set(kinds).size, kinds.length)
  assert.equal(kinds.length, 13)
  assert.deepEqual(kindsOfGroup('ALL'), [])
  assert.deepEqual(kindsOfGroup('ASSIGNMENT'), ['ASSIGNMENT'])
})

test('заголовок события назван по виду и подробностям', () => {
  assert.equal(historyTitle(item({ kind: 'COMMENTED' })), 'Добавлен комментарий')
  assert.equal(
    historyTitle(item({ kind: 'TRANSITIONED', fromStageName: 'Встреча', stageName: 'Договор' })),
    'Этап изменён: Встреча → Договор'
  )
  assert.equal(historyTitle(item({ kind: 'STAGE_COMPLETED', stageName: 'Занятия' })), 'Этап отмечен выполненным: Занятия')
  assert.equal(
    historyTitle(item({ kind: 'ASSIGNMENT', description: 'Назначен ответственный: Ирина.' })),
    'Назначен ответственный: Ирина.'
  )
  assert.equal(historyTitle(item({ kind: 'CONTACT', contactName: 'Проректор' })), 'Изменён контакт: Проректор')
})
