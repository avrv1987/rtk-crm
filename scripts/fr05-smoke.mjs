import fs from 'node:fs'
import { randomUUID } from 'node:crypto'

let socket
let password
let origin
let phase = 'startup'
let nextId = 1
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
      throw new Error('Browser evaluation failed')
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
  await evaluate(
    "(() => { const usernameField = document.querySelector('#username'); const passwordField = document.querySelector('#password'); "
      + 'usernameField.value = ' + JSON.stringify(username) + '; '
      + 'passwordField.value = ' + JSON.stringify(password) + '; '
      + "usernameField.dispatchEvent(new Event('input', { bubbles: true })); "
      + "passwordField.dispatchEvent(new Event('input', { bubbles: true })); "
      + "document.querySelector('#kc-login').click() })()"
  )
  await waitFor(
    () => evaluate('location.origin === ' + JSON.stringify(origin) + " && !location.pathname.startsWith('/idp/')"),
    'OIDC callback did not return to CRM'
  )
  return { contextId: context.browserContextId, evaluate, waitFor }
}

const dispose = (page) => call('Target.disposeBrowserContext', { browserContextId: page.contextId })

const request = (page, path, init = {}) => page.evaluate(
  "(async () => { const response = await fetch("
    + JSON.stringify(path)
    + ', '
    + JSON.stringify(init)
    + "); const contentType = response.headers.get('content-type') || ''; "
    + "return { status: response.status, body: contentType.includes('application/json') ? await response.json() : null } })()"
)

const requireStatus = (response, status, message) => {
  if (response.status !== status) {
    throw new Error(message)
  }
  return response.body
}

const assert = (condition, message) => {
  if (!condition) {
    throw new Error(message)
  }
}

const csrfFor = async (page) => {
  const value = requireStatus(await request(page, '/api/csrf'), 200, 'CSRF token is unavailable')
  assert(value?.headerName && value?.token, 'CSRF token is incomplete')
  return value
}

const jsonRequest = (page, csrf, method, path, body, key) => request(page, path, {
  method,
  headers: {
    'Content-Type': 'application/json',
    [csrf.headerName]: csrf.token,
    'Idempotency-Key': key
  },
  body: JSON.stringify(body)
})

const postJson = (page, csrf, path, body, key) => jsonRequest(page, csrf, 'POST', path, body, key)
const patchJson = (page, csrf, path, body, key) => jsonRequest(page, csrf, 'PATCH', path, body, key)

const interactionPath = (id, suffix = '') => '/api/interactions/' + encodeURIComponent(id) + suffix
const templatePath = (id = '') => '/api/admin/workflow-templates' + (id ? '/' + encodeURIComponent(id) : '')
const stageAt = (interaction, order) => interaction.stages.find((stage) => stage.order === order)
const stageNamed = (interaction, name) => interaction.stages.find((stage) => stage.name === name)
const transition = (page, csrf, interaction, toStageId, comment, key) => postJson(
  page,
  csrf,
  interactionPath(interaction.id, '/transitions'),
  { version: interaction.version, toStageId, comment },
  key
)
const stageEdits = (page, csrf, interactionId, body, key) => postJson(
  page,
  csrf,
  interactionPath(interactionId, '/stage-edits'),
  body,
  key
)

const tzStageNames = [
  'Поиск контакта',
  'Уточнение актуальности',
  'Встреча',
  'Обмен документами',
  'Корректировка документов',
  'Подписание',
  'Передача материалов, лицензии и документации',
  'Сопровождение внедрения',
  'Обучение преподавателей',
  'Актуализация программы',
  'Занятия',
  'Обновление документации и материалов',
  'Повышение квалификации'
]

const interactionPayload = (organizationId, title, templateId) => ({
  organizationId,
  title,
  contactIds: [],
  ...(templateId ? { templateId } : {})
})

function assertSnapshot(template, interaction, message) {
  const templateStages = [...template.stages].sort((left, right) => left.order - right.order)
  const interactionStages = [...interaction.stages].sort((left, right) => left.order - right.order)
  assert(templateStages.length === interactionStages.length, message + ' stage count changed')
  assert(interactionStages.every((stage) => !templateStages.some((templateStage) => templateStage.id === stage.id)), message + ' stage UUID was reused')
  assert(
    templateStages.every((templateStage, index) => {
      const interactionStage = interactionStages[index]
      return templateStage.name === interactionStage.name
        && templateStage.order === interactionStage.order
        && templateStage.optional === interactionStage.optional
    }),
    message + ' stage data differs'
  )
  const templateOrderById = new Map(templateStages.map((stage) => [stage.id, stage.order]))
  const interactionOrderById = new Map(interactionStages.map((stage) => [stage.id, stage.order]))
  const edgeSignature = (transitions, orders) => transitions
    .map((edge) => orders.get(edge.fromStageId) + ':' + orders.get(edge.toStageId) + ':' + edge.commentRequired)
    .sort()
  assert(
    JSON.stringify(edgeSignature(template.transitions, templateOrderById))
      === JSON.stringify(edgeSignature(interaction.transitions, interactionOrderById)),
    message + ' graph differs'
  )
  assert(interaction.currentStageId === stageAt(interaction, 0)?.id, message + ' does not start at order zero')
}

function assertLocalGraph(interaction, message) {
  const ids = new Set(interaction.stages.map((stage) => stage.id))
  const orders = new Set(interaction.stages.map((stage) => stage.order))
  assert(ids.has(interaction.currentStageId), message + ' current stage is absent')
  assert(interaction.transitions.every((edge) => ids.has(edge.fromStageId) && ids.has(edge.toStageId)), message + ' has a dangling edge')
  assert(
    orders.size === interaction.stages.length && [...Array(interaction.stages.length).keys()].every((order) => orders.has(order)),
    message + ' stage order is not contiguous'
  )
}

let kamA
let kamAConcurrent
let kamB
let leader

try {
  const environmentFile = new URL('../.env.local', import.meta.url)
  if (!fs.existsSync(environmentFile)) {
    throw new Error('Local environment file is unavailable')
  }
  const environment = Object.fromEntries(
    fs.readFileSync(environmentFile, 'utf8')
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
    const deferred = pending.get(message.id)
    pending.delete(message.id)
    if (message.error) {
      deferred.reject(new Error('CDP request failed'))
      return
    }
    deferred.resolve(message.result)
  })

  phase = 'login'
  kamA = await login('kam-a')
  kamAConcurrent = await login('kam-a')
  kamB = await login('kam-b')
  leader = await login('leader')
  const kamACsrf = await csrfFor(kamA)
  const kamAConcurrentCsrf = await csrfFor(kamAConcurrent)
  const kamBCsrf = await csrfFor(kamB)
  const leaderCsrf = await csrfFor(leader)

  phase = 'profiles'
  const kamProfile = requireStatus(await request(kamA, '/api/me'), 200, 'KAM profile is unavailable')
  const leaderProfile = requireStatus(await request(leader, '/api/me'), 200, 'Leader profile is unavailable')
  assert(kamProfile?.role === 'USER', 'KAM role is unavailable')
  assert(leaderProfile?.role === 'LEADER', 'Leader role is unavailable')

  phase = 'organization'
  const organizations = requireStatus(
    await request(kamA, '/api/organizations?page=0&size=1&sort=updatedAt,desc'),
    200,
    'KAM organization is unavailable'
  )
  const organizationId = organizations.items?.[0]?.id
  assert(organizationId, 'KAM organization is unavailable')

  const nonce = Date.now().toString() + '-' + randomUUID().slice(0, 8)
  const templateName = 'FR05 smoke template ' + nonce
  const startName = 'FR05 smoke start ' + nonce
  const reviewName = 'FR05 smoke review ' + nonce
  const futureName = 'FR05 smoke future ' + nonce
  const updatedStartName = 'FR05 smoke updated start ' + nonce
  const renamedFutureName = 'FR05 smoke renamed future ' + nonce
  const insertedName = 'FR05 smoke inserted ' + nonce
  const workflow = {
    name: templateName,
    stages: [
      { name: startName, order: 0, optional: false },
      { name: reviewName, order: 1, optional: false },
      { name: futureName, order: 2, optional: true }
    ],
    transitions: [
      { fromOrder: 0, toOrder: 1, commentRequired: false },
      { fromOrder: 1, toOrder: 2, commentRequired: true }
    ]
  }

  phase = 'invalid-template-graph'
  const invalidTemplate = await postJson(leader, leaderCsrf, templatePath(), {
    name: templateName + ' invalid',
    stages: [
      { name: 'FR05 smoke invalid start ' + nonce, order: 0, optional: false },
      { name: 'FR05 smoke invalid unreachable ' + nonce, order: 1, optional: false }
    ],
    transitions: []
  }, 'fr05-invalid-template-' + nonce)
  requireStatus(invalidTemplate, 400, 'Invalid workflow graph was accepted')

  phase = 'create-template'
  const templateKey = 'fr05-create-template-' + nonce
  const template = requireStatus(
    await postJson(leader, leaderCsrf, templatePath(), workflow, templateKey),
    201,
    'Workflow template creation failed'
  )
  const templateReplay = requireStatus(
    await postJson(leader, leaderCsrf, templatePath(), workflow, templateKey),
    201,
    'Workflow template replay failed'
  )
  assert(template?.id && templateReplay?.id === template.id, 'Workflow template replay created a new template')
  assert(template.stages?.length === 3 && template.transitions?.length === 2, 'Workflow template payload is incomplete')
  const availableTemplates = requireStatus(
    await request(kamA, '/api/workflow-templates?page=0&size=100&sort=name,asc'),
    200,
    'Available workflow templates are unavailable'
  )
  assert(availableTemplates.items?.some((item) => item.id === template.id), 'Team workflow template is unavailable to KAM')

  phase = 'clone-first-snapshot'
  const firstInteractionKey = 'fr05-create-first-' + nonce
  const firstInteractionPayload = interactionPayload(organizationId, 'FR05 smoke first ' + nonce, template.id)
  const firstInteraction = requireStatus(
    await postJson(kamA, kamACsrf, '/api/interactions', firstInteractionPayload, firstInteractionKey),
    201,
    'First snapshot interaction creation failed'
  )
  const firstInteractionReplay = requireStatus(
    await postJson(kamA, kamACsrf, '/api/interactions', firstInteractionPayload, firstInteractionKey),
    201,
    'First snapshot interaction replay failed'
  )
  assert(firstInteractionReplay?.id === firstInteraction.id, 'Interaction replay created a second snapshot')
  assertSnapshot(template, firstInteraction, 'First snapshot')

  phase = 'update-template'
  const updatedWorkflow = {
    name: templateName + ' updated',
    version: template.version,
    stages: workflow.stages.map((stage) => ({
      ...stage,
      name: stage.order === 0 ? updatedStartName : stage.name
    })),
    transitions: workflow.transitions
  }
  const updatedTemplate = requireStatus(
    await patchJson(leader, leaderCsrf, templatePath(template.id), updatedWorkflow, 'fr05-update-template-' + nonce),
    200,
    'Workflow template update failed'
  )
  assert(updatedTemplate.version === template.version + 1, 'Workflow template version did not change')

  phase = 'snapshot-isolation'
  const firstReloaded = requireStatus(
    await request(kamA, interactionPath(firstInteraction.id)),
    200,
    'First snapshot interaction reload failed'
  )
  assertSnapshot(template, firstReloaded, 'Existing snapshot after template update')
  assert(stageAt(firstReloaded, 0)?.name === startName, 'Existing snapshot changed with its template')
  const secondInteraction = requireStatus(
    await postJson(
      kamA,
      kamACsrf,
      '/api/interactions',
      interactionPayload(organizationId, 'FR05 smoke second ' + nonce, template.id),
      'fr05-create-second-' + nonce
    ),
    201,
    'Second snapshot interaction creation failed'
  )
  assertSnapshot(updatedTemplate, secondInteraction, 'New snapshot after template update')
  assert(stageAt(secondInteraction, 0)?.name === updatedStartName, 'New snapshot did not use the updated template')
  assert(
    !firstReloaded.stages.some((firstStage) => secondInteraction.stages.some((secondStage) => secondStage.id === firstStage.id)),
    'Interaction snapshots share stage UUIDs'
  )

  phase = 'current-stage-delete'
  const initialStage = stageAt(firstReloaded, 0)
  const reviewStage = stageAt(firstReloaded, 1)
  const futureStage = stageAt(firstReloaded, 2)
  assert(initialStage && reviewStage && futureStage, 'First snapshot stages are unavailable')
  const currentDelete = await stageEdits(kamA, kamACsrf, firstReloaded.id, {
    version: firstReloaded.version,
    operations: [{ type: 'DELETE', id: initialStage.id }]
  }, 'fr05-current-delete-' + nonce)
  requireStatus(currentDelete, 400, 'Current stage deletion was accepted')

  phase = 'used-stage-delete'
  const atReview = requireStatus(
    await transition(kamA, kamACsrf, firstReloaded, reviewStage.id, null, 'fr05-to-review-' + nonce),
    200,
    'Initial workflow transition failed'
  )
  const usedDelete = await stageEdits(kamA, kamACsrf, firstReloaded.id, {
    version: atReview.version,
    operations: [{ type: 'DELETE', id: initialStage.id }]
  }, 'fr05-used-delete-' + nonce)
  requireStatus(usedDelete, 400, 'Used stage deletion was accepted')

  phase = 'stage-edit-conflict'
  const concurrentBaseline = requireStatus(
    await request(kamAConcurrent, interactionPath(firstReloaded.id)),
    200,
    'Concurrent snapshot reload failed'
  )
  assert(concurrentBaseline.version === atReview.version, 'Concurrent snapshot version is unavailable')
  const renameRequest = {
    version: atReview.version,
    operations: [{ type: 'RENAME', id: futureStage.id, name: renamedFutureName }]
  }
  const renameKey = 'fr05-rename-' + nonce
  const renamed = requireStatus(
    await stageEdits(kamA, kamACsrf, firstReloaded.id, renameRequest, renameKey),
    200,
    'KAM stage rename failed'
  )
  const staleRename = await stageEdits(kamAConcurrent, kamAConcurrentCsrf, firstReloaded.id, {
    version: concurrentBaseline.version,
    operations: [{ type: 'RENAME', id: futureStage.id, name: 'FR05 smoke stale ' + nonce }]
  }, 'fr05-stale-rename-' + nonce)
  assert(
    staleRename.status === 409
      && staleRename.body?.code === 'VERSION_CONFLICT'
      && staleRename.body?.currentVersion === renamed.version,
    'Stage edit version conflict was not reported'
  )
  const renameReplay = requireStatus(
    await stageEdits(kamA, kamACsrf, firstReloaded.id, renameRequest, renameKey),
    200,
    'Stage edit replay failed'
  )
  assert(renameReplay.version === renamed.version, 'Stage edit replay changed the version')
  const idempotencyConflict = await stageEdits(kamA, kamACsrf, firstReloaded.id, {
    version: atReview.version,
    operations: [{ type: 'RENAME', id: futureStage.id, name: renamedFutureName + ' changed' }]
  }, renameKey)
  assert(
    idempotencyConflict.status === 409 && idempotencyConflict.body?.code === 'IDEMPOTENCY_CONFLICT',
    'Stage edit idempotency conflict was not reported'
  )

  phase = 'stage-edit-operations'
  const renamedStage = stageNamed(renamed, renamedFutureName)
  const currentReviewStage = stageAt(renamed, 1)
  assert(renamedStage && currentReviewStage, 'Renamed stages are unavailable')
  const added = requireStatus(
    await stageEdits(kamA, kamACsrf, firstReloaded.id, {
      version: renamed.version,
      operations: [{ type: 'ADD_AFTER', afterId: currentReviewStage.id, name: insertedName, optional: false }]
    }, 'fr05-add-after-' + nonce),
    200,
    'KAM stage insertion failed'
  )
  const insertedStage = stageNamed(added, insertedName)
  const addedFutureStage = stageNamed(added, renamedFutureName)
  const addedCurrentStage = stageAt(added, 1)
  assert(insertedStage && addedFutureStage && addedCurrentStage, 'Inserted stages are unavailable')
  const moved = requireStatus(
    await stageEdits(kamA, kamACsrf, firstReloaded.id, {
      version: added.version,
      operations: [{ type: 'MOVE_AFTER', id: addedFutureStage.id, afterId: addedCurrentStage.id }]
    }, 'fr05-move-after-' + nonce),
    200,
    'KAM stage move failed'
  )
  assert(stageAt(moved, 2)?.id === addedFutureStage.id, 'Moved stage has the wrong position')
  const deleted = requireStatus(
    await stageEdits(kamA, kamACsrf, firstReloaded.id, {
      version: moved.version,
      operations: [{ type: 'DELETE', id: insertedStage.id }]
    }, 'fr05-delete-future-' + nonce),
    200,
    'KAM future stage deletion failed'
  )
  assert(!stageNamed(deleted, insertedName), 'Deleted future stage remains in the snapshot')
  assertLocalGraph(deleted, 'Edited snapshot')
  const editedFutureStage = stageNamed(deleted, renamedFutureName)
  assert(editedFutureStage, 'Edited future stage is unavailable')

  phase = 'scope-and-graph'
  const rejectedGraphTransition = await transition(
    kamA,
    kamACsrf,
    deleted,
    stageAt(deleted, 0).id,
    null,
    'fr05-rejected-local-graph-' + nonce
  )
  requireStatus(rejectedGraphTransition, 400, 'Transition outside the local graph was accepted')
  const leaderEdit = await stageEdits(leader, leaderCsrf, deleted.id, {
    version: deleted.version,
    operations: [{ type: 'RENAME', id: editedFutureStage.id, name: 'FR05 smoke leader ' + nonce }]
  }, 'fr05-leader-edit-' + nonce)
  requireStatus(leaderEdit, 200, 'Leader local stage edit of the team card failed')
  const foreignEdit = await stageEdits(kamB, kamBCsrf, deleted.id, {
    version: deleted.version,
    operations: [{ type: 'RENAME', id: editedFutureStage.id, name: 'FR05 smoke foreign ' + nonce }]
  }, 'fr05-foreign-edit-' + nonce)
  requireStatus(foreignEdit, 404, 'Foreign interaction stage edit was visible')

  phase = 'base-route'
  const base = requireStatus(
    await postJson(
      kamA,
      kamACsrf,
      '/api/interactions',
      interactionPayload(organizationId, 'FR05 smoke base route ' + nonce),
      'fr05-create-base-' + nonce
    ),
    201,
    'Base workflow interaction creation failed'
  )
  assert(base.stages?.length === 13, 'Base workflow does not contain thirteen stages')
  assert(tzStageNames.every((name, order) => stageAt(base, order)?.name === name), 'Base workflow does not follow the TZ actions 1-13')
  const rejectedBaseRoute = await transition(
    kamA,
    kamACsrf,
    base,
    stageAt(base, 3).id,
    null,
    'fr05-rejected-base-route-' + nonce
  )
  requireStatus(rejectedBaseRoute, 400, 'Base workflow accepted a non-edge transition')

  let route = base
  for (const order of [1, 2, 3]) {
    const target = stageAt(route, order)
    assert(route.allowedTransitions?.some((option) => option.stageId === target.id), 'Base route edge is unavailable')
    route = requireStatus(
      await transition(kamA, kamACsrf, route, target.id, null, 'fr05-base-' + order + '-' + nonce),
      200,
      'Base workflow transition failed'
    )
  }
  const correctionStage = stageAt(route, 4)
  const signingStage = stageAt(route, 5)
  assert(
    route.allowedTransitions?.some((option) => option.stageId === correctionStage.id && !option.commentRequired)
      && route.allowedTransitions?.some((option) => option.stageId === signingStage.id && option.commentRequired),
    'Optional correction skip edge is unavailable'
  )
  const skipWithoutComment = await transition(
    kamA,
    kamACsrf,
    route,
    signingStage.id,
    null,
    'fr05-skip-empty-' + nonce
  )
  assert(skipWithoutComment.status === 400 && skipWithoutComment.body?.fieldErrors?.comment !== undefined, 'Correction skip accepted an empty comment')
  route = requireStatus(
    await transition(
      kamA,
      kamACsrf,
      route,
      signingStage.id,
      'FR05 smoke correction skip reason',
      'fr05-skip-comment-' + nonce
    ),
    200,
    'Correction skip with a comment failed'
  )
  route = requireStatus(
    await transition(kamA, kamACsrf, route, correctionStage.id, null, 'fr05-correction-return-' + nonce),
    200,
    'Return to the optional correction failed'
  )
  route = requireStatus(
    await transition(kamA, kamACsrf, route, signingStage.id, null, 'fr05-correction-signing-' + nonce),
    200,
    'Signing after the correction failed'
  )
  for (const order of [6, 7, 8, 9, 10, 11, 12]) {
    const target = stageAt(route, order)
    assert(route.allowedTransitions?.some((option) => option.stageId === target.id), 'Base route continuation is unavailable')
    route = requireStatus(
      await transition(kamA, kamACsrf, route, target.id, null, 'fr05-base-' + order + '-' + nonce),
      200,
      'Base workflow continuation failed'
    )
  }
  assert(route.currentStageId === stageAt(route, 12).id, 'Base route did not reach the final stage')

  phase = 'tz-control'
  const controlAction = 'FR05 smoke control ' + nonce
  const controlDue = new Date(Date.now() + 3 * 86400000).toISOString()
  route = requireStatus(
    await patchJson(kamA, kamACsrf, interactionPath(route.id), { version: route.version, nextAction: controlAction, nextActionAt: controlDue }, 'fr05-control-' + nonce),
    200,
    'Control plan update failed'
  )
  assert(route.nextAction === controlAction && Date.parse(route.nextActionAt) === Date.parse(controlDue), 'Control plan was not saved')
  const baseHistory = requireStatus(await request(kamA, interactionPath(route.id, '/events')), 200, 'Base route history is unavailable')
  const moves = baseHistory.filter((event) => event.type === 'TRANSITIONED')
  const tracedStages = new Set([baseHistory[0]?.stageNameSnapshot, ...moves.map((event) => event.toStageNameSnapshot)])
  assert(baseHistory[0]?.type === 'CREATED' && baseHistory[0].stageNameSnapshot === tzStageNames[0], 'History does not start with the creation at the first action')
  assert(moves.length === 13, 'History does not contain every base route transition')
  assert(tzStageNames.every((name) => tracedStages.has(name)), 'History does not trace every TZ action')
  assert(
    moves.some((event) => event.fromStageNameSnapshot === tzStageNames[3] && event.toStageNameSnapshot === tzStageNames[5] && event.comment === 'FR05 smoke correction skip reason'),
    'Correction skip reason is absent from history'
  )
  assert(
    baseHistory.every((event) => event.actorProfileId === kamProfile.id && event.actorDisplayName && !Number.isNaN(Date.parse(event.occurredAt))),
    'History events lack the author or the time'
  )
  const planEvent = baseHistory.find((event) => event.type === 'PLAN_UPDATED')
  assert(
    planEvent?.stageNameSnapshot === tzStageNames[12] && planEvent.nextStep?.nextAction === controlAction && Date.parse(planEvent.nextStep?.nextActionAt) === Date.parse(controlDue),
    'Control plan event is absent from history'
  )
  const moscowToday = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow' }).format(new Date())
  const controlView = requireStatus(
    await postJson(kamA, kamACsrf, '/api/reports/preview?page=0&size=200', {
      kind: 'PORTFOLIO',
      from: moscowToday,
      to: moscowToday,
      periodBasis: 'ACTIVITY',
      filters: { organizationIds: [organizationId], stages: [tzStageNames[12]] },
      columns: ['INTERACTION', 'STAGE', 'NEXT_ACTION', 'NEXT_ACTION_AT', 'LAST_EVENT_AT']
    }),
    200,
    'Control preview by stage failed'
  )
  const controlRow = controlView.items.find((row) => row.INTERACTION === 'FR05 smoke base route ' + nonce)
  assert(controlView.items.every((row) => row.STAGE === tzStageNames[12]), 'Stage filter returned another stage')
  assert(controlRow?.NEXT_ACTION === controlAction && controlRow.NEXT_ACTION_AT && controlRow.LAST_EVENT_AT, 'Control preview lacks the next step or the last event')

  console.log(JSON.stringify({
    oidc: true,
    invalidTemplateGraph: invalidTemplate.status,
    templateCreate: 201,
    templateReplay: 201,
    templateUpdate: 200,
    snapshotIsolation: true,
    currentDelete: currentDelete.status,
    usedDelete: usedDelete.status,
    stageRename: 200,
    stageReplay: 200,
    idempotencyConflict: idempotencyConflict.status,
    stageAdd: 200,
    stageMove: 200,
    stageDelete: 200,
    versionConflict: staleRename.status,
    foreignStageEdit: foreignEdit.status,
    leaderStageEdit: leaderEdit.status,
    rejectedGraphTransition: rejectedGraphTransition.status,
    baseStages: base.stages.length,
    rejectedBaseRoute: rejectedBaseRoute.status,
    skipWithoutComment: skipWithoutComment.status,
    skipWithComment: 200,
    correctionReturn: 200,
    baseFinalOrder: stageAt(route, 12).order,
    tzStagesMapped: tzStageNames.length,
    historyTransitions: moves.length,
    historyTracedStages: tracedStages.size,
    controlPlan: 200,
    controlPreviewRows: controlView.total
  }))
} catch (error) {
  const message = error instanceof Error
    ? error.message.replace(/[^\w .:-]/g, '').slice(0, 120) || 'failed'
    : 'failed'
  console.log(JSON.stringify({ oidc: false, smoke: 'failed', phase, message }))
  process.exitCode = 1
} finally {
  for (const page of [leader, kamB, kamAConcurrent, kamA]) {
    if (!page) {
      continue
    }
    try {
      await dispose(page)
    } catch {
    }
  }
  socket?.close()
}
