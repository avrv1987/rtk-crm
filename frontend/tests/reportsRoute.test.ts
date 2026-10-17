import assert from 'node:assert/strict'
import { test } from 'node:test'
import { homeTabFromQuery, homeTabHash, jobsLinkHash } from '../src/features/reports/reportsRoute.ts'

test('вкладка главной отчётов берётся из параметра tab', () => {
  assert.equal(homeTabFromQuery(undefined), 'catalog')
  assert.equal(homeTabFromQuery(''), 'catalog')
  assert.equal(homeTabFromQuery('tab=saved'), 'saved')
  assert.equal(homeTabFromQuery('x=1&tab=jobs'), 'jobs')
  assert.equal(homeTabFromQuery('tab=unknown'), 'catalog')
})

test('адреса вкладок совпадают с разбором', () => {
  assert.equal(homeTabHash('catalog'), '#/reports')
  assert.equal(homeTabFromQuery(homeTabHash('saved').split('?')[1]), 'saved')
  assert.equal(jobsLinkHash, '#/reports?tab=jobs')
})
