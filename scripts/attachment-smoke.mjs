import fs from 'node:fs'

let socket
let password
let origin
let nextId = 1
let phase = 'startup'
const pending = new Map()

const call = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
  const id = nextId++
  pending.set(id, { resolve, reject })
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
})
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

const connectCdp = async () => {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch('http://127.0.0.1:9333/json/version')
      if (response.ok) {
        return response.json()
      }
    } catch {
      await pause(100)
      continue
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
      throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text ?? 'Browser evaluation failed')
    }
    return result.result.value
  }
  const waitFor = async (predicate, message) => {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      if (await predicate()) {
        return
      }
      await pause(150)
    }
    throw new Error(message)
  }
  await call('Page.navigate', { url: `${origin}/` }, sessionId)
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
let kamA
let kamAConcurrent
let kamB
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
  if (!password) {
    throw new Error('Demo password is unavailable')
  }
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
    const request = pending.get(message.id)
    pending.delete(message.id)
    if (message.error) {
      request.reject(new Error(message.error.message))
      return
    }
    request.resolve(message.result)
  })
  kamA = await login('kam-a')
  const check = await kamA.evaluate(`(async () => {
    const request = async (path, init = {}) => {
      const response = await fetch(path, init)
      const type = response.headers.get('content-type') || ''
      return { status: response.status, body: type.includes('application/json') ? await response.json() : null }
    }
    const download = async (path) => {
      const response = await fetch(path)
      return { status: response.status, bytes: [...new Uint8Array(await response.arrayBuffer())] }
    }
    const requireStatus = (response, status, message) => {
      if (response.status !== status) throw new Error(message)
      return response.body
    }
    const me = await request('/api/me')
    if (me.status !== 200 || me.body?.role !== 'USER') throw new Error('KAM profile is unavailable')
    const organizations = requireStatus(await request('/api/organizations?page=0&size=1&sort=updatedAt,desc'), 200, 'Organization is unavailable')
    const organization = organizations.items?.[0]
    const organizationId = organization?.id
    if (!organizationId) throw new Error('Organization is unavailable')
    const csrf = requireStatus(await request('/api/csrf'), 200, 'CSRF token is unavailable')
    const jsonHeaders = (key) => ({ 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key })
    const formHeaders = (key) => ({ [csrf.headerName]: csrf.token, 'Idempotency-Key': key })
    const now = Date.now().toString()
    const title = 'Attachment smoke ' + now
    const interaction = requireStatus(await request('/api/interactions', {
      method: 'POST',
      headers: jsonHeaders('attachment-smoke-interaction-' + now),
      body: JSON.stringify({ organizationId, title, contactIds: [] })
    }), 201, 'Interaction creation failed')
    const pdf = new TextEncoder().encode('%PDF-1.4\\n1 0 obj\\n<< /Type /Catalog >>\\nendobj\\ntrailer\\n<< /Root 1 0 R >>\\n%%EOF\\n')
    const eicar = new TextEncoder().encode('X5O!P%@AP[4\\\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*')
    const compressGzip = async (bytes) => {
      return new Uint8Array(await new Response(
        new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip'))
      ).arrayBuffer())
    }
    const eicarGzip = await compressGzip(eicar)
    const upload = async (key, file) => {
      const form = new FormData()
      form.set('stageId', interaction.currentStageId)
      form.set('file', file)
      return request('/api/interactions/' + encodeURIComponent(interaction.id) + '/attachments', {
        method: 'POST', headers: formHeaders(key), body: form
      })
    }
    const uploadKey = 'attachment-smoke-upload-' + now
    const clean = requireStatus(await upload(uploadKey, new File([pdf], 'clean.pdf', { type: 'application/pdf' })), 201, 'Clean upload failed')
    const replay = requireStatus(await upload(uploadKey, new File([pdf], 'clean.pdf', { type: 'application/pdf' })), 201, 'Upload replay failed')
    if (replay.id !== clean.id) throw new Error('Upload replay created a new attachment')
    let metadata
    for (let attempt = 0; attempt < 100; attempt += 1) {
      metadata = requireStatus(await request('/api/attachments/' + encodeURIComponent(clean.id)), 200, 'Attachment metadata is unavailable')
      if (metadata.status !== 'QUARANTINE') break
      await new Promise((resolve) => setTimeout(resolve, 150))
    }
    if (metadata.status !== 'CLEAN') throw new Error('Attachment was not cleared')
    const downloaded = await download('/api/attachments/' + encodeURIComponent(clean.id) + '/download')
    if (downloaded.status !== 200 || downloaded.bytes.length !== pdf.length || downloaded.bytes.some((value, index) => value !== pdf[index])) {
      throw new Error('Clean download failed')
    }
    const comment = requireStatus(await request('/api/interactions/' + encodeURIComponent(interaction.id) + '/comments', {
      method: 'POST',
      headers: jsonHeaders('attachment-smoke-comment-' + now),
      body: JSON.stringify({ version: interaction.version, stageId: interaction.currentStageId, text: 'Attachment smoke binding', attachmentIds: [clean.id] })
    }), 200, 'Comment binding failed')
    const bound = requireStatus(await request('/api/attachments/' + encodeURIComponent(clean.id)), 200, 'Bound attachment metadata is unavailable')
    if (!comment.event?.id || bound.eventId !== comment.event.id) throw new Error('Attachment event binding failed')
    const rejected = requireStatus(await upload('attachment-smoke-eicar-' + now, new File([eicarGzip], 'eicar.gzip', { type: 'application/gzip' })), 201, 'EICAR upload failed')
    const rejectedMetadata = requireStatus(await request('/api/attachments/' + encodeURIComponent(rejected.id)), 200, 'Rejected attachment metadata is unavailable')
    if (rejectedMetadata.status !== 'REJECTED') throw new Error('EICAR attachment was not rejected')
    const rejectedDownload = await fetch('/api/attachments/' + encodeURIComponent(rejected.id) + '/download')
    if (rejectedDownload.status === 200) throw new Error('Rejected attachment download was allowed')
    const invalid = await upload('attachment-smoke-invalid-' + now, new File(['blocked'], 'blocked.txt', { type: 'text/plain' }))
    if (invalid.status !== 400) throw new Error('Unsupported extension was accepted')
    return {
      interactionId: interaction.id,
      organizationId,
      organizationName: organization.name,
      title,
      stageId: interaction.currentStageId,
      attachmentId: clean.id,
      upload: 201,
      replay: 201,
      status: metadata.status,
      download: downloaded.status,
      comment: 200,
      eventBound: true,
      eicarUpload: 201,
      eicar: rejectedMetadata.status,
      eicarDownload: rejectedDownload.status,
      invalid: invalid.status
    }
  })()`)
  kamB = await login('kam-b')
  const foreign = await kamB.evaluate(`fetch('/api/attachments/${check.attachmentId}').then((response) => response.status)`)
  if (foreign !== 404) throw new Error('Foreign attachment is visible')
  leader = await login('leader')
  const leaderUpload = await leader.evaluate(`(async () => {
    const csrf = await fetch('/api/csrf').then((response) => response.json())
    const form = new FormData()
    form.set('stageId', ${JSON.stringify(check.stageId)})
    form.set('file', new File(['%PDF-1.4\\n%%EOF\\n'], 'leader.pdf', { type: 'application/pdf' }))
    return fetch('/api/interactions/${check.interactionId}/attachments', {
      method: 'POST',
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': 'attachment-smoke-leader-' + Date.now() },
      body: form
    }).then(async (response) => ({ status: response.status, body: await response.json() }))
  })()`)
  if (leaderUpload.status !== 201 || leaderUpload.body?.interactionId !== check.interactionId) {
    throw new Error('Leader upload to the team card failed')
  }
  kamAConcurrent = await login('kam-a')
  const baseline = await kamA.evaluate(`(async () => {
    const [interaction, events] = await Promise.all([
      fetch('/api/interactions/${check.interactionId}'),
      fetch('/api/interactions/${check.interactionId}/events')
    ])
    const interactionBody = await interaction.json()
    const eventsBody = await events.json()
    if (interaction.status !== 200 || events.status !== 200) throw new Error('Stale baseline is unavailable')
    return { version: interactionBody.version, stageId: interactionBody.currentStageId, eventCount: eventsBody.length }
  })()`)
  const concurrentVersion = await kamAConcurrent.evaluate(`fetch('/api/interactions/${check.interactionId}').then(async (response) => ({ status: response.status, body: await response.json() }))`)
  if (concurrentVersion.status !== 200 || concurrentVersion.body?.version !== baseline.version) throw new Error('Concurrent KAM version is unavailable')
  const staleFirst = await kamA.evaluate(`(async () => {
    const csrf = await fetch('/api/csrf').then((response) => response.json())
    const response = await fetch('/api/interactions/${check.interactionId}/comments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': 'attachment-smoke-stale-a-' + Date.now() },
      body: JSON.stringify({ version: ${JSON.stringify(baseline.version)}, stageId: ${JSON.stringify(baseline.stageId)}, text: 'Attachment smoke stale first' })
    })
    return { status: response.status, body: await response.json() }
  })()`)
  if (staleFirst.status !== 200 || !staleFirst.body?.event?.id) throw new Error('First stale command failed')
  const staleSecond = await kamAConcurrent.evaluate(`(async () => {
    const csrf = await fetch('/api/csrf').then((response) => response.json())
    const response = await fetch('/api/interactions/${check.interactionId}/comments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': 'attachment-smoke-stale-b-' + Date.now() },
      body: JSON.stringify({ version: ${JSON.stringify(baseline.version)}, stageId: ${JSON.stringify(baseline.stageId)}, text: 'Attachment smoke stale second' })
    })
    return { status: response.status, body: await response.json() }
  })()`)
  if (staleSecond.status !== 409 || staleSecond.body?.code !== 'VERSION_CONFLICT' || staleSecond.body?.currentVersion !== baseline.version + 1) {
    throw new Error('Version conflict was not reported')
  }
  const finalState = await kamA.evaluate(`(async () => {
    const [interaction, events] = await Promise.all([
      fetch('/api/interactions/${check.interactionId}'),
      fetch('/api/interactions/${check.interactionId}/events')
    ])
    const interactionBody = await interaction.json()
    const eventsBody = await events.json()
    return {
      interactionStatus: interaction.status,
      eventsStatus: events.status,
      version: interactionBody.version,
      eventCount: eventsBody.length,
      matchingEvents: eventsBody.filter((event) => event.id === ${JSON.stringify(staleFirst.body.event.id)}).length
    }
  })()`)
  if (finalState.interactionStatus !== 200 || finalState.eventsStatus !== 200 || finalState.version !== staleSecond.body.currentVersion || finalState.eventCount !== baseline.eventCount + 1 || finalState.matchingEvents !== 1) {
    throw new Error('Stale command created an unexpected event')
  }
  phase = 'ui-navigate'
  await call('Page.navigate', { url: `${origin}/#/organizations` }, kamAConcurrent.sessionId)
  phase = 'ui-organization'
  await kamAConcurrent.waitFor(
    () => kamAConcurrent.evaluate(`Boolean([...document.querySelectorAll('.organization-list-item')].find((button) => button.querySelector('.organization-list-item__name')?.textContent === ${JSON.stringify(check.organizationName)}))`),
    'UI organization did not load'
  )
  await kamAConcurrent.evaluate(`(() => {
    [...document.querySelectorAll('.organization-list-item')]
      .find((button) => button.querySelector('.organization-list-item__name')?.textContent === ${JSON.stringify(check.organizationName)})
      .click()
  })()`)
  phase = 'ui-interaction'
  await kamAConcurrent.waitFor(
    () => kamAConcurrent.evaluate(`Boolean([...document.querySelectorAll('.interaction-list-item')].find((button) => button.querySelector('.interaction-list-item__title')?.textContent === ${JSON.stringify(check.title)}))`),
    'UI interaction did not load'
  )
  await kamAConcurrent.evaluate(`(() => {
    [...document.querySelectorAll('.interaction-list-item')]
      .find((button) => button.querySelector('.interaction-list-item__title')?.textContent === ${JSON.stringify(check.title)})
      .click()
  })()`)
  phase = 'ui-comment-form'
  await kamAConcurrent.waitFor(
    () => kamAConcurrent.evaluate("Boolean([...document.querySelectorAll('.work-card__actions button')].find((button) => button.textContent.trim() === 'Комментарий'))"),
    'UI comment button did not load'
  )
  await kamAConcurrent.evaluate("[...document.querySelectorAll('.work-card__actions button')].find((button) => button.textContent.trim() === 'Комментарий').click()")
  await kamAConcurrent.waitFor(
    () => kamAConcurrent.evaluate("Boolean(document.querySelector('dialog[open] form.interaction-comment-form'))"),
    'UI comment form did not load'
  )
  const uiDraft = 'Attachment smoke UI draft ' + Date.now()
  phase = 'ui-fill-draft'
  await kamAConcurrent.evaluate(`(() => {
    const form = document.querySelector('dialog[open] form.interaction-comment-form')
    const select = form.querySelector('select')
    const textarea = form.querySelector('textarea')
    select.value = ${JSON.stringify(check.stageId)}
    select.dispatchEvent(new Event('change', { bubbles: true }))
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set
    if (!setter) throw new Error('UI textarea setter is unavailable')
    setter.call(textarea, ${JSON.stringify(uiDraft)})
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
  })()`)
  phase = 'ui-prepare-draft'
  await kamAConcurrent.waitFor(
    () => kamAConcurrent.evaluate(`(() => {
      const form = document.querySelector('dialog[open] form.interaction-comment-form')
      const select = form?.querySelector('select')
      const textarea = form?.querySelector('textarea')
      const submit = form?.querySelector('button[type=submit]')
      return Boolean(select?.value === ${JSON.stringify(check.stageId)} && textarea?.value === ${JSON.stringify(uiDraft)} && submit && !submit.disabled)
    })()`),
    'UI comment draft was not prepared'
  )
  const uiPrepared = await kamAConcurrent.evaluate(`(async () => {
    const response = await fetch('/api/interactions/${check.interactionId}')
    const interaction = await response.json()
    const form = document.querySelector('dialog[open] form.interaction-comment-form')
    return { version: response.status === 200 ? interaction.version : null, draft: form?.querySelector('textarea')?.value === ${JSON.stringify(uiDraft)} }
  })()`)
  if (uiPrepared.version !== finalState.version || !uiPrepared.draft) throw new Error('UI comment version is unavailable')
  phase = 'ui-competing-command'
  const uiCompeting = await kamA.evaluate(`(async () => {
    const csrfResponse = await fetch('/api/csrf')
    const csrf = await csrfResponse.json()
    if (csrfResponse.status !== 200) throw new Error('UI conflict CSRF is unavailable')
    const response = await fetch('/api/interactions/${check.interactionId}/comments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': 'attachment-smoke-ui-stale-' + Date.now() },
      body: JSON.stringify({ version: ${JSON.stringify(uiPrepared.version)}, stageId: ${JSON.stringify(check.stageId)}, text: 'Attachment smoke UI competing comment' })
    })
    return { status: response.status, body: await response.json() }
  })()`)
  if (uiCompeting.status !== 200 || !uiCompeting.body?.event?.id) throw new Error('UI competing comment failed')
  phase = 'ui-submit-stale'
  await kamAConcurrent.evaluate(`(() => {
    const form = document.querySelector('dialog[open] form.interaction-comment-form')
    form.querySelector('button[type=submit]').click()
  })()`)
  phase = 'ui-wait-conflict'
  await kamAConcurrent.waitFor(
    () => kamAConcurrent.evaluate(`(() => {
      const form = document.querySelector('dialog[open] form.interaction-comment-form')
      const alert = form?.querySelector('[role=alert]')
      const refresh = [...(form?.querySelectorAll('button') ?? [])].find((button) => button.type === 'button' && button.textContent?.trim() === 'Обновить карточку')
      return Boolean(alert?.textContent?.includes('Черновик сохранён') && refresh && form?.querySelector('textarea')?.value === ${JSON.stringify(uiDraft)})
    })()`),
    'UI version conflict did not appear'
  )
  const uiConflict = await kamAConcurrent.evaluate(`(() => {
    const form = document.querySelector('dialog[open] form.interaction-comment-form')
    const refresh = [...form.querySelectorAll('button')].find((button) => button.type === 'button' && button.textContent?.trim() === 'Обновить карточку')
    return { draft: form.querySelector('textarea')?.value === ${JSON.stringify(uiDraft)}, refresh: Boolean(refresh) }
  })()`)
  if (!uiConflict.draft || !uiConflict.refresh) throw new Error('UI conflict draft was lost')
  phase = 'ui-refresh'
  await kamAConcurrent.evaluate(`(() => {
    const form = document.querySelector('dialog[open] form.interaction-comment-form')
    ;
    [...form.querySelectorAll('button')].find((button) => button.type === 'button' && button.textContent?.trim() === 'Обновить карточку').click()
  })()`)
  phase = 'ui-wait-refresh'
  await kamAConcurrent.waitFor(
    () => kamAConcurrent.evaluate(`(() => {
      const comments = [...document.querySelectorAll('.interaction-events__comment')].map((item) => item.textContent)
      const form = document.querySelector('dialog[open] form.interaction-comment-form')
      return comments.includes('Attachment smoke UI competing comment') && form?.querySelector('textarea')?.value === ${JSON.stringify(uiDraft)}
    })()`),
    'UI refresh did not retain the comment draft'
  )
  const uiRefreshed = await kamAConcurrent.evaluate(`(async () => {
    const response = await fetch('/api/interactions/${check.interactionId}')
    const interaction = await response.json()
    const form = document.querySelector('dialog[open] form.interaction-comment-form')
    return { version: response.status === 200 ? interaction.version : null, draft: form?.querySelector('textarea')?.value === ${JSON.stringify(uiDraft)} }
  })()`)
  if (uiRefreshed.version !== uiPrepared.version + 1 || !uiRefreshed.draft) throw new Error('UI refresh did not update the interaction')
  phase = 'api-skip-rule'
  const skipRule = await kamA.evaluate(`(async () => {
    const request = async (path, init = {}) => {
      const response = await fetch(path, init)
      const type = response.headers.get('content-type') || ''
      return { status: response.status, body: type.includes('application/json') ? await response.json() : null }
    }
    const csrf = await request('/api/csrf')
    if (csrf.status !== 200) throw new Error('Skip rule CSRF is unavailable')
    const headers = (key) => ({ 'Content-Type': 'application/json', [csrf.body.headerName]: csrf.body.token, 'Idempotency-Key': key })
    const now = Date.now().toString()
    const created = await request('/api/interactions', {
      method: 'POST',
      headers: headers('attachment-smoke-skip-create-' + now),
      body: JSON.stringify({ organizationId: ${JSON.stringify(check.organizationId)}, title: 'Attachment smoke skip ' + now, contactIds: [] })
    })
    if (created.status !== 201 || created.body?.currentStageId === undefined) throw new Error('Skip rule interaction creation failed')
    const stageId = (order) => created.body.stages.find((stage) => stage.order === order)?.id
    const one = stageId(1)
    const two = stageId(2)
    const three = stageId(3)
    const five = stageId(5)
    if (!one || !two || !three || !five) throw new Error('Skip rule stages are unavailable')
    const transition = (interaction, toStageId, comment, key) => request('/api/interactions/' + encodeURIComponent(interaction.id) + '/transitions', {
      method: 'POST',
      headers: headers(key),
      body: JSON.stringify({ version: interaction.version, toStageId, comment })
    })
    const oneToTwo = await transition(created.body, one, null, 'attachment-smoke-skip-0-1-' + now)
    if (oneToTwo.status !== 200) throw new Error('Skip rule transition 0-1 failed')
    const twoToThree = await transition(oneToTwo.body, two, null, 'attachment-smoke-skip-1-2-' + now)
    if (twoToThree.status !== 200) throw new Error('Skip rule transition 1-2 failed')
    const threeReady = await transition(twoToThree.body, three, null, 'attachment-smoke-skip-2-3-' + now)
    if (threeReady.status !== 200) throw new Error('Skip rule transition 2-3 failed')
    const withoutComment = await transition(threeReady.body, five, null, 'attachment-smoke-skip-3-5-empty-' + now)
    if (withoutComment.status !== 400 || withoutComment.body?.fieldErrors?.comment === undefined) {
      throw new Error('Skip rule accepted an empty comment')
    }
    const withComment = await transition(threeReady.body, five, 'Attachment smoke skip reason', 'attachment-smoke-skip-3-5-comment-' + now)
    if (withComment.status !== 200) throw new Error('Skip rule comment transition failed')
    return { skipWithoutComment: withoutComment.status, skipWithComment: withComment.status }
  })()`)
  if (skipRule.skipWithoutComment !== 400 || skipRule.skipWithComment !== 200) throw new Error('Skip rule proof is incomplete')
  console.log(JSON.stringify({
    oidc: true,
    upload: check.upload,
    replay: check.replay,
    status: check.status,
    download: check.download,
    comment: check.comment,
    eventBound: check.eventBound,
    eicarUpload: check.eicarUpload,
    eicar: check.eicar,
    eicarDownload: check.eicarDownload,
    invalid: check.invalid,
    foreign,
    leaderUpload,
    staleFirst: staleFirst.status,
    staleSecond: staleSecond.status,
    staleCode: staleSecond.body.code,
    staleCurrentVersion: staleSecond.body.currentVersion,
    staleEventsAdded: finalState.eventCount - baseline.eventCount,
    uiStaleOpened: true,
    uiStaleCompeting: uiCompeting.status,
    uiStaleConflict: uiConflict.refresh,
    uiStaleDraftBefore: uiPrepared.draft,
    uiStaleDraftAfterConflict: uiConflict.draft,
    uiStaleDraftAfterRefresh: uiRefreshed.draft,
    skipWithoutComment: skipRule.skipWithoutComment,
    skipWithComment: skipRule.skipWithComment
  }))
} catch (error) {
  const message = error instanceof Error
    ? `${phase}: ${error.message.replace(/[^\w .:-]/g, '').slice(0, 120)}` || 'failed'
    : 'failed'
  console.log(JSON.stringify({ oidc: false, smoke: 'failed', message }))
  process.exitCode = 1
} finally {
  if (leader) await dispose(leader)
  if (kamB) await dispose(kamB)
  if (kamAConcurrent) await dispose(kamAConcurrent)
  if (kamA) await dispose(kamA)
  socket?.close()
}
