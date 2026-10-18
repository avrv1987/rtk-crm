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

const workReady = (text) => `document.querySelectorAll('.work-row').length > 0 && [...document.querySelectorAll('.work-item__title')].every((item) => item.textContent.includes(${JSON.stringify(text)}))`

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

// Фильтры отчёта лежат в панели «Фильтры»: открываем её, раскрываем нужную группу и отмечаем значение.
const page_waitClosed = (page) => page.waitFor(() => page.evaluate("!document.querySelector('.filter-panel[open]')"), 'Filter panel did not close')

const filterPanelOpen = "Boolean(document.querySelector('.filter-panel[open]'))"

const checkOption = async (page, legend, label) => {
  if (!(await page.evaluate(filterPanelOpen))) {
    await page.evaluate("document.querySelector('.filter-button').click()")
    await page.waitFor(() => page.evaluate(filterPanelOpen), 'Filter panel did not open')
  }
  const group = `[...document.querySelectorAll('.report-filter-group')].find((item) => item.querySelector('.report-filter-group__label')?.textContent === ${JSON.stringify(legend)})`
  const option = `[...(${group}?.querySelectorAll('label') ?? [])].find((item) => item.textContent.trim() === ${JSON.stringify(label)})?.querySelector('input')`
  await page.waitFor(() => page.evaluate(`Boolean(${group})`), 'Filter is absent: ' + legend)
  if (!(await page.evaluate(`Boolean(${option})`))) {
    await page.evaluate(`${group}.querySelector('.report-filter-group__toggle').click()`)
  }
  await page.waitFor(() => page.evaluate(`Boolean(${option})`), 'Filter option is absent: ' + label)
  await page.evaluate(`${option}.checked || ${option}.click()`)
  await pause(100)
}

const listedOptions = async (page, legend) => {
  if (!(await page.evaluate(filterPanelOpen))) {
    await page.evaluate("document.querySelector('.filter-button').click()")
    await page.waitFor(() => page.evaluate(filterPanelOpen), 'Filter panel did not open')
  }
  const group = `[...document.querySelectorAll('.report-filter-group')].find((item) => item.querySelector('.report-filter-group__label')?.textContent === ${JSON.stringify(legend)})`
  await page.waitFor(() => page.evaluate(`Boolean(${group})`), 'Filter is absent: ' + legend)
  if (!(await page.evaluate(`Boolean(${group}.querySelector('.report-filter-group__options'))`))) {
    await page.evaluate(`${group}.querySelector('.report-filter-group__toggle').click()`)
  }
  await page.waitFor(() => page.evaluate(`${group}.querySelectorAll('.report-filter-group__options label').length > 0`), 'Filter has no options: ' + legend)
  return page.evaluate(`[...${group}.querySelectorAll('.report-filter-group__options label')].map((item) => item.textContent.trim())`)
}

// Выгрузка файла — пункт меню «Выгрузить ▾» в шапке отчёта.
const orderFromMenu = async (page, label) => {
  await page.evaluate("document.querySelector('.report-header__actions .report-export .action-menu__toggle').click()")
  const clicked = await page.evaluate(`(() => {
    const item = [...document.querySelectorAll('.report-header__actions [role=menuitem]')].find((entry) => entry.textContent.trim().startsWith(${JSON.stringify(label)}))
    item?.click()
    return Boolean(item)
  })()`)
  assert(clicked, 'Export item is unavailable: ' + label)
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
  await navigate(kam, '#/work', "Boolean(document.querySelector('.work-row'))")
  await fillFields(kam, '.work-filters input[type=search]', ['Демо'])
  await shoot(kam, 'work-kam', workReady('Демо'))
  const universityA = await demoOrganization(kam, 'Университет А')
  const organizationsReady = "document.querySelectorAll('.organizations-list li').length > 1"
  await navigate(kam, '#/organizations', organizationsReady)
  await shoot(kam, 'organizations', organizationsReady)
  const interactions = await listAll(kam, '/api/interactions?organizationId=' + universityA.id, 'Interactions are unavailable')
  const demo = interactions.find((item) => item.title === 'Демо: внедрение цифрового университета')
  assert(demo, 'Demo interaction is unavailable')
  const cardReady = "Boolean(document.querySelector('.work-facts')) && document.querySelectorAll('.interaction-events li').length > 0"
  await navigate(kam, '#/organizations/' + universityA.id + '/' + demo.id, cardReady)
  await shoot(kam, 'interaction-card', cardReady, '.interaction-detail')
  await shoot(kam, 'history', cardReady, '.interaction-events')
  await kam.evaluate("document.querySelector('.work-route__toggle').click()")
  await shoot(kam, 'interaction-path', "!document.querySelector('.work-route__panel').hidden", '.work-route')
  await kam.evaluate("document.querySelector('.work-route__toggle').click()")
  await clickButton(kam, 'Изменить', "document.querySelector('.work-step__actions')")
  await shoot(kam, 'next-step', "Boolean(document.querySelector('dialog[open] .interaction-plan-form'))", 'dialog[open]')
  await clickButton(kam, 'Закрыть', "document.querySelector('dialog[open]')")
  await kam.evaluate("(document.querySelector('.work-card__primary[data-action=transition]') ?? [...document.querySelectorAll('.work-card__more [role=menuitem]')].find((item) => item.textContent.trim() === 'Перейти на следующий этап…')).click()")
  await fillFields(kam, 'dialog[open] form.interaction-transition-form select', [1])
  await fillFields(kam, 'dialog[open] form.interaction-transition-form textarea', [
    'Проректор подтвердил интерес к программе, договорились о встрече.',
    'Провести встречу с проректором и показать программу'
  ])
  await shoot(kam, 'transition', "Boolean(document.querySelector('dialog[open] form.interaction-transition-form select')?.value)", 'dialog[open]')
  await clickButton(kam, 'Закрыть', "document.querySelector('dialog[open]')")
  // Заполненное окно перехода оставляет черновик, и его плашка закрыла бы следующие снимки: черновики удаляются,
  // а полная загрузка страницы (адрес без #) сбрасывает плашку.
  await kam.evaluate("Object.keys(sessionStorage).filter((key) => key.startsWith('rtk-crm:interaction-draft:')).forEach((key) => sessionStorage.removeItem(key))")
  await goTo(kam, originUrl() + '/', "Boolean(document.querySelector('.work-row')) && !document.querySelector('.unsaved-drafts')")

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
  const reportsReady = "Boolean(document.querySelector('.reports__form .filter-button'))"
  await navigate(kam, '#/reports/portfolio', reportsReady)
  // Все этапы из самого фильтра: демо-взаимодействия стоят и на этапах, которых нет в базовом списке.
  for (const stage of await listedOptions(kam, 'Этап')) {
    await checkOption(kam, 'Этап', stage)
  }
  // Все программы, кроме «Не указано»: тестовые взаимодействия проверочных скриптов создаются без программы.
  for (const program of await listedOptions(kam, 'ИТ-программы')) {
    await checkOption(kam, 'ИТ-программы', program)
  }
  await shoot(kam, 'reports-filters', reportsReady + ' && ' + filterPanelOpen, '.filter-panel')
  await clickButton(kam, 'Готово', "document.querySelector('.filter-panel')")
  await page_waitClosed(kam)
  await clickButton(kam, 'Показать')
  const previewReady = reportsReady + " && Boolean(document.querySelector('.reports__table-scroll'))"
  await shoot(kam, 'reports-preview', previewReady, ['.reports__form', '.reports__preview'])
  assert(!(await kam.evaluate("/smoke/i.test(document.querySelector('.reports__preview').innerText)")), 'reports-preview shows test interactions')
  await kam.evaluate("document.querySelector('#report-result-tab-chart').click()")
  await shoot(kam, 'statistics', reportsReady + " && Boolean(document.querySelector('.reports__statistics svg'))", '.reports__statistics')
  await orderFromMenu(kam, 'XLSX')
  await navigate(kam, '#/reports?tab=jobs', "Boolean(document.querySelector('.report-jobs li'))")
  await kam.evaluate("document.querySelector('.toast__close')?.click()")
  await shoot(kam, 'report-files', "Boolean(document.querySelector('.report-jobs li')) && !document.querySelector('.toast, .unsaved-drafts')", '.report-jobs')

  phase = 'kam-mobile'
  await setViewport(kam, phone)
  await goTo(kam, originUrl() + '/#/work', "Boolean(document.querySelector('.work-row'))")
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
  const leaderControlReady = "Boolean(document.querySelector('.control .stat-tiles')) && Boolean(document.querySelector('.control-manager'))"
  await navigate(leader, '#/control', leaderControlReady)
  await shoot(leader, 'work-leader', leaderControlReady)
  const leaderOrganizations = await listAll(leader, '/api/organizations?sort=name,asc', 'Organizations are unavailable')
  const universityC = leaderOrganizations.find((item) => item.name.startsWith('Университет C'))
  assert(universityC, 'Leader does not see University C')
  const assignmentReady = "([...document.querySelectorAll('.org-card__top [role=menuitem]')].find((item) => item.textContent.startsWith('Назначить') && !document.querySelector('dialog[open]'))?.click(), true) && Boolean(document.querySelector('dialog[open] .organization-assignment'))"
  await navigate(leader, '#/organizations/' + universityC.id, "Boolean(document.querySelector('.org-card__top'))")
  await shoot(leader, 'assignment', assignmentReady, 'dialog[open]')
  const templatesReady = "Boolean(document.querySelector('[aria-labelledby=workflow-templates-title] .admin-table tbody tr'))"
  await navigate(leader, '#/admin/workflow-templates', templatesReady)
  await clickButton(leader, 'Создать на основе базового')
  await shoot(leader, 'templates-editor', "Boolean(document.querySelector('#workflow-template-stages-title'))", '.card-dialog[open]')

  phase = 'management'
  const management = await session(process.env.DEMO_MANAGEMENT_USER || 'management')
  const teamsReady = "Boolean(document.querySelector('.control-team--group')) && Boolean(document.querySelector('.control-team__all'))"
  await navigate(management, '#/control', teamsReady)
  await shoot(management, 'teams-summary', teamsReady, '.control-board')

  phase = 'admin'
  const admin = await session('admin')
  const adminNavReady = "Boolean(document.querySelector('.admin-section-nav'))"
  const adminProfilesReady = adminNavReady + " && Boolean(document.querySelector('#admin-employees-title'))"
  await navigate(admin, '#/admin/employees', adminProfilesReady)
  await shoot(admin, 'admin', adminProfilesReady)
  await shoot(admin, 'admin-profiles', adminProfilesReady, 'section[aria-labelledby=admin-employees-title]')
  const adminImportReady = adminNavReady + " && Boolean(document.querySelector('.import-page'))"
  await navigate(admin, '#/admin/catalog-import', adminImportReady)
  await setFile(admin, '.import-page input[type=file]', importTemplate)
  await clickButton(admin, 'Проверить файл', "document.querySelector('.import-page')")
  await shoot(admin, 'catalog-import', adminImportReady + " && document.querySelector('.import-page').innerText.includes('Сопоставление столбцов')", '.import-page')
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
