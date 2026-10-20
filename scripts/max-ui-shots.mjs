// Снимки и проверки вёрстки мини-приложения MAX без сервера CRM и без новых зависимостей.
//
// Собранный frontend/dist отдаётся маленьким HTTP-сервером с заголовком CSP из infra/web/security-headers-max.conf
// (так проверяется и запрет атрибута style). Chrome запускается этим скриптом без окна, со своим временным профилем,
// и останавливается по своему PID. Протокол CDP — как в scripts/wave4-smoke.mjs: WebSocket, call/openPage.
// Мост MAX подменяется заглушкой (запрос к st.max.ru перехватывается), ответы /api/max/app/* — фикстуры (Fetch).
// Матрица: ширины 320, 375, 430 × светлая и тёмная тема. На каждом снимке — проверки в DOM, а не глазами.
//
// Запуск: cd frontend && npm run build && cd .. && node scripts/max-ui-shots.mjs
// Переменные: MAX_SHOTS_OUT — каталог снимков (по умолчанию max-ui-shots), CHROME — путь к Chrome или Edge.
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const dist = path.join(root, 'frontend', 'dist')
const out = path.resolve(process.env.MAX_SHOTS_OUT ?? 'max-ui-shots')
const widths = [320, 375, 430]
const themes = ['light', 'dark']
const height = 760
// Предел ожидания признака готовности в DOM. Ждём признак, а не фиксированное время: под нагрузкой экран
// собирается дольше, и прежний предел в 6 с давал ложные «экран не готов».
const waitMs = Number(process.env.MAX_SHOTS_WAIT_MS ?? 30000)

const csp = /Content-Security-Policy "([^"]+)"/.exec(fs.readFileSync(path.join(root, 'infra', 'web', 'security-headers-max.conf'), 'utf8'))[1]

// ---------- Фикстуры ----------
const minute = 60_000
const at = (minutes) => new Date(Date.now() + minutes * minute).toISOString()
const moscowHour = (days, hour) => {
  const day = Math.floor((Date.now() + 3 * 60 * minute) / 86_400_000) + days
  return new Date(day * 86_400_000 + (hour - 3) * 60 * minute).toISOString()
}
const marks = (extra = {}) => ({
  status: 'ACTIVE', statusReason: null, waitingOn: null, waitingNote: null, problemCount: 0, riskLevel: null, riskCount: 0,
  problems: null, risks: null, ...extra
})
let serial = 0
const item = (nextAction, organizationName, title, nextActionAt, extraMarks) => {
  serial += 1
  const id = `00000000-0000-4000-8000-${String(serial).padStart(12, '0')}`
  return {
    id, organizationId: '10000000-0000-4000-8000-000000000001', title, currentStageId: id, currentStageName: 'Согласование программы',
    nextAction, nextActionAt, contactIds: [], programId: null, productIds: [], lastContactAt: null, version: 7,
    createdBy: id, createdAt: at(-10000), updatedAt: at(-100), marks: marks(extraMarks), organizationName, programName: null,
    ownerManagerName: 'Анна', lastEventType: null, lastEventAt: null, stageEnteredAt: null, deputyManagerName: null, deputyEndsOn: null
  }
}

const ownerData = () => ({
  OVERDUE: [
    item('Позвонить проректору по учебной работе: подтвердить состав группы и сроки начала обучения', 'Университет телекоммуникаций (демо)', 'Курс «Сети связи», весна', at(-2 * 24 * 60), { waitingOn: 'UNIVERSITY' }),
    item('Отправить проект договора юристам вуза', 'Институт информатики (демо)', 'Стажировки 2027', at(-180), { riskLevel: 'HIGH', riskCount: 1 })
  ],
  TODAY: [],
  REST_OF_WEEK: [
    item('Встреча с деканом: план набора', 'Университет Б (демо)', 'Повышение квалификации', moscowHour(1, 10)),
    item('Согласовать расписание практики', 'Сибирский колледж связи', 'Практика 3 курса', moscowHour(2, 16))
  ],
  NO_NEXT_STEP: [item(null, 'Университет В (демо)', 'Лаборатория 5G', null)],
  LATER: [
    item('Подготовить отчёт по соглашению', 'Университет Г (демо)', 'Соглашение о сотрудничестве', moscowHour(23, 12)),
    item('Пригласить на день открытых дверей', 'Казанский ИТ-колледж', 'Профориентация', moscowHour(31, 10))
  ]
})

const datasets = {
  owner: ownerData(),
  empty: { OVERDUE: [], TODAY: [], REST_OF_WEEK: [], NO_NEXT_STEP: [], LATER: [] },
  later: { ...Object.fromEntries(Object.keys(ownerData()).map((key) => [key, []])), LATER: ownerData().LATER },
  many: {
    ...Object.fromEntries(Object.keys(ownerData()).map((key) => [key, []])),
    OVERDUE: Array.from({ length: 45 }, (_, index) => item(`Шаг ${index + 1}: уточнить план набора`, `Вуз № ${index + 1}`, 'Курс', at(-60 * (index + 1))))
  }
}

const feedOf = (data) => ({
  overdue: data.OVERDUE.length, today: data.TODAY.length, thisWeek: data.TODAY.length + data.REST_OF_WEEK.length,
  later: data.LATER.length, noNextStep: data.NO_NEXT_STEP.length, resumeDue: 0, resumeNoDate: 0
})
const firstGroup = (data) => ['OVERDUE', 'TODAY', 'REST_OF_WEEK', 'NO_NEXT_STEP', 'LATER'].find((due) => data[due].length > 0) ?? 'TODAY'

const card = (source, overrides = {}) => ({
  organization: { id: source.organizationId, name: source.organizationName, ownerManagerName: 'Анна' },
  interaction: { ...source, ...overrides },
  events: [
    { id: 'e1', interactionId: source.id, type: 'COMMENT', version: 6, occurredAt: at(-5 * 24 * 60), actorDisplayName: 'Анна', comment: 'Отправили программу на согласование, ждём ответ кафедры.', stageId: null, stageName: null },
    { id: 'e2', interactionId: source.id, type: 'STEP_COMPLETED', version: 5, occurredAt: at(-12 * 24 * 60), actorDisplayName: 'Анна', comment: 'Шаг выполнен: встреча с деканом.', stageId: null, stageName: null }
  ],
  contacts: [
    { id: 'c1', name: 'Смирнов Игорь Петрович', position: 'проректор по учебной работе', phone: '+7 900 000-00-01', email: 'i.smirnov@example.test' },
    { id: 'c2', name: 'Орлова Анна', position: 'методист', phone: null, email: 'a.orlova@example.test' }
  ],
  agreements: [{ id: 'g1', number: '45-СТ/2026', status: 'ACTIVE', statusTitle: 'действует', validUntil: '2026-12-31' }]
})

const team = {
  calculatedAt: new Date().toISOString(), stuckDays: 14, unassignedOrganizations: 2,
  managers: [
    { managerId: 'm1', managerName: 'Ким Денис', organizations: 7, interactions: 9, overdue: 0, withoutNextStep: 0, stuck: 0, atRisk: 0 },
    { managerId: 'm2', managerName: 'Иванова Мария', organizations: 12, interactions: 20, overdue: 5, withoutNextStep: 2, stuck: 1, atRisk: 2 },
    { managerId: null, managerName: null, organizations: 2, interactions: 2, overdue: 1, withoutNextStep: 0, stuck: 0, atRisk: 0 },
    { managerId: 'm3', managerName: 'Петров Алексей', organizations: 9, interactions: 11, overdue: 3, withoutNextStep: 0, stuck: 2, atRisk: 0 }
  ]
}

const hexOf = (id) => id.replaceAll('-', '')

// ---------- Сценарии ----------
// api(path, request, state) → { status, body } | null; ready — выражение готовности; checks — особые проверки.
const workApi = (data, options = {}) => (url, request, state) => {
  if (url.pathname === '/api/max/app/session') {
    state.sessions = (state.sessions ?? 0) + 1
    return options.session?.(state) ?? { status: 200, body: { token: `t${state.sessions}`, expiresAt: at(240), idleMinutes: 30, role: options.role ?? 'USER', startParam: options.startParam ?? null } }
  }
  if (url.pathname === '/api/max/app/work') {
    state.workCalls = (state.workCalls ?? 0) + 1
    if (options.work) {
      const custom = options.work(url, state)
      if (custom) return custom
    }
    const due = url.searchParams.get('due') ?? firstGroup(data)
    const page = Number(url.searchParams.get('page') ?? 0)
    const rows = data[due]
    return { status: 200, body: { feed: feedOf(data), due, items: { items: rows.slice(page * 20, page * 20 + 20), page, size: 20, total: rows.length } }, delay: options.delay?.(due) }
  }
  if (url.pathname.startsWith('/api/max/app/interactions/')) {
    const id = url.pathname.split('/')[5]
    const source = Object.values(data).flat().find((row) => row.id === id) ?? ownerData().OVERDUE[0]
    if (request.method === 'POST') {
      return options.save?.(state) ?? { status: 200, body: source }
    }
    return { status: 200, body: card(source) }
  }
  if (url.pathname === '/api/max/app/control') {
    return { status: 200, body: team }
  }
  return null
}

const visibleText = (text) => `document.body.innerText.includes(${JSON.stringify(text)})`
const click = (text, selector = 'button') => `(() => { const node = [...document.querySelectorAll(${JSON.stringify(selector)})].find((element) => element.offsetParent !== null && element.textContent.includes(${JSON.stringify(text)})); node?.click(); return Boolean(node) })()`

const owner = datasets.owner
const scenarios = [
  { name: '01-work-owner', api: workApi(owner), ready: "document.querySelectorAll('.mx-row:not(.mx-row--skeleton)').length > 0", expect: { pressed: 'overdue', rows: 2 } },
  { name: '02-work-week', api: workApi(owner), ready: "document.querySelectorAll('.mx-row:not(.mx-row--skeleton)').length > 0",
    steps: [click('На неделе', '.mx-tile'), "document.querySelector('[data-group-list=\"week\"]') !== null"], expect: { pressed: 'week', rows: 2 } },
  { name: '03-work-today-empty', api: workApi(owner), ready: "document.querySelectorAll('.mx-row:not(.mx-row--skeleton)').length > 0",
    steps: [click('Сегодня', '.mx-tile'), "document.querySelector('.mx-empty') !== null"], expect: { pressed: 'today', rows: 0 } },
  { name: '04-all-done', api: workApi(datasets.empty), ready: visibleText('Всё сделано'), expect: { rows: 0 } },
  { name: '05-only-later', api: workApi(datasets.later), ready: visibleText('Срочного нет'), expect: { pressed: 'later', rows: 2 } },
  { name: '06-show-more', api: workApi(datasets.many), ready: visibleText('Показать ещё 25'),
    steps: [click('Показать ещё'), "document.querySelectorAll('.mx-row').length === 40"], expect: { pressed: 'overdue', rows: 40 } },
  { name: '07-work-error', api: workApi(owner, { work: () => ({ status: 500, body: { code: 'INTERNAL_ERROR', message: 'Сервер CRM не ответил. Повторите позже.' } }) }),
    ready: visibleText('Повторить') },
  { name: '08-not-linked', api: workApi(owner, { session: () => ({ status: 403, body: { code: 'MAX_NOT_LINKED', message: 'не подключён' } }) }),
    ready: visibleText('MAX не подключён к CRM') },
  { name: '09-session-expired', api: workApi(owner, {
    session: (state) => (state.sessions > 1 ? { status: 401, body: { code: 'MAX_INIT_DATA_EXPIRED', message: 'устарело' } } : null),
    work: () => ({ status: 401, body: { code: 'MAX_SESSION_EXPIRED', message: 'истёк' } })
  }), ready: visibleText('Сеанс закончился') },
  { name: '10-card', api: workApi(owner, { startParam: `i_${hexOf(owner.OVERDUE[0].id)}` }), ready: visibleText('Следующий шаг'), depth: 2 },
  { name: '11-form-step-errors', api: workApi(owner, { startParam: `i_${hexOf(owner.OVERDUE[0].id)}` }), ready: visibleText('Следующий шаг'), depth: 3,
    steps: [click('Шаг выполнен', '.mx-bottom button'), visibleText('Выполняем шаг'), click('Сохранить'), visibleText('Укажите следующий шаг')] },
  { name: '12-form-comment', api: workApi(owner, { startParam: `i_${hexOf(owner.REST_OF_WEEK[0].id)}` }), ready: visibleText('Следующий шаг'), depth: 3,
    steps: [click('Комментарий', '.mx-bottom button'), visibleText('Шаг и срок останутся прежними')] },
  { name: '13-form-conflict', api: workApi(owner, { startParam: `i_${hexOf(owner.REST_OF_WEEK[0].id)}`, save: () => ({ status: 409, body: { code: 'VERSION_CONFLICT', message: 'изменили' } }) }),
    ready: visibleText('Следующий шаг'), depth: 3,
    steps: [click('Комментарий', '.mx-bottom button'), visibleText('Изменить'),
      "(() => { const area = document.querySelector('textarea'); const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set; set.call(area, 'Позвонил, договорились о встрече'); area.dispatchEvent(new Event('input', { bubbles: true })); return true })()",
      "document.querySelector('textarea').value.length > 0",
      click('Сохранить'), visibleText('Обновить данные')] },
  { name: '14-team', api: workApi(owner, { role: 'LEADER', startParam: 'control' }), ready: visibleText('Только просмотр') },
  { name: '15-card-no-bridge', bridge: false, api: workApi(owner, { startParam: `i_${hexOf(owner.OVERDUE[0].id)}` }), ready: visibleText('Следующий шаг'), depth: 2 }
]

// ---------- Заглушка моста ----------
const bridgeScript = `window.__bridge = { calls: [], back: null, shown: false };
window.WebApp = {
  initData: 'auth_date=1&hash=test', platform: 'ios', version: 'test',
  BackButton: {
    show() { __bridge.calls.push('show'); __bridge.shown = true },
    hide() { __bridge.calls.push('hide'); __bridge.shown = false },
    onClick(callback) { __bridge.back = callback },
    offClick() { __bridge.back = null }
  },
  enableClosingConfirmation() { __bridge.calls.push('confirm:on') },
  disableClosingConfirmation() { __bridge.calls.push('confirm:off') },
  openLink(url) { __bridge.calls.push('openLink:' + url) },
  ready() { __bridge.calls.push('ready') },
  close() { __bridge.calls.push('close') },
  HapticFeedback: { notificationOccurred(type) { __bridge.calls.push('haptic:' + type) } }
};`

// ---------- HTTP и Chrome ----------
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.png': 'image/png' }
const server = http.createServer((request, response) => {
  const pathname = decodeURIComponent(new URL(request.url, 'http://x').pathname)
  const file = path.join(dist, pathname === '/' ? 'max.html' : pathname)
  if (!file.startsWith(dist) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    response.writeHead(404).end()
    return
  }
  response.writeHead(200, { 'Content-Type': types[path.extname(file)] ?? 'application/octet-stream', 'Content-Security-Policy': csp })
  fs.createReadStream(file).pipe(response)
})

const chromePath = process.env.CHROME ?? ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', '/usr/bin/google-chrome', '/usr/bin/chromium']
  .find((candidate) => fs.existsSync(candidate))

let socket
let nextId = 1
const pending = new Map()
const listeners = new Set()
const call = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
  const id = nextId++
  pending.set(id, { resolve, reject })
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
})
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

async function openPage() {
  const context = await call('Target.createBrowserContext')
  const target = await call('Target.createTarget', { url: 'about:blank', browserContextId: context.browserContextId })
  const { sessionId } = await call('Target.attachToTarget', { targetId: target.targetId, flatten: true })
  await call('Page.enable', {}, sessionId)
  const evaluate = async (expression) => {
    const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true }, sessionId)
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text)
    return result.result.value
  }
  const waitFor = async (expression, message) => {
    const deadline = Date.now() + waitMs
    while (Date.now() < deadline) {
      if (await evaluate(expression).catch(() => false)) return
      await pause(50)
    }
    throw new Error(message)
  }
  return { contextId: context.browserContextId, sessionId, evaluate, waitFor }
}

// Экран дорисован: шрифты загружены, прошла задача и два кадра отрисовки — вместо паузы на глаз.
const settled = 'document.fonts.ready.then(() => new Promise((resolve) => setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))), 0)))'

// ---------- Проверки в DOM ----------
const domChecks = `(() => {
  const problems = []
  const visible = (node) => node.offsetParent !== null || getComputedStyle(node).position === 'fixed'
  if (document.documentElement.scrollWidth > innerWidth) problems.push('горизонтальная прокрутка страницы: ' + document.documentElement.scrollWidth + ' > ' + innerWidth)
  const tiles = [...document.querySelectorAll('.mx-tiles[role=group]')].find(visible)
  let pressed = null
  if (tiles) {
    if (tiles.scrollWidth > tiles.clientWidth) problems.push('плитки обрезаны')
    const on = [...tiles.querySelectorAll('[aria-pressed="true"]')]
    if (on.length !== 1) problems.push('выбранных плиток: ' + on.length)
    pressed = on[0] ? { group: on[0].dataset.group, count: Number(on[0].querySelector('.mx-tile__count').textContent) } : null
    for (const label of tiles.querySelectorAll('.mx-tile__label')) if (label.scrollWidth > label.clientWidth) problems.push('подпись плитки обрезана: ' + label.textContent)
    if (on[0]) {
      const style = getComputedStyle(on[0])
      const rgb = (value) => value.match(/[\\d.]+/g).slice(0, 3).map(Number)
      const lum = ([r, g, b]) => [r, g, b].map((c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4 }).reduce((s, c, i) => s + c * [0.2126, 0.7152, 0.0722][i], 0)
      const [a, b] = [lum(rgb(style.color)), lum(rgb(style.backgroundColor))].sort((x, y) => y - x)
      const ratio = (a + 0.05) / (b + 0.05)
      if (ratio < 4.5) problems.push('контраст выбранной плитки ' + ratio.toFixed(2))
    }
  }
  const empty = [...document.querySelectorAll('.mx-empty')].find(visible)
  const rows = [...document.querySelectorAll('.mx-row:not(.mx-row--skeleton)')].filter(visible).length
  const skeleton = [...document.querySelectorAll('.mx-row--skeleton')].some(visible)
  if (pressed && empty && pressed.count !== 0) problems.push('«пусто» при счётчике ' + pressed.count)
  if (pressed && !skeleton && pressed.count > 0 && rows === 0 && !document.querySelector('.mx-banner--error')) problems.push('счётчик ' + pressed.count + ', а строк нет')
  if (pressed && rows > pressed.count) problems.push('строк больше счётчика')
  for (const node of document.querySelectorAll('.mx-button, .mx-row__action, .mx-tile, .mx-icon-button')) {
    if (visible(node) && node.getBoundingClientRect().height < 44) problems.push('кнопка ниже 44 px: ' + node.textContent.trim().slice(0, 30))
  }
  for (const node of document.querySelectorAll('.mx-app *')) {
    if (visible(node) && node.getBoundingClientRect().right > innerWidth + 1) { problems.push('выходит за край экрана: ' + node.className); break }
  }
  if (document.querySelector('[style]')) problems.push('атрибут style в разметке')
  const backs = [...document.querySelectorAll('.mx-back')].filter(visible).length + (window.__bridge?.shown ? 1 : 0)
  return { problems, pressed, rows, empty: Boolean(empty), backs, calls: window.__bridge?.calls ?? null }
})()`

async function run(scenario, width, theme, page) {
  const state = {}
  await call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 2, mobile: true }, page.sessionId)
  await call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: theme }, { name: 'prefers-reduced-motion', value: 'reduce' }] }, page.sessionId)
  const handler = async (message) => {
    if (message.method !== 'Fetch.requestPaused' || message.sessionId !== page.sessionId) return
    const { requestId, request } = message.params
    const url = new URL(request.url)
    let status = 200
    let body = ''
    let type = 'application/json'
    let delay = 0
    if (url.hostname === 'st.max.ru') {
      type = 'text/javascript'
      body = scenario.bridge === false ? '' : bridgeScript
    } else {
      const answer = scenario.api(url, request, state) ?? { status: 404, body: { code: 'NOT_FOUND', message: 'нет' } }
      status = answer.status
      body = JSON.stringify(answer.body)
      delay = answer.delay ?? 0
    }
    if (delay) await pause(delay)
    await call('Fetch.fulfillRequest', {
      requestId, responseCode: status,
      responseHeaders: [{ name: 'Content-Type', value: type }, { name: 'Access-Control-Allow-Origin', value: '*' }],
      body: Buffer.from(body).toString('base64')
    }, page.sessionId).catch(() => null)
  }
  listeners.add(handler)
  try {
    await call('Fetch.enable', { patterns: [{ urlPattern: 'https://st.max.ru/*' }, { urlPattern: '*/api/max/app/*' }] }, page.sessionId)
    const hash = scenario.bridge === false ? '#WebAppData=auth_date%3D1%26hash%3Dtest' : ''
    await call('Page.navigate', { url: `${origin}/max.html${hash}` }, page.sessionId)
    await page.waitFor(scenario.ready, `${scenario.name}: экран не готов`)
    for (let index = 0; index < (scenario.steps ?? []).length; index += 2) {
      const done = await page.evaluate(scenario.steps[index])
      if (done === false) throw new Error(`${scenario.name}: шаг ${index / 2 + 1} не выполнен`)
      await page.waitFor(scenario.steps[index + 1], `${scenario.name}: после шага ${index / 2 + 1} нет ожидаемого`)
    }
    await page.evaluate(settled)
    const result = await page.evaluate(domChecks)
    const expect = scenario.expect ?? {}
    if (expect.pressed && result.pressed?.group !== expect.pressed) result.problems.push(`выбрана ${result.pressed?.group}, ждали ${expect.pressed}`)
    if (expect.rows !== undefined && result.rows !== expect.rows) result.problems.push(`строк ${result.rows}, ждали ${expect.rows}`)
    if ((scenario.depth ?? 1) > 1 && result.backs !== 1) result.problems.push(`кнопок «Назад»: ${result.backs}`)
    const shot = await call('Page.captureScreenshot', { format: 'png' }, page.sessionId)
    fs.writeFileSync(path.join(out, `${scenario.name}-${width}-${theme}.png`), Buffer.from(shot.data, 'base64'))
    return result
  } finally {
    listeners.delete(handler)
    await call('Fetch.disable', {}, page.sessionId).catch(() => null)
  }
}

// Гонка: поздний ответ «На неделе» не должен лечь под выбранную после него группу; пока грузится — скелет, не «пусто».
// Ответы «Сегодня» и «На неделе» придерживаются до нужного шага проверки, а не на фиксированное время:
// под нагрузкой таймерная задержка истекала раньше, чем проверка успевала посмотреть на скелет.
async function raceCheck(page) {
  const state = {}
  const gate = () => {
    let release
    const promise = new Promise((resolve) => { release = resolve })
    return { promise, release }
  }
  const held = { TODAY: gate(), REST_OF_WEEK: gate() }
  const answered = { REST_OF_WEEK: gate() }
  const scenario = { name: 'race', api: workApi(owner), ready: "document.querySelectorAll('.mx-row').length > 0" }
  await call('Emulation.setDeviceMetricsOverride', { width: 375, height, deviceScaleFactor: 1, mobile: true }, page.sessionId)
  const problems = []
  const handler = async (message) => {
    if (message.method !== 'Fetch.requestPaused' || message.sessionId !== page.sessionId) return
    const { requestId, request } = message.params
    const url = new URL(request.url)
    const answer = url.hostname === 'st.max.ru' ? { status: 200, body: null, script: true } : scenario.api(url, request, state)
    const due = url.pathname === '/api/max/app/work' ? url.searchParams.get('due') : null
    if (held[due]) await held[due].promise
    await call('Fetch.fulfillRequest', {
      requestId, responseCode: answer.status,
      responseHeaders: [{ name: 'Content-Type', value: answer.script ? 'text/javascript' : 'application/json' }],
      body: Buffer.from(answer.script ? bridgeScript : JSON.stringify(answer.body)).toString('base64')
    }, page.sessionId).catch(() => null)
    answered[due]?.release()
  }
  listeners.add(handler)
  try {
    await call('Fetch.enable', { patterns: [{ urlPattern: 'https://st.max.ru/*' }, { urlPattern: '*/api/max/app/*' }] }, page.sessionId)
    await call('Page.navigate', { url: `${origin}/max.html` }, page.sessionId)
    await page.waitFor(scenario.ready, 'race: экран не готов')
    await page.evaluate(click('Сегодня', '.mx-tile'))
    await page.waitFor("document.querySelector('.mx-tile[aria-pressed=\"true\"]')?.dataset.group === 'today'", 'race: «Сегодня» не выбрано')
    const during = await page.evaluate("({ empty: Boolean(document.querySelector('.mx-empty')), skeleton: Boolean(document.querySelector('.mx-row--skeleton')), rows: document.querySelectorAll('.mx-row:not(.mx-row--skeleton)').length })")
    if (during.empty || !during.skeleton || during.rows > 0) problems.push('во время загрузки не скелет: ' + JSON.stringify(during))
    await page.evaluate(click('На неделе', '.mx-tile'))
    await page.evaluate(click('Позже', '.mx-tile'))
    await page.waitFor("document.querySelector('[data-group-list=\"later\"]')?.innerText.includes('Подготовить отчёт')", 'race: «Позже» не показано')
    // Теперь отпускаем запоздалые ответы и ждём, пока «На неделе» дойдёт до страницы и она дорисуется.
    held.REST_OF_WEEK.release()
    held.TODAY.release()
    let timer
    await Promise.race([answered.REST_OF_WEEK.promise,
      new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('race: запрос «На неделе» не пришёл')), waitMs) })])
      .finally(() => clearTimeout(timer))
    await page.evaluate(settled)
    const after = await page.evaluate("({ list: document.querySelector('[data-group-list]')?.dataset.groupList, pressed: document.querySelector('.mx-tile[aria-pressed=\"true\"]')?.dataset.group, text: document.querySelector('.mx-list')?.innerText ?? '' })")
    if (after.list !== 'later' || after.pressed !== 'later' || !after.text.includes('Подготовить отчёт')) problems.push('после быстрого переключения: ' + JSON.stringify({ list: after.list, pressed: after.pressed }))
    // «Назад» из карточки: тот же список без повторной загрузки
    const before = state.workCalls
    await page.evaluate("document.querySelector('.mx-row__main').click()")
    await page.waitFor(visibleText('Следующий шаг'), 'race: карточка не открылась')
    await page.evaluate('window.__bridge.back()')
    await page.waitFor("document.querySelector('[data-group-list=\"later\"]')?.offsetParent !== null", 'race: «Назад» не вернул список')
    if (before !== state.workCalls) problems.push('«Назад» загрузил список заново')
  } finally {
    listeners.delete(handler)
    await call('Fetch.disable', {}, page.sessionId).catch(() => null)
  }
  return problems
}

// ---------- Запуск ----------
if (!fs.existsSync(path.join(dist, 'max.html'))) {
  console.error('Нет frontend/dist/max.html: сначала cd frontend && npm run build')
  process.exit(2)
}
if (!chromePath) {
  console.error('Не найден Chrome: задайте CHROME=<путь к chrome.exe или msedge.exe>')
  process.exit(2)
}
fs.mkdirSync(out, { recursive: true })
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const origin = `http://127.0.0.1:${server.address().port}`
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'max-ui-shots-'))
const chrome = spawn(chromePath, ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--disable-extensions', 'about:blank'], { stdio: 'ignore' })
let failures = 0
try {
  let port = null
  for (let attempt = 0; attempt < 100 && port === null; attempt += 1) {
    const file = path.join(profile, 'DevToolsActivePort')
    if (fs.existsSync(file)) port = fs.readFileSync(file, 'utf8').split('\n')[0].trim() || null
    if (port === null) await pause(100)
  }
  if (port === null) throw new Error('Chrome не открыл порт отладки')
  const version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json()
  socket = await new Promise((resolve, reject) => {
    const value = new WebSocket(version.webSocketDebuggerUrl)
    value.addEventListener('open', () => resolve(value), { once: true })
    value.addEventListener('error', () => reject(new Error('CDP недоступен')), { once: true })
  })
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.id && pending.has(message.id)) {
      const deferred = pending.get(message.id)
      pending.delete(message.id)
      if (message.error) deferred.reject(new Error(message.error.message))
      else deferred.resolve(message.result)
      return
    }
    listeners.forEach((listener) => listener(message))
  })
  const report = []
  for (const scenario of scenarios) {
    for (const width of widths) {
      for (const theme of themes) {
        const page = await openPage()
        try {
          const result = await run(scenario, width, theme, page)
          report.push({ shot: `${scenario.name}-${width}-${theme}`, problems: result.problems })
          if (result.problems.length > 0) failures += 1
        } catch (error) {
          failures += 1
          report.push({ shot: `${scenario.name}-${width}-${theme}`, problems: [error.message] })
        } finally {
          await call('Target.disposeBrowserContext', { browserContextId: page.contextId }).catch(() => null)
        }
      }
    }
  }
  const page = await openPage()
  const race = await raceCheck(page).catch((error) => [error.message])
  await call('Target.disposeBrowserContext', { browserContextId: page.contextId }).catch(() => null)
  if (race.length > 0) failures += 1
  report.push({ shot: 'race-and-back (без снимка)', problems: race })
  for (const line of report) console.log(`${line.problems.length === 0 ? 'OK  ' : 'FAIL'} ${line.shot}${line.problems.length ? ' — ' + line.problems.join('; ') : ''}`)
  console.log(`Снимков: ${report.length - 1}, с замечаниями: ${failures}. Каталог: ${out}`)
} finally {
  socket?.close()
  chrome.kill()
  server.close()
  await pause(300)
  fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 })
}
process.exitCode = failures > 0 ? 1 : 0
