import assert from 'node:assert/strict'
import { test } from 'node:test'
import { adminSectionsFor, resolveAdminAddress } from '../src/features/admin/adminSections.ts'

test('новые адреса открываются как есть, параметры сохраняются', () => {
  assert.deepEqual(resolveAdminAddress('employees', 'status=PENDING', 'ADMIN'), { section: 'employees', query: 'status=PENDING' })
  assert.deepEqual(resolveAdminAddress('sources', 'tab=review', 'ADMIN'), { section: 'sources', query: 'tab=review' })
  assert.deepEqual(resolveAdminAddress('catalogs', 'tab=vendors', 'ADMIN'), { section: 'catalogs', query: 'tab=vendors' })
  assert.deepEqual(resolveAdminAddress(undefined, '', 'ADMIN'), { section: 'overview', query: '' })
})

test('прежний slug profiles ведёт на «Сотрудники», старое ?pending=true доходит до страницы', () => {
  assert.deepEqual(resolveAdminAddress('profiles', '', 'ADMIN'), { section: 'employees', query: '' })
  assert.deepEqual(resolveAdminAddress('profiles', 'pending=true', 'ADMIN'), { section: 'employees', query: 'pending=true' })
})

test('прежний slug activity-kinds ведёт на вкладку «Виды мероприятий» справочников', () => {
  assert.deepEqual(resolveAdminAddress('activity-kinds', '', 'ADMIN'), { section: 'catalogs', query: 'tab=activity-kinds' })
})

test('недоступный роли или неизвестный адрес ведёт на первый подраздел роли без параметров', () => {
  assert.deepEqual(resolveAdminAddress('employees', 'status=PENDING', 'LEADER'), { section: 'workflow-templates', query: '' })
  assert.deepEqual(resolveAdminAddress('profiles', '', 'LEADER'), { section: 'workflow-templates', query: '' })
  assert.deepEqual(resolveAdminAddress('journal', '', 'LEADER'), { section: 'workflow-templates', query: '' })
  assert.deepEqual(resolveAdminAddress('pending-source-records', '', 'ADMIN'), { section: 'overview', query: '' })
  assert.deepEqual(resolveAdminAddress('nonsense', 'x=1', 'ADMIN'), { section: 'overview', query: '' })
  assert.deepEqual(resolveAdminAddress('pending-source-records', '', 'LEADER'), { section: 'pending-source-records', query: '' })
})

test('у руководителя ровно два подраздела, у администратора — все остальные', () => {
  assert.deepEqual(adminSectionsFor('LEADER').map((section) => section.slug), ['workflow-templates', 'pending-source-records'])
  assert.deepEqual(adminSectionsFor('ADMIN').map((section) => section.slug), [
    'overview', 'settings', 'employees', 'teams', 'organizations', 'catalogs', 'catalog-import', 'workflow-templates',
    'route-versions', 'demand-index', 'people-settings', 'agreement-reporting', 'sources', 'mail', 'modules', 'journal',
    'personal-data', 'personal-data-requests', 'retention'
  ])
})
