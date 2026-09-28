import assert from 'node:assert/strict'
import { test } from 'node:test'
import { matchingPreset, periodPresets, previousMonthEnd } from '../src/features/reports/reportPeriods.ts'

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
