import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { Organization, WorkControl, WorkControlItem, WorkControlManager } from '../src/shared/api/client.ts'
import {
  controlQuery,
  controlStateFromHash,
  controlViewFromQuery,
  emptyControlFilters,
  filterControl,
  teamControl,
  tileCounts
} from '../src/features/control/controlModel.ts'
import { cellFilters, cellTone, flatRows, stageMatrix, unassignedFor } from '../src/features/control/cycleModel.ts'
import { dueLine, pathGroups, pathSegments, stageLine, type PathWork } from '../src/features/organizations/pathModel.ts'

const totals = { organizations: 0, works: 0, overdue: 0, thisWeek: 0, noNextStep: 0, inOrder: 0, withIssues: 0, moved: 0, created: 0, completed: 0, idle: 0 }
const base = 'route-base'
const courses = 'route-courses'

const item = (id: string, organizationId: string, patch: Partial<WorkControlItem> = {}): WorkControlItem => ({
  interactionId: id, organizationId, organizationName: organizationId, title: id, products: [], productIds: [], stage: 'Встреча',
  stagePosition: 2, stageTotal: 4, daysOnStage: 3, due: 'IN_ORDER', status: 'ACTIVE', problems: 0, risks: 0, transitions: 0, created: false,
  completed: false, stageUnderApproval: false, nextActionAt: '2026-10-25T09:00:00Z', routeId: base, kindId: 'k-base',
  programId: 'g1', program: 'Цифровой университет', ...patch
})

const manager = (managerId: string | null, works: WorkControlItem[]): WorkControlManager => ({
  managerId, managerName: managerId, totals, works
})

const works = [
  item('w1', 'o1', { due: 'OVERDUE', productIds: ['p1', 'p2'], products: ['Облако', 'Защита'] }),
  item('w2', 'o1', { due: 'NO_NEXT_STEP', nextActionAt: null, stage: 'Подписание' }),
  item('w3', 'o2', { daysOnStage: 40, programId: 'g2', program: 'Анализ данных', productIds: ['p1'], products: ['Облако'] }),
  item('w4', 'o3', { programId: null, program: null, stage: 'Поиск контакта' }),
  item('w5', 'o4', { routeId: courses, kindId: 'k-courses', stage: 'Курс', due: 'OVERDUE' }),
  // Прошедший период: строка стоит на этапе на конец периода, а не на сегодняшнем.
  item('w6', 'o5', { stage: 'Подписание', stageAtEnd: 'Встреча', due: 'THIS_WEEK' })
]

const control: WorkControl = {
  calculatedAt: '2026-10-19T09:00:00Z',
  byTeams: true,
  total: { ...totals, organizations: 5, works: works.length, overdue: 2, noNextStep: 1, thisWeek: 1 },
  teams: [
    { teamId: 'a', teamName: 'Команда А', totals: { ...totals, overdue: 1 }, managers: [manager('m1', works.slice(0, 3)), manager(null, [works[3]])] },
    { teamId: 'b', teamName: 'Команда Б', totals: { ...totals, overdue: 1 }, managers: [manager('m2', works.slice(4))] }
  ],
  routes: [
    { templateId: base, templateName: 'Базовый процесс', stages: ['Поиск контакта', 'Встреча', 'Подписание', 'Занятия'] },
    { templateId: courses, templateName: 'Повышение квалификации', stages: ['Поиск контакта', 'Курс'] }
  ]
}

test('матрица «программа × этап»: маршрут на вид, этапы по порядку с пустыми, программы и «без программы»', () => {
  const routes = stageMatrix(control, 'PROGRAM', 30)
  assert.deepEqual(routes.map((route) => route.name), ['Базовый процесс', 'Повышение квалификации'])
  const [main, second] = routes
  assert.deepEqual(main.stages, ['Поиск контакта', 'Встреча', 'Подписание', 'Занятия'])
  assert.deepEqual(main.rows.map((row) => row.label), ['Анализ данных', 'Цифровой университет', 'Без программы'])
  const digital = main.rows[1]
  assert.deepEqual(digital.cells.map((cell) => cell.works), [0, 2, 1, 0], 'w6 стоит на «Встрече» на конец периода')
  assert.deepEqual(digital.cells[1], { works: 2, organizations: 2, overdue: 1, noStep: 0, stuck: 0 })
  assert.equal(cellTone(digital.cells[1]), 'danger')
  assert.equal(cellTone(digital.cells[2]), 'warning', 'без шага — жёлтая')
  assert.equal(cellTone(main.rows[0].cells[1]), 'warning', 'дольше порога — жёлтая')
  assert.equal(cellTone(digital.cells[3]), 'empty')
  assert.deepEqual(second.kindIds, ['k-courses'])
  assert.deepEqual(second.totals.map((cell) => cell.works), [0, 1])
})

test('итоги матрицы сходятся с плитками и числом строк «Контроля»', () => {
  for (const axis of ['PROGRAM', 'PRODUCT'] as const) {
    const routes = stageMatrix(control, axis, 30)
    const sum = (key: 'works' | 'overdue' | 'noStep' | 'stuck') => routes.reduce((total, route) => total + route.total[key], 0)
    const tiles = tileCounts(control, 30)
    assert.equal(sum('works'), control.total.works, `${axis}: каждое взаимодействие в итоге один раз`)
    assert.equal(sum('overdue'), tiles.OVERDUE)
    assert.equal(sum('noStep'), tiles.NO_NEXT_STEP)
    assert.equal(sum('stuck'), tiles.STUCK)
  }
  const byProduct = stageMatrix(control, 'PRODUCT', 30)[0]
  assert.deepEqual(byProduct.rows.map((row) => [row.label, row.total.works]), [['Защита', 1], ['Облако', 2], ['Без продукта', 3]])
  const shown = filterControl(control, 'OVERDUE', 30)
  assert.equal(stageMatrix(shown, 'PROGRAM', 30).reduce((total, route) => total + route.total.works, 0), 2, 'плитка сужает и матрицу')
  assert.equal(stageMatrix(teamControl(control, 'b'), 'PROGRAM', 30).length, 2, 'команда «Руководства» тоже применяется')
})

test('щелчок по ячейке открывает список с отбором программы или продукта, этапа и видов маршрута', () => {
  const [main, second] = stageMatrix(control, 'PROGRAM', 30)
  const filters = { ...emptyControlFilters, managerId: ['m1'], programId: ['g1', 'g2'] }
  assert.deepEqual(cellFilters(filters, 'PROGRAM', 'g1', 'Встреча', main, true),
    { ...filters, programId: ['g1'], stage: ['Встреча'], kindId: ['k-base'] })
  assert.deepEqual(cellFilters(filters, 'PRODUCT', 'p1', 'Курс', second, false),
    { ...filters, productId: ['p1'], stage: ['Курс'] }, 'при одном маршруте вид не добавляется')
})

test('вид «Списком» и «Этапы» живут в адресе рядом с отборами', () => {
  assert.equal(controlQuery(emptyControlFilters, { tile: null, stuckDays: 30, display: 'stages' }), 'display=stages')
  assert.equal(controlQuery(emptyControlFilters, { tile: null, stuckDays: 30, display: 'managers' }), '')
  assert.deepEqual(controlViewFromQuery('display=list&team=t'), { tile: null, stuckDays: 30, team: 't', display: 'list' })
  assert.deepEqual(controlViewFromQuery('display=чужое'), { tile: null, stuckDays: 30 })
  assert.equal(controlStateFromHash('#/control?display=list&program=g1')?.filters.programId[0], 'g1')
})

test('«Списком»: те же строки одной таблицей, просроченные и без шага первыми', () => {
  const rows = flatRows(control)
  assert.equal(rows.length, control.total.works)
  assert.deepEqual(rows.slice(0, 3).map((row) => row.item.interactionId), ['w1', 'w5', 'w2'])
  assert.deepEqual(rows.find((row) => row.item.interactionId === 'w4'), { item: works[3], managerName: null, teamName: 'Команда А' })
})

const organization = (id: string, patch: Partial<Organization> = {}) => ({
  id, name: id, requiresAssignment: true, status: 'ACTIVE', teamId: 'a', ...patch
}) as Organization

test('«Вузы без КАМ» при отборе по программе — только вузы без КАМ с подходящими строками', () => {
  const list = [organization('o3'), organization('o9'), organization('o8', { teamId: 'b' }), organization('o7', { status: 'ARCHIVED' })]
  assert.deepEqual(unassignedFor(list, control, emptyControlFilters, null).map((row) => row.id), ['o3', 'o9', 'o8'])
  const byProgram = { ...emptyControlFilters, programId: ['g1'] }
  assert.deepEqual(unassignedFor(list, control, byProgram, null).map((row) => row.id), ['o3'], 'o9 без взаимодействий к программе не привязан')
  assert.deepEqual(unassignedFor(list, control, emptyControlFilters, 'b').map((row) => row.id), ['o8'])
  assert.deepEqual(unassignedFor(list, control, { ...emptyControlFilters, organizationId: ['o9'] }, null).map((row) => row.id), ['o9'])
})

const pathWork = (id: string, patch: Partial<PathWork> = {}): PathWork => ({
  interactionId: id, title: id, status: 'ACTIVE', products: [], stageName: 'Встреча', stagePosition: 2, due: 'IN_ORDER', problems: 0, risks: 0,
  createdAt: '2026-01-01T00:00:00Z', programId: 'g1', programName: 'Цифровой университет', daysOnStage: 5,
  stages: [{ name: 'Поиск контакта', optional: false }, { name: 'Встреча', optional: true }, { name: 'Подписание', optional: false }],
  nextAction: 'Позвонить', nextActionAt: '2026-10-25T09:00:00Z', ...patch
})

test('путь вуза: группы по программе, идущие над завершёнными, новый цикл над прошлым', () => {
  const old = pathWork('old', { status: 'COMPLETED', createdAt: '2025-01-01T00:00:00Z', daysOnStage: null, stagePosition: 3 })
  const fresh = pathWork('new', { createdAt: '2026-09-01T00:00:00Z' })
  const other = pathWork('other', { programId: null, programName: null, due: 'OVERDUE' })
  const data = pathWork('data', { programId: 'g2', programName: 'Анализ данных', stagePosition: 3 })
  const groups = pathGroups([old, other, fresh, data], 'PROGRAM')
  assert.deepEqual(groups.map((group) => [group.label, group.works.map((work) => work.interactionId)]),
    [['Анализ данных', ['data']], ['Цифровой университет', ['new', 'old']], ['Без программы', ['other']]])
  assert.deepEqual(pathGroups([old, fresh, other], 'DUE')[0].works.map((work) => work.interactionId), ['other', 'new', 'old'])
  assert.deepEqual(pathGroups([fresh, data], 'STAGE')[0].works.map((work) => work.interactionId), ['data', 'new'])
})

test('путь вуза: полоса по этапам самой карточки, строка этапа и срока', () => {
  assert.deepEqual(pathSegments(pathWork('a')).map((segment) => segment.state), ['done', 'current', 'ahead'])
  assert.equal(pathSegments(pathWork('a'))[1].optional, true)
  assert.deepEqual(pathSegments(pathWork('b', { status: 'COMPLETED', stagePosition: 3 })).map((segment) => segment.state), ['done', 'done', 'done'])
  assert.equal(stageLine(pathWork('a')), 'Этап 2 из 3: Встреча · на этапе 5 дн.')
  assert.equal(stageLine(pathWork('b', { status: 'COMPLETED', daysOnStage: null })), 'Этап 2 из 3: Встреча', 'у завершённой работы дней на этапе нет')
  assert.equal(dueLine(pathWork('c', { due: 'NO_NEXT_STEP', nextAction: null, nextActionAt: null })).text, 'Следующий шаг не задан')
  assert.equal(dueLine(pathWork('d', { status: 'COMPLETED' })).tone, 'done')
  assert.equal(dueLine(pathWork('e', { due: 'OVERDUE' })).tone, 'overdue')
})
