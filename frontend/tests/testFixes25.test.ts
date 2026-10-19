import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { navFor, sectionsFor } from '../src/app/routing.ts'
import { returnRouteAllowed } from '../src/features/interactions/drafts.ts'
import { transitionBesidePrimary, undeletableStageReason } from '../src/features/interactions/workCardModel.ts'
import { assignmentResultText } from '../src/features/organizations/organizationHistory.ts'
import { catalogGroups } from '../src/shared/catalogGroups.ts'

// Исправления по тестированию ролей и продуктовому анализу 2.5 (пакет w44).
const file = (path: string) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')
const modules = { enrolment: true, partner: false }

test('В-1: адрес приглашения не запоминается как адрес возврата после входа', () => {
  assert.equal(returnRouteAllowed('#/invite/abcdefghijklmnopqrstuvwxyz'), false)
  assert.equal(returnRouteAllowed('#/work?due=OVERDUE'), true)
  assert.equal(returnRouteAllowed('https://example.test/#/work'), false)
})

test('Н-2: смена роли КАМ ↔ руководитель и снятие флага за одно сохранение передают снятие флага', () => {
  const edit = file('frontend/src/features/admin/employeeEdit.ts')
  assert.match(edit, /enrolmentOperator !== profile\.enrolmentOperator && \(operatorAllowed\(draft\.role\) \|\| update\.role === undefined\)/)
})

test('Н-7: менеджер учебного процесса служебной команды видит только «Зачисление» и справку', () => {
  assert.deepEqual(sectionsFor('USER', true, modules, true), ['enrolment', 'help'])
  assert.deepEqual(navFor('USER', true, modules, true).map((item) => item.label), ['Зачисление'])
  // КАМ с флагом сохраняет свои разделы.
  assert.ok(sectionsFor('USER', true, modules).includes('organizations'))
  // Без модуля «Слушатели» флаг ничего не открывает, а разделы КАМ остаются.
  assert.ok(sectionsFor('USER', true, { enrolment: false, partner: false }, true).includes('work'))
  assert.match(file('frontend/src/app/App.tsx'), /navFor\(profile\.role, profile\.enrolmentOperator, modules, profile\.enrolmentOnly\)/)
})

test('З-1: текущий этап и этапы с событиями или файлами помечены «нельзя удалить»', () => {
  const events = [{ stageId: 's1', fromStageId: null, toStageId: 's2' }]
  assert.equal(undeletableStageReason('s2', 's2', events, []), 'текущий этап')
  assert.equal(undeletableStageReason('s1', 's2', events, []), 'есть события или файлы')
  assert.equal(undeletableStageReason('s4', 's2', events, [{ stageId: 's4' }]), 'есть события или файлы')
  assert.equal(undeletableStageReason('s3', 's2', events, []), null)
  assert.equal(undeletableStageReason('s3', 's2', undefined, []), null)
  assert.match(file('frontend/src/features/interactions/InteractionsPanel.tsx'), /disabled=\{blocked !== null\}/)
})

test('п. 13: на этапах документов рядом с «Создать договор» виден переход на следующий этап', () => {
  assert.equal(transitionBesidePrimary({ kind: 'createAgreement', label: 'Создать договор' }, 1), true)
  assert.equal(transitionBesidePrimary({ kind: 'createAgreement', label: 'Создать договор' }, 0), false)
  assert.equal(transitionBesidePrimary({ kind: 'transition', label: 'Перейти', disabled: false }, 2), false)
  assert.equal(transitionBesidePrimary(null, 2), false)
})

test('п. 14: «Начать новый цикл…» из меню сразу открывает форму цикла', () => {
  assert.match(file('frontend/src/features/interactions/InteractionsPanel.tsx'), /setCycleFormRequested\(true\)/)
  const cycles = file('frontend/src/features/training/InteractionCycles.tsx')
  assert.match(cycles, /if \(!formRequested \|\| state\.kind !== 'ready'\) \{/)
  assert.match(cycles, /onFormRequestHandled\?\.\(\)\n\s+if \(canEdit && !state\.cycle\.next\) \{\n\s+void openForm\(\)/)
})

test('п. 12: «Моя работа» отбирает по ИТ-программе и продукту, запрос несёт их на сервер', () => {
  const filters = file('frontend/src/features/work/workFilters.ts')
  assert.match(filters, /programId: params\.get\('program'\) \?\? ''/)
  assert.match(filters, /set\('product', filters\.productId\)/)
  assert.match(file('frontend/src/features/work/WorkList.tsx'), /programId: current\.programId \|\| undefined,\n\s+productId: current\.productId \|\| undefined,/)
  // kindId раньше в адрес запроса не попадал вовсе.
  assert.match(file('frontend/src/shared/api/client.ts'), /for \(const name of \['kindId', 'programId', 'productId'\] as const\)/)
})

test('п. 16: программы группируются по направлению, продукты по вендору, записи без родителя — в конце', () => {
  const groups = catalogGroups([
    { id: '1', groupName: 'Связь' },
    { id: '2', groupName: null },
    { id: '3', groupName: 'Аналитика' },
    { id: '4', groupName: 'Связь' }
  ], 'Без вендора')
  assert.deepEqual(groups.map((group) => [group.label, group.items.map((item) => item.id)]), [
    ['Аналитика', ['3']], ['Связь', ['1', '4']], ['Без вендора', ['2']]
  ])
  assert.match(file('frontend/src/features/interactions/InteractionsPanel.tsx'), /catalogGroups\(catalogState\.programs, 'Без направления'\)/)
})

test('п. 15: в импорте по 10 полям есть необязательный столбец «ИТ-программа»', () => {
  assert.match(file('frontend/src/features/admin/CatalogImportPanel.tsx'), /field\('programName', 'ИТ-программа', false, true/)
})

test('Н-1 (руководитель): двойная точка после даты в «Сводке команд» убрана', () => {
  const overview = file('frontend/src/features/control/TeamsOverview.tsx')
  assert.doesNotMatch(overview, /formatCalendarDate\(data\.portfolio\.to\)\}\. /)
})

test('Н-2 (руководитель): дата договора не переносится внутри числа', () => {
  assert.match(file('frontend/src/features/agreements/agreementUi.tsx'), /status--missing agreement-term">до /)
  const tokens = file('frontend/src/shared/theme/tokens.css')
  assert.match(tokens.slice(tokens.indexOf('.agreement-term {')), /^\.agreement-term \{\n\s+white-space: nowrap;\n\s+overflow-wrap: normal;/)
})

test('Н-3 (руководитель): тост после «Снять назначение» называет действие верно', () => {
  assert.equal(assignmentResultText('assignment', null), 'Назначение снято: вуз в «Требует назначения».')
  assert.equal(assignmentResultText('assignment', 'kam-1'), 'Назначение ответственного сохранено.')
  assert.equal(assignmentResultText('deputy', 'kam-1'), 'Заместитель сохранён.')
})

test('Н-5: месяцы динамики до первого потока — «данных недостаточно», а не ноль', () => {
  const dynamics = file('frontend/src/features/reports/LearningDynamics.tsx')
  assert.match(dynamics, /&& runsWithoutData === 0 \? 'данных недостаточно' : cell\(value\)/)
  assert.match(dynamics, /monthCell\(result\.totalParticipants\[index\], result\.totalRunsWithoutData\[index\]\)/)
})

test('З-4: на 320 px подпись главной кнопки карточки переносится, а не обрезается', () => {
  const css = file('frontend/src/features/interactions/workCard.css')
  const rule = css.slice(css.lastIndexOf('  .work-card__primary {'))
  assert.match(rule.slice(0, rule.indexOf('}')), /white-space: normal;/)
})

test('Н-14: таблица слушателей потока на телефоне прокручивается, слова не рвутся по слогам', () => {
  const css = file('frontend/src/features/enrolment/enrolment.css')
  assert.match(css, /table\[aria-label="Слушатели потока"\] \{\n\s+min-width: 760px;/)
  assert.match(css, /table\[aria-label="Слушатели потока"\] :is\(th, td\) \{\n\s+hyphens: manual;/)
})

test('Н-11: протокол оплат при переносе называет кнопку «Перенести в Moodle»', () => {
  assert.match(file('frontend/src/features/enrolment/PaidOrdersUpload.tsx'), /кнопкой «Перенести в Moodle» в очереди «Что обработать»/)
})
