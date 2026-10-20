import assert from 'node:assert/strict'
import { test } from 'node:test'
import { initialSection, initialStack, moscowDateTime, nextStepErrors, ownBackButton, pop, push, readInitData } from '../src/max/startup.ts'
import { readFileSync } from 'node:fs'
import { dueChip, dueText, firstNonEmpty, groupOfDue, groups, managerChips, plural, quickDue, teamOrder, totalsOf } from '../src/max/model.ts'
import { MaxApiError, createMaxApi, problemOf } from '../src/max/maxApi.ts'

test('initData берётся из моста MAX, а без него — из фрагмента #WebAppData', () => {
  assert.equal(readInitData({ initData: 'auth_date=1&hash=a' }, '#WebAppData=ignored'), 'auth_date=1&hash=a')
  assert.equal(
    readInitData(undefined, '#WebAppData=auth_date%3D1%26hash%3Da&WebAppPlatform=web&WebAppVersion=26.2.8'),
    'auth_date=1&hash=a'
  )
  assert.equal(readInitData({ initData: '' }, ''), null)
  assert.equal(readInitData(undefined, '#/work'), null)
})

test('первый экран по payload кнопки, «Назад» не уходит дальше ленты', () => {
  assert.deepEqual(initialStack(null), [{ screen: 'work' }])
  assert.deepEqual(initialStack('i_0123456789abcdef0123456789abcdef'), [
    { screen: 'work' }, { screen: 'card', id: '01234567-89ab-cdef-0123-456789abcdef' }
  ])
  assert.deepEqual(initialStack('i_../admin'), [{ screen: 'work' }])
  assert.deepEqual(initialStack('control'), [{ screen: 'work' }])
  assert.equal(initialSection('control', true), 'team')
  assert.equal(initialSection('control', false), 'work')
  assert.equal(initialSection(null, true), 'work')
  const stack = push([{ screen: 'work' }], { screen: 'card', id: 'x' })
  assert.deepEqual(pop(stack), [{ screen: 'work' }])
  assert.deepEqual(pop(pop(stack)), [{ screen: 'work' }])
})

test('своя «Назад» — только без системной BackButton MAX', () => {
  const backButton = { show() {}, hide() {}, onClick() {}, offClick() {} }
  assert.equal(ownBackButton({ BackButton: backButton }, 2), false)
  assert.equal(ownBackButton(undefined, 2), true)
  assert.equal(ownBackButton({}, 2), true)
  assert.equal(ownBackButton(undefined, 1), false)
})

test('следующий шаг обязателен целиком, срок — по Москве', () => {
  assert.deepEqual(nextStepErrors('Позвонить', '2026-10-21T10:00'), {})
  assert.deepEqual(Object.keys(nextStepErrors('Позвонить', '')), ['nextActionAt'])
  assert.deepEqual(Object.keys(nextStepErrors(' ', '2026-10-21T10:00')), ['nextAction'])
  assert.equal(moscowDateTime('2026-10-21T10:00'), '2026-10-21T10:00:00+03:00')
  assert.equal(moscowDateTime('21.10.2026'), null)
})

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

test('клиент держит токен в памяти, ставит Bearer и один раз входит заново после 401', async () => {
  const calls: { url: string; init?: RequestInit }[] = []
  let sessions = 0
  const fetchImpl = async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    if (url === '/api/max/app/session') {
      sessions++
      return json(200, { token: `t${sessions}`, expiresAt: '2026-10-18T12:00:00Z', idleMinutes: 30, role: 'USER' })
    }
    const auth = new Headers(init?.headers).get('Authorization')
    return auth === 'Bearer t2' ? json(200, { ok: true }) : json(401, { code: 'MAX_SESSION_EXPIRED', message: 'истёк' })
  }
  const api = createMaxApi('auth_date=1&hash=a', fetchImpl)
  const result = await api.comment('id-1', { version: 1, text: 't', nextStep: { nextAction: 'a', nextActionAt: '2026-10-21T10:00:00+03:00' } }, 'key-1')
  assert.deepEqual(result, { ok: true })
  assert.equal(sessions, 2)
  const commentCalls = calls.filter((call) => call.url.endsWith('/comments'))
  assert.equal(commentCalls.length, 2)
  assert.deepEqual(commentCalls.map((call) => new Headers(call.init?.headers).get('Idempotency-Key')), ['key-1', 'key-1'])
  assert.equal(JSON.parse(String(calls[0].init?.body)).initData, 'auth_date=1&hash=a')
  assert.ok(calls.every((call) => call.init?.credentials === undefined))
})

test('просроченная initData при повторном входе даёт понятное сообщение, а не бесконечный повтор', async () => {
  let requests = 0
  const fetchImpl = async (url: string) => {
    requests++
    return url === '/api/max/app/session'
      ? json(401, { code: 'MAX_INIT_DATA_EXPIRED', message: 'устарело' })
      : json(401, { code: 'MAX_SESSION_EXPIRED', message: 'истёк' })
  }
  const api = createMaxApi('x', fetchImpl)
  await assert.rejects(api.work('TODAY', 0), (error: unknown) => error instanceof MaxApiError && error.code === 'MAX_INIT_DATA_EXPIRED')
  assert.equal(requests, 1)
  assert.equal(problemOf(new MaxApiError(401, 'MAX_INIT_DATA_EXPIRED', ''), false)?.title, 'Сеанс закончился')
  assert.equal(problemOf(new MaxApiError(403, 'MAX_NOT_LINKED', ''), false)?.action, 'crm')
  assert.equal(problemOf(new Error('сеть'), true)?.action, 'retry')
  // посреди работы сеть и обычные отказы — не экран входа, а плашка с «Повторить»
  assert.equal(problemOf(new Error('сеть'), false), null)
  assert.equal(problemOf(new MaxApiError(404, 'NOT_FOUND', 'нет'), false), null)
  assert.equal(problemOf(new MaxApiError(404, 'NOT_FOUND', 'нет'), true)?.title, 'Недоступно')
})

test('первая непустая группа: Просрочено → Сегодня → На неделе → Без шага → Позже; всё пусто — null', () => {
  const feed = (overdue: number, today: number, thisWeek: number, later: number, noNextStep: number) => (
    { overdue, today, thisWeek, later, noNextStep, resumeDue: 0, resumeNoDate: 0 }
  )
  assert.equal(firstNonEmpty(totalsOf(feed(2, 0, 3, 1, 1))), 'overdue')
  assert.equal(firstNonEmpty(totalsOf(feed(0, 0, 3, 1, 1))), 'week')
  // «На неделе» — без сегодня: неделя из одних сегодняшних дел — группа «Сегодня»
  assert.equal(totalsOf(feed(0, 2, 2, 0, 0)).week, 0)
  assert.equal(firstNonEmpty(totalsOf(feed(0, 0, 0, 4, 1))), 'noStep')
  assert.equal(firstNonEmpty(totalsOf(feed(0, 0, 0, 4, 0))), 'later')
  assert.equal(firstNonEmpty(totalsOf(feed(0, 0, 0, 0, 0))), null)
  assert.deepEqual(groups.map((group) => group.due), ['OVERDUE', 'TODAY', 'REST_OF_WEEK', 'NO_NEXT_STEP', 'LATER'])
  assert.equal(groupOfDue('REST_OF_WEEK'), 'week')
  assert.equal(groupOfDue('THIS_WEEK'), 'week')
  assert.equal(groups.find((group) => group.key === 'week')?.title, 'На этой неделе')
})

test('метка срока по Москве: насколько просрочено или когда', () => {
  const now = Date.parse('2026-10-20T12:00:00+03:00') // вторник
  assert.deepEqual(dueChip(null, now), { tone: 'warning', label: 'Срок не задан' })
  assert.equal(dueChip('2026-10-20T11:30:00+03:00', now).label, 'Просрочено на 30 мин')
  assert.equal(dueChip('2026-10-20T09:00:00+03:00', now).label, 'Просрочено на 3 ч')
  assert.equal(dueChip('2026-10-18T09:00:00+03:00', now).label, 'Просрочено на 2 дн.')
  assert.equal(dueChip('2026-10-18T09:00:00+03:00', now).tone, 'danger')
  assert.equal(dueChip('2026-10-20T15:30:00+03:00', now).label, 'Сегодня, 15:30')
  assert.equal(dueChip('2026-10-21T10:00:00+03:00', now).label, 'Завтра, 10:00')
  assert.equal(dueChip('2026-10-22T10:00:00+03:00', now).label, 'Чт 22.10, 10:00')
  assert.equal(dueChip('2026-11-12T12:00:00+03:00', now).label, '12.11, 12:00')
  assert.equal(dueChip('2027-01-12T12:00:00+03:00', now).label, '12.01.2027')
})

test('быстрые сроки — 10:00 МСК, выходной переносится на понедельник', () => {
  const tuesday = Date.parse('2026-10-20T23:30:00+03:00')
  assert.deepEqual(quickDue('tomorrow', tuesday), { local: '2026-10-21T10:00', moved: false })
  assert.deepEqual(quickDue('d3', tuesday), { local: '2026-10-23T10:00', moved: false })
  assert.deepEqual(quickDue('week', tuesday), { local: '2026-10-27T10:00', moved: false })
  const friday = Date.parse('2026-10-23T10:00:00+03:00')
  assert.deepEqual(quickDue('tomorrow', friday), { local: '2026-10-26T10:00', moved: true })
  assert.equal(dueText('2026-10-21T10:00'), 'Ср, 21 октября, 10:00 МСК')
  assert.equal(dueText(''), null)
})

test('команда: сначала у кого горит, «Без ответственного» последним, нули скрыты', () => {
  const manager = (managerId: string | null, managerName: string | null, overdue: number, atRisk: number, stuck: number, withoutNextStep: number) => (
    { managerId, managerName, organizations: 3, interactions: 5, overdue, atRisk, stuck, withoutNextStep }
  )
  const sorted = teamOrder([
    manager(null, null, 9, 0, 0, 0),
    manager('a', 'Ким Денис', 0, 0, 0, 0),
    manager('b', 'Петров', 1, 0, 2, 0),
    manager('c', 'Иванова', 2, 1, 0, 0)
  ])
  assert.deepEqual(sorted.map((item) => item.managerName), ['Иванова', 'Петров', 'Ким Денис', null])
  assert.deepEqual(managerChips(sorted[1]).map((chip) => chip.label), ['просрочено 1', 'застряли 2'])
  assert.deepEqual(managerChips(sorted[2]), [{ tone: 'success', label: 'всё в порядке' }])
  assert.equal(plural(2, ['вуз', 'вуза', 'вузов']), '2 вуза')
  assert.equal(plural(11, ['вуз', 'вуза', 'вузов']), '11 вузов')
  assert.equal(plural(21, ['вуз', 'вуза', 'вузов']), '21 вуз')
})

test('клиент: без due сервер выбирает группу сам, устаревший запрос отменяется', async () => {
  const urls: string[] = []
  const signals: (AbortSignal | null | undefined)[] = []
  const fetchImpl = async (url: string, init?: RequestInit) => {
    urls.push(url)
    signals.push(init?.signal)
    return url === '/api/max/app/session'
      ? json(200, { token: 't', expiresAt: '2026-10-18T12:00:00Z', idleMinutes: 30, role: 'USER' })
      : json(200, {})
  }
  const api = createMaxApi('x', fetchImpl)
  const controller = new AbortController()
  await api.work(null, 0, controller.signal)
  await api.work('REST_OF_WEEK', 1)
  assert.deepEqual(urls.slice(1), ['/api/max/app/work?page=0', '/api/max/app/work?due=REST_OF_WEEK&page=1'])
  assert.equal(signals[1], controller.signal)
})

test('стили MAX не тянут глобальные правила CRM, тема и безопасные зоны — свои', () => {
  const read = (path: string) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
  const main = read('src/max/main.tsx')
  assert.match(main, /shared\/theme\/variables\.css/)
  assert.doesNotMatch(main, /import '[^']*tokens\.css'/)
  assert.doesNotMatch(read('src/shared/theme/variables.css'), /\n(button|:focus-visible)[^\n]*\{/)
  const css = read('src/max/max.css')
  assert.match(css, /prefers-color-scheme: dark/)
  assert.match(css, /env\(safe-area-inset-bottom\)/)
  assert.doesNotMatch(css, /overflow-x: auto/)
  assert.match(read('max.html'), /viewport-fit=cover/)
  // CSP /max/ запрещает атрибут style
  for (const file of ['MaxApp', 'WorkScreen', 'CardScreen', 'StepForm', 'TeamScreen', 'ui']) {
    assert.doesNotMatch(read(`src/max/${file}.tsx`), /style=\{/, file)
  }
})
