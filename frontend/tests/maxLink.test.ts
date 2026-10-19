import assert from 'node:assert/strict'
import { test } from 'node:test'
import { maxLinkAvailable, pollNeeded, stateText } from '../src/features/max/maxLink.ts'
import { subscriptionText, tokenStateText } from '../src/features/max/maxAdmin.ts'
import { categoryLabels } from '../src/features/admin/auditJournal.ts'

const on = { enrolment: false, partner: false, max: true }
const off = { enrolment: false, partner: false }

test('«Подключить MAX» видят только КАМ и руководитель без флага менеджера учебного процесса при включённом модуле', () => {
  assert.equal(maxLinkAvailable({ role: 'USER', enrolmentOperator: false }, on), true)
  assert.equal(maxLinkAvailable({ role: 'LEADER', enrolmentOperator: false }, on), true)
  assert.equal(maxLinkAvailable({ role: 'USER', enrolmentOperator: false }, off), false)
  assert.equal(maxLinkAvailable({ role: 'USER', enrolmentOperator: true }, on), false)
  for (const role of ['ADMIN', 'MANAGEMENT', 'PARTNER']) {
    assert.equal(maxLinkAvailable({ role, enrolmentOperator: false }, on), false)
  }
})

test('опрос состояния идёт только пока ссылка ждёт открытия в MAX', () => {
  assert.equal(pollNeeded('WAITING'), true)
  for (const state of ['NONE', 'FOUND', 'ACTIVE', 'STOPPED'] as const) {
    assert.equal(pollNeeded(state), false)
    assert.ok(stateText[state].length > 0)
  }
})

test('карточка администратора: подписка, токен, раздел журнала', () => {
  assert.equal(subscriptionText({ subscribedAt: null, subscriptionOk: null, subscriptionCheckedAt: null, subscriptionError: null }), 'не подписан')
  assert.match(subscriptionText({
    subscribedAt: '2026-10-18T06:00:00Z', subscriptionOk: false, subscriptionCheckedAt: '2026-10-18T07:00:00Z', subscriptionError: 'HTTP 401'
  }), /^ошибка: HTTP 401, проверено/)
  assert.match(subscriptionText({
    subscribedAt: '2026-10-18T06:00:00Z', subscriptionOk: true, subscriptionCheckedAt: null, subscriptionError: null
  }), /^подписан 18 окт\. 2026 г\., 09:00 МСК$/)
  assert.equal(tokenStateText.NONE, 'не задан')
  assert.equal(categoryLabels.MESSENGER, 'Канал MAX')
})
