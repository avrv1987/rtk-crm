import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import type { WorkControl, WorkControlItem } from '../src/shared/api/client.ts'
import { draftProblem, draftProblems, emptyStage, movedStage, storedEditorDraft, type TemplateDraft } from '../src/features/admin/workflowTemplateDraft.ts'
import { refreshedRolloutSelection } from '../src/features/admin/workflowVersions.ts'
import { invalidQueryDates } from '../src/features/control/controlModel.ts'
import { stageMatrix } from '../src/features/control/cycleModel.ts'
import { sameConditions } from '../src/features/interactions/interactionKinds.ts'
import { forwardCommentReason } from '../src/features/interactions/workCardModel.ts'
import { organizationLoadFailure } from '../src/features/organizations/organizationFilters.ts'

// Последние исправления перед выпуском 2.5 (пакет w48).
const file = (path: string) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')

const stage = (name: string) => ({ ...emptyStage(), name })
const linear = (names: string[]): TemplateDraft => ({
  name: 'Маршрут',
  comment: '',
  stages: names.map(stage),
  transitions: names.slice(1).map((_, index) => ({ fromOrder: index, toOrder: index + 1, commentRequired: false }))
})

test('п. 2: правила выбора вида берут программы и продукты из справочника администратора', () => {
  const panel = file('frontend/src/features/admin/InteractionKindsPanel.tsx')
  assert.match(panel, /listAdminCatalogEntries\('programs', \{ page: 0, size: 100, state: 'ACTIVE' \}\)/)
  assert.match(panel, /listAdminCatalogEntries\('products', \{ page: 0, size: 100, state: 'ACTIVE' \}\)/)
  assert.doesNotMatch(panel, /apiClient\.listPrograms\(/)
})

test('п. 4: черновик редактора шаблона хранится и восстанавливается, мусор из хранилища отбрасывается', () => {
  const draft = linear(['А', 'Б'])
  const stored = storedEditorDraft({ template: { id: 't1', version: 3 }, source: null, draft, initialDraft: linear(['А']) })
  assert.equal(stored?.template?.id, 't1')
  assert.deepEqual(stored?.draft.stages.map((item) => item.name), ['А', 'Б'])
  assert.equal(storedEditorDraft({ template: { id: 1 }, source: null, draft, initialDraft: draft }), null)
  assert.equal(storedEditorDraft('строка'), null)
  assert.equal(storedEditorDraft({ template: null, source: null, draft: { name: 'x' }, initialDraft: draft }), null)
  const panel = file('frontend/src/features/admin/WorkflowTemplatesPanel.tsx')
  assert.match(panel, /saveFormDraft\(profileId, editorDraftKey, editorOpen && draftChanged\(initialDraft, draft\)/)
  assert.match(panel, /storedEditorDraft\(loadFormDraft\(profileId, editorDraftKey\)\)/)
})

test('п. 6: «Выше» и «Ниже» пересобирают переходы по новому порядку — шаблон остаётся сохраняемым', () => {
  const base = linear(['Поиск', 'Уточнение', 'Встреча', 'Договор', 'Финал'])
  const up = movedStage(base, 3, 2)
  assert.deepEqual(up.stages.map((item) => item.name), ['Поиск', 'Уточнение', 'Договор', 'Встреча', 'Финал'])
  assert.equal(draftProblem(up), null)
  assert.deepEqual(up.transitions.map((item) => `${item.fromOrder}>${item.toOrder}`).sort(), ['0>1', '1>2', '2>3', '3>4'])
  for (const [from, to] of [[0, 1], [1, 0], [4, 3], [3, 4], [2, 1]]) {
    assert.equal(draftProblem(movedStage(base, from, to)), null, `перемещение ${from} → ${to}`)
  }
  // Возврат с комментарием сохраняет признак и после перемещения.
  const withBack = { ...base, transitions: [...base.transitions, { fromOrder: 2, toOrder: 1, commentRequired: true }] }
  assert.equal(draftProblem(movedStage(withBack, 2, 3)), null)
})

test('п. 6: все ошибки шаблона показываются сразу', () => {
  const broken: TemplateDraft = { name: '', comment: '', stages: [stage('А'), stage(''), stage('А')], transitions: [] }
  const problems = draftProblems(broken)
  assert.ok(problems.length >= 4, problems.join(' | '))
  assert.ok(problems.some((item) => item.includes('название шаблона')))
  assert.ok(problems.some((item) => item.includes('этапа 2')))
  assert.ok(problems.some((item) => item.includes('не должны повторяться')))
  assert.ok(problems.some((item) => item.includes('нет выхода')))
  assert.match(file('frontend/src/features/admin/WorkflowTemplatesPanel.tsx'), /problems\.map\(\(item\) => <li key=\{item\}>\{item\}<\/li>\)/)
})

test('п. 7: у завершённой карточки нет «Шаг выполнен», правки шага; 409 показывает текст сервера', () => {
  const panel = file('frontend/src/features/interactions/InteractionsPanel.tsx')
  assert.match(panel, /canManageDailyWork && !completingStep && !completed && \(/)
  assert.match(panel, /\.\.\.\(completed \? \[\] : \[\{ label: 'Изменить шаг и срок…'/)
  assert.match(file('frontend/src/features/work/workShared.ts'), /error\.message\.trim\(\)\.length > 0\s+\? `Не удалось \$\{action\}: \$\{error\.message\}`/)
})

test('п. 8: на 320 px кнопка перехода уходит в «⋯», подпись кнопки договора не рвётся по буквам', () => {
  const css = file('frontend/src/features/interactions/workCard.css')
  assert.match(css, /@media \(max-width: 360px\) \{\s+\.work-card__transition \{\s+display: none;/)
  assert.match(css, /\.work-facts__action \{[^}]*overflow-wrap: normal;/)
})

test('п. 9: ближайший этап при нехватке документов — не «пропуск этапа»', () => {
  const stages = [{ id: 'a', order: 0 }, { id: 'b', order: 1 }, { id: 'c', order: 2 }]
  assert.equal(forwardCommentReason(stages, 'a', 'b', true, true), 'documents')
  assert.equal(forwardCommentReason(stages, 'a', 'c', true, true), 'skip')
  assert.equal(forwardCommentReason(stages, 'a', 'b', true, false), 'route')
  assert.equal(forwardCommentReason(stages, 'a', 'b', false, true), null)
})

test('п. 10: неверный адрес вуза и договора объясняются, «Повторить» только при сбое', () => {
  assert.equal(organizationLoadFailure(400).retry, false)
  assert.equal(organizationLoadFailure(404).retry, false)
  assert.equal(organizationLoadFailure(503).retry, true)
  assert.doesNotMatch(organizationLoadFailure(400).text, /передан другому КАМ/)
  assert.match(file('frontend/src/app/App.tsx'), /unknownAddress=\{route\.organizationId !== undefined && route\.organizationId\.length > 0\}/)
  // Техническое имя поля без подписи не выводится (префикс «priority:»).
  assert.match(file('frontend/src/features/agreements/agreementUi.tsx'), /fieldLabels\[field\] === undefined \? text :/)
})

test('п. 11: этап прежней версии в матрице помечен и не нумеруется после финала', () => {
  const item = { interactionId: 'w1', organizationId: 'o1', organizationName: 'o1', title: 'w1', products: [], productIds: [], stage: 'Б середина',
    stagePosition: 2, stageTotal: 3, daysOnStage: 1, due: 'IN_ORDER', status: 'ACTIVE', problems: 0, risks: 0, transitions: 0, created: false,
    completed: false, stageUnderApproval: false, nextActionAt: null, routeId: 'r', kindId: null, programId: null, program: null } as unknown as WorkControlItem
  const totals = { organizations: 0, works: 0, overdue: 0, thisWeek: 0, noNextStep: 0, inOrder: 0, withIssues: 0, moved: 0, created: 0, completed: 0, idle: 0 }
  const control = {
    calculatedAt: '2026-10-19T09:00:00Z', byTeams: false, total: totals,
    teams: [{ teamId: 'a', teamName: 'А', totals, managers: [{ managerId: 'm', managerName: 'm', totals, works: [item] }] }],
    routes: [{ templateId: 'r', templateName: 'Маршрут', stages: ['А начало', 'В работа', 'Г финал', 'Б середина'], formerStages: ['Б середина'] }]
  } as unknown as WorkControl
  const [route] = stageMatrix(control, 'PROGRAM', 30)
  assert.deepEqual(route.former, [false, false, false, true])
  assert.match(file('frontend/src/features/control/ControlCycleViews.tsx'), /\(прежний этап\)/)
})

test('п. 12: ручной выбор финала снимает отметку работы в мастере', () => {
  const before = [{ id: 'w1', noExit: false, landsOnFinal: false }, { id: 'w2', noExit: false, landsOnFinal: false }]
  const after = [{ id: 'w1', noExit: false, landsOnFinal: true }, { id: 'w2', noExit: false, landsOnFinal: false }]
  assert.deepEqual([...refreshedRolloutSelection(new Set(['w1', 'w2']), before, after)], ['w2'])
  // Отмеченную сознательно работу, которая и раньше вставала на финал, повторный предпросмотр не снимает.
  assert.deepEqual([...refreshedRolloutSelection(new Set(['w1']), after, after)], ['w1'])
})

test('п. 13: тексты замещения, почты, правил и протокола оплат', () => {
  assert.match(file('frontend/src/features/organizations/OrganizationDeputyPanel.tsx'), /'Отменить запланированное замещение\?'/)
  const dialog = file('frontend/src/features/admin/EmployeeAccountDialog.tsx')
  assert.match(dialog, /apiClient\.getMailSettings\(\)/)
  assert.match(dialog, /sendsLinks === 'SMTP'/)
  assert.equal(sameConditions({ organizationType: null, programId: 'p', productId: null, priority: 1 },
    { organizationType: undefined, programId: 'p', productId: null, priority: 5 } as never), true)
  assert.equal(sameConditions({ organizationType: null, programId: 'p', productId: null, priority: 1 },
    { organizationType: null, programId: 'p', productId: 'x', priority: 1 }), false)
  const upload = file('frontend/src/features/enrolment/PaidOrdersUpload.tsx')
  assert.match(upload, /!issue\.message\.includes\('запись принята'\)/)
  assert.match(upload, /Повторов заявки в файле/)
  assert.match(file('backend/src/main/resources/static/openapi.yaml'), /в предыдущий сохранившийся этап, а если его нет — в следующий/)
})

test('п. 16: несуществующая дата в адресе «Контроля» не отбрасывается молча', () => {
  assert.deepEqual(invalidQueryDates('from=2026-02-30&to=2026-03-10'), [{ name: 'from', value: '2026-02-30' }])
  assert.deepEqual(invalidQueryDates('from=2026-02-28'), [])
  assert.match(file('frontend/src/features/control/ControlScreen.tsx'), /не распознана или такой даты нет/)
})

test('п. 17: разделы без места в нижней панели доступны через «Ещё»', () => {
  const app = file('frontend/src/app/App.tsx')
  assert.match(app, /nav\.some\(\(item\) => !item\.phone\) && \(\s+<li className="app-tabbar__more">/)
})

test('п. 18: версия приложения 2.5.0', () => {
  assert.equal(JSON.parse(file('frontend/package.json')).version, '2.5.0')
  const lock = JSON.parse(file('frontend/package-lock.json'))
  assert.equal(lock.version, '2.5.0')
  assert.equal(lock.packages[''].version, '2.5.0')
})

test('«Контроль»: колонка сетки ограничена шириной экрана — нет горизонтальной прокрутки на ширине около 760 px', () => {
  const css = readFileSync(new URL('../src/features/control/control.css', import.meta.url), 'utf8')
  const rule = css.slice(css.indexOf('.control {'), css.indexOf('}', css.indexOf('.control {')))
  assert.match(rule, /grid-template-columns: minmax\(0, 1fr\)/)
})

test('«Контроль»: вложенные сетки и таблица менеджера не раздвигают страницу на узком экране', () => {
  const css = readFileSync(new URL('../src/features/control/control.css', import.meta.url), 'utf8')
  assert.match(css, /\.control-matrix,\s*\.control-team \{\s*grid-template-columns: minmax\(0, 1fr\)/)
  const manager = css.slice(css.indexOf('.control-manager {'), css.indexOf('}', css.indexOf('.control-manager {')))
  assert.match(manager, /overflow-x: auto/)
})
