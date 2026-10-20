import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { WorkControl, WorkControlItem } from '../src/shared/api/client.ts'
import { stageMatrix } from '../src/features/control/cycleModel.ts'
import { pathSegments, type PathWork } from '../src/features/organizations/pathModel.ts'
import {
  agreementFact,
  eventLinkHref,
  historyMatches,
  primaryAction,
  signingBlock,
  signingSatisfied,
  stageActionsAllowed,
  stageTimes,
  statusChangeNotice,
  type WorkAgreementsLike
} from '../src/features/interactions/workCardModel.ts'

const stages = [{ id: 's1', order: 0 }, { id: 'sign', order: 1 }, { id: 's3', order: 2 }]
const foreignActive = { id: 'a1', number: 'ВУЗ-1', validUntil: '2027-06-30', linked: false }
const work = (patch: Partial<WorkAgreementsLike> = {}): WorkAgreementsLike => ({
  signingRuleEnabled: true,
  stages: [{ stageId: 'sign', role: 'SIGNING', name: 'Подписание' }],
  agreements: [],
  ...patch
})

test('события из соседних таблиц попадают в отборы истории и дают ссылку', () => {
  assert.equal(historyMatches('DOCUMENT_ADDED', false, 'DOCUMENTS'), true)
  assert.equal(historyMatches('AGREEMENT_STATUS_CHANGED', false, 'DOCUMENTS'), true)
  assert.equal(historyMatches('CYCLE_STARTED', false, 'STAGES'), true)
  assert.equal(eventLinkHref({ kind: 'AGREEMENT', id: 'a1', organizationId: 'o1', label: '№ 1' }, '#/x'), '#/organizations/o1?agreement=a1')
  assert.equal(eventLinkHref({ kind: 'INTERACTION', id: 'w2', organizationId: 'o1', label: 'Цикл' }, '#/organizations/o1/w1?tab=1'),
    '#/organizations/o1/w2?tab=1')
  assert.equal(eventLinkHref({ kind: 'ATTACHMENT', id: 'f1', organizationId: null, label: 'f.pdf' }, '#/x'), null)
})

test('после возобновления КАМ видит восстановленный шаг или просьбу его задать', () => {
  assert.equal(statusChangeNotice('В работе', { marks: { status: 'ACTIVE' }, nextAction: 'Согласовать план' }),
    'Статус взаимодействия: В работе. Следующий шаг: «Согласовать план».')
  assert.equal(statusChangeNotice('В работе', { marks: { status: 'ACTIVE' }, nextAction: null }),
    'Статус взаимодействия: В работе. Следующий шаг не задан — задайте его.')
  assert.equal(statusChangeNotice('Приостановлено', { marks: { status: 'PAUSED' }, nextAction: 'Вернуться' }), 'Статус взаимодействия: Приостановлено.')
})

test('на этапе договора карточка и окно перехода называют засчитанный договор вуза', () => {
  const counted = work({ activeAgreement: foreignActive, activeAgreementNumber: 'ВУЗ-1' })
  assert.deepEqual(agreementFact(counted, 'sign'), { kind: 'none', counted: 'Засчитан договор вуза № ВУЗ-1, действует до 30.06.2027' })
  assert.deepEqual(agreementFact(counted, 's1'), { kind: 'none' }, 'вне этапа договора подсказки нет')
  assert.equal(signingBlock(counted, stages, 'sign', 's3'), null)
  assert.equal(signingSatisfied(counted, stages, 'sign', 's3'),
    'Условие этапа «Подписание» выполнено: договор № ВУЗ-1, действует до 30.06.2027 (засчитан договор вуза, с этим взаимодействием он не связан).')
  assert.equal(signingSatisfied(counted, stages, 's1', 'sign'), null, 'переход на этап договора условие не проверяет')
  assert.equal(signingSatisfied(work(), stages, 'sign', 's3'), null)

  const linked = work({
    agreements: [{ number: 'РАБ-1', status: 'ACTIVE', concludedOn: '2026-02-01', validUntil: null, statusChangedAt: '2026-02-01T10:00:00Z' }],
    activeAgreement: { id: 'a2', number: 'РАБ-1', validUntil: null, linked: true }
  })
  const fact = agreementFact(linked, 'sign')
  assert.equal(fact.kind, 'agreement')
  assert.equal(fact.kind === 'agreement' ? fact.counted : null, 'Условие этапа выполнено: действует бессрочно')
  assert.equal(signingSatisfied(linked, stages, 'sign', 's3'), 'Условие этапа «Подписание» выполнено: договор № РАБ-1, действует бессрочно.')

  // Засчитанный договор считается договором: главная кнопка не уводит создавать новый.
  assert.equal(primaryAction({ canEdit: true, status: 'ACTIVE', hasNextStep: true, transitionCount: 1, agreementStageRole: 'SIGNING',
    agreementCount: 1, signingBlocked: null })?.kind, 'transition')
})

test('этап карточки стоит в матрице на своём месте без пометки «прежний», номера — по шаблону', () => {
  const totals = { organizations: 0, works: 0, overdue: 0, thisWeek: 0, noNextStep: 0, inOrder: 0, withIssues: 0, moved: 0, created: 0, completed: 0, idle: 0 }
  const item: WorkControlItem = {
    interactionId: 'w1', organizationId: 'o1', organizationName: 'Вуз', title: 'w1', products: [], productIds: [], stage: 'Доп. консультация',
    stagePosition: 2, stageTotal: 5, daysOnStage: 1, due: 'IN_ORDER', status: 'ACTIVE', problems: 0, risks: 0, transitions: 0, created: false,
    completed: false, stageUnderApproval: false, nextActionAt: null, routeId: 'r1', programId: 'g1', program: 'Программа'
  }
  const control: WorkControl = {
    calculatedAt: '2026-10-20T09:00:00Z', byTeams: true, total: totals,
    teams: [{ teamId: 'a', teamName: 'А', totals, managers: [{ managerId: 'm', managerName: 'm', totals, works: [item] }] }],
    routes: [{ templateId: 'r1', templateName: 'Базовый', stages: ['Поиск контакта', 'Доп. консультация', 'Встреча', 'Старый этап'],
      formerStages: ['Старый этап'], cardStages: ['Доп. консультация'] }]
  }
  const [route] = stageMatrix(control, 'PROGRAM', 30)
  assert.deepEqual(route.former, [false, false, false, true])
  assert.deepEqual(route.card, [false, true, false, false])
  assert.deepEqual(route.numbers, [1, null, 2, null])
  assert.deepEqual(route.totals.map((cell) => cell.works), [0, 1, 0, 0])
})

test('«Путь вуза» отличает пропущенный этап от пройденного и даёт дату входа в подсказке', () => {
  const pathWork: PathWork = {
    interactionId: 'w', title: 'w', status: 'ACTIVE', products: [], stageName: 'Подписание', stagePosition: 3, due: 'IN_ORDER', problems: 0,
    risks: 0, createdAt: '2026-01-01T00:00:00Z',
    stages: [
      { name: 'Поиск контакта', optional: false, enteredAt: '2026-01-01T07:00:00Z' },
      { name: 'Корректировка', optional: true, enteredAt: null },
      { name: 'Подписание', optional: false, enteredAt: '2026-02-01T07:00:00Z' }
    ]
  }
  const segments = pathSegments(pathWork)
  assert.deepEqual(segments.map((segment) => segment.state), ['done', 'skipped', 'current'])
  assert.equal(segments[1].title, '2. Корректировка (необязательный): пропущен: на этап не входили')
  assert.match(segments[0].title, /^1\. Поиск контакта: пройден, вход 1 янв\. 2026/)
})

test('у завершённой карточки действий с этапами нет', () => {
  assert.equal(stageActionsAllowed(true, 'COMPLETED'), false)
  assert.equal(stageActionsAllowed(true, 'PAUSED'), true)
  assert.equal(stageActionsAllowed(false, 'ACTIVE'), false)
})

test('вход, выход и длительность этапа из истории, с учётом возвратов', () => {
  const day = 24 * 60 * 60 * 1000
  const at = (days: number) => new Date(Date.parse('2026-09-01T07:00:00Z') + days * day).toISOString()
  const times = stageTimes([
    { type: 'CREATED', toStageId: 's1', occurredAt: at(0) },
    { type: 'TRANSITIONED', toStageId: 's2', occurredAt: at(3) },
    { type: 'COMMENTED', toStageId: null, occurredAt: at(4) },
    { type: 'TRANSITIONED', toStageId: 's1', occurredAt: at(5) },
    { type: 'TRANSITIONED', toStageId: 's2', occurredAt: at(6) }
  ], Date.parse(at(10)))
  assert.deepEqual(times.get('s1'), { enteredAt: at(5), exitedAt: at(6), days: 4, visits: 2 })
  assert.deepEqual(times.get('s2'), { enteredAt: at(6), exitedAt: undefined, days: 6, visits: 2 })
})
