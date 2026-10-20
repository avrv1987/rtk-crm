import assert from 'node:assert/strict'
import { test } from 'node:test'
import { audienceNote, moodleIntegrationText, rolesFor, rolesLead, siteIntegrationText } from '../src/features/landing/landingModules.ts'
import { errorCodes, tocItems, visibleBy } from '../src/features/help/helpContent.ts'
import { getModules, sectionAvailable, setModules, withoutPaymentColumns } from '../src/shared/modules.ts'

const off = { enrolment: false, partner: false }
const on = { enrolment: true, partner: true, max: true, mail: true }

test('модули по умолчанию выключены, setModules меняет значение', () => {
  assert.deepEqual(getModules(), off)
  setModules(on)
  assert.deepEqual(getModules(), on)
  setModules(off)
})

test('разделы «Зачисление» и «Сотрудничество с ИТ Школой РТК» скрыты при выключенных модулях, остальные доступны', () => {
  assert.equal(sectionAvailable('enrolment', off), false)
  assert.equal(sectionAvailable('partner', off), false)
  assert.equal(sectionAvailable('enrolment', on), true)
  assert.equal(sectionAvailable('partner', on), true)
  assert.equal(sectionAvailable('enrolment', { enrolment: true, partner: false }), true)
  assert.equal(sectionAvailable('partner', { enrolment: true, partner: false }), false)
  for (const section of ['work', 'issues', 'organizations', 'reports', 'admin', 'help']) {
    assert.equal(sectionAvailable(section, off), true)
  }
})

test('колонки оплат убираются из «Востребованности программ» без модуля «Слушатели»', () => {
  const columns = ['DIRECTION', 'PAID_ORDERS', 'PAID_STREAMS', 'PARTICIPANTS']
  assert.deepEqual(withoutPaymentColumns(columns, off), ['DIRECTION', 'PARTICIPANTS'])
  assert.deepEqual(withoutPaymentColumns(columns, on), columns)
})

test('посадочная без модулей не упоминает роли «Менеджер учебного процесса» и «Координатор вуза»', () => {
  const titles = rolesFor(off).map((role) => role.title)
  assert.deepEqual(titles, [
    'Менеджер по работе с вузами (КАМ)', 'Руководитель команды', 'Руководство', 'Администратор платформы'
  ])
  const text = [
    ...rolesFor(off).flatMap((role) => [role.title, role.benefit, ...role.duties]),
    audienceNote(off), rolesLead(off), siteIntegrationText(off), moodleIntegrationText(off)
  ].join(' ').toLowerCase()
  for (const word of ['представител', 'кабинет', 'оплат', 'слушател', 'анкет', 'зачислен']) {
    assert.ok(!text.includes(word), `в тексте посадочной осталось «${word}»`)
  }
})

test('посадочная со включёнными модулями показывает обе роли и приглашение в кабинет', () => {
  const titles = rolesFor(on).map((role) => role.title)
  assert.ok(titles.includes('Менеджер учебного процесса'))
  assert.ok(titles.includes('Координатор вуза'))
  assert.ok(rolesFor(on)[0].duties.some((duty) => duty.includes('доступ в кабинет')))
  assert.ok(siteIntegrationText(on).includes('оплаты'))
})

test('посадочная: Moodle-запись описана только с модулем «Слушатели», вуз везде представляет координатор', () => {
  assert.ok(moodleIntegrationText(off).includes('читает данные об обучении'))
  assert.ok(!moodleIntegrationText(off).includes('Запись'))
  const write = moodleIntegrationText(on)
  assert.ok(write.includes('только с логином (почтой), фамилией и именем'))
  assert.ok(!write.includes('только на чтение'))
  const text = [...rolesFor(on).flatMap((role) => role.duties), audienceNote(on), rolesLead(on)].join(' ')
  assert.ok(!text.includes('представител'))
  assert.ok(rolesLead(on).includes('координатору вуза'))
})

test('при одном включённом модуле показывается только его роль', () => {
  const titles = rolesFor({ enrolment: true, partner: false }).map((role) => role.title)
  assert.ok(titles.includes('Менеджер учебного процесса'))
  assert.ok(!titles.includes('Координатор вуза'))
})

test('справка без модулей не содержит их разделов и кодов ошибок, код отказа входа остаётся', () => {
  const toc = visibleBy(tocItems, off).map((item) => item.id)
  assert.ok(!toc.includes('help-enrolment'))
  assert.ok(!toc.includes('help-partner'))
  assert.ok(toc.includes('help-kam') && toc.includes('help-errors'))
  const codes = visibleBy(errorCodes, off).map((item) => item.code)
  assert.ok(!codes.includes('PARTNER_ACCESS_EXISTS'))
  assert.ok(!codes.includes('PARTNER_ACCOUNT_CONFLICT'))
  assert.ok(codes.includes('PARTNER_MODULE_DISABLED'))
  assert.equal(visibleBy(tocItems, on).length, tocItems.length)
})

test('посадочная 2.5: цикл вуза, виды и свойства этапа, сравнение периодов, надёжность и MCP', async () => {
  const { readFileSync } = await import('node:fs')
  const page = readFileSync(new URL('../src/features/landing/LandingPage.tsx', import.meta.url), 'utf8')
  for (const fact of ['«Путь вуза»', 'Матрица «программа × этап»', "'#lp-cycle'", 'id="lp-cycle"', 'вида взаимодействия свой маршрут',
    '«Требует действующего договора»', 'роль этапа для LMS', 'циклом без выхода к финалу', 'медиана и P90', '«было → стало»',
    '«(текущая)»', '«N из M потоков»', 'Надёжность и защита', '(версия 2.5.3)', "['MCP',"]) {
    assert.ok(page.includes(fact), fact)
  }
  assert.doesNotMatch(page, /ранжирован/)
  const leader = rolesFor(on).find((role) => role.title === 'Руководитель команды')
  assert.ok(leader?.duties.some((duty) => duty.includes('матрица «программа × этап»')))
  assert.ok(rolesFor(on)[0].duties.some((duty) => duty.includes('«Путь вуза»')))
})
