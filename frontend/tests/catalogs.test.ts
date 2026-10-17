import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  catalogAddLabels,
  catalogTabFrom,
  catalogTabLabels,
  catalogTabSlugs
} from '../src/features/admin/catalogTabs.ts'
import { importStepOf, parseTransferStatuses } from '../src/features/admin/catalogImportSteps.ts'

test('вкладки справочников: шесть слагов, у каждой есть подпись', () => {
  assert.deepEqual([...catalogTabSlugs], ['directions', 'programs', 'vendors', 'products', 'activity-kinds', 'journal'])
  for (const slug of catalogTabSlugs) {
    assert.ok(catalogTabLabels[slug].length > 0, slug)
  }
  assert.equal(catalogTabLabels['activity-kinds'], 'Виды мероприятий')
})

test('вкладка из строки запроса: с «?» и без, по умолчанию «ИТ-направления»', () => {
  assert.equal(catalogTabFrom('tab=vendors'), 'vendors')
  assert.equal(catalogTabFrom('?tab=activity-kinds'), 'activity-kinds')
  assert.equal(catalogTabFrom('x=1&tab=journal'), 'journal')
  assert.equal(catalogTabFrom(''), 'directions')
  assert.equal(catalogTabFrom('tab=unknown'), 'directions')
  assert.equal(catalogTabFrom('tab='), 'directions')
})

test('главная кнопка страницы зависит от вкладки; у журнала её нет', () => {
  assert.equal(catalogAddLabels.directions, 'Добавить направление')
  assert.equal(catalogAddLabels['activity-kinds'], 'Добавить вид мероприятия')
  assert.equal(catalogAddLabels.journal, undefined)
})

test('шаг импорта: файл, столбцы, проверка, применение', () => {
  assert.equal(importStepOf({ hasSheets: false, planStatus: null, confirming: false }), 1)
  assert.equal(importStepOf({ hasSheets: true, planStatus: null, confirming: false }), 2)
  assert.equal(importStepOf({ hasSheets: true, planStatus: 'PREVIEWED', confirming: false }), 3)
  assert.equal(importStepOf({ hasSheets: true, planStatus: 'PREVIEWED', confirming: true }), 4)
  assert.equal(importStepOf({ hasSheets: true, planStatus: 'APPLIED', confirming: false }), 4)
})

test('словарь статусов: разбор строк и сообщения об ошибках прежние', () => {
  assert.deepEqual(parseTransferStatuses('передан=Передано\n\nв ожидании = В ожидании'), {
    statuses: { передан: 'Передано', 'в ожидании': 'В ожидании' }
  })
  assert.equal(
    parseTransferStatuses('передан=Передано\nбез знака').error,
    'Строка 2: используйте формат «значение в файле=значение в CRM».'
  )
  assert.equal(
    parseTransferStatuses('а=б\nа=в').error,
    'Строка 2: значение статуса должно быть непустым и уникальным.'
  )
})
