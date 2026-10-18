import assert from 'node:assert/strict'
import { test } from 'node:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import { canonicalAddress, parseRoute } from '../src/app/routing.ts'
import { canonicalAdminHash } from '../src/features/admin/adminSections.ts'
import { clearInteractionDraft, loadInteractionDraft, saveInteractionDraft } from '../src/features/interactions/drafts.ts'
import { currentUnsavedLabels, forgetUnsavedDraft, markUnsavedDraft } from '../src/features/interactions/unsavedDrafts.ts'
import { duplicateQuery } from '../src/features/organizations/organizationDuplicates.ts'
import { filtersFromQuery, queryFromFilters } from '../src/features/organizations/organizationFilters.ts'
import { emailHref, phoneHref } from '../src/shared/format/links.ts'
import { withCount } from '../src/shared/format/plural.ts'
import { ErrorBoundary, ErrorFallback } from '../src/shared/ui/ErrorBoundary.ts'

const memoryStorage = () => {
  const data = new Map<string, string>()
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    removeItem: (key: string) => void data.delete(key),
    size: () => data.size
  }
}

test('после создания взаимодействия черновик формы удаляется и не подставляется повторно', () => {
  const storage = memoryStorage()
  Object.assign(globalThis, { window: { sessionStorage: storage } })
  const filled = { ...loadInteractionDraft('p1', 'o1'), createTitle: 'Внедрение', createNextAction: 'Позвонить' }
  saveInteractionDraft('p1', 'o1', filled)
  assert.equal(loadInteractionDraft('p1', 'o1').createTitle, 'Внедрение')

  clearInteractionDraft('p1', 'o1')

  assert.equal(storage.size(), 0)
  assert.equal(loadInteractionDraft('p1', 'o1').createTitle, '')
})

test('метка несохранённого черновика снимается, когда форма очищена или закрыта', () => {
  markUnsavedDraft('organization:o1:create', 'новое взаимодействие', true)
  markUnsavedDraft('organization:o1:handover', 'комментарий к передаче', true)
  assert.deepEqual(currentUnsavedLabels(), ['новое взаимодействие', 'комментарий к передаче'])

  forgetUnsavedDraft('organization:o1:create')
  assert.deepEqual(currentUnsavedLabels(), ['комментарий к передаче'])

  markUnsavedDraft('organization:o1:handover', 'комментарий к передаче', false)
  assert.deepEqual(currentUnsavedLabels(), [])
})

test('дубли вуза запрашиваются по названию и по полному ИНН, неизменённые поля не проверяются', () => {
  assert.deepEqual(duplicateQuery({ name: ' Университет А ', inn: '' }), { name: 'Университет А', inn: '' })
  assert.deepEqual(duplicateQuery({ name: '', inn: '7700 000011' }), { name: '', inn: '7700000011' })
  assert.deepEqual(duplicateQuery({ name: 'Университет А', inn: '770000001' }), { name: 'Университет А', inn: '' })
  assert.equal(duplicateQuery({ name: 'А', inn: '12345' }), null)
  assert.equal(
    duplicateQuery({ name: 'Университет А', inn: '7700000011' }, { name: 'Университет А', inn: '7700 000011' }),
    null
  )
  assert.deepEqual(
    duplicateQuery({ name: 'Университет А', inn: '7700000012' }, { name: 'Университет А', inn: '7700000011' }),
    { name: '', inn: '7700000012' }
  )
})

test('ссылки tel: и mailto: строятся только из распознанных значений', () => {
  assert.equal(phoneHref('+7 (900) 123-45-67'), 'tel:+79001234567')
  assert.equal(phoneHref('8 900 123 45 67 доб. 12'), 'tel:89001234567')
  assert.equal(phoneHref('12'), null)
  assert.equal(phoneHref(null), null)
  assert.equal(emailHref(' ivan@example.test '), 'mailto:ivan@example.test')
  assert.equal(emailHref('не указана'), null)
})

test('недоступный роли раздел приводит адрес к первому доступному, пустой адрес не трогается', () => {
  assert.equal(canonicalAddress(parseRoute('#/control'), ['admin', 'help']), '#/admin')
  assert.equal(canonicalAddress(parseRoute('#/admin/employees'), ['admin', 'help']), null)
  assert.equal(canonicalAddress(parseRoute('#/'), ['admin', 'help']), null)
  assert.equal(canonicalAddress(parseRoute('#/enrolment?x=1'), ['work', 'help']), '#/work')
})

test('устаревший или недоступный подраздел администрирования заменяется настоящим адресом', () => {
  assert.equal(canonicalAdminHash(undefined, '', 'ADMIN'), null)
  assert.equal(canonicalAdminHash('employees', 'status=PENDING', 'ADMIN'), null)
  assert.equal(canonicalAdminHash('pending-source-records', '', 'ADMIN'), '#/admin/overview')
  assert.equal(canonicalAdminHash('profiles', 'pending=true', 'ADMIN'), '#/admin/employees?pending=true')
})

test('отбор списка вузов следует за адресом: переход на адрес без параметров сбрасывает отбор', () => {
  const filtered = filtersFromQuery('requiresAssignment=true&q=abc', 'LEADER')
  assert.equal(queryFromFilters(filtered), 'q=abc&requiresAssignment=true')
  assert.equal(queryFromFilters(filtersFromQuery('', 'LEADER')), '')
  assert.equal(filtersFromQuery('requiresAssignment=true', 'USER').requiresAssignment, false)
})

test('при ошибке интерфейса показывается экран с «Обновить» и «На главную», а не белый экран', () => {
  assert.deepEqual(ErrorBoundary.getDerivedStateFromError(), { failed: true })
  const html = renderToStaticMarkup(createElement(ErrorFallback))
  assert.match(html, /Что-то пошло не так/)
  assert.match(html, />Обновить</)
  assert.match(html, />На главную</)
})

test('склонение по числу: 1 вуз, 2 вуза, 5 вузов, 11 вузов, 21 вуз, 22 вуза', () => {
  const vuz = (count: number) => withCount(count, 'вуз', 'вуза', 'вузов')
  assert.deepEqual([1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 111, 0].map(vuz), [
    '1 вуз', '2 вуза', '4 вуза', '5 вузов', '11 вузов', '12 вузов', '14 вузов', '21 вуз', '22 вуза', '25 вузов', '111 вузов', '0 вузов'
  ])
})
