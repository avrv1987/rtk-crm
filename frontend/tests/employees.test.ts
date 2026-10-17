import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  employeeFilterParams,
  employeeMenuKeys,
  filterWithSegment,
  isEmployeeFiltered,
  noEmployeeFilter,
  panelFilterCount,
  roleSourceLabel,
  segmentOf,
  statusFromQuery,
  syncActionLabel
} from '../src/features/admin/employeeFilters.ts'
import { filterTeams, teamArchivable } from '../src/features/admin/teamList.ts'
import { organizationMenuActions } from '../src/features/admin/organizationList.ts'

const profile = (patch: Record<string, unknown> = {}) => ({
  id: 'p1',
  role: 'USER' as const,
  pendingActivation: false,
  accountSyncRequired: false,
  roleReconcileRequired: false,
  ...patch
})

test('сегменты «Все / Ожидают / Заблокированы» превращаются в параметр status', () => {
  assert.equal(employeeFilterParams(filterWithSegment(noEmployeeFilter, 'PENDING'), '').status, 'PENDING')
  assert.equal(employeeFilterParams(filterWithSegment(noEmployeeFilter, 'BLOCKED'), '').status, 'BLOCKED')
  assert.equal(employeeFilterParams(filterWithSegment({ ...noEmployeeFilter, status: 'PENDING' }, 'ALL'), '').status, undefined)
})

test('сегмент сохраняет роль и команду, пустые значения не уходят в запрос', () => {
  const filter = filterWithSegment({ status: '', role: 'LEADER', teamId: 't1' }, 'PENDING')
  assert.deepEqual(employeeFilterParams(filter, 'иван'), { status: 'PENDING', role: 'LEADER', teamId: 't1', q: 'иван' })
  assert.deepEqual(employeeFilterParams(noEmployeeFilter, ''), { status: undefined, role: undefined, teamId: undefined, q: undefined })
})

test('«Активные» и «Требуют синхронизации» не подсвечивают сегмент, а считаются на кнопке «Фильтры»', () => {
  assert.equal(segmentOf(noEmployeeFilter), 'ALL')
  assert.equal(segmentOf({ ...noEmployeeFilter, status: 'PENDING' }), 'PENDING')
  assert.equal(segmentOf({ ...noEmployeeFilter, status: 'ACTIVE' }), null)
  assert.equal(segmentOf({ ...noEmployeeFilter, status: 'SYNC_REQUIRED' }), null)
  assert.equal(panelFilterCount(noEmployeeFilter), 0)
  assert.equal(panelFilterCount({ status: 'PENDING', role: '', teamId: '' }), 0)
  assert.equal(panelFilterCount({ status: 'ACTIVE', role: 'ADMIN', teamId: 't' }), 3)
  assert.equal(isEmployeeFiltered(noEmployeeFilter, ''), false)
  assert.equal(isEmployeeFiltered(noEmployeeFilter, 'а'), true)
})

test('адрес ?status=PENDING и старое ?pending=true включают фильтр «Ожидают»', () => {
  assert.equal(statusFromQuery('status=PENDING'), 'PENDING')
  assert.equal(statusFromQuery('pending=true'), 'PENDING')
  assert.equal(statusFromQuery('status=SYNC_REQUIRED'), 'SYNC_REQUIRED')
  assert.equal(statusFromQuery('status=bogus'), '')
  assert.equal(statusFromQuery(''), '')
})

test('«⋯» строки: у своего профиля нет сброса пароля, завершения сеансов и второго фактора', () => {
  assert.deepEqual(employeeMenuKeys(profile(), 'someone'), ['edit', 'journal', 'reset', 'logout', 'otp', 'email'])
  assert.deepEqual(employeeMenuKeys(profile(), 'p1'), ['edit', 'journal', 'email'])
})

test('«⋯» строки: у администратора нет сброса пароля и второго фактора, сеансы и почта остаются', () => {
  assert.deepEqual(employeeMenuKeys(profile({ role: 'ADMIN' }), 'someone'), ['edit', 'journal', 'logout', 'email'])
})

test('«⋯» строки: у представителя вуза нет смены почты; у ожидающего «Изменить» заменяет кнопка «Активировать»', () => {
  assert.ok(!employeeMenuKeys(profile({ role: 'PARTNER' }), 'someone').includes('email'))
  assert.ok(!employeeMenuKeys(profile({ pendingActivation: true }), 'someone').includes('edit'))
})

test('«⋯» строки: действие синхронизации появляется при признаках и подписано по ситуации', () => {
  assert.ok(!employeeMenuKeys(profile(), 'someone').includes('sync'))
  assert.equal(employeeMenuKeys(profile({ accountSyncRequired: true }), 'someone').at(-1), 'sync')
  assert.equal(employeeMenuKeys(profile({ roleReconcileRequired: true }), 'someone').at(-1), 'sync')
  assert.equal(syncActionLabel({ active: true, accountSyncRequired: true }), 'Включить в Keycloak')
  assert.equal(syncActionLabel({ active: false, accountSyncRequired: true }), 'Отключить в Keycloak')
  assert.equal(syncActionLabel({ active: true, accountSyncRequired: false }), 'Сверить роль с Keycloak')
})

test('пометка источника роли: «из Keycloak», «в Keycloak не перенесена», у ожидающих — ничего', () => {
  assert.equal(roleSourceLabel({ roleSyncedAt: '2026-10-01T00:00:00Z', pendingActivation: false }), 'из Keycloak')
  assert.equal(roleSourceLabel({ roleSyncedAt: null, pendingActivation: false }), 'в Keycloak не перенесена')
  assert.equal(roleSourceLabel({ roleSyncedAt: null, pendingActivation: true }), null)
})

test('команды: поиск без учёта регистра и состояние «Действующие / Архив / Все»', () => {
  const teams = [
    { name: 'Команда Север', archived: false },
    { name: 'Команда Юг', archived: false },
    { name: 'Старая команда', archived: true }
  ]
  assert.deepEqual(filterTeams(teams, '', 'ACTIVE').map((team) => team.name), ['Команда Север', 'Команда Юг'])
  assert.deepEqual(filterTeams(teams, '', 'ARCHIVED').map((team) => team.name), ['Старая команда'])
  assert.equal(filterTeams(teams, '', 'ALL').length, 3)
  assert.deepEqual(filterTeams(teams, ' ЮГ ', 'ALL').map((team) => team.name), ['Команда Юг'])
})

test('команду можно архивировать, только если в ней нет организаций и людей', () => {
  const empty = { organizationCount: 0, leaderNames: [], managerNames: [], otherProfileCount: 0 }
  assert.equal(teamArchivable(empty), true)
  assert.equal(teamArchivable({ ...empty, organizationCount: 1 }), false)
  assert.equal(teamArchivable({ ...empty, managerNames: ['А'] }), false)
  assert.equal(teamArchivable({ ...empty, otherProfileCount: 2 }), false)
})

test('«⋯» организации: правка не для архивных и открытого набора, подтверждение только для заявок', () => {
  assert.deepEqual(organizationMenuActions({ status: 'ACTIVE', type: 'UNIVERSITY' }), ['edit', 'ARCHIVE', 'transfer'])
  assert.deepEqual(organizationMenuActions({ status: 'PENDING', type: 'SCHOOL' }), ['edit', 'APPROVE', 'REJECT', 'ARCHIVE', 'transfer'])
  assert.deepEqual(organizationMenuActions({ status: 'ARCHIVED', type: 'COLLEGE' }), ['RESTORE', 'transfer'])
  assert.deepEqual(organizationMenuActions({ status: 'ACTIVE', type: 'OPEN_ENROLLMENT' }), ['ARCHIVE', 'transfer'])
})
