import assert from 'node:assert/strict'
import { test } from 'node:test'
import { currentPeriod, periodTitle, planKindLabels, planStateLabels, quarterLabel, stateCounts, yearOptions } from '../src/features/reports/signingPlan.ts'

test('квартал определяется по московской дате, границы кварталов точные', () => {
  assert.deepEqual(currentPeriod('2026-01-01'), { year: 2026, quarter: 1 })
  assert.deepEqual(currentPeriod('2026-03-31'), { year: 2026, quarter: 1 })
  assert.deepEqual(currentPeriod('2026-04-01'), { year: 2026, quarter: 2 })
  assert.deepEqual(currentPeriod('2026-09-29'), { year: 2026, quarter: 3 })
  assert.deepEqual(currentPeriod('2026-12-31'), { year: 2026, quarter: 4 })
})

test('заголовок периода — квартал или год, выбор года — окно вокруг текущего', () => {
  assert.equal(quarterLabel(4), 'IV квартал')
  assert.equal(periodTitle(2026, 4), 'IV квартал 2026 года')
  assert.equal(periodTitle(2027, undefined), '2027 год')
  assert.deepEqual(yearOptions(2026), [2024, 2025, 2026, 2027, 2028])
})

test('подписи вида и статуса по-русски, без служебных кодов', () => {
  assert.equal(planKindLabels.SIGNING, 'Подписание')
  assert.equal(planKindLabels.RENEWAL, 'Продление')
  assert.deepEqual(Object.values(planStateLabels), ['Выполнено', 'Просрочено', 'Впереди'])
})

test('итоги по статусам считаются по строкам', () => {
  assert.deepEqual(stateCounts([{ state: 'DONE' }, { state: 'OVERDUE' }, { state: 'OVERDUE' }, { state: 'UPCOMING' }]), {
    done: 1,
    overdue: 2,
    upcoming: 1
  })
  assert.deepEqual(stateCounts([]), { done: 0, overdue: 0, upcoming: 0 })
})
