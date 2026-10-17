import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  actionText,
  appliedFilterCount,
  categoryLabels,
  categoryOptions,
  emptyAuditFilters,
  fallbackActionLabels
} from '../src/features/admin/auditJournal.ts'

test('категория «Входы и выходы» есть в подписях и в списке «Раздел»', () => {
  assert.equal(categoryLabels.LOGIN, 'Входы и выходы')
  assert.ok(categoryOptions(false).some((option) => option.value === 'LOGIN' && option.label === 'Входы и выходы'))
})

test('«Слушатели» в списке «Раздел» только при включённом модуле зачисления', () => {
  assert.ok(!categoryOptions(false).some((option) => option.value === 'LEARNER'))
  assert.ok(categoryOptions(true).some((option) => option.value === 'LEARNER'))
  assert.equal(categoryOptions(true).length, Object.keys(categoryLabels).length)
})

test('запасные подписи пяти новых действий журнала', () => {
  assert.deepEqual(fallbackActionLabels, {
    LOGIN_SUCCEEDED: 'Вход в CRM',
    LOGIN_DENIED: 'Отказ во входе в CRM',
    LOGOUT: 'Выход из CRM',
    ACCOUNT_ROLE_RECONCILED: 'Роль в Keycloak сверена с CRM',
    PROFILES_EXPORTED: 'Выгружен список профилей CRM'
  })
})

test('подпись действия берётся с сервера, при пустой — запасная, иначе нейтральная', () => {
  assert.equal(actionText({ action: 'LOGOUT', actionLabel: 'Выход из CRM' }), 'Выход из CRM')
  assert.equal(actionText({ action: 'LOGIN_DENIED', actionLabel: '  ' }), 'Отказ во входе в CRM')
  assert.equal(actionText({ action: 'TEAM_CREATED', actionLabel: '' }), 'Действие')
})

test('число применённых условий считает только заполненные', () => {
  assert.equal(appliedFilterCount(emptyAuditFilters), 0)
  assert.equal(appliedFilterCount({ from: '2026-10-01', to: '', actor: '  ', object: 'Вуз', category: 'LOGIN' }), 3)
})
