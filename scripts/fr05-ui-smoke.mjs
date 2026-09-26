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

const assert = (condition, message) => {
  if (!condition) {
    throw new Error(message)
  }
}

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
  throw new Error('CDP endpoint is unavailable')
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
    'Login button is unavailable'
  )
  await evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')).click()")
  await waitFor(
    () => evaluate("Boolean(document.querySelector('#username') && document.querySelector('#password') && document.querySelector('#kc-login'))"),
    'Login form is unavailable'
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

const request = (page, path, init = {}) => page.evaluate(`(async () => {
  const response = await fetch(${JSON.stringify(path)}, ${JSON.stringify(init)})
  const contentType = response.headers.get('content-type') || ''
  return { status: response.status, body: contentType.includes('application/json') ? await response.json() : null }
})()`)

const requireStatus = (response, status, message) => {
  if (response.status !== status) {
    throw new Error(message)
  }
  return response.body
}

const csrfFor = async (page) => {
  const csrf = requireStatus(await request(page, '/api/csrf'), 200, 'CSRF token is unavailable')
  assert(csrf?.headerName && csrf?.token, 'CSRF token is incomplete')
  return csrf
}

const postJson = (page, csrf, path, body, key) => request(page, path, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    [csrf.headerName]: csrf.token,
    'Idempotency-Key': key
  },
  body: JSON.stringify(body)
})

const navigateHome = (page) => call('Page.navigate', { url: origin + '/#/organizations' }, page.sessionId)

const openOrganization = async (page, organizationName) => {
  await page.waitFor(
    () => page.evaluate("Boolean(document.querySelector('ul[aria-label=\"Список организаций\"]'))"),
    'Organization list is unavailable'
  )
  await page.waitFor(
    () => page.evaluate(`(() => {
      const list = document.querySelector('ul[aria-label="Список организаций"]')
      return Boolean([...list.querySelectorAll('a')].find((link) => link.textContent.includes(${JSON.stringify(organizationName)})))
    })()`),
    'Organization is unavailable in the UI'
  )
  await page.evaluate(`(() => {
    const list = document.querySelector('ul[aria-label="Список организаций"]')
    const link = [...list.querySelectorAll('a')].find((item) => item.textContent.includes(${JSON.stringify(organizationName)}))
    link.click()
  })()`)
}

const openInteraction = async (page, title) => {
  await page.waitFor(
    () => page.evaluate(`Boolean([...document.querySelectorAll('button')].find((button) => button.textContent.includes(${JSON.stringify(title)})))`),
    'Interaction is unavailable in the UI'
  )
  await page.evaluate(`(() => {
    const button = [...document.querySelectorAll('button')].find((item) => item.textContent.includes(${JSON.stringify(title)}))
    button.click()
  })()`)
}

let leader
let kam
let kamConcurrent

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
      deferred.reject(new Error('CDP request failed'))
      return
    }
    deferred.resolve(message.result)
  })

  phase = 'login'
  leader = await login('leader')
  kam = await login('kam-a')
  kamConcurrent = await login('kam-a')
  const leaderCsrf = await csrfFor(leader)
  const kamCsrf = await csrfFor(kam)
  const kamConcurrentCsrf = await csrfFor(kamConcurrent)

  phase = 'leader-panel'
  await navigateHome(leader)
  await leader.waitFor(
    () => leader.evaluate("Boolean([...document.querySelectorAll('h2')].find((heading) => heading.textContent.trim() === 'Шаблоны этапов'))"),
    'Leader workflow template panel is unavailable'
  )
  const leaderPanel = await leader.evaluate(`(() => ({
    templates: Boolean([...document.querySelectorAll('h2')].find((heading) => heading.textContent.trim() === 'Шаблоны этапов')),
    stageEditor: Boolean([...document.querySelectorAll('form h6')].find((heading) => heading.textContent.trim() === 'Изменить локальный граф этапов'))
  }))()`)
  assert(leaderPanel.templates && !leaderPanel.stageEditor, 'Leader UI role boundary is incorrect')

  phase = 'setup'
  const organizationPage = requireStatus(
    await request(kam, '/api/organizations?page=0&size=1&sort=updatedAt,desc'),
    200,
    'KAM organization is unavailable'
  )
  const organization = organizationPage.items?.[0]
  assert(organization?.id && organization?.name, 'KAM organization is unavailable')
  const nonce = Date.now().toString() + '-' + randomUUID().slice(0, 8)
  const templateName = '000 UI smoke template ' + nonce
  const template = requireStatus(await postJson(leader, leaderCsrf, '/api/admin/workflow-templates', {
    name: templateName,
    stages: [
      { name: 'UI smoke start ' + nonce, order: 0, optional: false },
      { name: 'UI smoke future ' + nonce, order: 1, optional: false }
    ],
    transitions: [{ fromOrder: 0, toOrder: 1, commentRequired: false }]
  }, 'fr05-ui-template-' + nonce), 201, 'Workflow template setup failed')
  assert(template?.id, 'Workflow template setup is incomplete')

  phase = 'user-template-select'
  await navigateHome(kam)
  await openOrganization(kam, organization.name)
  await kam.waitFor(
    () => kam.evaluate(`(() => {
      const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h5')?.textContent?.trim() === 'Новое взаимодействие')
      const label = [...(form?.querySelectorAll('label') ?? [])].find((item) => item.textContent.includes('Шаблон процесса'))
      return Boolean(label?.querySelector('select option[value=${JSON.stringify(template.id)}]'))
    })()`),
    'Workflow template option is unavailable to KAM'
  )
  await kam.evaluate(`(() => {
    const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h5')?.textContent?.trim() === 'Новое взаимодействие')
    const label = [...form.querySelectorAll('label')].find((item) => item.textContent.includes('Шаблон процесса'))
    const select = label.querySelector('select')
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set
    if (!setter) throw new Error('Select setter is unavailable')
    setter.call(select, ${JSON.stringify(template.id)})
    select.dispatchEvent(new Event('change', { bubbles: true }))
  })()`)
  await kam.waitFor(
    () => kam.evaluate(`(() => {
      const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h5')?.textContent?.trim() === 'Новое взаимодействие')
      const label = [...(form?.querySelectorAll('label') ?? [])].find((item) => item.textContent.includes('Шаблон процесса'))
      return label?.querySelector('select')?.value === ${JSON.stringify(template.id)}
    })()`),
    'Workflow template choice did not persist in the UI'
  )

  const interactionTitle = 'UI smoke interaction ' + nonce
  const interaction = requireStatus(await postJson(kam, kamCsrf, '/api/interactions', {
    organizationId: organization.id,
    title: interactionTitle,
    contactIds: [],
    templateId: template.id
  }, 'fr05-ui-interaction-' + nonce), 201, 'Interaction setup failed')
  const futureStage = interaction.stages?.find((stage) => stage.id !== interaction.currentStageId)
  assert(interaction?.id && interaction?.currentStageId && futureStage?.id, 'Interaction setup is incomplete')

  phase = 'leader-card'
  await navigateHome(leader)
  await openOrganization(leader, organization.name)
  await openInteraction(leader, interactionTitle)
  await leader.waitFor(
    () => leader.evaluate("Boolean(document.querySelector('ol[aria-labelledby=\"interaction-path-title\"] li'))"),
    'Leader interaction stages are unavailable'
  )
  await leader.waitFor(
    () => leader.evaluate("Boolean([...document.querySelectorAll('form h6')].find((heading) => heading.textContent.trim() === 'Изменить локальный граф этапов'))"),
    'Leader cannot see the local stage editor of the team card'
  )

  phase = 'user-stage-editor'
  await navigateHome(kam)
  await openOrganization(kam, organization.name)
  await openInteraction(kam, interactionTitle)
  await kam.waitFor(
    () => kam.evaluate("Boolean([...document.querySelectorAll('form h6')].find((heading) => heading.textContent.trim() === 'Изменить локальный граф этапов'))"),
    'KAM local stage editor is unavailable'
  )

  phase = 'current-delete'
  await kam.evaluate(`(() => {
    const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
    const select = form.querySelector('select')
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set
    if (!setter) throw new Error('Select setter is unavailable')
    setter.call(select, 'DELETE')
    select.dispatchEvent(new Event('change', { bubbles: true }))
  })()`)
  await kam.waitFor(
    () => kam.evaluate(`(() => {
      const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
      return form?.querySelectorAll('select').length === 2
    })()`),
    'Stage delete controls are unavailable'
  )
  await kam.evaluate(`(() => {
    const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
    const select = form.querySelectorAll('select')[1]
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set
    if (!setter) throw new Error('Select setter is unavailable')
    setter.call(select, ${JSON.stringify(interaction.currentStageId)})
    select.dispatchEvent(new Event('change', { bubbles: true }))
  })()`)
  await kam.waitFor(
    () => kam.evaluate(`(() => {
      const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
      return !form?.querySelector('button[type="submit"]')?.disabled
    })()`),
    'Current stage delete is not ready'
  )
  await kam.evaluate(`(() => {
    const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
    form.querySelector('button[type="submit"]').click()
  })()`)
  await kam.waitFor(
    () => kam.evaluate(`(() => {
      const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
      const alert = form?.querySelector('[role="alert"]')
      const reason = alert?.querySelector('.structured-api-error li')?.textContent ?? ''
      const support = alert?.querySelector('.support-details')?.textContent ?? ''
      return reason.startsWith('Этап:') && reason.includes('Нельзя удалить текущий этап') && support.includes('Код ошибки: VALIDATION_ERROR')
    })()`),
    'Current stage deletion reason is unavailable in the UI'
  )

  phase = 'stage-conflict'
  const draftName = 'UI smoke local draft ' + nonce
  const serverName = 'UI smoke concurrent change ' + nonce
  await kam.evaluate(`(() => {
    const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
    const select = form.querySelector('select')
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set
    if (!setter) throw new Error('Select setter is unavailable')
    setter.call(select, 'RENAME')
    select.dispatchEvent(new Event('change', { bubbles: true }))
  })()`)
  await kam.waitFor(
    () => kam.evaluate(`(() => {
      const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
      return Boolean(form?.querySelectorAll('select').length === 2 && form?.querySelector('input'))
    })()`),
    'Stage rename controls are unavailable'
  )
  await kam.evaluate(`(() => {
    const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
    const select = form.querySelectorAll('select')[1]
    const input = form.querySelector('input')
    const selectSetter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set
    const inputSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    if (!selectSetter || !inputSetter) throw new Error('Editor setters are unavailable')
    selectSetter.call(select, ${JSON.stringify(futureStage.id)})
    select.dispatchEvent(new Event('change', { bubbles: true }))
    inputSetter.call(input, ${JSON.stringify(draftName)})
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })()`)
  await kam.waitFor(
    () => kam.evaluate(`(() => {
      const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
      return form?.querySelector('input')?.value === ${JSON.stringify(draftName)} && !form?.querySelector('button[type="submit"]')?.disabled
    })()`),
    'Local stage draft is unavailable'
  )
  const concurrent = requireStatus(await postJson(kamConcurrent, kamConcurrentCsrf, `/api/interactions/${encodeURIComponent(interaction.id)}/stage-edits`, {
    version: interaction.version,
    operations: [{ type: 'RENAME', id: futureStage.id, name: serverName }]
  }, 'fr05-ui-concurrent-' + nonce), 200, 'Concurrent stage edit failed')
  assert(concurrent.version === interaction.version + 1, 'Concurrent stage edit did not change the version')
  await kam.evaluate(`(() => {
    const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
    form.querySelector('button[type="submit"]').click()
  })()`)
  await kam.waitFor(
    () => kam.evaluate(`(() => {
      const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
      const alert = form?.querySelector('[role="alert"]')
      const refresh = [...(form?.querySelectorAll('button') ?? [])].find((button) => button.type === 'button' && button.textContent?.trim() === 'Обновить карточку')
      return Boolean(alert?.textContent?.includes('Черновик сохранён') && refresh && form?.querySelector('input')?.value === ${JSON.stringify(draftName)})
    })()`),
    'Stage conflict draft or refresh action is unavailable'
  )
  await kam.evaluate(`(() => {
    const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
    ;[...form.querySelectorAll('button')].find((button) => button.type === 'button' && button.textContent?.trim() === 'Обновить карточку').click()
  })()`)
  await kam.waitFor(
    () => kam.evaluate(`(() => {
      const form = [...document.querySelectorAll('form')].find((item) => item.querySelector('h6')?.textContent?.trim() === 'Изменить локальный граф этапов')
      const selected = form?.querySelectorAll('select')[1]
      return form?.querySelector('input')?.value === ${JSON.stringify(draftName)} && selected?.querySelector('option:checked')?.textContent?.includes(${JSON.stringify(serverName)})
    })()`),
    'Refresh did not retain the stage draft'
  )

  console.log(JSON.stringify({
    oidc: true,
    leaderTemplatesPanel: true,
    leaderStageEditor: true,
    userTemplateOption: true,
    userTemplateSelected: true,
    userStageEditor: true,
    currentDeleteStructuredError: true,
    stageConflict: 409,
    stageDraftPreserved: true,
    refreshVisible: true,
    refreshRetainedDraft: true
  }))
} catch {
  console.log(JSON.stringify({ oidc: false, smoke: 'failed', phase }))
  process.exitCode = 1
} finally {
  for (const page of [kamConcurrent, kam, leader]) {
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
