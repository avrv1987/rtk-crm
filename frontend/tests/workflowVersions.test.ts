import assert from 'node:assert/strict'
import { test } from 'node:test'
import { diffRoutes, isEmptyDiff, parseRouteFile, routeFileName, routeToFile, type Route } from '../src/features/admin/workflowVersions.ts'

const v1: Route = {
  name: 'Маршрут',
  stages: [
    { id: 'a', name: 'Старт', order: 0, optional: false },
    { id: 'b', name: 'Проверка', order: 1, optional: false },
    { id: 'c', name: 'Встреча', order: 2, optional: false },
    { id: 'd', name: 'Финиш', order: 3, optional: false }
  ],
  transitions: [
    { fromStageId: 'a', toStageId: 'b', commentRequired: false },
    { fromStageId: 'b', toStageId: 'c', commentRequired: false },
    { fromStageId: 'c', toStageId: 'd', commentRequired: false }
  ]
}

const v2: Route = {
  name: 'Маршрут',
  stages: [
    { id: 'a', name: 'Старт', order: 0, optional: false },
    { id: 'c', name: 'Встреча', order: 1, optional: true },
    { id: 'b', name: 'Согласование', order: 2, optional: false },
    { id: 'e', name: 'Подписание', order: 3, optional: false }
  ],
  transitions: [
    { fromStageId: 'a', toStageId: 'c', commentRequired: false },
    { fromStageId: 'c', toStageId: 'b', commentRequired: false },
    { fromStageId: 'b', toStageId: 'e', commentRequired: true }
  ]
}

test('сравнение версий называет добавленные, удалённые, переименованные и перемещённые этапы', () => {
  const diff = diffRoutes(v1, v2)
  assert.deepEqual(diff.added, ['Подписание'])
  assert.deepEqual(diff.removed, ['Финиш'])
  assert.deepEqual(diff.renamed, [{ from: 'Проверка', to: 'Согласование' }])
  assert.equal(diff.moved.length, 1)
  assert.deepEqual(diff.optionalChanged, ['«Встреча» — теперь необязательный'])
  assert.deepEqual(diff.transitionsRemoved, ['«Старт» → «Проверка»', '«Проверка» → «Встреча»', '«Встреча» → «Финиш»'])
  assert.equal(diff.transitionsAdded.length, 3)
  assert.equal(isEmptyDiff(diffRoutes(v1, v1)), true)
})

test('файл шаблона выгружается и загружается обратно без потерь', () => {
  const file = routeToFile(v2, 2)
  assert.deepEqual(file.stages.map((stage) => stage.key), ['a', 'c', 'b', 'e'])
  assert.deepEqual(file.transitions[2], { from: 2, to: 3, commentRequired: true })
  const parsed = parseRouteFile(JSON.stringify(file))
  assert.ok('file' in parsed)
  assert.deepEqual(parsed.file, file)
  assert.equal(routeFileName('Маршрут/команды', 2), 'Маршрут команды — версия 2.json')
})

test('неверный файл отклоняется с понятной причиной', () => {
  assert.deepEqual(parseRouteFile('не json'), { error: 'Файл не похож на шаблон этапов: это не JSON.' })
  assert.deepEqual(parseRouteFile('{"format":"other"}'), { error: 'Файл не похож на шаблон этапов: выгрузите его кнопкой «Скачать файл».' })
  const broken = { ...routeToFile(v1), transitions: [{ from: 0, to: 7, commentRequired: false }] }
  assert.deepEqual(parseRouteFile(JSON.stringify(broken)), { error: 'Переход 1 в файле ведёт в несуществующий этап.' })
  const unnamed = { ...routeToFile(v1), stages: [{ name: ' ', optional: false }] }
  assert.deepEqual(parseRouteFile(JSON.stringify(unnamed)), { error: 'Этап 1 в файле заполнен неверно.' })
})
