import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  api, assert, call, connect, demoOrganization, demoPassword, disconnect,
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
  const chip = `[...document.querySelectorAll('details.report-chip')].find((item) => item.querySelector('.report-chip__label')?.textContent === ${JSON.stringify(legend + ':')})`
  const option = `[...(${chip}?.querySelectorAll('label') ?? [])].find((item) => item.textContent.trim() === ${JSON.stringify(label)})?.querySelector('input')`
  await page.waitFor(() => page.evaluate(`Boolean(${chip})`), 'Filter is absent: ' + legend)
  await page.evaluate(`${chip}.open = true`)
  await page.waitFor(() => page.evaluate(`Boolean(${option})`), 'Filter option is absent: ' + label)
  await page.evaluate(`${option}.checked || ${option}.click()`)
  await page.evaluate(`${chip}.open = false`)
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
  await shoot(kam, 'history', cardReady, '.interaction-events')
  await kam.evaluate("document.getElementById('work-card-tab-route').click()")
  await shoot(kam, 'interaction-path', "Boolean(document.querySelector('.interaction-stages .interaction-path'))", ['#interaction-route-title', '.interaction-stages'])
  await kam.evaluate("document.getElementById('work-card-tab-history').click()")
  await clickButton(kam, 'Следующий шаг', "document.querySelector('.work-card__actions')")
  await shoot(kam, 'next-step', "Boolean(document.querySelector('dialog[open] .interaction-plan-form'))", 'dialog[open]')
  await clickButton(kam, 'Закрыть', "document.querySelector('dialog[open]')")
  await clickButton(kam, 'Перейти к следующему этапу', "document.querySelector('.work-card__actions')")
  await fillFields(kam, 'dialog[open] form.interaction-transition-form select', [1])
  await fillFields(kam, 'dialog[open] form.interaction-transition-form textarea', [
    'Проректор подтвердил интерес к программе, договорились о встрече.',
    'Провести встречу с проректором и показать программу'
  ])
  await shoot(kam, 'transition', "Boolean(document.querySelector('dialog[open] form.interaction-transition-form')?.innerText.includes('Выбран этап'))", 'dialog[open]')
  await clickButton(kam, 'Закрыть', "document.querySelector('dialog[open]')")

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
  const attachmentsReady = "(document.querySelector('#work-card-tab-documents[aria-selected=false]')?.click(), true) && document.querySelectorAll('.interaction-attachments li').length > 1"
  await navigate(kam, '#/organizations/' + universityA.id + '/' + attachmentCard.id, attachmentsReady)
  await shoot(kam, 'attachments', attachmentsReady, '.interaction-attachments')

  phase = 'reports'
  const reportsReady = "Boolean(document.querySelector('.reports__form .report-chip')) && document.body.innerText.includes('Мои выгрузки')"
  await navigate(kam, '#/reports/portfolio', reportsReady)
  for (const stage of baseStages) {
    await checkOption(kam, 'Этап', stage)
  }
  await shoot(kam, 'reports-filters', reportsReady)
  await clickButton(kam, 'Показать')
  const previewReady = reportsReady + " && Boolean(document.querySelector('.reports__table-scroll'))"
  await shoot(kam, 'reports-preview', previewReady, '.reports__preview')
  await kam.evaluate("document.querySelector('#report-result-tab-chart').click()")
  await shoot(kam, 'statistics', reportsReady + " && Boolean(document.querySelector('.reports__statistics svg'))", '.reports__statistics')
  await clickButton(kam, 'Выгрузить XLSX')
  await shoot(kam, 'report-files', reportsReady + " && Boolean(document.querySelector('.report-jobs li'))", '.report-jobs')

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
  const assignmentReady = "(document.querySelector('#organization-card-tab-history[aria-selected=false]')?.click(), true) && Boolean(document.querySelector('.organization-assignment')) && document.body.innerText.includes('История назначений')"
  await navigate(leader, '#/organizations/' + universityC.id, assignmentReady)
  await shoot(leader, 'assignment', assignmentReady, ['#organization-detail-title', '.organization-assignment'])
  const templatesReady = "Boolean(document.querySelector('.workflow-templates__item'))"
  await navigate(leader, '#/admin/workflow-templates', templatesReady)
  await clickButton(leader, 'Создать на основе базового')
  await shoot(leader, 'templates-editor', "Boolean(document.querySelector('#workflow-template-stages-title'))", '.workflow-templates form')

  phase = 'management'
  const management = await session(process.env.DEMO_MANAGEMENT_USER || 'management')
  await navigate(management, '#/work', "Boolean(document.getElementById('teams-summary-title'))")
  await shoot(management, 'teams-summary', "Boolean(document.getElementById('teams-summary-title')) && Boolean(document.querySelector('.work-control__table'))", 'section[aria-labelledby=teams-summary-title]')

  phase = 'admin'
  const admin = await session('admin')
  const adminNavReady = "Boolean(document.querySelector('.admin-section-nav'))"
  const adminProfilesReady = adminNavReady + " && Boolean(document.querySelector('#admin-profiles-title'))"
  await navigate(admin, '#/admin', adminProfilesReady)
  await shoot(admin, 'admin', adminProfilesReady)
  await shoot(admin, 'admin-profiles', adminProfilesReady, 'section[aria-labelledby=admin-profiles-title]')
  const adminImportReady = adminNavReady + " && Boolean(document.querySelector('.catalog-import'))"
  await navigate(admin, '#/admin/catalog-import', adminImportReady)
  await setFile(admin, '.catalog-import input[type=file]', importTemplate)
  await clickButton(admin, 'Проверить файл', "document.querySelector('.catalog-import')")
  await shoot(admin, 'catalog-import', adminImportReady + " && document.querySelector('.catalog-import').innerText.includes('Сопоставление столбцов')", '.catalog-import')
  const adminSourcesReady = adminNavReady + " && Boolean(document.querySelector('.data-sources'))"
  await navigate(admin, '#/admin/sources', adminSourcesReady)
  await shoot(admin, 'sources', adminSourcesReady + " && document.querySelector('.data-sources').innerText.includes('LMS Moodle')", '.data-sources')

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
