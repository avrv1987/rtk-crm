import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  adminProfile, api, assert, call, connect, demoOrganization, demoPassword, disconnect,
  failureText, listAll, openPage, originUrl, pause, session
} from './wave4-smoke.mjs'

const outputDirectory = fileURLToPath(new URL('../frontend/public/help/img/', import.meta.url))
const importTemplate = fileURLToPath(new URL('../frontend/public/catalog-import-template.xlsx', import.meta.url))
const desktop = { width: 1440, height: 900, mobile: false }
const phone = { width: 390, height: 844, mobile: true }
const loginButtonVisible = "Boolean([...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')))"
const keycloakFormVisible = "Boolean(document.querySelector('#username') && document.querySelector('#password') && document.querySelector('#kc-login'))"
const baseStages = ['Поиск контакта', 'Уточнение актуальности', 'Встреча', 'Обмен документами', 'Подписание', 'Занятия', 'Повышение квалификации']

const saved = []
let phase = 'startup'

const setViewport = (page, viewport) => call('Emulation.setDeviceMetricsOverride', {
  width: viewport.width,
  height: viewport.height,
  deviceScaleFactor: 1,
  mobile: viewport.mobile
}, page.sessionId)

const goTo = async (page, url, ready) => {
  await call('Page.navigate', { url }, page.sessionId)
  await page.waitFor(() => page.evaluate(ready), 'Page did not load: ' + url)
}

const navigate = async (page, hash, ready) => {
  await page.evaluate(`location.hash = ${JSON.stringify(hash)}`)
  await page.waitFor(() => page.evaluate(ready), 'Screen is not ready: ' + hash)
}

const workReady = (text) => `document.querySelectorAll('.work-item').length > 0 && [...document.querySelectorAll('.work-item__title')].every((item) => item.textContent.includes(${JSON.stringify(text)}))`

async function shoot(page, name, ready, clip) {
  await page.waitFor(() => page.evaluate(ready), name + ' is not ready')
  await page.evaluate('window.scrollTo(0, 0); document.activeElement instanceof HTMLElement && document.activeElement.blur()')
  await pause(400)
  const overflow = await page.evaluate('document.documentElement.scrollWidth - document.documentElement.clientWidth')
  assert(overflow <= 0, name + ' scrolls horizontally')
  const exposed = await page.evaluate(`(() => {
    const text = document.body.innerText + [...document.querySelectorAll('input, textarea')].map((field) => field.value).join(' ')
    return text.includes(${JSON.stringify(demoPassword())})
  })()`)
  assert(!exposed, name + ': the password is visible on the screen')
  const region = clip === undefined ? null : await page.evaluate(`(() => {
    const rects = ${JSON.stringify([clip].flat())}.map((selector) => document.querySelector(selector)?.getBoundingClientRect()).filter(Boolean)
    if (rects.length === 0) return null
    const top = Math.min(...rects.map((rect) => rect.top))
    const x = Math.max(0, Math.min(...rects.map((rect) => rect.left)) - 16)
    const right = Math.min(document.documentElement.clientWidth, Math.max(...rects.map((rect) => rect.right)) + 16)
    const bottom = Math.max(...rects.map((rect) => rect.bottom))
    return { x, y: Math.max(0, top - 16), width: right - x, height: Math.min(bottom - top + 32, 1000), scale: 1 }
  })()`)
  assert(clip === undefined || region !== null, name + ': ' + clip + ' is absent')
  const shot = await call('Page.captureScreenshot', {
    format: 'png',
    ...(region === null ? {} : { clip: region, captureBeyondViewport: true })
  }, page.sessionId)
  fs.writeFileSync(path.join(outputDirectory, name + '.png'), Buffer.from(shot.data, 'base64'))
  saved.push(name)
}

const clickButton = async (page, label, scope = 'document') => {
  const clicked = await page.evaluate(`(() => {
    const button = [...${scope}.querySelectorAll('button')].find((item) => item.textContent.trim() === ${JSON.stringify(label)} && !item.disabled)
    button?.click()
    return Boolean(button)
  })()`)
  assert(clicked, 'Button is unavailable: ' + label)
}

const setFile = async (page, selector, file) => {
  const { root } = await call('DOM.getDocument', {}, page.sessionId)
  const { nodeId } = await call('DOM.querySelector', { nodeId: root.nodeId, selector }, page.sessionId)
  assert(nodeId, 'File input is absent: ' + selector)
  await call('DOM.setFileInputFiles', { nodeId, files: [file] }, page.sessionId)
}

const fillFields = async (page, scope, values) => {
  const filled = await page.evaluate(`(() => {
    const fields = [...document.querySelectorAll(${JSON.stringify(scope)})]
    const values = ${JSON.stringify(values)}
    if (fields.length < values.length) return false
    values.forEach((value, index) => {
      const field = fields[index]
      const next = value === 1 && field instanceof HTMLSelectElement ? field.options[1].value : value
      Object.getOwnPropertyDescriptor(Object.getPrototypeOf(field), 'value').set.call(field, next)
      field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }))
    })
    return true
  })()`)
  assert(filled, 'Fields are unavailable: ' + scope)
}

const checkOption = async (page, legend, label) => {
  const option = `[...([...document.querySelectorAll('fieldset.report-filter')].find((item) => item.querySelector('legend')?.textContent === ${JSON.stringify(legend)})?.querySelectorAll('label') ?? [])].find((item) => item.textContent.trim() === ${JSON.stringify(label)})?.querySelector('input')`
  await page.waitFor(() => page.evaluate(`Boolean(${option})`), 'Filter option is absent: ' + label)
  await page.evaluate(`${option}.checked || ${option}.click()`)
  await pause(100)
}

try {
  await connect()
  fs.mkdirSync(outputDirectory, { recursive: true })

  phase = 'login-screens'
  const anonymous = await openPage(originUrl() + '/')
  await shoot(anonymous, 'login', loginButtonVisible, '.landing-header')
  await anonymous.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')).click()")
  await anonymous.waitFor(() => anonymous.evaluate(keycloakFormVisible), 'Keycloak form did not appear')
  await fillFields(anonymous, '#username', ['kam-a'])
  await shoot(anonymous, 'login-keycloak', keycloakFormVisible, ['#kc-header', '.pf-v5-c-login__main'])

  phase = 'kam'
  const kam = await session('kam-a')
  await navigate(kam, '#/work', "Boolean(document.querySelector('.work-item'))")
  await fillFields(kam, '.work-filters input[type=search]', ['Демо'])
  await shoot(kam, 'work-kam', workReady('Демо'))
  const universityA = await demoOrganization(kam, 'Университет А')
  await navigate(kam, '#/organizations/' + universityA.id, "Boolean(document.querySelector('.interactions-list'))")
  await shoot(kam, 'organizations', "Boolean(document.querySelector('.interactions-list'))")
  const interactions = await listAll(kam, '/api/interactions?organizationId=' + universityA.id, 'Interactions are unavailable')
  const demo = interactions.find((item) => item.title === 'Демо: внедрение цифрового университета')
  assert(demo, 'Demo interaction is unavailable')
  const cardReady = "Boolean(document.querySelector('.interaction-path')) && [...document.querySelectorAll('.interaction-events li')].some((item) => item.textContent.includes('Данные LMS'))"
  await navigate(kam, '#/organizations/' + universityA.id + '/' + demo.id, cardReady)
  await shoot(kam, 'interaction-card', cardReady, '.interaction-detail')
  await shoot(kam, 'interaction-path', cardReady, ['#interaction-path-title', '.interaction-path'])
  await shoot(kam, 'next-step', cardReady, '.interaction-plan-form')
  await shoot(kam, 'history', cardReady, '.interaction-events')
  await fillFields(kam, '.interaction-commands form:nth-of-type(2) select', [1])
  await fillFields(kam, '.interaction-commands form:nth-of-type(2) textarea', [
    'Проректор подтвердил интерес к программе, договорились о встрече.',
    'Провести встречу с проректором и показать программу'
  ])
  await shoot(kam, 'transition', cardReady + " && document.querySelector('.interaction-commands').innerText.includes('Выбран этап')", '.interaction-commands')

  phase = 'attachments'
  let attachmentCard = null
  for (const item of interactions.filter((candidate) => candidate.title.startsWith('Attachment smoke'))) {
    const detail = await api(kam, 'GET', '/api/interactions/' + item.id)
    const statuses = new Set(detail.body?.attachments.map((attachment) => attachment.status))
    if (statuses.has('CLEAN') && statuses.has('REJECTED')) {
      attachmentCard = item
      break
    }
  }
  assert(attachmentCard, 'No interaction with clean and rejected attachments (run scripts/attachment-smoke.mjs first)')
  const attachmentsReady = "document.querySelectorAll('.interaction-attachments li').length > 1"
  await navigate(kam, '#/organizations/' + universityA.id + '/' + attachmentCard.id, attachmentsReady)
  await shoot(kam, 'attachments', attachmentsReady, '.interaction-attachments')

  phase = 'reports'
  const reportsReady = "Boolean(document.querySelector('.reports__form')) && document.body.innerText.includes('Заказанные файлы')"
  await navigate(kam, '#/reports', reportsReady)
  for (const stage of baseStages) {
    await checkOption(kam, 'Этап', stage)
  }
  await shoot(kam, 'reports-filters', reportsReady)
  await clickButton(kam, 'Показать')
  const previewReady = reportsReady + " && Boolean(document.querySelector('.reports__table-scroll'))"
  await shoot(kam, 'reports-preview', previewReady, '.reports__preview')
  await clickButton(kam, 'Построить диаграмму')
  await shoot(kam, 'statistics', previewReady + " && Boolean(document.querySelector('.reports__statistics svg'))", '.reports__statistics')
  await clickButton(kam, 'Сформировать файл')
  await shoot(kam, 'report-files', reportsReady + " && Boolean(document.querySelector('.reports__jobs li'))", '.reports__files')

  phase = 'kam-mobile'
  await setViewport(kam, phone)
  await goTo(kam, originUrl() + '/#/work', "Boolean(document.querySelector('.work-item'))")
  await fillFields(kam, '.work-filters input[type=search]', ['Демо'])
  await shoot(kam, 'mobile-work', workReady('Демо'))
  await navigate(kam, '#/organizations/' + universityA.id + '/' + demo.id, cardReady)
  await kam.evaluate("document.querySelector('.interaction-detail').scrollIntoView()")
  await shoot(kam, 'mobile-card', cardReady, '.interaction-detail')
  await setViewport(kam, desktop)

  phase = 'kam-enrolment'
  const enrol = await session('enrol')
  await navigate(enrol, '#/enrolment', "Boolean(document.getElementById('enrolment-upload-title'))")
  await shoot(enrol, 'enrolment-upload', "Boolean(document.getElementById('enrolment-upload-title'))", '.enrolment')

  phase = 'leader'
  const leader = await session('leader')
  await navigate(leader, '#/work', "Boolean(document.querySelector('.work-item'))")
  await fillFields(leader, '.work-filters input[type=search]', ['Заявка'])
  await shoot(leader, 'work-leader', workReady('Заявка'))
  const leaderOrganizations = await listAll(leader, '/api/organizations?sort=name,asc', 'Organizations are unavailable')
  const universityC = leaderOrganizations.find((item) => item.name.startsWith('Университет C'))
  assert(universityC, 'Leader does not see University C')
  const assignmentReady = "Boolean(document.querySelector('.organization-assignment')) && document.body.innerText.includes('История назначений')"
  await navigate(leader, '#/organizations/' + universityC.id, assignmentReady)
  await shoot(leader, 'assignment', assignmentReady, ['#organization-detail-title', '.organization-assignment'])
  const templatesReady = "Boolean(document.querySelector('.workflow-templates__item'))"
  await navigate(leader, '#/organizations', templatesReady)
  await clickButton(leader, 'Создать на основе базового')
  await shoot(leader, 'templates-editor', "Boolean(document.querySelector('#workflow-template-stages-title'))", '.workflow-templates form')

  phase = 'management'
  const managementLogin = process.env.DEMO_MANAGEMENT_USER || 'unprofiled-2'
  const spare = await session(managementLogin)
  await api(spare, 'GET', '/api/me')
  const spareBefore = await adminProfile(managementLogin)
  const admin = await session('admin')
  await api(admin, 'PATCH', '/api/admin/crm-profiles/' + spareBefore.id, { body: { version: spareBefore.version, role: 'MANAGEMENT', active: true } })
  try {
    await call('Page.navigate', { url: originUrl() + '/#/work' }, spare.sessionId)
    await call('Page.reload', {}, spare.sessionId)
    await spare.waitFor(() => spare.evaluate("Boolean(document.getElementById('teams-summary-title'))"), 'Page did not load: teams-summary')
    await shoot(spare, 'teams-summary', "Boolean(document.getElementById('teams-summary-title')) && Boolean(document.querySelector('.work-control__table'))", 'section[aria-labelledby=teams-summary-title]')
  } finally {
    const spareAfter = await adminProfile(managementLogin)
    await api(admin, 'PATCH', '/api/admin/crm-profiles/' + spareAfter.id, { body: { version: spareAfter.version, active: false } })
  }

  phase = 'admin'
  const adminReady = "Boolean(document.querySelector('#admin-profiles-title')) && Boolean(document.querySelector('.data-sources'))"
  await navigate(admin, '#/admin', adminReady)
  await shoot(admin, 'admin', adminReady)
  await shoot(admin, 'admin-profiles', adminReady, 'section[aria-labelledby=admin-profiles-title]')
  await setFile(admin, '.catalog-import input[type=file]', importTemplate)
  await clickButton(admin, 'Проверить файл', "document.querySelector('.catalog-import')")
  await shoot(admin, 'catalog-import', adminReady + " && document.querySelector('.catalog-import').innerText.includes('Сопоставление столбцов')", '.catalog-import')
  await shoot(admin, 'sources', adminReady + " && document.querySelector('.data-sources').innerText.includes('LMS Moodle')", '.data-sources')

  phase = 'swagger'
  await goTo(kam, originUrl() + '/swagger-ui/index.html', "document.querySelectorAll('.opblock').length > 10")
  await shoot(kam, 'swagger', "document.querySelectorAll('.opblock').length > 10")

  console.log(JSON.stringify({ screenshots: saved.length, names: saved }))
} catch (error) {
  console.log(JSON.stringify({ screenshots: 'failed', phase, message: failureText(error), saved }))
  process.exitCode = 1
} finally {
  await disconnect()
}
