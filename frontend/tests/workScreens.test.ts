import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { WorkControl, WorkControlItem, WorkControlManager } from '../src/shared/api/client.ts'
import {
  controlQuery,
  controlViewFromQuery,
  defaultStuckDays,
  emptyControlFilters,
  filterControl,
  isHotManager,
  panelFilterCount,
  sortedManagers,
  tileCounts,
  tileMatches
} from '../src/features/control/controlModel.ts'
import { groupFeed } from '../src/features/work/deadlines.ts'
import { feedGroupsFor, readKamView, saveKamView } from '../src/features/work/workView.ts'
import { enrolmentBack, enrolmentHref, enrolmentRoute } from '../src/features/enrolment/enrolmentRoute.ts'

const totals = { organizations: 1, works: 1, overdue: 0, thisWeek: 0, noNextStep: 0, inOrder: 0, withIssues: 0, moved: 0, created: 0, completed: 0 }

const item = (id: string, patch: Partial<WorkControlItem> = {}): WorkControlItem => ({
  interactionId: id,
  organizationId: `org-${id}`,
  organizationName: `Вуз ${id}`,
  title: `Взаимодействие ${id}`,
  products: [],
  stage: 'Встреча',
  stagePosition: 2,
  stageTotal: 13,
  daysOnStage: 3,
  due: 'IN_ORDER',
  status: 'ACTIVE',
  problems: 0,
  risks: 0,
  transitions: 0,
  created: false,
  completed: false,
  ...patch
})

const manager = (managerId: string | null, works: WorkControlItem[], patch: Partial<WorkControlManager['totals']> = {}): WorkControlManager => ({
  managerId,
  managerName: managerId === null ? null : `КАМ ${managerId}`,
  totals: { ...totals, ...patch },
  works
})

const control: WorkControl = {
  calculatedAt: '2026-10-17T09:00:00Z',
  byTeams: true,
  total: { ...totals, organizations: 5, works: 6, overdue: 2, thisWeek: 1, noNextStep: 1, withIssues: 1 },
  teams: [
    {
      teamId: 't1',
      teamName: 'Команда 1',
      totals: { ...totals, overdue: 1 },
      managers: [
        manager('m1', [
          item('a', { due: 'OVERDUE', problems: 1 }),
          item('b', { due: 'THIS_WEEK', agreementStatus: 'REVIEW', daysOnStage: 45 }),
          item('c', { daysOnStage: 90, status: 'COMPLETED' })
        ], { overdue: 1 })
      ]
    },
    {
      teamId: 't2',
      teamName: 'Команда 2',
      totals: { ...totals, overdue: 3 },
      managers: [
        manager('m2', [item('d', { due: 'NO_NEXT_STEP', learning: { runs: 1, participants: 4, signals: 0, signalTitles: [], mismatch: 'Этап раньше «Занятий»' } })]),
        manager(null, [item('e', { due: 'OVERDUE', agreementStatus: 'ACTIVE', learning: { runs: 1, participants: 2, signals: 2, signalTitles: ['Мало заходят'] } })])
      ]
    }
  ]
}

test('плитка «Контроля» отбирает строки по сроку, проблемам, договору, назначению, «долго на этапе» и LMS', () => {
  const ids = (tile: Parameters<typeof filterControl>[1], days = defaultStuckDays) => (
    filterControl(control, tile, days).teams.flatMap((team) => team.managers.flatMap((group) => group.works.map((work) => work.interactionId)))
  )
  assert.deepEqual(ids(null), ['a', 'b', 'c', 'd', 'e'])
  assert.deepEqual(ids('OVERDUE'), ['a', 'e'])
  assert.deepEqual(ids('THIS_WEEK'), ['b'])
  assert.deepEqual(ids('NO_NEXT_STEP'), ['d'])
  assert.deepEqual(ids('ISSUES'), ['a'])
  assert.deepEqual(ids('AGREEMENT'), ['b'], 'договор «Действует» не считается договором на согласовании')
  assert.deepEqual(ids('UNASSIGNED'), ['e'])
  assert.deepEqual(ids('STUCK'), ['b'], 'завершённое взаимодействие не считается «долго на этапе»')
  assert.deepEqual(ids('STUCK', 60), [])
  assert.deepEqual(ids('LMS'), ['d', 'e'], 'сверка этапа без сигналов тоже попадает в плитку LMS')
})

test('отбор плиткой скрывает пустые группы, а без отбора матрица не меняется', () => {
  assert.equal(filterControl(control, null, 30), control)
  const overdue = filterControl(control, 'OVERDUE', 30)
  assert.deepEqual(overdue.teams.map((team) => team.managers.map((group) => group.managerId)), [['m1'], [null]])
  assert.deepEqual(filterControl(control, 'THIS_WEEK', 30).teams.map((team) => team.teamId), ['t1'])
  assert.equal(overdue.teams[0].totals.overdue, 1, 'итоги групп остаются серверными')
})

test('числа на плитках: сроки и проблемы — из итогов сервера, остальное — по строкам', () => {
  assert.deepEqual(tileCounts(control, 30), { OVERDUE: 2, THIS_WEEK: 1, NO_NEXT_STEP: 1, ISSUES: 1, AGREEMENT: 1, STUCK: 1, LMS: 2 })
  assert.equal(tileMatches(item('x', { daysOnStage: 30 }), 'm', 'STUCK', 30), true)
  assert.equal(tileMatches(item('x', { daysOnStage: 29 }), 'm', 'STUCK', 30), false)
  assert.equal(tileMatches(item('x', { daysOnStage: 99, status: 'PAUSED' }), 'm', 'STUCK', 30), false)
})

test('плитка и порог «долго на этапе» живут в адресе рядом с фильтрами и переживают круг', () => {
  const filters = { ...emptyControlFilters, managerId: ['m1'], from: '2026-10-01', to: '2026-10-31' }
  const query = controlQuery(filters, { tile: 'OVERDUE', stuckDays: 14 })
  assert.equal(query, 'manager=m1&from=2026-10-01&to=2026-10-31&tile=OVERDUE&stuck=14')
  assert.deepEqual(controlViewFromQuery(query), { tile: 'OVERDUE', stuckDays: 14 })
  assert.equal(controlQuery(emptyControlFilters, { tile: null, stuckDays: defaultStuckDays }), '')
  assert.deepEqual(controlViewFromQuery('tile=EVERYTHING&stuck=-3'), { tile: null, stuckDays: defaultStuckDays })
  assert.deepEqual(controlViewFromQuery('view=list&due=OVERDUE'), { tile: null, stuckDays: defaultStuckDays })
  assert.equal(panelFilterCount({ ...filters, stage: ['Встреча'] }), 2, 'период не входит в число на кнопке «Фильтры»')
})

test('группы менеджеров: «Требует назначения» первой, раскрыты горящие', () => {
  const groups = control.teams[1].managers
  assert.deepEqual(sortedManagers(groups).map((group) => group.managerId), [null, 'm2'])
  assert.equal(isHotManager(manager('m3', [], { overdue: 1 })), true)
  assert.equal(isHotManager(manager('m3', [], { withIssues: 2 })), true)
  assert.equal(isHotManager(manager('m3', [])), false)
  assert.equal(isHotManager(manager(null, [])), true)
})

test('«Моя работа» КАМ: переключатель видов запоминается, сбой хранилища не ломает экран', () => {
  const values = new Map<string, string>()
  const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => void values.set(key, value) }
  assert.equal(readKamView(storage), 'deadlines')
  assert.equal(saveKamView(storage, 'signals'), true)
  assert.equal(readKamView(storage), 'signals')
  const broken = { getItem: () => { throw new Error('blocked') }, setItem: () => { throw new Error('blocked') } }
  assert.equal(readKamView(broken), 'deadlines')
  assert.equal(saveKamView(broken, 'signals'), false)
  assert.equal(readKamView(null), 'deadlines')
})

test('плитки КАМ отбирают группы ленты; без шага или срока — только в своей группе', () => {
  assert.deepEqual(feedGroupsFor(null), ['overdue', 'today', 'week', 'later', 'noStep'])
  assert.deepEqual(feedGroupsFor('OVERDUE'), ['overdue'])
  assert.deepEqual(feedGroupsFor('THIS_WEEK'), ['today', 'week'])
  assert.deepEqual(feedGroupsFor('NO_NEXT_STEP'), ['noStep'])
  assert.deepEqual(feedGroupsFor('LICENSES'), ['overdue', 'today', 'week', 'later', 'noStep'])
  const now = Date.parse('2026-10-14T09:00:00Z')
  const work = (id: string, nextAction: string | null, nextActionAt: string | null) => ({ id, nextAction, nextActionAt }) as never
  const grouped = groupFeed(
    [work('late', 'Позвонить', '2026-10-13T09:00:00Z'), work('soon', 'Встреча', '2026-10-30T09:00:00Z'), work('dateOnly', '', '2026-10-15T09:00:00Z')],
    [work('dateOnly', '', '2026-10-15T09:00:00Z'), work('nothing', null, null)],
    now
  )
  assert.deepEqual([...grouped.keys()], ['overdue', 'later', 'noStep'])
  assert.deepEqual((grouped.get('noStep') ?? []).map((value: { id: string }) => value.id), ['dateOnly', 'nothing'])
})

test('«Зачисление»: у внутренних экранов есть адреса, «Назад» ведёт на уровень выше', () => {
  assert.deepEqual(enrolmentRoute('#/enrolment'), { kind: 'streams' })
  assert.deepEqual(enrolmentRoute('#/enrolment/stream/s-1'), { kind: 'stream', streamId: 's-1' })
  assert.deepEqual(enrolmentRoute('#/enrolment/learner/l-2?stream=s-1'), { kind: 'learner', learnerId: 'l-2', streamId: 's-1' })
  assert.deepEqual(enrolmentRoute('#/enrolment/learner/l-2'), { kind: 'learner', learnerId: 'l-2', streamId: null })
  assert.deepEqual(enrolmentRoute('#/enrolment/stream/'), { kind: 'streams' })
  assert.deepEqual(enrolmentRoute('#/enrolment/learner/<script>'), { kind: 'streams' })
  assert.deepEqual(enrolmentRoute('#/enrolment/other/s-1'), { kind: 'streams' })
  for (const hash of ['#/enrolment', '#/enrolment/stream/s-1', '#/enrolment/learner/l-2?stream=s-1', '#/enrolment/learner/l-2']) {
    assert.equal(enrolmentHref(enrolmentRoute(hash)), hash)
  }
  assert.equal(enrolmentBack({ kind: 'streams' }), null)
  assert.deepEqual(enrolmentBack({ kind: 'stream', streamId: 's-1' }), { href: '#/enrolment', label: '← Потоки' })
  assert.deepEqual(enrolmentBack({ kind: 'learner', learnerId: 'l', streamId: 's-1' }), { href: '#/enrolment/stream/s-1', label: '← Поток' })
  assert.deepEqual(enrolmentBack({ kind: 'learner', learnerId: 'l', streamId: null }), { href: '#/enrolment', label: '← Потоки' })
})
