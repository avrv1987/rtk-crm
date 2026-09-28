import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'
import { randomUUID } from 'node:crypto'
import { isDeepStrictEqual } from 'node:util'

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

async function submitKeycloak(page, username) {
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
}

async function login(username) {
  const page = await openPage(origin + '/')
  await page.waitFor(() => page.evaluate(loginButtonVisible), 'Login button did not appear')
  await page.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')).click()")
  await submitKeycloak(page, username)
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
  const started = performance.now()
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
  return {
    status: response.status,
    type,
    body: json,
    base64,
    disposition: response.headers.get('content-disposition'),
    elapsed: performance.now() - started
  }
})()`)

const requireStatus = (response, status, message) => {
  if (response.status !== status) {
    throw new Error(message + ': ' + response.status + ' ' + (response.body?.code ?? ''))
  }
  return response.body
}

const moscowToday = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow' }).format(new Date())

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
    assert(buffer.readUInt32LE(offset) === 0x02014b50, 'ZIP central directory is broken')
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

const xmlText = (value) => value
  .replace(/&lt;/g, '<')
  .replace(/&gt;/g, '>')
  .replace(/&quot;/g, '"')
  .replace(/&apos;/g, "'")
  .replace(/&amp;/g, '&')

const pdfText = (buffer) => {
  const raw = buffer.toString('latin1')
  const streams = []
  const pattern = /<<((?:(?!>>\s*stream)[\s\S])*)>>\s*stream\r?\n/g
  let match
  while ((match = pattern.exec(raw))) {
    const start = match.index + match[0].length
    const end = raw.indexOf('endstream', start)
    const data = buffer.subarray(start, end)
    if (match[1].includes('/Image')) {
      continue
    }
    streams.push(match[1].includes('/FlateDecode') ? zlib.inflateSync(data, { finishFlush: zlib.constants.Z_SYNC_FLUSH }).toString('latin1') : data.toString('latin1'))
  }
  const unicode = new Map()
  const hexText = (hex) => {
    let text = ''
    for (let index = 0; index + 4 <= hex.length; index += 4) {
      text += String.fromCharCode(parseInt(hex.slice(index, index + 4), 16))
    }
    return text
  }
  for (const stream of streams) {
    for (const block of stream.matchAll(/beginbfchar([\s\S]*?)endbfchar/g)) {
      for (const pair of block[1].matchAll(/<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>/g)) {
        unicode.set(parseInt(pair[1], 16), hexText(pair[2]))
      }
    }
    for (const block of stream.matchAll(/beginbfrange([\s\S]*?)endbfrange/g)) {
      for (const range of block[1].matchAll(/<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>/g)) {
        const first = parseInt(range[1], 16)
        const last = parseInt(range[2], 16)
        const base = parseInt(range[3], 16)
        for (let code = first; code <= last; code += 1) {
          unicode.set(code, String.fromCharCode(base + code - first))
        }
      }
    }
  }
  const literalCodes = (literal) => {
    const bytes = []
    for (let index = 0; index < literal.length; index += 1) {
      const character = literal[index]
      if (character !== '\\') {
        bytes.push(character.charCodeAt(0) & 0xff)
        continue
      }
      const next = literal[index + 1]
      const octal = /^[0-7]{1,3}/.exec(literal.slice(index + 1))
      if (octal) {
        bytes.push(parseInt(octal[0], 8) & 0xff)
        index += octal[0].length
        continue
      }
      bytes.push({ n: 10, r: 13, t: 9, b: 8, f: 12 }[next] ?? next.charCodeAt(0))
      index += 1
    }
    const codes = []
    for (let index = 0; index + 1 < bytes.length; index += 2) {
      codes.push(bytes[index] * 256 + bytes[index + 1])
    }
    return codes
  }
  const hexCodes = (hex) => {
    const codes = []
    for (let index = 0; index + 4 <= hex.length; index += 4) {
      codes.push(parseInt(hex.slice(index, index + 4), 16))
    }
    return codes
  }
  let text = ''
  for (const stream of streams) {
    for (const operation of stream.matchAll(/\[((?:\((?:\\[\s\S]|[^\\)])*\)|[^\]()])*)\]\s*TJ|<([0-9A-Fa-f]*)>\s*Tj|\(((?:\\[\s\S]|[^\\)])*)\)\s*Tj|\bET\b/g)) {
      if (operation[0] === 'ET') {
        text += '\n'
        continue
      }
      const codes = operation[2] !== undefined
        ? hexCodes(operation[2])
        : operation[3] !== undefined
          ? literalCodes(operation[3])
          : [...operation[1].matchAll(/<([0-9A-Fa-f]*)>|\(((?:\\[\s\S]|[^\\)])*)\)/g)]
              .flatMap((part) => (part[1] !== undefined ? hexCodes(part[1]) : literalCodes(part[2])))
      text += codes.map((code) => unicode.get(code) ?? '?').join('')
    }
  }
  return text
}

const xlsIncludes = (buffer, value) => buffer.includes(Buffer.from(value, 'utf16le'))
  || (!/[^\u0000-\u00ff]/.test(value) && buffer.includes(Buffer.from(value, 'latin1')))

function checkFile(format, buffer, expectations) {
  const { present, absent } = expectations
  if (format === 'XLSX') {
    assert(buffer.subarray(0, 4).equals(Buffer.from([0x50, 0x4b, 0x03, 0x04])), 'XLSX is not a ZIP container')
    const entries = zipEntries(buffer)
    assert(entries.has('xl/workbook.xml'), 'XLSX has no xl/workbook.xml')
    const text = xmlText([...entries.values()].join('\n'))
    const rows = [...(entries.get('xl/worksheets/sheet1.xml') ?? '').matchAll(/<row\b/g)].length
    present.forEach((value) => assert(text.includes(value), 'XLSX lacks expected text'))
    absent.forEach((value) => assert(!text.includes(value), 'XLSX contains foreign text'))
    return { container: 'zip', workbook: true, sheetRows: rows }
  }
  if (format === 'XLS') {
    assert(buffer.subarray(0, 8).equals(Buffer.from([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1])), 'XLS is not an OLE2 file')
    present.forEach((value) => assert(xlsIncludes(buffer, value), 'XLS lacks expected text'))
    absent.forEach((value) => assert(!xlsIncludes(buffer, value), 'XLS contains foreign text'))
    return { container: 'ole2' }
  }
  if (format === 'PDF') {
    assert(buffer.toString('latin1', 0, 5) === '%PDF-', 'PDF signature is absent')
    const text = pdfText(buffer)
    present.forEach((value) => assert(text.includes(value), 'PDF lacks expected Cyrillic text: ' + value))
    absent.forEach((value) => assert(!text.includes(value), 'PDF contains foreign text'))
    return { signature: '%PDF', cyrillic: /[А-Яа-яЁё]/.test(text) }
  }
  if (format === 'PNG') {
    assert(buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), 'PNG signature is absent')
    return { signature: 'PNG', width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) }
  }
  const json = JSON.parse(buffer.toString('utf8'))
  const text = JSON.stringify(json)
  present.forEach((value) => assert(text.includes(value), 'JSON lacks expected text'))
  absent.forEach((value) => assert(!text.includes(value), 'JSON contains foreign text'))
  return { parsed: true, rows: json.rows.length }
}

const percentile = (values, share) => {
  const sorted = [...values].sort((left, right) => left - right)
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * share) - 1)]
}

const timing = (values) => ({
  count: values.length,
  maxMs: Math.round(Math.max(...values)),
  p95Ms: Math.round(percentile(values, 0.95))
})

async function previewAll(page, request) {
  const rows = []
  let columns
  let total
  for (let pageNumber = 0; ; pageNumber += 1) {
    const body = requireStatus(
      await api(page, 'POST', '/api/reports/preview?page=' + pageNumber + '&size=200', { body: request }),
      200,
      'Report preview failed'
    )
    columns = body.columns
    total = body.total
    rows.push(...body.items)
    if (rows.length >= total || body.items.length === 0) {
      break
    }
  }
  assert(rows.length === total, 'Preview pages do not add up to the total')
  return { columns, rows, total }
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
    await api(page, 'POST', '/api/reports', { body: request, key: 'release-smoke-' + label + '-' + randomUUID() }),
    202,
    'Report order failed'
  )
  const job = await waitForJob(page, created.jobId)
  assert(job.status === 'SUCCEEDED', 'Report job failed: ' + (job.error?.code ?? ''))
  const download = await api(page, 'GET', '/api/report-jobs/' + created.jobId + '/result', { binary: true })
  assert(download.status === 200 && download.base64, 'Report download failed: ' + download.status)
  assert(download.disposition?.includes("filename*=UTF-8''"), 'Report file name is not UTF-8 encoded')
  return { jobId: created.jobId, job, buffer: Buffer.from(download.base64, 'base64'), type: download.type }
}

const kinds = ['PORTFOLIO', 'EVENTS']
const groupings = ['STAGE', 'ORGANIZATION', 'DIRECTION', 'PROGRAM', 'PRODUCT', 'MANAGER', 'MONTH']

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
  const outputDirectory = process.env.RELEASE_SMOKE_DIR || fs.mkdtempSync(path.join(os.tmpdir(), 'rtk-release-smoke-'))
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
  const nonce = Date.now().toString() + '-' + randomUUID().slice(0, 8)
  const today = moscowToday()

  phase = 'swagger'
  const swagger = await openPage(origin + '/swagger-ui/index.html')
  await swagger.waitFor(
    () => swagger.evaluate("document.querySelectorAll('.opblock').length > 20 && Boolean(document.querySelector('.info .title'))"),
    'Swagger UI did not render operations'
  )
  result.swagger = await swagger.evaluate(`(() => ({
    operations: document.querySelectorAll('.opblock').length,
    specUrl: performance.getEntriesByType('resource').some((entry) => new URL(entry.name).pathname === '/openapi.yaml'),
    title: document.querySelector('.info .title').textContent.trim().slice(0, 60)
  }))()`)
  assert(result.swagger.specUrl, 'Swagger UI did not load /openapi.yaml')
  const spec = await fetch(origin + '/openapi.yaml', { headers: { 'Accept-Encoding': 'gzip' } })
  result.swagger.specEncoding = spec.headers.get('content-encoding')
  await spec.arrayBuffer()
  assert(spec.ok && result.swagger.specEncoding === 'gzip', 'nginx did not gzip /openapi.yaml')

  phase = 'login'
  const kamA = await login('kam-a')
  const kamB = await login('kam-b')
  const leader = await login('leader')
  const admin = await login('admin')

  phase = 'errors'
  const unknown = await api(kamA, 'GET', '/api/unknown-release-smoke')
  assert(unknown.status === 404 && unknown.body?.code === 'NOT_FOUND' && unknown.body?.requestId, 'Unknown API path is not a JSON 404')
  const tooLarge = await kamA.evaluate(`(async () => {
    const csrf = await (await fetch('/api/csrf')).json()
    const form = new FormData()
    form.set('file', new File([new Uint8Array(22 * 1024 * 1024)], 'large.pdf', { type: 'application/pdf' }))
    const response = await fetch('/api/interactions/00000000-0000-0000-0000-000000000000/attachments', {
      method: 'POST',
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': 'release-smoke-large-' + Date.now() },
      body: form
    })
    return { status: response.status, type: response.headers.get('content-type'), body: await response.json() }
  })()`)
  assert(
    tooLarge.status === 413 && tooLarge.type.includes('application/json') && tooLarge.body?.code === 'PAYLOAD_TOO_LARGE'
      && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(tooLarge.body?.requestId ?? '')
      && /[А-Яа-я]/.test(tooLarge.body?.message ?? ''),
    'Oversized upload is not a JSON 413 in the ApiError format'
  )
  result.errors = { unknown: unknown.status, unknownCode: unknown.body.code, tooLarge: tooLarge.status, tooLargeCode: tooLarge.body.code }

  phase = 'setup'
  const kamBOrganizations = requireStatus(await api(kamB, 'GET', '/api/organizations?page=0&size=100&sort=name,asc'), 200, 'KAM B organizations are unavailable')
  const foreignOrganization = kamBOrganizations.items.find((item) => item.name === 'Университет Б')
  assert(foreignOrganization, 'Demo organization B is unavailable to KAM B')
  const foreignTitle = 'Release smoke foreign ' + nonce
  requireStatus(
    await api(kamB, 'POST', '/api/interactions', { body: { organizationId: foreignOrganization.id, title: foreignTitle, contactIds: [] }, key: 'release-smoke-foreign-' + nonce }),
    201,
    'Foreign interaction creation failed'
  )
  const kamAOrganizations = requireStatus(await api(kamA, 'GET', '/api/organizations?page=0&size=100&sort=name,asc'), 200, 'KAM A organizations are unavailable')
  const organization = kamAOrganizations.items.find((item) => item.name === 'Университет А')
  assert(organization, 'Demo organization A is unavailable to KAM A')
  assert(!kamAOrganizations.items.some((item) => item.id === foreignOrganization.id), 'KAM A sees organization B')
  const programs = requireStatus(await api(kamA, 'GET', '/api/programs?page=0&size=100'), 200, 'Programs are unavailable')
  const products = requireStatus(await api(kamA, 'GET', '/api/products?page=0&size=100'), 200, 'Products are unavailable')
  const directions = requireStatus(await api(kamA, 'GET', '/api/directions?page=0&size=100'), 200, 'Directions are unavailable')
  assert(programs.items?.length && products.items?.length, 'Program and product catalogs are empty')
  const title = 'Release smoke ' + nonce
  let interaction = requireStatus(
    await api(kamA, 'POST', '/api/interactions', { body: { organizationId: organization.id, title, contactIds: [] }, key: 'release-smoke-create-' + nonce }),
    201,
    'Interaction creation failed'
  )

  phase = 'workflow-plan'
  const planRequest = {
    version: interaction.version,
    nextAction: 'Release smoke next step',
    nextActionAt: new Date(Date.now() + 7 * 86400000).toISOString(),
    programId: programs.items[0].id,
    productIds: [products.items[0].id]
  }
  const planKey = 'release-smoke-plan-' + nonce
  const planned = requireStatus(
    await api(kamA, 'PATCH', '/api/interactions/' + interaction.id, { body: planRequest, key: planKey }),
    200,
    'Plan update failed'
  )
  assert(planned.version === interaction.version + 1 && planned.program?.id === programs.items[0].id, 'Plan update did not apply')
  const planReplay = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + interaction.id, { body: planRequest, key: planKey }), 200, 'Plan replay failed')
  assert(planReplay.version === planned.version, 'Plan replay changed the version')
  const stalePlan = await api(kamA, 'PATCH', '/api/interactions/' + interaction.id, {
    body: { ...planRequest, nextAction: 'Release smoke stale step' },
    key: 'release-smoke-plan-stale-' + nonce
  })
  assert(stalePlan.status === 409 && stalePlan.body?.code === 'VERSION_CONFLICT' && stalePlan.body?.currentVersion === planned.version, 'Stale plan update was not a version conflict')
  const leaderPlan = await api(leader, 'PATCH', '/api/interactions/' + interaction.id, {
    body: { version: planned.version, nextAction: 'Leader step' },
    key: 'release-smoke-plan-leader-' + nonce
  })
  const foreignPlan = await api(kamB, 'PATCH', '/api/interactions/' + interaction.id, {
    body: { version: planned.version, nextAction: 'Foreign step' },
    key: 'release-smoke-plan-foreign-' + nonce
  })
  assert(foreignPlan.status === 404, 'Foreign plan update was visible')
  assert(
    leaderPlan.status === 200 && leaderPlan.body?.nextAction === 'Leader step' && leaderPlan.body?.version === planned.version + 1,
    'Leader could not update the plan of a team card'
  )
  interaction = leaderPlan.body

  phase = 'workflow-graph'
  const current = interaction.stages.find((stage) => stage.id === interaction.currentStageId)
  const insertedName = 'Release smoke inserted ' + nonce
  const edited = requireStatus(
    await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/stage-edits', {
      body: { version: interaction.version, operations: [{ type: 'ADD_AFTER', afterId: current.id, name: insertedName, optional: false }] },
      key: 'release-smoke-add-after-' + nonce
    }),
    200,
    'ADD_AFTER failed'
  )
  const inserted = edited.stages.find((stage) => stage.name === insertedName)
  assert(inserted && inserted.order === current.order + 1, 'Inserted stage has the wrong position')
  assert(edited.allowedTransitions.some((option) => option.stageId === inserted.id && !option.commentRequired), 'Inserted stage is not the next step from the current stage')
  const afterInserted = edited.stages.find((stage) => stage.order === inserted.order + 1)
  assert(edited.transitions.some((edge) => edge.fromStageId === inserted.id && edge.toStageId === afterInserted.id), 'Inserted stage does not lead to the following stage')
  interaction = requireStatus(
    await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/transitions', {
      body: { version: edited.version, toStageId: inserted.id, comment: null },
      key: 'release-smoke-to-inserted-' + nonce
    }),
    200,
    'Transition to the inserted stage failed'
  )
  const history = requireStatus(await api(kamA, 'GET', '/api/interactions/' + interaction.id + '/events'), 200, 'History is unavailable')
  const types = history.map((event) => event.type)
  assert(['CREATED', 'PLAN_UPDATED', 'STAGES_EDITED', 'TRANSITIONED'].every((type) => types.includes(type)), 'History lacks new event types')
  assert(types.filter((type) => type === 'PLAN_UPDATED').length === 2, 'Plan replay duplicated the history event')
  const leaderEvents = history.filter((event) => event.actorDisplayName === 'Руководитель')
  assert(
    leaderEvents.length === 1 && leaderEvents[0].type === 'PLAN_UPDATED'
      && history.every((event) => event === leaderEvents[0] || event.actorDisplayName === 'КАМ А'),
    'History author name is not the profile display name'
  )
  result.workflow = {
    plan: 200,
    planReplay: 200,
    stalePlan: stalePlan.status,
    leaderPlan: leaderPlan.status,
    foreignPlan: foreignPlan.status,
    addAfter: 200,
    insertedReachable: true,
    history: types,
    author: history[0].actorDisplayName
  }

  phase = 'timing'
  const transitionTimes = []
  const commentTimes = []
  const previewTimes = []
  let route = requireStatus(
    await api(kamA, 'POST', '/api/interactions', { body: { organizationId: organization.id, title: 'Release smoke timing ' + nonce, contactIds: [] }, key: 'release-smoke-timing-' + nonce }),
    201,
    'Timing interaction creation failed'
  )
  for (let step = 0; step < 10; step += 1) {
    const order = route.stages.find((stage) => stage.id === route.currentStageId).order
    const next = route.allowedTransitions.find((option) => !option.commentRequired && route.stages.find((stage) => stage.id === option.stageId)?.order === order + 1)
    if (!next) {
      break
    }
    const response = await api(kamA, 'POST', '/api/interactions/' + route.id + '/transitions', {
      body: { version: route.version, toStageId: next.stageId, comment: null },
      key: 'release-smoke-timing-transition-' + step + '-' + nonce
    })
    route = requireStatus(response, 200, 'Timing transition failed')
    transitionTimes.push(response.elapsed)
  }
  for (let step = 0; step < 10; step += 1) {
    const response = await api(kamA, 'POST', '/api/interactions/' + route.id + '/comments', {
      body: { version: route.version, stageId: route.currentStageId, text: 'Release smoke timing comment ' + step },
      key: 'release-smoke-timing-comment-' + step + '-' + nonce
    })
    route = { ...route, version: requireStatus(response, 200, 'Timing comment failed').interaction?.version ?? route.version + 1 }
    commentTimes.push(response.elapsed)
  }
  for (let step = 0; step < 10; step += 1) {
    const response = await api(kamA, 'POST', '/api/reports/preview?page=0&size=50', {
      body: { kind: step % 2 ? 'EVENTS' : 'PORTFOLIO', from: today, to: today, filters: {}, columns: [] }
    })
    requireStatus(response, 200, 'Timing preview failed')
    previewTimes.push(response.elapsed)
  }
  result.timing = { transitions: timing(transitionTimes), comments: timing(commentTimes), previews: timing(previewTimes) }

  phase = 'reports'
  result.reports = {}
  for (const [name, page] of [['kam-a', kamA], ['leader', leader]]) {
    const actor = {}
    for (const kind of kinds) {
      const request = { kind, from: today, to: today, filters: {}, columns: [] }
      const preview = await previewAll(page, request)
      const previewText = JSON.stringify(preview.rows)
      assert(previewText.includes(title), 'Preview lacks the own interaction')
      assert(!previewText.includes(foreignTitle), 'Preview contains the foreign interaction')
      const foreignFilter = requireStatus(
        await api(page, 'POST', '/api/reports/preview?page=0&size=10', { body: { ...request, filters: { organizationIds: [foreignOrganization.id] } } }),
        200,
        'Foreign filter preview failed'
      )
      assert(foreignFilter.total === 0, 'Foreign organization filter returned rows')
      const statistics = {}
      for (const groupBy of groupings) {
        const response = await api(page, 'POST', '/api/statistics', { body: { ...request, groupBy } })
        const body = requireStatus(response, 200, 'Statistics ' + kind + ' ' + groupBy + ' failed')
        assert(body.total === preview.total, 'Statistics total differs from the preview')
        assert(!body.items.some((item) => item.key === foreignOrganization.id || item.label === 'Университет Б'), 'Statistics contains the foreign organization')
        statistics[groupBy] = { total: body.total, groups: body.items.length, unknown: body.unknownCount }
        if (groupBy === 'STAGE') {
          const counted = new Map()
          preview.rows.forEach((row) => counted.set(row.STAGE, (counted.get(row.STAGE) ?? 0) + 1))
          assert(body.items.every((item) => (counted.get(item.label) ?? 0) === item.count), 'Stage statistics differ from preview rows')
          assert(body.items.reduce((sum, item) => sum + item.count, 0) + body.unknownCount === body.total, 'Stage statistics do not add up')
        }
        if (groupBy === 'ORGANIZATION') {
          const counted = new Map()
          preview.rows.forEach((row) => counted.set(row.ORGANIZATION, (counted.get(row.ORGANIZATION) ?? 0) + 1))
          assert(body.items.every((item) => (counted.get(item.label) ?? 0) === item.count), 'Organization statistics differ from preview rows')
        }
      }
      if (kind === 'PORTFOLIO') {
        const activityRequest = { ...request, periodBasis: 'ACTIVITY' }
        const activityPreview = await previewAll(page, activityRequest)
        for (const groupBy of groupings.filter((value) => value !== 'MONTH')) {
          const body = requireStatus(await api(page, 'POST', '/api/statistics', { body: { ...activityRequest, groupBy } }), 200, 'Activity statistics ' + groupBy + ' failed')
          assert(body.total === activityPreview.total, 'Activity statistics total differs from the preview')
        }
      }
      const combinedRequest = {
        ...request,
        filters: {
          organizationIds: [organization.id],
          organizationType: 'UNIVERSITY',
          directionIds: directions.items.map((item) => item.id),
          includeNoDirection: true,
          programIds: [programs.items[0].id],
          includeNoProgram: true,
          productIds: [products.items[0].id],
          includeNoProduct: true,
          managerIds: requireStatus(await api(page, 'GET', '/api/report-filters/managers'), 200, 'Manager options are unavailable').map((item) => item.id),
          includeNoManager: true,
          stages: [
            insertedName,
            ...requireStatus(await api(page, 'GET', '/api/report-filters/stages'), 200, 'Stage options are unavailable')
              .filter((stage) => stage !== insertedName)
          ].slice(0, 100)
        }
      }
      const combined = await previewAll(page, combinedRequest)
      assert(combined.total > 0 && JSON.stringify(combined.rows).includes(title), 'Combined filter lost the own interaction')
      for (const groupBy of groupings) {
        const body = requireStatus(await api(page, 'POST', '/api/statistics', { body: { ...combinedRequest, groupBy } }), 200, 'Combined statistics ' + groupBy + ' failed')
        assert(body.total === combined.total, 'Combined statistics total differs from the preview')
      }
      const combinedFile = await orderFile(page, { ...combinedRequest, format: 'JSON' }, name + '-' + kind + '-combined')
      assert(isDeepStrictEqual(JSON.parse(combinedFile.buffer.toString('utf8')).rows, combined.rows), 'Combined export differs from the combined preview')
      const narrowed = await previewAll(page, { ...request, filters: { programIds: [programs.items[0].id] } })
      assert(narrowed.rows.every((row) => row.PROGRAM === programs.items[0].name), 'Program filter returned other programs')
      const withoutProgram = await previewAll(page, { ...request, filters: { includeNoProgram: true } })
      assert(withoutProgram.rows.every((row) => row.PROGRAM === null) && withoutProgram.total + narrowed.total <= preview.total, 'Rows without a program are not separated')
      const activity = await api(page, 'POST', '/api/statistics', { body: { ...request, periodBasis: 'ACTIVITY', groupBy: 'MONTH' } })
      if (kind === 'PORTFOLIO') {
        assert(activity.status === 400 && activity.body?.fieldErrors?.groupBy, 'MONTH over ACTIVITY was not rejected')
      }
      const files = {}
      for (const format of ['XLSX', 'XLS', 'PDF', 'JSON']) {
        const file = await orderFile(page, { ...request, format }, name + '-' + kind + '-' + format)
        fs.writeFileSync(path.join(outputDirectory, name + '-' + kind + '.' + format.toLowerCase()), file.buffer)
        files[format] = { rowCount: file.job.rowCount, ...checkFile(format, file.buffer, { present: format === 'PDF' ? ['Университет А'] : [title, 'Университет А'], absent: [foreignTitle, 'Университет Б'] }) }
        assert(file.job.rowCount === preview.total, format + ' row count differs from the preview')
        if (format === 'JSON') {
          const json = JSON.parse(file.buffer.toString('utf8'))
          assert(isDeepStrictEqual(json.columns, preview.columns), 'JSON columns differ from the preview')
          assert(isDeepStrictEqual(json.rows, preview.rows), 'JSON rows differ from the preview')
        }
        if (format === 'XLSX') {
          assert(files.XLSX.sheetRows >= preview.total + 1, 'XLSX has fewer rows than the preview')
        }
      }
      const monthStart = new Date(Date.UTC(Number(today.slice(0, 4)), Number(today.slice(5, 7)) - 3, 1)).toISOString().slice(0, 10)
      const chartRequests = [
        { ...request, groupBy: 'PROGRAM' },
        { ...request, from: monthStart, groupBy: 'MONTH' }
      ]
      for (const chartRequest of chartRequests) {
        const statistics = requireStatus(await api(page, 'POST', '/api/statistics', { body: chartRequest }), 200, 'Chart statistics failed')
        if (chartRequest.groupBy === 'PROGRAM') {
          assert(statistics.unknownCount > 0, 'Program statistics has no rows without a program')
        } else {
          assert(statistics.items.length === 3 && statistics.items.some((item) => item.count === 0) && statistics.unknownCount === 0, 'Month statistics does not keep zero months')
        }
        for (const format of ['PNG', 'PDF']) {
          const chart = await orderFile(page, { ...chartRequest, format }, name + '-' + kind + '-chart-' + chartRequest.groupBy + '-' + format)
          fs.writeFileSync(path.join(outputDirectory, name + '-' + kind + '-chart-' + chartRequest.groupBy.toLowerCase() + '.' + format.toLowerCase()), chart.buffer)
          const present = format === 'PDF'
            ? ['Таблица основания диаграммы', 'Всего строк в выборке\n' + statistics.total, ...statistics.items.map((item) => item.label), ...(chartRequest.groupBy === 'PROGRAM' ? ['Не указано\n' + statistics.unknownCount] : [])]
            : []
          files['chart' + chartRequest.groupBy + format] = checkFile(format, chart.buffer, { present, absent: ['Университет Б'] })
        }
      }
      actor[kind] = { rows: preview.total, columns: preview.columns.length, statistics, files }
    }
    result.reports[name] = actor
  }

  phase = 'report-revocation'
  const revoked = await orderFile(leader, { kind: 'PORTFOLIO', from: today, to: today, filters: {}, columns: [], format: 'JSON' }, 'leader-revoked')
  const profiles = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles?page=0&size=100'), 200, 'CRM profiles are unavailable')
  const leaderProfile = profiles.items.find((item) => item.displayName === 'Руководитель')
  assert(leaderProfile?.active, 'Leader profile is unavailable')
  const deactivated = requireStatus(
    await api(admin, 'PATCH', '/api/admin/crm-profiles/' + leaderProfile.id, { body: { version: leaderProfile.version, active: false }, key: 'release-smoke-deactivate-' + nonce }),
    200,
    'Leader deactivation failed'
  )
  const inactiveMe = await api(leader, 'GET', '/api/me')
  const inactiveJob = await api(leader, 'GET', '/api/report-jobs/' + revoked.jobId)
  const reactivated = requireStatus(
    await api(admin, 'PATCH', '/api/admin/crm-profiles/' + leaderProfile.id, { body: { version: deactivated.version, active: true }, key: 'release-smoke-reactivate-' + nonce }),
    200,
    'Leader reactivation failed'
  )
  assert(reactivated.accessRevision > leaderProfile.accessRevision, 'Access revision did not change')
  const goneJob = await api(leader, 'GET', '/api/report-jobs/' + revoked.jobId)
  const goneResult = await api(leader, 'GET', '/api/report-jobs/' + revoked.jobId + '/result', { binary: true })
  const recent = requireStatus(await api(leader, 'GET', '/api/report-jobs'), 200, 'Recent report jobs are unavailable')
  assert(inactiveMe.status === 403 && inactiveMe.body?.code === 'CRM_PROFILE_REQUIRED', 'Inactive leader was not rejected')
  assert(goneJob.status === 410 && goneJob.body?.code === 'REPORT_ACCESS_CHANGED', 'Revoked report job is still available')
  assert(goneResult.status === 410 && goneResult.body?.code === 'REPORT_ACCESS_CHANGED' && !goneResult.base64, 'Revoked report file is still downloadable')
  assert(!recent.some((job) => job.id === revoked.jobId), 'Revoked report is still listed')
  result.revocation = { inactiveMe: inactiveMe.status, inactiveJob: inactiveJob.status, job: goneJob.status, result: goneResult.status, listed: false }

  phase = 'import-template'
  result.importTemplate = await admin.evaluate(`(async () => {
    const csrf = await (await fetch('/api/csrf')).json()
    const headers = { [csrf.headerName]: csrf.token }
    const template = await fetch('/catalog-import-template.xlsx')
    if (template.status !== 200) throw new Error('Template is unavailable')
    const blob = await template.blob()
    const inspectForm = new FormData()
    inspectForm.set('file', new File([blob], 'catalog-import-template.xlsx'))
    const inspected = await fetch('/api/imports/inspect', { method: 'POST', headers, body: inspectForm })
    const sheets = (await inspected.json()).sheets
    const sheet = sheets.find((item) => item.name === 'Каталог')
    const tzHeaders = ['Название ВУЗа', 'Вендор', 'ПО', 'Номер договора', 'Подписание лицензии',
      'Срок действия лицензии (год)', 'Статус по передаче', 'ФИО Менеджера', 'Ответственные от ВУЗа', 'Комментарий']
    const tzFields = ['organizationName', 'vendorName', 'productName', 'contractNumber', 'licenseSigned',
      'licenseExpiryYear', 'transferStatus', 'managerName', 'contactName', 'comment']
    if (inspected.status !== 200 || !sheet || sheet.headers.length !== 10 || !tzHeaders.every((header) => sheet.headers.includes(header))) {
      throw new Error('Template does not have exactly the ten TZ columns')
    }
    const form = new FormData()
    form.set('file', new File([blob], 'catalog-import-template.xlsx'))
    form.set('profile', 'AGREEMENT')
    form.set('sheet', 'Каталог')
    form.set('mapping', new Blob([JSON.stringify({ columns: Object.fromEntries(tzFields.map((field, index) => [field, tzHeaders[index]])), rowTargets: {}, transferStatuses: {} })], { type: 'application/json' }))
    const preview = await fetch('/api/imports/preview', { method: 'POST', headers, body: form })
    if (preview.status !== 202) throw new Error('Template preview failed: ' + preview.status)
    const created = await preview.json()
    const protocol = await (await fetch('/api/imports/' + encodeURIComponent(created.importId))).json()
    return { inspect: inspected.status, headers: sheet.headers.length, preview: preview.status, status: protocol.status, rows: protocol.rows.map((row) => ({ status: row.status, errors: Object.keys(row.fieldErrors ?? {}) })) }
  })()`)

  phase = 'ui-no-reload'
  const uiTitle = 'Release smoke UI ' + nonce
  const uiInteraction = requireStatus(
    await api(kamA, 'POST', '/api/interactions', { body: { organizationId: organization.id, title: uiTitle, contactIds: [] }, key: 'release-smoke-ui-' + nonce }),
    201,
    'UI interaction creation failed'
  )
  const uiTarget = uiInteraction.allowedTransitions.find((option) => !option.commentRequired)
  assert(uiTarget, 'UI interaction has no transition without a comment')
  const dialogForms = { 'Переход этапа': 'interaction-transition-form', 'Комментарий': 'interaction-comment-form' }
  const formByTitle = (titleText) => `document.querySelector('dialog[open] form.${dialogForms[titleText]}')`
  const openCardDialog = async (buttonText, titleText) => {
    await kamA.evaluate(`[...document.querySelectorAll('.work-card__actions button')].find((button) => button.textContent.trim() === ${JSON.stringify(buttonText)}).click()`)
    await kamA.waitFor(() => kamA.evaluate(`Boolean(${formByTitle(titleText)})`), 'UI dialog did not open: ' + titleText)
  }
  const uiState = () => kamA.evaluate(`({
    marker: window.__releaseSmokeMarker === ${JSON.stringify(nonce)},
    navigations: performance.getEntriesByType('navigation').length,
    scroll: document.scrollingElement.scrollTop
  })`)
  await call('Page.navigate', { url: origin + '/#/organizations' }, kamA.sessionId)
  await kamA.waitFor(
    () => kamA.evaluate("Boolean([...document.querySelectorAll('.organization-list-item')].find((item) => item.textContent.includes('Университет А')))"),
    'UI organization list did not load'
  )
  await kamA.evaluate("[...document.querySelectorAll('.organization-list-item')].find((item) => item.textContent.includes('Университет А')).click()")
  await kamA.waitFor(
    () => kamA.evaluate(`Boolean([...document.querySelectorAll('.interaction-list-item')].find((item) => item.querySelector('.interaction-list-item__title')?.textContent === ${JSON.stringify(uiTitle)}))`),
    'UI interaction did not load'
  )
  await kamA.evaluate(`[...document.querySelectorAll('.interaction-list-item')].find((item) => item.querySelector('.interaction-list-item__title')?.textContent === ${JSON.stringify(uiTitle)}).click()`)
  await kamA.waitFor(
    () => kamA.evaluate("Boolean([...document.querySelectorAll('.work-card__actions button')].find((button) => button.textContent.trim() === 'Перейти к следующему этапу'))"),
    'UI work card did not load'
  )
  await kamA.evaluate(`(() => {
    window.__releaseSmokeMarker = ${JSON.stringify(nonce)}
    window.scrollTo(0, 200)
  })()`)
  await pause(300)
  const uiScrollBefore = (await uiState()).scroll
  await openCardDialog('Перейти к следующему этапу', 'Переход этапа')
  await kamA.evaluate(`(() => {
    const form = ${formByTitle('Переход этапа')}
    const select = form.querySelector('select')
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(select, ${JSON.stringify(uiTarget.stageId)})
    select.dispatchEvent(new Event('change', { bubbles: true }))
  })()`)
  await kamA.waitFor(() => kamA.evaluate(`!${formByTitle('Переход этапа')}.querySelector('button[type=submit]').disabled`), 'UI transition is not ready')
  await kamA.evaluate(`${formByTitle('Переход этапа')}.querySelector('button[type=submit]').click()`)
  await kamA.waitFor(
    () => kamA.evaluate(`document.querySelector('li[aria-current="step"] .interaction-path__name')?.textContent === ${JSON.stringify(uiTarget.stageName)}`),
    'UI transition did not update the path map'
  )
  const uiAfterTransition = await uiState()
  const uiComment = 'Release smoke UI comment ' + nonce
  await openCardDialog('Комментарий', 'Комментарий')
  await kamA.evaluate(`(() => {
    const form = ${formByTitle('Комментарий')}
    const select = form.querySelector('select')
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(select, ${JSON.stringify(uiTarget.stageId)})
    select.dispatchEvent(new Event('change', { bubbles: true }))
    const textarea = form.querySelector('textarea')
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(textarea, ${JSON.stringify(uiComment)})
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
  })()`)
  await kamA.waitFor(() => kamA.evaluate(`!${formByTitle('Комментарий')}.querySelector('button[type=submit]').disabled`), 'UI comment is not ready')
  await kamA.evaluate(`${formByTitle('Комментарий')}.querySelector('button[type=submit]').click()`)
  await kamA.waitFor(
    () => kamA.evaluate(`!${formByTitle('Комментарий')} && Boolean(document.querySelector('.card-notice')?.textContent.includes('Комментарий добавлен'))`),
    'UI comment was not accepted'
  )
  const uiEvents = requireStatus(await api(kamA, 'GET', '/api/interactions/' + uiInteraction.id + '/events'), 200, 'UI history is unavailable')
  assert(uiEvents.some((event) => event.type === 'COMMENTED' && event.comment === uiComment), 'UI comment is absent from history')
  const uiAfterComment = await uiState()
  await kamA.evaluate("location.hash = '#/reports'")
  await kamA.waitFor(() => kamA.evaluate("document.querySelectorAll('.report-card').length > 0"), 'UI reports catalog did not open')
  await kamA.evaluate("document.querySelector('.report-card[href=\"#/reports/portfolio\"]').click()")
  await kamA.waitFor(() => kamA.evaluate("Boolean(document.querySelector('.reports__form .report-period__preset'))"), 'UI reports screen did not open')
  await kamA.evaluate("[...document.querySelectorAll('.report-period__preset')].find((button) => button.textContent.trim() === 'Свой диапазон').click()")
  await kamA.waitFor(() => kamA.evaluate("document.querySelectorAll('.reports__form input[type=date]').length === 2"), 'UI custom period did not open')
  await kamA.evaluate(`(() => {
    for (const input of document.querySelectorAll('.reports__form input[type=date]')) {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, ${JSON.stringify(today)})
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }
  })()`)
  await kamA.waitFor(
    () => kamA.evaluate(`[...document.querySelectorAll('.reports__form input[type=date]')].every((input) => input.value === ${JSON.stringify(today)})`),
    'UI period filter was not set'
  )
  await kamA.waitFor(() => kamA.evaluate("!document.querySelector('.reports__actions button[type=submit]').disabled"), 'UI preview is not ready')
  await kamA.evaluate("document.querySelector('.reports__actions button[type=submit]').click()")
  const expectedTotal = requireStatus(
    await api(kamA, 'POST', '/api/reports/preview?page=0&size=1', { body: { kind: 'PORTFOLIO', from: today, to: today, filters: {}, columns: [] } }),
    200,
    'Control preview failed'
  ).total
  await kamA.waitFor(
    () => kamA.evaluate(`document.querySelector('.reports__total')?.textContent === ${JSON.stringify('Строк в отчёте: ' + expectedTotal)}`),
    'UI preview does not show the filtered total'
  )
  await kamA.evaluate("location.hash = '#/organizations'")
  await kamA.waitFor(() => kamA.evaluate("!document.querySelector('.reports__form') && Boolean(document.querySelector('.organization-list-item'))"), 'UI organizations did not open')
  await kamA.evaluate("location.hash = '#/reports/portfolio'")
  await kamA.waitFor(() => kamA.evaluate("document.querySelectorAll('.reports__form input[type=date]').length === 2"), 'UI reports screen did not reopen')
  const uiReports = await kamA.evaluate(`({
    marker: window.__releaseSmokeMarker === ${JSON.stringify(nonce)},
    navigations: performance.getEntriesByType('navigation').length,
    periodKept: [...document.querySelectorAll('.reports__form input[type=date]')].every((input) => input.value === ${JSON.stringify(today)})
  })`)
  assert(uiScrollBefore > 0, 'UI card was not scrolled')
  assert(uiAfterTransition.marker && uiAfterTransition.navigations === 1 && Math.abs(uiAfterTransition.scroll - uiScrollBefore) < 100, 'UI transition reloaded the page or lost the scroll position')
  assert(uiAfterComment.marker && uiAfterComment.navigations === 1 && Math.abs(uiAfterComment.scroll - uiScrollBefore) < 100, 'UI comment reloaded the page or lost the scroll position')
  assert(uiReports.marker && uiReports.navigations === 1 && uiReports.periodKept, 'UI report filters were lost or the page reloaded')
  result.ui = {
    scrollBefore: Math.round(uiScrollBefore),
    scrollAfterTransition: Math.round(uiAfterTransition.scroll),
    scrollAfterComment: Math.round(uiAfterComment.scroll),
    reloads: uiReports.navigations - 1,
    previewTotal: expectedTotal,
    periodKept: uiReports.periodKept
  }

  phase = 'logout'
  const logoutPage = await login('kam-a')
  await logoutPage.waitFor(
    () => logoutPage.evaluate("Boolean([...document.querySelectorAll('button')].find((button) => button.textContent.trim() === 'Выйти'))"),
    'Logout button did not appear'
  )
  await logoutPage.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.trim() === 'Выйти').click()")
  await logoutPage.waitFor(
    () => logoutPage.evaluate(`location.origin === ${JSON.stringify(origin)} && ${loginButtonVisible}`),
    'Logout did not return to the CRM login screen'
  )
  const afterLogout = await api(logoutPage, 'GET', '/api/me')
  assert(afterLogout.status === 401, 'Session survived logout')
  await logoutPage.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')).click()")
  await logoutPage.waitFor(
    () => logoutPage.evaluate(`${keycloakFormVisible} || (location.origin === ${JSON.stringify(origin)} && !${loginButtonVisible} && document.readyState === 'complete' && performance.now() > 0 && Boolean(document.querySelector('header button')))`),
    'Neither the Keycloak form nor the CRM appeared after logout'
  )
  const formShown = await logoutPage.evaluate(keycloakFormVisible)
  assert(formShown, 'Login after logout did not require the Keycloak form')
  result.logout = { meAfterLogout: afterLogout.status, keycloakFormAfterLogout: formShown }

  result.outputDirectory = outputDirectory
  console.log(JSON.stringify(result))
} catch (error) {
  const message = error instanceof Error
    ? error.message.replace(/[^\wА-Яа-яЁё .:,/-]/g, '').slice(0, 200) || 'failed'
    : 'failed'
  console.log(JSON.stringify({ oidc: false, smoke: 'failed', phase, message }))
  process.exitCode = 1
} finally {
  for (const page of pages.reverse()) {
    await call('Target.disposeBrowserContext', { browserContextId: page.contextId }).catch(() => null)
  }
  socket?.close()
}
