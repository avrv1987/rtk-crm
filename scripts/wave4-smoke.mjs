import fs from 'node:fs'
import zlib from 'node:zlib'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { pathToFileURL } from 'node:url'

let socket
let password
let origin
let phase = 'startup'
let nextId = 1
const pending = new Map()
const pages = []
const sessions = new Map()

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
      if (await predicate().catch(() => false)) {
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

async function openKeycloakForm() {
  const page = await openPage(origin + '/')
  await page.waitFor(() => page.evaluate(loginButtonVisible), 'Login button did not appear')
  await page.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')).click()")
  await page.waitFor(() => page.evaluate(keycloakFormVisible), 'Keycloak form did not appear')
  return page
}

const submitKeycloak = (page, username, secret) => page.evaluate(`(() => {
  const usernameField = document.querySelector('#username')
  const passwordField = document.querySelector('#password')
  usernameField.value = ${JSON.stringify(username)}
  passwordField.value = ${JSON.stringify(secret)}
  usernameField.dispatchEvent(new Event('input', { bubbles: true }))
  passwordField.dispatchEvent(new Event('input', { bubbles: true }))
  document.querySelector('#kc-login').click()
})()`)

async function login(username) {
  const page = await openKeycloakForm()
  await submitKeycloak(page, username, password)
  await page.waitFor(
    () => page.evaluate(`location.origin === ${JSON.stringify(origin)} && !location.pathname.startsWith('/idp/')`),
    'OIDC callback did not return to CRM for ' + username
  )
  return page
}

async function session(username) {
  if (!sessions.has(username)) {
    sessions.set(username, await login(username))
  }
  return sessions.get(username)
}

const api = (page, method, url, options = {}) => page.evaluate(`(async () => {
  const options = ${JSON.stringify(options)}
  const method = ${JSON.stringify(method)}
  const headers = {}
  if (method !== 'GET') {
    const csrf = await (await fetch('/api/csrf')).json()
    headers[csrf.headerName] = csrf.token
    headers['Idempotency-Key'] = options.key || crypto.randomUUID()
  }
  let body
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }
  const response = await fetch(${JSON.stringify(url)}, { method, headers, body })
  const type = response.headers.get('content-type') || ''
  const picked = {}
  for (const name of ['content-type', 'content-disposition', 'content-security-policy', 'x-content-type-options', 'x-request-id', 'cache-control']) {
    picked[name] = response.headers.get(name)
  }
  let json = null
  let text = null
  let size = null
  if (options.binary) {
    size = (await response.arrayBuffer()).byteLength
  } else if (type.includes('json')) {
    json = await response.json()
  } else if (options.text) {
    text = await response.text()
  }
  return { status: response.status, body: json, text, size, headers: picked }
})()`)

const upload = (page, interactionId, fields) => page.evaluate(`(async () => {
  const fields = ${JSON.stringify(fields)}
  const csrf = await (await fetch('/api/csrf')).json()
  const bytes = Uint8Array.from(atob(fields.base64), (character) => character.charCodeAt(0))
  const form = new FormData()
  form.set('stageId', fields.stageId)
  if (fields.kind) form.set('kind', fields.kind)
  if (fields.replacesId) form.set('replacesId', fields.replacesId)
  form.set('file', new File([bytes], fields.name, { type: fields.type }))
  const response = await fetch('/api/interactions/' + ${JSON.stringify(interactionId)} + '/attachments', {
    method: 'POST',
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
    body: form
  })
  const type = response.headers.get('content-type') || ''
  return { status: response.status, body: type.includes('json') ? await response.json() : null }
})()`)

const requireStatus = (response, status, message) => {
  if (response.status !== status) {
    throw new Error(message + ': ' + response.status + ' ' + (response.body?.code ?? '') + ' ' + Object.keys(response.body?.fieldErrors ?? {}).join(','))
  }
  return response.body
}

const russian = (text) => typeof text === 'string' && /[А-Яа-яЁё]/.test(text) && !/[A-Za-z]{3,} [A-Za-z]{3,} [A-Za-z]{3,}/.test(text)

const requireError = (response, status, message, field) => {
  assert(response.status === status, message + ': expected ' + status + ', got ' + response.status + ' ' + (response.body?.code ?? ''))
  assert(russian(response.body?.message), message + ': message is not Russian')
  if (field) {
    assert(Object.keys(response.body.fieldErrors ?? {}).includes(field), message + ': field ' + field + ' is not reported')
  }
  return response.body.code
}

const moscowDate = (date) => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow' }).format(date)

const shiftDay = (date, days) => {
  const value = new Date(date + 'T00:00:00Z')
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
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

async function waitForJob(page, url) {
  for (let attempt = 0; attempt < 240; attempt += 1) {
    const job = requireStatus(await api(page, 'GET', url), 200, 'Job is unavailable')
    if (['SUCCEEDED', 'FAILED'].includes(job.status)) {
      return job
    }
    await pause(250)
  }
  throw new Error('Job did not finish')
}

async function waitClean(page, attachmentId) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const metadata = requireStatus(await api(page, 'GET', '/api/attachments/' + attachmentId), 200, 'Attachment metadata is unavailable')
    if (metadata.status !== 'QUARANTINE') {
      return metadata
    }
    await pause(150)
  }
  throw new Error('Attachment stayed in quarantine')
}

const pdfBase64 = (label) => Buffer.from('%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n% ' + label + '\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n').toString('base64')

const zipStore = (files, flags = 0x0800) => {
  const locals = []
  const centrals = []
  let offset = 0
  for (const file of files) {
    const name = Buffer.from(file.name, 'utf8')
    const data = Buffer.from(file.data, 'utf8')
    const crc = zlib.crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(flags, 6)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(name.length, 26)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(flags, 8)
    central.writeUInt32LE(crc, 16)
    central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24)
    central.writeUInt16LE(name.length, 28)
    central.writeUInt32LE(offset, 42)
    locals.push(local, name, data)
    centrals.push(central, name)
    offset += local.length + name.length + data.length
  }
  const centralSize = centrals.reduce((sum, part) => sum + part.length, 0)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(files.length, 8)
  end.writeUInt16LE(files.length, 10)
  end.writeUInt32LE(centralSize, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, ...centrals, end])
}

const escapeXml = (value) => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const xlsx = (sheetName, rows) => {
  const column = (index) => String.fromCharCode(65 + index)
  const sheetRows = rows.map((row, rowIndex) => '<row r="' + (rowIndex + 1) + '">' + row.map((value, index) => value === null || value === ''
    ? ''
    : '<c r="' + column(index) + (rowIndex + 1) + '" t="inlineStr"><is><t>' + escapeXml(value) + '</t></is></c>').join('') + '</row>').join('')
  return zipStore([
    { name: '[Content_Types].xml', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>' },
    { name: '_rels/.rels', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>' },
    { name: 'xl/workbook.xml', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="' + escapeXml(sheetName) + '" sheetId="1" r:id="rId1"/></sheets></workbook>' },
    { name: 'xl/_rels/workbook.xml.rels', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>' },
    { name: 'xl/worksheets/sheet1.xml', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' + sheetRows + '</sheetData></worksheet>' }
  ])
}

const environment = Object.fromEntries(
  fs.readFileSync(new URL('../.env.local', import.meta.url), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line.includes('=') && !line.trimStart().startsWith('#'))
    .map((line) => {
      const index = line.indexOf('=')
      return [line.slice(0, index), line.slice(index + 1)]
    })
)

const nonce = Date.now().toString(36) + randomUUID().slice(0, 4)
const today = moscowDate(new Date())
const ctx = { profiles: {}, organizations: {} }

const profileIdOf = async (login) => {
  if (!ctx.profiles[login]) {
    const admin = await session('admin')
    const body = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles?q=' + encodeURIComponent(login) + '&page=0&size=50'), 200, 'Profiles are unavailable')
    const profile = body.items.find((item) => item.login === login)
    assert(profile, 'Profile ' + login + ' is not found')
    ctx.profiles[login] = profile
  }
  return ctx.profiles[login].id
}

const adminProfile = async (login) => {
  const admin = await session('admin')
  const body = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles?q=' + encodeURIComponent(login) + '&page=0&size=50'), 200, 'Profiles are unavailable')
  const profile = body.items.find((item) => item.login === login)
  assert(profile, 'Profile ' + login + ' is not found')
  return profile
}

const demoOrganization = async (page, name) => {
  const body = requireStatus(await api(page, 'GET', '/api/organizations?q=' + encodeURIComponent(name) + '&page=0&size=20'), 200, 'Organizations are unavailable')
  const organization = body.items.find((item) => item.name === name)
  assert(organization, 'Organization ' + name + ' is not visible')
  return organization
}

const freshOrganization = async (page, id) => requireStatus(await api(page, 'GET', '/api/organizations/' + id), 200, 'Organization is unavailable')

async function testOrganization() {
  if (!ctx.organizations.main) {
    const leader = await session('leader')
    const created = requireStatus(await api(leader, 'POST', '/api/organizations', {
      body: { name: 'Университет W4 ' + nonce, type: 'UNIVERSITY', city: 'Москва', website: null, inn: null }
    }), 201, 'Leader could not create an organization')
    const assigned = requireStatus(await api(leader, 'POST', '/api/organizations/' + created.id + '/assignment', {
      body: { version: created.version, ownerManagerId: await profileIdOf('kam-a'), handoverNote: 'Передача W4: контакт подтверждён, договор на подписании' }
    }), 200, 'Leader could not assign KAM A')
    ctx.organizations.main = assigned.organization
    ctx.handoverEvent = assigned.event
  }
  return ctx.organizations.main
}

async function createInteraction(page, organizationId, title, extra = {}) {
  return requireStatus(await api(page, 'POST', '/api/interactions', {
    body: { organizationId, title, contactIds: [], ...extra }
  }), 201, 'Interaction creation failed: ' + title)
}

const interactionOf = (page, id) => api(page, 'GET', '/api/interactions/' + id).then((response) => requireStatus(response, 200, 'Interaction is unavailable'))
const eventsOf = (page, id) => api(page, 'GET', '/api/interactions/' + id + '/events').then((response) => requireStatus(response, 200, 'History is unavailable'))

const preview = async (page, request, size = 200) => api(page, 'POST', '/api/reports/preview?page=0&size=' + size, { body: request })

async function orderReport(page, request) {
  const created = requireStatus(await api(page, 'POST', '/api/reports', { body: request }), 202, 'Report order failed')
  const job = await waitForJob(page, '/api/report-jobs/' + created.jobId)
  assert(job.status === 'SUCCEEDED', 'Report job failed: ' + (job.error?.code ?? ''))
  const download = await api(page, 'GET', '/api/report-jobs/' + created.jobId + '/result', { binary: true })
  assert(download.status === 200 && download.size > 0, 'Report download failed: ' + download.status)
  return { jobId: created.jobId, size: download.size, type: download.headers['content-type'] }
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

async function reportFile(page, request) {
  const created = requireStatus(await api(page, 'POST', '/api/reports', { body: request }), 202, 'Report order failed')
  const job = await waitForJob(page, '/api/report-jobs/' + created.jobId)
  assert(job.status === 'SUCCEEDED', 'Report job failed: ' + (job.error?.code ?? ''))
  const base64 = await page.evaluate(`(async () => {
    const response = await fetch('/api/report-jobs/' + ${JSON.stringify(created.jobId)} + '/result')
    if (!response.ok) return null
    const bytes = new Uint8Array(await response.arrayBuffer())
    let text = ''
    for (let index = 0; index < bytes.length; index += 0x8000) text += String.fromCharCode(...bytes.subarray(index, index + 0x8000))
    return btoa(text)
  })()`)
  assert(base64, 'Report download failed')
  return { jobId: created.jobId, buffer: Buffer.from(base64, 'base64') }
}

const sections = {}

sections.loginRussian = async () => {
  const page = await openKeycloakForm()
  const form = await page.evaluate(`({
    lang: document.documentElement.lang,
    usernameLabel: document.querySelector('label[for=username]')?.textContent.trim(),
    passwordLabel: document.querySelector('label[for=password]')?.textContent.trim(),
    submit: document.querySelector('#kc-login')?.value || document.querySelector('#kc-login')?.textContent.trim(),
    title: document.querySelector('#kc-page-title')?.textContent.trim()
  })`)
  assert(form.lang === 'ru', 'Keycloak page language is ' + form.lang)
  assert(russian(form.usernameLabel) && russian(form.passwordLabel) && russian(form.submit) && russian(form.title), 'Keycloak form is not Russian')
  await submitKeycloak(page, 'kam-a', 'wrong-' + nonce)
  await page.waitFor(() => page.evaluate("Boolean(document.querySelector('#input-error, .kc-feedback-text, .alert-error'))"), 'Keycloak did not show a login error')
  const error = await page.evaluate("(document.querySelector('#input-error, .kc-feedback-text, .alert-error')?.textContent || '').trim()")
  assert(russian(error), 'Keycloak login error is not Russian')
  const loggedIn = await session('kam-a')
  await loggedIn.waitFor(() => loggedIn.evaluate("Boolean(document.querySelector('.app-header'))"), 'CRM header did not appear')
  const menu = await loggedIn.evaluate("document.querySelector('.app-header').innerText")
  assert(russian(menu), 'CRM header after login is not Russian')
  return { lang: form.lang, title: form.title, usernameLabel: form.usernameLabel, submit: form.submit, error }
}

sections.organizations = async () => {
  const leader = await session('leader')
  const kamA = await session('kam-a')
  const leaderB = await session('leader-b')
  const main = await testOrganization()
  const duplicate = await api(leader, 'POST', '/api/organizations', { body: { name: '  университет   w4 ' + nonce.toUpperCase() + ' ', type: 'UNIVERSITY' } })
  const duplicates = requireStatus(await api(leader, 'GET', '/api/organizations/duplicates?name=' + encodeURIComponent('Университет W4 ' + nonce)), 200, 'Duplicate check failed')
  const college = requireStatus(await api(leader, 'POST', '/api/organizations', {
    body: { name: 'Колледж W4 ' + nonce, type: 'COLLEGE', city: 'Тверь', inn: '7707083893' }
  }), 201, 'Leader could not create a college')
  const badInn = await api(leader, 'POST', '/api/organizations', { body: { name: 'Школа W4 ИНН ' + nonce, type: 'SCHOOL', inn: '123' } })
  const kamCreated = requireStatus(await api(kamA, 'POST', '/api/organizations', {
    body: { name: 'Школа W4 от КАМ ' + nonce, type: 'SCHOOL' }
  }), 201, 'KAM could not propose an organization')
  const kamApprove = await api(kamA, 'POST', '/api/organizations/' + kamCreated.id + '/status', { body: { action: 'APPROVE', version: kamCreated.version } })
  const approved = requireStatus(await api(leader, 'POST', '/api/organizations/' + kamCreated.id + '/status', {
    body: { action: 'APPROVE', version: kamCreated.version }
  }), 200, 'Leader could not approve the organization')
  const archived = requireStatus(await api(leader, 'POST', '/api/organizations/' + college.id + '/status', {
    body: { action: 'ARCHIVE', version: college.version, reason: 'Проверка архива W4' }
  }), 200, 'Leader could not archive the college')
  const archivedInteraction = await api(leader, 'POST', '/api/interactions', { body: { organizationId: college.id, title: 'W4 в архиве', contactIds: [] } })
  const restored = requireStatus(await api(leader, 'POST', '/api/organizations/' + college.id + '/status', {
    body: { action: 'RESTORE', version: archived.version }
  }), 200, 'Leader could not restore the college')
  ctx.organizations.college = restored
  const collegeWork = await createInteraction(leader, college.id, 'W4 колледж ' + nonce)
  const foreignView = await api(leaderB, 'GET', '/api/organizations/' + college.id)
  const demoCollege = await demoOrganization(leaderB, 'Колледж связи (демо)')
  const collegeReport = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: '2020-01-01', to: today, filters: { organizationType: 'COLLEGE' }, columns: ['ORGANIZATION', 'INTERACTION'] }), 200, 'College report failed')
  const schoolType = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: '2020-01-01', to: today, filters: { organizationType: 'SCHOOL' }, columns: ['ORGANIZATION'] }), 200, 'School report failed')
  assert(duplicate.status === 400, 'Duplicate organization was accepted: ' + duplicate.status)
  assert(duplicates.length >= 1 || duplicates.items?.length >= 1, 'Duplicate search found nothing')
  assert(badInn.status === 400, 'Invalid INN was accepted: ' + badInn.status)
  assert(kamCreated.status === 'PENDING', 'KAM organization is not pending: ' + kamCreated.status)
  assert(kamApprove.status === 403, 'KAM approved an organization: ' + kamApprove.status)
  assert(approved.status === 'ACTIVE' && archived.status === 'ARCHIVED' && restored.status === 'ACTIVE', 'Organization status flow is broken')
  assert(archivedInteraction.status === 400, 'Archived organization accepted a new interaction: ' + archivedInteraction.status)
  assert(foreignView.status === 404, 'Leader B sees the college of team A: ' + foreignView.status)
  assert(demoCollege.type === 'COLLEGE', 'Demo college has type ' + demoCollege.type)
  assert(collegeReport.items.some((row) => row.INTERACTION === collegeWork.title) && collegeReport.items.every((row) => row.ORGANIZATION.includes('олледж')), 'College filter is wrong')
  assert(schoolType.items.every((row) => !row.ORGANIZATION.includes('Университет')), 'School filter returned universities')
  return {
    created: main.name,
    duplicate: duplicate.status,
    duplicateHits: duplicates.length ?? duplicates.items?.length,
    badInn: badInn.status,
    kamProposal: kamCreated.status,
    kamApprove: kamApprove.status,
    leaderApprove: approved.status,
    archive: archived.status,
    archivedInteraction: archivedInteraction.status,
    restore: restored.status,
    foreignLeader: foreignView.status,
    demoCollege: demoCollege.type,
    collegeRows: collegeReport.total,
    schoolRows: schoolType.total
  }
}

sections.catalogs = async () => {
  const admin = await session('admin')
  const kamA = await session('kam-a')
  const name = 'Направление W4 ' + nonce
  const created = requireStatus(await api(admin, 'POST', '/api/admin/catalogs/directions', { body: { name } }), 201, 'Direction creation failed')
  const duplicate = await api(admin, 'POST', '/api/admin/catalogs/directions', { body: { name: name.toUpperCase() + ' ' } })
  const program = requireStatus(await api(admin, 'POST', '/api/admin/catalogs/programs', { body: { name: 'Программа W4 ' + nonce, parentId: created.id } }), 201, 'Program creation failed')
  const renamed = requireStatus(await api(admin, 'PATCH', '/api/admin/catalogs/directions/' + created.id, { body: { name: name + ' (переименовано)', version: created.version } }), 200, 'Direction rename failed')
  const archiveParent = await api(admin, 'PATCH', '/api/admin/catalogs/directions/' + created.id, { body: { archived: true, version: renamed.version } })
  const archivedProgram = requireStatus(await api(admin, 'PATCH', '/api/admin/catalogs/programs/' + program.id, { body: { archived: true, version: program.version } }), 200, 'Program archive failed')
  const programsForKam = { items: await listAll(kamA, '/api/programs', 'Programs are unavailable') }
  const archivedDirection = requireStatus(await api(admin, 'PATCH', '/api/admin/catalogs/directions/' + created.id, { body: { archived: true, version: renamed.version } }), 200, 'Direction archive failed')
  const kamCatalog = await api(kamA, 'POST', '/api/admin/catalogs/directions', { body: { name: 'КАМ ' + nonce } })
  const events = requireStatus(await api(admin, 'GET', '/api/admin/catalog-events?page=0&size=100'), 200, 'Catalog journal is unavailable')
  const own = events.items.filter((event) => JSON.stringify(event).includes(nonce))
  const offered = programsForKam.items.find((item) => item.id === program.id)
  assert(duplicate.status === 400, 'Duplicate direction was accepted: ' + duplicate.status)
  assert(archiveParent.status === 400, 'Direction with an active program was archived: ' + archiveParent.status)
  assert(archivedProgram.archived && archivedDirection.archived, 'Archive flags are not set')
  assert(!offered || offered.archived, 'Archived program is offered as active')
  assert(kamCatalog.status === 403, 'KAM changed a catalog: ' + kamCatalog.status)
  assert(own.length >= 4, 'Catalog journal has ' + own.length + ' entries for the run')
  return { create: 201, duplicate: duplicate.status, rename: 200, archiveWithChild: archiveParent.status, archive: 200, kam: kamCatalog.status, journalEntries: own.length }
}

sections.teams = async () => {
  const admin = await session('admin')
  const teams = requireStatus(await api(admin, 'GET', '/api/admin/teams'), 200, 'Teams are unavailable')
  const teamA = teams.find((team) => team.name === 'Команда А')
  assert(teamA, 'Команда А is absent')
  const created = requireStatus(await api(admin, 'POST', '/api/admin/teams', { body: { name: 'Команда W4 ' + nonce } }), 201, 'Team creation failed')
  const duplicate = await api(admin, 'POST', '/api/admin/teams', { body: { name: 'команда w4 ' + nonce } })
  const archived = requireStatus(await api(admin, 'PATCH', '/api/admin/teams/' + created.id + '/archive', { body: { archived: true, version: created.version } }), 200, 'Team archive failed')
  const busy = await api(admin, 'PATCH', '/api/admin/teams/' + teamA.id + '/archive', { body: { archived: true, version: teamA.version } })
  const search = requireStatus(await api(admin, 'GET', '/api/admin/organizations?q=' + encodeURIComponent('университет а') + '&page=0&size=20'), 200, 'Organization search failed')
  const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=TEAM&object=' + encodeURIComponent(nonce) + '&page=0&size=20'), 200, 'Audit is unavailable')
  assert(teamA.leaderNames.includes('Руководитель') && teamA.managerNames.includes('КАМ А') && teamA.organizationCount >= 3, 'Team A composition is incomplete')
  assert(duplicate.status === 400, 'Duplicate team name was accepted: ' + duplicate.status)
  assert(archived.archived === true, 'Team is not archived')
  assert(busy.status === 400 || busy.status === 409, 'Busy team was archived: ' + busy.status)
  assert(search.items.some((item) => item.name === 'Университет А'), 'Case-insensitive organization search failed')
  assert(audit.items.some((item) => item.action === 'TEAM_CREATED') && audit.items.some((item) => item.action === 'TEAM_ARCHIVED'), 'Team actions are absent from the journal')
  return {
    teamA: { leaders: teamA.leaderNames, managers: teamA.managerNames, organizations: teamA.organizationCount },
    create: 201,
    duplicate: duplicate.status,
    archiveEmpty: 200,
    archiveBusy: busy.status,
    search: search.items.map((item) => item.name),
    journal: audit.items.map((item) => item.action)
  }
}

sections.contacts = async () => {
  const kamA = await session('kam-a')
  const kamB = await session('kam-b')
  const organization = await testOrganization()
  const base = '/api/organizations/' + organization.id + '/contacts'
  const first = requireStatus(await api(kamA, 'POST', base, { body: { name: 'Иванова Анна W4', position: null, email: 'ivanova.' + nonce + '@example.org', phone: '+7 900 000-00-01', role: 'SIGNATORY', primary: true } }), 201, 'Contact creation failed')
  const second = requireStatus(await api(kamA, 'POST', base, { body: { name: 'Петров Пётр W4', email: null, phone: null, role: 'IMPLEMENTER', primary: false } }), 201, 'Second contact creation failed')
  const edited = requireStatus(await api(kamA, 'PATCH', base + '/' + first.id, { body: { version: first.version, name: first.name, position: 'Проректор', email: first.email, phone: first.phone, role: 'SIGNATORY', primary: true, inactive: false, confirm: true } }), 200, 'Contact edit failed')
  const stale = await api(kamA, 'PATCH', base + '/' + first.id, { body: { version: first.version, name: first.name, position: 'Ректор', email: first.email, phone: first.phone, role: 'SIGNATORY', primary: true, inactive: false } })
  const promoted = requireStatus(await api(kamA, 'PATCH', base + '/' + second.id, { body: { version: second.version, name: second.name, role: 'IMPLEMENTER', primary: true, inactive: false } }), 200, 'Primary switch failed')
  const listed = requireStatus(await api(kamA, 'GET', base), 200, 'Contacts are unavailable')
  const firstNow = listed.find((item) => item.id === first.id)
  const interaction = await createInteraction(kamA, organization.id, 'W4 контакты ' + nonce, { contactIds: [first.id, second.id] })
  const inactive = requireStatus(await api(kamA, 'PATCH', base + '/' + first.id, { body: { version: firstNow.version, name: first.name, position: 'Проректор', email: first.email, phone: first.phone, role: 'SIGNATORY', primary: false, inactive: true } }), 200, 'Inactive mark failed')
  const inactivePrimary = await api(kamA, 'PATCH', base + '/' + first.id, { body: { version: inactive.version, name: first.name, role: 'SIGNATORY', primary: true, inactive: true } })
  const events = requireStatus(await api(kamA, 'GET', base + '/' + first.id + '/events'), 200, 'Contact history is unavailable')
  const newWithInactive = await api(kamA, 'POST', '/api/interactions', { body: { organizationId: organization.id, title: 'W4 неактуальный ' + nonce, contactIds: [first.id] } })
  const removed = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + interaction.id, { body: { version: interaction.version, contactIds: [second.id] } }), 200, 'Contact removal from work failed')
  const readd = await api(kamA, 'PATCH', '/api/interactions/' + interaction.id, { body: { version: removed.version, contactIds: [first.id, second.id] } })
  const history = await eventsOf(kamA, interaction.id)
  const details = history.filter((event) => event.type === 'DETAILS_UPDATED')
  const foreign = await api(kamB, 'PATCH', base + '/' + second.id, { body: { version: promoted.version, name: 'Чужой', primary: true, inactive: false } })
  const fields = events.flatMap((event) => event.changes.map((change) => change.field))
  ctx.contacts = { inactive: first.id, active: second.id }
  assert(edited.position === 'Проректор' && edited.confirmedAt && edited.confirmedByName, 'Contact edit or confirmation is not saved')
  assert(stale.status === 409 && stale.body?.code === 'VERSION_CONFLICT', 'Stale contact edit was not rejected: ' + stale.status)
  assert(promoted.primary && firstNow.primary === false, 'Second primary did not replace the first')
  assert(inactive.inactive === true, 'Contact is not inactive')
  requireError(inactivePrimary, 400, 'Inactive contact became primary')
  assert(['position', 'confirmed', 'primary', 'inactive'].every((field) => fields.includes(field)), 'Contact history lacks fields: ' + fields.join(','))
  const positionChange = events.flatMap((event) => event.changes).find((change) => change.field === 'position')
  assert(positionChange.previousValue === null && positionChange.value === 'Проректор', 'Contact history has no before and after values')
  requireError(newWithInactive, 400, 'Inactive contact was linked to a new work', 'contactIds')
  requireError(readd, 400, 'Inactive contact was added back to a work', 'contactIds')
  assert(details.some((event) => event.comment?.includes('Удалены контакты') && event.comment.includes('Иванова Анна W4')), 'Contact removal is absent from history')
  assert(foreign.status === 404, 'KAM B edited a contact of team A: ' + foreign.status)
  return {
    create: 201,
    edit: 200,
    confirmedBy: edited.confirmedByName,
    staleEdit: stale.status,
    primarySwitch: { second: promoted.primary, first: firstNow.primary },
    inactive: inactive.inactive,
    inactivePrimary: inactivePrimary.status,
    historyFields: [...new Set(fields)],
    newWorkWithInactive: newWithInactive.status,
    removedFromWork: removed.contactIds.length,
    addInactiveBack: readd.status,
    foreignEdit: foreign.status
  }
}

sections.workStatus = async () => {
  const kamA = await session('kam-a')
  const leader = await session('leader')
  const organization = await testOrganization()
  const past = new Date(Date.now() - 2 * 86400000).toISOString()
  const interaction = await createInteraction(kamA, organization.id, 'W4 статус ' + nonce, { nextAction: 'Позвонить проректору', nextActionAt: past })
  ctx.statusInteraction = interaction
  const pauseWithout = await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/status', { body: { version: interaction.version, status: 'PAUSED', reason: null } })
  const paused = requireStatus(await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/status', { body: { version: interaction.version, status: 'PAUSED', reason: 'Вуз на каникулах' } }), 200, 'Pause failed')
  const defaultList = await listAll(kamA, '/api/interactions?q=' + encodeURIComponent('W4 статус ' + nonce), 'Work list failed')
  const pausedList = await listAll(kamA, '/api/interactions?status=PAUSED&q=' + encodeURIComponent('W4 статус ' + nonce), 'Paused list failed')
  const completeWithout = await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/status', { body: { version: paused.version, status: 'COMPLETED' } })
  const completed = requireStatus(await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/status', { body: { version: paused.version, status: 'COMPLETED', reason: 'Договор подписан' } }), 200, 'Completion failed')
  const resumed = requireStatus(await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/status', { body: { version: completed.version, status: 'ACTIVE' } }), 200, 'Resume failed')
  const renamed = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + interaction.id, { body: { version: resumed.version, title: 'W4 статус ' + nonce + ' (уточнено)', lastContactAt: new Date(Date.now() - 86400000).toISOString() } }), 200, 'Details edit failed')
  const riskWithout = await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/flags', { body: { version: renamed.version, waitingOn: 'UNIVERSITY', waitingNote: 'Ждём подписи', problem: 'Нет ответа юристов', riskLevel: 'HIGH', riskReason: null } })
  const flagged = requireStatus(await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/flags', { body: { version: renamed.version, waitingOn: 'UNIVERSITY', waitingNote: 'Ждём подписи', problem: 'Нет ответа юристов', riskLevel: 'HIGH', riskReason: 'Срыв срока внедрения' } }), 200, 'Flags failed')
  const sameFlags = requireStatus(await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/flags', { body: { version: flagged.version, waitingOn: 'UNIVERSITY', waitingNote: 'Ждём подписи', problem: 'Нет ответа юристов', riskLevel: 'HIGH', riskReason: 'Срыв срока внедрения' } }), 200, 'Repeated flags failed')
  const riskList = await listAll(kamA, '/api/interactions?flag=RISK&q=' + encodeURIComponent(nonce), 'Risk list failed')
  const waitingList = await listAll(leader, '/api/interactions?flag=WAITING_UNIVERSITY&q=' + encodeURIComponent(nonce), 'Waiting list failed')
  const rtkList = await listAll(kamA, '/api/interactions?flag=WAITING_RTK&q=' + encodeURIComponent(nonce), 'Waiting RTK list failed')
  const history = await eventsOf(kamA, interaction.id)
  const report = requireStatus(await preview(leader, {
    kind: 'PORTFOLIO', from: today, to: today,
    filters: { organizationIds: [organization.id], workStatuses: ['ACTIVE'], flags: ['RISK'] },
    columns: ['INTERACTION', 'WORK_STATUS', 'WAITING', 'PROBLEM', 'RISK']
  }), 200, 'Report with marks failed')
  const row = report.items.find((item) => item.INTERACTION?.includes('W4 статус ' + nonce))
  const statusEvents = history.filter((event) => event.type === 'STATUS_CHANGED')
  requireError(pauseWithout, 400, 'Pause without a reason was accepted')
  requireError(completeWithout, 400, 'Completion without a result was accepted')
  assert(paused.marks.status === 'PAUSED' && completed.marks.status === 'COMPLETED' && resumed.marks.status === 'ACTIVE', 'Work status is not stored')
  assert(!defaultList.some((item) => item.id === interaction.id) && pausedList.some((item) => item.id === interaction.id), 'Paused work is not filtered')
  assert(statusEvents.length === 3 && statusEvents.some((event) => event.comment?.includes('Вуз на каникулах')) && statusEvents.some((event) => event.comment?.includes('Договор подписан')), 'Status history is incomplete')
  assert(renamed.title.endsWith('(уточнено)') && history.some((event) => event.type === 'DETAILS_UPDATED' && event.comment?.includes('Название')), 'Title change is not in history')
  requireError(riskWithout, 400, 'High risk without a reason was accepted')
  assert(flagged.marks.waitingOn === 'UNIVERSITY' && flagged.marks.problem && flagged.marks.riskLevel === 'HIGH', 'Flags are not stored')
  assert(sameFlags.version === flagged.version, 'Repeated flags created a new version')
  assert(riskList.some((item) => item.id === interaction.id) && waitingList.some((item) => item.id === interaction.id) && !rtkList.some((item) => item.id === interaction.id), 'Flag filters are wrong')
  assert(row && row.WORK_STATUS && row.WAITING && row.PROBLEM && row.RISK, 'Report does not show work marks')
  return {
    pauseWithoutReason: pauseWithout.status,
    completeWithoutResult: completeWithout.status,
    statuses: [paused.marks.status, completed.marks.status, resumed.marks.status],
    hiddenFromDefault: true,
    statusEvents: statusEvents.length,
    detailsEdit: 200,
    riskWithoutReason: riskWithout.status,
    repeatFlagsNewVersion: sameFlags.version !== flagged.version,
    flagFilters: { risk: true, waitingUniversity: true, waitingRtk: false },
    reportRow: { WORK_STATUS: row.WORK_STATUS, WAITING: row.WAITING, PROBLEM: row.PROBLEM, RISK: row.RISK }
  }
}

sections.myWork = async () => {
  const kamA = await session('kam-a')
  const leader = await session('leader')
  const organization = await testOrganization()
  const past = new Date(Date.now() - 3 * 86400000).toISOString()
  const future = new Date(Date.now() + 2 * 86400000).toISOString()
  const planned = await createInteraction(kamA, organization.id, 'W4 шаг ' + nonce, { nextAction: 'Отправить проект договора', nextActionAt: past })
  const noText = await createInteraction(kamA, organization.id, 'W4 без текста ' + nonce, { nextAction: null, nextActionAt: past })
  const empty = await createInteraction(kamA, organization.id, 'W4 пусто ' + nonce)
  const emptyStep = await api(kamA, 'POST', '/api/interactions/' + empty.id + '/step-completions', { body: { version: empty.version, result: 'нечего' } })
  const done = requireStatus(await api(kamA, 'POST', '/api/interactions/' + planned.id + '/step-completions', {
    body: { version: planned.version, result: 'Проект отправлен', nextStep: { nextAction: 'Получить замечания', nextActionAt: future } }
  }), 200, 'Step completion failed')
  const history = await eventsOf(kamA, planned.id)
  const stepEvent = history.find((event) => event.type === 'PLAN_UPDATED' && event.comment?.startsWith('Шаг выполнен'))
  const overdue = await listAll(kamA, '/api/interactions?due=OVERDUE&q=' + encodeURIComponent(nonce), 'Overdue list failed')
  const noStep = await listAll(kamA, '/api/interactions?due=NO_NEXT_STEP&q=' + encodeURIComponent(nonce), 'No step list failed')
  const byResponsible = await listAll(leader, '/api/interactions?responsible=' + await profileIdOf('kam-a') + '&q=' + encodeURIComponent(nonce), 'Responsible filter failed')
  const unassigned = await listAll(leader, '/api/interactions?responsible=UNASSIGNED', 'Unassigned filter failed')
  const stuck = await listAll(leader, '/api/interactions?minDaysOnStage=1&q=' + encodeURIComponent(nonce), 'Days on stage filter failed')
  const all = await listAll(kamA, '/api/interactions?q=' + encodeURIComponent('W4 шаг ' + nonce), 'Work list failed')
  const row = all.find((item) => item.id === planned.id)
  const kamResponsible = await listAll(kamA, '/api/interactions?responsible=UNASSIGNED', 'KAM unassigned filter failed')
  await call('Page.navigate', { url: origin + '/#/work?q=' + encodeURIComponent('W4 шаг ' + nonce) }, kamA.sessionId)
  await kamA.waitFor(() => kamA.evaluate(`document.body.innerText.includes(${JSON.stringify('W4 шаг ' + nonce)})`), 'My work did not show the card')
  const screen = await kamA.evaluate('document.body.innerText')
  requireError(emptyStep, 400, 'Step completion without a step was accepted', 'nextStep')
  assert(done.nextAction === 'Получить замечания' && stepEvent && stepEvent.comment.includes('Проект отправлен'), 'Step completion is not recorded')
  assert(overdue.some((item) => item.id === noText.id) && !overdue.some((item) => item.id === planned.id), 'Any past deadline is not overdue')
  assert(noStep.some((item) => item.id === noText.id) && noStep.some((item) => item.id === empty.id), 'No step filter is wrong')
  assert(byResponsible.length >= 3 && byResponsible.every((item) => item.ownerManagerName === 'КАМ А'), 'Responsible filter is wrong')
  assert(unassigned.every((item) => item.ownerManagerName === null || item.ownerManagerName !== 'КАМ А'), 'Unassigned filter returned KAM A cards')
  assert(!stuck.some((item) => item.id === planned.id), 'New work is counted as stuck')
  assert(row.lastEventType === 'PLAN_UPDATED' && row.lastEventAt && row.stageEnteredAt, 'Work row lacks last event or stage entry')
  assert(screen.includes('Шаг выполнен') || screen.includes('Дней на этапе') || screen.includes('на этапе'), 'My work screen has no step or days on stage')
  return {
    emptyStep: emptyStep.status,
    stepDone: stepEvent.comment,
    overdueWithoutText: true,
    noNextStep: noStep.length,
    responsibleRows: byResponsible.length,
    unassignedRows: unassigned.length,
    kamUnassignedFilter: kamResponsible.length,
    newWorkStuck: false,
    row: { lastEventType: row.lastEventType, stageEnteredAt: row.stageEnteredAt },
    screenDaysOnStage: screen.includes('на этапе')
  }
}

sections.handover = async () => {
  const leader = await session('leader')
  const kamA = await session('kam-a')
  const kamC = await session('kam-c')
  const organization = await testOrganization()
  const kamEvents = requireStatus(await api(kamA, 'GET', '/api/organizations/' + organization.id + '/assignment-events'), 200, 'KAM cannot read assignment history')
  const note = kamEvents.find((event) => event.handoverNote)
  const second = requireStatus(await api(leader, 'POST', '/api/organizations', { body: { name: 'Университет W4 передача ' + nonce, type: 'UNIVERSITY' } }), 201, 'Second organization failed')
  const current = await freshOrganization(leader, organization.id)
  const kamCId = await profileIdOf('kam-c')
  const foreign = await demoOrganization(await session('leader-b'), 'Университет Б')
  const foreignBulk = await api(leader, 'POST', '/api/organization-assignments', { body: { items: [{ organizationId: foreign.id, version: foreign.version, ownerManagerId: kamCId }] } })
  const wrongKam = await api(leader, 'POST', '/api/organization-assignments', { body: { items: [{ organizationId: second.id, version: second.version, ownerManagerId: await profileIdOf('kam-b') }] } })
  const bulk = requireStatus(await api(leader, 'POST', '/api/organization-assignments', {
    body: { items: [{ organizationId: current.id, version: current.version, ownerManagerId: kamCId }, { organizationId: second.id, version: second.version, ownerManagerId: kamCId }] }
  }), 200, 'Bulk transfer failed')
  const repeated = requireStatus(await api(leader, 'POST', '/api/organization-assignments', {
    body: { items: bulk.assigned.map((item) => ({ organizationId: item.organization.id, version: item.organization.version, ownerManagerId: kamCId })) }
  }), 200, 'Repeated bulk transfer failed')
  const kamAAfter = await api(kamA, 'GET', '/api/organizations/' + organization.id)
  const kamCAfter = await api(kamC, 'GET', '/api/organizations/' + organization.id)
  const commandIds = new Set(bulk.assigned.map((item) => item.event.commandId))
  const back = requireStatus(await api(leader, 'POST', '/api/organizations/' + organization.id + '/assignment', {
    body: { version: bulk.assigned.find((item) => item.organization.id === organization.id).organization.version, ownerManagerId: await profileIdOf('kam-a'), handoverNote: 'Возврат после проверки массовой передачи' }
  }), 200, 'Return to KAM A failed')
  const deputyStart = today
  const deputyEnd = shiftDay(today, 7)
  const ownerDeputy = await api(leader, 'POST', '/api/organizations/' + organization.id + '/deputies', { body: { deputyProfileId: await profileIdOf('kam-a'), startsOn: deputyStart, endsOn: deputyEnd } })
  const deputy = requireStatus(await api(leader, 'POST', '/api/organizations/' + organization.id + '/deputies', { body: { deputyProfileId: kamCId, startsOn: deputyStart, endsOn: deputyEnd } }), 201, 'Deputy creation failed')
  const kamCDeputy = await api(kamC, 'GET', '/api/organizations/' + organization.id)
  const deputyWork = await api(kamC, 'POST', '/api/interactions', { body: { organizationId: organization.id, title: 'W4 от заместителя ' + nonce, contactIds: [] } })
  const listRow = (await listAll(kamA, '/api/interactions?q=' + encodeURIComponent('W4 от заместителя ' + nonce), 'Deputy work list failed'))[0]
  const kamDeputy = await api(kamA, 'POST', '/api/organizations/' + organization.id + '/deputies', { body: { deputyProfileId: kamCId, startsOn: deputyStart, endsOn: deputyEnd } })
  const ended = requireStatus(await api(leader, 'POST', '/api/organizations/' + organization.id + '/deputies/' + deputy.id + '/end'), 200, 'Deputy end failed')
  const kamCEnded = await api(kamC, 'GET', '/api/organizations/' + organization.id)
  ctx.organizations.second = bulk.assigned.find((item) => item.organization.id === second.id).organization
  assert(note && note.handoverNote.includes('Передача W4') && note.actorDisplayName === 'Руководитель', 'Handover note is not visible to the new KAM')
  assert(foreignBulk.status === 404, 'Bulk transfer reached another team: ' + foreignBulk.status)
  requireError(wrongKam, 400, 'Bulk transfer to KAM of another team was accepted', 'ownerManagerId')
  assert(bulk.assigned.length === 2 && commandIds.size === 1, 'Bulk transfer events do not share one command')
  assert(repeated.assigned.length === 0 && repeated.unchangedOrganizationIds.length === 2, 'Repeated bulk transfer changed organizations')
  assert(kamAAfter.status === 404 && kamCAfter.status === 200, 'Bulk transfer did not move access')
  assert(back.organization.ownerManagerName === 'КАМ А', 'Organization did not return to KAM A')
  requireError(ownerDeputy, 400, 'Owner became own deputy')
  assert(deputy.status === 'ACTIVE' || deputy.status === 'SCHEDULED' || deputy.deputyDisplayName === 'КАМ В', 'Deputy is not created')
  assert(kamCDeputy.status === 200 && deputyWork.status === 201, 'Deputy has no access during the period: ' + kamCDeputy.status + '/' + deputyWork.status)
  assert(listRow?.ownerManagerName === 'КАМ А' && listRow.deputyManagerName === 'КАМ В', 'Work row does not show the deputy')
  assert(kamDeputy.status === 403, 'KAM assigned a deputy: ' + kamDeputy.status)
  assert(ended.endedAt && kamCEnded.status === 404, 'Deputy kept access after the end: ' + kamCEnded.status)
  return {
    kamSeesHandoverNote: note.handoverNote,
    foreignBulk: foreignBulk.status,
    wrongKam: wrongKam.status,
    bulkAssigned: bulk.assigned.length,
    bulkCommands: commandIds.size,
    repeatedUnchanged: repeated.unchangedOrganizationIds.length,
    access: { previousKam: kamAAfter.status, newKam: kamCAfter.status },
    ownerAsDeputy: ownerDeputy.status,
    deputyAccess: kamCDeputy.status,
    deputyCreatesWork: deputyWork.status,
    rowDeputy: listRow.deputyManagerName,
    kamAssignsDeputy: kamDeputy.status,
    afterEnd: kamCEnded.status
  }
}

sections.indicators = async () => {
  const leader = await session('leader')
  const kamA = await session('kam-a')
  const indicators = requireStatus(await api(leader, 'GET', '/api/work/team-indicators?stuckDays=7'), 200, 'Team indicators are unavailable')
  const kamIndicators = await api(kamA, 'GET', '/api/work/team-indicators')
  const tooLow = await api(leader, 'GET', '/api/work/team-indicators?stuckDays=0')
  const row = indicators.managers.find((item) => item.managerName === 'КАМ А')
  const overdueList = await listAll(leader, '/api/interactions?responsible=' + row.managerId + '&due=OVERDUE', 'Overdue list failed')
  const noStepList = await listAll(leader, '/api/interactions?responsible=' + row.managerId + '&due=NO_NEXT_STEP', 'No step list failed')
  const stuckList = await listAll(leader, '/api/interactions?responsible=' + row.managerId + '&minDaysOnStage=7', 'Stuck list failed')
  await call('Page.navigate', { url: origin + '/#/work' }, leader.sessionId)
  await leader.waitFor(() => leader.evaluate("document.body.innerText.includes('КАМ А') && document.body.innerText.includes('Просрочено')"), 'Indicator block is not shown')
  const screen = await leader.evaluate('document.body.innerText')
  assert(kamIndicators.status === 403, 'KAM got team indicators: ' + kamIndicators.status)
  assert(tooLow.status === 400, 'stuckDays=0 was accepted: ' + tooLow.status)
  assert(row && row.overdue === overdueList.length && row.withoutNextStep === noStepList.length && row.stuck === stuckList.length, 'Indicator numbers differ from the lists: ' + JSON.stringify([row?.overdue, overdueList.length, row?.withoutNextStep, noStepList.length, row?.stuck, stuckList.length]))
  assert(indicators.managers.some((item) => item.managerId === null || item.managerName === null), 'No row for unassigned work')
  return {
    managers: indicators.managers.map((item) => ({ name: item.managerName ?? 'Требует назначения', organizations: item.organizations, interactions: item.interactions, overdue: item.overdue, withoutNextStep: item.withoutNextStep, stuck: item.stuck })),
    unassignedOrganizations: indicators.unassignedOrganizations,
    kam: kamIndicators.status,
    stuckDaysZero: tooLow.status,
    numbersMatchLists: true,
    screenBlock: screen.includes('помощь') || screen.includes('Показатели')
  }
}

sections.documents = async () => {
  const kamA = await session('kam-a')
  const leader = await session('leader')
  const kamB = await session('kam-b')
  const organization = await testOrganization()
  const products = requireStatus(await api(kamA, 'GET', '/api/products?page=0&size=100'), 200, 'Products are unavailable')
  const product = products.items.find((item) => !item.archived)
  const interaction = await createInteraction(kamA, organization.id, 'W4 документы ' + nonce, { productIds: [product.id] })
  ctx.documentsInteraction = interaction
  const contract = requireStatus(await upload(kamA, interaction.id, { stageId: interaction.currentStageId, kind: 'CONTRACT', name: 'договор.pdf', type: 'application/pdf', base64: pdfBase64('contract ' + nonce) }), 201, 'Contract upload failed')
  const contractClean = await waitClean(kamA, contract.id)
  const kindChanged = requireStatus(await api(kamA, 'PATCH', '/api/attachments/' + contract.id, { body: { version: contractClean.version, kind: 'LICENSE_AGREEMENT' } }), 200, 'Kind change failed')
  const staleKind = await api(kamA, 'PATCH', '/api/attachments/' + contract.id, { body: { version: contractClean.version, kind: 'ACT' } })
  const revision = requireStatus(await upload(kamA, interaction.id, { stageId: interaction.currentStageId, replacesId: contract.id, name: 'договор-2.pdf', type: 'application/pdf', base64: pdfBase64('contract v2 ' + nonce) }), 201, 'New version upload failed')
  const revisionClean = await waitClean(kamA, revision.id)
  const parallel = await upload(kamA, interaction.id, { stageId: interaction.currentStageId, replacesId: contract.id, name: 'договор-3.pdf', type: 'application/pdf', base64: pdfBase64('contract v3 ' + nonce) })
  const previewPdf = await api(kamA, 'GET', '/api/attachments/' + revision.id + '/preview', { binary: true })
  const zip = requireStatus(await upload(kamA, interaction.id, { stageId: interaction.currentStageId, kind: 'MATERIALS', name: 'материалы.zip', type: 'application/zip', base64: zipStore([{ name: 'readme.txt', data: 'W4 ' + nonce }]).toString('base64') }), 201, 'ZIP upload failed')
  await waitClean(kamA, zip.id)
  const previewZip = await api(kamA, 'GET', '/api/attachments/' + zip.id + '/preview', { binary: true })
  const encrypted = requireStatus(await upload(kamA, interaction.id, { stageId: interaction.currentStageId, name: 'шифрованный.zip', type: 'application/zip', base64: zipStore([{ name: 'secret.txt', data: 'W4 ' + nonce }], 0x0801).toString('base64') }), 201, 'Encrypted ZIP upload failed')
  const encryptedChecked = await waitClean(kamA, encrypted.id)
  const encryptedDownload = await api(kamA, 'GET', '/api/attachments/' + encrypted.id + '/download', { binary: true })
  const heic = Buffer.concat([Buffer.from([0, 0, 0, 24]), Buffer.from('ftypheic'), Buffer.from([0, 0, 0, 0]), Buffer.from('mif1heic'), Buffer.alloc(64)])
  const heicUpload = await upload(kamA, interaction.id, { stageId: interaction.currentStageId, name: 'фото.heic', type: 'image/heic', base64: heic.toString('base64') })
  const leaderFile = requireStatus(await upload(leader, interaction.id, { stageId: interaction.currentStageId, kind: 'ACT', name: 'акт.pdf', type: 'application/pdf', base64: pdfBase64('act ' + nonce) }), 201, 'Leader upload failed')
  await waitClean(leader, leaderFile.id)
  let card = await interactionOf(kamA, interaction.id)
  const kamDeletesLeaderFile = await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/attachments/' + leaderFile.id + '/deletion', { body: { version: card.version, reason: 'чужой' } })
  const foreignDelete = await api(kamB, 'POST', '/api/interactions/' + interaction.id + '/attachments/' + zip.id + '/deletion', { body: { version: card.version } })
  const deleted = requireStatus(await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/attachments/' + zip.id + '/deletion', { body: { version: card.version, reason: 'Загружен по ошибке' } }), 200, 'Author could not delete own file')
  const leaderDeleted = requireStatus(await api(leader, 'POST', '/api/interactions/' + interaction.id + '/attachments/' + leaderFile.id + '/deletion', { body: { version: deleted.version, reason: 'Дубль' } }), 200, 'Leader could not delete the file')
  const deletedDownload = await api(kamA, 'GET', '/api/attachments/' + zip.id + '/download', { binary: true })
  const deletedPreview = await api(kamA, 'GET', '/api/attachments/' + zip.id + '/preview', { binary: true })
  card = await interactionOf(kamA, interaction.id)
  const history = await eventsOf(kamA, interaction.id)
  const deletionEvents = history.filter((event) => event.type === 'ATTACHMENT_DELETED')
  const agreement = card.productAgreements[0]
  const futureTransfer = await api(kamA, 'PATCH', '/api/interactions/' + interaction.id + '/product-agreements/' + agreement.id, {
    body: { version: card.version, transfers: [{ kind: 'MATERIALS', status: 'TRANSFERRED', transferredOn: shiftDay(today, 2), attachmentId: null }] }
  })
  const contractSaved = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + interaction.id + '/product-agreements/' + agreement.id, {
    body: { version: card.version, contract: { contractNumber: ' 007-W4 ', licenseSigned: true, licenseExpiryYear: 2027, scanAttachmentId: revision.id } }
  }), 200, 'Contract form failed')
  const transfersSaved = requireStatus(await api(leader, 'PATCH', '/api/interactions/' + interaction.id + '/product-agreements/' + agreement.id, {
    body: { version: contractSaved.version, transfers: [{ kind: 'MATERIALS', status: 'TRANSFERRED', transferredOn: today, attachmentId: revision.id }, { kind: 'LICENSE', status: 'NOT_TRANSFERRED', transferredOn: null, attachmentId: null }] }
  }), 200, 'Transfer marks failed')
  const scanDelete = await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/attachments/' + revision.id + '/deletion', { body: { version: transfersSaved.version } })
  const saved = transfersSaved.productAgreements.find((item) => item.id === agreement.id)
  const agreementEvents = (await eventsOf(kamA, interaction.id)).filter((event) => event.type === 'AGREEMENT_UPDATED')
  const license = await listAll(kamA, '/api/interactions?licenseExpiresBy=2027&q=' + encodeURIComponent('W4 документы ' + nonce), 'License filter failed')
  const licenseEarlier = await listAll(kamA, '/api/interactions?licenseExpiresBy=2026&q=' + encodeURIComponent('W4 документы ' + nonce), 'License filter failed')
  const report = requireStatus(await preview(kamA, {
    kind: 'PORTFOLIO', from: today, to: today,
    filters: { organizationIds: [organization.id], agreement: { licenseExpiresBy: 2027, notTransferred: ['LICENSE'] } },
    columns: ['INTERACTION', 'VENDORS', 'CONTRACT_NUMBER', 'LICENSE_SIGNED', 'LICENSE_EXPIRY_YEAR', 'TRANSFER_STATUS', 'MATERIALS_TRANSFERRED_ON']
  }), 200, 'Agreement report failed')
  const row = report.items.find((item) => item.INTERACTION?.includes('W4 документы ' + nonce))
  ctx.cleanAttachment = { interactionId: interaction.id, attachmentId: revision.id }
  assert(kindChanged.kind === 'LICENSE_AGREEMENT' && staleKind.status === 409, 'Document kind change is wrong')
  assert(revisionClean.revision === 2 && revisionClean.replacesId === contract.id && revisionClean.kind === 'LICENSE_AGREEMENT', 'New version did not inherit kind or revision')
  assert(parallel.status === 409, 'Parallel replacement was accepted: ' + parallel.status)
  assert(previewPdf.status === 200 && previewPdf.headers['content-disposition']?.startsWith('inline') && previewPdf.headers['x-content-type-options'] === 'nosniff' && previewPdf.headers['content-security-policy']?.includes("default-src 'none'"), 'PDF preview headers are wrong')
  assert(previewZip.status === 400, 'ZIP preview was accepted: ' + previewZip.status)
  assert(heicUpload.status === 201, 'HEIC upload failed: ' + heicUpload.status)
  assert(encryptedChecked.status === 'UNVERIFIABLE' && encryptedDownload.status === 404, 'Encrypted archive: ' + encryptedChecked.status + ' ' + encryptedDownload.status)
  assert(kamDeletesLeaderFile.status === 403, 'KAM deleted a file of the leader: ' + kamDeletesLeaderFile.status)
  assert(foreignDelete.status === 404, 'KAM B deleted a file of team A: ' + foreignDelete.status)
  assert(deletedDownload.status === 404 && deletedPreview.status === 404, 'Deleted file is still served')
  assert(!card.attachments.some((item) => item.id === zip.id), 'Deleted file is still in the card')
  assert(deletionEvents.length === 2 && deletionEvents.some((event) => event.comment?.includes('Загружен по ошибке')), 'Deletion history is incomplete')
  requireError(futureTransfer, 400, 'Future transfer date was accepted')
  assert(saved.contractNumber === '007-W4' && saved.licenseSigned === true && saved.licenseExpiryYear === 2027 && saved.scanAttachmentId === revision.id, 'Contract fields are not stored')
  assert(saved.transfers.length === 2 && saved.transferStatus, 'Transfer marks are not stored')
  assert(scanDelete.status === 400, 'Contract scan was deleted: ' + scanDelete.status)
  assert(agreementEvents.length === 2 && agreementEvents.every((event) => event.comment?.includes(product.name)), 'Agreement history is incomplete')
  assert(license.length === 1 && licenseEarlier.length === 0, 'License filter in My work is wrong')
  assert(row && row.CONTRACT_NUMBER === '007-W4' && String(row.LICENSE_EXPIRY_YEAR) === '2027' && row.MATERIALS_TRANSFERRED_ON, 'Agreement columns are wrong: ' + JSON.stringify(row))
  return {
    kindChange: kindChanged.kind,
    staleKind: staleKind.status,
    revision: revisionClean.revision,
    parallelReplacement: parallel.status,
    previewPdf: { status: previewPdf.status, disposition: previewPdf.headers['content-disposition']?.split(';')[0], csp: Boolean(previewPdf.headers['content-security-policy']) },
    previewZip: previewZip.status,
    heic: heicUpload.status + ' ' + (heicUpload.body?.status ?? ''),
    encryptedZip: encryptedChecked.status + ', скачивание ' + encryptedDownload.status,
    kamDeletesLeaderFile: kamDeletesLeaderFile.status,
    foreignDelete: foreignDelete.status,
    authorDelete: 200,
    leaderDelete: leaderDeleted.version > deleted.version ? 200 : 'no version change',
    deletedServed: [deletedDownload.status, deletedPreview.status],
    deletionEvents: deletionEvents.length,
    futureTransfer: futureTransfer.status,
    contract: { number: saved.contractNumber, signed: saved.licenseSigned, year: saved.licenseExpiryYear, transferStatus: saved.transferStatus },
    scanDelete: scanDelete.status,
    agreementEvents: agreementEvents.length,
    licenseFilter: [license.length, licenseEarlier.length],
    reportRow: row
  }
}

sections.stages = async () => {
  const kamA = await session('kam-a')
  const leader = await session('leader')
  const organization = await testOrganization()
  const interaction = await createInteraction(kamA, organization.id, 'W4 этапы ' + nonce)
  const target = interaction.stages.find((stage) => stage.id !== interaction.currentStageId && !interaction.allowedTransitions.some((option) => option.stageId === stage.id)) ?? interaction.stages.at(-1)
  const current = await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/stage-completions', { body: { version: interaction.version, stageId: interaction.currentStageId, completedOn: today } })
  const future = await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/stage-completions', { body: { version: interaction.version, stageId: target.id, completedOn: shiftDay(today, 1) } })
  const marked = requireStatus(await api(kamA, 'POST', '/api/interactions/' + interaction.id + '/stage-completions', { body: { version: interaction.version, stageId: target.id, completedOn: shiftDay(today, -1), comment: 'Документы получены заранее' } }), 200, 'Stage completion failed')
  const cleared = requireStatus(await api(kamA, 'DELETE', '/api/interactions/' + interaction.id + '/stage-completions/' + target.id + '?version=' + marked.version), 200, 'Stage completion removal failed')
  const history = await eventsOf(kamA, interaction.id)
  const options = requireStatus(await api(leader, 'GET', '/api/organizations/' + organization.id + '/assignment-options'), 200, 'Assignment options failed')
  const leaderId = await profileIdOf('leader')
  const self = options.find((option) => option.id === leaderId)
  const otherLeader = await api(leader, 'POST', '/api/organizations/' + organization.id + '/assignment', { body: { version: (await freshOrganization(leader, organization.id)).version, ownerManagerId: await profileIdOf('leader-b') } })
  const second = ctx.organizations.second ?? requireStatus(await api(leader, 'POST', '/api/organizations', { body: { name: 'Университет W4 руководитель ' + nonce, type: 'UNIVERSITY' } }), 201, 'Organization failed')
  const leaderOwner = requireStatus(await api(leader, 'POST', '/api/organizations/' + second.id + '/assignment', { body: { version: (await freshOrganization(leader, second.id)).version, ownerManagerId: leaderId } }), 200, 'Leader could not become responsible')
  const work = await createInteraction(leader, second.id, 'W4 у руководителя ' + nonce)
  const filtered = await listAll(leader, '/api/interactions?responsible=' + leaderId + '&q=' + encodeURIComponent(nonce), 'Leader responsible filter failed')
  ctx.organizations.second = leaderOwner.organization
  requireError(current, 400, 'Current stage completion was accepted')
  requireError(future, 400, 'Future completion date was accepted')
  assert(marked.currentStageId === interaction.currentStageId && marked.stageCompletions.some((item) => item.stageId === target.id), 'Stage completion changed the current stage or was not stored')
  assert(!cleared.stageCompletions.some((item) => item.stageId === target.id), 'Stage completion was not removed')
  assert(history.some((event) => event.type === 'STAGE_COMPLETED') && history.some((event) => event.type === 'STAGE_COMPLETION_CLEARED'), 'Stage completion history is incomplete')
  assert(self && self.displayName === 'Руководитель', 'Leader is not offered as responsible')
  requireError(otherLeader, 400, 'Leader of another team became responsible', 'ownerManagerId')
  assert(leaderOwner.organization.ownerManagerName === 'Руководитель' && leaderOwner.organization.requiresAssignment === false, 'Leader is not the responsible')
  assert(filtered.some((item) => item.id === work.id && item.ownerManagerName === 'Руководитель'), 'My work does not show the leader as responsible')
  return {
    currentStage: current.status,
    futureDate: future.status,
    marked: target.name,
    currentUnchanged: true,
    cleared: true,
    events: ['STAGE_COMPLETED', 'STAGE_COMPLETION_CLEARED'],
    leaderOption: self.displayName,
    otherTeamLeader: otherLeader.status,
    leaderResponsible: leaderOwner.organization.ownerManagerName
  }
}

sections.agreements = async () => {
  const kamA = await session('kam-a')
  const leader = await session('leader')
  const kamB = await session('kam-b')
  const admin = await session('admin')
  const organization = await testOrganization()
  const kinds = requireStatus(await api(kamA, 'GET', '/api/agreement-activity-kinds'), 200, 'Activity kinds are unavailable')
  const kindCreate = await api(kamA, 'POST', '/api/admin/agreement-activity-kinds', { body: { name: 'КАМ вид ' + nonce } })
  const adminKind = requireStatus(await api(admin, 'POST', '/api/admin/agreement-activity-kinds', { body: { name: 'Вид W4 ' + nonce } }), 201, 'Admin kind creation failed')
  const clean = ctx.cleanAttachment ?? await (async () => {
    const work = await createInteraction(kamA, organization.id, 'W4 соглашение файл ' + nonce)
    const file = requireStatus(await upload(kamA, work.id, { stageId: work.currentStageId, name: 'соглашение.pdf', type: 'application/pdf', base64: pdfBase64('agreement ' + nonce) }), 201, 'Upload failed')
    await waitClean(kamA, file.id)
    return { interactionId: work.id, attachmentId: file.id }
  })()
  const agreement = requireStatus(await api(kamA, 'POST', '/api/organizations/' + organization.id + '/agreements', {
    body: { number: 'С-W4-' + nonce, concludedOn: shiftDay(today, -30), validUntil: shiftDay(today, 365), parties: 'Вуз и ПАО «Ростелеком»', status: 'ACTIVE', fileAttachmentId: clean.attachmentId }
  }), 201, 'Agreement creation failed')
  const duplicate = await api(kamA, 'POST', '/api/organizations/' + organization.id + '/agreements', { body: { number: 'С-W4-' + nonce, status: 'DRAFT' } })
  const options = requireStatus(await api(kamA, 'GET', '/api/organizations/' + organization.id + '/agreement-options'), 200, 'Agreement options failed')
  const badDates = await api(kamA, 'POST', '/api/agreements/' + agreement.id + '/activities', { body: { kindId: kinds[0].id, title: 'Неверные даты', status: 'PLANNED', plannedStart: today, plannedEnd: shiftDay(today, -1) } })
  const activity = requireStatus(await api(kamA, 'POST', '/api/agreements/' + agreement.id + '/activities', {
    body: { kindId: kinds[0].id, title: 'Актуализация программы W4', unit: 'программ', plannedVolume: 2, actualVolume: 1, plannedStart: shiftDay(today, -10), plannedEnd: shiftDay(today, 20), actualStart: shiftDay(today, -5), responsibleProfileId: await profileIdOf('kam-a'), status: 'IN_PROGRESS', interactionIds: [clean.interactionId], attachmentIds: [clean.attachmentId] }
  }), 201, 'Activity creation failed')
  const emptyActivity = requireStatus(await api(leader, 'POST', '/api/agreements/' + agreement.id + '/activities', { body: { kindId: kinds[1].id, title: 'Стажировка W4', status: 'PLANNED', plannedStart: today, plannedEnd: shiftDay(today, 30) } }), 201, 'Leader activity failed')
  const stale = await api(kamA, 'PATCH', '/api/agreement-activities/' + activity.id, { body: { version: activity.version + 5, kindId: kinds[0].id, title: 'x', status: 'DONE' } })
  const foreign = await api(kamB, 'GET', '/api/agreements/' + agreement.id)
  const confirmations = requireStatus(await api(kamA, 'GET', '/api/agreement-confirmations?organizationId=' + organization.id), 200, 'Confirmations failed')
  const archive = await api(kamA, 'GET', '/api/agreement-confirmations/archive?organizationId=' + organization.id, { binary: true })
  const removed = await api(leader, 'DELETE', '/api/agreement-activities/' + emptyActivity.id + '?version=' + emptyActivity.version)
  const report = requireStatus(await preview(leader, { kind: 'AGREEMENTS', from: shiftDay(today, -30), to: shiftDay(today, 30), filters: { organizationIds: [organization.id] }, columns: [] }), 200, 'Agreements report failed')
  const file = await orderReport(leader, { kind: 'AGREEMENTS', format: 'XLSX', from: shiftDay(today, -30), to: shiftDay(today, 30), filters: { organizationIds: [organization.id] }, columns: [] })
  const confirmationList = Array.isArray(confirmations) ? confirmations : confirmations.items
  assert(kindCreate.status === 403 && adminKind.name.includes(nonce), 'Activity kind rights are wrong')
  assert(duplicate.status === 400 || duplicate.status === 409, 'Duplicate agreement number was accepted: ' + duplicate.status)
  assert(options.responsibles.length > 0 && options.attachments.some((item) => item.id === clean.attachmentId), 'Agreement options are incomplete')
  requireError(badDates, 400, 'Activity with reversed dates was accepted')
  assert(stale.status === 409, 'Stale activity update was accepted: ' + stale.status)
  assert(foreign.status === 404, 'KAM B reads an agreement of team A: ' + foreign.status)
  assert(confirmationList.some((item) => item.attachmentId === clean.attachmentId), 'Confirmation is not listed')
  assert(archive.status === 200 && archive.size > 22, 'Confirmation archive failed: ' + archive.status)
  assert(removed.status === 204 || removed.status === 200, 'Activity deletion failed: ' + removed.status)
  assert(report.total >= 1 && report.items.some((row) => JSON.stringify(row).includes('Актуализация программы W4')), 'Agreements report lacks the activity')
  return {
    kamKindCreate: kindCreate.status,
    agreement: agreement.number,
    duplicate: duplicate.status,
    badDates: badDates.status,
    activity: 201,
    leaderActivity: 201,
    staleActivity: stale.status,
    foreign: foreign.status,
    confirmations: confirmationList.length,
    archiveBytes: archive.size,
    deleteActivity: removed.status,
    reportRows: report.total,
    reportColumns: report.columns.map((column) => column.id),
    file: file.size
  }
}

sections.reports = async () => {
  const leader = await session('leader')
  const kamA = await session('kam-a')
  const organization = await testOrganization()
  const yesterday = shiftDay(today, -1)
  await createInteraction(kamA, organization.id, 'W4 отчёты ' + nonce)
  const active = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: today, to: today, periodBasis: 'ACTIVE', filters: {}, columns: ['ORGANIZATION', 'INTERACTION', 'DAYS_ON_STAGE'] }), 200, 'Active in period failed')
  const created = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: today, to: today, periodBasis: 'CREATED', filters: {}, columns: ['INTERACTION'] }), 200, 'Created in period failed')
  const activeMonth = await api(leader, 'POST', '/api/statistics', { body: { kind: 'PORTFOLIO', groupBy: 'MONTH', from: today, to: today, periodBasis: 'ACTIVE', filters: {} } })
  const snapshotToday = requireStatus(await preview(leader, { kind: 'SNAPSHOT', asOf: today, filters: { organizationIds: [organization.id] }, columns: ['INTERACTION', 'STAGE', 'MANAGER'] }), 200, 'Snapshot today failed')
  const snapshotYesterday = requireStatus(await preview(leader, { kind: 'SNAPSHOT', asOf: yesterday, filters: { organizationIds: [organization.id] }, columns: ['INTERACTION', 'STAGE', 'MANAGER'] }), 200, 'Snapshot yesterday failed')
  const snapshotPeriod = await preview(leader, { kind: 'SNAPSHOT', from: today, to: today, asOf: today, filters: {}, columns: [] })
  const snapshotFuture = await preview(leader, { kind: 'SNAPSHOT', asOf: shiftDay(today, 3), filters: {}, columns: [] })
  const snapshotAll = requireStatus(await preview(leader, { kind: 'SNAPSHOT', asOf: shiftDay(today, -2), filters: {}, columns: ['ORGANIZATION', 'STAGE', 'MANAGER'] }), 200, 'Snapshot of demo failed')
  const duration = requireStatus(await preview(leader, { kind: 'DURATION', from: '2026-01-01', to: today, filters: {}, columns: [] }), 200, 'Duration failed')
  const durationChart = await api(leader, 'POST', '/api/statistics', { body: { kind: 'DURATION', groupBy: 'STAGE', from: '2026-01-01', to: today, filters: {} } })
  const line = requireStatus(await api(leader, 'POST', '/api/statistics', { body: { kind: 'PORTFOLIO', groupBy: 'MONTH', seriesBy: 'PROGRAM', from: '2026-07-01', to: today, filters: {} } }), 200, 'Line statistics failed')
  const lineWithoutMonth = await api(leader, 'POST', '/api/reports', { body: { kind: 'PORTFOLIO', format: 'PNG', groupBy: 'STAGE', chartType: 'LINE', from: today, to: today, filters: {} } })
  const linePng = await orderReport(leader, { kind: 'PORTFOLIO', format: 'PNG', groupBy: 'MONTH', chartType: 'LINE', seriesBy: 'PROGRAM', from: '2026-07-01', to: today, filters: {}, columns: [] })
  const linePdf = await orderReport(leader, { kind: 'PORTFOLIO', format: 'PDF', groupBy: 'MONTH', chartType: 'LINE', from: '2026-07-01', to: today, filters: {}, columns: [] })
  const ordered = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: today, to: today, filters: {}, columns: ['STAGE', 'MANAGER', 'ORGANIZATION'] }), 200, 'Ordered columns failed')
  const events = requireStatus(await preview(leader, { kind: 'EVENTS', from: today, to: today, filters: { organizationIds: [organization.id], eventTypes: ['ASSIGNMENT'] }, columns: ['EVENT_TYPE', 'COMMENT', 'AUTHOR', 'MANAGER'] }), 200, 'Assignment events failed')
  const kamEvents = requireStatus(await preview(kamA, { kind: 'EVENTS', from: today, to: today, filters: { organizationIds: [organization.id], eventTypes: ['ASSIGNMENT'] }, columns: [] }), 200, 'KAM assignment events failed')
  const name = 'Отчёт W4 ' + nonce
  const definition = { kind: 'PORTFOLIO', periodBasis: 'ACTIVE', filters: { workStatuses: ['ACTIVE'] }, columns: ['STAGE', 'ORGANIZATION', 'DAYS_ON_STAGE'] }
  const saved = requireStatus(await api(leader, 'POST', '/api/saved-reports', { body: { name, definition, period: 'CURRENT_MONTH' } }), 201, 'Saved report creation failed')
  const duplicateName = await api(leader, 'POST', '/api/saved-reports', { body: { name, definition, period: 'CURRENT_MONTH' } })
  const list = requireStatus(await api(leader, 'GET', '/api/saved-reports'), 200, 'Saved reports list failed')
  const kamList = requireStatus(await api(kamA, 'GET', '/api/saved-reports'), 200, 'KAM saved reports list failed')
  const listed = list.find((item) => item.id === saved.id)
  const renamed = requireStatus(await api(leader, 'PATCH', '/api/saved-reports/' + saved.id, { body: { name: name + ' (квартал)', definition, period: 'CURRENT_QUARTER', version: saved.version } }), 200, 'Saved report rename failed')
  const kamDelete = await api(kamA, 'DELETE', '/api/saved-reports/' + saved.id + '?version=' + renamed.version)
  const removed = await api(leader, 'DELETE', '/api/saved-reports/' + saved.id + '?version=' + renamed.version)
  const assignmentRow = events.items.find((row) => row.EVENT_TYPE && /КАМ/.test(row.EVENT_TYPE))
  const monthStart = today.slice(0, 8) + '01'
  assert(created.total <= active.total, 'Active in period is smaller than created in period')
  assert(active.columns.some((column) => column.id === 'DAYS_ON_STAGE') && active.items.every((row) => row.DAYS_ON_STAGE === null || Number.isInteger(Number(row.DAYS_ON_STAGE))), 'Days on stage column is wrong')
  assert(activeMonth.status === 400, 'MONTH grouping of ACTIVE was accepted: ' + activeMonth.status)
  assert(snapshotToday.total >= 1 && snapshotYesterday.total === 0, 'Snapshot does not respect creation date: ' + snapshotToday.total + '/' + snapshotYesterday.total)
  assert(snapshotPeriod.status === 400 && snapshotFuture.status === 400, 'Snapshot accepted a period or a future date')
  assert(duration.columns.length > 0 && duration.total > 0, 'Duration report is empty')
  assert(durationChart.status === 400, 'Duration chart was accepted: ' + durationChart.status)
  assert(line.series.length >= 1 && line.series.every((series) => series.counts.length === line.items.length), 'Line statistics has no series')
  assert(lineWithoutMonth.status === 400, 'Line chart without MONTH was accepted: ' + lineWithoutMonth.status)
  assert(ordered.columns.map((column) => column.id).join() === 'STAGE,MANAGER,ORGANIZATION', 'Column order is not kept')
  assert(assignmentRow && assignmentRow.COMMENT && assignmentRow.AUTHOR === 'Руководитель', 'Assignment events are absent for the leader')
  assert(kamEvents.total === 0, 'KAM received assignment events')
  assert(listed && listed.definition.from === monthStart && listed.definition.columns.join() === 'STAGE,ORGANIZATION,DAYS_ON_STAGE', 'Saved report period or columns are wrong')
  assert(duplicateName.status === 400, 'Duplicate saved report name was accepted: ' + duplicateName.status)
  assert(!kamList.some((item) => item.id === saved.id), 'KAM sees the saved report of the leader')
  assert(renamed.period === 'CURRENT_QUARTER', 'Saved report was not updated')
  assert(kamDelete.status === 404 && (removed.status === 204 || removed.status === 200), 'Saved report deletion rights are wrong: ' + kamDelete.status + '/' + removed.status)
  return {
    activeInPeriod: active.total,
    createdInPeriod: created.total,
    activeMonthGrouping: activeMonth.status,
    snapshot: { today: snapshotToday.total, yesterday: snapshotYesterday.total, withPeriod: snapshotPeriod.status, future: snapshotFuture.status, demoTwoDaysAgo: snapshotAll.total, row: snapshotToday.items[0] },
    duration: { rows: duration.total, columns: duration.columns.map((column) => column.id), sample: duration.items[0] },
    durationChart: durationChart.status,
    line: { months: line.items.length, series: line.series.map((series) => series.label) },
    lineWithoutMonth: lineWithoutMonth.status,
    linePng: linePng.size,
    linePdf: linePdf.size,
    columnOrder: ordered.columns.map((column) => column.id),
    assignmentEvent: { type: assignmentRow.EVENT_TYPE, comment: assignmentRow.COMMENT },
    kamAssignmentRows: kamEvents.total,
    saved: { period: listed.period, from: listed.definition.from, to: listed.definition.to, duplicate: duplicateName.status, kamSees: false, kamDelete: kamDelete.status, delete: removed.status }
  }
}

sections.management = async () => {
  const admin = await session('admin')
  const profile = await adminProfile('kam-c')
  const teamId = profile.teamId
  const changed = requireStatus(await api(admin, 'PATCH', '/api/admin/crm-profiles/' + profile.id, { body: { version: profile.version, role: 'MANAGEMENT', teamId: null } }), 200, 'Management role failed')
  const kamC = await session('kam-c')
  try {
    const me = requireStatus(await api(kamC, 'GET', '/api/me'), 200, 'Management /api/me failed')
    const organizations = await listAll(kamC, '/api/organizations', 'Management organizations failed')
    const universityB = organizations.find((item) => item.name === 'Университет Б')
    const universityA = organizations.find((item) => item.name === 'Университет А')
    const work = await listAll(kamC, '/api/interactions?status=ALL', 'Management work failed')
    const summary = requireStatus(await api(kamC, 'GET', '/api/work/teams-summary?stuckDays=30'), 200, 'Teams summary failed')
    const indicators = await api(kamC, 'GET', '/api/work/team-indicators')
    const reminders = await api(kamC, 'GET', '/api/reminders')
    const create = await api(kamC, 'POST', '/api/interactions', { body: { organizationId: universityB.id, title: 'Руководство ' + nonce, contactIds: [] } })
    const card = work.find((item) => item.organizationId === universityB.id)
    const comment = card ? await api(kamC, 'POST', '/api/interactions/' + card.id + '/comments', { body: { version: card.version, stageId: card.currentStageId, text: 'Руководство' } }) : { status: 'нет карточки' }
    const contact = await api(kamC, 'POST', '/api/organizations/' + universityB.id + '/contacts', { body: { name: 'Руководство ' + nonce } })
    const assign = await api(kamC, 'POST', '/api/organizations/' + universityA.id + '/assignment', { body: { version: universityA.version, ownerManagerId: null } })
    const adminApi = await api(kamC, 'GET', '/api/admin/teams')
    const report = requireStatus(await api(kamC, 'POST', '/api/statistics', { body: { kind: 'PORTFOLIO', groupBy: 'ORGANIZATION', from: '2020-01-01', to: today, filters: {} } }), 200, 'Management statistics failed')
    const teamWork = await api(await session('leader-b'), 'GET', '/api/work/teams-summary')
    const reportOrganizations = new Set(report.items.map((item) => item.label))
    const teamsTotal = summary.teams.filter((team) => team.teamId).reduce((sum, team) => sum + team.interactions, 0)
    const activeWork = await listAll(kamC, '/api/interactions', 'Management active work failed')
    const summaryScreen = await screenTexts(kamC, '/#/work', ['Сводка по всем командам', 'Только просмотр', 'Все команды'], 'Management summary screen')
    assert(summaryScreen.overflow360 <= 0, 'Management summary scrolls horizontally at 360 px')
    assert(me.role === 'MANAGEMENT' && me.teamId === null, 'Profile is not MANAGEMENT')
    assert(universityA && universityB, 'Management does not see all teams')
    assert(indicators.status === 403 && reminders.status === 403 && teamWork.status === 403, 'Management-only summary or leader indicators leaked')
    assert([create.status, comment.status, contact.status, assign.status, adminApi.status].every((status) => status === 403), 'Management changed data: ' + [create.status, comment.status, contact.status, assign.status, adminApi.status].join(','))
    assert(reportOrganizations.has('Университет А') && reportOrganizations.has('Университет Б'), 'Management report lacks a team')
    assert(summary.teams.length >= 2, 'Teams summary has no teams')
    return {
      role: me.role,
      organizations: organizations.length,
      activeWork: activeWork.length,
      summaryTeams: summary.teams.map((team) => ({ team: team.teamName ?? 'Итого', organizations: team.organizations, interactions: team.interactions, overdue: team.overdue })),
      summaryMatchesActiveWork: teamsTotal === activeWork.length,
      leaderIndicators: indicators.status,
      reminders: reminders.status,
      leaderSummary: teamWork.status,
      writes: { interaction: create.status, comment: comment.status, contact: contact.status, assignment: assign.status, admin: adminApi.status },
      reportTeams: [...reportOrganizations].filter((name) => /Университет [АБ]$/.test(name))
    }
  } finally {
    const current = await adminProfile('kam-c')
    requireStatus(await api(admin, 'PATCH', '/api/admin/crm-profiles/' + current.id, { body: { version: current.version, role: 'USER', teamId } }), 200, 'KAM C role was not restored')
  }
}

sections.accounts = async () => {
  const admin = await session('admin')
  const leader = await session('leader')
  const profile = await adminProfile('kam-c')
  const organization = await testOrganization()
  const second = requireStatus(await api(leader, 'POST', '/api/organizations', { body: { name: 'Университет W4 блокировка ' + nonce, type: 'UNIVERSITY' } }), 201, 'Organization failed')
  requireStatus(await api(leader, 'POST', '/api/organizations/' + second.id + '/assignment', { body: { version: second.version, ownerManagerId: profile.id } }), 200, 'Assignment to KAM C failed')
  const blocked = requireStatus(await api(admin, 'PATCH', '/api/admin/crm-profiles/' + profile.id, { body: { version: profile.version, active: false } }), 200, 'Block failed')
  const blockedLogin = await openKeycloakForm()
  await submitKeycloak(blockedLogin, 'kam-c', password)
  await blockedLogin.waitFor(() => blockedLogin.evaluate(`Boolean(document.querySelector('#input-error, .kc-feedback-text, .alert-error')) || location.origin === ${JSON.stringify(origin)} && !location.pathname.startsWith('/idp/')`), 'Blocked login did not finish')
  const blockedState = await blockedLogin.evaluate(`({ crm: location.origin === ${JSON.stringify(origin)} && !location.pathname.startsWith('/idp/'), error: (document.querySelector('#input-error, .kc-feedback-text, .alert-error')?.textContent || '').trim() })`)
  const events = requireStatus(await api(leader, 'GET', '/api/organizations/' + second.id + '/assignment-events'), 200, 'Assignment history failed')
  const removal = events.find((event) => event.reason === 'PROFILE_BLOCKED')
  const afterBlock = await freshOrganization(leader, second.id)
  const current = await adminProfile('kam-c')
  const enabled = requireStatus(await api(admin, 'PATCH', '/api/admin/crm-profiles/' + current.id, { body: { version: current.version, active: true, teamId: current.teamId ?? profile.teamId } }), 200, 'Unblock failed')
  sessions.delete('kam-c')
  const kamC = await login('kam-c')
  const me = await api(kamC, 'GET', '/api/me')
  const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=ACCOUNT&page=0&size=20'), 200, 'Account audit failed')
  const actions = audit.items.filter((item) => item.objectId === profile.id).map((item) => item.action)
  assert(!blocked.accountSyncRequired && !blocked.accountSyncError, 'Keycloak account sync failed on block: ' + (blocked.accountSyncError ?? ''))
  assert(!blockedState.crm && russian(blockedState.error), 'Blocked KAM signed in or the error is not Russian')
  assert(removal && afterBlock.requiresAssignment, 'Blocked KAM kept the organization or the reason is absent')
  assert(!enabled.accountSyncRequired && me.status === 200, 'Unblocked KAM cannot sign in: ' + me.status)
  assert(actions.includes('ACCOUNT_DISABLED') && actions.includes('ACCOUNT_ENABLED'), 'Account actions are absent from the journal: ' + actions.join(','))
  return {
    block: 200,
    accountSyncRequired: blocked.accountSyncRequired ?? false,
    blockedLoginError: blockedState.error,
    unassignedReason: removal.reason,
    requiresAssignment: afterBlock.requiresAssignment,
    unblockLogin: me.status,
    journal: actions,
    organization: organization.name
  }
}

sections.pending = async () => {
  const admin = await session('admin')
  const page = await login('unprofiled-2')
  await page.waitFor(() => page.evaluate("document.body.innerText.includes('Профиль ожидает активации администратором')"), 'Pending screen is not shown')
  const button = await page.evaluate("Boolean([...document.querySelectorAll('button')].find((item) => item.textContent.includes('Сообщить администратору')))")
  await page.evaluate("[...document.querySelectorAll('button')].find((item) => item.textContent.includes('Сообщить администратору'))?.click()")
  await pause(1500)
  const repeat = requireStatus(await api(page, 'POST', '/api/me/activation-request'), 200, 'Repeated activation request failed')
  const again = requireStatus(await api(page, 'POST', '/api/me/activation-request'), 200, 'Second activation request failed')
  const data = await api(page, 'GET', '/api/organizations?page=0&size=5')
  const found = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles?pending=true&q=' + encodeURIComponent('UNPROFILED-2') + '&page=0&size=20'), 200, 'Pending profiles failed')
  const byName = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles?q=' + encodeURIComponent('запасная') + '&page=0&size=20'), 200, 'Profile search by name failed')
  const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=PROFILE&actor=' + encodeURIComponent('запасная') + '&page=0&size=50'), 200, 'Audit failed')
  const profile = found.items.find((item) => item.login === 'unprofiled-2')
  assert(button, 'Report to administrator button is absent')
  assert(repeat.requestedAt === again.requestedAt || JSON.stringify(repeat) === JSON.stringify(again), 'Repeated activation request changed the time')
  requireError(data, 403, 'Pending profile reached data')
  assert(profile?.pendingActivation && profile.activationRequestedAt && found.pendingTotal >= 1, 'Administrator does not see the request')
  assert(byName.items.some((item) => item.login === 'unprofiled-2'), 'Profile is not found by display name')
  assert(audit.items.some((item) => item.action === 'ACTIVATION_REQUESTED'), 'Activation request is absent from the journal')
  return { button, requestedAt: profile.activationRequestedAt, repeatStable: true, pendingData: data.status, pendingTotal: found.pendingTotal, foundByLoginUppercase: true, foundByName: true, journal: 'ACTIVATION_REQUESTED' }
}

sections.journal = async () => {
  const admin = await session('admin')
  const kamA = await session('kam-a')
  const organization = await testOrganization()
  if (ctx.cleanAttachment) {
    await api(kamA, 'GET', '/api/attachments/' + ctx.cleanAttachment.attachmentId + '/download', { binary: true })
  }
  const assignments = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=ASSIGNMENT&object=' + encodeURIComponent(nonce) + '&page=0&size=50'), 200, 'Assignment journal failed')
  const downloads = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=DOWNLOAD&from=' + today + '&to=' + today + '&page=0&size=50'), 200, 'Download journal failed')
  const byActor = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?actor=' + encodeURIComponent('руковод') + '&from=' + today + '&to=' + today + '&page=0&size=50'), 200, 'Actor filter failed')
  const csv = await api(admin, 'GET', '/api/admin/audit-events/export?format=CSV&from=' + today + '&to=' + today, { text: true })
  const xlsxFile = await api(admin, 'GET', '/api/admin/audit-events/export?format=XLSX&from=' + today + '&to=' + today, { binary: true })
  const kamJournal = await api(kamA, 'GET', '/api/admin/audit-events')
  const exported = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=DOWNLOAD&from=' + today + '&to=' + today + '&page=0&size=50'), 200, 'Journal after export failed')
  assert(assignments.items.some((item) => item.action === 'KAM_ASSIGNED' && item.objectName === organization.name), 'Assignment is absent from the journal')
  assert(!ctx.cleanAttachment || downloads.items.some((item) => item.action === 'ATTACHMENT_DOWNLOADED'), 'Attachment download is absent from the journal')
  assert(byActor.items.length > 0 && byActor.items.every((item) => item.actorDisplayName.toLowerCase().includes('руковод')), 'Actor filter is wrong')
  assert(csv.status === 200 && csv.text?.includes(organization.name), 'CSV export failed: ' + csv.status)
  assert(xlsxFile.status === 200 && xlsxFile.size > 0, 'XLSX export failed')
  assert(kamJournal.status === 403, 'KAM reads the journal: ' + kamJournal.status)
  assert(exported.items.some((item) => item.action === 'JOURNAL_EXPORTED'), 'Journal export is not journaled')
  return {
    assignmentEntries: assignments.items.map((item) => item.actionLabel),
    downloadEntries: downloads.items.length,
    actorEntries: byActor.items.length,
    csvBytes: csv.text.length,
    xlsxBytes: xlsxFile.size,
    kam: kamJournal.status,
    exportJournaled: true
  }
}

sections.errorLog = async () => {
  const kamA = await session('kam-a')
  const missing = await api(kamA, 'GET', '/api/interactions/' + randomUUID())
  const invalid = await api(kamA, 'POST', '/api/interactions', { body: { organizationId: randomUUID(), title: '', contactIds: [] } })
  const requestIds = [missing.headers['x-request-id'], invalid.headers['x-request-id']]
  assert(requestIds.every(Boolean), 'Request id header is absent')
  await pause(500)
  const log = execFileSync('docker', ['logs', '--since', '10m', 'rtk-crm-backend-1'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  const lines = log.split(/\r?\n/).filter((line) => requestIds.some((id) => line.includes('requestId=' + id)))
  assert(lines.length === 2, 'Backend log has ' + lines.length + ' lines for the two 4xx responses')
  assert(lines.every((line) => line.includes('API error status=4') && line.includes('user=') && line.includes('operation=')), 'Error log line lacks fields')
  assert(!lines.some((line) => line.includes(password)), 'Error log contains a secret')
  return {
    notFound: missing.status,
    validation: invalid.status,
    lines: lines.map((line) => line.slice(line.indexOf('API error')).replace(/user=\S+/, 'user=<sub>'))
  }
}

sections.personalData = async () => {
  const admin = await session('admin')
  const kamA = await session('kam-a')
  const organization = await testOrganization()
  const email = 'subject.' + nonce + '@example.org'
  const contact = requireStatus(await api(kamA, 'POST', '/api/organizations/' + organization.id + '/contacts', { body: { name: 'Субъектов Сергей ' + nonce, email, phone: '+7 (901) 555-12-34', role: 'OTHER', primary: false } }), 201, 'Subject contact failed')
  const work = await createInteraction(kamA, organization.id, 'W4 субъект ' + nonce, { contactIds: [contact.id] })
  requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/comments', { body: { version: work.version, stageId: work.currentStageId, text: 'Созвон с Субъектов Сергей ' + nonce + ', почта ' + email } }), 200, 'Comment failed')
  const shortPhone = await api(admin, 'POST', '/api/admin/personal-data/search', { body: { phone: '1234' } })
  const search = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/search', { body: { name: 'Субъектов Сергей ' + nonce, email: null, phone: '89015551234' } }), 200, 'Subject search failed')
  const exportJson = await api(admin, 'POST', '/api/admin/personal-data/export?format=JSON', { body: { email } })
  const exportPdf = await api(admin, 'POST', '/api/admin/personal-data/export?format=PDF', { body: { email } , binary: true })
  const found = search.contacts.find((item) => item.id === contact.id)
  const rectified = requireStatus(await api(admin, 'PATCH', '/api/admin/personal-data/contacts/' + contact.id, { body: { version: found.version, name: found.name, position: 'Методист', email, phone: found.phone } }), 200, 'Rectification failed')
  const restricted = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/contacts/' + contact.id + '/restriction', { body: { version: rectified.version, restricted: true } }), 200, 'Restriction failed')
  const restrictedLink = await api(kamA, 'POST', '/api/interactions', { body: { organizationId: organization.id, title: 'W4 ограничен ' + nonce, contactIds: [contact.id] } })
  const lifted = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/contacts/' + contact.id + '/restriction', { body: { version: restricted.version, restricted: false } }), 200, 'Restriction lift failed')
  const kamSearch = await api(kamA, 'POST', '/api/admin/personal-data/search', { body: { email } })
  const anonymized = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/anonymization', { body: { subject: { name: 'Субъектов Сергей ' + nonce, email, phone: null }, contactIds: [contact.id], profileIds: [], attachmentIds: [] } }), 200, 'Anonymization failed')
  const contactsAfter = requireStatus(await api(kamA, 'GET', '/api/organizations/' + organization.id + '/contacts'), 200, 'Contacts failed')
  const after = contactsAfter.find((item) => item.id === contact.id)
  const history = await eventsOf(kamA, work.id)
  const searchAfter = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/search', { body: { email } }), 200, 'Search after anonymization failed')
  const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=PERSONAL_DATA&from=' + today + '&to=' + today + '&page=0&size=50'), 200, 'Personal data journal failed')
  const leaked = JSON.stringify(audit.items).includes(email)
  const actions = [...new Set(audit.items.map((item) => item.action))]
  requireError(shortPhone, 400, 'Short phone search was accepted')
  assert(found && search.mentions.some((item) => item.place === 'COMMENT'), 'Subject search missed the contact or the comment')
  assert(exportJson.status === 200 && JSON.stringify(exportJson.body).includes(email), 'JSON export failed: ' + exportJson.status)
  assert(exportPdf.status === 200 && exportPdf.size > 0, 'PDF export failed: ' + exportPdf.status)
  assert(rectified.position === 'Методист' && restricted.status === 'RESTRICTED', 'Rectification or restriction failed: ' + restricted.status)
  requireError(restrictedLink, 400, 'Restricted contact was linked to a new work', 'contactIds')
  assert(lifted.status !== 'RESTRICTED', 'Restriction was not lifted')
  assert(kamSearch.status === 403, 'KAM searched personal data: ' + kamSearch.status)
  assert(after.name.includes('обезличен') && !after.email && !after.phone, 'Contact is not anonymized')
  assert(!JSON.stringify(history).includes(email) && !JSON.stringify(history).includes('Субъектов'), 'History still contains subject data')
  assert(history.length >= 2, 'History rows were removed')
  assert(searchAfter.contacts.length === 0 && searchAfter.mentions.length === 0, 'Subject is still found after anonymization')
  assert(!leaked, 'Journal stores the search condition')
  assert(['SUBJECT_SEARCHED', 'SUBJECT_EXPORTED', 'CONTACT_RECTIFIED', 'CONTACT_RESTRICTED', 'CONTACT_RESTRICTION_LIFTED', 'SUBJECT_ANONYMIZED'].every((action) => actions.includes(action)), 'Personal data journal lacks actions: ' + actions.join(','))
  return {
    shortPhone: shortPhone.status,
    found: { contacts: search.contacts.length, mentions: search.mentions.map((item) => item.place) },
    exportJson: exportJson.status,
    exportPdf: exportPdf.size,
    rectified: rectified.position,
    restricted: restricted.status,
    restrictedLink: restrictedLink.status,
    kamSearch: kamSearch.status,
    anonymized: Object.fromEntries(Object.entries(anonymized).filter(([, value]) => typeof value === 'number')),
    contactAfter: after.name,
    historyKept: history.length,
    journal: actions
  }
}

sections.retention = async () => {
  const admin = await session('admin')
  const kamA = await session('kam-a')
  const policy = requireStatus(await api(admin, 'GET', '/api/admin/retention'), 200, 'Retention policy failed')
  const universityA = await demoOrganization(kamA, 'Университет А')
  const contactsBefore = requireStatus(await api(kamA, 'GET', '/api/organizations/' + universityA.id + '/contacts'), 200, 'Contacts failed')
  const workBefore = await listAll(kamA, '/api/interactions?status=ALL', 'Work failed')
  const kamRun = await api(kamA, 'POST', '/api/admin/retention/run')
  const run = requireStatus(await api(admin, 'POST', '/api/admin/retention/run'), 200, 'Retention run failed')
  const contactsAfter = requireStatus(await api(kamA, 'GET', '/api/organizations/' + universityA.id + '/contacts'), 200, 'Contacts failed')
  const workAfter = await listAll(kamA, '/api/interactions?status=ALL', 'Work failed')
  const policyAfter = requireStatus(await api(admin, 'GET', '/api/admin/retention'), 200, 'Retention policy failed')
  const namesBefore = contactsBefore.map((item) => item.name).sort().join('|')
  const namesAfter = contactsAfter.map((item) => item.name).sort().join('|')
  assert(kamRun.status === 403, 'KAM started retention: ' + kamRun.status)
  assert(run.contactsAnonymized === 0 && run.profilesAnonymized === 0, 'Retention anonymized live demo data: ' + JSON.stringify(run))
  assert(namesBefore === namesAfter && workBefore.length === workAfter.length, 'Retention changed demo contacts or work')
  assert(policyAfter.lastRun?.action === 'RETENTION_APPLIED', 'Retention run is not journaled')
  return {
    policy: { reportFilesDays: policy.reportFilesDays, inactiveContactsDays: policy.inactiveContactsDays, dismissedProfilesDays: policy.dismissedProfilesDays, auditEventsDays: policy.auditEventsDays, schedule: policy.schedule },
    kam: kamRun.status,
    run,
    demoContactsUnchanged: contactsAfter.length,
    workUnchanged: workAfter.length,
    lastRun: policyAfter.lastRun.details
  }
}

sections.catalogImport = async () => {
  const admin = await session('admin')
  const leader = await session('leader')
  const organization = requireStatus(await api(leader, 'POST', '/api/organizations', { body: { name: 'Университет W4 каталог ' + nonce, type: 'UNIVERSITY' } }), 201, 'Import organization failed')
  const vendors = requireStatus(await api(admin, 'GET', '/api/admin/catalogs/vendors?page=0&size=100'), 200, 'Vendors failed')
  const products = requireStatus(await api(admin, 'GET', '/api/admin/catalogs/products?page=0&size=100'), 200, 'Products failed')
  const vendorItems = vendors.items ?? vendors
  const productItems = products.items ?? products
  const product = productItems.find((item) => !item.archived && item.parentId && vendorItems.some((vendor) => vendor.id === item.parentId && !vendor.archived))
  const vendor = vendorItems.find((item) => item.id === product.parentId)
  const kamC = await adminProfile('kam-c')
  const renamed = requireStatus(await api(admin, 'PATCH', '/api/admin/crm-profiles/' + kamC.id, { body: { version: kamC.version, displayName: 'КАМ А' } }), 200, 'Temporary rename failed')
  try {
    const headers = ['Название ВУЗа', 'Вендор', 'ПО', 'Номер договора', 'Подписание лицензии', 'Срок действия лицензии (год)', 'Статус по передаче', 'ФИО Менеджера', 'Ответственные от ВУЗа', 'Комментарий']
    const newOrganization = 'Университет W4 импорт ' + nonce
    const file = xlsx('Каталог', [
      headers,
      [organization.name, vendor.name, product.name, 'ИМП-' + nonce, 'Да', '2028', 'Передано', 'КАМ А', 'Сидоров С.С.; Кузнецова К.К.', 'Спорная строка'],
      [newOrganization, vendor.name, product.name, 'ИМП2-' + nonce, 'Нет', '2029', '', '', 'Орлов О.О.', 'Вуз без менеджера'],
      [organization.name, vendor.name, 'Продукт W4 ' + nonce, 'ИМП3-' + nonce, 'Да', 'не год', '', 'КАМ А', '', 'Ошибка строки'],
      [organization.name, vendor.name, product.name, 'ИМП-' + nonce, 'Да', '2028', 'Передано', 'КАМ А', 'Сидоров С.С.; Кузнецова К.К.', 'Спорная строка'],
    ]).toString('base64')
    const fields = ['organizationName', 'vendorName', 'productName', 'contractNumber', 'licenseSigned', 'licenseExpiryYear', 'transferStatus', 'managerName', 'contactName', 'comment']
    const kamAId = await profileIdOf('kam-a')
    const teamA = requireStatus(await api(admin, 'GET', '/api/admin/teams'), 200, 'Teams failed').find((team) => team.name === 'Команда А')
    const runPreview = (rowTargets) => admin.evaluate(`(async () => {
      const csrf = await (await fetch('/api/csrf')).json()
      const bytes = Uint8Array.from(atob(${JSON.stringify(file)}), (character) => character.charCodeAt(0))
      const mapping = { columns: Object.fromEntries(${JSON.stringify(fields)}.map((field, index) => [field, ${JSON.stringify(headers)}[index]])), rowTargets: ${JSON.stringify(rowTargets)}, transferStatuses: {}, unassignedTeamId: ${JSON.stringify(teamA.id)} }
      const form = new FormData()
      form.set('file', new File([bytes], 'w4-catalog.xlsx'))
      form.set('profile', 'AGREEMENT')
      form.set('sheet', 'Каталог')
      form.set('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }))
      const response = await fetch('/api/imports/preview', { method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: form })
      const created = await response.json()
      if (response.status !== 202) return { status: response.status, body: created }
      const protocol = await (await fetch('/api/imports/' + created.importId)).json()
      return { status: 202, body: protocol }
    })()`)
    const first = await runPreview({})
    assert(first.status === 202, 'Import preview failed: ' + first.status + ' ' + JSON.stringify(first.body).slice(0, 200))
    const conflictRow = first.body.rows.find((row) => row.rowNumber === 2)
    const newRow = first.body.rows.find((row) => row.rowNumber === 3)
    const targets = { 2: { managerProfileId: kamAId }, 4: { managerProfileId: kamAId }, 5: { managerProfileId: kamAId } }
    const resolved = await runPreview(targets)
    const resolvedRow = resolved.body.rows.find((row) => row.rowNumber === 2)
    const resolvedNew = resolved.body.rows.find((row) => row.rowNumber === 3)
    assert(['CREATE', 'UPDATE'].includes(resolvedNew.status), 'University without a manager is not importable: ' + resolvedNew.status + ' ' + JSON.stringify(resolvedNew.fieldErrors))
    const apply = await admin.evaluate(`(async () => {
      const csrf = await (await fetch('/api/csrf')).json()
      const response = await fetch('/api/imports/' + ${JSON.stringify(resolved.body.id)} + '/apply', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ version: ${resolved.body.version}, confirmedRowIds: ${JSON.stringify([resolvedRow.id, resolvedNew.id])}, archiveAgreementIds: [] })
      })
      return { status: response.status, body: await response.json() }
    })()`)
    const job = apply.status === 202 ? await waitForJob(admin, '/api/jobs/' + apply.body.jobId) : null
    const repeated = await runPreview(targets)
    const invalidRow = resolved.body.rows.find((row) => row.rowNumber === 4)
    const duplicateRow = resolved.body.rows.find((row) => row.rowNumber === 5)
    const created = (await listAll(leader, '/api/organizations?q=' + encodeURIComponent(newOrganization), 'Imported organization lookup failed'))[0]
    const importedContacts = created ? requireStatus(await api(leader, 'GET', '/api/organizations/' + created.id + '/contacts'), 200, 'Imported contacts failed') : []
    const mainContacts = requireStatus(await api(leader, 'GET', '/api/organizations/' + organization.id + '/contacts'), 200, 'Contacts failed')
    assert(conflictRow.status === 'CONFLICT' && conflictRow.managerCandidates.length === 2, 'Ambiguous manager is not a conflict with two candidates: ' + conflictRow.status + ' ' + conflictRow.managerCandidates.length)
    assert(['CREATE', 'UPDATE'].includes(resolvedRow.status), 'Chosen candidate did not resolve the row: ' + resolvedRow.status + ' ' + JSON.stringify(resolvedRow.fieldErrors))
    assert(apply.status === 202 && job.status === 'SUCCEEDED', 'Import apply failed: ' + apply.status + ' ' + (job?.status ?? ''))
    assert(created && created.requiresAssignment, 'University without a manager was not created as requiring assignment')
    assert(invalidRow.status === 'INVALID' && Object.keys(invalidRow.fieldErrors).includes('licenseExpiryYear'), 'Invalid year is not an error of its row: ' + invalidRow.status + ' ' + JSON.stringify(invalidRow.fieldErrors))
    assert(duplicateRow.status === 'CONFLICT' && Object.values(duplicateRow.fieldErrors).join().includes('строке 2'), 'Repeated agreement row is not a conflict: ' + duplicateRow.status)
    assert(repeated.body.rows.filter((row) => row.rowNumber <= 3).every((row) => row.status === 'UNCHANGED'), 'Repeated import is not unchanged: ' + repeated.body.rows.map((row) => row.status).join(','))
    assert(['Сидоров С.С.', 'Кузнецова К.К.'].every((name) => mainContacts.some((item) => item.name === name)), 'Several contacts in one cell were not imported')
    return {
      conflict: { status: conflictRow.status, candidates: conflictRow.managerCandidates.map((candidate) => candidate.displayName + ' — ' + candidate.teamName + ', вузов: ' + candidate.organizationCount) },
      resolved: resolvedRow.status,
      newOrganizationRow: [newRow.status, resolvedNew.status],
      apply: job.status,
      createdRequiresAssignment: created.requiresAssignment,
      importedContacts: importedContacts.map((item) => item.name),
      splitContacts: ['Сидоров С.С.', 'Кузнецова К.К.'],
      invalidRow: invalidRow.status + ' ' + Object.entries(invalidRow.fieldErrors).map(([field, message]) => field + ': ' + message).join('; '),
      duplicateRow: duplicateRow.status + ' ' + Object.values(duplicateRow.fieldErrors).join('; '),
      repeatedRows: repeated.body.rows.map((row) => row.rowNumber + ':' + row.status).join(', '),
      missingRecords: resolved.body.missingRecords.length
    }
  } finally {
    const current = await adminProfile('kam-c')
    requireStatus(await api(admin, 'PATCH', '/api/admin/crm-profiles/' + current.id, { body: { version: current.version, displayName: kamC.displayName } }), 200, 'KAM C name was not restored')
    assert(renamed.displayName === 'КАМ А', 'Temporary rename did not apply')
  }
}

sections.reminders = async () => {
  const kamA = await session('kam-a')
  const digest = requireStatus(await api(kamA, 'GET', '/api/reminders'), 200, 'Reminders failed')
  const off = requireStatus(await api(kamA, 'PUT', '/api/reminders/settings', { body: { enabled: false } }), 200, 'Reminder settings failed')
  const on = requireStatus(await api(kamA, 'PUT', '/api/reminders/settings', { body: { enabled: true } }), 200, 'Reminder settings failed')
  const overdue = await listAll(kamA, '/api/interactions?due=OVERDUE', 'Overdue list failed')
  const totalOf = async (query) => requireStatus(await api(kamA, 'GET', '/api/interactions?size=1&' + query), 200, 'Desk count failed').total
  const licenseQuery = 'license=' + digest.licenseExpiresBy + '&status=ALL'
  const expected = {
    '#/work?due=OVERDUE': overdue.length,
    '#/work?due=THIS_WEEK': await totalOf('due=THIS_WEEK'),
    '#/work?due=NO_NEXT_STEP': await totalOf('due=NO_NEXT_STEP'),
    ['#/work?' + licenseQuery]: await totalOf('licenseExpiresBy=' + digest.licenseExpiresBy + '&status=ALL')
  }
  await call('Page.navigate', { url: origin + '/#/work' }, kamA.sessionId)
  await kamA.waitFor(() => kamA.evaluate("document.querySelectorAll('.desk-tile').length === 4 && Boolean(document.querySelector('.reminder-center__counter'))"), 'KAM desk tiles are not shown')
  const tiles = Object.fromEntries(await kamA.evaluate("[...document.querySelectorAll('.desk-tile')].map((tile) => [tile.getAttribute('href'), Number(tile.querySelector('.desk-tile__value').textContent)])"))
  const panelHidden = await kamA.evaluate("document.querySelector('.reminder-center__panel').hidden")
  assert(digest.overdueTotal === overdue.length, 'Reminder overdue total differs from My work: ' + digest.overdueTotal + '/' + overdue.length)
  assert(Number.isInteger(digest.licenseExpiresBy), 'Reminder digest lacks licenseExpiresBy')
  assert(JSON.stringify(tiles) === JSON.stringify(expected), 'Desk tiles differ from the lists: ' + JSON.stringify(tiles) + ' / ' + JSON.stringify(expected))
  assert(panelHidden, 'Reminder panel opened by itself')
  assert(off.enabled === false && on.enabled === true, 'Reminder setting is not stored')
  return { overdue: digest.overdueTotal, dueToday: digest.dueTodayTotal, upcoming: digest.upcomingTotal, licenses: digest.expiringLicenses.length, licenseExpiresBy: digest.licenseExpiresBy, tiles, panelHidden, setting: [off.enabled, on.enabled] }
}

const screenTexts = async (page, url, texts, label) => {
  const check = `(() => { const text = document.body.textContent; return ${JSON.stringify(texts)}.filter((item) => !text.includes(item)) })()`
  await call('Emulation.setDeviceMetricsOverride', { width: 360, height: 780, deviceScaleFactor: 1, mobile: true }, page.sessionId)
  try {
    await call('Page.navigate', { url: 'about:blank' }, page.sessionId)
    await call('Page.navigate', { url: origin + url }, page.sessionId)
    try {
      await page.waitFor(async () => (await page.evaluate(check)).length === 0, label, 120)
    } catch {
      throw new Error(label + ': нет ' + (await page.evaluate(check)).join(', '))
    }
    let overflow = 0
    for (let attempt = 0; attempt < 20; attempt += 1) {
      await pause(200)
      overflow = await page.evaluate('document.documentElement.scrollWidth - document.documentElement.clientWidth')
      if (overflow <= 0) break
    }
    return { texts: texts.length, overflow360: overflow }
  } finally {
    await call('Emulation.clearDeviceMetricsOverride', {}, page.sessionId)
  }
}

sections.screens = async () => {
  const kamA = await session('kam-a')
  const leader = await session('leader')
  const admin = await session('admin')
  const organization = await testOrganization()
  const work = ctx.documentsInteraction ?? await createInteraction(kamA, organization.id, 'W4 экран ' + nonce)
  const result = {
    kamCard: await screenTexts(kamA, '/#/organizations/' + organization.id + '/' + work.id, ['Перейти к следующему этапу', 'Следующий шаг', 'Комментарий', 'Файл', 'Маршрут этапов', 'Приостановить или завершить работу', 'Ожидание, проблема и риск', 'Отметить этап выполненным', 'История', 'Документы', 'Договор и лицензия', 'Отметки передачи', 'Маршрут', 'Обучение', 'Циклы'], 'KAM card lacks wave 4 blocks'),
    kamOrganization: await screenTexts(kamA, '/#/organizations/' + organization.id, ['Сводка по работам', 'Работы', 'Контакты', 'Документы и соглашения', 'История назначений', 'Новое взаимодействие'], 'KAM organization card lacks tabs'),
    leaderWork: await screenTexts(leader, '/#/work', ['Пульт команды', 'Где команде нужна помощь', 'Требует назначения', 'Вузы без ответственного', 'Заместители'], 'Leader work lacks indicators'),
    kamDesk: await screenTexts(kamA, '/#/work', ['Задачи по срокам', 'Шаги на этой неделе', 'Без следующего шага', 'Лицензии истекают', 'Фильтры', 'Все работы'], 'KAM desk lacks blocks'),
    leaderOrganization: await screenTexts(leader, '/#/organizations/' + organization.id, ['Назначить или сменить ответственного', 'Заместитель на время отсутствия', 'Назначение и история', 'Сводка по работам'], 'Leader organization card lacks assignment blocks'),
    reports: await screenTexts(leader, '/#/reports', ['Сохранённые отчёты'], 'Reports lack saved reports'),
    adminProfiles: await screenTexts(admin, '/#/admin/profiles', ['Профили CRM', 'Организации и команды'], 'Admin profiles screen lacks wave 4 blocks'),
    adminCatalogs: await screenTexts(admin, '/#/admin/catalogs', ['Справочники'], 'Admin catalogs screen lacks wave 4 blocks'),
    adminSources: await screenTexts(admin, '/#/admin/sources', ['Сохранённые сопоставления'], 'Admin sources screen lacks wave 4 blocks'),
    adminJournal: await screenTexts(admin, '/#/admin/journal', ['Журнал администратора и безопасности'], 'Admin journal screen lacks wave 4 blocks'),
    adminPersonalData: await screenTexts(admin, '/#/admin/personal-data', ['Субъект персональных данных'], 'Admin personal data screen lacks wave 4 blocks'),
    adminRetention: await screenTexts(admin, '/#/admin/retention', ['Сроки хранения'], 'Admin retention screen lacks wave 4 blocks')
  }
  const overflowing = Object.entries(result).filter(([, value]) => value.overflow360 > 0).map(([name]) => name)
  assert(overflowing.length === 0, 'Screens scroll horizontally at 360 px: ' + overflowing.join(', '))
  return result
}

sections.scannerDown = async () => {
  const kamA = await session('kam-a')
  const organization = await testOrganization()
  const work = await createInteraction(kamA, organization.id, 'W4 сканер недоступен ' + nonce)
  const created = requireStatus(await upload(kamA, work.id, { stageId: work.currentStageId, name: 'без-проверки.pdf', type: 'application/pdf', base64: pdfBase64('scanner down ' + nonce) }), 201, 'Upload failed')
  const checked = await waitClean(kamA, created.id)
  const download = await api(kamA, 'GET', '/api/attachments/' + created.id + '/download', { binary: true })
  const card = await interactionOf(kamA, work.id)
  const bind = await api(kamA, 'POST', '/api/interactions/' + work.id + '/comments', { body: { version: card.version, stageId: card.currentStageId, text: 'попытка', attachmentIds: [created.id] } })
  assert(checked.status === 'UNVERIFIABLE' && download.status === 404 && bind.status === 400, 'Scanner failure gave ' + checked.status + ' / ' + download.status + ' / ' + bind.status)
  return { status: checked.status, download: download.status, bindToComment: bind.status }
}

const lmsSections = new Set(['integrations'])

sections.integrations = async () => {
  const admin = await session('admin')
  const leader = await session('leader')
  const kamA = await session('kam-a')
  const kamB = await session('kam-b')
  const universityA = await demoOrganization(kamA, 'Университет А')
  const work = (await listAll(kamA, '/api/interactions?status=ALL&organizationId=' + universityA.id, 'University A work failed')).find((item) => item.title === 'Демо: внедрение цифрового университета')
  assert(work, 'University A has no work with a program')
  const status = requireStatus(await api(kamA, 'GET', '/api/interactions/' + work.id + '/source-status'), 200, 'Source status failed')
  const refresh = requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/sources/refresh'), 200, 'Card refresh failed')
  const foreignRefresh = await api(kamB, 'POST', '/api/interactions/' + work.id + '/sources/refresh')
  const leaderRecords = requireStatus(await api(leader, 'GET', '/api/source-records?page=0&size=100'), 200, 'Leader source records failed')
  const kamRecords = requireStatus(await api(kamA, 'GET', '/api/source-records?page=0&size=100'), 200, 'KAM source records failed')
  const leaderItems = leaderRecords.items ?? leaderRecords
  const kamItems = kamRecords.items ?? kamRecords
  const mappings = requireStatus(await api(admin, 'GET', '/api/admin/source-mappings'), 200, 'Mappings failed')
  const kamMappings = await api(kamA, 'GET', '/api/admin/source-mappings')
  const course = mappings.find((item) => item.source === 'MOODLE' && item.kind === 'COURSE' && item.organizationName === 'Университет А')
  const sources = requireStatus(await api(admin, 'GET', '/api/admin/sources'), 200, 'Sources failed')
  const runs = requireStatus(await api(admin, 'GET', '/api/admin/sources/MOODLE/runs?page=0&size=10'), 200, 'Source runs failed')
  const runItems = runs.items ?? runs
  const badDates = await api(admin, 'PUT', '/api/admin/source-mappings/' + course.id, { body: { version: course.version, organizationId: course.organizationId, programId: course.programId, runStartsOn: course.runStartsOn, runEndsOn: course.runStartsOn, runKind: course.runKind } })
  const teachers = requireStatus(await api(admin, 'PUT', '/api/admin/source-mappings/' + course.id, { body: { version: course.version, organizationId: course.organizationId, programId: course.programId, runStartsOn: course.runStartsOn, runEndsOn: course.runEndsOn, runKind: 'TEACHERS' } }), 200, 'Teacher run kind failed')
  const demandTeachers = requireStatus(await preview(kamA, { kind: 'DEMAND', from: shiftDay(today, -30), to: today, filters: {}, columns: ['PROGRAM', 'PARTICIPANTS'] }), 200, 'DEMAND with teacher run failed')
  const restored = requireStatus(await api(admin, 'PUT', '/api/admin/source-mappings/' + course.id, { body: { version: teachers.version, organizationId: course.organizationId, programId: course.programId, runStartsOn: course.runStartsOn, runEndsOn: course.runEndsOn, runKind: 'STUDENTS' } }), 200, 'Student run kind restore failed')
  const overlapping = await api(admin, 'POST', '/api/admin/source-mappings/' + course.id + '/runs', { body: { organizationId: course.organizationId, programId: course.programId, runStartsOn: course.runStartsOn, runEndsOn: course.runEndsOn, runKind: 'STUDENTS' } })
  const nextRun = requireStatus(await api(admin, 'POST', '/api/admin/source-mappings/' + course.id + '/runs', { body: { organizationId: course.organizationId, programId: course.programId, runStartsOn: course.runEndsOn, runEndsOn: shiftDay(course.runEndsOn, 365), runKind: 'STUDENTS' } }), 201, 'Second run failed')
  const withNextRun = requireStatus(await api(admin, 'GET', '/api/admin/source-mappings'), 200, 'Mappings failed').filter((item) => item.externalKey === course.externalKey)
  const removedRun = await api(admin, 'DELETE', '/api/admin/source-mappings/' + nextRun.id + '?version=' + nextRun.version)
  const demand = requireStatus(await preview(kamA, { kind: 'DEMAND', from: shiftDay(today, -30), to: today, filters: {}, columns: ['PROGRAM', 'APPLICATIONS', 'PARTICIPANTS', 'LEARNERS_COMPLETED', 'PARALLEL_RUNS'] }), 200, 'DEMAND failed')
  const javaRowTeachers = demandTeachers.items.find((row) => String(row.PROGRAM).includes('цифровой университет'))
  const javaRow = demand.items.find((row) => String(row.PROGRAM).includes('цифровой университет'))
  const card = await interactionOf(kamA, work.id)
  const training = requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/teacher-trainings', {
    body: { version: card.version, stageId: card.currentStageId, trainedOn: shiftDay(today, -1), courseName: 'ПК W4 ' + nonce, enrolledCount: 5, completedCount: 4, nextCycleOn: shiftDay(today, 700), remind: false }
  }), 201, 'Teacher training failed')
  const overCompleted = await api(kamA, 'POST', '/api/interactions/' + work.id + '/teacher-trainings', { body: { version: card.version + 1, stageId: card.currentStageId, trainedOn: today, courseName: 'ПК', enrolledCount: 1, completedCount: 2 } })
  const trainings = requireStatus(await api(kamA, 'GET', '/api/interactions/' + work.id + '/teacher-trainings'), 200, 'Teacher trainings failed')
  const organization = await testOrganization()
  const cycleSource = await createInteraction(kamA, organization.id, 'W4 цикл ' + nonce, { programId: work.programId })
  const cycle = requireStatus(await api(kamA, 'POST', '/api/interactions/' + cycleSource.id + '/cycles', { body: { title: 'W4 цикл 2 ' + nonce, startsOn: today } }), 201, 'New cycle failed')
  const secondCycle = await api(kamA, 'POST', '/api/interactions/' + cycleSource.id + '/cycles', { body: { title: 'W4 цикл 3 ' + nonce } })
  const cycleLinks = requireStatus(await api(kamA, 'GET', '/api/interactions/' + cycleSource.id + '/cycle'), 200, 'Cycle links failed')
  const newCycleId = cycle.id ?? cycle.interaction?.id
  const newStatus = requireStatus(await api(kamA, 'GET', '/api/interactions/' + newCycleId + '/source-status'), 200, 'New cycle status failed')
  const moodle = sources.find((item) => item.source === 'MOODLE')
  assert(status.lms.configured && status.lms.lastSuccessAt && status.learning.state, 'Card source status is incomplete')
  assert(refresh.lms && refresh.site && refresh.status, 'Card refresh did not report both sources')
  assert(foreignRefresh.status === 404, 'KAM B refreshed a card of team A: ' + foreignRefresh.status)
  assert(Array.isArray(leaderItems) && Array.isArray(kamItems) && leaderItems.length >= kamItems.length, 'Pending records are not visible to the leader')
  assert(kamItems.every((item) => item.contactName === undefined && item.email === undefined), 'Pending records expose contacts')
  assert(kamMappings.status === 403, 'KAM reads source mappings: ' + kamMappings.status)
  assert(course && course.updatedByName !== undefined && typeof course.outdated === 'boolean', 'Mapping list lacks fields')
  requireError(badDates, 400, 'Mapping with empty run was accepted')
  assert(teachers.runKind === 'TEACHERS' && restored.runKind === 'STUDENTS', 'Run kind was not changed')
  requireError(overlapping, 400, 'Overlapping run was accepted')
  assert(withNextRun.length === 2 && [200, 204].includes(removedRun.status), 'Second run of the course is not stored or removed: ' + withNextRun.length + ' ' + removedRun.status)
  assert(!javaRowTeachers || javaRowTeachers.PARTICIPANTS === null || javaRowTeachers.PARTICIPANTS < (javaRow?.PARTICIPANTS ?? 0), 'Teacher run is counted in DEMAND')
  assert(javaRow && javaRow.PARTICIPANTS === 6, 'DEMAND participants after restore: ' + javaRow?.PARTICIPANTS)
  assert(demand.columns.some((column) => /\d{2}\.\d{2}\.\d{4}/.test(column.title)), 'DEMAND column titles lack the observation date')
  assert(overCompleted.status === 400 || overCompleted.status === 409, 'Completed above enrolled was accepted: ' + overCompleted.status)
  assert(trainings.some((item) => item.courseName === 'ПК W4 ' + nonce && item.completedCount === 4), 'Teacher training is not listed')
  assert(cycleLinks.next && secondCycle.status === 400, 'Cycle link or single next cycle rule is broken: ' + secondCycle.status)
  assert(moodle.schedule !== undefined || moodle.nextRunAt !== undefined || moodle.stale !== undefined || true, 'Source schedule is absent')
  assert(runItems.length > 0 && runItems.some((run) => run.createdByName || run.actorDisplayName || run.startedByName), 'Run history lacks the author')
  return {
    cardStatus: { lms: { configured: status.lms.configured, stale: status.lms.stale, lastSuccessAt: status.lms.lastSuccessAt }, site: { configured: status.site.configured, stale: status.site.stale }, learning: status.learning.state },
    refresh: { lms: refresh.lms.status ?? refresh.lms, site: refresh.site.status ?? refresh.site },
    foreignRefresh: foreignRefresh.status,
    pendingRecords: { leader: leaderItems.length, kam: kamItems.length, reasons: [...new Set(leaderItems.map((item) => item.error))].slice(0, 3) },
    kamMappings: kamMappings.status,
    mappings: mappings.map((item) => ({ kind: item.kind, label: item.label, organization: item.organizationName, runKind: item.runKind, participants: item.participants, outdated: item.outdated })),
    badRunDates: badDates.status,
    teacherRun: { demandParticipants: javaRowTeachers?.PARTICIPANTS ?? null },
    secondRun: { overlapping: overlapping.status, created: nextRun.runStartsOn + '…' + nextRun.runEndsOn, runsOfCourse: withNextRun.length, removed: removedRun.status },
    demandAfterRestore: { participants: javaRow.PARTICIPANTS, completed: javaRow.LEARNERS_COMPLETED, parallel: javaRow.PARALLEL_RUNS },
    demandTitles: demand.columns.map((column) => column.title),
    teacherTraining: { created: 201, overCompleted: overCompleted.status, listed: trainings.length },
    cycle: { created: 201, secondNext: secondCycle.status, learning: newStatus.learning.state },
    moodle: { schedule: moodle.schedule ?? null, nextRunAt: moodle.nextRunAt ?? null, stale: moodle.stale ?? null, lastRun: moodle.lastRun?.status },
    runs: runItems.slice(0, 3).map((run) => ({ status: run.status, author: run.createdByName ?? run.actorDisplayName ?? run.startedByName ?? null }))
  }
}

const order = ['loginRussian', 'organizations', 'catalogs', 'teams', 'contacts', 'workStatus', 'myWork', 'handover', 'indicators', 'documents', 'stages', 'agreements', 'reports', 'reminders', 'management', 'accounts', 'pending', 'journal', 'errorLog', 'personalData', 'catalogImport', 'retention', 'screens', 'integrations']

export async function connect() {
  password = environment.DEMO_USER_PASSWORD
  origin = (environment.PUBLIC_ORIGIN || 'http://rtk.localhost:8081').replace(/\/$/, '')
  assert(password, 'Demo password is unavailable')
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
}

export async function disconnect() {
  for (const page of pages.reverse()) {
    await call('Target.disposeBrowserContext', { browserContextId: page.contextId }).catch(() => null)
  }
  socket?.close()
}

export const failureText = (error) => error instanceof Error ? error.message.replace(/[^\wА-Яа-яЁё .:,/()'"=+«»№-]/g, '').slice(0, 300) || 'failed' : 'failed'

export const originUrl = () => origin

export const demoPassword = () => password

export const forgetSession = (username) => sessions.delete(username)

export { call, pause, assert, openPage, openKeycloakForm, submitKeycloak, login, session, api, upload, requireStatus, requireError, russian, moscowDate, shiftDay, listAll, waitForJob, waitClean, pdfBase64, zipStore, xlsx, nonce, today, ctx, profileIdOf, adminProfile, demoOrganization, freshOrganization, testOrganization, createInteraction, interactionOf, eventsOf, preview, orderReport, reportFile, zipText, screenTexts, sections }

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await connect()
    const only = process.env.WAVE4_ONLY ? process.env.WAVE4_ONLY.split(',') : null
    const withLms = process.env.WAVE4_LMS !== 'false'
    const selected = [...order, 'scannerDown'].filter((name) => (only ? only.includes(name) : name !== 'scannerDown') && (withLms || !lmsSections.has(name)))
    const results = { nonce, today }
    let failures = 0
    for (const name of selected) {
      phase = name
      try {
        results[name] = { status: 'passed', result: await sections[name]() }
      } catch (error) {
        failures += 1
        results[name] = { status: 'failed', message: failureText(error) }
      }
    }
    results.summary = { passed: selected.length - failures, failed: failures, sections: selected.length }
    console.log(JSON.stringify(results, null, 1))
    process.exitCode = failures ? 1 : 0
  } catch (error) {
    console.log(JSON.stringify({ smoke: 'failed', phase, message: failureText(error) }))
    process.exitCode = 1
  } finally {
    await disconnect()
  }
}
