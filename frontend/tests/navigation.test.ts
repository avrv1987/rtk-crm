import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  activeWorkTab,
  isListView,
  navFor,
  parseRoute,
  personInitials,
  roleInitials,
  sectionTitle,
  sectionsFor,
  withoutView,
  workRedirect,
  workTabsFor
} from '../src/app/routing.ts'
import { adminGroupsFor, adminSectionsFor, resolveAdminSection } from '../src/features/admin/adminSections.ts'

const off = { enrolment: false, partner: false }
const on = { enrolment: true, partner: true }
const labels = (role: Parameters<typeof navFor>[0], operator = false, modules = on) => navFor(role, operator, modules).map((item) => item.label)

test('верхнее меню: 4–5 пунктов на роль, у администратора и представителя вуза меню нет', () => {
  assert.deepEqual(labels('USER'), ['Работа', 'Вузы', 'Договоры', 'Отчёты'])
  assert.deepEqual(labels('LEADER'), ['Работа', 'Вузы', 'Договоры', 'Отчёты', 'Настройки команды'])
  assert.deepEqual(labels('MANAGEMENT'), ['Работа', 'Вузы', 'Договоры', 'Отчёты'])
  assert.deepEqual(labels('ADMIN'), [])
  assert.deepEqual(labels('PARTNER'), [])
})

test('«Зачисление» появляется только у оператора при включённом модуле', () => {
  assert.ok(labels('USER', true, on).includes('Зачисление'))
  assert.ok(labels('LEADER', true, on).includes('Зачисление'))
  assert.ok(!labels('USER', true, off).includes('Зачисление'))
  assert.ok(!labels('USER', false, on).includes('Зачисление'))
  assert.ok(sectionsFor('USER', true, on).includes('enrolment'))
  assert.ok(!sectionsFor('USER', true, off).includes('enrolment'))
})

test('нижняя панель телефона — четыре раздела, остальное уходит в меню под аватаром', () => {
  for (const role of ['USER', 'LEADER', 'MANAGEMENT'] as const) {
    const items = navFor(role, true, on)
    assert.deepEqual(items.filter((item) => item.phone).map((item) => item.label), ['Работа', 'Вузы', 'Договоры', 'Отчёты'])
  }
  assert.deepEqual(navFor('LEADER', true, on).filter((item) => !item.phone).map((item) => item.label), ['Настройки команды', 'Зачисление'])
})

test('каждый пункт меню ведёт в раздел, доступный роли, и ни один прежний раздел не потерян', () => {
  const before: Record<string, string[]> = {
    USER: ['work', 'issues', 'organizations', 'agreements', 'reports', 'help'],
    LEADER: ['control', 'work', 'issues', 'organizations', 'agreements', 'reports', 'admin', 'help'],
    MANAGEMENT: ['control', 'work', 'issues', 'organizations', 'agreements', 'reports', 'help'],
    ADMIN: ['admin', 'help'],
    PARTNER: ['partner', 'help']
  }
  for (const [role, sections] of Object.entries(before) as [Parameters<typeof navFor>[0], string[]][]) {
    const allowed = sectionsFor(role, false, on)
    assert.deepEqual([...allowed].sort(), [...sections].sort(), role)
    for (const item of navFor(role, false, on)) {
      for (const section of item.sections) {
        assert.ok(allowed.includes(section), `${role}: ${item.label} → ${section}`)
      }
    }
  }
  assert.deepEqual(sectionsFor('PARTNER', false, off), ['help'])
})

test('группа «Работа»: вкладки по роли и подсветка активной', () => {
  assert.deepEqual(workTabsFor('USER').map((tab) => tab.label), ['Моя работа', 'Проблемы и риски'])
  assert.deepEqual(workTabsFor('LEADER').map((tab) => tab.label), ['Контроль', 'Проблемы и риски'])
  assert.deepEqual(workTabsFor('MANAGEMENT').map((tab) => tab.label), ['Контроль', 'Проблемы и риски'])
  assert.deepEqual(workTabsFor('ADMIN'), [])
  assert.equal(activeWorkTab('control'), 'control')
  assert.equal(activeWorkTab('issues'), 'issues')
  assert.equal(activeWorkTab('organizations'), null)
})

test('#/work у руководителя и «Руководства» переводится в #/control?view=list с прежними параметрами', () => {
  assert.equal(workRedirect('#/work', 'LEADER'), '#/control?view=list')
  assert.equal(workRedirect('#/work?due=OVERDUE&responsible=abc&minDaysOnStage=14', 'MANAGEMENT'),
    '#/control?view=list&due=OVERDUE&responsible=abc&minDaysOnStage=14')
  assert.equal(workRedirect('#/work?view=list&flag=RISK', 'LEADER'), '#/control?view=list&flag=RISK')
  assert.equal(workRedirect('#/work?due=OVERDUE', 'USER'), null)
  assert.equal(workRedirect('#/control', 'LEADER'), null)
  assert.equal(withoutView('view=list&due=OVERDUE'), 'due=OVERDUE')
  assert.equal(isListView('view=list&due=OVERDUE'), true, 'вид списком открывается внутри «Контроля»')
  assert.equal(isListView('tile=OVERDUE'), false)
})

test('старые адреса разбираются как прежде', () => {
  assert.deepEqual(parseRoute('#/organizations/org-1/work-2?issues=1'), { section: 'organizations', organizationId: 'org-1', interactionId: 'work-2', query: 'issues=1' })
  assert.deepEqual(parseRoute('#/reports/signing-plan'), { section: 'reports', organizationId: 'signing-plan', interactionId: undefined, query: '' })
  assert.equal(parseRoute('').section, '')
})

test('заголовки экранов и инициалы роли', () => {
  assert.equal(sectionTitle('admin', 'LEADER'), 'Настройки команды')
  assert.equal(sectionTitle('admin', 'ADMIN'), 'Администрирование')
  assert.equal(sectionTitle('control', 'MANAGEMENT'), 'Контроль')
  assert.equal(sectionTitle('control', 'LEADER'), 'Контроль')
  assert.equal(sectionTitle('work', 'USER'), 'Моя работа')
  assert.equal(roleInitials('LEADER'), 'РК')
  assert.equal(roleInitials('USER'), 'К')
})

test('администрирование: группы бокового меню, «Обзор» первым, прежние и новые адреса подразделов работают', () => {
  assert.deepEqual(adminGroupsFor('ADMIN').map((group) => group.group), ['Начало', 'Люди и доступ', 'Данные', 'Интеграции', 'Безопасность'])
  assert.equal(resolveAdminSection(undefined, 'ADMIN'), 'overview')
  for (const slug of ['employees', 'teams', 'organizations', 'catalogs', 'workflow-templates', 'catalog-import', 'sources', 'journal', 'personal-data', 'retention']) {
    assert.equal(resolveAdminSection(slug, 'ADMIN'), slug)
  }
  assert.equal(resolveAdminSection('profiles', 'ADMIN'), 'employees')
  assert.equal(resolveAdminSection('activity-kinds', 'ADMIN'), 'catalogs')
  assert.equal(resolveAdminSection('pending-source-records', 'ADMIN'), 'overview')
})

test('«Настройки команды» руководителя: только шаблоны этапов и заявки с сайта', () => {
  assert.deepEqual(adminSectionsFor('LEADER').map((section) => section.slug), ['workflow-templates', 'pending-source-records'])
  assert.equal(resolveAdminSection(undefined, 'LEADER'), 'workflow-templates')
  assert.equal(resolveAdminSection('profiles', 'LEADER'), 'workflow-templates')
  assert.equal(resolveAdminSection('employees', 'LEADER'), 'workflow-templates')
  assert.equal(resolveAdminSection('pending-source-records', 'LEADER'), 'pending-source-records')
})

test('инициалы в меню профиля берутся из имени сотрудника, без имени — из роли', () => {
  assert.equal(personInitials('Иван Петров', 'USER'), 'ИП')
  assert.equal(personInitials('КАМ А', 'USER'), 'КА')
  assert.equal(personInitials(null, 'USER'), roleInitials('USER'))
  assert.equal(personInitials('   ', 'LEADER'), roleInitials('LEADER'))
})
