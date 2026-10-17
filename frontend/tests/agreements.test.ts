import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  activeStageRole,
  agreementStatusLabels,
  agreementStatuses,
  daysInStatus,
  defaultSignerId,
  emptyRegistryFilters,
  inputFromAgreement,
  periodDefaults,
  registrySearch,
  registryTileCounts,
  registryTileFilters,
  registryTileOf,
  supplementStatusLabels,
  transitionFields
} from '../src/features/agreements/agreementModel.ts'

test('семь статусов договора подписаны по-русски, без служебных кодов', () => {
  assert.deepEqual(agreementStatuses.map((status) => agreementStatusLabels[status]), [
    'Проект', 'На согласовании', 'На корректировке', 'На подписании', 'Действует', 'Завершён', 'Расторгнут'
  ])
  assert.deepEqual(Object.values(supplementStatusLabels), ['Проект', 'Подписано'])
})

test('запрос реестра повторяет статус и пропускает пустые отборы', () => {
  assert.equal(registrySearch(emptyRegistryFilters), '')
  assert.equal(
    registrySearch({ ...emptyRegistryFilters, statuses: ['SIGNING', 'REVIEW'], managerId: 'm1', reviewLongerThanDays: ' 30 ' }),
    '?status=REVIEW&status=SIGNING&managerId=m1&reviewLongerThanDays=30'
  )
  assert.equal(registrySearch({ ...emptyRegistryFilters, organizationId: 'o1', expiresBefore: '2026-12-31' }), '?organizationId=o1&expiresBefore=2026-12-31')
})

test('дни в статусе считаются полными сутками и не бывают отрицательными', () => {
  const now = new Date('2026-10-17T12:00:00Z').getTime()
  assert.equal(daysInStatus('2026-10-17T09:00:00Z', now), 0)
  assert.equal(daysInStatus('2026-10-16T12:00:00Z', now), 1)
  assert.equal(daysInStatus('2026-09-17T11:00:00Z', now), 30)
  assert.equal(daysInStatus('2026-10-18T00:00:00Z', now), 0)
  assert.equal(daysInStatus('не дата', now), 0)
})

test('подписант вуза по умолчанию — первый ЛПР, без ЛПР — не выбран', () => {
  assert.equal(defaultSignerId([{ id: 'a', signatory: false }, { id: 'b', signatory: true }, { id: 'c', signatory: true }]), 'b')
  assert.equal(defaultSignerId([{ id: 'a', signatory: false }]), '')
  assert.equal(defaultSignerId([]), '')
})

test('диалог перехода спрашивает дату подписания только без неё, дату отправки — при отправке на согласование', () => {
  assert.deepEqual(transitionFields('ACTIVE', { concludedOn: null }), { signedOn: true, validUntil: true, sentOn: false })
  assert.deepEqual(transitionFields('ACTIVE', { concludedOn: '2026-10-01' }), { signedOn: false, validUntil: false, sentOn: false })
  assert.deepEqual(transitionFields('REVIEW', {}), { signedOn: false, validUntil: false, sentOn: true })
  assert.deepEqual(transitionFields('TERMINATED', {}), { signedOn: false, validUntil: false, sentOn: false })
})

test('роль текущего этапа работы определяется по списку этапов договора', () => {
  const stages = [{ stageId: 's1', role: 'EXCHANGE' as const }, { stageId: 's3', role: 'SIGNING' as const }]
  assert.equal(activeStageRole(stages, 's3'), 'SIGNING')
  assert.equal(activeStageRole(stages, 's2'), null)
  assert.equal(activeStageRole([], 's1'), null)
})

test('период отчёта по умолчанию — с 1 января по сегодня', () => {
  assert.deepEqual(periodDefaults('2026-10-17'), { from: '2026-01-01', to: '2026-10-17' })
})

test('изменение из карточки сохраняет реквизиты и не трогает статус и связи', () => {
  const input = inputFromAgreement({
    version: 4,
    number: 'Д-1',
    concludedOn: '2026-03-01',
    validUntil: null,
    sentOn: '2026-02-01',
    parties: null,
    plannedKind: null,
    plannedOn: null,
    universitySigner: { id: 'c1' },
    schoolSigner: null
  })
  assert.equal(input.status, null)
  assert.equal(input.interactionIds, null)
  assert.equal(input.directionIds, null)
  assert.equal(input.universitySignerContactId, 'c1')
  assert.equal(input.schoolSignerProfileId, null)
  assert.equal(input.concludedOn, '2026-03-01')
  assert.equal(input.version, 4)
})

test('плитки реестра: счёт по статусам и «Истекает за 60 дней» только у действующих', () => {
  const counts = registryTileCounts([
    { status: 'REVIEW' },
    { status: 'REVIEW' },
    { status: 'SIGNING' },
    { status: 'ACTIVE', validUntil: '2026-12-01' },
    { status: 'ACTIVE', validUntil: '2027-06-01' },
    { status: 'ACTIVE', validUntil: '2026-10-01' },
    { status: 'COMPLETED', validUntil: '2026-11-01' }
  ], '2026-10-17')
  assert.deepEqual(counts, { REVIEW: 2, CORRECTION: 0, SIGNING: 1, ACTIVE: 3, EXPIRING: 1 })
  assert.deepEqual(registryTileFilters('EXPIRING', '2026-10-17'), { statuses: ['ACTIVE'], expiresBefore: '2026-12-16' })
  assert.deepEqual(registryTileFilters('SIGNING', '2026-10-17'), { statuses: ['SIGNING'], expiresBefore: '' })
  assert.deepEqual(registryTileFilters(null, '2026-10-17'), { statuses: [], expiresBefore: '' })
  assert.equal(registryTileOf({ ...emptyRegistryFilters, statuses: ['ACTIVE'], expiresBefore: '2026-12-16' }, '2026-10-17'), 'EXPIRING')
  assert.equal(registryTileOf({ ...emptyRegistryFilters, statuses: ['ACTIVE'] }, '2026-10-17'), 'ACTIVE')
  assert.equal(registryTileOf({ ...emptyRegistryFilters, statuses: ['ACTIVE', 'SIGNING'] }, '2026-10-17'), null)
})
