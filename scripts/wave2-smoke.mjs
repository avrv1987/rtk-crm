import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'
import { randomUUID } from 'node:crypto'

let socket
let password
let origin
let phase = 'startup'
let nextId = 1
const pending = new Map()
const pages = []

const call = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
  const id = nextId++
  pending.set(id, { resolve, reject })
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
})

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

const assert = (condition, message) => {
  if (!condition) {
    throw new Error(message)
  }
}

const connectCdp = async () => {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const response = await fetch('http://127.0.0.1:9333/json/version').catch(() => null)
    if (response?.ok) {
      return response.json()
    }
    await pause(100)
  }
  throw new Error('CDP endpoint is unavailable')
}

async function openPage(url) {
  const context = await call('Target.createBrowserContext')
  const target = await call('Target.createTarget', { url: 'about:blank', browserContextId: context.browserContextId })
  const attached = await call('Target.attachToTarget', { targetId: target.targetId, flatten: true })
  const sessionId = attached.sessionId
  const evaluate = async (expression) => {
    const result = await call('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
      userGesture: true
    }, sessionId)
    if (result.exceptionDetails) {
      throw new Error('Browser evaluation failed: ' + (result.exceptionDetails.exception?.description ?? result.exceptionDetails.text))
    }
    return result.result.value
  }
  const waitFor = async (predicate, message, attempts = 100) => {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      if (await predicate()) {
        return
      }
      await pause(150)
    }
    throw new Error(message)
  }
  const page = { contextId: context.browserContextId, sessionId, evaluate, waitFor }
  pages.push(page)
  await call('Page.navigate', { url }, sessionId)
  return page
}

const loginButtonVisible = "Boolean([...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')))"
const keycloakFormVisible = "Boolean(document.querySelector('#username') && document.querySelector('#password') && document.querySelector('#kc-login'))"

async function login(username) {
  const page = await openPage(origin + '/')
  await page.waitFor(() => page.evaluate(loginButtonVisible), 'Login button did not appear')
  await page.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')).click()")
  await page.waitFor(() => page.evaluate(keycloakFormVisible), 'Keycloak form did not appear')
  await page.evaluate(`(() => {
    const usernameField = document.querySelector('#username')
    const passwordField = document.querySelector('#password')
    usernameField.value = ${JSON.stringify(username)}
    passwordField.value = ${JSON.stringify(password)}
    usernameField.dispatchEvent(new Event('input', { bubbles: true }))
    passwordField.dispatchEvent(new Event('input', { bubbles: true }))
    document.querySelector('#kc-login').click()
  })()`)
  await page.waitFor(
    () => page.evaluate(`location.origin === ${JSON.stringify(origin)} && !location.pathname.startsWith('/idp/')`),
    'OIDC callback did not return to CRM'
  )
  return page
}

const api = (page, method, url, options = {}) => page.evaluate(`(async () => {
  const options = ${JSON.stringify(options)}
  const method = ${JSON.stringify(method)}
  const headers = {}
  if (method !== 'GET') {
    const csrf = await (await fetch('/api/csrf')).json()
    headers[csrf.headerName] = csrf.token
  }
  if (options.key) headers['Idempotency-Key'] = options.key
  let body
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }
  const response = await fetch(${JSON.stringify(url)}, { method, headers, body })
  const type = response.headers.get('content-type') || ''
  let json = null
  let base64 = null
  if (options.binary && response.ok) {
    const bytes = new Uint8Array(await response.arrayBuffer())
    let text = ''
    for (let index = 0; index < bytes.length; index += 0x8000) {
      text += String.fromCharCode(...bytes.subarray(index, index + 0x8000))
    }
    base64 = btoa(text)
  } else if (type.includes('json')) {
    json = await response.json()
  }
  return { status: response.status, body: json, base64 }
})()`)

const requireStatus = (response, status, message) => {
  if (response.status !== status) {
    throw new Error(message + ': ' + response.status + ' ' + (response.body?.code ?? ''))
  }
  return response.body
}

const russian = (text) => typeof text === 'string' && /[А-Яа-яЁё]/.test(text) && !/[A-Za-z]{3,} [A-Za-z]{3,} [A-Za-z]{3,}/.test(text)

const requireRussianError = (response, status, code, message) => {
  assert(response.status === status && response.body?.code === code, message + ': ' + response.status + ' ' + (response.body?.code ?? ''))
  assert(russian(response.body.message), message + ': message is not Russian')
  Object.values(response.body.fieldErrors ?? {}).forEach((value) => assert(russian(value), message + ': field error is not Russian'))
  return response.body.message
}

const moscowDate = (date) => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow' }).format(date)

const endOfMoscowWeek = () => {
  const today = moscowDate(new Date())
  const weekday = new Date(today + 'T00:00:00Z').getUTCDay()
  const daysLeft = (7 - weekday) % 7
  return new Date(Date.parse(today + 'T23:59:00+03:00') + daysLeft * 86400000)
}

const listAll = async (page, url, message) => {
  const items = []
  for (let pageNumber = 0; ; pageNumber += 1) {
    const separator = url.includes('?') ? '&' : '?'
    const body = requireStatus(await api(page, 'GET', url + separator + 'page=' + pageNumber + '&size=100'), 200, message)
    items.push(...body.items)
    if (items.length >= body.total || body.items.length === 0) {
      return items
    }
  }
}

const zipText = (buffer) => {
  let end = buffer.length - 22
  while (end >= 0 && buffer.readUInt32LE(end) !== 0x06054b50) {
    end -= 1
  }
  assert(end >= 0, 'ZIP end of central directory is absent')
  const count = buffer.readUInt16LE(end + 10)
  let offset = buffer.readUInt32LE(end + 16)
  let text = ''
  for (let index = 0; index < count; index += 1) {
    const method = buffer.readUInt16LE(offset + 10)
    const compressedSize = buffer.readUInt32LE(offset + 20)
    const nameLength = buffer.readUInt16LE(offset + 28)
    const extraLength = buffer.readUInt16LE(offset + 30)
    const commentLength = buffer.readUInt16LE(offset + 32)
    const localOffset = buffer.readUInt32LE(offset + 42)
    const dataStart = localOffset + 30 + buffer.readUInt16LE(localOffset + 26) + buffer.readUInt16LE(localOffset + 28)
    const data = buffer.subarray(dataStart, dataStart + compressedSize)
    text += (method === 8 ? zlib.inflateRawSync(data) : data).toString('utf8') + '\n'
    offset += 46 + nameLength + extraLength + commentLength
  }
  return text.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
}

async function waitForJob(page, jobId) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const job = requireStatus(await api(page, 'GET', '/api/report-jobs/' + jobId), 200, 'Report job is unavailable')
    if (job.status === 'SUCCEEDED' || job.status === 'FAILED') {
      return job
    }
    await pause(250)
  }
  throw new Error('Report job did not finish')
}

async function orderFile(page, request, label) {
  const created = requireStatus(
    await api(page, 'POST', '/api/reports', { body: request, key: 'wave2-smoke-' + label + '-' + randomUUID() }),
    202,
    'Report order failed'
  )
  const job = await waitForJob(page, created.jobId)
  assert(job.status === 'SUCCEEDED', 'Report job failed: ' + (job.error?.code ?? ''))
  const download = await api(page, 'GET', '/api/report-jobs/' + created.jobId + '/result', { binary: true })
  assert(download.status === 200 && download.base64, 'Report download failed: ' + download.status)
  return { jobId: created.jobId, buffer: Buffer.from(download.base64, 'base64') }
}

async function syncSource(admin, source) {
  const created = requireStatus(await api(admin, 'POST', '/api/admin/sources/' + source + '/sync'), 202, source + ' sync was not accepted')
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const sources = requireStatus(await api(admin, 'GET', '/api/admin/sources'), 200, 'Sources are unavailable')
    const run = sources.find((item) => item.source === source)?.lastRun
    if (run?.id === created.runId && (run.status === 'SUCCEEDED' || run.status === 'FAILED')) {
      return run
    }
    await pause(250)
  }
  throw new Error(source + ' sync did not finish')
}

const eventsOf = (page, interactionId) => api(page, 'GET', '/api/interactions/' + interactionId + '/events')

const lmsEvents = (events) => events.filter((event) => event.type === 'COMMENTED' && event.comment?.startsWith('Данные LMS: курс'))

const sameSnapshot = (snapshot, expected) => Object.entries(expected).every(([key, value]) => snapshot[key] === value)

const viewports = [
  { width: 1440, height: 900, mobile: false },
  { width: 390, height: 844, mobile: true },
  { width: 360, height: 780, mobile: true }
]

const pressKey = async (page, key, code, keyCode) => {
  for (const type of ['keyDown', 'keyUp']) {
    await call('Input.dispatchKeyEvent', { type, key, code, windowsVirtualKeyCode: keyCode, text: type === 'keyDown' && key === 'Enter' ? String.fromCharCode(13) : undefined }, page.sessionId)
  }
}

const setViewport = (page, viewport) => call('Emulation.setDeviceMetricsOverride', {
  width: viewport.width,
  height: viewport.height,
  deviceScaleFactor: 1,
  mobile: viewport.mobile
}, page.sessionId)

async function captureViewports(page, name, ready, outputDirectory, focus) {
  const result = {}
  for (const viewport of viewports) {
    await setViewport(page, viewport)
    await pause(400)
    await page.waitFor(() => page.evaluate(ready), name + ' is not ready at ' + viewport.width)
    if (focus) {
      await page.evaluate(`document.querySelector(${JSON.stringify(focus)})?.scrollIntoView({ block: 'start' })`)
      await pause(200)
    }
    const overflow = await page.evaluate('document.documentElement.scrollWidth - document.documentElement.clientWidth')
    assert(overflow <= 0, name + ' scrolls horizontally at ' + viewport.width + ' px')
    const shot = await call('Page.captureScreenshot', { format: 'png' }, page.sessionId)
    fs.writeFileSync(path.join(outputDirectory, name + '-' + viewport.width + '.png'), Buffer.from(shot.data, 'base64'))
    result[viewport.width] = { overflow }
  }
  await call('Emulation.clearDeviceMetricsOverride', {}, page.sessionId)
  return result
}

const javaCourse = { courseName: 'Демо: Java-разработчик', groupId: null, participants: 6, completed: 3, notCompleted: 3, unknown: 0, teachers: 1, groupsCount: 2 }
const dataGroup = { courseName: 'Демо: анализ данных', groupName: 'Поток Б1', participants: 4, completed: null, notCompleted: null, unknown: 4, teachers: 0, groupsCount: 0 }
const shiftDay = (date, days) => {
  const value = new Date(date + 'T00:00:00Z')
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}

try {
  const environment = Object.fromEntries(
    fs.readFileSync(new URL('../.env.local', import.meta.url), 'utf8')
      .split(/\r?\n/)
      .filter((line) => line.includes('=') && !line.trimStart().startsWith('#'))
      .map((line) => {
        const index = line.indexOf('=')
        return [line.slice(0, index), line.slice(index + 1)]
      })
  )
  password = environment.DEMO_USER_PASSWORD
  origin = (environment.PUBLIC_ORIGIN || 'http://rtk.localhost:8081').replace(/\/$/, '')
  assert(password, 'Demo password is unavailable')
  assert(environment.MOODLE_BASE_URL === 'http://moodle:8080', 'Demo Moodle is not configured; run scripts/bootstrap-demo.sh with DEMO_LMS=true')
  const outputDirectory = process.env.WAVE2_SMOKE_DIR || fs.mkdtempSync(path.join(os.tmpdir(), 'rtk-wave2-smoke-'))
  fs.mkdirSync(outputDirectory, { recursive: true })

  const browser = await connectCdp()
  socket = await new Promise((resolve, reject) => {
    const value = new WebSocket(browser.webSocketDebuggerUrl)
    value.addEventListener('open', () => resolve(value), { once: true })
    value.addEventListener('error', () => reject(new Error('CDP connection failed')), { once: true })
  })
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (!message.id || !pending.has(message.id)) {
      return
    }
    const deferred = pending.get(message.id)
    pending.delete(message.id)
    if (message.error) {
      deferred.reject(new Error('CDP request failed: ' + message.error.message))
      return
    }
    deferred.resolve(message.result)
  })
  const result = {}
  const nonce = Date.now().toString(36) + randomUUID().slice(0, 6)
  const today = moscowDate(new Date())

  phase = 'login'
  const kamA = await login('kam-a')
  const kamB = await login('kam-b')
  const kamC = await login('kam-c')
  const leader = await login('leader')
  const admin = await login('admin')
  const unprofiled = await login('unprofiled')

  phase = 'pending-profile'
  const pendingMe = await api(unprofiled, 'GET', '/api/me')
  const pendingData = await api(unprofiled, 'GET', '/api/organizations?page=0&size=10')
  requireRussianError(pendingMe, 403, 'CRM_PROFILE_PENDING', 'Unprofiled user is not pending')
  requireRussianError(pendingData, 403, 'CRM_PROFILE_PENDING', 'Pending profile reached business data')
  await unprofiled.waitFor(
    () => unprofiled.evaluate("document.body.innerText.includes('Профиль ожидает активации администратором')"),
    'Pending screen is not shown'
  )
  const pendingProfiles = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles?page=0&size=100&pending=true'), 200, 'Pending profiles are unavailable')
  const pendingProfile = pendingProfiles.items.find((item) => item.displayName.includes('Без профиля CRM'))
  assert(pendingProfile?.pendingActivation && !pendingProfile.active, 'Administrator does not see the pending profile')
  result.pending = { me: pendingMe.status, code: pendingMe.body.code, data: pendingData.status, listedForAdmin: true, screen: true }

  phase = 'setup'
  const organizationsA = await listAll(kamA, '/api/organizations?sort=name,asc', 'KAM A organizations are unavailable')
  const universityA = organizationsA.find((item) => item.name === 'Университет А')
  assert(universityA, 'KAM A does not see University A')
  const organizationsB = await listAll(kamB, '/api/organizations?sort=name,asc', 'KAM B organizations are unavailable')
  const universityB = organizationsB.find((item) => item.name === 'Университет Б')
  assert(universityB, 'KAM B does not see University B')
  const leaderOrganizations = await listAll(leader, '/api/organizations?sort=name,asc', 'Leader organizations are unavailable')
  const universityC = leaderOrganizations.find((item) => item.name.startsWith('Университет C'))
  assert(universityC && !leaderOrganizations.some((item) => item.id === universityB.id), 'Leader scope is wrong')
  const interactionsA = await listAll(kamA, '/api/interactions?organizationId=' + universityA.id, 'Interactions of University A are unavailable')
  const demoInteraction = interactionsA.find((item) => item.title === 'Демо: внедрение цифрового университета')
  assert(demoInteraction, 'Demo interaction is unavailable')
  const foreign = requireStatus(
    await api(kamB, 'POST', '/api/interactions', { body: { organizationId: universityB.id, title: 'Волна-2 чужое ' + nonce, contactIds: [] }, key: 'wave2-foreign-' + nonce }),
    201,
    'Foreign interaction creation failed'
  )

  phase = 'organization-search'
  const lowerSearch = await listAll(kamA, '/api/organizations?q=' + encodeURIComponent('университет а'), 'Lower-case search failed')
  const upperSearch = await listAll(kamA, '/api/organizations?q=' + encodeURIComponent('УНИВЕРСИТЕТ А'), 'Upper-case search failed')
  const leaderSearch = await listAll(leader, '/api/organizations?q=' + encodeURIComponent('ТРЕБУЕТ НАЗНАЧЕНИЯ'), 'Leader search failed')
  assert(lowerSearch.some((item) => item.id === universityA.id) && upperSearch.some((item) => item.id === universityA.id), 'Cyrillic organization search depends on case')
  assert(!upperSearch.some((item) => item.id === universityB.id), 'Organization search leaks another scope')
  assert(leaderSearch.length === 1 && leaderSearch[0].id === universityC.id, 'Leader Cyrillic search failed')
  result.organizationSearch = { lower: lowerSearch.length, upper: upperSearch.length, leader: leaderSearch.length }

  phase = 'leader-cards'
  const leaderCard = requireStatus(
    await api(leader, 'POST', '/api/interactions', { body: { organizationId: universityA.id, title: 'Волна-2 руководитель ' + nonce, contactIds: [] }, key: 'wave2-leader-create-' + nonce }),
    201,
    'Leader could not create a team card'
  )
  const leaderTarget = leaderCard.allowedTransitions.find((option) => !option.commentRequired)
  assert(leaderTarget, 'Leader card has no transition without a comment')
  const leaderMoved = requireStatus(
    await api(leader, 'POST', '/api/interactions/' + leaderCard.id + '/transitions', { body: { version: leaderCard.version, toStageId: leaderTarget.stageId, comment: null }, key: 'wave2-leader-move-' + nonce }),
    200,
    'Leader could not move a team card'
  )
  requireStatus(
    await api(leader, 'POST', '/api/interactions/' + leaderCard.id + '/comments', { body: { version: leaderMoved.version, stageId: leaderMoved.currentStageId, text: 'Волна-2 комментарий руководителя' }, key: 'wave2-leader-comment-' + nonce }),
    200,
    'Leader could not comment a team card'
  )
  const kamOnLeaderCard = requireStatus(await api(kamA, 'GET', '/api/interactions/' + leaderCard.id), 200, 'KAM A does not see the team card')
  const leaderForeignRead = await api(leader, 'GET', '/api/interactions/' + foreign.id)
  const leaderForeignMove = await api(leader, 'POST', '/api/interactions/' + foreign.id + '/transitions', {
    body: { version: foreign.version, toStageId: foreign.allowedTransitions[0]?.stageId ?? foreign.currentStageId, comment: 'Чужая карточка' },
    key: 'wave2-leader-foreign-' + nonce
  })
  const leaderForeignComment = await api(leader, 'POST', '/api/interactions/' + foreign.id + '/comments', {
    body: { version: foreign.version, stageId: foreign.currentStageId, text: 'Чужая карточка' },
    key: 'wave2-leader-foreign-comment-' + nonce
  })
  assert(leaderForeignRead.status === 404 && leaderForeignMove.status === 404 && leaderForeignComment.status === 404, 'Leader reached a foreign card')
  assert(kamOnLeaderCard.currentStageId === leaderTarget.stageId, 'Leader transition is not visible to the KAM')
  result.leaderCards = { create: 201, transition: 200, comment: 200, foreignRead: 404, foreignTransition: 404, foreignComment: 404 }

  phase = 'my-work'
  const overdueTitle = 'Волна-2 просрочено ' + nonce
  const weekTitle = 'Волна-2 неделя ' + nonce
  const weekDue = endOfMoscowWeek()
  assert(weekDue.getTime() > Date.now() + 60000, 'The Moscow week ends too soon for the THIS_WEEK check')
  const overdueCard = requireStatus(
    await api(kamA, 'POST', '/api/interactions', { body: { organizationId: universityA.id, title: overdueTitle, contactIds: [] }, key: 'wave2-overdue-' + nonce }),
    201,
    'Overdue card creation failed'
  )
  requireStatus(
    await api(kamA, 'PATCH', '/api/interactions/' + overdueCard.id, {
      body: { version: overdueCard.version, nextAction: 'Позвонить проректору', nextActionAt: new Date(Date.now() - 2 * 86400000).toISOString() },
      key: 'wave2-overdue-plan-' + nonce
    }),
    200,
    'Overdue plan failed'
  )
  const weekCard = requireStatus(
    await api(kamA, 'POST', '/api/interactions', {
      body: { organizationId: universityA.id, title: weekTitle, contactIds: [], nextAction: 'Отправить предложение', nextActionAt: weekDue.toISOString() },
      key: 'wave2-week-' + nonce
    }),
    201,
    'This week card creation failed'
  )
  const search = encodeURIComponent(nonce.toUpperCase())
  const cyrillic = encodeURIComponent('ПРОСРОЧЕНО ' + nonce.toUpperCase())
  const work = {}
  for (const [name, page] of [['kamA', kamA], ['leader', leader], ['kamB', kamB]]) {
    const ids = async (query, label) => (await listAll(page, '/api/interactions?' + query, label + ' failed')).map((item) => item.id)
    work[name] = {
      all: await ids('q=' + search, 'My work search'),
      cyrillic: await ids('q=' + cyrillic, 'Cyrillic search'),
      overdue: await ids('due=OVERDUE&q=' + search, 'Overdue filter'),
      week: await ids('due=THIS_WEEK&q=' + search, 'This week filter')
    }
  }
  for (const name of ['kamA', 'leader']) {
    assert(work[name].all.includes(overdueCard.id) && work[name].all.includes(weekCard.id), name + ' search misses own cards')
    assert(work[name].cyrillic.length === 1 && work[name].cyrillic[0] === overdueCard.id, name + ' Cyrillic search depends on case')
    assert(work[name].overdue.includes(overdueCard.id) && !work[name].overdue.includes(weekCard.id), name + ' overdue filter is wrong')
    assert(work[name].week.includes(weekCard.id), name + ' this week filter is wrong')
  }
  assert(!work.kamA.all.includes(foreign.id) && !work.leader.all.includes(foreign.id), 'My work leaks a foreign card')
  assert(work.kamB.all.length === 1 && work.kamB.all[0] === foreign.id, 'KAM B sees team A cards in My work')
  result.myWork = {
    kamA: { all: work.kamA.all.length, overdue: work.kamA.overdue.length, week: work.kamA.week.length },
    leader: { all: work.leader.all.length, overdue: work.leader.overdue.length, week: work.leader.week.length },
    kamB: { all: work.kamB.all.length }
  }

  phase = 'russian-errors'
  const errors = {
    validation: requireRussianError(
      await api(kamA, 'POST', '/api/interactions', { body: { organizationId: universityA.id, title: '', contactIds: [] }, key: 'wave2-invalid-' + nonce }),
      400, 'VALIDATION_ERROR', 'Empty title'
    ),
    badUuid: requireRussianError(await api(kamA, 'GET', '/api/interactions/not-a-uuid'), 400, 'VALIDATION_ERROR', 'Malformed id'),
    notFound: requireRussianError(await api(kamA, 'GET', '/api/interactions/' + randomUUID()), 404, 'NOT_FOUND', 'Unknown card'),
    stale: requireRussianError(
      await api(kamA, 'PATCH', '/api/interactions/' + overdueCard.id, { body: { version: overdueCard.version, nextAction: 'Устаревшая версия' }, key: 'wave2-stale-' + nonce }),
      409, 'VERSION_CONFLICT', 'Stale plan'
    ),
    unknownTeam: requireRussianError(
      await api(admin, 'PATCH', '/api/admin/teams/' + randomUUID(), { body: { version: 0, name: 'Команда ' + nonce }, key: 'wave2-team-' + nonce }),
      404, 'NOT_FOUND', 'Unknown team'
    ),
    pending: pendingMe.body.message
  }
  result.russianErrors = Object.keys(errors)

  phase = 'sources'
  const sourcesBefore = requireStatus(await api(admin, 'GET', '/api/admin/sources'), 200, 'Sources are unavailable')
  for (const source of ['WEBSITE', 'MOODLE']) {
    const view = sourcesBefore.find((item) => item.source === source)
    assert(view?.configured && view.lastSuccessAt, source + ' is not configured or never synchronized')
  }
  const demoEventsBefore = requireStatus(await eventsOf(kamA, demoInteraction.id), 200, 'Demo history is unavailable').length
  const website = await syncSource(admin, 'WEBSITE')
  const moodle = await syncSource(admin, 'MOODLE')
  assert(website.status === 'SUCCEEDED' && website.fetchedCount === 16 && website.createdCount === 0 && website.updatedCount === 0 && website.failedCount === 0, 'Website repeat sync changed data')
  assert(moodle.status === 'SUCCEEDED' && moodle.fetchedCount === 5 && moodle.createdCount === 0 && moodle.updatedCount === 0 && moodle.failedCount === 0, 'Moodle repeat sync changed data')
  const demoEventsAfter = requireStatus(await eventsOf(kamA, demoInteraction.id), 200, 'Demo history is unavailable').length
  assert(demoEventsAfter === demoEventsBefore, 'Repeat sync added events')
  const counters = (run) => [run.fetchedCount, run.createdCount, run.updatedCount, run.skippedCount, run.needsMappingCount, run.failedCount]
  result.sources = { website: counters(website), moodle: counters(moodle), eventsAdded: demoEventsAfter - demoEventsBefore }

  phase = 'needs-mapping'
  const problems = requireStatus(await api(admin, 'GET', '/api/admin/source-records'), 200, 'Problem records are unavailable')
  assert(problems.some((record) => record.externalId === 'pr-demo-006' && record.status === 'NEEDS_MAPPING'), 'Demo record needing mapping is not visible')
  const universityD = problems.find((record) => record.externalId === 'pr-demo-004' && record.status === 'NEEDS_MAPPING')
  if (universityD) {
    const options = requireStatus(await api(admin, 'GET', '/api/admin/source-mapping-options'), 200, 'Mapping options are unavailable')
    const target = options.organizations.find((item) => item.name.startsWith('Университет C'))
    const applied = requireStatus(
      await api(admin, 'POST', '/api/admin/source-records/' + universityD.id + '/apply', { body: { organizationId: target.id, programId: null } }),
      200,
      'Mapping was not applied'
    )
    assert(applied.record.status === 'APPLIED' && applied.reappliedCount >= 1, 'Mapping did not apply the neighbour record')
    const afterMapping = requireStatus(await api(admin, 'GET', '/api/admin/source-records'), 200, 'Problem records are unavailable')
    assert(!afterMapping.some((record) => ['pr-demo-004', 'la-demo-107'].includes(record.externalId)), 'Mapped records are still problems')
    const repeat = await syncSource(admin, 'WEBSITE')
    assert(repeat.createdCount === 0 && repeat.updatedCount === 0, 'Sync after mapping duplicated records')
    result.needsMapping = { applied: applied.record.status, reapplied: applied.reappliedCount, repeatCreated: repeat.createdCount }
  } else {
    assert(!problems.some((record) => ['pr-demo-004', 'la-demo-107'].includes(record.externalId)), 'University D records are in an unexpected state')
    result.needsMapping = { applied: 'earlier run' }
  }

  phase = 'lms-card'
  const snapshotsA = requireStatus(await api(kamA, 'GET', '/api/interactions/' + demoInteraction.id + '/learning-snapshots'), 200, 'LMS block of KAM A is unavailable')
  assert(snapshotsA.length === 1 && sameSnapshot(snapshotsA[0], javaCourse), 'Moodle numbers of the Java course differ from the demo data')
  const demoHistory = requireStatus(await eventsOf(kamA, demoInteraction.id), 200, 'Demo history is unavailable')
  const lmsHistory = lmsEvents(demoHistory)
  assert(lmsHistory.length >= 1 && lmsHistory.at(-1).comment.includes('обучающихся 6, завершили 3, не завершили 3, статус неизвестен 0, групп 2, преподавателей 1'), 'LMS event is absent in the history of KAM A')
  const interactionsB = await listAll(kamB, '/api/interactions?organizationId=' + universityB.id, 'Interactions of University B are unavailable')
  const learningB = interactionsB.find((item) => item.title === 'Обучение в LMS: Демо: анализ данных')
  assert(learningB, 'Moodle did not create the University B interaction')
  const snapshotsB = requireStatus(await api(kamB, 'GET', '/api/interactions/' + learningB.id + '/learning-snapshots'), 200, 'LMS block of KAM B is unavailable')
  assert(snapshotsB.length === 1 && sameSnapshot(snapshotsB[0], dataGroup), 'Moodle numbers of the data group differ from the demo data')
  for (const snapshot of [snapshotsA[0], snapshotsB[0]]) {
    assert(snapshot.runStartsOn && snapshot.runStartsOn <= today && today < snapshot.runEndsOn, 'Demo training run has no dates or is not active today')
  }
  const foreignSnapshots = await api(kamA, 'GET', '/api/interactions/' + learningB.id + '/learning-snapshots')
  const foreignRefresh = await api(kamA, 'POST', '/api/interactions/' + learningB.id + '/learning-snapshots/sync')
  const adminRefresh = await api(admin, 'POST', '/api/interactions/' + demoInteraction.id + '/learning-snapshots/sync')
  assert(foreignSnapshots.status === 404 && foreignRefresh.status === 404 && adminRefresh.status === 404, 'LMS data of another scope is reachable')
  const refreshed = requireStatus(await api(kamA, 'POST', '/api/interactions/' + demoInteraction.id + '/learning-snapshots/sync'), 200, 'KAM A could not refresh LMS data')
  assert(refreshed.run.status === 'SUCCEEDED' && refreshed.run.fetchedCount === 1 && refreshed.run.createdCount === 0 && refreshed.run.updatedCount === 0, 'Scoped LMS refresh changed data')
  assert(refreshed.snapshots.length === 1 && sameSnapshot(refreshed.snapshots[0], javaCourse), 'Scoped LMS refresh returned other numbers')
  const leaderRefresh = requireStatus(await api(leader, 'POST', '/api/interactions/' + demoInteraction.id + '/learning-snapshots/sync'), 200, 'Leader could not refresh LMS data')
  const unmapped = await api(kamA, 'POST', '/api/interactions/' + weekCard.id + '/learning-snapshots/sync')
  errors.lmsNotMapped = requireRussianError(unmapped, 409, 'LMS_NOT_MAPPED', 'Card without mapping')
  const historyAfterRefresh = requireStatus(await eventsOf(kamA, demoInteraction.id), 200, 'Demo history is unavailable')
  assert(lmsEvents(historyAfterRefresh).length === lmsHistory.length, 'Unchanged LMS refresh added an event')
  result.lms = {
    kamA: snapshotsA[0],
    kamB: snapshotsB[0],
    lmsEvents: lmsHistory.length,
    foreign: [foreignSnapshots.status, foreignRefresh.status, adminRefresh.status],
    refresh: counters(refreshed.run),
    leaderRefresh: leaderRefresh.run.status,
    unmapped: unmapped.status
  }

  phase = 'demand'
  const demandRequest = { kind: 'DEMAND', from: '2026-01-01', to: today, filters: {}, columns: [] }
  const demandA = requireStatus(await api(kamA, 'POST', '/api/reports/preview?page=0&size=200', { body: demandRequest }), 200, 'DEMAND preview of KAM A failed')
  const demandB = requireStatus(await api(kamB, 'POST', '/api/reports/preview?page=0&size=200', { body: demandRequest }), 200, 'DEMAND preview of KAM B failed')
  const digital = demandA.items.find((row) => row.PROGRAM === 'Демо-программа: цифровой университет')
  const analysis = demandB.items.find((row) => row.PROGRAM === 'Демо-программа: анализ данных')
  assert(digital?.PARTICIPANTS === 6 && digital.PARALLEL_RUNS === 1 && digital.APPLICATIONS > 0, 'DEMAND row of KAM A has wrong Moodle numbers')
  assert(analysis?.PARTICIPANTS === 4 && analysis.PARALLEL_RUNS === 1 && analysis.APPLICATIONS === 4, 'DEMAND row of KAM B has wrong numbers')
  assert(!demandA.items.some((row) => row.PROGRAM === 'Демо-программа: анализ данных'), 'DEMAND of KAM A contains another scope')
  assert(demandA.notes.some((note) => note.includes('параллельные потоки — потоки, чей интервал [начало, окончание) содержит')), 'DEMAND notes do not explain parallel runs')
  const runOf = snapshotsA[0]
  const runsOn = async (to) => {
    const preview = requireStatus(
      await api(kamA, 'POST', '/api/reports/preview?page=0&size=200', { body: { kind: 'DEMAND', to, filters: {}, columns: [] } }),
      200,
      'DEMAND preview on a date failed'
    )
    const row = preview.items.find((item) => item.PROGRAM === 'Демо-программа: цифровой университет')
    return [row?.PARTICIPANTS, row?.PARALLEL_RUNS]
  }
  const runsByDate = {
    beforeStart: await runsOn(shiftDay(runOf.runStartsOn, -1)),
    start: await runsOn(runOf.runStartsOn),
    lastDay: await runsOn(shiftDay(runOf.runEndsOn, -1)),
    end: await runsOn(runOf.runEndsOn)
  }
  assert(JSON.stringify(runsByDate) === JSON.stringify({ beforeStart: [6, 0], start: [6, 1], lastDay: [6, 1], end: [6, 0] }), 'Parallel runs do not follow [start, end) of the training run')
  const demandStatistics = requireStatus(
    await api(kamA, 'POST', '/api/statistics', { body: { ...demandRequest, groupBy: 'PROGRAM' } }),
    200,
    'DEMAND statistics of KAM A failed'
  )
  assert(demandStatistics.total === demandA.items.reduce((sum, row) => sum + (row.APPLICATIONS ?? 0), 0), 'DEMAND statistics total differs from the preview')
  const demandJson = await orderFile(kamA, { ...demandRequest, format: 'JSON' }, 'demand-json')
  const demandXlsx = await orderFile(kamA, { ...demandRequest, format: 'XLSX' }, 'demand-xlsx')
  fs.writeFileSync(path.join(outputDirectory, 'demand-kam-a.json'), demandJson.buffer)
  fs.writeFileSync(path.join(outputDirectory, 'demand-kam-a.xlsx'), demandXlsx.buffer)
  const json = JSON.parse(demandJson.buffer.toString('utf8'))
  const jsonDigital = json.rows.find((row) => row.PROGRAM === 'Демо-программа: цифровой университет')
  assert(json.kind === 'DEMAND' && jsonDigital?.PARTICIPANTS === 6 && jsonDigital.PARALLEL_RUNS === 1, 'DEMAND JSON has wrong numbers')
  assert(json.totals.participations === 6 && !JSON.stringify(json).includes('анализ данных'), 'DEMAND JSON totals or scope are wrong')
  const xlsxText = zipText(demandXlsx.buffer)
  assert(xlsxText.includes('Демо-программа: цифровой университет') && !xlsxText.includes('анализ данных'), 'DEMAND XLSX content or scope is wrong')
  result.demand = { kamA: digital, kamB: analysis, run: [runOf.runStartsOn, runOf.runEndsOn], runsByDate, json: { rows: json.rows.length, totals: json.totals }, xlsx: demandXlsx.buffer.length }

  phase = 'role-change'
  const profiles = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles?page=0&size=100'), 200, 'CRM profiles are unavailable')
  const kamAProfile = profiles.items.find((item) => item.displayName === 'КАМ А')
  const teams = requireStatus(await api(admin, 'GET', '/api/admin/teams'), 200, 'Teams are unavailable')
  const teamA = teams.find((item) => item.name === 'team-a')
  const teamB = teams.find((item) => item.name === 'team-b')
  assert(kamAProfile?.role === 'USER' && kamAProfile.teamId === teamA?.id && teamB, 'KAM A profile is not in its demo state')
  const ownedBefore = (await listAll(kamA, '/api/organizations', 'Organizations of KAM A are unavailable')).map((item) => item.id)
  const oldReport = await orderFile(kamA, { kind: 'PORTFOLIO', from: today, to: today, filters: {}, columns: [], format: 'JSON' }, 'kam-a-before-role')
  const promoted = requireStatus(
    await api(admin, 'PATCH', '/api/admin/crm-profiles/' + kamAProfile.id, { body: { version: kamAProfile.version, role: 'LEADER', teamId: teamB.id }, key: 'wave2-promote-' + nonce }),
    200,
    'Role change failed'
  )
  const promotedMe = requireStatus(await api(kamA, 'GET', '/api/me'), 200, 'KAM A lost the session')
  const promotedOrganizations = await listAll(kamA, '/api/organizations?sort=name,asc', 'Organizations after role change are unavailable')
  const oldJob = await api(kamA, 'GET', '/api/report-jobs/' + oldReport.jobId)
  const oldResult = await api(kamA, 'GET', '/api/report-jobs/' + oldReport.jobId + '/result', { binary: true })
  assert(promotedMe.role === 'LEADER' && promotedMe.teamId === teamB.id, 'Next request does not follow the new role')
  assert(promotedOrganizations.some((item) => item.id === universityB.id) && !promotedOrganizations.some((item) => item.id === universityA.id), 'Scope did not follow the new team')
  assert(oldJob.status === 410 && oldResult.status === 410 && oldResult.body?.code === 'REPORT_ACCESS_CHANGED', 'Report of the old scope is still available')
  const requiringAssignment = await listAll(leader, '/api/organizations?requiresAssignment=true', 'Organizations requiring assignment are unavailable')
  const orphan = requiringAssignment.find((item) => item.id === universityA.id)
  assert(orphan && orphan.ownerManagerId === null, 'University A is not left to the leader as requiring assignment')
  const kamBProfile = profiles.items.find((item) => item.displayName === 'КАМ Б')
  const foreignOwner = await api(leader, 'POST', '/api/organizations/' + universityA.id + '/assignment', {
    body: { version: orphan.version, ownerManagerId: kamBProfile.id },
    key: 'wave2-assign-foreign-' + nonce
  })
  requireRussianError(foreignOwner, 400, 'VALIDATION_ERROR', 'KAM of another team was assigned')
  assert(foreignOwner.body.fieldErrors?.ownerManagerId, 'Foreign KAM assignment error is not bound to ownerManagerId')
  const restored = requireStatus(
    await api(admin, 'PATCH', '/api/admin/crm-profiles/' + kamAProfile.id, { body: { version: promoted.version, role: 'USER', teamId: teamA.id }, key: 'wave2-restore-' + nonce }),
    200,
    'Role restore failed'
  )
  result.roleChange = {
    me: promotedMe.role,
    organizations: promotedOrganizations.length,
    oldJob: oldJob.status,
    oldResult: oldResult.status,
    requiresAssignment: true,
    foreignOwner: foreignOwner.status,
    restored: restored.role
  }

  phase = 'owner-replacement'
  const optionsA = requireStatus(await api(leader, 'GET', '/api/organizations/' + universityA.id + '/assignment-options'), 200, 'Assignment options are unavailable')
  const candidateA = optionsA.find((item) => item.displayName === 'КАМ А')
  const candidateC = optionsA.find((item) => item.displayName === 'КАМ В')
  assert(candidateA && candidateC, 'Leader cannot choose KAM A and KAM C')
  const assign = async (ownerManagerId, label, organizationId = universityA.id) => {
    const current = requireStatus(await api(leader, 'GET', '/api/organizations/' + organizationId), 200, 'Organization is unavailable to the leader')
    return requireStatus(
      await api(leader, 'POST', '/api/organizations/' + organizationId + '/assignment', { body: { version: current.version, ownerManagerId }, key: 'wave2-assign-' + label + '-' + organizationId + '-' + nonce }),
      200,
      'Assignment ' + label + ' failed'
    )
  }
  for (const organizationId of ownedBefore.filter((id) => id !== universityA.id)) {
    await assign(candidateA.id, 'restore', organizationId)
  }
  await assign(candidateC.id, 'kam-c')
  const kamAWithoutOwner = await api(kamA, 'GET', '/api/organizations/' + universityA.id)
  const kamCWithOwner = await api(kamC, 'GET', '/api/organizations/' + universityA.id)
  const kamADemoCard = await api(kamA, 'GET', '/api/interactions/' + demoInteraction.id)
  const kamCDemoCard = await api(kamC, 'GET', '/api/interactions/' + demoInteraction.id)
  assert(kamAWithoutOwner.status === 404 && kamADemoCard.status === 404, 'Replaced KAM A still sees University A')
  assert(kamCWithOwner.status === 200 && kamCDemoCard.status === 200, 'KAM C does not see University A')
  await assign(candidateA.id, 'kam-a')
  const kamAAgain = await api(kamA, 'GET', '/api/organizations/' + universityA.id)
  const kamCAgain = await api(kamC, 'GET', '/api/organizations/' + universityA.id)
  assert(kamAAgain.status === 200 && kamAAgain.body.ownerManagerId === candidateA.id && kamCAgain.status === 404, 'Owner was not returned to KAM A')
  const ownedAfter = (await listAll(kamA, '/api/organizations', 'Organizations of KAM A are unavailable')).map((item) => item.id)
  assert(ownedBefore.every((id) => ownedAfter.includes(id)) && ownedAfter.length === ownedBefore.length, 'KAM A portfolio was not restored')
  result.ownerReplacement = { kamAAfter: kamAWithoutOwner.status, kamCAfter: kamCWithOwner.status, kamAReturned: kamAAgain.status, kamCReturned: kamCAgain.status }

  phase = 'team-transfer'
  const adminOrganizations = await listAll(admin, '/api/admin/organizations', 'Admin organizations are unavailable')
  const transferred = adminOrganizations.find((item) => item.id === universityC.id)
  const leaderBefore = await api(leader, 'GET', '/api/organizations/' + universityC.id)
  const moved = requireStatus(
    await api(admin, 'PATCH', '/api/admin/organizations/' + universityC.id + '/team', { body: { teamId: teamB.id, version: transferred.version }, key: 'wave2-transfer-' + nonce }),
    200,
    'Team transfer failed'
  )
  const leaderAfter = await api(leader, 'GET', '/api/organizations/' + universityC.id)
  const movedBack = requireStatus(
    await api(admin, 'PATCH', '/api/admin/organizations/' + universityC.id + '/team', { body: { teamId: teamA.id, version: moved.version }, key: 'wave2-transfer-back-' + nonce }),
    200,
    'Team transfer back failed'
  )
  const leaderReturned = await api(leader, 'GET', '/api/organizations/' + universityC.id)
  assert(leaderBefore.status === 200 && leaderAfter.status === 404 && leaderReturned.status === 200 && movedBack.teamId === teamA.id, 'Team transfer did not change visibility')
  result.teamTransfer = { before: leaderBefore.status, after: leaderAfter.status, returned: leaderReturned.status }

  phase = 'viewports'
  const cardReady = "document.body.innerText.includes('Обучение (LMS)') && document.body.innerText.includes('Демо: Java-разработчик') && document.body.innerText.includes('Поток обучения') && Boolean([...document.querySelectorAll('button')].find((button) => button.textContent.includes('Обновить данные LMS')))"
  await call('Page.navigate', { url: origin + '/#/organizations/' + universityA.id + '/' + demoInteraction.id }, kamA.sessionId)
  await kamA.waitFor(() => kamA.evaluate(cardReady), 'LMS block is not shown in the card')
  await kamA.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Обновить данные LMS')).click()")
  await kamA.waitFor(
    () => kamA.evaluate("[...document.querySelectorAll('.interaction-learning [role=status]')].some((node) => node.textContent.includes('Данные Moodle'))"),
    'LMS refresh button did not report the result'
  )
  await call('Page.navigate', { url: origin + '/#/work' }, leader.sessionId)
  await call('Page.navigate', { url: origin + '/#/admin' }, admin.sessionId)
  await call('Page.reload', {}, unprofiled.sessionId)
  result.viewports = {
    kamCard: await captureViewports(kamA, 'kam-a-card', cardReady, outputDirectory, '.interaction-learning'),
    leaderWork: await captureViewports(leader, 'leader-work', "Boolean(document.querySelector('.work-item')) && document.body.innerText.includes('Моя работа')", outputDirectory),
    adminSources: await captureViewports(admin, 'admin-sources', "document.body.innerText.includes('Источники данных') && document.body.innerText.includes('LMS Moodle')", outputDirectory, '.data-sources'),
    pending: await captureViewports(unprofiled, 'unprofiled', "document.body.innerText.includes('Профиль ожидает активации администратором')", outputDirectory)
  }

  phase = 'mobile-keyboard'
  const narrow = viewports.at(-1)
  await setViewport(kamA, narrow)
  await call('Page.reload', {}, kamA.sessionId)
  await kamA.waitFor(() => kamA.evaluate(cardReady), 'LMS block is not shown at 360 px')
  await kamA.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Обновить данные LMS')).focus()")
  assert(await kamA.evaluate("document.activeElement?.textContent.includes('Обновить данные LMS')"), 'LMS refresh button cannot take focus')
  await pressKey(kamA, 'Enter', 'Enter', 13)
  await kamA.waitFor(
    () => kamA.evaluate("[...document.querySelectorAll('.interaction-learning [role=status]')].some((node) => node.textContent.includes('Данные Moodle'))"),
    'LMS refresh by keyboard did not report the result at 360 px'
  )
  await setViewport(leader, narrow)
  await call('Page.navigate', { url: origin + '/#/work' }, leader.sessionId)
  await leader.waitFor(() => leader.evaluate("Boolean(document.querySelector('.work-filters input[type=search]'))"), 'Work filters are not shown at 360 px')
  await leader.evaluate("document.querySelector('.work-filters input[type=search]').focus()")
  await call('Input.insertText', { text: 'ПРОСРОЧЕНО ' + nonce.toUpperCase() }, leader.sessionId)
  await leader.evaluate("[...document.querySelectorAll('.work-filters input[type=radio]')].find((input) => input.value === 'OVERDUE').focus()")
  await pressKey(leader, ' ', 'Space', 32)
  await leader.waitFor(
    () => leader.evaluate(`(() => {
      const titles = [...document.querySelectorAll('.work-item__title')].map((node) => node.textContent)
      return titles.length === 1 && titles[0] === ${JSON.stringify(overdueTitle)} && location.hash.includes('due=OVERDUE')
    })()`),
    'Leader could not filter My work by keyboard at 360 px'
  )
  const leaderOverflow = await leader.evaluate('document.documentElement.scrollWidth - document.documentElement.clientWidth')
  await setViewport(admin, narrow)
  await call('Page.reload', {}, admin.sessionId)
  await admin.waitFor(() => admin.evaluate("document.body.innerText.includes('LMS Moodle') && [...document.querySelectorAll('.data-sources__item button')].length === 2"), 'Sources are not shown at 360 px')
  const lastMoodleRun = (await api(admin, 'GET', '/api/admin/sources')).body.find((item) => item.source === 'MOODLE').lastRun.id
  await admin.evaluate("[...document.querySelectorAll('.data-sources__item')].find((item) => item.textContent.includes('LMS Moodle')).querySelector('button').focus()")
  await pressKey(admin, 'Enter', 'Enter', 13)
  await admin.waitFor(async () => {
    const run = (await api(admin, 'GET', '/api/admin/sources')).body.find((item) => item.source === 'MOODLE').lastRun
    return run.id !== lastMoodleRun && run.status === 'SUCCEEDED'
  }, 'Administrator could not start the Moodle sync by keyboard at 360 px')
  for (const page of [kamA, leader, admin]) {
    await call('Emulation.clearDeviceMetricsOverride', {}, page.sessionId)
  }
  assert(leaderOverflow <= 0, 'My work scrolls horizontally at 360 px')
  result.mobileKeyboard = { lmsRefresh: true, leaderOverdueFilter: true, adminMoodleSync: true }

  result.outputDirectory = outputDirectory
  console.log(JSON.stringify(result))
} catch (error) {
  const message = error instanceof Error
    ? error.message.replace(/[^\wА-Яа-яЁё .:,/-]/g, '').slice(0, 200) || 'failed'
    : 'failed'
  console.log(JSON.stringify({ smoke: 'failed', phase, message }))
  process.exitCode = 1
} finally {
  for (const page of pages.reverse()) {
    await call('Target.disposeBrowserContext', { browserContextId: page.contextId }).catch(() => null)
  }
  socket?.close()
}
