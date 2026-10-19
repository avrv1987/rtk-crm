import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  automaticKindLabel,
  effectiveKindTemplate,
  optionalityLabel,
  optionalityOperation,
  resolutionHint,
  ruleConditions,
  type InteractionKindResolution
} from '../src/features/interactions/interactionKinds.ts'
import { controlFiltersFromQuery, controlListHref, controlQueryFromFilters, emptyControlFilters } from '../src/features/control/controlModel.ts'

const resolution: InteractionKindResolution = {
  kindId: 'k1',
  kindName: 'Повышение квалификации',
  kindSource: 'RULE',
  templateId: 't1',
  templateName: 'ПК команды А',
  templateSource: 'TEAM_KIND'
}

test('форма создания: подсказка называет вид, маршрут и откуда они взяты', () => {
  assert.equal(resolutionHint(resolution),
    'Вид «Повышение квалификации» (подобран по правилу), маршрут «ПК команды А» — шаблон команды для вида.')
  assert.equal(resolutionHint({ ...resolution, kindSource: 'FIRST_ACTIVE', templateSource: 'DEFAULT' }),
    'Вид «Повышение квалификации» (вид по умолчанию), маршрут «ПК команды А» — шаблон по умолчанию.')
})

test('пустой вариант выбора вида показывает, что подставят правила', () => {
  assert.equal(automaticKindLabel(null), 'Подобрать по правилам')
  assert.equal(automaticKindLabel(resolution), 'По правилам: Повышение квалификации')
  assert.equal(automaticKindLabel({ ...resolution, kindSource: 'SELECTED' }), 'Подобрать по правилам')
})

test('шаблон вида для команды: командный, иначе шаблон вида, иначе по умолчанию', () => {
  assert.equal(effectiveKindTemplate({ teamTemplateName: 'Команда', templateName: 'Общий' }), 'Команда')
  assert.equal(effectiveKindTemplate({ teamTemplateName: null, templateName: 'Общий' }), 'Общий')
  assert.equal(effectiveKindTemplate({ teamTemplateName: null, templateName: null }), 'шаблон по умолчанию')
})

test('условия правила одной строкой без пустых частей', () => {
  assert.equal(ruleConditions({ organizationType: 'COLLEGE', programName: 'Python', productName: null }), 'Колледж · программа «Python»')
  assert.equal(ruleConditions({ organizationType: null, programName: null, productName: '1С' }), 'продукт «1С»')
})

test('обязательность этапа переключается одной операцией', () => {
  assert.deepEqual(optionalityOperation({ id: 's1', optional: false }), { type: 'SET_OPTIONAL', id: 's1', optional: true })
  assert.deepEqual(optionalityOperation({ id: 's1', optional: true }), { type: 'SET_OPTIONAL', id: 's1', optional: false })
  assert.match(optionalityLabel({ optional: true }), /обязательным/)
})

test('отбор по виду: адрес «Контроля» сохраняет вид, «Показать списком» переносит виды', () => {
  const filters = { ...emptyControlFilters, kindId: ['k1'] }
  assert.equal(controlQueryFromFilters(filters), 'kind=k1')
  assert.deepEqual(controlFiltersFromQuery('kind=k1').kindId, ['k1'])
  assert.match(controlListHref(filters, { tile: null, stuckDays: 30 }), /kind=k1/)
  assert.match(controlListHref({ ...filters, kindId: ['k1', 'k2'] }, { tile: null, stuckDays: 30 }), /kind=k1&kind=k2/)
})

