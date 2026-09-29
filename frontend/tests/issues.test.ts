import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  calendarDaysBetween,
  emptyIssueFilters,
  isIssueOverdue,
  issueBadges,
  issueFiltersFromQuery,
  issueQueryFromFilters,
  issueTitle
} from '../src/features/issues/issueModel.ts'

test('плашки называют число проблем и наибольший уровень риска', () => {
  assert.deepEqual(issueBadges({ problemCount: 0, riskLevel: null, problems: null, risks: null }), [])
  assert.deepEqual(
    issueBadges({ problemCount: 2, riskLevel: 'HIGH', problems: 'Нет акта; Нет доступа', risks: 'высокий: Срыв сроков' }).map((badge) => [badge.label, badge.tone, badge.title]),
    [['Проблемы: 2', 'overdue', 'Нет акта; Нет доступа'], ['Риск: высокий', 'overdue', 'высокий: Срыв сроков']]
  )
  assert.equal(issueBadges({ problemCount: 0, riskLevel: 'MEDIUM', problems: null, risks: 'средний: x' })[0].tone, 'missing')
})

test('заголовок записи, возраст и просрочка', () => {
  assert.equal(issueTitle({ kind: 'PROBLEM', riskLevel: null }), 'Проблема')
  assert.equal(issueTitle({ kind: 'RISK', riskLevel: 'MEDIUM' }), 'Риск, средний')
  assert.equal(calendarDaysBetween('2026-09-20', '2026-09-29'), 9)
  assert.equal(calendarDaysBetween('2026-09-29T10:00:00Z', '2026-09-29'), 0)
  assert.equal(isIssueOverdue({ status: 'OPEN', dueOn: '2026-09-28' }, '2026-09-29'), true)
  assert.equal(isIssueOverdue({ status: 'OPEN', dueOn: '2026-09-29' }, '2026-09-29'), false)
  assert.equal(isIssueOverdue({ status: 'RESOLVED', dueOn: '2026-09-01' }, '2026-09-29'), false)
  assert.equal(isIssueOverdue({ status: 'OPEN', dueOn: null }, '2026-09-29'), false)
})

test('отборы страницы переживают адресную строку, неизвестные значения отбрасываются', () => {
  assert.equal(issueQueryFromFilters(emptyIssueFilters), '')
  const filters = { ...emptyIssueFilters, kind: 'RISK' as const, riskLevel: 'HIGH' as const, overdue: true, status: 'ALL' as const, page: 2 }
  const query = issueQueryFromFilters(filters)
  assert.equal(query, 'kind=RISK&riskLevel=HIGH&overdue=true&status=ALL&page=2')
  assert.deepEqual(issueFiltersFromQuery(query), filters)
  assert.deepEqual(issueFiltersFromQuery('kind=OTHER&status=OPEN&page=-1'), emptyIssueFilters)
})
