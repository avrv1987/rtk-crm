import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  activeControlFilterCount,
  changeNotes,
  controlFiltersFromQuery,
  controlQueryFromFilters,
  controlStateFromHash,
  dueLabels,
  dueTones,
  emptyControlFilters,
  exportColumns,
  filterControl,
  hasPeriod,
  isStuck,
  learningText,
  routeLabel,
  routeShare,
  toExportRequest,
  controlViewFromQuery,
  controlListHref,
  controlReportHref,
  reportSelectionFromControl,
  controlMoreScope,
  shownTotals,
  shownTotalsText,
  shownWorks,
  skippedNotice,
  tileShownCount,
  trendLine
} from '../src/features/control/controlModel.ts'
import type { ReportSelection } from '../src/features/reports/reportSelection.ts'
import type { WorkControl } from '../src/shared/api/client.ts'

test('этап договора на согласовании не считается застрявшим', () => {
  assert.equal(isStuck({ status: 'ACTIVE', daysOnStage: 40, stageUnderApproval: false }, 30), true)
  assert.equal(isStuck({ status: 'ACTIVE', daysOnStage: 40, stageUnderApproval: true }, 30), false)
  assert.equal(isStuck({ status: 'PAUSED', daysOnStage: 40, stageUnderApproval: false }, 30), false)
  assert.equal(isStuck({ status: 'ACTIVE', daysOnStage: 10, stageUnderApproval: false }, 30), false)
})

test('отборы экрана «Контроль» переживают круг через адрес', () => {
  const filters = {
    ...emptyControlFilters,
    productId: ['p1', 'p2'],
    programId: ['g1'],
    managerId: ['m1', 'UNASSIGNED'],
    stage: ['Согласование, подписание'],
    from: '2026-09-01',
    to: '2026-09-30',
    change: 'MOVED' as const
  }
  const query = controlQueryFromFilters(filters)
  assert.deepEqual(controlFiltersFromQuery(query), filters)
  assert.equal(controlQueryFromFilters(emptyControlFilters), '')
  assert.deepEqual(controlFiltersFromQuery(''), emptyControlFilters)
})

test('смена адреса без перезагрузки задаёт отборы и плитку «Контроля»', () => {
  const linked = controlStateFromHash('#/control?tile=OVERDUE&from=2026-09-01&to=2026-09-30&stuck=14')
  assert.equal(linked?.view.tile, 'OVERDUE')
  assert.equal(linked?.view.stuckDays, 14)
  assert.equal(linked?.filters.from, '2026-09-01')
  assert.equal(linked?.filters.to, '2026-09-30')
  const reset = controlStateFromHash('#/control')
  assert.deepEqual(reset?.filters, emptyControlFilters)
  assert.equal(reset?.view.tile, null)
  assert.equal(controlStateFromHash('#/control?view=list&due=OVERDUE'), null)
  assert.equal(controlStateFromHash('#/organizations?tile=OVERDUE'), null)
})

test('из адреса берутся только известные значения', () => {
  const filters = controlFiltersFromQuery('from=вчера&to=2026-13&change=ALL&manager=m1&stage=')
  assert.equal(filters.from, '')
  assert.equal(filters.to, '')
  assert.equal(filters.change, '')
  assert.deepEqual(filters.managerId, ['m1'])
  assert.equal(activeControlFilterCount(filters), 2)
  assert.equal(hasPeriod(filters), false)
  assert.equal(hasPeriod({ ...emptyControlFilters, to: '2026-09-30' }), true)
})

test('несуществующая дата из адреса отбрасывается', () => {
  const filters = controlFiltersFromQuery('from=2026-13-45&to=2026-02-30')
  assert.equal(filters.from, '')
  assert.equal(filters.to, '')
  assert.equal(controlFiltersFromQuery('from=2026-02-28&to=2028-02-29').to, '2028-02-29')
})

test('цвет и подпись состояния срока', () => {
  assert.deepEqual(
    (['OVERDUE', 'THIS_WEEK', 'NO_NEXT_STEP', 'IN_ORDER'] as const).map((due) => [dueLabels[due], dueTones[due]]),
    [['Просрочено', 'overdue'], ['На этой неделе', 'week'], ['Без шага или срока', 'missing'], ['В порядке', 'ok']]
  )
})

test('положение в маршруте: номер этапа без процента готовности', () => {
  assert.equal(routeLabel({ stagePosition: 5, stageTotal: 13 }), 'Положение в маршруте: этап 5 из 13')
  assert.doesNotMatch(routeLabel({ stagePosition: 5, stageTotal: 13 }), /готов|%/)
  assert.equal(routeShare({ stagePosition: 5, stageTotal: 13 }), 38)
  assert.equal(routeShare({ stagePosition: 13, stageTotal: 13 }), 100)
  assert.equal(routeLabel({ stagePosition: 0, stageTotal: 0 }), '')
  assert.equal(routeShare({ stagePosition: 0, stageTotal: 0 }), 0)
})

test('пометки изменений за период', () => {
  const base = { created: false, completed: false, transitions: 0, stageAtStart: null, stage: 'Встреча' }
  assert.deepEqual(changeNotes(base), [])
  assert.deepEqual(changeNotes({ ...base, created: true }), ['Новое взаимодействие'])
  assert.deepEqual(
    changeNotes({ ...base, transitions: 2, stageAtStart: 'Поиск контакта' }),
    ['Было: Поиск контакта · 2 перехода']
  )
  assert.deepEqual(changeNotes({ ...base, transitions: 5, stageAtStart: 'Встреча', completed: true }), ['5 переходов', 'Завершена в периоде'])
})

test('выгрузка берёт те же отборы, что на экране, и добавляет колонки периода', () => {
  const filters = {
    ...emptyControlFilters,
    organizationId: ['o1'],
    managerId: ['m1', 'UNASSIGNED'],
    from: '2026-09-01',
    to: '2026-09-30',
    change: 'NEW' as const
  }
  const request = toExportRequest(filters, 'XLSX', false)
  assert.equal(request.kind, 'MANAGER_WORK')
  assert.equal(request.format, 'XLSX')
  assert.equal(request.from, '2026-09-01')
  assert.deepEqual(request.filters?.managerIds, ['m1'])
  assert.equal(request.filters?.includeNoManager, true)
  assert.equal(request.filters?.change, 'NEW')
  assert.ok(request.columns?.includes('TRANSITIONS'))
  assert.ok(!request.columns?.includes('TEAM'))
  assert.ok(exportColumns(emptyControlFilters, true).includes('TEAM'))
  assert.ok(!exportColumns(emptyControlFilters, true).includes('TRANSITIONS'))
  assert.equal(toExportRequest(emptyControlFilters, 'PDF', false).from, null)
  assert.equal(toExportRequest(emptyControlFilters, 'PDF', false).filters?.change, null)
})

test('колонка «Обучение (LMS)» пишет числа идущих потоков и попадает в выгрузку', () => {
  assert.equal(learningText(null), 'Нет потоков LMS')
  assert.equal(learningText({ runs: 0, participants: 0, signals: 1, signalTitles: ['Низкая доля завершивших'] }), 'Идущих потоков нет')
  assert.equal(learningText({ runs: 1, participants: 6, active7d: 2, signals: 0, signalTitles: [] }), 'Обучающихся 6, за 7 дней заходили 2')
  assert.equal(learningText({ runs: 1, participants: 6, active7d: null, signals: 0, signalTitles: [] }), 'Обучающихся 6')
  assert.ok(exportColumns(emptyControlFilters, false).includes('LMS_LEARNING'))
  assert.ok(exportColumns(emptyControlFilters, false).includes('LMS_CHECK'))
})

test('выгрузка «Контроля» берёт выбранную плитку и колонку «Договор»', () => {
  const overdue = toExportRequest(emptyControlFilters, 'XLSX', true, { tile: 'OVERDUE', stuckDays: 30 })
  assert.equal(overdue.filters?.tile, 'OVERDUE')
  assert.equal(overdue.filters?.minDaysOnStage, null)
  const stuck = toExportRequest(emptyControlFilters, 'PDF', false, { tile: 'STUCK', stuckDays: 14 })
  assert.equal(stuck.filters?.tile, 'STUCK')
  assert.equal(stuck.filters?.minDaysOnStage, 14)
  // П03: «Без работы» выгружается своей плиткой — списком вузов, а не всеми взаимодействиями по фильтрам.
  assert.equal(toExportRequest(emptyControlFilters, 'XLSX', false, { tile: 'IDLE', stuckDays: 30 }).filters?.tile, 'IDLE')
  assert.equal(toExportRequest(emptyControlFilters, 'XLSX', false).filters?.tile, null)
  assert.ok(overdue.columns?.includes('AGREEMENT'))
  assert.ok(overdue.columns?.includes('AGREEMENT_STATUS'))
  assert.equal(controlViewFromQuery('tile=IDLE').tile, 'IDLE')
})

const queryOf = (href: string) => href.slice(href.indexOf('?') + 1)

// П01, П02: режим хранится в адресе и уходит в выгрузку и в отчёт тем же правилом владельца, что на экране.
test('режим «За период» живёт в адресе и уходит в выгрузку и отчёт, без периода режим «Сейчас»', () => {
  const period = { ...emptyControlFilters, from: '2026-09-01', to: '2026-09-30', managerId: ['m1'], mode: 'PERIOD' as const }
  const query = controlQueryFromFilters(period)
  assert.ok(query.includes('mode=period'))
  assert.deepEqual(controlFiltersFromQuery(query), period)
  assert.equal(controlFiltersFromQuery('mode=period').mode, 'NOW')
  assert.equal(controlQueryFromFilters({ ...emptyControlFilters, mode: 'PERIOD' }), '')
  assert.equal(toExportRequest(period, 'XLSX', false).filters?.managerAt, 'PERIOD_END')
  assert.equal(toExportRequest({ ...period, mode: 'NOW' }, 'PDF', false).filters?.managerAt, 'CURRENT')
  assert.equal(toExportRequest({ ...emptyControlFilters, mode: 'PERIOD' }, 'PDF', false).filters?.managerAt, 'CURRENT')
  const selection = reportSelectionFromControl({} as ReportSelection, queryOf(controlReportHref(period, { tile: null, stuckDays: 30 })))
  assert.equal(selection?.managerAt, 'PERIOD_END')
  assert.deepEqual(selection?.managerIds, ['m1'])
  assert.equal(reportSelectionFromControl({} as ReportSelection, queryOf(controlReportHref({ ...period, mode: 'NOW' },
    { tile: null, stuckDays: 30 })))?.managerAt, 'CURRENT')
})

// П17: сопоставимые потоки и потоки без базы сравнения не складываются в одно «было → стало».
test('строка тренда разделяет сопоставимые потоки и новые без базы', () => {
  const total = { start: 10, end: 12, change: 2, runsWithoutData: 1, endWithoutBase: 20, startedRuns: 0, endStarted: 0, endedRuns: 0, startEnded: 0 }
  assert.equal(trendLine({ total }, 90), 'Обучающиеся за 90 дней, сопоставимые потоки: 10 → 12, +2; новые потоки без базы сравнения: 20')
  assert.equal(trendLine({ total: { ...total, runsWithoutData: 0, endWithoutBase: 0 } }, 90),
    'Обучающиеся за 90 дней, сопоставимые потоки: 10 → 12, +2')
})

// А08: начавшийся поток — не рост, закончившийся по плану — не падение; они показаны как изменение состава.
test('строка тренда отдельно называет начавшиеся и закончившиеся потоки', () => {
  const total = { start: 10, end: 12, change: 2, runsWithoutData: 0, endWithoutBase: 0, startedRuns: 2, endStarted: 30, endedRuns: 1, startEnded: 8 }
  assert.equal(trendLine({ total }, 90),
    'Обучающиеся за 90 дней, сопоставимые потоки: 10 → 12, +2; начались потоки (2): +30; закончились потоки (1): −8')
})

test('«Показать списком» переносит все отборы, плитку, порог, команду и период', () => {
  const filters = {
    ...emptyControlFilters,
    organizationId: ['o1', 'o2'],
    managerId: ['m1', 'UNASSIGNED'],
    stage: ['Пилот', 'Занятия'],
    programId: ['g1'],
    productId: ['p1'],
    directionId: ['d1'],
    kindId: ['k1', 'k2'],
    from: '2026-10-01',
    to: '2026-10-18',
    change: 'MOVED' as const,
    mode: 'PERIOD' as const
  }
  const view = { tile: 'STUCK' as const, stuckDays: 14, team: 'team-a' }
  const href = controlListHref(filters, view)
  assert.ok(href.startsWith('#/control?'))
  const linked = controlStateFromHash(href)
  assert.ok(linked !== null, 'список — тот же экран «Контроля», а не другой раздел')
  assert.deepEqual(linked.filters, filters)
  assert.deepEqual(linked.view, { ...view, display: 'list' })
  assert.equal(new URLSearchParams(queryOf(href)).get('skipped'), null)
  assert.equal(skippedNotice('view=list'), null)
})

test('«Все форматы — в отчёте» переносит период и фильтры, плитку называет', () => {
  const filters = {
    ...emptyControlFilters,
    productId: ['p1'],
    programId: ['g1'],
    directionId: ['d1'],
    organizationId: ['o1'],
    managerId: ['m1', 'UNASSIGNED'],
    stage: ['Пилот'],
    from: '2026-10-01',
    to: '2026-10-18',
    change: 'MOVED' as const
  }
  const href = controlReportHref(filters, { tile: 'THIS_WEEK', stuckDays: 30 })
  assert.ok(href.startsWith('#/reports/manager-work?'))
  assert.equal(skippedNotice(queryOf(href)), 'Не перенесено из «Контроля»: плитка — на этом экране такого отбора нет.')
  assert.equal(skippedNotice(queryOf(controlReportHref(filters, { tile: null, stuckDays: 30 }))), null)
  const stored = { kind: 'PORTFOLIO', organizationType: 'COLLEGE', includeNoDirection: true, from: '', to: '' } as unknown as ReportSelection
  const selection = reportSelectionFromControl(stored, queryOf(href))
  assert.ok(selection !== null)
  assert.equal(selection.kind, 'MANAGER_WORK')
  assert.equal(selection.from, '2026-10-01')
  assert.equal(selection.to, '2026-10-18')
  assert.deepEqual(selection.productIds, ['p1'])
  assert.deepEqual(selection.programIds, ['g1'])
  assert.deepEqual(selection.directionIds, ['d1'])
  assert.deepEqual(selection.organizationIds, ['o1'])
  assert.deepEqual(selection.managerIds, ['m1'])
  assert.equal(selection.includeNoManager, true)
  assert.deepEqual(selection.stages, ['Пилот'])
  assert.equal(selection.change, 'MOVED')
  assert.equal(selection.organizationType, '')
  assert.equal(selection.includeNoDirection, false)
  assert.equal(reportSelectionFromControl(stored, 'from=2026-10-01'), null)
})

test('счётчик «Контроля» показывает число строк под выбранной плиткой', () => {
  const works = (count: number) => Array.from({ length: count }, () => ({}))
  const shown = {
    teams: [{ managers: [{ managerId: 'm1', works: works(2) }, { managerId: null, works: works(1) }] }],
    idleOrganizations: [{}, {}]
  } as unknown as WorkControl
  assert.equal(tileShownCount(shown, null), null)
  assert.equal(tileShownCount(shown, 'OVERDUE'), 3)
  assert.equal(tileShownCount(shown, 'IDLE'), 2)
})

test('под плиткой итог менеджера «всего» остаётся серверным, а итог отбора считается по показанным строкам', () => {
  const work = (interactionId: string, organizationId: string, due: string) => ({ interactionId, organizationId, due, problems: 0, risks: 0 })
  const control = {
    total: { organizations: 2, works: 3, overdue: 2 },
    teams: [{
      teamId: 't1',
      totals: { organizations: 2, works: 3, overdue: 2 },
      managers: [{
        managerId: 'm1',
        totals: { organizations: 2, works: 3, overdue: 2 },
        works: [work('w1', 'o1', 'OVERDUE'), work('w2', 'o1', 'OVERDUE'), work('w3', 'o2', 'IN_ORDER')]
      }]
    }]
  } as unknown as WorkControl
  const shown = filterControl(control, 'OVERDUE', 30)
  const manager = shown.teams[0].managers[0]
  assert.equal(manager.works.length, 2)
  assert.equal(manager.totals.works, 3)
  assert.deepEqual(shownTotals(manager.works), { organizations: 1, works: 2 })
  assert.equal(shownTotalsText(shownTotals(shownWorks(shown))), 'по отбору: вузов 1, взаимодействий 2')
  assert.equal(filterControl(control, null, 30), control)
})

test('дополнительные блоки «Контроля» подписаны областью, временем и тем, что фильтры к ним не применяются', () => {
  assert.equal(controlMoreScope(true), 'По всем командам, фильтры, плитки и период выше не применяются. Сигналы LMS — действующие сейчас; обучающиеся и активные взаимодействия — сегодня против 90 дней назад; данные обучения — по идущим потокам; подписания — текущий квартал.')
  assert.match(controlMoreScope(false), /^По всей команде, фильтры, плитки и период выше не применяются\./)
  assert.doesNotMatch(controlMoreScope(false), /подписания/)
})
