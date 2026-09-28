import fs from 'node:fs'

let socket
let password
let origin
let phase = 'startup'
let nextId = 1
const pending = new Map()

const assert = (condition, message) => {
  if (!condition) throw new Error(message)
}

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

const call = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
  const id = nextId++
  pending.set(id, { resolve, reject })
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
})

const connectCdp = async () => {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch('http://127.0.0.1:9333/json/version')
      if (response.ok) return response.json()
    } catch {
    }
    await pause(100)
  }
  throw new Error('CDP endpoint did not become available')
}

async function login(username) {
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
      throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text || 'Browser evaluation failed')
    }
    return result.result.value
  }
  const waitFor = async (predicate, message) => {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      if (await predicate()) return
      await pause(150)
    }
    throw new Error(message)
  }
  await call('Page.navigate', { url: origin + '/' }, sessionId)
  await waitFor(
    () => evaluate("Boolean([...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')))"),
    'Login button did not appear'
  )
  await evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')).click()")
  await waitFor(
    () => evaluate("Boolean(document.querySelector('#username') && document.querySelector('#password') && document.querySelector('#kc-login'))"),
    'Keycloak form did not appear'
  )
  await evaluate(`(() => {
    const usernameField = document.querySelector('#username')
    const passwordField = document.querySelector('#password')
    usernameField.value = ${JSON.stringify(username)}
    passwordField.value = ${JSON.stringify(password)}
    usernameField.dispatchEvent(new Event('input', { bubbles: true }))
    passwordField.dispatchEvent(new Event('input', { bubbles: true }))
    document.querySelector('#kc-login').click()
  })()`)
  await waitFor(
    () => evaluate(`location.origin === ${JSON.stringify(origin)} && !location.pathname.startsWith('/idp/')`),
    'OIDC callback did not return to CRM'
  )
  return { contextId: context.browserContextId, sessionId, evaluate, waitFor }
}

const dispose = (page) => call('Target.disposeBrowserContext', { browserContextId: page.contextId })

let admin
let kam
let leader

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
  if (!password) throw new Error('Demo password is unavailable')
  phase = 'local-alias'
  const localAlias = origin === 'http://rtk.localhost:8081' ? 'http://localhost:8081' : null
  let localAliasRedirect = false
  if (localAlias) {
    const authorization = await fetch(localAlias + '/api/auth/authorization/keycloak', { redirect: 'manual' })
    const location = authorization.headers.get('location')
    if (authorization.status !== 302 || !location) throw new Error('Local alias authorization is unavailable')
    const authorizationUrl = new URL(location)
    if (authorizationUrl.searchParams.get('redirect_uri') !== localAlias + '/api/auth/callback/keycloak') {
      throw new Error('Local alias callback is incorrect')
    }
    const keycloak = await fetch(authorizationUrl, { redirect: 'manual' })
    if (keycloak.status !== 200) throw new Error('Keycloak rejected the local alias callback')
    localAliasRedirect = true
  }
  const file = fs.readFileSync(new URL('./fixtures/fr07-direction.xlsx.b64', import.meta.url), 'utf8').trim()
  const legacyFile = fs.readFileSync(new URL('./fixtures/fr07-direction-update.xls.b64', import.meta.url), 'utf8').trim()
  const catalogFile = fs.readFileSync(new URL('./fixtures/fr07-catalog.xlsx.b64', import.meta.url), 'utf8').trim()

  const browser = await connectCdp()
  socket = await new Promise((resolve, reject) => {
    const value = new WebSocket(browser.webSocketDebuggerUrl)
    value.addEventListener('open', () => resolve(value), { once: true })
    value.addEventListener('error', () => reject(new Error('CDP connection failed')), { once: true })
  })
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (!message.id || !pending.has(message.id)) return
    const deferred = pending.get(message.id)
    pending.delete(message.id)
    if (message.error) {
      deferred.reject(new Error('CDP request failed'))
      return
    }
    deferred.resolve(message.result)
  })

  phase = 'login-admin'
  admin = await login('admin')
  phase = 'admin-ui'
  await admin.evaluate("location.hash = '#/admin/catalog-import'")
  await admin.waitFor(
    () => admin.evaluate("Boolean(document.querySelector('#catalog-import-title')?.textContent?.includes('Импорт каталогов'))"),
    'Catalog import panel did not appear for ADMIN'
  )
  phase = 'login-kam'
  kam = await login('kam-a')
  phase = 'demo-seed'
  const demoSeed = await kam.evaluate(`(async () => {
    const request = async (path) => {
      const response = await fetch(path)
      const contentType = response.headers.get('content-type') || ''
      return { status: response.status, body: contentType.includes('application/json') ? await response.json() : null }
    }
    const organizations = await request('/api/organizations?page=0&size=25&sort=name,asc')
    if (organizations.status !== 200) throw new Error('Demo organizations are unavailable')
    const university = organizations.body.items?.find((item) => item.name === 'Университет А')
    if (!university) throw new Error('Demo organization is unavailable to KAM')
    if (organizations.body.items.some((item) => item.name === 'Университет C — требует назначения')) {
      throw new Error('Unassigned organization is visible to KAM')
    }
    const contacts = await request('/api/organizations/' + encodeURIComponent(university.id) + '/contacts')
    const demoContactNames = ['Александра Демонстрационная', 'Игорь Демонстрационный']
    if (contacts.status !== 200 || !demoContactNames.every((name) => contacts.body?.some((contact) => contact.name === name))) {
      throw new Error('Demo contacts are incomplete')
    }
    let interaction
    for (let page = 0; !interaction; page += 1) {
      const interactions = await request('/api/interactions?organizationId=' + encodeURIComponent(university.id) + '&page=' + page + '&size=100&sort=createdAt,asc')
      if (interactions.status !== 200) throw new Error('Demo interactions are unavailable')
      interaction = interactions.body.items?.find((item) => item.title === 'Демо: внедрение цифрового университета')
      if (!interaction && (page + 1) * 100 >= interactions.body.total) throw new Error('Demo interaction is unavailable')
    }
    const detail = await request('/api/interactions/' + encodeURIComponent(interaction.id))
    const demoContactIds = contacts.body.filter((contact) => demoContactNames.includes(contact.name)).map((contact) => contact.id)
    if (detail.status !== 200 || !demoContactIds.every((id) => detail.body?.contactIds?.includes(id)) || detail.body?.productAgreements?.length !== 2 || !detail.body?.program || !detail.body?.stages?.length) {
      throw new Error('Demo interaction is incomplete')
    }
    const events = await request('/api/interactions/' + encodeURIComponent(interaction.id) + '/events')
    if (events.status !== 200 || !events.body?.some((event) => event.type === 'CREATED')) {
      throw new Error('Demo interaction history is unavailable')
    }
    return { contacts: demoContactNames.length, agreements: detail.body.productAgreements.length, history: events.body.length }
  })()`)
  phase = 'login-leader'
  leader = await login('leader')
  phase = 'leader-scope'
  const leaderScope = await leader.evaluate(`(async () => {
    const response = await fetch('/api/organizations?page=0&size=25&sort=name,asc')
    const body = await response.json()
    if (response.status !== 200 || !body.items?.some((item) => item.name === 'Университет А') || !body.items?.some((item) => item.name === 'Университет C — требует назначения') || body.items?.some((item) => item.name === 'Университет Б')) {
      throw new Error('Leader demo scope is incomplete')
    }
    return body.items.length
  })()`)
  phase = 'access-control'
  const forbidden = await kam.evaluate(`(async () => {
    const csrf = await fetch('/api/csrf').then((response) => response.json())
    const form = new FormData()
    form.set('file', new File(['blocked'], 'blocked.xlsx'))
    return fetch('/api/imports/inspect', { method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: form }).then((response) => response.status)
  })()`)
  assert(forbidden === 403, 'KAM was allowed to inspect imports')

  phase = 'admin-import'
  const check = await admin.evaluate(`(async () => {
    const requireStatus = (response, expected, message) => {
      if (response.status !== expected) throw new Error(message + ':' + response.status)
      return response.body
    }
    const request = async (path, init = {}) => {
      const response = await fetch(path, init)
      const contentType = response.headers.get('content-type') || ''
      return { status: response.status, body: contentType.includes('application/json') ? await response.json() : null }
    }
    const csrf = requireStatus(await request('/api/csrf'), 200, 'CSRF token is unavailable')
    const bytes = Uint8Array.from(atob(${JSON.stringify(file)}), (character) => character.charCodeAt(0))
    const workbook = () => new File([bytes], 'fr07-direction.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })
    const legacyBytes = Uint8Array.from(atob(${JSON.stringify(legacyFile)}), (character) => character.charCodeAt(0))
    const legacyWorkbook = () => new File([legacyBytes], 'fr07-direction-update.xls', {
      type: 'application/vnd.ms-excel'
    })
    const formHeaders = { [csrf.headerName]: csrf.token }
    const mapping = {
      columns: {
        directionExternalKey: 'directionKey',
        directionName: 'directionName',
        programExternalKey: 'programKey',
        programName: 'programName',
        programDirectionRef: 'directionRef'
      },
      rowTargets: {},
      transferStatuses: {}
    }
    const inspectForm = new FormData()
    inspectForm.set('file', workbook())
    const inspected = requireStatus(await request('/api/imports/inspect', {
      method: 'POST', headers: formHeaders, body: inspectForm
    }), 200, 'Inspect failed')
    if (!inspected.sheets?.some((sheet) => sheet.name === 'Directions' && sheet.headers.includes('directionKey'))) {
      throw new Error('Inspect did not return sheet headers')
    }
    const previewForm = (file = workbook) => {
      const form = new FormData()
      form.set('file', file())
      form.set('profile', 'DIRECTION_PROGRAM')
      form.set('sheet', 'Directions')
      form.set('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }))
      return form
    }
    const preview = requireStatus(await request('/api/imports/preview', {
      method: 'POST', headers: formHeaders, body: previewForm()
    }), 202, 'Preview failed')
    const previewJob = requireStatus(await request('/api/jobs/' + encodeURIComponent(preview.jobId)), 200, 'Preview job unavailable')
    if (previewJob.action !== 'PREVIEW' || previewJob.status !== 'SUCCEEDED') throw new Error('Preview job is not terminal')
    const beforeApply = requireStatus(await request('/api/imports/' + encodeURIComponent(preview.importId)), 200, 'Preview protocol unavailable')
    if (beforeApply.status !== 'PREVIEWED' || beforeApply.rows?.length !== 1 || !['CREATE', 'UPDATE', 'UNCHANGED'].includes(beforeApply.rows[0].status)) {
      throw new Error('Preview protocol is incomplete')
    }
    const applyKey = 'fr07-smoke-apply-' + Date.now()
    const applyRequest = {
      version: beforeApply.version,
      confirmedRowIds: [beforeApply.rows[0].id]
    }
    const apply = requireStatus(await request('/api/imports/' + encodeURIComponent(preview.importId) + '/apply', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...formHeaders, 'Idempotency-Key': applyKey },
      body: JSON.stringify(applyRequest)
    }), 202, 'Apply failed')
    const applyReplay = requireStatus(await request('/api/imports/' + encodeURIComponent(preview.importId) + '/apply', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...formHeaders, 'Idempotency-Key': applyKey },
      body: JSON.stringify(applyRequest)
    }), 202, 'Apply replay failed')
    if (applyReplay.jobId !== apply.jobId) throw new Error('Apply replay created a second job')
    const applyJob = requireStatus(await request('/api/jobs/' + encodeURIComponent(apply.jobId)), 200, 'Apply job unavailable')
    if (applyJob.action !== 'APPLY' || applyJob.status !== 'SUCCEEDED') throw new Error('Apply job is not terminal')
    const applied = requireStatus(await request('/api/imports/' + encodeURIComponent(preview.importId)), 200, 'Applied import unavailable')
    if (applied.status !== 'APPLIED' || !applied.rows?.[0]?.applied) throw new Error('Import was not marked applied')
    const legacyPreview = requireStatus(await request('/api/imports/preview', {
      method: 'POST', headers: formHeaders, body: previewForm(legacyWorkbook)
    }), 202, 'Legacy XLS preview failed')
    const legacyBeforeApply = requireStatus(await request('/api/imports/' + encodeURIComponent(legacyPreview.importId)), 200, 'Legacy XLS protocol unavailable')
    if (legacyBeforeApply.rows?.[0]?.status !== 'UPDATE') throw new Error('Legacy XLS did not produce an update')
    const legacyApply = requireStatus(await request('/api/imports/' + encodeURIComponent(legacyPreview.importId) + '/apply', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...formHeaders, 'Idempotency-Key': 'fr07-smoke-legacy-' + Date.now() },
      body: JSON.stringify({ version: legacyBeforeApply.version, confirmedRowIds: [legacyBeforeApply.rows[0].id] })
    }), 202, 'Legacy XLS apply failed')
    const legacyJob = requireStatus(await request('/api/jobs/' + encodeURIComponent(legacyApply.jobId)), 200, 'Legacy XLS job unavailable')
    if (legacyJob.action !== 'APPLY' || legacyJob.status !== 'SUCCEEDED') throw new Error('Legacy XLS job is not terminal')
    const repeatedPreview = requireStatus(await request('/api/imports/preview', {
      method: 'POST', headers: formHeaders, body: previewForm(legacyWorkbook)
    }), 202, 'Repeated legacy preview failed')
    const repeated = requireStatus(await request('/api/imports/' + encodeURIComponent(repeatedPreview.importId)), 200, 'Repeated protocol unavailable')
    if (repeated.rows?.[0]?.status !== 'UNCHANGED') throw new Error('Stable external keys did not produce an unchanged preview')
    return {
      inspect: 200,
      preview: 202,
      previewJob: previewJob.status,
      previewRow: beforeApply.rows[0].status,
      apply: 202,
      applyReplay: 202,
      applyJob: applyJob.status,
      legacyPreviewRow: legacyBeforeApply.rows[0].status,
      legacyApply: 202,
      legacyJob: legacyJob.status,
      repeatedPreview: 202,
      repeatedRow: repeated.rows[0].status
    }
  })()`)

  phase = 'admin-catalog-import'
  const catalog = await admin.evaluate(`(async () => {
    const requireStatus = (response, expected, message) => {
      if (response.status !== expected) throw new Error(message + ':' + response.status)
      return response.body
    }
    const request = async (path, init = {}) => {
      const response = await fetch(path, init)
      const contentType = response.headers.get('content-type') || ''
      return { status: response.status, body: contentType.includes('application/json') ? await response.json() : null }
    }
    const csrf = requireStatus(await request('/api/csrf'), 200, 'CSRF token is unavailable')
    const formHeaders = { [csrf.headerName]: csrf.token }
    const tzHeaders = ['Название ВУЗа', 'Вендор', 'ПО', 'Номер договора', 'Подписание лицензии',
      'Срок действия лицензии (год)', 'Статус по передаче', 'ФИО Менеджера', 'Ответственные от ВУЗа', 'Комментарий']
    const tzFields = ['organizationName', 'vendorName', 'productName', 'contractNumber', 'licenseSigned',
      'licenseExpiryYear', 'transferStatus', 'managerName', 'contactName', 'comment']
    const template = await fetch('/catalog-import-template.xlsx')
    if (template.status !== 200) throw new Error('Catalog template is unavailable:' + template.status)
    const templateForm = new FormData()
    templateForm.set('file', new File([await template.blob()], 'catalog-import-template.xlsx'))
    const inspectedTemplate = requireStatus(await request('/api/imports/inspect', {
      method: 'POST', headers: formHeaders, body: templateForm
    }), 200, 'Template inspect failed')
    if (!inspectedTemplate.sheets?.some((sheet) => sheet.name === 'Каталог' && tzHeaders.every((header) => sheet.headers.includes(header)))) {
      throw new Error('Template does not contain the ten TZ columns')
    }
    const bytes = Uint8Array.from(atob(${JSON.stringify(catalogFile)}), (character) => character.charCodeAt(0))
    const mapping = {
      columns: Object.fromEntries(tzFields.map((field, index) => [field, tzHeaders[index]])),
      rowTargets: {},
      transferStatuses: {}
    }
    const preview = async () => {
      const form = new FormData()
      form.set('file', new File([bytes], 'fr07-catalog.xlsx'))
      form.set('profile', 'AGREEMENT')
      form.set('sheet', 'Каталог')
      form.set('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }))
      const created = requireStatus(await request('/api/imports/preview', {
        method: 'POST', headers: formHeaders, body: form
      }), 202, 'Catalog preview failed')
      const protocol = requireStatus(await request('/api/imports/' + encodeURIComponent(created.importId)), 200, 'Catalog protocol unavailable')
      return { ...protocol, byRow: Object.fromEntries(protocol.rows.map((row) => [row.rowNumber, row])) }
    }
    const first = await preview()
    const applicable = [first.byRow[2], first.byRow[3]]
    if (applicable.some((row) => !['CREATE', 'UPDATE', 'UNCHANGED'].includes(row?.status))) {
      throw new Error('Catalog rows are not applicable')
    }
    if (first.byRow[3].newValues?.managerName !== 'КАМ А') throw new Error('Manager is not shared by rows of one university')
    if (first.byRow[4]?.status !== 'INVALID' || !first.byRow[4].fieldErrors?.managerName) {
      throw new Error('Unknown manager is not reported in its row')
    }
    const apply = requireStatus(await request('/api/imports/' + encodeURIComponent(first.id) + '/apply', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...formHeaders, 'Idempotency-Key': 'fr07-smoke-catalog-' + Date.now() },
      body: JSON.stringify({ version: first.version, confirmedRowIds: applicable.map((row) => row.id) })
    }), 202, 'Catalog apply failed')
    const applyJob = requireStatus(await request('/api/jobs/' + encodeURIComponent(apply.jobId)), 200, 'Catalog apply job unavailable')
    if (applyJob.action !== 'APPLY' || applyJob.status !== 'SUCCEEDED') throw new Error('Catalog apply job is not terminal')
    const repeated = await preview()
    if (repeated.byRow[2]?.status !== 'UNCHANGED' || repeated.byRow[3]?.status !== 'UNCHANGED') {
      throw new Error('Repeated catalog preview is not unchanged')
    }
    return {
      templateInspect: 200,
      catalogRows: [2, 3, 4].map((rowNumber) => first.byRow[rowNumber].status),
      catalogApplyJob: applyJob.status,
      catalogRepeatedRows: [repeated.byRow[2].status, repeated.byRow[3].status]
    }
  })()`)
  phase = 'kam-catalog-scope'
  const catalogScope = await kam.evaluate(`(async () => {
    const response = await fetch('/api/organizations?page=0&size=100&sort=name,asc')
    const body = await response.json()
    if (response.status !== 200 || !body.items?.some((item) => item.name === 'Смоук-университет FR-07')) {
      throw new Error('Imported university is not in the KAM scope')
    }
    return true
  })()`)

  console.log(JSON.stringify({
    oidc: true, localAliasRedirect, adminPanel: true, demoSeed, leaderScope, forbidden, ...check, ...catalog, catalogScope
  }))
} catch (error) {
  const message = error instanceof Error
    ? error.message.replace(/[^\w .:-]/g, '').slice(0, 160) || 'failed'
    : 'failed'
  console.log(JSON.stringify({ oidc: false, smoke: 'failed', phase, message }))
  process.exitCode = 1
} finally {
  for (const page of [leader, kam, admin]) {
    if (!page) continue
    try {
      await dispose(page)
    } catch {
    }
  }
  socket?.close()
}
