import assert from 'node:assert/strict'
import { test } from 'node:test'
import { homeTabFromQuery, homeTabHash, jobsLinkHash } from '../src/features/reports/reportsRoute.ts'
import { periodPresets } from '../src/features/reports/reportPeriods.ts'
import { reportTaskFor, reportTasks, taskHref, taskSelection } from '../src/features/reports/reportTasks.ts'
import type { ReportSelection } from '../src/features/reports/reportSelection.ts'

test('вкладка главной отчётов берётся из параметра tab', () => {
  assert.equal(homeTabFromQuery(undefined), 'tasks')
  assert.equal(homeTabFromQuery(''), 'tasks')
  assert.equal(homeTabFromQuery('tab=catalog'), 'catalog')
  assert.equal(homeTabFromQuery('tab=saved'), 'saved')
  assert.equal(homeTabFromQuery('x=1&tab=jobs'), 'jobs')
  assert.equal(homeTabFromQuery('tab=unknown'), 'tasks')
})

test('адреса вкладок совпадают с разбором', () => {
  assert.equal(homeTabHash('tasks'), '#/reports')
  assert.equal(homeTabFromQuery(homeTabHash('catalog').split('?')[1]), 'catalog')
  assert.equal(homeTabFromQuery(homeTabHash('saved').split('?')[1]), 'saved')
  assert.equal(jobsLinkHash, '#/reports?tab=jobs')
})

test('первый уровень отчётов — четыре задачи, каждая с методикой и своим отчётом', () => {
  assert.deepEqual(reportTasks.map((task) => task.title), [
    'Что требует внимания', 'Что сделано за период', 'Что происходит с обучением', 'Исполнение договоров'
  ])
  assert.deepEqual(reportTasks.map((task) => task.view), ['manager-work', 'manager-work', 'learning-dynamics', 'agreements'])
  reportTasks.forEach((task) => assert.ok(task.method.length > 60, task.id))
  assert.equal(taskHref(reportTasks[1]), '#/reports/manager-work?task=done')
  assert.equal(reportTaskFor('manager-work', 'task=done')?.id, 'done')
  assert.equal(reportTaskFor('portfolio', 'task=done'), null)
  assert.equal(reportTaskFor('manager-work', ''), null)
})

test('задача задаёт вид, период и колонки отчёта и сбрасывает прежние отборы', () => {
  const presets = periodPresets('2026-10-18')
  const stored = {
    kind: 'PORTFOLIO', from: '2026-01-01', to: '2026-01-31', organizationIds: ['o1'], managerIds: ['m1'], stages: ['Пилот'],
    flags: ['RISK'], change: 'MOVED', agreement: { vendorIds: ['v1'], licenseSigned: 'true', licenseExpiresBy: '', notTransferred: [] },
    columns: { PORTFOLIO: ['ORGANIZATION'], MANAGER_WORK: ['MANAGER'], AGREEMENTS: ['ORGANIZATION'] }
  } as unknown as ReportSelection
  const done = taskSelection(stored, reportTasks[1], presets)
  assert.equal(done.kind, 'MANAGER_WORK')
  assert.deepEqual([done.from, done.to], ['2026-09-01', '2026-09-30'])
  assert.deepEqual([done.organizationIds, done.managerIds, done.stages, done.flags, done.change], [[], [], [], [], ''])
  assert.ok(done.columns.MANAGER_WORK.includes('TRANSITIONS'))
  assert.deepEqual(done.columns.PORTFOLIO, ['ORGANIZATION'])
  const attention = taskSelection(stored, reportTasks[0], presets)
  assert.deepEqual([attention.from, attention.to], ['', ''])
  assert.ok(attention.columns.MANAGER_WORK.includes('DUE_STATE'))
  const agreements = taskSelection(stored, reportTasks[3], presets)
  assert.deepEqual([agreements.kind, agreements.from, agreements.to], ['AGREEMENTS', '2026-01-01', '2026-12-31'])
  assert.deepEqual(agreements.agreement.vendorIds, [])
  assert.equal(taskSelection(stored, reportTasks[2], presets), stored)
})
