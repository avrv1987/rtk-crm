import assert from 'node:assert/strict'
import { test } from 'node:test'
import { isSameRouteClick } from '../src/app/routing.ts'

test('повторный клик по ссылке на уже открытый адрес считается навигацией на тот же маршрут', () => {
  assert.equal(isSameRouteClick('#/work', '#/work'), true)
  assert.equal(isSameRouteClick('#/work?due=OVERDUE', '#/work?due=OVERDUE'), true)
})

test('клик по ссылке на другой адрес не считается навигацией на тот же маршрут', () => {
  assert.equal(isSameRouteClick('#/work?due=OVERDUE', '#/work'), false)
  assert.equal(isSameRouteClick('#/organizations', '#/work'), false)
})

test('внешние и не-хэш ссылки игнорируются', () => {
  assert.equal(isSameRouteClick(null, '#/work'), false)
  assert.equal(isSameRouteClick('https://example.com/#/work', '#/work'), false)
  assert.equal(isSameRouteClick('mailto:a@b.ru', ''), false)
})
