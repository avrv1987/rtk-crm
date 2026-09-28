import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  formatCalendarDate,
  formatMoscowDateTime,
  isoFromMoscowInput,
  moscowInputValue,
  shiftCalendarDate,
  todayInMoscow
} from '../src/shared/format/datetime.ts'

test('время момента показывается по МСК с пометкой', () => {
  assert.equal(formatMoscowDateTime('2026-09-30T07:00:00Z'), '30 сент. 2026 г., 10:00 МСК')
})

test('календарная дата не сдвигается часовым поясом', () => {
  assert.equal(formatCalendarDate('2026-01-01'), '1 янв. 2026 г.')
  assert.equal(formatCalendarDate('2026-01-01T00:00:00Z'), '1 янв. 2026 г.')
})

test('сдвиг календарной даты не зависит от часового пояса браузера', () => {
  assert.equal(shiftCalendarDate('2026-09-28', 7), '2026-10-05')
})

test('«сегодня» по Москве — YYYY-MM-DD', () => {
  assert.match(todayInMoscow(new Date('2026-09-30T21:30:00Z')), /^2026-10-01$/)
})

test('поле срока (datetime-local) показывает и принимает московское время, а не время браузера', () => {
  assert.equal(moscowInputValue('2026-09-30T07:00:00Z'), '2026-09-30T10:00')
  assert.equal(isoFromMoscowInput('2026-09-30T10:00'), '2026-09-30T07:00:00.000Z')
  assert.equal(moscowInputValue(null), '')
  assert.equal(isoFromMoscowInput(''), null)
})
