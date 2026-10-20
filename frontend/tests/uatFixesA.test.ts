import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { ApiError, clearConflict } from '../src/shared/api/client.ts'
import { documentTitle, redirectNotice } from '../src/app/routing.ts'
import { mergedValues } from '../src/features/interactions/draftBase.ts'
import { clearDraftsForProfile, saveFormDraft, storedFormDraftLabels } from '../src/features/interactions/drafts.ts'
import { nextRecordDraft, parseRecordDraft, valuesLike } from '../src/features/interactions/formDraft.ts'
import {
  clearUnsavedDrafts,
  currentUnsavedEntries,
  markUnsavedDraft,
  sessionExpiredNotice
} from '../src/features/interactions/unsavedDrafts.ts'
import { partnerRequiredCount, partnerReviewLists } from '../src/features/partner/partnerRequestModel.ts'
import { peopleFields } from '../src/features/people/peopleForms.ts'
import { watchSessionElsewhere } from '../src/features/session/sessionSync.ts'
import { withCount } from '../src/shared/format/plural.ts'

const source = (path: string) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')

const apiError = (status: number, code: string) => new ApiError({ code, message: 'Договор уже изменили', requestId: 'r-1' }, status)

class MemoryStorage {
  private readonly items = new Map<string, string>()
  get length() {
    return this.items.size
  }
  key(index: number) {
    return [...this.items.keys()][index] ?? null
  }
  getItem(key: string) {
    return this.items.get(key) ?? null
  }
  setItem(key: string, value: string) {
    this.items.set(key, value)
  }
  removeItem(key: string) {
    this.items.delete(key)
  }
}

test('Н-А1: у форм договора при 409 есть «Обновить карточку», договор перечитывается на месте', () => {
  const ui = source('src/features/agreements/agreementUi.tsx')
  assert.match(ui, /onReload && isConflict\(error\) && <button type="button" onClick=\{onReload\}>Обновить карточку<\/button>/)
  assert.match(ui, /Нажмите «Обновить карточку», чтобы увидеть текущие данные; введённое в форме не потеряется\./)
  const panel = source('src/features/agreements/AgreementsPanel.tsx')
  // Перечитывание не уводит карточку в «Загружаем…», иначе окно с введённым текстом закрылось бы.
  assert.match(panel, /setState\(\(current\) => \(current\.kind === 'ready' \? current : \{ kind: 'loading' \}\)\)\n    try \{\n      setState\(\{ kind: 'ready', value: await agreementsApi\.get\(agreementId\) \}\)/)
  assert.ok((panel.match(/onReload=\{refresh\}/g) ?? []).length >= 4)
  assert.match(panel, /activity=\{editedActivity\(editor, agreement\.activities\)\}/)
  for (const file of ['AgreementForm.tsx', 'AgreementSupplements.tsx', 'AgreementTransitions.tsx', 'ActivityListsPanel.tsx']) {
    assert.match(source(`src/features/agreements/${file}`), /<CommandError[^>]*onReload=/, file)
  }
  assert.match(source('src/features/agreements/WorkAgreementBlock.tsx'), /onReload=\{changed\}/)
})

test('Н-А13: «Обновить карточку» снимает плашку конфликта, другие ошибки остаются', () => {
  const conflict = { kind: 'failed' as const, error: apiError(409, 'VERSION_CONFLICT') }
  const invalid = { kind: 'failed' as const, error: apiError(400, 'VALIDATION_ERROR') }
  assert.deepEqual(clearConflict(conflict), { kind: 'idle' })
  assert.equal(clearConflict(invalid), invalid)
  assert.deepEqual(clearConflict({ kind: 'saving' }), { kind: 'saving' })
  const panel = source('src/features/interactions/InteractionsPanel.tsx')
  for (const setter of ['setPlanState', 'setCommentState', 'setTransitionState', 'setStageEditState', 'setUploadState']) {
    assert.match(panel, new RegExp(`${setter}\\(clearConflict\\)`), setter)
  }
})

test('Н-А2: черновик записи — только тронутые поля, чужое изменение не затирается, мусор из хранилища отброшен', () => {
  const current = { parties: 'ООО', sentOn: '2026-10-01', ids: ['a'], signed: false }
  const edited = nextRecordDraft(null, current, { parties: 'ООО и вуз' })
  assert.ok(edited !== null)
  assert.equal(nextRecordDraft(edited, current, { parties: 'ООО' }), null)
  // КАМ тем временем сменил дату отправки: после «Обновить карточку» она берётся из записи, «Стороны» — из черновика.
  const fresh = { ...current, sentOn: '2026-10-20' }
  assert.deepEqual(mergedValues(edited, fresh), { parties: 'ООО и вуз', sentOn: '2026-10-20', ids: ['a'], signed: false })
  assert.deepEqual(valuesLike(current, { parties: 5, sentOn: '2026-10-02', ids: ['b', 7], signed: true, extra: 'x' }),
    { parties: 'ООО', sentOn: '2026-10-02', ids: ['a'], signed: true })
  assert.equal(parseRecordDraft('мусор', current), null)
  assert.deepEqual(parseRecordDraft({ label: 'x', base: current, values: { ...current, parties: 'Новое' } }, current)?.values.parties, 'Новое')
})

test('Н-А2: черновики форм переживают F5 и перечисляются после входа, при выходе удаляются', () => {
  const storage = new MemoryStorage()
  Object.assign(globalThis, { window: { sessionStorage: storage } })
  try {
    saveFormDraft('p-1', 'record:activity:new:a-1', { label: 'новое мероприятие плана договора', base: {}, values: {} })
    saveFormDraft('p-1', 'card:i-1:details', { base: {}, values: {} })
    saveFormDraft('p-2', 'record:agreement:a-2', { label: 'чужой', base: {}, values: {} })
    assert.deepEqual(storedFormDraftLabels('p-1'), [{ key: 'record:activity:new:a-1', label: 'новое мероприятие плана договора' }])
    clearDraftsForProfile('p-1')
    assert.deepEqual(storedFormDraftLabels('p-1'), [])
    assert.equal(storedFormDraftLabels('p-2').length, 1)
  } finally {
    Reflect.deleteProperty(globalThis, 'window')
  }
})

test('Н-А2: плашка истёкшего сеанса обещает возврат только формам с черновиком', () => {
  clearUnsavedDrafts()
  assert.equal(sessionExpiredNotice(currentUnsavedEntries()), 'Сеанс истёк. Войдите снова — вы вернётесь на тот же экран.')
  markUnsavedDraft('form:record:activity:new:a', 'новое мероприятие плана договора', true)
  markUnsavedDraft('learner:l:CONTACTS', 'группа «Контакты» анкеты слушателя', true, false)
  assert.equal(sessionExpiredNotice(currentUnsavedEntries()),
    'Сеанс истёк. Войдите снова — вы вернётесь на тот же экран. Несохранённый текст вернётся в форму: новое мероприятие плана договора.'
    + ' Не сохранится, введите заново: группа «Контакты» анкеты слушателя.')
  clearUnsavedDrafts()
  const app = source('src/app/App.tsx')
  assert.match(app, /setSessionNotice\(\(current\) => current \?\? sessionExpiredNotice\(currentUnsavedEntries\(\)\)\)/)
  assert.doesNotMatch(app, /несохранённый текст восстановится/)
  assert.match(app, /storedFormDraftLabels\(profile\.id\)\.forEach/)
})

test('Н-А2: формы договора, плана, перечня, «Людей и часов», МУП и кабинета вуза пишут черновик', () => {
  const files = [
    'agreements/AgreementForm.tsx', 'agreements/AgreementsPanel.tsx', 'agreements/AgreementSupplements.tsx',
    'agreements/AgreementTransitions.tsx', 'agreements/ActivityListsPanel.tsx', 'people/PeoplePanel.tsx',
    'enrolment/EnrolmentBatchView.tsx', 'partner/PartnerRequestCard.tsx', 'partner/PartnerActivityLists.tsx'
  ]
  for (const file of files) {
    assert.match(source(`src/features/${file}`), /useRecordDraft/, file)
  }
  // Анкета слушателя — персональные данные: черновика нет, но плашка знает, что текст не вернётся.
  assert.match(source('src/features/enrolment/LearnerCardView.tsx'), /useUnsavedDraft\(`learner:\$\{learnerId\}:\$\{config\.group\}`, .*, dirty, true\)/)
})

test('Н-А3: перечень «На согласовании» входит в «Требуется от вуза» и в счётчик перечней', () => {
  const lists = [{ summary: { status: 'REVIEW' } }, { summary: { status: 'APPROVED' } }]
  assert.equal(partnerReviewLists(lists).length, 1)
  assert.equal(partnerRequiredCount(0, lists), 1)
  assert.equal(partnerRequiredCount(2, null), 2)
  const cabinet = source('src/features/partner/PartnerCabinet.tsx')
  assert.match(cabinet, /Требуется от вуза \(\{partnerRequiredCount\(groups\.required\.length, lists\)\}\)/)
  assert.match(cabinet, /Перечни мероприятий\{lists === null \? '' : ` \(\$\{lists\.length\}\)`\}/)
  assert.match(cabinet, /groups\.required\.length === 0 && reviewLists\.length === 0 && <p>Сейчас школа ничего не ждёт от вуза\.<\/p>/)
})

test('Н-А4: конфликт правки перечня — перечитать статус, черновик неприменимой правки стирается', () => {
  const panel = source('src/features/agreements/ActivityListsPanel.tsx')
  assert.match(panel, /discardRecordDraft\(`activity-list:\$\{listId\}`\)/)
  assert.match(panel, /const reloadAfterConflict = \(\) => \{\n    setEditing\(false\)/)
  const service = source('../backend/src/main/java/ru/rtk/crm/agreement/ActivityListService.java')
  assert.match(service, /throw AgreementException\.listNotEditable\(current\.version\(\), current\.status\(\)\)/)
})

test('Н-А5 и Н-А6: счётчик заявок и очередь МУП обновляются без перезагрузки', () => {
  assert.match(source('src/features/admin/AdminScreen.tsx'), /<PendingSourceRecords role=\{profile\.role\} onCount=\{setPendingRecords\}/)
  assert.match(source('src/features/sources/PendingSourceRecords.tsx'), /onCount\?\.\(records\.length\)/)
  const list = source('src/features/enrolment/EnrolmentStreamsList.tsx')
  assert.match(list, /<EnrolmentQueuePanel onAccessError=\{onAccessError\} refreshSignal=\{queueRevision\} \/>/)
  assert.match(list, /onUploaded=\{\(\) => \{ void load\(\); setQueueRevision\(\(value\) => value \+ 1\) \}\}/)
  assert.match(source('src/features/enrolment/EnrolmentQueuePanel.tsx'), /\}, \[load, refreshSignal\]\)/)
})

test('Н-А7: выход в другой вкладке отличается от входа другого сотрудника', () => {
  const listeners: ((event: { key: string; newValue: string | null }) => void)[] = []
  Object.assign(globalThis, {
    window: {
      addEventListener: (_type: string, listener: (event: { key: string; newValue: string | null }) => void) => listeners.push(listener),
      removeEventListener: () => undefined
    }
  })
  try {
    const calls: boolean[] = []
    const stop = watchSessionElsewhere('p-1', (signedOut) => calls.push(signedOut))
    listeners.forEach((listener) => listener({ key: 'rtk-crm:session-profile', newValue: '' }))
    listeners.forEach((listener) => listener({ key: 'rtk-crm:session-profile', newValue: 'p-2' }))
    listeners.forEach((listener) => listener({ key: 'rtk-crm:session-profile', newValue: 'p-1' }))
    stop()
    assert.deepEqual(calls, [true, false])
  } finally {
    Reflect.deleteProperty(globalThis, 'window')
  }
  assert.match(source('src/app/App.tsx'), /Вы вышли из CRM в другой вкладке\. Войдите снова — откроется этот же экран\./)
})

test('Н-А8: «Перейти к содержанию», заголовок вкладки по экрану, фокус после закрытия окна', () => {
  assert.equal(documentTitle('Контроль'), 'Контроль — CRM ИТ Школы РТК')
  assert.equal(documentTitle(null), 'CRM ИТ Школы РТК')
  const app = source('src/app/App.tsx')
  assert.match(app, /className="skip-link"/)
  assert.match(app, /<main className="app-main" id="app-main" tabIndex=\{-1\}>/)
  assert.match(app, /document\.title = documentTitle\(screenTitle\)/)
  assert.match(source('src/features/interactions/cardUi.tsx'), /useFocusReturn\(open\)/)
  assert.match(source('src/shared/ui/ConfirmDialog.tsx'), /useFocusReturn\(open\)/)
})

test('Н-А9: ссылка на раздел другой роли объясняет, почему открыт свой раздел', () => {
  assert.equal(redirectNotice('admin', 'work', 'USER'), 'Раздел «Администрирование» доступен другой роли — открыт ваш раздел «Моя работа».')
  assert.equal(redirectNotice('organizations', 'partner', 'PARTNER'),
    'Раздел «Вузы» доступен другой роли — открыт ваш раздел «Сотрудничество с ИТ Школой РТК».')
  assert.equal(redirectNotice('nonexistent', 'control', 'LEADER'), 'Такого адреса в CRM нет — открыт ваш раздел «Контроль».')
  assert.equal(redirectNotice('constructor', 'admin', 'ADMIN'), 'Такого адреса в CRM нет — открыт ваш раздел «Администрирование».')
})

test('Н-А11: у стажировки договор определяется мероприятием плана, справка это и говорит', () => {
  const keys = peopleFields.internships.map((field) => field.key)
  assert.ok(keys.includes('agreementActivityId'))
  assert.ok(!keys.includes('agreementId'))
  // Публичная копия инструкций: она есть и в открытом репозитории, где нет docs/delivery.
  const guide = source('../docs/public/Инструкции_пользователей.md')
  assert.match(guide, /«Стажировки»: «Взаимодействие», «Мероприятие плана договора» \(договор определяется мероприятием\)/)
  assert.doesNotMatch(guide, /Можно выбрать договор и мероприятие плана\./)
})

test('Н-А12: числа в отчётах склоняются', () => {
  assert.equal(withCount(1, 'запись', 'записи', 'записей'), '1 запись')
  assert.equal(withCount(2, 'мероприятие', 'мероприятия', 'мероприятий'), '2 мероприятия')
  const agreements = source('src/features/reports/AgreementReportingReport.tsx')
  assert.match(agreements, /pluralForm\(state\.result\.totals\.organizations, 'вуз', 'вуза', 'вузов'\)/)
  assert.match(agreements, /pluralForm\(state\.result\.totals\.withoutRate, 'мероприятие', 'мероприятия', 'мероприятий'\)/)
  const people = source('src/features/reports/PeopleReportPage.tsx')
  assert.match(people, /pluralForm\(teaching\.records, 'запись', 'записи', 'записей'\)/)
  assert.match(people, /pluralForm\(internships\.interns, 'стажёр', 'стажёра', 'стажёров'\)/)
  assert.match(source('../backend/src/main/java/ru/rtk/crm/agreement/AgreementReportService.java'),
    /"Без ставки: " \+ Plural\.withCount\(withoutRate, "мероприятие", "мероприятия", "мероприятий"\)/)
})
