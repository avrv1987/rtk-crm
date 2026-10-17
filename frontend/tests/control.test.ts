import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  activeControlFilterCount,
  changeNotes,
  controlFiltersFromQuery,
  controlQueryFromFilters,
  dueLabels,
  dueTones,
  emptyControlFilters,
  exportColumns,
  hasPeriod,
  routeLabel,
  routeShare,
  toExportRequest
} from '../src/features/control/controlModel.ts'

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

test('положение на маршруте: номер из числа этапов и доля', () => {
  assert.equal(routeLabel({ stagePosition: 5, stageTotal: 13 }), '5 из 13')
  assert.equal(routeShare({ stagePosition: 5, stageTotal: 13 }), 38)
  assert.equal(routeShare({ stagePosition: 13, stageTotal: 13 }), 100)
  assert.equal(routeLabel({ stagePosition: 0, stageTotal: 0 }), '')
  assert.equal(routeShare({ stagePosition: 0, stageTotal: 0 }), 0)
})

test('пометки изменений за период', () => {
  const base = { created: false, completed: false, transitions: 0, stageAtStart: null, stage: 'Встреча' }
  assert.deepEqual(changeNotes(base), [])
  assert.deepEqual(changeNotes({ ...base, created: true }), ['Новая работа'])
  assert.deepEqual(
    changeNotes({ ...base, transitions: 2, stageAtStart: 'Поиск контакта' }),
    ['Было: Поиск контакта · 2 перехода']
  )
  assert.deepEqual(changeNotes({ ...base, transitions: 5, stageAtStart: 'Встреча', completed: true }), ['5 переходов', 'Завершена'])
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
