import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  cycleRouteHint,
  kindDisableWarning,
  kindFilterLabel,
  manualRouteMismatch,
  routeKindNote,
  ruleOverlapNotes,
  templateStagesLine,
  type InteractionKindResolution
} from '../src/features/interactions/interactionKinds.ts'

const resolution: InteractionKindResolution = {
  kindId: 'k1',
  kindName: 'Повышение квалификации',
  kindSource: 'RULE',
  templateId: 't1',
  templateName: 'ПК общий',
  templateSource: 'KIND'
}

const rule = (id: string, priority: number, programId: string | null, extra: Record<string, unknown> = {}) => ({
  id,
  kindName: `Вид ${id}`,
  organizationType: null,
  programId,
  programName: programId === null ? null : `Программа ${programId}`,
  productId: null,
  productName: null,
  priority,
  active: true,
  kindActive: true,
  ...extra
})

test('отключённый вид в отборах помечен, действующий — без пометки', () => {
  assert.equal(kindFilterLabel({ name: 'ПК', active: false }), 'ПК (не действует)')
  assert.equal(kindFilterLabel({ name: 'ПК', active: true }), 'ПК')
})

test('отключение вида называет число его карточек', () => {
  assert.match(kindDisableWarning('ПК', 5), /«ПК»: 5\./)
})

test('пересечение правил с разным приоритетом: кто выиграет и кто никогда не сработает', () => {
  const general = rule('a', 10, null, { organizationType: 'UNIVERSITY' })
  const draftExact = { id: null, organizationType: 'UNIVERSITY' as const, programId: 'p', productId: null, priority: 50 }
  const [shadowed] = ruleOverlapNotes(draftExact, [general])
  assert.match(shadowed.text, /никогда не сработает/)
  assert.match(shadowed.text, /приоритетом 10/)

  const draftWins = { ...draftExact, organizationType: null, priority: 5 }
  assert.match(ruleOverlapNotes(draftWins, [rule('b', 20, 'p')])[0].text, /перестанет срабатывать/)

  const partial = { id: null, organizationType: null, programId: 'p', productId: null, priority: 50 }
  const [note] = ruleOverlapNotes(partial, [rule('c', 10, null, { productId: 'x', productName: 'Продукт' })])
  assert.match(note.text, /выиграет «продукт «Продукт» → Вид c»/)

  assert.deepEqual(ruleOverlapNotes(draftExact, [{ ...general, active: false }]), [], 'отключённое правило не мешает')
  assert.deepEqual(ruleOverlapNotes(draftExact, [{ ...general, kindActive: false }]), [], 'правило отключённого вида не мешает')
  assert.deepEqual(ruleOverlapNotes({ ...draftExact, active: false }, [general]), [], 'отключённый черновик не предупреждает')
})

test('ручной маршрут, не совпадающий с маршрутом вида, предупреждается', () => {
  assert.equal(manualRouteMismatch(resolution, '', null), null)
  assert.equal(manualRouteMismatch(resolution, 't1', 'ПК общий'), null)
  assert.match(manualRouteMismatch(resolution, 't2', 'Базовый') ?? '', /ведёт по маршруту «ПК общий», а выбран «Базовый»/)
})

test('после смены вида шапка говорит, что маршрут остался от прежнего вида', () => {
  assert.equal(routeKindNote('ПК', { templateName: 'Базовый', fromPreviousKind: false }), null)
  assert.match(routeKindNote('ПК', { templateName: 'Базовый', fromPreviousKind: true }) ?? '', /маршрут «Базовый» остался от прежнего вида/)
})

test('«Новый цикл» показывает, какой маршрут получится', () => {
  assert.match(cycleRouteHint(resolution, null) ?? '', /маршрут «ПК общий» — шаблон вида/)
  assert.equal(cycleRouteHint(resolution, 'Свой'), 'Маршрут «Свой» — выбран вручную; вид «Повышение квалификации».')
  assert.equal(cycleRouteHint(null, null), null)
})

test('руководитель видит этапы выбранного шаблона по порядку', () => {
  assert.equal(templateStagesLine({ stages: [{ name: 'Б', order: 1 }, { name: 'А', order: 0 }] }), 'Этапы: А → Б')
  assert.equal(templateStagesLine(undefined), null)
})
