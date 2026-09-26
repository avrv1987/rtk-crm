import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { randomUUID } from 'node:crypto'

const projectRoot = path.resolve(import.meta.dirname, '../..')
const stateDir = process.env.LOAD_STATE_DIR || path.join(projectRoot, '.load-test')
const envFile = process.argv[2] || path.join(projectRoot, '.env.local')
const durationSeconds = Number(process.env.LOAD_DURATION_S || 360)
const userLimit = Number(process.env.LOAD_USERS || 50)
const burstAt = (process.env.LOAD_REPORT_BURSTS || '90,210').split(',').filter(Boolean).map(Number)
const browserRounds = (process.env.LOAD_BROWSER_ROUNDS || '30,60,92,120,150,180,212,240,270,300').split(',').filter(Boolean).map(Number)
const thinkMin = Number(process.env.LOAD_THINK_MIN_MS || 1000)
const thinkMax = Number(process.env.LOAD_THINK_MAX_MS || 3000)
const cdpPort = Number(process.env.LOAD_CDP_PORT || 9333)
const httpOnly = process.env.LOAD_HTTP_LOGIN === '1'

const readEnv = (file) => Object.fromEntries(
  fs.readFileSync(file, 'utf8').split(/\r?\n/)
    .filter((line) => line.includes('=') && !line.trimStart().startsWith('#'))
    .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)])
)

if (process.argv[2] === '--self-test') {
  const rewrite = (absoluteUrl, internalOrigin) => {
    const target = new URL(absoluteUrl)
    const base = new URL(internalOrigin)
    target.protocol = base.protocol
    target.host = base.host
    return target.toString()
  }
  const check = (actual, expected, message) => {
    if (actual !== expected) {
      throw new Error(`${message}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`)
    }
  }
  check(
    rewrite('https://crm.example.ru/idp/realms/rtk-crm/protocol/openid-connect/auth?x=1', 'http://web:8080'),
    'http://web:8080/idp/realms/rtk-crm/protocol/openid-connect/auth?x=1',
    'toInternal keeps path and query, rewrites scheme and host'
  )
  const html = '<form id="kc-form-login" onsubmit="x" action="https://crm.example.ru/idp/realms/rtk-crm/login-actions/authenticate?session_code=a&amp;execution=b" method="post">'
  const formTag = (html.match(/<form[^>]*id="kc-form-login"[^>]*>/) || [])[0]
  check(Boolean(formTag), true, 'finds the Keycloak login form tag')
  const action = (formTag.match(/action="([^"]+)"/) || [])[1]
  check(action?.replace(/&amp;/g, '&'), 'https://crm.example.ru/idp/realms/rtk-crm/login-actions/authenticate?session_code=a&execution=b', 'extracts and unescapes the form action')
  console.log('load-test.mjs --self-test: OK')
  process.exit(0)
}

const origin = (readEnv(envFile).PUBLIC_ORIGIN || 'http://rtk.localhost:8081').replace(/\/$/, '')

const toInternal = (absoluteUrl) => {
  const target = new URL(absoluteUrl)
  const base = new URL(origin)
  target.protocol = base.protocol
  target.host = base.host
  return target.toString()
}
const credentials = Object.entries(readEnv(path.join(stateDir, 'users.env')))
  .sort(([a], [b]) => a.localeCompare(b))
  .slice(0, userLimit)

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))
const pick = (items) => items[Math.floor(Math.random() * items.length)]
const assert = (condition, message) => {
  if (!condition) {
    throw new Error(message)
  }
}
const isoDate = (daysAgo) => new Date(Date.now() - daysAgo * 86400000).toISOString().slice(0, 10)

let socket
let nextId = 1
const pending = new Map()
const cdp = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
  const id = nextId++
  pending.set(id, { resolve, reject })
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
})

async function connectCdp() {
  const version = await (await fetch(`http://127.0.0.1:${cdpPort}/json/version`)).json()
  socket = await new Promise((resolve, reject) => {
    const value = new WebSocket(version.webSocketDebuggerUrl)
    value.addEventListener('open', () => resolve(value), { once: true })
    value.addEventListener('error', () => reject(new Error('CDP connection failed')), { once: true })
  })
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    const deferred = message.id && pending.get(message.id)
    if (!deferred) {
      return
    }
    pending.delete(message.id)
    if (message.error) {
      deferred.reject(new Error('CDP request failed: ' + message.error.message))
    } else {
      deferred.resolve(message.result)
    }
  })
}

async function openPage(url) {
  const { browserContextId } = await cdp('Target.createBrowserContext')
  const { targetId } = await cdp('Target.createTarget', { url: 'about:blank', browserContextId })
  const { sessionId } = await cdp('Target.attachToTarget', { targetId, flatten: true })
  const evaluate = async (expression) => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true }, sessionId)
    if (result.exceptionDetails) {
      throw new Error('Browser evaluation failed: ' + (result.exceptionDetails.exception?.description ?? result.exceptionDetails.text))
    }
    return result.result.value
  }
  const waitFor = async (expression, message, attempts = 200) => {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      if (await evaluate(expression).catch(() => false)) {
        return
      }
      await pause(150)
    }
    throw new Error(message)
  }
  await cdp('Page.navigate', { url }, sessionId)
  return { browserContextId, sessionId, evaluate, waitFor, close: () => cdp('Target.disposeBrowserContext', { browserContextId }).catch(() => null) }
}

async function browserLogin(username, password) {
  const page = await openPage(origin + '/')
  const loginButton = "[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти'))"
  await page.waitFor(`Boolean(${loginButton})`, 'Login button did not appear for ' + username)
  await page.evaluate(`${loginButton}.click()`)
  await page.waitFor("Boolean(document.querySelector('#username') && document.querySelector('#kc-login'))", 'Keycloak form did not appear for ' + username)
  await page.evaluate(`(() => {
    const set = (selector, value) => {
      const field = document.querySelector(selector)
      field.value = value
      field.dispatchEvent(new Event('input', { bubbles: true }))
    }
    set('#username', ${JSON.stringify(username)})
    set('#password', ${JSON.stringify(password)})
    document.querySelector('#kc-login').click()
  })()`)
  await page.waitFor(`location.origin === ${JSON.stringify(origin)} && !location.pathname.startsWith('/idp/') && !(${loginButton})`, 'OIDC callback did not return to CRM for ' + username)
  const me = await page.evaluate("fetch('/api/me').then((response) => response.status)")
  assert(me === 200, `${username}: login returned to CRM without a session, /api/me ${me}`)
  return page
}

async function httpLogin(username, password) {
  const cookies = new Map()
  const hop = async (url, init) => {
    const headers = { ...(init?.headers || {}) }
    if (cookies.size > 0) {
      headers.Cookie = [...cookies].map(([name, value]) => `${name}=${value}`).join('; ')
    }
    const response = await fetch(url, { ...init, headers, redirect: 'manual' })
    for (const line of response.headers.getSetCookie()) {
      const [pair] = line.split(';')
      const index = pair.indexOf('=')
      cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim())
    }
    return response
  }
  const followRedirects = async (start, startUrl) => {
    let response = start
    let current = startUrl
    for (let hops = 0; hops < 10 && response.status >= 300 && response.status < 400; hops += 1) {
      current = toInternal(new URL(response.headers.get('location'), current).toString())
      response = await hop(current)
    }
    return { response, current }
  }
  const start = await followRedirects(await hop(origin + '/api/auth/login'), origin + '/api/auth/login')
  assert(start.response.status === 200, `${username}: Keycloak did not return a login page (${start.response.status} at ${start.current})`)
  const html = await start.response.text()
  const formTag = (html.match(/<form[^>]*id="kc-form-login"[^>]*>/) || [])[0]
  assert(formTag, `${username}: Keycloak login form not found`)
  const action = (formTag.match(/action="([^"]+)"/) || [])[1]
  assert(action, `${username}: Keycloak login form has no action`)
  const submitted = await hop(toInternal(action.replace(/&amp;/g, '&')), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password, credentialId: '' }).toString()
  })
  const landed = await followRedirects(submitted, toInternal(action))
  assert(landed.response.status === 200, `${username}: login did not return to the CRM (${landed.response.status} at ${landed.current})`)
  const client = new Client(username, [...cookies].map(([name, value]) => ({ name, value, path: '/' })))
  const me = await client.request('GET', '/api/me')
  assert(me.status === 200, `${username}: login returned to CRM without a session, /api/me ${me.status}`)
  return client
}

class Client {
  constructor(username, cookies) {
    this.username = username
    this.cookies = new Map(cookies.filter((cookie) => cookie.path === '/').map((cookie) => [cookie.name, cookie.value]))
    this.csrf = null
  }

  async request(method, url, body) {
    if (method !== 'GET' && !this.csrf) {
      this.csrf = await (await this.request('GET', '/api/csrf')).json()
    }
    const headers = { Cookie: [...this.cookies].map(([name, value]) => `${name}=${value}`).join('; ') }
    if (method !== 'GET') {
      headers[this.csrf.headerName] = this.csrf.token
      headers['Idempotency-Key'] = randomUUID()
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    const response = await fetch(origin + url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), redirect: 'manual' })
    for (const line of response.headers.getSetCookie()) {
      const [pair] = line.split(';')
      const index = pair.indexOf('=')
      this.cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim())
    }
    return response
  }
}

const samples = []
const errors = []
const expected = { foreign: [404], reportOrder: [202], setup: [201] }

async function measure(client, op, method, url, body) {
  const started = performance.now()
  const at = Date.now()
  let status = 0
  let payload = null
  let bytes = null
  try {
    const response = await client.request(method, url, body)
    status = response.status
    if ((response.headers.get('content-type') || '').includes('json')) {
      payload = await response.json()
    } else {
      bytes = Buffer.from(await response.arrayBuffer())
    }
  } catch (error) {
    payload = { network: error.cause?.code || error.message }
  }
  const elapsed = performance.now() - started
  const ok = (expected[op] || [200]).includes(status)
  samples.push({ op, at, elapsed, status, ok, user: client.username })
  if (!ok && status !== 409 && errors.length < 50) {
    errors.push({ user: client.username, op, status, code: payload?.code ?? payload?.network })
  }
  return { status, payload, bytes, ok, elapsed }
}

const previewBodies = [
  { kind: 'PORTFOLIO', from: isoDate(30), to: isoDate(0), periodBasis: 'CREATED', filters: {}, columns: [] },
  { kind: 'PORTFOLIO', from: isoDate(365), to: isoDate(0), periodBasis: 'ACTIVITY', filters: {}, columns: [] },
  { kind: 'EVENTS', from: isoDate(90), to: isoDate(0), filters: {}, columns: [] },
  { kind: 'EVENTS', from: isoDate(365), to: isoDate(0), filters: {}, columns: [] },
  { kind: 'DEMAND', from: isoDate(365), to: isoDate(0), filters: {}, columns: [] }
]
const dueValues = ['', 'OVERDUE', 'THIS_WEEK', 'NO_NEXT_STEP']
const counters = { transitions: 0, comments: 0, conflicts: 0, foreignLeaks: 0 }

class VirtualUser {
  constructor(client, profile) {
    this.client = client
    this.profile = profile
    this.cards = []
    this.writable = []
    this.foreign = []
  }

  async init() {
    const organizations = await measure(this.client, 'organizations', 'GET', '/api/organizations?page=0&size=100')
    assert(organizations.ok, `${this.client.username}: organizations ${organizations.status}`)
    const list = await measure(this.client, 'work', 'GET', '/api/interactions?page=0&size=100')
    assert(list.ok, `${this.client.username}: interactions ${list.status}`)
    this.cards = list.payload.items.filter((item) => !item.title.startsWith('LOAD-браузер'))
    if (this.profile.role === 'LEADER') {
      const unassigned = await measure(this.client, 'organizations', 'GET', '/api/organizations?page=0&size=100&requiresAssignment=true')
      const loadOrganization = unassigned.payload.items.find((item) => item.name.startsWith('LOAD-'))
      assert(loadOrganization, `${this.client.username}: no unassigned LOAD organization`)
      const own = await measure(this.client, 'work', 'GET', `/api/interactions?page=0&size=100&organizationId=${loadOrganization.id}`)
      this.writable = own.payload.items.map((item) => item.id)
    } else {
      this.writable = this.cards.map((item) => item.id)
    }
    assert(this.writable.length > 0, `${this.client.username}: nothing to change`)
  }

  async openCard(id) {
    const started = performance.now()
    const at = Date.now()
    const [card, events] = await Promise.all([
      measure(this.client, 'cardGet', 'GET', `/api/interactions/${id}`),
      measure(this.client, 'cardEvents', 'GET', `/api/interactions/${id}/events`)
    ])
    samples.push({ op: 'card', at, elapsed: performance.now() - started, status: card.status, ok: card.ok && events.ok, user: this.client.username })
    return card.payload
  }

  async step() {
    const roll = Math.random() * 100
    if (roll < 15) {
      const q = Math.random() < 0.3 ? '&q=' + encodeURIComponent('load-вуз 0') : ''
      const assignment = this.profile.role === 'LEADER' && Math.random() < 0.3 ? '&requiresAssignment=true' : ''
      await measure(this.client, 'organizations', 'GET', `/api/organizations?page=0&size=25${q}${assignment}`)
    } else if (roll < 35) {
      const due = pick(dueValues)
      await measure(this.client, 'work', 'GET', `/api/interactions?page=0&size=25${due ? '&due=' + due : ''}`)
    } else if (roll < 55) {
      await this.openCard(pick(this.cards).id)
    } else if (roll < 67) {
      await measure(this.client, 'preview', 'POST', '/api/reports/preview?page=0&size=50', pick(previewBodies))
    } else if (roll < 71) {
      const body = pick(previewBodies.filter((item) => item.kind !== 'DEMAND'))
      const groupBy = pick(body.periodBasis === 'ACTIVITY' ? ['STAGE', 'ORGANIZATION'] : ['STAGE', 'MONTH', 'ORGANIZATION'])
      await measure(this.client, 'statistics', 'POST', '/api/statistics', { ...body, groupBy })
    } else if (roll < 83) {
      await this.transition()
    } else if (roll < 96) {
      await this.comment()
    } else if (this.foreign.length > 0) {
      const result = await measure(this.client, 'foreign', 'GET', `/api/interactions/${pick(this.foreign)}`)
      if (result.status === 200) {
        counters.foreignLeaks += 1
      }
    }
  }

  async transition() {
    const card = await this.openCard(pick(this.writable))
    const option = card && pick(card.allowedTransitions)
    if (!option) {
      return this.comment()
    }
    const result = await measure(this.client, 'transition', 'POST', `/api/interactions/${card.id}/transitions`, {
      version: card.version,
      toStageId: option.stageId,
      comment: 'LOAD-переход ' + randomUUID().slice(0, 8)
    })
    this.track(result, 'transitions')
  }

  async comment() {
    const card = await this.openCard(pick(this.writable))
    if (!card) {
      return
    }
    const result = await measure(this.client, 'comment', 'POST', `/api/interactions/${card.id}/comments`, {
      version: card.version,
      stageId: card.currentStageId,
      text: 'LOAD-комментарий ' + randomUUID().slice(0, 8)
    })
    this.track(result, 'comments')
  }

  track(result, counter) {
    if (result.ok) {
      counters[counter] += 1
    } else if (result.status === 409) {
      counters.conflicts += 1
    }
  }

  async run(until) {
    await pause(Math.random() * thinkMax)
    while (Date.now() < until) {
      try {
        await this.step()
      } catch (error) {
        if (errors.length < 50) {
          errors.push({ user: this.client.username, message: error.message.slice(0, 200) })
        }
      }
      await pause(thinkMin + Math.random() * (thinkMax - thinkMin))
    }
  }
}

const zipEntries = (buffer) => {
  let end = buffer.length - 22
  while (end >= 0 && buffer.readUInt32LE(end) !== 0x06054b50) {
    end -= 1
  }
  assert(end >= 0, 'ZIP end of central directory is absent')
  const count = buffer.readUInt16LE(end + 10)
  let offset = buffer.readUInt32LE(end + 16)
  const entries = new Map()
  for (let index = 0; index < count; index += 1) {
    const method = buffer.readUInt16LE(offset + 10)
    const compressedSize = buffer.readUInt32LE(offset + 20)
    const nameLength = buffer.readUInt16LE(offset + 28)
    const extraLength = buffer.readUInt16LE(offset + 30)
    const commentLength = buffer.readUInt16LE(offset + 32)
    const localOffset = buffer.readUInt32LE(offset + 42)
    const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength)
    const dataStart = localOffset + 30 + buffer.readUInt16LE(localOffset + 26) + buffer.readUInt16LE(localOffset + 28)
    const data = buffer.subarray(dataStart, dataStart + compressedSize)
    entries.set(name, method === 8 ? zlib.inflateRawSync(data).toString('utf8') : data.toString('utf8'))
    offset += 46 + nameLength + extraLength + commentLength
  }
  return entries
}

const checkFile = (format, buffer, rowCount) => {
  const head = buffer.subarray(0, 8)
  if (format === 'XLSX') {
    const sheet = [...zipEntries(buffer)].find(([name]) => /^xl\/worksheets\/sheet\d+\.xml$/.test(name))
    assert(sheet, 'XLSX has no worksheet')
    return { format, bytes: buffer.length, sheetRows: (sheet[1].match(/<row /g) || []).length }
  }
  if (format === 'XLS') {
    assert(head.equals(Buffer.from([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1])), 'XLS is not an OLE2 document')
    return { format, bytes: buffer.length }
  }
  if (format === 'PDF') {
    const text = buffer.toString('latin1')
    assert(text.startsWith('%PDF-') && text.trimEnd().endsWith('%%EOF'), 'PDF is broken')
    return { format, bytes: buffer.length }
  }
  if (format === 'PNG') {
    assert(head.equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), 'PNG signature is absent')
    return { format, bytes: buffer.length, width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) }
  }
  const json = JSON.parse(buffer.toString('utf8'))
  assert(Array.isArray(json.rows) && json.rows.length === rowCount, 'JSON rows do not match rowCount')
  return { format, bytes: buffer.length, rows: json.rows.length }
}

const reportPlan = [
  { role: 'LEADER', body: { kind: 'PORTFOLIO', format: 'XLSX', from: isoDate(365), to: isoDate(0), periodBasis: 'ACTIVITY' } },
  { role: 'LEADER', body: { kind: 'PORTFOLIO', format: 'PDF', from: isoDate(365), to: isoDate(0), periodBasis: 'CREATED' } },
  { role: 'LEADER', body: { kind: 'EVENTS', format: 'XLSX', from: isoDate(365), to: isoDate(0) } },
  { role: 'LEADER', body: { kind: 'EVENTS', format: 'PDF', from: isoDate(365), to: isoDate(0) } },
  { role: 'LEADER', body: { kind: 'DEMAND', format: 'XLSX', from: isoDate(365), to: isoDate(0) } },
  { role: 'USER', body: { kind: 'PORTFOLIO', format: 'XLS', from: isoDate(365), to: isoDate(0), periodBasis: 'ACTIVITY' } },
  { role: 'USER', body: { kind: 'EVENTS', format: 'JSON', from: isoDate(365), to: isoDate(0) } },
  { role: 'USER', body: { kind: 'DEMAND', format: 'PDF', from: isoDate(365), to: isoDate(0) } },
  { role: 'USER', body: { kind: 'PORTFOLIO', format: 'PNG', from: isoDate(365), to: isoDate(0), groupBy: 'STAGE' } },
  { role: 'USER', body: { kind: 'EVENTS', format: 'PDF', from: isoDate(180), to: isoDate(0), groupBy: 'MONTH' } },
  { role: 'USER', body: { kind: 'PORTFOLIO', format: 'JSON', from: isoDate(90), to: isoDate(0), periodBasis: 'ACTIVITY' } },
  { role: 'USER', body: { kind: 'EVENTS', format: 'XLS', from: isoDate(90), to: isoDate(0) } }
]

const concurrency = (jobs, threshold) => {
  const points = jobs.flatMap((job) => [[Date.parse(job.startedAt), 1], [Date.parse(job.finishedAt), -1]])
    .sort((a, b) => a[0] - b[0] || a[1] - b[1])
  let current = 0
  let max = 0
  let peakAt = null
  let previous = null
  let atThresholdMs = 0
  for (const [time, delta] of points) {
    if (current >= threshold) {
      atThresholdMs += time - previous
    }
    current += delta
    previous = time
    if (current > max) {
      max = current
      peakAt = time
    }
  }
  const peakJobs = jobs.filter((job) => Date.parse(job.startedAt) <= peakAt && Date.parse(job.finishedAt) > peakAt)
  return {
    max,
    peakAt: peakAt === null ? null : new Date(peakAt).toISOString(),
    atThresholdMs,
    peakCommonMs: peakJobs.length === 0 ? 0 : Math.min(...peakJobs.map((job) => Date.parse(job.finishedAt))) - Math.max(...peakJobs.map((job) => Date.parse(job.startedAt))),
    peakJobs: peakJobs.map((job) => `${job.user} ${job.kind}/${job.format}${job.groupBy ? '/' + job.groupBy : ''} ${job.from}`)
  }
}

async function reportBurst(users, label) {
  const leaders = users.filter((user) => user.profile.role === 'LEADER')
  const kams = users.filter((user) => user.profile.role !== 'LEADER')
  const assigned = reportPlan.map((plan, index) => ({
    plan,
    user: plan.role === 'LEADER' ? leaders[index % leaders.length] : kams[(index * 3) % kams.length]
  }))
  const submittedAt = Date.now()
  const orders = await Promise.all(assigned.map(({ plan, user }) => measure(user.client, 'reportOrder', 'POST', '/api/reports', { filters: {}, columns: [], ...plan.body })))
  const jobs = await Promise.all(orders.map(async (order, index) => {
    const { plan, user } = assigned[index]
    if (!order.ok) {
      return { user: user.client.username, ...plan.body, error: 'order ' + order.status + ' ' + (order.payload?.code ?? '') }
    }
    let job
    for (let attempt = 0; attempt < 600; attempt += 1) {
      job = (await measure(user.client, 'reportStatus', 'GET', `/api/report-jobs/${order.payload.jobId}`)).payload
      if (job?.status === 'SUCCEEDED' || job?.status === 'FAILED') {
        break
      }
      await pause(300)
    }
    const summary = { user: user.client.username, kind: plan.body.kind, format: plan.body.format, groupBy: plan.body.groupBy ?? null, from: plan.body.from, jobId: job.id, status: job.status, rowCount: job.rowCount, createdAt: job.createdAt, startedAt: job.startedAt, finishedAt: job.finishedAt, error: job.error?.code }
    if (job.status === 'SUCCEEDED') {
      const download = await measure(user.client, 'reportDownload', 'GET', `/api/report-jobs/${job.id}/result`)
      try {
        summary.file = checkFile(plan.body.format, download.bytes ?? Buffer.from(JSON.stringify(download.payload)), job.rowCount)
      } catch (error) {
        summary.fileError = error.message
      }
      if (!plan.body.groupBy) {
        const { format, groupBy, ...previewBody } = plan.body
        const preview = await measure(user.client, 'preview', 'POST', '/api/reports/preview?page=0&size=1', { filters: {}, columns: [], ...previewBody })
        summary.previewTotal = preview.payload?.total
      }
    }
    return summary
  }))
  const finished = jobs.filter((job) => job.startedAt && job.finishedAt)
  return {
    label,
    submittedAt: new Date(submittedAt).toISOString(),
    windowEnd: new Date(Math.max(...finished.map((job) => Date.parse(job.finishedAt)))).toISOString(),
    jobs,
    succeeded: jobs.filter((job) => job.status === 'SUCCEEDED' && job.file && !job.fileError).length,
    concurrency: concurrency(finished, 10),
    runningMs: finished.map((job) => Date.parse(job.finishedAt) - Date.parse(job.startedAt))
  }
}

const observe = (condition) => `new Promise((resolve) => {
  const started = performance.now()
  let busySeen = false
  const check = () => {
    const state = (${condition})()
    busySeen = busySeen || state.busy
    if (state.done && (busySeen || state.changed)) {
      observer.disconnect()
      resolve(performance.now() - started)
    }
  }
  const observer = new MutationObserver(check)
  observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true })
  window.__loadAct()
  check()
  setTimeout(() => { observer.disconnect(); resolve(-1) }, 15000)
})`

async function browserMeasurements(page, card, rounds, start) {
  const results = []
  const formByTitle = (title) => `[...document.querySelectorAll('form')].find((form) => form.querySelector('h6')?.textContent?.trim() === ${JSON.stringify(title)})`
  const setField = (element, prototype, value) => `(() => { const field = ${element}; Object.getOwnPropertyDescriptor(${prototype}.prototype, 'value').set.call(field, ${JSON.stringify(value)}); field.dispatchEvent(new Event(field.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true })) })()`
  const record = (op, elapsed, round) => results.push({ op, elapsed, round, at: Date.now() })
  for (const [round, second] of rounds.entries()) {
    await pause(Math.max(0, start + second * 1000 - Date.now()))
    try {
      await page.evaluate(`location.hash = '#/organizations/${card.organizationId}/${card.id}'`)
      await page.waitFor(`Boolean(${formByTitle('Переход этапа')}) && Boolean(${formByTitle('Комментарий')})`, 'Card forms did not load')
      const state = await page.evaluate(`fetch('/api/interactions/${card.id}').then((response) => response.json())`)
      const option = state.allowedTransitions.find((item) => !item.commentRequired) ?? state.allowedTransitions[0]
      await page.evaluate(setField(`${formByTitle('Переход этапа')}.querySelector('select')`, 'HTMLSelectElement', option.stageId))
      const transitionTextarea = `${formByTitle('Переход этапа')}.querySelector('textarea')`
      if (option.commentRequired || await page.evaluate(`Boolean(${transitionTextarea})`)) {
        await page.evaluate(setField(transitionTextarea, 'HTMLTextAreaElement', 'LOAD-браузер переход ' + round))
      }
      await page.waitFor(`!${formByTitle('Переход этапа')}.querySelector('button[type=submit]').disabled`, 'Transition is not ready')
      await page.evaluate(`window.__loadAct = () => ${formByTitle('Переход этапа')}.querySelector('button[type=submit]').click()`)
      record('transition', await page.evaluate(observe(`() => ({ busy: false, changed: true, done: document.querySelector('li[aria-current="step"] .interaction-path__name')?.textContent === ${JSON.stringify(option.stageName)} })`)), round)
      const commentText = 'LOAD-браузер комментарий ' + round + ' ' + randomUUID().slice(0, 6)
      await page.evaluate(setField(`${formByTitle('Комментарий')}.querySelector('select')`, 'HTMLSelectElement', option.stageId))
      await page.evaluate(setField(`${formByTitle('Комментарий')}.querySelector('textarea')`, 'HTMLTextAreaElement', commentText))
      await page.waitFor(`!${formByTitle('Комментарий')}.querySelector('button[type=submit]').disabled`, 'Comment is not ready')
      await page.evaluate(`window.__loadAct = () => ${formByTitle('Комментарий')}.querySelector('button[type=submit]').click()`)
      record('comment', await page.evaluate(observe(`() => ({ busy: false, changed: true, done: ${formByTitle('Комментарий')}.querySelector('textarea').value === '' && document.body.textContent.includes(${JSON.stringify(commentText)}) })`)), round)

      await page.evaluate("location.hash = '#/work'")
      await page.waitFor("document.querySelector('.work__total')?.textContent.startsWith('Найдено:')", 'Work list did not load')
      const due = await page.evaluate(`[...document.querySelectorAll('input[name=work-due]')].filter((input) => !input.checked).map((input) => input.value)[${round} % 3]`)
      await page.evaluate(`window.__loadAct = () => document.querySelector('input[name=work-due][value="${due}"]').click()`)
      record('workFilter', await page.evaluate(observe(`() => { const text = document.querySelector('.work__total')?.textContent ?? ''; return { busy: !text.startsWith('Найдено:') || document.querySelector('.work-list')?.getAttribute('aria-busy') === 'true', changed: false, done: text.startsWith('Найдено:') } }`)), round)

      await page.evaluate("location.hash = '#/reports'")
      await page.waitFor("Boolean(document.querySelector('.reports__form')) && !document.querySelector('.reports__actions button[type=submit]').disabled", 'Reports screen did not load')
      const from = isoDate([30, 365, 90, 180, 7][round % 5])
      await page.evaluate(setField("document.querySelectorAll('.reports__form input[type=date]')[0]", 'HTMLInputElement', from))
      await page.evaluate(setField("document.querySelectorAll('.reports__form input[type=date]')[1]", 'HTMLInputElement', isoDate(0)))
      await page.waitFor("!document.querySelector('.reports__actions button[type=submit]').disabled", 'Preview is not ready')
      await page.evaluate("window.__loadAct = () => document.querySelector('.reports__actions button[type=submit]').click()")
      record('preview', await page.evaluate(observe(`() => ({ busy: document.querySelector('.reports__preview')?.getAttribute('aria-busy') === 'true', changed: false, done: document.querySelector('.reports__preview')?.getAttribute('aria-busy') === 'false' && Boolean(document.querySelector('.reports__total')) })`)), round)
    } catch (error) {
      results.push({ op: 'roundFailed', elapsed: -1, round, at: Date.now(), message: error.message })
    }
  }
  return results
}

const percentile = (values, share) => {
  if (values.length === 0) {
    return null
  }
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.ceil(share * sorted.length) - 1)]
}

const summarize = (items) => {
  const groups = {}
  for (const item of items) {
    (groups[item.op] ||= []).push(item)
  }
  return Object.fromEntries(Object.entries(groups).sort().map(([op, list]) => {
    const values = list.map((item) => item.elapsed)
    return [op, {
      count: list.length,
      errors: list.filter((item) => !item.ok).length,
      server5xx: list.filter((item) => item.status >= 500 || item.status === 0).length,
      p50: Math.round(percentile(values, 0.5)),
      p95: Math.round(percentile(values, 0.95)),
      max: Math.round(Math.max(...values)),
      over1s: values.filter((value) => value > 1000).length
    }]
  }))
}

const output = { origin, startedAt: new Date().toISOString(), users: credentials.length, durationSeconds, thinkMs: [thinkMin, thinkMax] }
try {
  if (!httpOnly) {
    await connectCdp()
  }
  const logins = []
  const loginStarted = Date.now()
  for (let index = 0; index < credentials.length; index += 5) {
    logins.push(...await Promise.all(credentials.slice(index, index + 5).map(async ([username, password]) => {
      if (httpOnly) {
        return httpLogin(username, password)
      }
      const page = await browserLogin(username, password)
      const { cookies } = await cdp('Storage.getCookies', { browserContextId: page.browserContextId })
      await page.close()
      return new Client(username, cookies.filter((cookie) => origin.includes(cookie.domain)))
    })))
  }
  output.loginSeconds = Math.round((Date.now() - loginStarted) / 1000)
  const users = []
  for (const client of logins) {
    const me = await measure(client, 'me', 'GET', '/api/me')
    assert(me.ok, `${client.username}: /api/me ${me.status}`)
    users.push(new VirtualUser(client, me.payload))
  }
  output.distinctProfiles = new Set(users.map((user) => user.profile.id)).size
  output.roles = users.reduce((acc, user) => ({ ...acc, [user.profile.role]: (acc[user.profile.role] || 0) + 1 }), {})
  await Promise.all(users.map((user) => user.init()))
  for (const user of users) {
    const others = users.filter((other) => other !== user && (user.profile.role === 'LEADER' ? other.profile.teamId !== user.profile.teamId : other.profile.id !== user.profile.id) && other.profile.role !== 'LEADER')
    user.foreign = others.flatMap((other) => other.writable.slice(0, 2)).filter((id) => !user.cards.some((card) => card.id === id)).slice(0, 20)
  }

  let browserCard = null
  let browserPage = null
  if (!httpOnly) {
    const browserUser = users.find((user) => user.profile.role !== 'LEADER')
    const [, browserPassword] = credentials.find(([username]) => username === browserUser.client.username)
    browserCard = await measure(browserUser.client, 'setup', 'POST', '/api/interactions', {
      organizationId: browserUser.cards[0].organizationId,
      title: 'LOAD-браузер ' + randomUUID().slice(0, 8),
      contactIds: []
    })
    assert(browserCard.status === 201, 'Browser interaction was not created: ' + browserCard.status + ' ' + JSON.stringify(browserCard.payload))
    browserPage = await browserLogin(browserUser.client.username, browserPassword)
  }

  const start = Date.now()
  const until = start + durationSeconds * 1000
  output.loadStartedAt = new Date(start).toISOString()
  const bursts = burstAt.map(async (second, index) => {
    await pause(start + second * 1000 - Date.now())
    return reportBurst(users, 'burst-' + (index + 1)).catch((error) => ({ label: 'burst-' + (index + 1), error: error.message }))
  })
  const browser = httpOnly
    ? Promise.resolve(null)
    : browserMeasurements(browserPage, browserCard.payload, browserRounds, start).catch((error) => ({ error: error.message }))
  await Promise.all(users.map((user) => user.run(until)))
  output.loadFinishedAt = new Date().toISOString()
  output.reportBursts = await Promise.all(bursts)
  output.browser = await browser
  if (browserPage) {
    await browserPage.close()
  }

  const loadSamples = samples.filter((sample) => sample.at >= start)
  const reportWindows = output.reportBursts.filter((burst) => !burst.error).map((burst) => [Date.parse(burst.submittedAt), Date.parse(burst.windowEnd)])
  const interactiveOps = new Set(['work', 'organizations', 'card', 'preview', 'statistics', 'transition', 'comment', 'foreign'])
  output.api = summarize(loadSamples)
  output.apiDuringReports = summarize(loadSamples.filter((sample) => interactiveOps.has(sample.op) && reportWindows.some(([from, to]) => sample.at >= from && sample.at <= to)))
  output.browserSummary = Array.isArray(output.browser) ? summarize(output.browser.map((item) => ({ ...item, ok: item.elapsed >= 0, status: item.elapsed >= 0 ? 200 : 0 }))) : null
  output.timeline = Object.entries(Object.groupBy(loadSamples.filter((sample) => interactiveOps.has(sample.op)), (sample) => Math.floor((sample.at - start) / 15000) * 15))
    .map(([second, list]) => ({ second: Number(second), ...summarize(list.map((sample) => ({ ...sample, op: 'interactive' }))).interactive }))
  fs.writeFileSync(path.join(stateDir, `samples-${output.startedAt.replace(/[:.]/g, '-')}.json`), JSON.stringify(loadSamples.map(({ op, at, elapsed, status, user }) => [op, at - start, Math.round(elapsed), status, user])))
  output.activeSessionsDuringLoad = new Set(loadSamples.map((sample) => sample.user)).size
  output.requests = loadSamples.length
  output.counters = counters
  output.errors = errors
  output.statuses = loadSamples.reduce((acc, sample) => ({ ...acc, [sample.status]: (acc[sample.status] || 0) + 1 }), {})
} catch (error) {
  output.failure = error.message
  process.exitCode = 1
} finally {
  socket?.close()
}

fs.mkdirSync(stateDir, { recursive: true })
const resultFile = path.join(stateDir, `result-${output.startedAt.replace(/[:.]/g, '-')}.json`)
fs.writeFileSync(resultFile, JSON.stringify(output, null, 2))
console.log(JSON.stringify({ resultFile, failure: output.failure, api: output.api, apiDuringReports: output.apiDuringReports, browser: output.browserSummary, bursts: output.reportBursts?.map(({ label, succeeded, concurrency, runningMs }) => ({ label, succeeded, concurrency, runningMs })), counters: output.counters, statuses: output.statuses, errors: output.errors?.slice(0, 10) }, null, 1))
