import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { WorkControl, WorkControlItem, WorkControlManager, WorkControlTeam } from '../src/shared/api/client.ts'
import {
  compactManagers,
  compactTeams,
  controlQuery,
  controlViewFromQuery,
  emptyControlFilters,
  filterControl,
  isPeriodMode,
  teamControl,
  tileShownCount,
  unassignedInRows
} from '../src/features/control/controlModel.ts'
import {
  changeHref,
  changeText,
  comparisonPeriods,
  coverageText,
  nbsp,
  portfolioText,
  registryHref,
  shareText,
  teamChanges,
  teamHref,
  teamOverviews
} from '../src/features/control/overviewModel.ts'
import {
  emptyRegistryFilters,
  registryFiltersFromQuery,
  registrySearch,
  registryTeams
} from '../src/features/agreements/agreementModel.ts'
import type { AgreementRegistryRow } from '../src/features/agreements/agreementsApi.ts'
import { templateLmsWarning } from '../src/features/training/lmsStageSuggestionRules.ts'

const totals = { organizations: 0, works: 0, overdue: 0, thisWeek: 0, noNextStep: 0, inOrder: 0, withIssues: 0, moved: 0, created: 0, completed: 0, idle: 0 }

const item = (id: string, organizationId: string, patch: Partial<WorkControlItem> = {}): WorkControlItem => ({
  interactionId: id, organizationId, organizationName: organizationId, title: id, products: [], stage: 'Встреча', stagePosition: 2,
  stageTotal: 13, daysOnStage: 3, due: 'IN_ORDER', status: 'ACTIVE', problems: 0, risks: 0, transitions: 0, created: false, completed: false,
  nextActionAt: '2026-10-25T09:00:00Z', ...patch
})

const manager = (managerId: string | null, works: WorkControlItem[], patch: Partial<WorkControlManager['totals']> = {}): WorkControlManager => ({
  managerId, managerName: managerId, totals: { ...totals, ...patch }, works
})

const team = (teamId: string, managers: WorkControlManager[], patch: Partial<WorkControlTeam['totals']> = {}): WorkControlTeam => ({
  teamId, teamName: `Команда ${teamId}`, totals: { ...totals, ...patch }, managers
})

const registryRow = (id: string, teamId: string, patch: Partial<AgreementRegistryRow> = {}): AgreementRegistryRow => ({
  id, organizationId: `org-${id}`, organizationName: `Вуз ${id}`, number: id, status: 'REVIEW', statusChangedAt: '2026-10-01T09:00:00Z',
  daysInStatus: 17, sentOn: null, concludedOn: null, validUntil: null, subject: null, managerId: null, managerName: null, approval: null,
  teamId, teamName: `Команда ${teamId}`, ...patch
})

const control: WorkControl = {
  calculatedAt: '2026-10-18T09:00:00Z',
  byTeams: true,
  total: { ...totals, organizations: 5, works: 5, overdue: 2 },
  teams: [
    team('a', [
      manager('m1', [item('w1', 'o1', { due: 'OVERDUE' }), item('w2', 'o1', { nextActionAt: null, due: 'NO_NEXT_STEP' }), item('w3', 'o2', { daysOnStage: 40 })], { overdue: 1 }),
      manager(null, [item('w4', 'o3', { due: 'OVERDUE' })], { overdue: 1 })
    ], { organizations: 4, works: 4, overdue: 2, noNextStep: 1, idle: 1 }),
    team('b', [manager('m2', [item('w5', 'o5')])], { organizations: 2, works: 1 })
  ],
  idleOrganizations: [
    { id: 'o4', name: 'o4', teamId: 'a', teamName: 'Команда a', managerId: 'm1', managerName: 'm1', lastActionAt: null },
    { id: 'o6', name: 'o6', teamId: 'b', teamName: 'Команда b', managerId: 'm2', managerName: 'm2', lastActionAt: null }
  ],
  agreements: [registryRow('Д-1', 'a', { organizationId: 'o1' }), registryRow('Д-2', 'b', { organizationId: 'o7', status: 'SIGNING' })]
}

test('режим «За период» — только с концом периода; вузы без КАМ считаются по строкам', () => {
  assert.equal(isPeriodMode({ ...emptyControlFilters, mode: 'PERIOD', from: '2026-09-01', to: '2026-09-30' }), true)
  assert.equal(isPeriodMode({ ...emptyControlFilters, mode: 'PERIOD', from: '2026-09-01' }), false)
  assert.equal(isPeriodMode({ ...emptyControlFilters, from: '2026-09-01', to: '2026-09-30' }), false)
  assert.equal(unassignedInRows(control), 1, 'вуз o3 без КАМ на конец периода; вузов больше, чем строк, быть не может')
})

test('плитка «Договор на согласовании» считает договоры, а показывает их и взаимодействия этих вузов', () => {
  const shown = filterControl(control, 'AGREEMENT', 30)
  assert.deepEqual(shown.teams.flatMap((group) => group.managers.flatMap((row) => row.works.map((work) => work.interactionId))), ['w1', 'w2'])
  assert.equal(tileShownCount(shown, 'AGREEMENT'), 2, 'под плиткой — число договоров, как на плитке')
})

test('отбор команды из сводки живёт в адресе и оставляет её строки, договоры и итоги', () => {
  const view = controlViewFromQuery('tile=OVERDUE&team=a')
  assert.deepEqual(view, { tile: 'OVERDUE', stuckDays: null, team: 'a' })
  assert.equal(controlQuery(emptyControlFilters, view), 'tile=OVERDUE&team=a')
  const own = teamControl(control, 'a')
  assert.deepEqual(own.teams.map((group) => group.teamId), ['a'])
  assert.equal(own.total.overdue, 2)
  assert.deepEqual(own.agreements?.map((row) => row.number), ['Д-1'])
  assert.deepEqual(own.idleOrganizations?.map((row) => row.id), ['o4'])
  assert.equal(teamControl(control, null), control)
  assert.equal(teamControl(control, 'нет').total.works, 0)
})

test('на телефоне «горящие» менеджеры и команды идут первыми', () => {
  const calm = manager('m3', [])
  const hot = manager('m4', [], { overdue: 3 })
  const warm = manager('m5', [], { overdue: 1 })
  assert.deepEqual(compactManagers([calm, warm, hot]).map((row) => row.managerId), ['m4', 'm5', 'm3'])
  assert.deepEqual(compactTeams(control.teams).map((group) => group.teamId), ['a', 'b'])
  assert.deepEqual(compactTeams([...control.teams].reverse()).map((group) => group.teamId), ['a', 'b'])
})

test('доля всегда с числом и знаменателем, без знаменателя доли нет', () => {
  assert.equal(shareText({ count: 8, base: 80 }, 'активных'), `8 из 80 активных (10${nbsp}%)`)
  assert.equal(shareText({ count: 5, base: 10 }, 'активных'), `5 из 10 активных (50${nbsp}%)`)
  assert.equal(shareText({ count: 0, base: 0 }, 'активных'), '0 — активных: нет')
  assert.equal(shareText({ count: 1, base: 3 }, 'активных'), '1 из 3 активных', 'при малой базе процента нет')
})

test('изменение к прошлому периоду: от нуля — «новое наблюдение», а не бесконечный рост', () => {
  assert.equal(changeText({ current: 4, previous: 0 }), 'новое наблюдение')
  assert.equal(changeText({ current: 0, previous: 0 }), 'без изменений')
  assert.equal(changeText({ current: 5, previous: 3 }), '+2', 'при малой базе процента нет')
  assert.equal(changeText({ current: 3, previous: 4 }), '−1')
  assert.equal(changeText({ current: 15, previous: 12 }), `+3 (+25${nbsp}%)`)
  assert.equal(changeText({ current: 9, previous: 10 }), `−1 (−10${nbsp}%)`)
  assert.equal(changeText({ current: 3, previous: 3 }), 'без изменений')
  assert.doesNotMatch(changeText({ current: 4, previous: 0 }), /%|∞/)
})

// Разбор §7.3: «было → стало» раскладывается на тех, кто остался, вошёл и вышел.
test('состав портфеля: было → стало и из чего сложилась разница', () => {
  const row = { teamId: 'a', teamName: 'А', atStart: 10, atEnd: 12, stayed: 8, created: 3, resumed: 1, completed: 1, paused: 1 }
  assert.equal(portfolioText(row), '10 → 12: остались 8, новые +3, возобновлены +1, завершены −1, приостановлены −1')
  assert.equal(portfolioText({ ...row, atEnd: 8, created: 0, resumed: 0, completed: 2, paused: 0 }), '10 → 8: остались 8, завершены −2')
  assert.equal(row.atStart, row.stayed + row.completed + row.paused)
  assert.equal(row.atEnd, row.stayed + row.created + row.resumed)
})

// Разбор §5.4: знаменатель — идущие сопоставленные потоки; без Moodle свежесть не оценивается.
test('индикатор покрытия данных обучения', () => {
  const coverage = { calculatedAt: '2026-10-19T10:00:00+03:00', sourceConfigured: true, staleAfterHours: 26, expected: 10, fresh: 7,
    stale: 2, withoutObservation: 1, unappliedRecords: null, runs: [] }
  assert.equal(coverageText(coverage), 'Данные обучения доступны для 7 из 10 идущих потоков; 3 требуют проверки')
  assert.equal(coverageText({ ...coverage, fresh: 10, stale: 0, withoutObservation: 0 }), 'Данные обучения доступны для 10 из 10 идущих потоков')
  assert.equal(coverageText({ ...coverage, expected: 0, fresh: 0, stale: 0, withoutObservation: 0 }), 'Данные обучения: идущих сопоставленных потоков нет')
  assert.match(coverageText({ ...coverage, sourceConfigured: false }), /Moodle не подключён/)
})

test('окна сравнения одной длины и не пересекаются', () => {
  assert.deepEqual(comparisonPeriods('2026-10-18', 30), {
    current: { from: '2026-09-19', to: '2026-10-18' },
    previous: { from: '2026-08-20', to: '2026-09-18' }
  })
})

test('сводка команд: знаменатели по паспортам, договоры по реестру с цветом и сроком', () => {
  const summary = {
    calculatedAt: '2026-10-18T09:00:00Z',
    stuckDays: 30,
    teams: [
      { teamId: 'a', teamName: 'Команда a', organizations: 5, unassignedOrganizations: 1, interactions: 4, overdue: 2, withoutNextStep: 1, stuck: 1, atRisk: 0, organizationsWithLearning: 0, participants: 0, learningNow: 0, teachers: 0 },
      { teamId: 'b', teamName: 'Команда b', organizations: 2, unassignedOrganizations: 0, interactions: 1, overdue: 0, withoutNextStep: 0, stuck: 0, atRisk: 0, organizationsWithLearning: 0, participants: 0, learningNow: 0, teachers: 0 }
    ],
    total: { teamId: null, teamName: null, organizations: 7, unassignedOrganizations: 1, interactions: 5, overdue: 2, withoutNextStep: 1, stuck: 1, atRisk: 0, organizationsWithLearning: 0, participants: 0, learningNow: 0, teachers: 0 }
  }
  const agreements = [
    registryRow('Д-1', 'a', { approval: { side: 'UNIVERSITY', round: 1, sideDays: 50, sideLimitDays: 42, totalDays: 50, color: 'YELLOW' } }),
    registryRow('Д-3', 'a', { status: 'CORRECTION', approval: { side: 'SCHOOL', round: 1, sideDays: 2, sideLimitDays: 7, totalDays: 95, color: 'RED' } }),
    registryRow('Д-4', 'a', { status: 'ACTIVE', validUntil: '2026-12-01' }),
    registryRow('Д-5', 'a', { status: 'ACTIVE', validUntil: '2027-12-01' }),
    registryRow('Д-2', 'b', { status: 'SIGNING' })
  ]
  const [a, b] = teamOverviews(control, summary, agreements, '2026-10-18')
  assert.equal(a.teamName, 'Команда a')
  assert.equal(a.works, 4)
  assert.deepEqual(a.overdue, { count: 2, base: 3 }, 'просрочено — из взаимодействий со сроком (М03)')
  assert.deepEqual(a.noNextStep, { count: 1, base: 4 })
  assert.deepEqual(a.stuck, { count: 1, base: 4 })
  assert.deepEqual(a.unassigned, { count: 1, base: 5 })
  assert.deepEqual(a.idle, { count: 1, base: 5 })
  assert.deepEqual(a.review, { total: 2, yellow: 1, red: 1 })
  assert.equal(a.expiring, 1, 'истекает в 90 дней — как плитка реестра')
  assert.deepEqual(b.review, { total: 1, yellow: 0, red: 0 })
})

test('изменения по командам и ссылки из чисел открывают те же строки', () => {
  const periods = comparisonPeriods('2026-10-18', 30)
  const current = { ...control, teams: [team('a', [], { created: 4, completed: 1, moved: 3 })] }
  const previous = { ...control, teams: [team('a', [], { created: 0, completed: 2, moved: 3 }), team('b', [], { created: 1 })] }
  const agreements = [
    registryRow('Д-6', 'a', { status: 'ACTIVE', concludedOn: '2026-10-01' }),
    registryRow('Д-7', 'a', { status: 'TERMINATED', concludedOn: '2026-09-01' }),
    registryRow('Д-8', 'a', { status: 'REVIEW', concludedOn: null })
  ]
  const [a, b] = teamChanges(current, previous, agreements, periods)
  assert.deepEqual(a.created, { current: 4, previous: 0 })
  assert.deepEqual(a.signed, { current: 1, previous: 1 })
  assert.deepEqual(b.created, { current: 0, previous: 1 }, 'команда без строк сейчас всё равно видна')
  assert.equal(teamHref('a', 'OVERDUE'), '#/control?tile=OVERDUE&team=a')
  assert.equal(teamHref('a', null), '#/control?team=a')
  assert.equal(changeHref('a', periods.current, 'NEW'), '#/control?from=2026-09-19&to=2026-10-18&change=NEW&team=a')
  assert.equal(registryHref('a', 'EXPIRING'), '#/agreements?team=a&tile=EXPIRING')
})

test('реестр договоров: отбор по команде из адреса и список команд из строк', () => {
  const filters = registryFiltersFromQuery('team=a&tile=EXPIRING', '2026-10-18')
  assert.equal(filters.teamId, 'a')
  assert.deepEqual(filters.statuses, ['ACTIVE'])
  assert.equal(filters.expiresBefore, '2027-01-16')
  assert.deepEqual(registryFiltersFromQuery('', '2026-10-18'), emptyRegistryFilters)
  assert.equal(registrySearch({ ...emptyRegistryFilters, teamId: 'a' }), '?teamId=a')
  assert.deepEqual(registryTeams([registryRow('1', 'b'), registryRow('2', 'a'), registryRow('3', 'b'), { ...registryRow('4', 'x'), teamId: null }]),
    [{ id: 'a', name: 'Команда a' }, { id: 'b', name: 'Команда b' }])
})

test('копия шаблона предупреждает об этапе, найденном по названию, так же, как правка', () => {
  const base = { stages: [{ name: 'Поиск контакта' }, { name: 'Актуализация программы' }, { name: 'Занятия' }] }
  const renamed = { stages: [{ name: 'Поиск контакта' }, { name: 'Обновление программы' }, { name: 'Занятия со студентами' }] }
  assert.match(templateLmsWarning(base, renamed) ?? '', /этап «Актуализация программы»/)
  assert.doesNotMatch(templateLmsWarning(base, renamed) ?? '', /«Занятия»/, 'занятия ищутся по роли этапа')
  assert.equal(templateLmsWarning(base, base), null)
  assert.equal(templateLmsWarning(null, renamed), null, 'новому шаблону сравнивать не с чем')
})
