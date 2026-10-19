import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import {
  documentsCommentExplained,
  missingDocumentsText,
  selectableDocumentKinds,
  stagePropertiesOf,
  stagePropertiesSummary,
  transitionCommentWithDocuments
} from '../src/features/documents/stageProperties.ts'
import { draftChanged, draftProblem, emptyDraft, payloadFromDraft, type TemplateDraft } from '../src/features/admin/workflowTemplateDraft.ts'
import { defaultRolloutSelection, diffRoutes, parseRouteFile, routeToFile, type Route } from '../src/features/admin/workflowVersions.ts'

const checklist = [
  { code: 'CONTRACT', name: 'Договор', attached: true },
  { code: 'ACT', name: 'Акт', attached: false },
  { code: 'SIGNED_SCAN', name: 'Скан подписанного документа', attached: false }
]

test('мягкие документы: перечень недостающего подставляется в начало комментария перехода вперёд', () => {
  assert.equal(missingDocumentsText(checklist), 'Не хватает документов: Акт, Скан подписанного документа.')
  assert.equal(missingDocumentsText([{ code: 'CONTRACT', name: 'Договор', attached: true }]), null)
  assert.equal(transitionCommentWithDocuments('', checklist, true), 'Не хватает документов: Акт, Скан подписанного документа. ')
  assert.equal(transitionCommentWithDocuments('Вуз пришлёт позже', checklist, true),
    'Не хватает документов: Акт, Скан подписанного документа. Вуз пришлёт позже')
  const prefilled = transitionCommentWithDocuments('', checklist, true)
  assert.equal(transitionCommentWithDocuments(prefilled, checklist, true), prefilled, 'повторно перечень не добавляется')
  assert.equal(transitionCommentWithDocuments('Назад', checklist, false), 'Назад', 'возврат назад не требует документов')
  assert.equal(documentsCommentExplained(prefilled, checklist), false, 'один перечень без объяснения — не комментарий')
  assert.equal(documentsCommentExplained(`${prefilled}Скан привезут на встречу`, checklist), true)
})

test('виды документов: архивный вид не выбирается для нового документа, но остаётся у текущего', () => {
  const kinds = [
    { code: 'CONTRACT', name: 'Договор', archived: false },
    { code: 'OLD', name: 'Старый вид', archived: true }
  ]
  assert.deepEqual(selectableDocumentKinds(kinds).map((kind) => kind.code), ['CONTRACT'])
  assert.deepEqual(selectableDocumentKinds(kinds, 'OLD').map((kind) => kind.code), ['CONTRACT', 'OLD'])
})

test('свойства этапа читаются из старых ответов без полей и кратко подписываются', () => {
  assert.deepEqual(stagePropertiesOf({}), { requiresAgreement: false, lmsRole: null, documentKinds: [] })
  assert.equal(stagePropertiesSummary({ requiresAgreement: true, lmsRole: 'CLASSES', documentKinds: ['ACT', 'CONTRACT'] }),
    'нужен действующий договор · LMS: Идут занятия · обязательных документов: 2')
})

const draft = (names: string[], transitions: TemplateDraft['transitions']): TemplateDraft => ({
  ...emptyDraft(),
  name: 'Маршрут',
  stages: names.map((name) => ({ name, optional: false, requiresAgreement: false, lmsRole: null, documentKinds: [] })),
  transitions
})

test('редактор шаблона: цикл без выхода к финалу и одинаковые названия этапов не сохраняются', () => {
  const trap = draft(['А', 'Б', 'В', 'Г'], [
    { fromOrder: 0, toOrder: 1, commentRequired: false },
    { fromOrder: 1, toOrder: 2, commentRequired: false },
    { fromOrder: 2, toOrder: 1, commentRequired: false },
    { fromOrder: 0, toOrder: 3, commentRequired: true }
  ])
  assert.match(draftProblem(trap) ?? '', /С этапов «Б», «В» нельзя дойти до финального этапа «Г»/)
  const fixed = { ...trap, transitions: [...trap.transitions, { fromOrder: 2, toOrder: 3, commentRequired: false }] }
  assert.equal(draftProblem(fixed), null)
  const duplicates = draft(['Встреча', ' встреча '], [{ fromOrder: 0, toOrder: 1, commentRequired: false }])
  assert.match(draftProblem(duplicates) ?? '', /Названия этапов не должны повторяться/)
})

test('редактор шаблона: свойства этапа уходят в запрос, изменённый черновик требует подтверждения закрытия', () => {
  const base = draft(['Подписание', 'Занятия'], [{ fromOrder: 0, toOrder: 1, commentRequired: false }])
  const marked: TemplateDraft = {
    ...base,
    stages: [
      { ...base.stages[0], requiresAgreement: true, documentKinds: ['SIGNED_SCAN'] },
      { ...base.stages[1], lmsRole: 'CLASSES' }
    ]
  }
  const payload = payloadFromDraft(marked)
  assert.equal(payload.stages[0].requiresAgreement, true)
  assert.deepEqual(payload.stages[0].documentKinds, ['SIGNED_SCAN'])
  assert.equal(payload.stages[1].lmsRole, 'CLASSES')
  assert.equal(draftChanged(base, base), false)
  assert.equal(draftChanged(base, marked), true)
})

const route: Route = {
  name: 'Маршрут',
  stages: [
    { id: 'a', name: 'Подписание', order: 0, optional: false, requiresAgreement: true, lmsRole: null, documentKinds: ['SIGNED_SCAN'] },
    { id: 'b', name: 'Занятия', order: 1, optional: false, requiresAgreement: false, lmsRole: 'CLASSES', documentKinds: [] }
  ],
  transitions: [{ fromStageId: 'a', toStageId: 'b', commentRequired: false }]
}

test('файл маршрута: свойства этапа переносятся, старый файл без них читается, формат не меняется', () => {
  const file = routeToFile(route)
  assert.equal(file.formatVersion, 1)
  assert.deepEqual(file.stages[0], { key: 'a', name: 'Подписание', optional: false, requiresAgreement: true, documentKinds: ['SIGNED_SCAN'] })
  assert.deepEqual(file.stages[1], { key: 'b', name: 'Занятия', optional: false, lmsRole: 'CLASSES' })
  const parsed = parseRouteFile(JSON.stringify(file))
  assert.ok('file' in parsed)
  assert.deepEqual(parsed.file.stages, file.stages)
  const legacy = parseRouteFile(JSON.stringify({
    format: 'rtk-crm-workflow', formatVersion: 1, name: 'Старый', stages: [{ name: 'Один', optional: false }], transitions: []
  }))
  assert.ok('file' in legacy)
  assert.deepEqual(legacy.file.stages, [{ name: 'Один', optional: false }])
  const broken = parseRouteFile(JSON.stringify({ ...file, stages: [{ name: 'Один', optional: false, lmsRole: 'LESSONS' }] }))
  assert.ok('error' in broken)
})

test('сравнение версий показывает изменение свойств этапа', () => {
  const next: Route = { ...route, stages: [{ ...route.stages[0], requiresAgreement: false }, { ...route.stages[1], lmsRole: null }] }
  assert.deepEqual(diffRoutes(route, next).propertiesChanged, [
    '«Подписание» — договор больше не нужен',
    '«Занятия» — роль для LMS: без связи с LMS'
  ])
})

test('мастер версий не отмечает по умолчанию работы без выхода к финалу и попадающие на финал', () => {
  const works = [
    { id: '1', edited: false, noExit: false, landsOnFinal: false },
    { id: '2', edited: false, noExit: true, landsOnFinal: false },
    { id: '3', edited: false, noExit: false, landsOnFinal: true },
    { id: '4', edited: true, noExit: false, landsOnFinal: false }
  ]
  assert.deepEqual([...defaultRolloutSelection(works)], ['1'])
})

const source = (path: string) => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')

test('редактор шаблона: изменённый черновик закрывается только с подтверждением, конфликт загружает текущую версию', () => {
  const panel = source('features/admin/WorkflowTemplatesPanel.tsx')
  assert.match(panel, /if \(!force && draftChanged\(initialDraft, draft\)\) \{\n\s+setConfirmClose\(true\)/)
  assert.match(panel, /title="Закрыть без сохранения\?"/)
  assert.match(panel, /onClose=\{\(\) => closeEditor\(\)\}/, 'Escape и «Закрыть» идут через ту же проверку')
  assert.match(panel, /const fresh = draftFromTemplate\(template\)\n\s+setDraft\(fresh\)/, 'после конфликта черновик — текущая версия')
})

test('карточка: чек-лист документов, подстановка перечня в переход и свойства нового этапа', () => {
  const panel = source('features/interactions/InteractionsPanel.tsx')
  assert.match(panel, /transitionCommentWithDocuments\(transitionDraft, currentInteraction\.documentChecklist, true\)/)
  assert.match(panel, /aria-label="Обязательные документы текущего этапа"/)
  assert.match(panel, /requiresAgreement: stageEditRequiresAgreement,/)
  assert.match(source('features/control/ControlMatrix.tsx'), /item\.missingDocuments && <span[^>]*>Нет документов этапа/)
  assert.match(source('features/work/WorkList.tsx'), /item\.missingDocuments && <span[^>]*>Нет документов этапа/)
})
