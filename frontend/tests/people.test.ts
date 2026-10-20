import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { PeopleOverview, QualificationRecord } from '../src/features/people/peopleApi.ts'
import { draftFrom, frdoText, hoursText, payloadFrom, peopleFields } from '../src/features/people/peopleForms.ts'
import { adminSectionsFor } from '../src/features/admin/adminSections.ts'

const options: PeopleOverview['options'] = {
  employees: [{ id: 'p1', title: 'Лектор Сотрудникович' }],
  interactions: [],
  agreements: [],
  activities: [{ id: 'a1', agreementId: 'g1', agreementNumber: '7', title: 'Преподавание практиков', kindName: null }],
  teacherRosters: [],
  teachers: []
}

test('новая запись берёт единицу часа из настроек и взаимодействие карточки', () => {
  const draft = draftFrom('internships', null, { hourUnit: 'ASTRONOMICAL', interactionId: 'w1' })
  assert.equal(draft.hourUnit, 'ASTRONOMICAL')
  assert.equal(draft.interactionId, 'w1')
  assert.equal(draft.employed, false)
})

test('запрос: пустые поля — null, часы с запятой — число, версия передаётся', () => {
  const draft = draftFrom('teaching', null, { hourUnit: 'ACADEMIC' })
  const payload = payloadFrom('teaching', { ...draft, discipline: ' Сети ', hours: '16,5', performerProfileId: 'p1' }, 3)
  assert.equal(payload.discipline, 'Сети')
  assert.equal(payload.hours, 16.5)
  assert.equal(payload.performerName, null)
  assert.equal(payload.version, 3)
})

test('при правке записи сотрудника из справочника имя не попадает в ФИО исполнителя группы компаний', () => {
  const draft = draftFrom('teaching', { performerProfileId: 'p1', performerName: 'Лектор Сотрудникович', hours: 36 }, { hourUnit: 'ACADEMIC' })
  assert.equal(draft.performerName, '')
  assert.equal(draft.hours, '36')
})

test('мероприятие договора подписано номером договора', () => {
  const field = peopleFields.teaching.find((item) => item.key === 'agreementActivityId')
  assert.deepEqual(field?.choices?.(options), [{ value: 'a1', label: '№ 7: Преподавание практиков' }])
})

test('ФРДО: передано, просрочено, срок, не задан', () => {
  const base = { result: 'CERTIFIED', frdoSubmittedOn: null, frdoDueOn: null, frdoOverdue: false } as unknown as QualificationRecord
  const date = (value: string) => value
  assert.equal(frdoText({ ...base, frdoSubmittedOn: '2026-10-01' }, date), 'передано 2026-10-01')
  assert.equal(frdoText({ ...base, frdoDueOn: '2026-10-01', frdoOverdue: true }, date), 'просрочено: срок 2026-10-01')
  assert.equal(frdoText({ ...base, frdoDueOn: '2026-12-01' }, date), 'передать до 2026-12-01')
  assert.equal(frdoText(base, date), 'не передано')
  assert.equal(frdoText({ ...base, result: 'IN_PROGRESS' }, date), '—')
  assert.equal(hoursText(null, 'ACADEMIC'), '—')
})

test('настройки учёта людей и часов — только у администратора', () => {
  assert.ok(adminSectionsFor('ADMIN').some((section) => section.slug === 'people-settings'))
  assert.ok(!adminSectionsFor('LEADER').some((section) => section.slug === 'people-settings'))
})
