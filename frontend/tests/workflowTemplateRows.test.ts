import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  canEditTemplate,
  defaultActionLabel,
  isEffectiveDefault,
  templateRowActions,
  templateScopeLabel
} from '../src/features/admin/workflowTemplateRows.ts'

const common = { id: 'c1', teamId: null, defaultTemplate: false }
const commonDefault = { id: 'c2', teamId: null, defaultTemplate: true }
const team = { id: 't1', teamId: 'team', defaultTemplate: false }

const labels = (menu: { label: string }[]) => menu.map((entry) => entry.label)

test('администратор: общий шаблон — правка, по умолчанию и удаление', () => {
  const row = templateRowActions('ADMIN', common, null)
  assert.equal(row.primary, 'edit')
  assert.equal(row.primaryLabel, 'Редактировать')
  assert.deepEqual(labels(row.menu), ['История версий', 'Сделать шаблоном по умолчанию', 'Удалить'])
  assert.equal(row.menu.at(-1)?.danger, true)
  assert.equal(row.note, null)
})

test('администратор: шаблон по умолчанию удалять нельзя, пункта «Удалить» нет', () => {
  const row = templateRowActions('ADMIN', commonDefault, null)
  assert.deepEqual(labels(row.menu), ['История версий'])
  assert.equal(row.note, 'Шаблон по умолчанию удалять нельзя.')
})

test('руководитель: базовый шаблон копируется, а не правится и не удаляется', () => {
  const row = templateRowActions('LEADER', common, null)
  assert.equal(row.primary, 'copy')
  assert.equal(row.primaryLabel, 'Создать на основе базового')
  assert.deepEqual(labels(row.menu), ['История версий'])
  assert.equal(row.note, 'Базовый шаблон меняет администратор.')
})

test('руководитель: общий шаблон по умолчанию можно вернуть, только если команда использует свой', () => {
  assert.equal(defaultActionLabel('LEADER', commonDefault, null), null)
  assert.equal(defaultActionLabel('LEADER', commonDefault, 't1'), 'Вернуть общий шаблон по умолчанию')
  assert.deepEqual(labels(templateRowActions('LEADER', commonDefault, 't1').menu), ['История версий', 'Вернуть общий шаблон по умолчанию'])
})

test('руководитель: шаблон команды — правка, «по умолчанию» и удаление', () => {
  const row = templateRowActions('LEADER', team, null)
  assert.equal(row.primary, 'edit')
  assert.deepEqual(labels(row.menu), ['История версий', 'Сделать шаблоном команды по умолчанию', 'Удалить'])
  const current = templateRowActions('LEADER', team, 't1')
  assert.deepEqual(labels(current.menu), ['История версий', 'Удалить'])
})

test('любой шаблон с признаком «по умолчанию» не удаляется', () => {
  const row = templateRowActions('LEADER', { ...team, defaultTemplate: true }, 't1')
  assert.equal(row.note, 'Шаблон по умолчанию удалять нельзя.')
  assert.ok(!labels(row.menu).includes('Удалить'))
})

test('права на правку: администратор — общие, руководитель — команды', () => {
  assert.equal(canEditTemplate('ADMIN', common), true)
  assert.equal(canEditTemplate('ADMIN', team), false)
  assert.equal(canEditTemplate('LEADER', team), true)
  assert.equal(canEditTemplate('LEADER', common), false)
})

test('подписи вида шаблона и отметка «по умолчанию»', () => {
  assert.equal(templateScopeLabel(common, null), 'Общий шаблон')
  assert.equal(templateScopeLabel(commonDefault, null), 'Шаблон по умолчанию')
  assert.equal(templateScopeLabel(commonDefault, 't1'), 'Общий шаблон по умолчанию (команда использует свой)')
  assert.match(templateScopeLabel(team, 't1'), /^Шаблон команды по умолчанию/)
  assert.equal(templateScopeLabel(team, null), 'Шаблон команды')
  assert.equal(isEffectiveDefault(commonDefault, null), true)
  assert.equal(isEffectiveDefault(commonDefault, 't1'), false)
  assert.equal(isEffectiveDefault(team, 't1'), true)
})
