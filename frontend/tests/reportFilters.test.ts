import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { ReportColumn } from '../src/shared/api/client.ts'
import type { ReportSelection } from '../src/features/reports/reportSelection.ts'
import { activeFilterCount, activeFilterGroups, columnRows, filterVisibility, moveColumnTo, resetFilters, setColumnChecked } from '../src/features/reports/reportFilterModel.ts'

const columns = { PORTFOLIO: [], EVENTS: [], DEMAND: [], SNAPSHOT: [], DURATION: [], AGREEMENTS: [], MANAGER_WORK: [] }

const defaultSelection = (kind: ReportSelection['kind']): ReportSelection => ({
  kind, from: '', to: '', asOf: '', periodBasis: 'CREATED', organizationIds: [], directionIds: [], includeNoDirection: false,
  programIds: [], includeNoProgram: false, productIds: [], includeNoProduct: false, kindIds: [], managerIds: [], includeNoManager: false,

  stages: [], organizationType: '', workStatuses: [], flags: [],
  agreement: { vendorIds: [], licenseSigned: '', licenseExpiresBy: '', notTransferred: [] },
  eventTypes: [], minDaysOnStage: '', change: '', columns, groupBy: 'STAGE', chartType: 'BAR', seriesBy: null, sortBy: 'APPLICATIONS'
})

test('без отборов счётчик «Фильтры» пуст', () => {
  assert.equal(activeFilterCount(defaultSelection('PORTFOLIO')), 0)
})

test('счётчик считает группы, а не значения; «Не указано» тоже отбор', () => {
  const selection = {
    ...defaultSelection('PORTFOLIO'),
    organizationIds: ['a', 'b', 'c'],
    includeNoProgram: true,
    minDaysOnStage: '14',
    flags: ['RISK' as const]
  }
  assert.deepEqual(activeFilterGroups(selection), ['organizations', 'programs', 'flags', 'minDays'])
  assert.equal(activeFilterCount(selection), 4)
})

test('отбор, которого вид отчёта не применяет, не считается', () => {
  const base = defaultSelection('AGREEMENTS')
  const selection = { ...base, programIds: ['p'], productIds: ['x'], stages: ['Встреча'], flags: ['RISK' as const], minDaysOnStage: '3' }
  assert.equal(activeFilterCount(selection), 0)
  assert.equal(filterVisibility('DEMAND').products, false)
  assert.equal(filterVisibility('MANAGER_WORK').agreement, false)
  assert.equal(filterVisibility('PORTFOLIO').agreement, true)
})

test('«Сбросить фильтры» очищает отборы и оставляет период, колонки и сортировку', () => {
  const base = defaultSelection('DEMAND')
  const selection = {
    ...base,
    from: '2026-01-01',
    to: '2026-03-31',
    sortBy: 'PARTICIPANTS' as const,
    columns: { ...base.columns, DEMAND: ['PROGRAM' as ReportColumn] },
    organizationIds: ['a'],
    managerIds: ['m'],
    includeNoManager: true,
    organizationType: 'COLLEGE' as const,
    agreement: { ...base.agreement, vendorIds: ['v'], licenseSigned: 'true' as const }
  }
  const reset = resetFilters(selection)
  assert.equal(activeFilterCount(reset), 0)
  assert.equal(reset.from, '2026-01-01')
  assert.equal(reset.to, '2026-03-31')
  assert.equal(reset.sortBy, 'PARTICIPANTS')
  assert.deepEqual(reset.columns.DEMAND, ['PROGRAM'])
})

test('список колонок: выбранные по порядку, затем остальные; отметка добавляет в конец', () => {
  const available: ReportColumn[] = ['ORGANIZATION', 'INTERACTION', 'STAGE', 'RISK']
  const rows = columnRows(available, ['STAGE', 'ORGANIZATION'])
  assert.deepEqual(rows.map((row) => `${row.column}:${row.checked}`), ['STAGE:true', 'ORGANIZATION:true', 'INTERACTION:false', 'RISK:false'])
  assert.deepEqual(setColumnChecked(['STAGE', 'ORGANIZATION'], 'RISK', true), ['STAGE', 'ORGANIZATION', 'RISK'])
  assert.deepEqual(setColumnChecked(['STAGE', 'ORGANIZATION'], 'STAGE', false), ['ORGANIZATION'])
  assert.deepEqual(setColumnChecked(['STAGE'], 'STAGE', true), ['STAGE'])
})

test('перетаскивание колонки на место другой сдвигает остальные', () => {
  assert.deepEqual(moveColumnTo(['ORGANIZATION', 'STAGE', 'RISK'], 'RISK', 'ORGANIZATION'), ['RISK', 'ORGANIZATION', 'STAGE'])
  assert.deepEqual(moveColumnTo(['ORGANIZATION', 'STAGE', 'RISK'], 'ORGANIZATION', 'RISK'), ['STAGE', 'RISK', 'ORGANIZATION'])
  assert.deepEqual(moveColumnTo(['ORGANIZATION', 'STAGE'], 'STAGE', 'STAGE'), ['ORGANIZATION', 'STAGE'])
  assert.deepEqual(moveColumnTo(['ORGANIZATION', 'STAGE'], 'RISK', 'STAGE'), ['ORGANIZATION', 'STAGE'])
})
