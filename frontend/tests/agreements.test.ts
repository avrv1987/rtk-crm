import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  activeStageRole,
  agreementStatusLabels,
  agreementTerm,
  agreementStatuses,
  approvalDelayed,
  approvalText,
  daysInStatus,
  defaultSignerId,
  emptyRegistryFilters,
  inputFromAgreement,
  linkCandidates,
  linkInput,
  partnerApprovalLine,
  periodDefaults,
  registrySearch,
  registryTileCounts,
  registryTileFilters,
  registryTileOf,
  signedFileName,
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
  assert.equal(registrySearch({ ...emptyRegistryFilters, delayed: true }), '?delayed=true')
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

test('плитки реестра: счёт по статусам, «Истёк или истекает за 90 дней» считает так же, как отбирает', () => {
  const counts = registryTileCounts([
    { status: 'REVIEW' },
    { status: 'REVIEW' },
    { status: 'SIGNING' },
    { status: 'ACTIVE', validUntil: '2026-12-01' },
    { status: 'ACTIVE', validUntil: '2027-06-01' },
    { status: 'ACTIVE', validUntil: '2026-10-01' },
    { status: 'COMPLETED', validUntil: '2026-11-01' },
    { status: 'CORRECTION', approval: { side: 'SCHOOL', round: 1, sideDays: 9, sideLimitDays: 7, totalDays: 30, color: 'YELLOW' } },
    { status: 'SIGNING', approval: { side: 'SIGNING', round: 2, sideDays: 1, sideLimitDays: null, totalDays: 10, color: 'NORMAL' } }
  ], '2026-10-17')
  assert.deepEqual(counts, { REVIEW: 2, CORRECTION: 1, SIGNING: 2, DELAYED: 1, ACTIVE: 3, EXPIRING: 2 })
  assert.deepEqual(registryTileFilters('EXPIRING', '2026-10-17'), { statuses: ['ACTIVE'], expiresBefore: '2027-01-15', delayed: false })
  assert.deepEqual(registryTileFilters('SIGNING', '2026-10-17'), { statuses: ['SIGNING'], expiresBefore: '', delayed: false })
  assert.deepEqual(registryTileFilters(null, '2026-10-17'), { statuses: [], expiresBefore: '', delayed: false })
  assert.deepEqual(registryTileFilters('DELAYED', '2026-10-17'), { statuses: [], expiresBefore: '', delayed: true })
  assert.equal(registryTileOf({ ...emptyRegistryFilters, delayed: true }, '2026-10-17'), 'DELAYED')
  assert.equal(registryTileOf({ ...emptyRegistryFilters, statuses: ['SIGNING'], delayed: true }, '2026-10-17'), null)
  assert.equal(registryTileOf({ ...emptyRegistryFilters, statuses: ['ACTIVE'], expiresBefore: '2027-01-15' }, '2026-10-17'), 'EXPIRING')
  assert.equal(registryTileOf({ ...emptyRegistryFilters, statuses: ['ACTIVE'] }, '2026-10-17'), 'ACTIVE')
  assert.equal(registryTileOf({ ...emptyRegistryFilters, statuses: ['ACTIVE', 'SIGNING'] }, '2026-10-17'), null)
})

test('согласование: сторона, круг и дни из нормы, затянулось только жёлтое и красное', () => {
  assert.equal(approvalText({ side: 'UNIVERSITY', round: 2, sideDays: 12, sideLimitDays: 21, totalDays: 45, color: 'NORMAL' }),
    'у вуза, круг 2: 12 из 21 дн.; всего 45 дн.')
  assert.equal(approvalText({ side: 'SCHOOL', round: 1, sideDays: 9, sideLimitDays: 7, totalDays: 30, color: 'YELLOW' }),
    'у школы, круг 1: 9 из 7 дн.; всего 30 дн.')
  assert.equal(approvalText({ side: 'SIGNING', round: 1, sideDays: 3, sideLimitDays: null, totalDays: 95, color: 'RED' }),
    'на подписании: 3 дн.; всего 95 дн.')
  assert.equal(approvalDelayed(null), false)
  assert.equal(approvalDelayed({ side: 'SCHOOL', round: 1, sideDays: 1, sideLimitDays: 7, totalDays: 1, color: 'NORMAL' }), false)
  assert.equal(approvalDelayed({ side: 'SCHOOL', round: 1, sideDays: 1, sideLimitDays: 7, totalDays: 95, color: 'RED' }), true)
  assert.equal(partnerApprovalLine('REVIEW', 2), 'Сейчас у вуза, круг согласования 2')
  assert.equal(partnerApprovalLine('CORRECTION', 1), 'Сейчас у школы, круг согласования 1')
  assert.equal(partnerApprovalLine('ACTIVE', null), null)
})

test('подписанная версия скачивается с пометкой в имени, как на сервере', () => {
  assert.equal(signedFileName('Договор.pdf'), 'Договор (подписанная).pdf')
  assert.equal(signedFileName('договор'), 'договор (подписанная)')
  assert.equal(signedFileName('.pdf'), '.pdf (подписанная)')
})

test('отметка срока: «срок истёк» только у действующего с прошедшей датой, «истекает» — в горизонте предупреждения', () => {
  const today = '2026-10-18'
  assert.equal(agreementTerm({ status: 'ACTIVE', validUntil: '2026-10-14' }, today), 'expired')
  assert.equal(agreementTerm({ status: 'ACTIVE', validUntil: '2026-10-18' }, today), null)
  assert.equal(agreementTerm({ status: 'ACTIVE', validUntil: '2026-10-18' }, today, 90), 'expiring')
  assert.equal(agreementTerm({ status: 'ACTIVE', validUntil: '2027-01-16' }, today, 90), 'expiring')
  assert.equal(agreementTerm({ status: 'ACTIVE', validUntil: '2027-01-17' }, today, 90), null)
  assert.equal(agreementTerm({ status: 'ACTIVE', validUntil: null }, today, 90), null)
  assert.equal(agreementTerm({ status: 'COMPLETED', validUntil: '2026-10-14' }, today, 90), null)
})

test('привязка договора из взаимодействия: кандидаты без расторгнутых и уже связанных, связи договора сохраняются', () => {
  const agreements = [
    { id: 'a1', status: 'ACTIVE' as const },
    { id: 'a2', status: 'TERMINATED' as const },
    { id: 'a3', status: 'DRAFT' as const }
  ]
  assert.deepEqual(linkCandidates(agreements, ['a3']).map((agreement) => agreement.id), ['a1'])
  const input = linkInput({
    version: 3,
    number: 'Д-2',
    concludedOn: '2025-11-12',
    validUntil: '2026-11-02',
    sentOn: null,
    parties: null,
    plannedKind: 'RENEWAL',
    plannedOn: '2026-10-28',
    universitySigner: null,
    schoolSigner: { id: 'p1' },
    interactions: [{ id: 'w1' }, { id: 'w2' }]
  }, 'w2')
  assert.deepEqual(input.interactionIds, ['w1', 'w2'])
  assert.equal(input.version, 3)
  assert.equal(input.schoolSignerProfileId, 'p1')
  assert.equal(input.programIds, null)
  assert.equal(input.status, null)
})
