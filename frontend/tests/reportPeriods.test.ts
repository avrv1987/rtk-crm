import assert from 'node:assert/strict'
import { test } from 'node:test'
import { defaultPeriod, matchingPreset, periodOnKindChange, periodPresets, previousMonthEnd, suggestedPeriod } from '../src/features/reports/reportPeriods.ts'

const ranges = (today: string) => Object.fromEntries(periodPresets(today).map((preset) => [preset.id, [preset.from, preset.to]]))

test('быстрые периоды отчёта считаются от московского «сегодня» целыми календарными периодами', () => {
  assert.deepEqual(ranges('2026-09-30'), {
    WEEK: ['2026-09-28', '2026-10-04'],
    MONTH: ['2026-09-01', '2026-09-30'],
    QUARTER: ['2026-07-01', '2026-09-30'],
    YEAR: ['2026-01-01', '2026-12-31'],
    PREVIOUS_MONTH: ['2026-08-01', '2026-08-31']
  })
})

test('переходы через год и неделя с воскресеньем', () => {
  const january = ranges('2027-01-03')
  assert.deepEqual(january.WEEK, ['2026-12-28', '2027-01-03'])
  assert.deepEqual(january.PREVIOUS_MONTH, ['2026-12-01', '2026-12-31'])
  assert.deepEqual(ranges('2028-02-10').MONTH, ['2028-02-01', '2028-02-29'])
  assert.deepEqual(ranges('2026-11-15').QUARTER, ['2026-10-01', '2026-12-31'])
})

test('выбранные даты узнаются как быстрый период, иначе — свой диапазон', () => {
  assert.equal(matchingPreset('2026-09-01', '2026-09-30', '2026-09-30'), 'MONTH')
  assert.equal(matchingPreset('2026-09-01', '2026-09-29', '2026-09-30'), null)
  assert.equal(matchingPreset('', '', '2026-09-30'), null)
  assert.equal(previousMonthEnd('2026-03-15'), '2026-02-28')
})

test('портфель открывается без периода, остальные отчёты — с текущим месяцем', () => {
  assert.deepEqual(defaultPeriod('PORTFOLIO', '2026-10-17'), { from: '', to: '' })
  assert.deepEqual(defaultPeriod('EVENTS', '2026-10-17'), { from: '2026-10-01', to: '2026-10-31' })
  assert.deepEqual(defaultPeriod('MANAGER_WORK', '2026-10-17'), { from: '2026-10-01', to: '2026-10-31' })
})

test('при смене вида подменяется только нетронутое умолчание, выбранный период сохраняется', () => {
  const month = { from: '2026-10-01', to: '2026-10-31' }
  const empty = { from: '', to: '' }
  const custom = { from: '2026-08-15', to: '2026-09-15' }
  const today = '2026-10-17'
  assert.deepEqual(periodOnKindChange(month, 'EVENTS', 'PORTFOLIO', today), empty)
  assert.deepEqual(periodOnKindChange(empty, 'PORTFOLIO', 'EVENTS', today), month)
  assert.deepEqual(periodOnKindChange(custom, 'EVENTS', 'PORTFOLIO', today), custom)
  assert.deepEqual(periodOnKindChange(custom, 'PORTFOLIO', 'DEMAND', today), custom)
  assert.deepEqual(periodOnKindChange(empty, 'EVENTS', 'DEMAND', today), empty)
  assert.deepEqual(periodOnKindChange(month, 'PORTFOLIO', 'PORTFOLIO', today), month)
})

test('сохранение отчёта предлагает выбранный готовый период, а не даты', () => {
  assert.equal(suggestedPeriod({ from: '2026-10-01', to: '2026-10-31' }, '2026-10-18'), 'CURRENT_MONTH')
  assert.equal(suggestedPeriod({ from: '2026-09-01', to: '2026-09-30' }, '2026-10-18'), 'PREVIOUS_MONTH')
  assert.equal(suggestedPeriod({ from: '2026-10-01', to: '2026-12-31' }, '2026-10-18'), 'CURRENT_QUARTER')
  assert.equal(suggestedPeriod({ from: '2026-10-02', to: '2026-10-31' }, '2026-10-18'), '')
  assert.equal(suggestedPeriod({ from: null, to: null }, '2026-10-18'), '')
})
