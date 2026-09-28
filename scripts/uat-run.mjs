import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import zlib from 'node:zlib'
import {
  connect, disconnect, failureText, originUrl, demoPassword, forgetSession,
  call, pause, assert, openPage, openKeycloakForm, submitKeycloak, login, session, api, upload, requireStatus, requireError, russian,
  shiftDay, listAll, waitForJob, waitClean, pdfBase64, zipStore, xlsx, nonce, today, profileIdOf, adminProfile, demoOrganization,
  freshOrganization, createInteraction, interactionOf, eventsOf, preview, reportFile, zipText, screenTexts, sections
} from './wave4-smoke.mjs'

const statePath = process.env.UAT_STATE || path.join(os.tmpdir(), 'rtk-uat-state.json')
const readState = () => (fs.existsSync(statePath) ? JSON.parse(fs.readFileSync(statePath, 'utf8')) : {})
const writeState = (update) => fs.writeFileSync(statePath, JSON.stringify({ ...readState(), ...update }, null, 1))

const label = 'UAT ' + nonce
const demo = {
  universityA: 'Университет А',
  universityB: 'Университет Б',
  universityC: 'Университет C — требует назначения',
  demoWork: 'Демо: внедрение цифрового университета',
  secureProduct: 'Демо-продукт: защищённая связь',
  cloudProduct: 'Демо-продукт: облачная платформа',
  digitalProgram: 'Демо-программа: цифровой университет'
}

const cardTabTexts = async (page, url, tab, texts, label) => {
  await screenTexts(page, url, [tab], label)
  await page.evaluate(`[...document.querySelectorAll('.card-tabs__tab')].find((item) => item.textContent.startsWith(${JSON.stringify(tab)})).click()`)
  await page.waitFor(() => page.evaluate(`${JSON.stringify(texts)}.every((item) => document.body.textContent.includes(item))`), label + ': ' + texts.join(', '))
}
const leaderView = async (name) => demoOrganization(await session('leader'), name)
const kamView = async (login, name) => demoOrganization(await session(login), name)
const organizationWork = async (page, organizationId) => listAll(page, '/api/interactions?status=ALL&organizationId=' + organizationId, 'Organization work failed')
const demoWorkOf = async (page, organizationId) => (await organizationWork(page, organizationId)).find((item) => item.title === demo.demoWork)
const contactsOf = async (page, organizationId) => requireStatus(await api(page, 'GET', '/api/organizations/' + organizationId + '/contacts'), 200, 'Contacts failed')
const assign = async (leader, organizationId, ownerManagerId, handoverNote = null) => {
  const organization = await freshOrganization(leader, organizationId)
  return requireStatus(await api(leader, 'POST', '/api/organizations/' + organizationId + '/assignment', { body: { version: organization.version, ownerManagerId, handoverNote } }), 200, 'Assignment failed')
}
const setActive = async (login, active) => {
  const admin = await session('admin')
  const profile = await adminProfile(login)
  const teamId = profile.teamId ?? (await adminProfile('kam-a')).teamId
  return requireStatus(await api(admin, 'PATCH', '/api/admin/crm-profiles/' + profile.id, { body: active ? { version: profile.version, active: true, role: 'USER', teamId } : { version: profile.version, active: false } }), 200, 'Profile update failed')
}
const orderXlsx = async (page, filters = {}) => requireStatus(await api(page, 'POST', '/api/reports', { body: { kind: 'PORTFOLIO', format: 'XLSX', from: '2026-01-01', to: today, filters, columns: [] } }), 202, 'Report order failed').jobId
const loginError = async (username) => {
  const page = await openKeycloakForm()
  await submitKeycloak(page, username, demoPassword())
  await page.waitFor(() => page.evaluate(`Boolean(document.querySelector('#input-error, .kc-feedback-text, .alert-error')) || location.origin === ${JSON.stringify(originUrl())} && !location.pathname.startsWith('/idp/')`), 'Login did not finish')
  return page.evaluate(`({ crm: location.origin === ${JSON.stringify(originUrl())} && !location.pathname.startsWith('/idp/'), error: (document.querySelector('#input-error, .kc-feedback-text, .alert-error')?.textContent || '').trim() })`)
}
const uploadNamed = async (page, work, name, type, base64, kind) => {
  const created = requireStatus(await upload(page, work.id, { stageId: work.currentStageId, name, type, base64, kind }), 201, 'Upload failed: ' + name)
  return waitClean(page, created.id)
}
const pngBase64 = () => {
  const chunk = (type, data) => {
    const body = Buffer.concat([Buffer.from(type), data])
    const length = Buffer.alloc(4)
    length.writeUInt32BE(data.length)
    const crc = Buffer.alloc(4)
    crc.writeUInt32BE(zlibCrc(body))
    return Buffer.concat([length, body, crc])
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(1, 0)
  header.writeUInt32BE(1, 4)
  header[8] = 8
  header[9] = 2
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header), chunk('IDAT', zlibDeflate(Buffer.from([0, 255, 255, 255]))), chunk('IEND', Buffer.alloc(0))]).toString('base64')
}
const zlibCrc = (buffer) => zlib.crc32(buffer)
const zlibDeflate = (buffer) => zlib.deflateSync(buffer)
const jpegBase64 = () => Buffer.from('ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffc0000b080001000101011100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda0008010100003f00fbd3ffd9', 'hex').toString('base64')
const ole2Base64 = (text) => Buffer.concat([Buffer.from('d0cf11e0a1b11ae1', 'hex'), Buffer.alloc(504), Buffer.from(text)]).toString('base64')
const officeZip = (kind) => zipStore(kind === 'docx'
  ? [{ name: '[Content_Types].xml', data: '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>' }, { name: 'word/document.xml', data: '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Договор ' + nonce + '</w:t></w:r></w:p></w:body></w:document>' }]
  : [{ name: '[Content_Types].xml', data: '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>' }, { name: 'xl/workbook.xml', data: '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>' }]).toString('base64')
const eicarZip = () => zipStore([{ name: 'eicar.com', data: 'X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*' }]).toString('base64')
const backendLog = (since = '15m') => execFileSync('docker', ['logs', '--since', since, 'rtk-crm-backend-1'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
const keycloakEvents = (userId) => JSON.parse(execFileSync('docker', ['exec', 'rtk-crm-keycloak-1', 'bash', '-c',
  '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null && /opt/keycloak/bin/kcadm.sh get events -r rtk-crm -q user=' + userId + ' -q max=50'
], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }))
const keycloakRealm = () => JSON.parse(execFileSync('docker', ['exec', 'rtk-crm-keycloak-1', 'bash', '-c',
  '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null && /opt/keycloak/bin/kcadm.sh get realms/rtk-crm --fields eventsEnabled,eventsExpiration,enabledEventTypes'
], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }))
const keycloakUserId = (username) => execFileSync('docker', ['exec', 'rtk-crm-keycloak-1', 'bash', '-c',
  '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null && /opt/keycloak/bin/kcadm.sh get users -r rtk-crm -q username=' + username + ' -q exact=true --fields id --format csv --noquotes'
], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()

const scenarios = []
const scenario = (id, title, options, run) => scenarios.push({ id, title, phase: options.phase ?? 'crm', gaps: options.gaps ?? [], manual: options.manual ?? [], blocked: options.blocked ?? null, notes: options.notes ?? [], run })

scenario('UAT-КАМ-01', 'Вход, только свои вузы и выход', { manual: [] }, async ({ step }) => {
  await step(1, 'Экран входа без данных', async () => {
    const page = await openPage(originUrl() + '/')
    await page.waitFor(() => page.evaluate("[...document.querySelectorAll('button')].some((button) => button.textContent.includes('Войти'))"), 'Нет кнопки «Войти»')
    const text = await page.evaluate('document.body.textContent')
    assert(!text.includes('Университет'), 'На экране входа видны данные')
    return 'кнопка «Войти», данных нет'
  })
  const form = await step('2–3', 'Форма входа и ошибка неверного пароля на русском', async () => {
    const value = await sections.loginRussian()
    return value.title + '; ' + value.usernameLabel + '; ' + value.submit + '; ошибка: ' + value.error
  })
  const kamA = await session('kam-a')
  await step(4, 'Вход kam-a: роль, команда, разделы', async () => {
    const me = requireStatus(await api(kamA, 'GET', '/api/me'), 200, '/api/me')
    assert(me.role === 'USER' && me.teamName === 'Команда А', 'Роль или команда: ' + me.role + ' ' + me.teamName)
    const result = await screenTexts(kamA, '/#/work', ['Моя работа', 'Вузы', 'Отчёты', 'Выйти'], 'Шапка')
    return 'USER, Команда А, разделы на месте, переполнение 360 px: ' + result.overflow360
  })
  await step('5–6', 'Только свои вузы, поиск «Университет Б» пуст', async () => {
    const organizations = [...await listAll(kamA, '/api/organizations', 'Вузы'), ...await listAll(kamA, '/api/organizations?status=ARCHIVED', 'Архив')]
    assert(!organizations.some((item) => [demo.universityB, demo.universityC].includes(item.name)), 'Видны чужие вузы')
    const search = await listAll(kamA, '/api/organizations?q=' + encodeURIComponent(demo.universityB), 'Поиск')
    assert(search.length === 0, 'Поиск нашёл Университет Б')
    const work = await listAll(kamA, '/api/interactions?status=ALL', 'Моя работа')
    const own = new Set(organizations.map((item) => item.id))
    assert(work.every((item) => own.has(item.organizationId)), 'В «Моей работе» чужие вузы')
    return 'вузов ' + organizations.length + ', поиск Б — 0'
  })
  await step(7, 'Фильтр «Ответственный» в отчётах — только КАМ А', async () => {
    const managers = requireStatus(await api(kamA, 'GET', '/api/report-filters/managers'), 200, 'Ответственные')
    const names = (managers.items ?? managers).map((item) => item.displayName ?? item.name)
    assert(names.length === 1 && names[0] === 'КАМ А', 'Ответственные: ' + names.join(','))
    return names.join(',')
  })
  await step(8, 'Ссылка «Сменить пароль»', async () => {
    await kamA.waitFor(() => kamA.evaluate("[...document.querySelectorAll('a')].some((link) => link.textContent.includes('Сменить пароль'))"), 'Нет ссылки «Сменить пароль»')
    const href = await kamA.evaluate("[...document.querySelectorAll('a')].find((link) => link.textContent.includes('Сменить пароль')).href")
    assert(href.includes('/idp/realms/rtk-crm/account'), 'Ссылка ведёт на ' + href)
    return href.replace(originUrl(), '')
  })
  await step('9–10', 'Выход и возврат по адресу без данных', async () => {
    const page = await login('kam-a')
    await page.waitFor(() => page.evaluate("[...document.querySelectorAll('button')].some((button) => button.textContent.trim() === 'Выйти')"), 'Нет «Выйти»')
    await page.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.trim() === 'Выйти').click()")
    await page.waitFor(() => page.evaluate(`location.origin === ${JSON.stringify(originUrl())} && [...document.querySelectorAll('button')].some((button) => button.textContent.includes('Войти'))`), 'После выхода нет экрана входа')
    const me = await api(page, 'GET', '/api/me')
    await call('Page.navigate', { url: originUrl() + '/#/work' }, page.sessionId)
    await page.waitFor(() => page.evaluate("[...document.querySelectorAll('button')].some((button) => button.textContent.includes('Войти'))"), 'По адресу #/work нет «Войти»')
    await page.evaluate("[...document.querySelectorAll('button')].find((button) => button.textContent.includes('Войти')).click()")
    await page.waitFor(() => page.evaluate("Boolean(document.querySelector('#password'))"), 'Пароль не запрошен повторно')
    assert(me.status === 401, '/api/me после выхода: ' + me.status)
    return '/api/me 401, пароль запрошен снова'
  })
  return form
})

scenario('UAT-КАМ-03', '«Моя работа»: список дел на день', { manual: [9, 11] }, async ({ step }) => {
  const kamA = await session('kam-a')
  const university = await kamView('kam-a', demo.universityA)
  const at = (days, hours) => new Date(Date.parse(shiftDay(today, days) + 'T' + String(hours).padStart(2, '0') + ':00:00+03:00')).toISOString()
  const weekday = new Date(today + 'T00:00:00Z').getUTCDay()
  const tomorrowIsMonday = weekday === 0
  const titles = { a: 'UAT-КАМ-03 ' + nonce + ' А', b: 'UAT-КАМ-03 ' + nonce + ' Б', v: 'UAT-КАМ-03 ' + nonce + ' В', g: 'UAT-КАМ-03 ' + nonce + ' Г' }
  const works = {
    a: await createInteraction(kamA, university.id, titles.a, { nextAction: 'Позвонить проректору', nextActionAt: at(-1, 10) }),
    b: await createInteraction(kamA, university.id, titles.b, { nextAction: 'Отправить проект договора', nextActionAt: tomorrowIsMonday ? at(0, 23) : at(1, 12) }),
    v: await createInteraction(kamA, university.id, titles.v),
    g: await createInteraction(kamA, university.id, titles.g, { nextAction: null, nextActionAt: at(-1, 9) })
  }
  const query = '&q=' + encodeURIComponent('UAT-КАМ-03 ' + nonce)
  const ids = (items) => items.map((item) => Object.entries(works).find(([, work]) => work.id === item.id)?.[0]).join('')
  await step(2, 'Поиск: 4 работы, порядок по сроку', async () => {
    const items = await listAll(kamA, '/api/interactions?sort=nextActionAt,asc' + query, 'Поиск')
    assert(items.length === 4, 'Найдено ' + items.length)
    assert(ids(items) === 'gabv', 'Порядок ' + ids(items))
    return 'найдено 4, порядок ' + ids(items)
  })
  await step(3, 'Просрочено: А и Г', async () => {
    const value = ids(await listAll(kamA, '/api/interactions?due=OVERDUE&sort=nextActionAt,asc' + query, 'Просрочено'))
    assert(value === 'ga', 'Просрочено: ' + value)
    return value
  })
  await step(4, 'Срок на этой неделе: Б (и А, если вчера на этой неделе)', async () => {
    const value = ids(await listAll(kamA, '/api/interactions?due=THIS_WEEK&sort=nextActionAt,asc' + query, 'Неделя'))
    const yesterdayThisWeek = weekday !== 1
    assert(value === (yesterdayThisWeek ? 'ab' : 'b'), 'На этой неделе: ' + value)
    return value
  })
  await step(5, 'Без следующего шага или срока: В и Г', async () => {
    const value = ids(await listAll(kamA, '/api/interactions?due=NO_NEXT_STEP' + query, 'Без шага')).split('').sort().join('')
    assert(value === 'gv', 'Без шага: ' + value)
    return value
  })
  await step(1, 'Рабочий стол: плитки равны спискам, напоминания не закрывают экран', async () => {
    await screenTexts(kamA, '/#/work', ['Задачи по срокам', 'Просрочено', 'Шаги на этой неделе', 'Без следующего шага', 'Лицензии истекают'], 'Рабочий стол')
    const tiles = Object.fromEntries(await kamA.evaluate("[...document.querySelectorAll('.desk-tile')].map((tile) => [tile.getAttribute('href'), Number(tile.querySelector('.desk-tile__value').textContent)])"))
    for (const due of ['OVERDUE', 'THIS_WEEK', 'NO_NEXT_STEP']) {
      const total = requireStatus(await api(kamA, 'GET', '/api/interactions?size=1&due=' + due), 200, 'Список').total
      assert(tiles['#/work?due=' + due] === total, due + ': плитка ' + tiles['#/work?due=' + due] + ' ≠ список ' + total)
    }
    assert(await kamA.evaluate("document.querySelector('.reminder-center__panel').hidden"), 'Напоминания открылись сами')
    return 'плитки ' + JSON.stringify(tiles) + ', панель напоминаний закрыта'
  })
  await step(6, 'В строке последнее событие и дни на этапе', async () => {
    const row = (await listAll(kamA, '/api/interactions?status=ALL' + query, 'Строки')).find((item) => item.id === works.a.id)
    assert(row.lastEventType && row.lastEventAt && row.stageEnteredAt, 'Нет последнего события или входа в этап')
    await screenTexts(kamA, '/#/work?q=' + encodeURIComponent(titles.a), [titles.a, 'на этапе'], 'Строка «Моей работы»')
    return 'lastEventType ' + row.lastEventType + ', дни на этапе на экране'
  })
  await step('7–8', 'Отбор в адресе, «Назад» сохраняет отбор', async () => {
    await call('Page.navigate', { url: originUrl() + '/#/work?q=' + encodeURIComponent('UAT-КАМ-03 ' + nonce) + '&due=OVERDUE&stage=' + encodeURIComponent('Поиск контакта') }, kamA.sessionId)
    await kamA.waitFor(() => kamA.evaluate(`[...document.querySelectorAll('.work-item__title')].map((link) => link.textContent.trim()).sort().join('|') === ${JSON.stringify([titles.a, titles.g].sort().join('|'))}`), 'Отбор по адресу не применён')
    await kamA.evaluate(`[...document.querySelectorAll('.work-item__title')].find((link) => link.textContent.trim() === ${JSON.stringify(titles.a)}).click()`)
    await kamA.waitFor(() => kamA.evaluate(`location.hash.includes(${JSON.stringify(works.a.id)})`), 'Карточка не открылась')
    await kamA.evaluate('history.back()')
    await kamA.waitFor(() => kamA.evaluate("location.hash.startsWith('#/work') && location.hash.includes('due=OVERDUE')"), '«Назад» не вернул отбор')
    return 'по адресу в списке только А и Г, «Назад» вернул due=OVERDUE'
  })
  await step(10, '«Шаг выполнен» с записью в истории', async () => {
    const done = requireStatus(await api(kamA, 'POST', '/api/interactions/' + works.a.id + '/step-completions', { body: { version: works.a.version, result: 'Звонок сделан', nextStep: null } }), 200, 'Шаг выполнен')
    const history = await eventsOf(kamA, works.a.id)
    assert(done.nextAction === null && history.some((event) => event.comment?.startsWith('Шаг выполнен: «Позвонить проректору»')), 'Нет записи «Шаг выполнен»')
    return 'PLAN_UPDATED «Шаг выполнен»'
  })
  await step(12, 'Возврат: очистка планов Б и Г', async () => {
    for (const key of ['b', 'g']) {
      const work = await interactionOf(kamA, works[key].id)
      requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + work.id, { body: { version: work.version, nextAction: null, nextActionAt: null } }), 200, 'Очистка плана')
    }
    const overdue = await listAll(kamA, '/api/interactions?due=OVERDUE' + query, 'Просрочено после очистки')
    assert(overdue.length === 0, 'Тестовые работы остались просроченными')
    return 'просроченных тестовых работ 0'
  })
})

scenario('UAT-КАМ-05', 'Новая школа и колледж, сразу работа', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const leader = await session('leader')
  const school = await step(2, 'КАМ добавляет школу', async () => {
    const created = requireStatus(await api(kamA, 'POST', '/api/organizations', { body: { name: 'UAT Школа № 1 ' + nonce, type: 'SCHOOL', city: 'Тверь', website: 'https://school.example.org' } }), 201, 'Создание школы')
    assert(created.status === 'PENDING' && created.teamName === 'Команда А' && created.ownerManagerName === 'КАМ А', 'Школа: ' + created.status + ' ' + created.ownerManagerName)
    return created
  })
  const college = await step(3, 'Колледж с типом «Колледж (СПО)»', async () => requireStatus(await api(kamA, 'POST', '/api/organizations', { body: { name: 'UAT Колледж связи ' + nonce, type: 'COLLEGE' } }), 201, 'Создание колледжа'))
  await step(4, 'Дубль «Университет А» не создаётся', async () => {
    const duplicate = await api(kamA, 'POST', '/api/organizations', { body: { name: 'университет  а', type: 'UNIVERSITY' } })
    requireError(duplicate, 400, 'Дубль', 'name')
    return duplicate.body.fieldErrors.name
  })
  await step(5, 'Школа в списке с типом', async () => {
    const found = (await listAll(kamA, '/api/organizations?q=' + encodeURIComponent('UAT Школа № 1 ' + nonce), 'Поиск'))[0]
    assert(found?.type === 'SCHOOL', 'Тип ' + found?.type)
    await screenTexts(kamA, '/#/organizations?q=' + encodeURIComponent('UAT Школа'), ['UAT Школа № 1 ' + nonce, 'Школа'], 'Список организаций')
    return 'SCHOOL, «Ожидает подтверждения»'
  })
  await step(6, 'Работа в новой школе на этапе «Поиск контакта»', async () => {
    const work = await createInteraction(kamA, school.id, 'UAT-КАМ-05 ' + nonce + ' Код будущего')
    assert(work.currentStageName === 'Поиск контакта', 'Этап ' + work.currentStageName)
    return work.currentStageName
  })
  await step(7, 'Руководитель видит заявки и подтверждает', async () => {
    const pendingList = await listAll(leader, '/api/organizations?status=PENDING', 'Ожидают подтверждения')
    assert([school.id, college.id].every((id) => pendingList.some((item) => item.id === id)), 'Руководитель не видит заявки')
    for (const item of [school, college]) {
      requireStatus(await api(leader, 'POST', '/api/organizations/' + item.id + '/status', { body: { action: 'APPROVE', version: (await freshOrganization(leader, item.id)).version } }), 200, 'Подтверждение')
    }
    return 'обе подтверждены'
  })
  await step(8, 'Отчёт КАМ по типу «Школа» — только школы, тестовая среди них', async () => {
    const report = requireStatus(await preview(kamA, { kind: 'PORTFOLIO', from: today, to: today, filters: { organizationType: 'SCHOOL' }, columns: ['ORGANIZATION', 'INTERACTION'] }), 200, 'Отчёт')
    const names = [...new Set(report.items.map((row) => row.ORGANIZATION))]
    const types = new Map([...await listAll(kamA, '/api/organizations', 'Вузы'), ...await listAll(kamA, '/api/organizations?status=ARCHIVED', 'Архив')].map((item) => [item.name, item.type]))
    assert(names.includes('UAT Школа № 1 ' + nonce) && names.every((name) => types.get(name) === 'SCHOOL'), 'Организации: ' + names.join(','))
    await screenTexts(kamA, '/#/reports/portfolio', ['Тип организации'], 'Фильтр типа на экране')
    return names.length === 1 ? names[0] : 'только школы: ' + names.length + ', тестовая среди них'
  })
  await step(9, 'Возврат: архивирование, архивная не принимает работы', async () => {
    for (const item of [school, college]) {
      requireStatus(await api(leader, 'POST', '/api/organizations/' + item.id + '/status', { body: { action: 'ARCHIVE', version: (await freshOrganization(leader, item.id)).version, reason: 'Возврат данных UAT' } }), 200, 'Архивирование')
    }
    requireError(await api(kamA, 'POST', '/api/interactions', { body: { organizationId: school.id, title: 'после архива', contactIds: [] } }), 400, 'Работа в архивной школе', 'organizationId')
    return 'ARCHIVED, новая работа — 400'
  })
})

scenario('UAT-КАМ-07', 'Контакты: правка, роль, неактуальный, обезличивание', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const admin = await session('admin')
  const university = await kamView('kam-a', demo.universityA)
  const base = '/api/organizations/' + university.id + '/contacts'
  const first = await step(1, 'Добавить контакт', async () => requireStatus(await api(kamA, 'POST', base, { body: { name: 'UAT Контакт-07 ' + nonce, position: 'Проректор', email: 'k07.' + nonce + '@example.org', phone: '+7 000 000-07-07' } }), 201, 'Контакт'))
  let current = await step(2, 'Правка телефона и должности с историей', async () => {
    const edited = requireStatus(await api(kamA, 'PATCH', base + '/' + first.id, { body: { version: first.version, name: first.name, position: 'Первый проректор', email: first.email, phone: '+7 000 000-07-08', role: null, primary: false, inactive: false } }), 200, 'Правка')
    const events = requireStatus(await api(kamA, 'GET', base + '/' + first.id + '/events'), 200, 'История контакта')
    const change = events[0].changes.find((item) => item.field === 'phone')
    assert(change.previousValue === '+7 000 000-07-07' && change.value === '+7 000 000-07-08' && events[0].actorDisplayName === 'КАМ А', 'История без было/стало')
    return edited
  })
  current = await step(3, 'Роль «Подписант (ЛПР)» и основной контакт', async () => {
    const edited = requireStatus(await api(kamA, 'PATCH', base + '/' + first.id, { body: { version: current.version, name: first.name, position: 'Первый проректор', email: first.email, phone: '+7 000 000-07-08', role: 'SIGNATORY', primary: true, inactive: false } }), 200, 'Роль')
    assert(edited.role === 'SIGNATORY' && edited.primary, 'Роль не сохранена')
    return edited
  })
  const second = await step(4, 'Второй контакт с ролью «Исполнитель»', async () => requireStatus(await api(kamA, 'POST', base, { body: { name: 'UAT Контакт-07б ' + nonce, email: 'k07b.' + nonce + '@example.org', role: 'IMPLEMENTER' } }), 201, 'Второй контакт'))
  const work = await createInteraction(kamA, university.id, 'UAT-КАМ-07 ' + nonce, { contactIds: [first.id, second.id] })
  await step(5, 'Отметить первый «Не актуален»', async () => {
    const edited = requireStatus(await api(kamA, 'PATCH', base + '/' + first.id, { body: { version: current.version, name: first.name, position: 'Первый проректор', email: first.email, phone: '+7 000 000-07-08', role: 'SIGNATORY', primary: false, inactive: true } }), 200, 'Не актуален')
    assert(edited.inactive, 'Не отмечен')
    return 'inactive'
  })
  await step(6, 'Неактуальный не выбирается для новой работы', async () => {
    requireError(await api(kamA, 'POST', '/api/interactions', { body: { organizationId: university.id, title: 'UAT-КАМ-07 новая ' + nonce, contactIds: [first.id] } }), 400, 'Неактуальный в новой работе', 'contactIds')
    return '400 contactIds'
  })
  await step(7, 'Обезличить второй контакт; история сохранена; журнал', async () => {
    requireStatus(await api(admin, 'POST', '/api/admin/personal-data/anonymization', { body: { subject: { name: second.name, email: second.email }, contactIds: [second.id], profileIds: [], attachmentIds: [] } }), 200, 'Обезличивание')
    const after = (await contactsOf(kamA, university.id)).find((item) => item.id === second.id)
    const history = await eventsOf(kamA, work.id)
    const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=PERSONAL_DATA&from=' + today + '&to=' + today + '&page=0&size=20'), 200, 'Журнал')
    assert(after.name === 'Контакт обезличен' && !after.email && history.length >= 1 && audit.items.some((item) => item.action === 'SUBJECT_ANONYMIZED'), 'Обезличивание неполное')
    return after.name
  })
  await step(8, 'В XLSX «События за период» нет ФИО обезличенного', async () => {
    const file = await reportFile(kamA, { kind: 'EVENTS', format: 'XLSX', from: today, to: today, filters: { organizationIds: [university.id] }, columns: ['EVENT_AT', 'INTERACTION', 'EVENT_TYPE', 'COMMENT'] })
    assert(!zipText(file.buffer).includes('UAT Контакт-07б'), 'ФИО найдено в XLSX')
    return 'не найдено'
  })
  await step(9, 'Возврат: обезличить и первый', async () => {
    requireStatus(await api(admin, 'POST', '/api/admin/personal-data/anonymization', { body: { subject: { name: first.name, email: first.email, phone: '+7 000 000-07-08' }, contactIds: [first.id], profileIds: [], attachmentIds: [] } }), 200, 'Обезличивание')
    return 'обезличен'
  })
})

scenario('UAT-КАМ-12', 'Пакет документов в 10 форматах', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const university = await kamView('kam-a', demo.universityA)
  const work = await createInteraction(kamA, university.id, 'UAT-КАМ-12 ' + nonce + ' пакет документов')
  const files = [
    ['uat-договор.pdf', 'application/pdf', pdfBase64('договор ' + nonce), 'CONTRACT'],
    ['uat-скан.png', 'image/png', pngBase64(), 'SIGNED_SCAN'],
    ['uat-фото.jpg', 'image/jpeg', jpegBase64(), 'OTHER'],
    ['uat-архив.zip', 'application/zip', zipStore([{ name: 'readme.txt', data: 'UAT ' + nonce }]).toString('base64'), 'MATERIALS'],
    ['uat-архив.gz', 'application/gzip', zlib.gzipSync(Buffer.from('UAT ' + nonce)).toString('base64'), 'MATERIALS'],
    ['uat-архив.rar', 'application/vnd.rar', Buffer.concat([Buffer.from('526172211a0700', 'hex'), Buffer.from('cf907300000d00000000000000', 'hex'), Buffer.alloc(16)]).toString('base64'), 'OTHER'],
    ['uat-письмо.doc', 'application/msword', ole2Base64('UAT doc ' + nonce), 'APPENDIX'],
    ['uat-договор.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', officeZip('docx'), 'CONTRACT'],
    ['uat-смета.xls', 'application/vnd.ms-excel', ole2Base64('UAT xls ' + nonce), 'APPENDIX'],
    ['uat-смета.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', officeZip('xlsx'), 'APPENDIX']
  ]
  const uploaded = {}
  await step('2–4', 'Загрузка 10 форматов и проверка ClamAV', async () => {
    const statuses = []
    for (const [name, type, base64, kind] of files) {
      const created = requireStatus(await upload(kamA, work.id, { stageId: work.currentStageId, name, type, base64, kind }), 201, 'Загрузка ' + name)
      assert(created.status === 'QUARANTINE' || created.status === 'CLEAN', 'Начальный статус ' + created.status)
      uploaded[name] = await waitClean(kamA, created.id)
      statuses.push(name.split('.').pop() + ':' + uploaded[name].status)
    }
    assert(Object.values(uploaded).every((item) => item.status === 'CLEAN'), 'Не все проверены: ' + statuses.join(', '))
    return statuses.join(', ')
  })
  await step(5, 'Скачивание под исходным именем и размером', async () => {
    const sizes = []
    for (const [name, , base64] of files) {
      const download = await api(kamA, 'GET', '/api/attachments/' + uploaded[name].id + '/download', { binary: true })
      assert(download.status === 200 && download.size === Buffer.from(base64, 'base64').length, 'Скачивание ' + name + ': ' + download.status + ' ' + download.size)
      assert(decodeURIComponent(download.headers['content-disposition'] ?? '').includes(name.split('.')[0].replace('uat-', '')), 'Имя файла ' + name)
      sizes.push(download.size)
    }
    return 'все 10 совпали по размеру'
  })
  await step(6, 'Комментарий с двумя документами', async () => {
    const card = await interactionOf(kamA, work.id)
    const result = requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/comments', { body: { version: card.version, stageId: card.currentStageId, text: 'Направлен пакет документов на согласование', attachmentIds: [uploaded['uat-договор.docx'].id, uploaded['uat-смета.xls'].id] } }), 200, 'Комментарий')
    const bound = await waitClean(kamA, uploaded['uat-смета.xls'].id)
    assert(bound.eventId === result.event.id, 'Документ не связан с событием')
    return 'связаны с событием'
  })
  await step(7, 'Вид документа и отбор', async () => {
    const card = await interactionOf(kamA, work.id)
    const kinds = new Set(card.attachments.map((item) => item.kind))
    assert(['CONTRACT', 'SIGNED_SCAN', 'MATERIALS', 'APPENDIX'].every((kind) => kinds.has(kind)), 'Виды: ' + [...kinds].join(','))
    await cardTabTexts(kamA, '/#/organizations/' + university.id + '/' + work.id, 'Документы', ['Отбор по виду документа', 'Вид документа'], 'Отбор по виду')
    return [...kinds].join(', ')
  })
  await step(8, 'Просмотр PNG и PDF без скачивания', async () => {
    const png = await api(kamA, 'GET', '/api/attachments/' + uploaded['uat-скан.png'].id + '/preview', { binary: true })
    const pdf = await api(kamA, 'GET', '/api/attachments/' + uploaded['uat-договор.pdf'].id + '/preview', { binary: true })
    assert([png, pdf].every((item) => item.status === 200 && item.headers['content-disposition']?.startsWith('inline')), 'Просмотр: ' + png.status + '/' + pdf.status)
    return 'inline'
  })
  await step(9, 'Удаление ошибочного RAR с записью в историю', async () => {
    const card = await interactionOf(kamA, work.id)
    requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/attachments/' + uploaded['uat-архив.rar'].id + '/deletion', { body: { version: card.version, reason: 'Приложен по ошибке' } }), 200, 'Удаление')
    const download = await api(kamA, 'GET', '/api/attachments/' + uploaded['uat-архив.rar'].id + '/download', { binary: true })
    const history = await eventsOf(kamA, work.id)
    assert(download.status === 404 && history.some((event) => event.type === 'ATTACHMENT_DELETED' && event.comment?.includes('uat-архив.rar')), 'Удаление не отражено')
    return '404, ATTACHMENT_DELETED'
  })
  await step(10, 'Новая версия DOCX, прежняя сохранена', async () => {
    const created = requireStatus(await upload(kamA, work.id, { stageId: work.currentStageId, name: 'uat-договор-исправленный.docx', type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', base64: officeZip('docx'), replacesId: uploaded['uat-договор.docx'].id }), 201, 'Новая версия')
    const version = await waitClean(kamA, created.id)
    const card = await interactionOf(kamA, work.id)
    assert(version.revision === 2 && card.attachments.some((item) => item.id === uploaded['uat-договор.docx'].id), 'Версии не сохранены')
    return 'revision 2, прежняя в списке'
  })
})

scenario('UAT-КАМ-13', 'Неподходящий, поддельный, большой и заражённый файл', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const university = await kamView('kam-a', demo.universityA)
  const work = await createInteraction(kamA, university.id, 'UAT-КАМ-13 ' + nonce)
  const rejected = async (name, type, base64) => {
    const response = await upload(kamA, work.id, { stageId: work.currentStageId, name, type, base64 })
    requireError(response, 400, name)
    return response.body.message + ' (' + response.body.code + ')'
  }
  await step(1, 'TXT не принят', () => rejected('uat-заметка.txt', 'text/plain', Buffer.from('заметка').toString('base64')))
  await step(2, 'EXE не принят', () => rejected('uat-программа.exe', 'application/octet-stream', Buffer.from('MZ' + nonce).toString('base64')))
  await step(3, 'Подделка PDF не принята', () => rejected('uat-подделка.pdf', 'application/pdf', Buffer.from('это не pdf').toString('base64')))
  await step(4, 'Файл больше 20 МБ отклоняется', async () => {
    const result = await kamA.evaluate(`(async () => {
      const csrf = await (await fetch('/api/csrf')).json()
      const bytes = new Uint8Array(21 * 1024 * 1024)
      bytes.set([0x25, 0x50, 0x44, 0x46, 0x2d])
      const form = new FormData()
      form.set('stageId', ${JSON.stringify(work.currentStageId)})
      form.set('file', new File([bytes], 'uat-большой.pdf', { type: 'application/pdf' }))
      const response = await fetch('/api/interactions/' + ${JSON.stringify(work.id)} + '/attachments', { method: 'POST', headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() }, body: form })
      const type = response.headers.get('content-type') || ''
      return { status: response.status, body: type.includes('json') ? await response.json() : null }
    })()`)
    assert(result.status === 413 && russian(result.body?.message), 'Большой файл: ' + result.status)
    return result.status + ' ' + result.body.code + ': ' + result.body.message
  })
  const eicar = await step(5, 'EICAR в ZIP отклонён антивирусом', async () => {
    const created = requireStatus(await upload(kamA, work.id, { stageId: work.currentStageId, name: 'uat-eicar.zip', type: 'application/zip', base64: eicarZip() }), 201, 'EICAR')
    const checked = await waitClean(kamA, created.id)
    const download = await api(kamA, 'GET', '/api/attachments/' + created.id + '/download', { binary: true })
    assert(checked.status === 'REJECTED' && download.status === 404, 'EICAR: ' + checked.status + ' ' + download.status)
    return checked
  })
  await step('6–7', 'Отклонённый не привязывается, истории и плана не касается', async () => {
    const card = await interactionOf(kamA, work.id)
    const bind = await api(kamA, 'POST', '/api/interactions/' + work.id + '/comments', { body: { version: card.version, stageId: card.currentStageId, text: 'попытка', attachmentIds: [eicar.id] } })
    const history = await eventsOf(kamA, work.id)
    assert(bind.status === 400 && history.length === 1 && card.currentStageId === work.currentStageId, 'Отклонённый файл повлиял на карточку')
    return 'привязка 400, событий 1'
  })
})

scenario('UAT-КАМ-14', 'Договор и лицензия по продукту', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const university = await kamView('kam-a', demo.universityA)
  const products = await listAll(kamA, '/api/products', 'Продукты')
  const secure = products.find((item) => item.name === demo.secureProduct)
  const cloud = products.find((item) => item.name === demo.cloudProduct)
  const work = await createInteraction(kamA, university.id, 'UAT-КАМ-14 ' + nonce, { productIds: [secure.id, cloud.id] })
  const scan = await uploadNamed(kamA, work, 'uat-скан-лицензии.pdf', 'application/pdf', pdfBase64('скан ' + nonce), 'SIGNED_SCAN')
  let card = await interactionOf(kamA, work.id)
  const agreementOf = (value, product) => value.productAgreements.find((item) => item.productId === product.id)
  await step(1, 'Продукты с полями договора', async () => {
    assert(card.productAgreements.length === 2 && agreementOf(card, secure).licenseSigned === null, 'Продукты карточки')
    return '2 продукта, «Не указано»'
  })
  card = await step('3–4', 'Номер 007/2026, подписана, 2027, скан; история', async () => {
    const saved = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + work.id + '/product-agreements/' + agreementOf(card, secure).id, { body: { version: card.version, contract: { contractNumber: '007/2026', licenseSigned: true, licenseExpiryYear: 2027, scanAttachmentId: scan.id } } }), 200, 'Договор')
    const value = agreementOf(saved, secure)
    const event = (await eventsOf(kamA, work.id)).find((item) => item.type === 'AGREEMENT_UPDATED')
    assert(value.contractNumber === '007/2026' && value.licenseSigned && value.licenseExpiryYear === 2027 && event.actorDisplayName === 'КАМ А' && event.comment.includes('→'), 'Договор не сохранён')
    return saved
  })
  await step(5, 'Второй продукт не изменился', async () => {
    const value = agreementOf(card, cloud)
    assert(!value.contractNumber && value.licenseSigned === null, 'Облачная платформа изменилась')
    return 'не изменился'
  })
  await step(6, 'Продукт с договором снять нельзя', async () => {
    requireError(await api(kamA, 'PATCH', '/api/interactions/' + work.id, { body: { version: card.version, productIds: [cloud.id] } }), 400, 'Снятие продукта')
    return '400'
  })
  await step(7, 'Колонки договора и фильтр «Лицензия подписана»', async () => {
    const report = requireStatus(await preview(kamA, { kind: 'PORTFOLIO', from: today, to: today, filters: { organizationIds: [university.id], agreement: { licenseSigned: true } }, columns: ['INTERACTION', 'CONTRACT_NUMBER', 'LICENSE_SIGNED', 'LICENSE_EXPIRY_YEAR', 'TRANSFER_STATUS'] }), 200, 'Портфель')
    const row = report.items.find((item) => item.INTERACTION === work.title)
    assert(row && row.CONTRACT_NUMBER.includes('007/2026') && row.LICENSE_EXPIRY_YEAR.includes('2027'), 'Строка отчёта ' + JSON.stringify(row))
    return row.CONTRACT_NUMBER + ' / ' + row.LICENSE_SIGNED + ' / ' + row.LICENSE_EXPIRY_YEAR
  })
  card = await step(8, 'Срок 2028 — было и стало в истории', async () => {
    const saved = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + work.id + '/product-agreements/' + agreementOf(card, secure).id, { body: { version: card.version, contract: { contractNumber: '007/2026', licenseSigned: true, licenseExpiryYear: 2028, scanAttachmentId: scan.id } } }), 200, 'Срок')
    const event = (await eventsOf(kamA, work.id)).filter((item) => item.type === 'AGREEMENT_UPDATED').at(-1)
    assert(event.comment.includes('2027') && event.comment.includes('2028'), 'Нет было/стало: ' + event.comment)
    return saved
  })
  await step(9, 'Возврат к «Не указано»', async () => {
    const saved = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + work.id + '/product-agreements/' + agreementOf(card, secure).id, { body: { version: card.version, contract: { contractNumber: null, licenseSigned: null, licenseExpiryYear: null, scanAttachmentId: null } } }), 200, 'Очистка')
    const value = agreementOf(saved, secure)
    assert(!value.contractNumber && value.licenseSigned === null && value.licenseExpiryYear === null, 'Не очищено')
    return 'очищено'
  })
})

scenario('UAT-КАМ-15', 'Отметки передачи материалов, лицензии и документации', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const university = await kamView('kam-a', demo.universityA)
  const products = await listAll(kamA, '/api/products', 'Продукты')
  const cloud = products.find((item) => item.name === demo.cloudProduct)
  const work = await createInteraction(kamA, university.id, 'UAT-КАМ-15 ' + nonce, { productIds: [cloud.id] })
  const materials = await step(1, 'Файлы материалов и лицензии проверены', async () => ({
    zip: await uploadNamed(kamA, work, 'uat-материалы.zip', 'application/zip', zipStore([{ name: 'm.txt', data: 'materials' }]).toString('base64'), 'MATERIALS'),
    license: await uploadNamed(kamA, work, 'uat-лицензия.pdf', 'application/pdf', pdfBase64('лицензия ' + nonce), 'LICENSE_AGREEMENT')
  }))
  let card = await interactionOf(kamA, work.id)
  card = await step('3–4', 'Три отметки: материалы и лицензия переданы, документация нет', async () => {
    const agreement = card.productAgreements[0]
    const saved = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + work.id + '/product-agreements/' + agreement.id, { body: { version: card.version, transfers: [
      { kind: 'MATERIALS', status: 'TRANSFERRED', transferredOn: today, attachmentId: materials.zip.id },
      { kind: 'LICENSE', status: 'TRANSFERRED', transferredOn: today, attachmentId: materials.license.id },
      { kind: 'DOCUMENTATION', status: 'NOT_TRANSFERRED', transferredOn: null, attachmentId: null }
    ] } }), 200, 'Отметки')
    const event = (await eventsOf(kamA, work.id)).find((item) => item.type === 'AGREEMENT_UPDATED')
    assert(saved.productAgreements[0].transfers.length === 3 && event.actorDisplayName === 'КАМ А', 'Отметки не сохранены')
    return saved
  })
  await step('5–6', 'Статус передачи «Передано частично» виден в карточке', async () => {
    const value = card.productAgreements[0]
    assert(value.transferStatus === 'Передано частично', 'Статус ' + value.transferStatus)
    await cardTabTexts(kamA, '/#/organizations/' + university.id + '/' + work.id, 'Отметки передачи', ['Передано частично'], 'Карточка')
    return value.transferStatus
  })
  await step(7, 'Отбор «Документация не передана»', async () => {
    const report = requireStatus(await preview(kamA, { kind: 'PORTFOLIO', from: today, to: today, filters: { organizationIds: [university.id], agreement: { notTransferred: ['DOCUMENTATION'] } }, columns: ['INTERACTION', 'TRANSFER_STATUS'] }), 200, 'Отчёт')
    assert(report.items.some((row) => row.INTERACTION === work.title), 'Работа не найдена')
    return 'найдена'
  })
  await step(8, 'Вузы с продуктом и датой последней передачи материалов', async () => {
    const report = requireStatus(await preview(kamA, { kind: 'PORTFOLIO', from: '2026-01-01', to: today, filters: { productIds: [cloud.id] }, columns: ['ORGANIZATION', 'INTERACTION', 'MATERIALS_TRANSFERRED_ON'] }), 200, 'Отчёт')
    const row = report.items.find((item) => item.INTERACTION === work.title)
    assert(row?.MATERIALS_TRANSFERRED_ON, 'Нет даты передачи')
    return row.ORGANIZATION + ': ' + row.MATERIALS_TRANSFERRED_ON
  })
  await step(9, 'Возврат: снять отметки', async () => {
    const saved = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + work.id + '/product-agreements/' + card.productAgreements[0].id, { body: { version: card.version, transfers: [] } }), 200, 'Снятие')
    assert(saved.productAgreements[0].transfers.length === 0 && !saved.productAgreements[0].transferStatus, 'Не снято')
    return 'сняты'
  })
})

scenario('UAT-КАМ-21', 'Несколько работ с вузом: правка и закрытие с итогом', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const university = await kamView('kam-a', demo.universityA)
  const first = await createInteraction(kamA, university.id, 'UAT-КАМ-21 ' + nonce + ' цифровой университет')
  const second = await createInteraction(kamA, university.id, 'UAT-КАМ-21 ' + nonce + ' защищённая связь')
  let one = first
  await step(2, 'Первая переходит на «Уточнение актуальности», вторая остаётся', async () => {
    const target = first.allowedTransitions.find((option) => option.stageName === 'Уточнение актуальности') ?? first.allowedTransitions.find((option) => !option.commentRequired)
    one = requireStatus(await api(kamA, 'POST', '/api/interactions/' + first.id + '/transitions', { body: { version: first.version, toStageId: target.stageId, comment: target.commentRequired ? 'UAT' : null } }), 200, 'Переход')
    const other = await interactionOf(kamA, second.id)
    assert(other.currentStageName === 'Поиск контакта', 'Вторая изменилась')
    return one.currentStageName
  })
  await step(3, 'Портфель: обе работы по одному разу', async () => {
    const report = requireStatus(await preview(kamA, { kind: 'PORTFOLIO', from: today, to: today, filters: { organizationIds: [university.id] }, columns: ['INTERACTION', 'STAGE'] }), 200, 'Портфель')
    const rows = report.items.filter((row) => row.INTERACTION.startsWith('UAT-КАМ-21 ' + nonce))
    assert(rows.length === 2, 'Строк ' + rows.length)
    return 'строк 2'
  })
  const contact = (await contactsOf(kamA, university.id)).find((item) => item.name === 'Игорь Демонстрационный' && !item.inactive)
  one = await step('4–6', 'Название, контакт и дата последнего контакта с историей', async () => {
    const edited = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + first.id, { body: { version: one.version, title: 'UAT-КАМ-21 ' + nonce + ' цифровой университет, кафедра ИТ', contactIds: [contact.id], lastContactAt: new Date().toISOString() } }), 200, 'Правка')
    const details = (await eventsOf(kamA, first.id)).filter((event) => event.type === 'DETAILS_UPDATED').map((event) => event.comment).join(' ')
    assert(details.includes('Название') && details.includes('Игорь Демонстрационный') && details.includes('Дата последнего контакта'), 'История: ' + details)
    return edited
  })
  await step(7, 'Вторая завершена с итогом', async () => {
    const done = requireStatus(await api(kamA, 'POST', '/api/interactions/' + second.id + '/status', { body: { version: second.version, status: 'COMPLETED', reason: 'Продукт передан, вуз работает самостоятельно' } }), 200, 'Завершение')
    const active = await listAll(kamA, '/api/interactions?q=' + encodeURIComponent('UAT-КАМ-21 ' + nonce), 'Моя работа')
    assert(done.marks.status === 'COMPLETED' && !active.some((item) => item.id === second.id), 'Завершённая видна по умолчанию')
    return 'COMPLETED, скрыта по умолчанию'
  })
  await step(8, 'Первая приостановлена с причиной', async () => {
    const paused = requireStatus(await api(kamA, 'POST', '/api/interactions/' + first.id + '/status', { body: { version: one.version, status: 'PAUSED', reason: 'Вуз перенёс старт на следующий год' } }), 200, 'Приостановка')
    assert(paused.marks.status === 'PAUSED', 'Статус ' + paused.marks.status)
    return 'PAUSED'
  })
  await step(9, 'Отбор по статусу и колонка в отчёте', async () => {
    const completed = await listAll(kamA, '/api/interactions?status=COMPLETED&q=' + encodeURIComponent('UAT-КАМ-21 ' + nonce), 'Завершённые')
    const paused = await listAll(kamA, '/api/interactions?status=PAUSED&q=' + encodeURIComponent('UAT-КАМ-21 ' + nonce), 'Приостановленные')
    const report = requireStatus(await preview(kamA, { kind: 'PORTFOLIO', from: today, to: today, filters: { organizationIds: [university.id], workStatuses: ['COMPLETED', 'PAUSED'] }, columns: ['INTERACTION', 'WORK_STATUS'] }), 200, 'Отчёт')
    const statuses = report.items.filter((row) => row.INTERACTION.startsWith('UAT-КАМ-21 ' + nonce)).map((row) => row.WORK_STATUS).sort()
    assert(completed.length === 1 && paused.length === 1 && statuses.join() === 'Завершена,Приостановлена', 'Отбор: ' + statuses.join())
    return statuses.join(', ')
  })
})

scenario('UAT-КАМ-30', 'Принять вуз от коллеги и понять историю', {}, async ({ step }) => {
  const leader = await session('leader')
  const kamA = await session('kam-a')
  const kamC = await session('kam-c')
  const university = await leaderView(demo.universityA)
  const kamCId = await profileIdOf('kam-c')
  const kamAId = await profileIdOf('kam-a')
  let commented = false
  try {
    await step('1–3', 'Передача КАМ В с комментарием', async () => {
      const result = await assign(leader, university.id, kamCId, 'Вуз на этапе подписания; Александра Демонстрационная — ЛПР, звонить до 12:00')
      assert(result.organization.ownerManagerName === 'КАМ В' && result.event.handoverNote, 'Передача')
      return 'Текущий: КАМ В, записка сохранена'
    })
    const work = await step('4–5', 'КАМ В видит вуз, работы и историю с авторами', async () => {
      const organizations = await listAll(kamC, '/api/organizations', 'Вузы')
      const demoWork = await demoWorkOf(kamC, university.id)
      const history = await eventsOf(kamC, demoWork.id)
      assert(organizations.some((item) => item.id === university.id) && history.some((event) => event.actorDisplayName === 'КАМ А'), 'Нет вуза или истории')
      return demoWork
    })
    await step(6, 'Записка и история назначений видны КАМ', async () => {
      const events = requireStatus(await api(kamC, 'GET', '/api/organizations/' + university.id + '/assignment-events'), 200, 'История назначений')
      const handover = events.at(-1)
      assert(handover?.previousOwnerManagerDisplayName === 'КАМ А' && handover.newOwnerManagerDisplayName === 'КАМ В' && handover.occurredAt && handover.handoverNote?.includes('ЛПР'), 'Нет записки или истории')
      await cardTabTexts(kamC, '/#/organizations/' + university.id, 'История назначений', ['Ответственный изменён: КАМ А → КАМ В', 'ЛПР'], 'Карточка вуза у КАМ В')
      return 'записей ' + events.length
    })
    await step(7, 'Контакт подтверждён с датой и автором', async () => {
      const contact = (await contactsOf(kamC, university.id)).find((item) => item.name === 'Александра Демонстрационная')
      const confirmed = requireStatus(await api(kamC, 'PATCH', '/api/organizations/' + university.id + '/contacts/' + contact.id, { body: { version: contact.version, name: contact.name, position: contact.position, email: contact.email, phone: contact.phone, role: contact.role, primary: contact.primary, inactive: contact.inactive, confirm: true } }), 200, 'Подтверждение')
      assert(confirmed.confirmedByName === 'КАМ В' && confirmed.confirmedAt, 'Не подтверждён')
      return 'КАМ В, ' + confirmed.confirmedAt.slice(0, 10)
    })
    await step(8, 'Комментарий от имени КАМ В', async () => {
      const card = await interactionOf(kamC, work.id)
      const result = requireStatus(await api(kamC, 'POST', '/api/interactions/' + work.id + '/comments', { body: { version: card.version, stageId: card.currentStageId, text: 'Принял вуз, созвонился с Александрой Демонстрационной ' + nonce } }), 200, 'Комментарий')
      assert(result.event.actorDisplayName === 'КАМ В', 'Автор ' + result.event.actorDisplayName)
      commented = true
      return 'автор КАМ В'
    })
    await step(9, 'У КАМ А вуза нет', async () => {
      const direct = await api(kamA, 'GET', '/api/organizations/' + university.id)
      assert(direct.status === 404, 'КАМ А: ' + direct.status)
      return '404'
    })
  } finally {
    await step(10, 'Возврат КАМ А; КАМ А видит комментарий КАМ В', async () => {
      await assign(leader, university.id, kamAId, null)
      const work = await demoWorkOf(kamA, university.id)
      const history = await eventsOf(kamA, work.id)
      const [handover, handback] = requireStatus(await api(kamA, 'GET', '/api/organizations/' + university.id + '/assignment-events'), 200, 'История назначений').slice(-2)
      const kamCView = await api(kamC, 'GET', '/api/organizations/' + university.id)
      assert(handover?.newOwnerManagerDisplayName === 'КАМ В' && handover.handoverNote?.includes('ЛПР') && handback?.previousOwnerManagerDisplayName === 'КАМ В' && handback.newOwnerManagerDisplayName === 'КАМ А', 'В истории назначений нет обеих смен')
      assert(!commented || history.some((event) => event.actorDisplayName === 'КАМ В' && event.comment?.includes(nonce)), 'КАМ А не видит комментарий КАМ В')
      assert(kamCView.status === 404, 'КАМ В после возврата: ' + kamCView.status)
      return (commented ? 'КАМ А видит комментарий и обе смены' : 'КАМ А видит обе смены') + ', КАМ В — 404'
    })
  }
})

scenario('UAT-КАМ-33', 'Чужой вуз, работа и файл по прямой ссылке', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const kamB = await session('kam-b')
  const universityB = await kamView('kam-b', demo.universityB)
  const work = (await organizationWork(kamB, universityB.id))[0]
  const file = await step('1–3', 'kam-b загружает файл в работу Университета Б', async () => uploadNamed(kamB, await interactionOf(kamB, work.id), 'uat-договор.pdf', 'application/pdf', pdfBase64('B ' + nonce), 'CONTRACT'))
  await step('4–5', 'Карточки по адресу не открываются', async () => {
    await screenTexts(kamA, '/#/organizations/' + universityB.id + '/' + work.id, ['Не удалось открыть карточку вуза'], 'Чужая карточка')
    const text = await kamA.evaluate('document.body.textContent')
    assert(!text.includes(universityB.name), 'Показано название чужого вуза')
    return 'сообщение без данных'
  })
  await step(6, 'API вуза — NOT_FOUND без названия', async () => {
    const response = await api(kamA, 'GET', '/api/organizations/' + universityB.id)
    assert(response.status === 404 && response.body.code === 'NOT_FOUND' && !JSON.stringify(response.body).includes('Университет Б'), 'Ответ ' + response.status)
    return '404 NOT_FOUND'
  })
  await step(7, 'Файл не скачивается', async () => {
    const response = await api(kamA, 'GET', '/api/attachments/' + file.id + '/download', { binary: true })
    assert(response.status === 404, 'Скачивание ' + response.status)
    return '404'
  })
  await step(8, 'Поиск в «Моей работе» пуст', async () => {
    const items = await listAll(kamA, '/api/interactions?status=ALL&q=' + encodeURIComponent(demo.universityB), 'Поиск')
    assert(items.length === 0, 'Найдено ' + items.length)
    return '0'
  })
  await step(9, 'Портфель и XLSX без Университетов Б и C', async () => {
    const report = requireStatus(await preview(kamA, { kind: 'PORTFOLIO', from: '2026-01-01', to: today, filters: {}, columns: ['ORGANIZATION'] }), 200, 'Портфель')
    const file = await reportFile(kamA, { kind: 'PORTFOLIO', format: 'XLSX', from: '2026-01-01', to: today, filters: {}, columns: [] })
    const text = zipText(file.buffer)
    assert(!report.items.some((row) => [demo.universityB, demo.universityC].includes(row.ORGANIZATION)) && !text.includes('Университет Б') && !text.includes('Университет C'), 'Чужие вузы в отчёте')
    return 'строк ' + report.total + ', чужих нет'
  })
  await step(10, 'Университет C по адресу недоступен', async () => {
    const universityC = await leaderView(demo.universityC)
    const response = await api(kamA, 'GET', '/api/organizations/' + universityC.id)
    assert(response.status === 404, 'C: ' + response.status)
    return '404'
  })
})

scenario('UAT-КАМ-35', 'КАМ не выполняет действия руководителя и администратора', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const university = await kamView('kam-a', demo.universityA)
  await step('1–3', 'Нет разделов руководителя и администратора', async () => {
    await screenTexts(kamA, '/#/organizations/' + university.id, ['Ответственный КАМ', 'История назначений'], 'Карточка вуза')
    const card = await kamA.evaluate('document.body.textContent')
    await call('Page.navigate', { url: originUrl() + '/#/admin' }, kamA.sessionId)
    await pause(1500)
    const adminText = await kamA.evaluate('document.body.textContent')
    assert(!card.includes('Назначить ответственного') && !adminText.includes('Профили CRM') && !adminText.includes('Импорт каталогов'), 'Видны разделы руководителя или администратора')
    return 'нет'
  })
  await step(4, 'assignment-options — отказ с code, message, requestId', async () => {
    const response = await api(kamA, 'GET', '/api/organizations/' + university.id + '/assignment-options')
    assert([403, 404].includes(response.status) && response.body.code && response.body.requestId && !Array.isArray(response.body), 'Ответ ' + response.status)
    return response.status + ' ' + response.body.code
  })
  await step(5, 'Служебные API — отказ', async () => {
    const statuses = []
    for (const url of ['/api/admin/crm-profiles', '/api/admin/teams', '/api/admin/workflow-templates', '/api/admin/source-records']) {
      const response = await api(kamA, 'GET', url)
      assert(response.status === 403 && response.body.requestId, url + ': ' + response.status)
      statuses.push(response.status)
    }
    return statuses.join(',')
  })
  await step(6, 'Шаблоны только для выбора', async () => {
    const templates = requireStatus(await api(kamA, 'GET', '/api/workflow-templates?page=0&size=20'), 200, 'Шаблоны')
    const templateId = templates.items[0].id
    const change = await api(kamA, 'DELETE', '/api/admin/workflow-templates/' + templateId + '?version=0')
    assert(change.status === 403, 'Удаление шаблона ' + change.status)
    return 'чтение 200, удаление 403'
  })
  await step(7, 'Ответственный — только КАМ А', async () => {
    const managers = requireStatus(await api(kamA, 'GET', '/api/report-filters/managers'), 200, 'Ответственные')
    const names = (managers.items ?? managers).map((item) => item.displayName ?? item.name)
    assert(names.join() === 'КАМ А', names.join())
    return names.join()
  })
})

scenario('UAT-РУК-02', 'Передача вуза КАМ А → КАМ В и обратно', {}, async ({ step }) => {
  const leader = await session('leader')
  const kamA = await session('kam-a')
  const kamC = await session('kam-c')
  const university = await leaderView(demo.universityA)
  const kamAId = await profileIdOf('kam-a')
  const kamCId = await profileIdOf('kam-c')
  const jobId = await step(1, 'КАМ А заказывает XLSX до передачи', async () => {
    const id = await orderXlsx(kamA)
    const job = await waitForJob(kamA, '/api/report-jobs/' + id)
    assert(job.status === 'SUCCEEDED', 'Отчёт ' + job.status)
    return id
  })
  await step(2, 'У КАМ В нет вузов', async () => {
    const organizations = await listAll(kamC, '/api/organizations', 'Вузы')
    assert(organizations.length === 0, 'Вузов ' + organizations.length)
    return '0'
  })
  await step(3, 'В списке только активные сотрудники команды', async () => {
    const options = requireStatus(await api(leader, 'GET', '/api/organizations/' + university.id + '/assignment-options'), 200, 'Кандидаты')
    const names = options.map((item) => item.displayName)
    assert(names.includes('КАМ В') && !names.includes('КАМ Б') && !names.includes('Администратор') && !names.includes('Руководитель Б'), names.join(','))
    return names.join(', ')
  })
  try {
    await step('4–5', 'Передача КАМ В с запиской; история', async () => {
      const result = await assign(leader, university.id, kamCId, 'Передача UAT-РУК-02 ' + nonce)
      const events = requireStatus(await api(leader, 'GET', '/api/organizations/' + university.id + '/assignment-events'), 200, 'История')
      assert(result.organization.ownerManagerName === 'КАМ В' && events.at(-1).previousOwnerManagerDisplayName === 'КАМ А' && events.at(-1).actorDisplayName === 'Руководитель', 'История')
      return 'КАМ А → КАМ В, Руководитель'
    })
    await step(6, 'КАМ А теряет вуз и карточки', async () => {
      const work = await demoWorkOf(leader, university.id)
      const organization = await api(kamA, 'GET', '/api/organizations/' + university.id)
      const card = await api(kamA, 'GET', '/api/interactions/' + work.id)
      assert(organization.status === 404 && card.status === 404, organization.status + '/' + card.status)
      return '404/404'
    })
    await step(7, 'Прежний отчёт КАМ А не выдаётся', async () => {
      const download = await api(kamA, 'GET', '/api/report-jobs/' + jobId + '/result', { binary: true })
      assert(download.status === 410, 'Скачивание ' + download.status)
      return '410'
    })
    await step('8–9', 'КАМ В видит карточку, записку и историю назначений', async () => {
      const work = await demoWorkOf(kamC, university.id)
      const events = requireStatus(await api(kamC, 'GET', '/api/organizations/' + university.id + '/assignment-events'), 200, 'История')
      assert(work && events.at(-1).handoverNote?.includes(nonce), 'Нет записки')
      return 'видны'
    })
    await step(11, 'У руководителя ответственный — КАМ В', async () => {
      const work = await listAll(leader, '/api/interactions?organizationId=' + university.id, 'Работы')
      assert(work.every((item) => item.ownerManagerName === 'КАМ В'), 'Ответственный')
      return 'КАМ В'
    })
  } finally {
    await step(12, 'Возврат КАМ А', async () => {
      await assign(leader, university.id, kamAId)
      const kamCView = await api(kamC, 'GET', '/api/organizations/' + university.id)
      const events = requireStatus(await api(leader, 'GET', '/api/organizations/' + university.id + '/assignment-events'), 200, 'История')
      assert(kamCView.status === 404 && events.at(-1).newOwnerManagerDisplayName === 'КАМ А', 'Возврат')
      return 'КАМ В → КАМ А'
    })
  }
})

scenario('UAT-РУК-04', 'Уход КАМ: вузы в «Требует назначения», массовая раздача', {}, async ({ step }) => {
  const leader = await session('leader')
  const admin = await session('admin')
  const kamC = await session('kam-c')
  const universityA = await leaderView(demo.universityA)
  const universityC = await leaderView(demo.universityC)
  const kamAId = await profileIdOf('kam-a')
  const kamCId = await profileIdOf('kam-c')
  let jobId
  let plannedWork
  try {
    await step(1, 'КАМ В назначен за А и C', async () => {
      await assign(leader, universityA.id, kamCId)
      await assign(leader, universityC.id, kamCId)
      return 'назначен'
    })
    await step(2, 'КАМ В видит вузы, пишет шаг, заказывает отчёт', async () => {
      const work = (await organizationWork(kamC, universityC.id))[0]
      plannedWork = requireStatus(await api(kamC, 'PATCH', '/api/interactions/' + work.id, { body: { version: work.version, nextAction: 'UAT-РУК-04 шаг ' + nonce, nextActionAt: new Date(Date.now() + 86400000).toISOString() } }), 200, 'План')
      jobId = await orderXlsx(kamC)
      assert((await waitForJob(kamC, '/api/report-jobs/' + jobId)).status === 'SUCCEEDED', 'Отчёт')
      return 'шаг записан, отчёт готов'
    })
    await step('3–4', 'Блокировка КАМ В: журнал профиля', async () => {
      const blocked = await setActive('kam-c', false)
      const events = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles/' + kamCId + '/events'), 200, 'Журнал профиля')
      assert(!blocked.active && events[0].actorDisplayName === 'Администратор', 'Журнал')
      return 'заблокирован, запись в журнале'
    })
    await step(5, 'КАМ В получает «Профиль CRM недоступен»', async () => {
      const response = await api(kamC, 'GET', '/api/organizations')
      assert([401, 403].includes(response.status), 'Ответ ' + response.status)
      return response.status + ' ' + (response.body?.code ?? '')
    })
    await step('6–7', 'Оба вуза «Требует назначения» с причиной', async () => {
      const unassigned = await listAll(leader, '/api/organizations?requiresAssignment=true', 'Требует назначения')
      const events = requireStatus(await api(leader, 'GET', '/api/organizations/' + universityA.id + '/assignment-events'), 200, 'История')
      assert([universityA.id, universityC.id].every((id) => unassigned.some((item) => item.id === id)) && events.at(-1).reason === 'PROFILE_BLOCKED' && events.at(-1).actorDisplayName === 'Администратор', 'Нет причины')
      return 'PROFILE_BLOCKED, Администратор'
    })
    await step(8, 'Шаги и сроки сохранены', async () => {
      const work = await interactionOf(leader, plannedWork.id)
      assert(work.nextAction === plannedWork.nextAction, 'Шаг потерян')
      return work.nextAction
    })
    await step('9–10', 'Массовая передача обоих вузов КАМ А', async () => {
      const items = []
      for (const id of [universityA.id, universityC.id]) {
        items.push({ organizationId: id, version: (await freshOrganization(leader, id)).version, ownerManagerId: kamAId })
      }
      const result = requireStatus(await api(leader, 'POST', '/api/organization-assignments', { body: { items } }), 200, 'Массовая передача')
      assert(result.assigned.length === 2, 'Передано ' + result.assigned.length)
      return 'передано 2'
    })
    await step(11, 'КАМ А видит историю при КАМ В и прежний шаг', async () => {
      const kamA = await session('kam-a')
      const work = await interactionOf(kamA, plannedWork.id)
      assert(work.nextAction === plannedWork.nextAction, 'Шаг')
      return 'видит'
    })
  } finally {
    await step(12, 'Возврат: КАМ В активен без вузов, отчёт не выдаётся; C без ответственного', async () => {
      const current = await freshOrganization(leader, universityC.id)
      if (current.ownerManagerId) {
        requireStatus(await api(leader, 'POST', '/api/organizations/' + universityC.id + '/assignment', { body: { version: current.version, ownerManagerId: null } }), 200, 'Снятие C')
      }
      const a = await freshOrganization(leader, universityA.id)
      if (a.ownerManagerId !== kamAId) {
        await assign(leader, universityA.id, kamAId)
      }
      const profile = await adminProfile('kam-c')
      if (!profile.active) {
        await setActive('kam-c', true)
      }
      forgetSession('kam-c')
      const again = await session('kam-c')
      const organizations = await listAll(again, '/api/organizations', 'Вузы')
      const download = jobId ? await api(again, 'GET', '/api/report-jobs/' + jobId + '/result', { binary: true }) : { status: 'нет' }
      assert(organizations.length === 0 && download.status === 410, 'Вузов ' + organizations.length + ', отчёт ' + download.status)
      return 'вузов 0, отчёт 410'
    })
  }
})

scenario('UAT-РУК-05', 'Временный заместитель', {}, async ({ step }) => {
  const leader = await session('leader')
  const kamA = await session('kam-a')
  const kamC = await session('kam-c')
  const university = await leaderView(demo.universityA)
  const kamCId = await profileIdOf('kam-c')
  let deputy
  try {
    deputy = await step('1–2', 'Назначить заместителя КАМ В на сегодня и завтра', async () => {
      const created = requireStatus(await api(leader, 'POST', '/api/organizations/' + university.id + '/deputies', { body: { deputyProfileId: kamCId, startsOn: today, endsOn: shiftDay(today, 1) } }), 201, 'Заместитель')
      const organization = await freshOrganization(leader, university.id)
      assert(organization.ownerManagerName === 'КАМ А' && organization.deputyManagerName === 'КАМ В', 'Карточка вуза')
      return created
    })
    await step(3, 'КАМ В ведёт карточку от своего имени', async () => {
      const work = await demoWorkOf(kamC, university.id)
      const card = await interactionOf(kamC, work.id)
      const result = requireStatus(await api(kamC, 'POST', '/api/interactions/' + work.id + '/comments', { body: { version: card.version, stageId: card.currentStageId, text: 'Замещаю КАМ А ' + nonce } }), 200, 'Комментарий')
      assert(result.event.actorDisplayName === 'КАМ В', 'Автор')
      return 'автор КАМ В'
    })
    await step(4, 'Ответственный в «Моей работе» и отчёте — КАМ А', async () => {
      const work = await listAll(leader, '/api/interactions?organizationId=' + university.id, 'Работы')
      const report = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: '2026-01-01', to: today, filters: { organizationIds: [university.id] }, columns: ['INTERACTION', 'MANAGER'] }), 200, 'Отчёт')
      assert(work.every((item) => item.ownerManagerName === 'КАМ А' && item.deputyManagerName === 'КАМ В') && report.items.every((row) => row.MANAGER === 'КАМ А'), 'Ответственный')
      return 'КАМ А (заместитель КАМ В)'
    })
    await step(5, 'Досрочное завершение: доступ закрыт, КАМ А видит комментарий', async () => {
      const ended = requireStatus(await api(leader, 'POST', '/api/organizations/' + university.id + '/deputies/' + deputy.id + '/end'), 200, 'Завершение')
      const access = await api(kamC, 'GET', '/api/organizations/' + university.id)
      const work = await demoWorkOf(kamA, university.id)
      const history = await eventsOf(kamA, work.id)
      assert(ended.endedAt && access.status === 404 && history.some((event) => event.comment?.includes('Замещаю КАМ А ' + nonce)), 'Завершение')
      return 'КАМ В 404, комментарий виден'
    })
  } finally {
    await step(7, 'Исходное состояние: вуз у КАМ А, у КАМ В нет вузов', async () => {
      const organization = await freshOrganization(leader, university.id)
      const organizations = await listAll(kamC, '/api/organizations', 'Вузы')
      assert(organization.ownerManagerName === 'КАМ А' && !organization.deputyManagerName && organizations.length === 0, 'Состояние')
      return 'восстановлено'
    })
  }
})

scenario('UAT-РУК-08', 'Показатели руководителя по КАМ', {}, async ({ step }) => {
  const leader = await session('leader')
  const indicators = await step(1, 'Блок показателей по КАМ и «Требует назначения»', async () => {
    const value = requireStatus(await api(leader, 'GET', '/api/work/team-indicators'), 200, 'Показатели')
    await screenTexts(leader, '/#/work', ['Пульт команды', 'Где команде нужна помощь', 'Требует назначения', 'Вузы без ответственного', 'Заместители'], 'Блок показателей')
    assert(value.managers.some((item) => item.managerId === null) && value.calculatedAt, 'Нет строки «Требует назначения»')
    return value
  })
  await step(2, '«Просрочено» у «Требует назначения» совпадает со списком', async () => {
    const row = indicators.managers.find((item) => item.managerId === null)
    const list = await listAll(leader, '/api/interactions?responsible=UNASSIGNED&due=OVERDUE', 'Список')
    assert(row.overdue === list.length, row.overdue + ' ≠ ' + list.length)
    return row.overdue + ' = ' + list.length
  })
  await step(3, 'Число вузов без КАМ совпадает со списком', async () => {
    const list = await listAll(leader, '/api/organizations?requiresAssignment=true', 'Вузы')
    assert(indicators.unassignedOrganizations === list.length, indicators.unassignedOrganizations + ' ≠ ' + list.length)
    return String(list.length)
  })
  const total = await step(4, 'Портфель без периода', async () => {
    const report = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: null, to: null, filters: {}, columns: ['ORGANIZATION', 'INTERACTION', 'WORK_STATUS', 'MANAGER', 'LAST_EVENT_AT', 'NEXT_ACTION', 'NEXT_ACTION_AT'] }, 1), 200, 'Портфель')
    return report.total
  })
  await step(5, 'Статистика по ответственным: сумма равна портфелю', async () => {
    const statistics = requireStatus(await api(leader, 'POST', '/api/statistics', { body: { kind: 'PORTFOLIO', groupBy: 'MANAGER', from: null, to: null, filters: {} } }), 200, 'Статистика')
    const sum = statistics.items.reduce((value, item) => value + item.count, 0) + statistics.unknownCount
    assert(sum === total && statistics.total === total, sum + ' ≠ ' + total)
    return 'сумма ' + sum + ' (в т. ч. «Не указано» ' + statistics.unknownCount + ')'
  })
  await step(6, 'Работы без КАМ: «Моя работа» = отчёт «Не указано»', async () => {
    const list = await listAll(leader, '/api/interactions?responsible=UNASSIGNED&status=ALL', 'Список')
    const report = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: null, to: null, filters: { includeNoManager: true } }, 1), 200, 'Отчёт')
    assert(list.length === report.total, list.length + ' ≠ ' + report.total)
    return String(list.length)
  })
  await step('1а', 'Плитки пульта равны спискам «Моей работы»', async () => {
    const sum = (key) => indicators.managers.reduce((value, item) => value + item[key], 0)
    const totals = {}
    for (const [key, query] of [['overdue', 'due=OVERDUE'], ['withoutNextStep', 'due=NO_NEXT_STEP'], ['stuck', 'minDaysOnStage=' + indicators.stuckDays]]) {
      totals[key] = requireStatus(await api(leader, 'GET', '/api/interactions?size=1&' + query), 200, 'Список').total
      assert(sum(key) === totals[key], key + ': ' + sum(key) + ' ≠ ' + totals[key])
    }
    return JSON.stringify(totals)
  })
  await step(7, 'Колонка «Дней на этапе» и отбор «дольше N дней»', async () => {
    const report = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: null, to: null, filters: { minDaysOnStage: 1 }, columns: ['INTERACTION', 'DAYS_ON_STAGE'] }), 200, 'Отчёт')
    assert(report.items.every((row) => Number(row.DAYS_ON_STAGE) >= 1), 'Отбор неверен')
    return 'строк ' + report.total
  })
})

scenario('UAT-РУК-14', 'Данных другой команды у руководителя нет', {}, async ({ step }) => {
  const leader = await session('leader')
  const kamB = await session('kam-b')
  const universityB = await kamView('kam-b', demo.universityB)
  const workB = (await organizationWork(kamB, universityB.id))[0]
  await step(2, 'Поиск «университет б» пуст', async () => {
    const items = await listAll(leader, '/api/organizations?q=' + encodeURIComponent('университет б'), 'Поиск')
    assert(items.length === 0, 'Найдено ' + items.length)
    return '0'
  })
  await step('3–4', 'Карточки Б не открываются, действия недоступны', async () => {
    const organization = await api(leader, 'GET', '/api/organizations/' + universityB.id)
    const card = await api(leader, 'GET', '/api/interactions/' + workB.id)
    const comment = await api(leader, 'POST', '/api/interactions/' + workB.id + '/comments', { body: { version: workB.version, stageId: workB.currentStageId, text: 'чужая' } })
    await screenTexts(leader, '/#/organizations/' + universityB.id, ['Не удалось открыть карточку вуза'], 'Чужая карточка')
    assert([organization.status, card.status, comment.status].every((status) => status === 404), [organization.status, card.status, comment.status].join())
    return '404/404/404'
  })
  await step('5–6', '«Моя работа», фильтры и отчёты без Б', async () => {
    const work = await listAll(leader, '/api/interactions?status=ALL&q=' + encodeURIComponent('Университет Б'), 'Поиск')
    const managers = requireStatus(await api(leader, 'GET', '/api/report-filters/managers'), 200, 'Ответственные')
    const names = (managers.items ?? managers).map((item) => item.displayName ?? item.name)
    const portfolio = requireStatus(await preview(leader, { kind: 'PORTFOLIO', from: '2026-01-01', to: today, filters: {}, columns: ['ORGANIZATION', 'MANAGER'] }), 200, 'Портфель')
    const events = requireStatus(await preview(leader, { kind: 'EVENTS', from: '2026-01-01', to: today, filters: {}, columns: ['ORGANIZATION'] }), 200, 'События')
    assert(work.length === 0 && !names.includes('КАМ Б') && ![...portfolio.items, ...events.items].some((row) => row.ORGANIZATION === demo.universityB), 'Видны данные Б')
    return 'нет'
  })
  await step(7, 'DEMAND и статистика по вузам без Б', async () => {
    const statistics = requireStatus(await api(leader, 'POST', '/api/statistics', { body: { kind: 'PORTFOLIO', groupBy: 'ORGANIZATION', from: '2026-01-01', to: today, filters: {} } }), 200, 'Статистика')
    assert(!statistics.items.some((item) => item.label === demo.universityB), 'Столбец Б')
    return 'нет столбца Б'
  })
  await step(8, 'XLSX и JSON без Б', async () => {
    const xlsxFile = await reportFile(leader, { kind: 'PORTFOLIO', format: 'XLSX', from: null, to: null, filters: {}, columns: [] })
    const json = await reportFile(leader, { kind: 'PORTFOLIO', format: 'JSON', from: null, to: null, filters: {}, columns: [] })
    assert(!zipText(xlsxFile.buffer).includes('Университет Б') && !json.buffer.toString('utf8').includes('Университет Б'), 'Найдено в файлах')
    return 'нет'
  })
  await step(9, 'КАМ Б нельзя назначить в Университет А', async () => {
    const universityA = await leaderView(demo.universityA)
    const options = requireStatus(await api(leader, 'GET', '/api/organizations/' + universityA.id + '/assignment-options'), 200, 'Кандидаты')
    assert(!options.some((item) => item.displayName === 'КАМ Б'), 'КАМ Б в списке')
    return 'нет'
  })
  await step('11–12', 'Администрирование недоступно', async () => {
    const profiles = await api(leader, 'GET', '/api/admin/crm-profiles')
    const records = await api(leader, 'GET', '/api/admin/source-records')
    assert(profiles.status === 403 && records.status === 403 && profiles.body.requestId, profiles.status + '/' + records.status)
    return '403/403'
  })
})

const blockFlow = async (step, withKeycloak) => {
  const leader = await session('leader')
  const admin = await session('admin')
  const kamC = await session('kam-c')
  const universityC = await leaderView(demo.universityC)
  const kamCId = await profileIdOf('kam-c')
  let jobId
  let work
  try {
    await step(1, 'Руководитель назначает КАМ В за Университет C', async () => {
      const result = await assign(leader, universityC.id, kamCId)
      assert(result.event.actorDisplayName === 'Руководитель', 'Автор')
      return 'назначен'
    })
    work = await step(2, 'КАМ В видит вуз и работу', async () => {
      const items = await organizationWork(kamC, universityC.id)
      assert(items.length > 0, 'Нет работ')
      return items[0]
    })
    await step(3, 'КАМ В заказывает XLSX', async () => {
      jobId = await orderXlsx(kamC, { organizationIds: [universityC.id] })
      assert((await waitForJob(kamC, '/api/report-jobs/' + jobId)).status === 'SUCCEEDED', 'Отчёт')
      return 'готов'
    })
    await step('4–5', 'Блокировка КАМ В', async () => {
      const blocked = await setActive('kam-c', false)
      assert(!blocked.active && !blocked.accountSyncRequired, 'Блокировка или синхронизация Keycloak')
      return 'заблокирован, Keycloak синхронизирован'
    })
    await step(6, 'Keycloak: учётная запись отключена', async () => {
      const state = await loginError('kam-c')
      assert(!state.crm && russian(state.error), 'Вход выполнен')
      return state.error
    })
    await step('7–8', 'Открытая сессия КАМ В: доступ закрыт', async () => {
      const response = await api(kamC, 'GET', '/api/interactions/' + work.id)
      assert([401, 403].includes(response.status), 'Ответ ' + response.status)
      return response.status + ' ' + (response.body?.code ?? '')
    })
    await step(9, 'Университет C «Требует назначения», причина и автор', async () => {
      const organization = await freshOrganization(leader, universityC.id)
      const events = requireStatus(await api(leader, 'GET', '/api/organizations/' + universityC.id + '/assignment-events'), 200, 'История')
      assert(organization.requiresAssignment && events.at(-1).reason === 'PROFILE_BLOCKED' && events.at(-1).actorDisplayName === 'Администратор', 'Снятие')
      return 'PROFILE_BLOCKED, Администратор'
    })
    await step(10, 'Работа не изменилась', async () => {
      const card = await interactionOf(leader, work.id)
      assert(card.version === work.version, 'Версия ' + card.version + ' ≠ ' + work.version)
      return 'версия ' + card.version
    })
  } finally {
    await step(11, 'Разблокировка: вуз не возвращается', async () => {
      const profile = await adminProfile('kam-c')
      if (!profile.active) {
        await setActive('kam-c', true)
      }
      const organization = await freshOrganization(leader, universityC.id)
      assert(organization.requiresAssignment, 'Вуз вернулся к КАМ В')
      return 'C без ответственного'
    })
    await step(12, 'Прежний отчёт не выдаётся, вузов нет', async () => {
      forgetSession('kam-c')
      const again = await session('kam-c')
      const download = jobId ? await api(again, 'GET', '/api/report-jobs/' + jobId + '/result', { binary: true }) : { status: 'нет' }
      const organizations = await listAll(again, '/api/organizations', 'Вузы')
      assert(download.status === 410 && organizations.length === 0, 'Отчёт ' + download.status)
      return '410, вузов 0'
    })
    await step(13, 'Журнал профиля: закрытие и открытие с Request ID', async () => {
      const events = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles/' + kamCId + '/events'), 200, 'Журнал')
      assert(events.length >= 2 && events.slice(0, 2).every((event) => event.requestId && event.actorDisplayName === 'Администратор'), 'Журнал')
      const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=ACCOUNT&from=' + today + '&to=' + today + '&page=0&size=20'), 200, 'Журнал безопасности')
      assert(audit.items.some((item) => item.action === 'ACCOUNT_DISABLED' && item.objectId === kamCId), 'Нет ACCOUNT_DISABLED')
      return 'записи есть'
    })
    if (withKeycloak) {
      await step('12 (Keycloak)', 'Учётная запись Keycloak снова включена', async () => {
        const kamC2 = await session('kam-c')
        const me = await api(kamC2, 'GET', '/api/me')
        assert(me.status === 200, 'Вход после разблокировки ' + me.status)
        return 'вход 200'
      })
    }
  }
}

scenario('UAT-АДМ-03', 'Блокировка уволенного КАМ', {}, async ({ step }) => blockFlow(step, false))
scenario('UAT-ИБ-02', 'Немедленный отзыв прав при блокировке', { manual: [3] }, async ({ step }) => blockFlow(step, true))

scenario('UAT-АДМ-10', 'Импорт спорных строк', {}, async ({ step }) => {
  const admin = await session('admin')
  const leader = await session('leader')
  const kamC = await adminProfile('kam-c')
  const vendors = requireStatus(await api(admin, 'GET', '/api/admin/catalogs/vendors?page=0&size=100'), 200, 'Вендоры')
  const allProducts = requireStatus(await api(admin, 'GET', '/api/admin/catalogs/products?page=0&size=100'), 200, 'Продукты')
  const secure = (allProducts.items ?? allProducts).find((item) => item.name === demo.secureProduct)
  const cloud = (allProducts.items ?? allProducts).find((item) => item.name === demo.cloudProduct)
  const vendorName = (id) => (vendors.items ?? vendors).find((item) => item.id === id).name
  const teamA = requireStatus(await api(admin, 'GET', '/api/admin/teams'), 200, 'Команды').find((team) => team.name === 'Команда А')
  const organization = requireStatus(await api(leader, 'POST', '/api/organizations', { body: { name: 'UAT Университет импорта ' + nonce, type: 'UNIVERSITY' } }), 201, 'Вуз')
  const newName = 'UAT Новый вуз без менеджера ' + nonce
  const headers = ['Название ВУЗа', 'Вендор', 'ПО', 'Номер договора', 'Подписание лицензии', 'Срок действия лицензии (год)', 'Статус по передаче', 'ФИО Менеджера', 'Ответственные от ВУЗа', 'Комментарий']
  const fields = ['organizationName', 'vendorName', 'productName', 'contractNumber', 'licenseSigned', 'licenseExpiryYear', 'transferStatus', 'managerName', 'contactName', 'comment']
  const rowOf = (product, contract, extra = {}) => [organization.name, vendorName(product.parentId), product.name, contract, 'Да', '2028', 'передан', 'КАМ А', extra.contacts ?? '', extra.comment ?? '']
  const fullFile = xlsx('Каталог', [
    headers,
    rowOf(secure, '010/2026', { contacts: 'Сидорова Анна (UAT); Кузнецов Пётр (UAT)', comment: 'спорный менеджер' }),
    [newName, vendorName(cloud.parentId), cloud.name, '011/2026', 'Нет', '2029', '', '', 'Орлов О.О.', 'без менеджера'],
    rowOf(cloud, '012/2026'),
    rowOf(cloud, '012/2026')
  ]).toString('base64')
  const shortFile = xlsx('Каталог', [headers, rowOf(secure, '010/2026')]).toString('base64')
  const kamAId = await profileIdOf('kam-a')
  const runPreview = (file, rowTargets) => admin.evaluate(`(async () => {
    const csrf = await (await fetch('/api/csrf')).json()
    const bytes = Uint8Array.from(atob(${JSON.stringify(file)}), (character) => character.charCodeAt(0))
    const mapping = { columns: Object.fromEntries(${JSON.stringify(fields)}.map((field, index) => [field, ${JSON.stringify(headers)}[index]])), rowTargets: ${JSON.stringify(rowTargets)}, transferStatuses: { 'передан': 'Передано' }, unassignedTeamId: ${JSON.stringify(teamA.id)} }
    const form = new FormData()
    form.set('file', new File([bytes], 'uat-import.xlsx'))
    form.set('profile', 'AGREEMENT')
    form.set('sheet', 'Каталог')
    form.set('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }))
    const response = await fetch('/api/imports/preview', { method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: form })
    const created = await response.json()
    if (response.status !== 202) return { status: response.status, body: created }
    return { status: 202, body: await (await fetch('/api/imports/' + created.importId)).json() }
  })()`)
  const byRow = (protocol) => Object.fromEntries(protocol.rows.map((row) => [row.rowNumber, row]))
  try {
    await step(1, 'Временно два активных «КАМ А»', async () => {
      requireStatus(await api(admin, 'PATCH', '/api/admin/crm-profiles/' + kamC.id, { body: { version: kamC.version, displayName: 'КАМ А' } }), 200, 'Переименование')
      return 'КАМ В → КАМ А'
    })
    const first = await step('2–3', 'Предпросмотр: стр. 2 — конфликт, выбор не сделан', async () => {
      const result = await runPreview(fullFile, {})
      assert(result.status === 202, 'Предпросмотр ' + result.status)
      const rows = byRow(result.body)
      assert(rows[2].status === 'CONFLICT' && rows[2].managerCandidates.length === 2, 'Стр. 2: ' + rows[2].status)
      return result.body
    })
    const resolved = await step(4, 'Выбор КАМ из кандидатов в строке', async () => {
      const result = await runPreview(fullFile, { 2: { managerProfileId: kamAId }, 4: { managerProfileId: kamAId }, 5: { managerProfileId: kamAId } })
      const rows = byRow(result.body)
      assert(['CREATE', 'UPDATE'].includes(rows[2].status), 'Стр. 2 после выбора: ' + rows[2].status)
      return result.body
    })
    await step(5, 'Стр. 3: новый вуз без менеджера — «Требует назначения»', async () => {
      const rows = byRow(resolved)
      assert(rows[3].status === 'CREATE', 'Стр. 3: ' + rows[3].status + ' ' + JSON.stringify(rows[3].fieldErrors))
      return rows[3].status
    })
    await step(6, 'Стр. 4 по словарю «Передано», стр. 5 — дубль', async () => {
      const rows = byRow(resolved)
      assert(['CREATE', 'UPDATE'].includes(rows[4].status) && rows[4].newValues.transferStatus === 'Передано' && rows[5].status === 'CONFLICT', 'Стр. 4/5: ' + rows[4].status + ' ' + JSON.stringify(rows[4].fieldErrors) + ' ' + rows[5].status)
      return 'стр. 4 ' + rows[4].status + ' «' + rows[4].newValues.transferStatus + '», стр. 5 ' + rows[5].status + ': ' + Object.values(rows[5].fieldErrors).join('; ')
    })
    await step('7–8', 'Применение стр. 2, 3, 4; контакты разобраны; вуз без менеджера создан', async () => {
      const rows = byRow(resolved)
      const apply = await admin.evaluate(`(async () => {
        const csrf = await (await fetch('/api/csrf')).json()
        const response = await fetch('/api/imports/' + ${JSON.stringify(resolved.id)} + '/apply', { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ version: ${resolved.version}, confirmedRowIds: ${JSON.stringify([rows[2].id, rows[3].id, rows[4].id])}, archiveAgreementIds: [] }) })
        return { status: response.status, body: await response.json() }
      })()`)
      assert(apply.status === 202 && (await waitForJob(admin, '/api/jobs/' + apply.body.jobId)).status === 'SUCCEEDED', 'Применение')
      const contacts = await contactsOf(leader, organization.id)
      const created = (await listAll(leader, '/api/organizations?q=' + encodeURIComponent(newName), 'Новый вуз'))[0]
      assert(['Сидорова Анна (UAT)', 'Кузнецов Пётр (UAT)'].every((name) => contacts.some((item) => item.name === name)) && created?.requiresAssignment, 'Контакты или новый вуз')
      return 'контакты: ' + contacts.map((item) => item.name).join(', ') + '; новый вуз требует назначения'
    })
    await step(9, 'Короткий реестр: отсутствующая запись предложена к архивированию', async () => {
      const result = await runPreview(shortFile, { 2: { managerProfileId: kamAId } })
      assert(result.body.missingRecords.some((item) => item.contractNumber === '012/2026'), 'Нет отсутствующей записи')
      return result.body.missingRecords.map((item) => item.productName + ' ' + item.contractNumber).join('; ')
    })
  } finally {
    await step(10, 'Возврат имени КАМ В; стр. 2 без изменений', async () => {
      const current = await adminProfile('kam-c')
      requireStatus(await api(admin, 'PATCH', '/api/admin/crm-profiles/' + current.id, { body: { version: current.version, displayName: 'КАМ В' } }), 200, 'Возврат имени')
      const result = await runPreview(fullFile, {})
      const rows = byRow(result.body)
      assert(rows[2].status === 'UNCHANGED', 'Стр. 2: ' + rows[2].status)
      return 'стр. 2 UNCHANGED'
    })
  }
})

scenario('UAT-ИБ-05', 'Журнал значимых событий', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const admin = await session('admin')
  const leader = await session('leader')
  const university = await kamView('kam-a', demo.universityA)
  const work = await demoWorkOf(kamA, university.id)
  await step(1, 'Комментарий КАМ А в истории без правки', async () => {
    const card = await interactionOf(kamA, work.id)
    const result = requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/comments', { body: { version: card.version, stageId: card.currentStageId, text: 'UAT ИБ-05: запись для журнала ' + nonce } }), 200, 'Комментарий')
    assert(result.event.actorDisplayName === 'КАМ А', 'Автор')
    return 'COMMENTED, КАМ А'
  })
  await step('2–3', 'Скачивания файла и отчёта в журнале', async () => {
    const clean = await uploadNamed(kamA, await interactionOf(kamA, work.id), 'uat-иб-05.pdf', 'application/pdf', pdfBase64('иб-05 ' + nonce), 'OTHER')
    await api(kamA, 'GET', '/api/attachments/' + clean.id + '/download', { binary: true })
    const report = await reportFile(kamA, { kind: 'PORTFOLIO', format: 'XLSX', from: today, to: today, filters: {}, columns: [] })
    const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=DOWNLOAD&actor=' + encodeURIComponent('КАМ А') + '&from=' + today + '&to=' + today + '&page=0&size=50'), 200, 'Журнал')
    assert(audit.items.some((item) => item.action === 'ATTACHMENT_DOWNLOADED' && item.objectId === clean.id) && audit.items.some((item) => item.action === 'REPORT_DOWNLOADED' && item.objectId === report.jobId), 'Скачивания не записаны')
    return 'ATTACHMENT_DOWNLOADED, REPORT_DOWNLOADED'
  })
  await step(4, 'Журнал профиля КАМ В', async () => {
    const events = requireStatus(await api(admin, 'GET', '/api/admin/crm-profiles/' + await profileIdOf('kam-c') + '/events'), 200, 'Журнал профиля')
    assert(events.length > 0 && events[0].actorDisplayName, 'Пусто')
    return 'записей ' + events.length
  })
  const universityC = await leaderView(demo.universityC)
  await step(5, 'История назначений Университета C', async () => {
    const events = requireStatus(await api(leader, 'GET', '/api/organizations/' + universityC.id + '/assignment-events'), 200, 'История')
    assert(events.every((event) => event.actorDisplayName && event.occurredAt), 'Нет автора')
    return 'записей ' + events.length
  })
  await step('6–7', 'Перенос в команду Б и обратно — в журнале', async () => {
    const teams = requireStatus(await api(admin, 'GET', '/api/admin/teams'), 200, 'Команды')
    const teamA = teams.find((team) => team.name === 'Команда А')
    const teamB = teams.find((team) => team.name === 'Команда Б')
    const organizations = requireStatus(await api(admin, 'GET', '/api/admin/organizations?q=' + encodeURIComponent('Университет C') + '&page=0&size=10'), 200, 'Организации')
    const target = organizations.items.find((item) => item.id === universityC.id)
    const moved = requireStatus(await api(admin, 'PATCH', '/api/admin/organizations/' + target.id + '/team', { body: { teamId: teamB.id, version: target.version } }), 200, 'Перенос')
    requireStatus(await api(admin, 'PATCH', '/api/admin/organizations/' + target.id + '/team', { body: { teamId: teamA.id, version: moved.version } }), 200, 'Возврат')
    const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=ORGANIZATION&from=' + today + '&to=' + today + '&page=0&size=50'), 200, 'Журнал')
    const transfers = audit.items.filter((item) => item.action === 'ORGANIZATION_TEAM_CHANGED' && item.objectId === target.id)
    assert(transfers.length >= 2 && transfers[0].details, 'Переносов в журнале ' + transfers.length)
    return transfers.slice(0, 2).map((item) => item.details).join(' | ')
  })
  await step(8, 'История запусков синхронизации с автором', async () => {
    const runs = requireStatus(await api(admin, 'GET', '/api/admin/sources/MOODLE/runs?page=0&size=10'), 200, 'Запуски')
    const items = runs.items ?? runs
    assert(items.length > 1 && items.every((run) => run.createdByName ?? run.actorDisplayName ?? run.startedByName), 'Нет автора')
    return 'запусков ' + items.length
  })
  await step(9, 'События Keycloak: входы kam-a без паролей', async () => {
    const events = keycloakEvents(keycloakUserId('kam-a'))
    assert(events.some((event) => event.type === 'LOGIN') && !JSON.stringify(events).includes(demoPassword()), 'Нет входов')
    return 'событий ' + events.length + ', типы: ' + [...new Set(events.map((event) => event.type))].join(',')
  })
  await step(10, 'Единый экран журнала с фильтрами и выгрузкой', async () => {
    await screenTexts(admin, '/#/admin/journal', ['Журнал администратора и безопасности'], 'Журнал')
    const csv = await api(admin, 'GET', '/api/admin/audit-events/export?format=CSV&from=' + today + '&to=' + today, { text: true })
    assert(csv.status === 200 && csv.text.length > 0, 'Выгрузка ' + csv.status)
    return 'экран и CSV'
  })
})

scenario('UAT-ИБ-06', 'Ошибки: по-русски, с кодом для поддержки', {}, async ({ step }) => {
  const kamA = await session('kam-a')
  const kamB = await session('kam-b')
  const university = await kamView('kam-a', demo.universityA)
  const work = await createInteraction(kamA, university.id, 'UAT-ИБ-06 ' + nonce, { nextAction: 'исходный шаг' })
  await step(1, 'TXT: причина по-русски без техники', async () => {
    const response = await upload(kamA, work.id, { stageId: work.currentStageId, name: 'uat-text.txt', type: 'text/plain', base64: Buffer.from('x').toString('base64') })
    requireError(response, 400, 'TXT')
    assert(!/Exception|java\.|\/app\//.test(JSON.stringify(response.body)), 'Техника в ответе')
    return response.body.message
  })
  let requestId
  await step(3, 'Чужая карточка: сообщение, код и Request ID', async () => {
    await screenTexts(kamB, '/#/organizations/' + university.id, ['Не удалось открыть карточку вуза', 'Подробнее для поддержки'], 'Экран ошибки')
    const text = await kamB.evaluate('document.body.textContent')
    requestId = text.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)?.[0]
    assert(requestId, 'Нет Request ID на экране')
    return 'Request ID на экране'
  })
  await step(4, 'Ответ API: только code, message, requestId', async () => {
    const response = await api(kamB, 'GET', '/api/organizations/' + university.id)
    const keys = Object.keys(response.body).sort().join()
    assert(response.status === 404 && keys === 'code,message,requestId' && russian(response.body.message), 'Ключи ' + keys)
    return keys
  })
  await step(5, 'Конфликт плана: понятное сообщение', async () => {
    requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + work.id, { body: { version: work.version, nextAction: 'вкладка 2' } }), 200, 'Вкладка 2')
    const stale = await api(kamA, 'PATCH', '/api/interactions/' + work.id, { body: { version: work.version, nextAction: 'вкладка 1' } })
    requireError(stale, 409, 'Конфликт')
    return stale.body.message
  })
  await step(6, 'unprofiled: ожидает активации, код CRM_PROFILE_PENDING', async () => {
    const page = await login('unprofiled')
    await page.waitFor(() => page.evaluate("document.body.textContent.includes('Профиль ожидает активации администратором')"), 'Нет экрана ожидания')
    const me = await api(page, 'GET', '/api/me')
    requireError(me, 403, 'Ожидание')
    assert(me.body.code === 'CRM_PROFILE_PENDING', me.body.code)
    return me.body.code
  })
  await step(8, 'Request ID из шага 3 найден в журнале backend', async () => {
    await pause(500)
    const line = backendLog('10m').split(/\r?\n/).find((item) => item.includes('requestId=' + requestId))
    assert(line && line.includes('code=NOT_FOUND') && !line.includes(demoPassword()), 'Запись не найдена')
    return line.slice(line.indexOf('API error')).replace(/user=\S+/, 'user=<sub>').slice(0, 160)
  })
  await step(9, 'Встроенная справка: код NOT_FOUND из шага 4 описан', async () => {
    await screenTexts(kamB, '/#/help', ['NOT_FOUND', 'Request ID'], 'Справка: коды ошибок')
    return 'Код и Request ID найдены в справке'
  })
})

scenario('UAT-ИБ-07', 'Запрос субъекта ПДн', { manual: [5, 9] }, async ({ step }) => {
  const kamA = await session('kam-a')
  const admin = await session('admin')
  const university = await kamView('kam-a', demo.universityA)
  const name = 'Тестовый Субъект ' + nonce
  const email = 'subject.uat.' + nonce + '@example.org'
  const phoneDigits = String(Date.now()).slice(-7)
  const phone = '+7 000 ' + phoneDigits
  const setup = await step(1, 'Контакт, упоминание и документ субъекта', async () => {
    const contact = requireStatus(await api(kamA, 'POST', '/api/organizations/' + university.id + '/contacts', { body: { name, email, phone } }), 201, 'Контакт')
    const work = await createInteraction(kamA, university.id, 'UAT-ИБ-07 ' + nonce)
    requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/comments', { body: { version: work.version, stageId: work.currentStageId, text: 'Разговор: ' + name } }), 200, 'Комментарий')
    const file = await uploadNamed(kamA, await interactionOf(kamA, work.id), 'uat-subject-' + nonce + '.pdf', 'application/pdf', pdfBase64('subject'), 'OTHER')
    return { contact, work, file }
  })
  await step(2, 'Поиск находит контакт, упоминание и файл', async () => {
    const found = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/search', { body: { name, email, phone: '8000' + phoneDigits, otherSpellings: 'subject-' + nonce } }), 200, 'Поиск')
    assert(found.contacts.some((item) => item.id === setup.contact.id) && found.mentions.length > 0 && found.attachments.some((item) => item.id === setup.file.id), 'Найдено не всё')
    return 'контактов ' + found.contacts.length + ', упоминаний ' + found.mentions.length + ', файлов ' + found.attachments.length
  })
  await step(3, 'Выгрузка сведений одной кнопкой (PDF и JSON)', async () => {
    const json = await api(admin, 'POST', '/api/admin/personal-data/export?format=JSON', { body: { email } })
    const pdf = await api(admin, 'POST', '/api/admin/personal-data/export?format=PDF', { body: { email }, binary: true })
    assert(json.status === 200 && pdf.status === 200 && pdf.size > 0, json.status + '/' + pdf.status)
    return 'JSON и PDF'
  })
  await step(4, 'Уточнение телефона с историей', async () => {
    const found = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/search', { body: { email } }), 200, 'Поиск')
    const contact = found.contacts[0]
    const updated = requireStatus(await api(admin, 'PATCH', '/api/admin/personal-data/contacts/' + contact.id, { body: { version: contact.version, name: contact.name, position: contact.position, email, phone: '+7 000 000-00-98' } }), 200, 'Уточнение')
    const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=PERSONAL_DATA&from=' + today + '&to=' + today + '&page=0&size=50'), 200, 'Журнал')
    const entry = audit.items.find((item) => item.action === 'CONTACT_RECTIFIED' && item.objectId === contact.id)
    const card = (await contactsOf(kamA, university.id)).find((item) => item.id === contact.id)
    assert(updated.phone === '+7 000 000-00-98' && card.phone === updated.phone && entry?.details?.includes('000-00-98') && entry.actorDisplayName === 'Администратор', 'Нет записи в журнале: ' + entry?.details)
    return updated.phone + '; журнал: ' + entry.details
  })
  await step(6, 'Ограничение обработки', async () => {
    const found = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/search', { body: { email } }), 200, 'Поиск')
    const restricted = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/contacts/' + found.contacts[0].id + '/restriction', { body: { version: found.contacts[0].version, restricted: true } }), 200, 'Ограничение')
    requireError(await api(kamA, 'POST', '/api/interactions', { body: { organizationId: university.id, title: 'UAT-ИБ-07 ограничен', contactIds: [setup.contact.id] } }), 400, 'Связь с новой работой', 'contactIds')
    const history = await eventsOf(kamA, setup.work.id)
    assert(restricted.status === 'RESTRICTED' && history.length >= 2, 'Ограничение')
    return 'RESTRICTED, не связывается, история читается'
  })
  await step(7, 'Обезличивание с удалением вложения', async () => {
    const result = requireStatus(await api(admin, 'POST', '/api/admin/personal-data/anonymization', { body: { subject: { name, email, phone: '+7 000 000-00-98', otherSpellings: 'subject-' + nonce }, contactIds: [setup.contact.id], profileIds: [], attachmentIds: [setup.file.id] } }), 200, 'Обезличивание')
    const contact = (await contactsOf(kamA, university.id)).find((item) => item.id === setup.contact.id)
    const history = await eventsOf(kamA, setup.work.id)
    const download = await api(kamA, 'GET', '/api/attachments/' + setup.file.id + '/download', { binary: true })
    assert(contact.name === 'Контакт обезличен' && !JSON.stringify(history).includes(name) && download.status === 404 && history.length >= 2, 'Обезличивание неполное')
    return 'контакт обезличен, файл удалён (' + result.attachmentsDeleted + '), история сохранена'
  })
  await step(8, 'Журнал действий по запросу без условий поиска', async () => {
    const audit = requireStatus(await api(admin, 'GET', '/api/admin/audit-events?category=PERSONAL_DATA&from=' + today + '&to=' + today + '&page=0&size=100'), 200, 'Журнал')
    const actions = new Set(audit.items.map((item) => item.action))
    assert(['SUBJECT_SEARCHED', 'SUBJECT_EXPORTED', 'CONTACT_RECTIFIED', 'CONTACT_RESTRICTED', 'SUBJECT_ANONYMIZED'].every((action) => actions.has(action)) && !JSON.stringify(audit.items).includes(email), 'Журнал')
    return [...actions].join(', ')
  })
})

scenario('UAT-ИБ-08', 'Сроки хранения ПДн и файлов отчётов', { blocked: 'шаги 3–5 требуют файлов, контактов и профилей старше срока хранения (7 дней, 3 года, 1 год) — на стенде таких данных нет; шаги 1 и 7 — сверка документа ИБ, не автоматизированы' }, async ({ step }) => {
  const admin = await session('admin')
  await step(2, 'Экран и настройки «Сроки хранения»', async () => {
    const policy = requireStatus(await api(admin, 'GET', '/api/admin/retention'), 200, 'Сроки')
    await screenTexts(admin, '/#/admin/retention', ['Сроки хранения'], 'Экран')
    return 'файлы отчётов ' + policy.reportFilesDays + ' дн., контакты ' + policy.inactiveContactsDays + ' дн., профили ' + policy.dismissedProfilesDays + ' дн., журнал ' + policy.auditEventsDays + ' дн., расписание ' + policy.schedule
  })
  await step('3–5', 'Ручной запуск: лишнего не удалено', async () => {
    const run = requireStatus(await api(admin, 'POST', '/api/admin/retention/run'), 200, 'Запуск')
    assert(run.contactsAnonymized === 0 && run.profilesAnonymized === 0, JSON.stringify(run))
    return 'удалено файлов ' + run.reportFilesDeleted + ', обезличено контактов ' + run.contactsAnonymized + ', профилей ' + run.profilesAnonymized
  })
  await step(6, 'Срок хранения событий Keycloak', async () => {
    const realm = keycloakRealm()
    return 'eventsEnabled ' + realm.eventsEnabled + ', eventsExpiration ' + (realm.eventsExpiration ?? 'не задан')
  })
})

scenario('UAT-ОБЩ-07', 'Единая картина по всем командам', {}, async ({ step }) => {
  const admin = await session('admin')
  await step(1, 'Роль «Руководство (только чтение)» в профилях', async () => {
    await screenTexts(admin, '/#/admin', ['Профили CRM'], 'Профили')
    const html = await admin.evaluate('document.documentElement.innerHTML')
    const inBundle = html.includes('Руководство') || (await admin.evaluate("fetch([...document.scripts].map((script) => script.src).find(Boolean)).then((response) => response.text()).then((text) => text.includes('Руководство (только чтение)'))"))
    assert(inBundle, 'Нет роли в интерфейсе')
    return 'MANAGEMENT доступна'
  })
  const result = await step('3–4', 'Пользователь руководства: все команды, только чтение, сводка', async () => sections.management())
  await step(6, 'Администратор бизнес-данных не видит', async () => {
    const organizations = requireStatus(await api(admin, 'GET', '/api/organizations?page=0&size=10'), 200, 'Вузы')
    assert(organizations.total === 0, 'Вузов ' + organizations.total)
    return '0'
  })
  return result
})

scenario('UAT-ОБЩ-11', 'Что передано по продукту и чего ждём', { gaps: ['шаг 7, замечание, BL-34 (P3): одностраничной сводки по вузу в PDF нет'] }, async ({ step }) => {
  const kamA = await session('kam-a')
  const university = await kamView('kam-a', demo.universityA)
  const products = await listAll(kamA, '/api/products', 'Продукты')
  const secure = products.find((item) => item.name === demo.secureProduct)
  const cloud = products.find((item) => item.name === demo.cloudProduct)
  const work = await createInteraction(kamA, university.id, 'UAT-ОБЩ-11 ' + nonce, { productIds: [secure.id, cloud.id] })
  const scan = await uploadNamed(kamA, work, 'uat-скан.pdf', 'application/pdf', pdfBase64('скан'), 'SIGNED_SCAN')
  let card = await interactionOf(kamA, work.id)
  const agreementOf = (value, product) => value.productAgreements.find((item) => item.productId === product.id)
  card = await step(2, 'Договор 007/2026 по защищённой связи', async () => {
    const saved = requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + work.id + '/product-agreements/' + agreementOf(card, secure).id, { body: { version: card.version, contract: { contractNumber: '007/2026', licenseSigned: true, licenseExpiryYear: 2027, scanAttachmentId: scan.id } } }), 200, 'Договор')
    assert(agreementOf(saved, secure).contractNumber === '007/2026' && !agreementOf(saved, cloud).contractNumber, 'Договор')
    return saved
  })
  card = await step(3, 'Три отметки передачи', async () => requireStatus(await api(kamA, 'PATCH', '/api/interactions/' + work.id + '/product-agreements/' + agreementOf(card, secure).id, { body: { version: card.version, transfers: [{ kind: 'MATERIALS', status: 'TRANSFERRED', transferredOn: today, attachmentId: null }, { kind: 'LICENSE', status: 'TRANSFERRED', transferredOn: today, attachmentId: scan.id }, { kind: 'DOCUMENTATION', status: 'NOT_TRANSFERRED', transferredOn: null, attachmentId: null }] } }), 200, 'Отметки'))
  await step(4, '«Ждём вуз» и отбор', async () => {
    requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/flags', { body: { version: card.version, waitingOn: 'UNIVERSITY', waitingNote: 'Ждём документацию от вуза', problem: null, riskLevel: null, riskReason: null } }), 200, 'Ожидание')
    const list = await listAll(kamA, '/api/interactions?flag=WAITING_UNIVERSITY&q=' + encodeURIComponent('UAT-ОБЩ-11 ' + nonce), 'Отбор')
    const report = requireStatus(await preview(kamA, { kind: 'PORTFOLIO', from: today, to: today, filters: { organizationIds: [university.id], flags: ['WAITING_UNIVERSITY'] }, columns: ['INTERACTION', 'WAITING'] }), 200, 'Отчёт')
    assert(list.length === 1 && report.items.some((row) => row.INTERACTION === work.title), 'Отбор ожидания')
    return 'найдена в списке и отчёте'
  })
  await step(5, 'Ответ виден в карточке сразу', async () => {
    await screenTexts(kamA, '/#/organizations/' + university.id + '/' + work.id, ['007/2026', 'Передано частично', 'Ждём вуз'], 'Карточка')
    return 'договор, статус передачи и ожидание на экране'
  })
  await step(6, 'Колонки договора, лицензии и передачи в портфеле', async () => {
    const report = requireStatus(await preview(kamA, { kind: 'PORTFOLIO', from: today, to: today, filters: { organizationIds: [university.id] }, columns: ['INTERACTION', 'CONTRACT_NUMBER', 'LICENSE_SIGNED', 'LICENSE_EXPIRY_YEAR', 'TRANSFER_STATUS'] }), 200, 'Отчёт')
    const row = report.items.find((item) => item.INTERACTION === work.title)
    assert(row.CONTRACT_NUMBER.includes('007/2026') && row.TRANSFER_STATUS.includes('Передано частично'), JSON.stringify(row))
    return row.CONTRACT_NUMBER + '; ' + row.TRANSFER_STATUS
  })
})

scenario('UAT-ИНТ-01', 'Сверка чисел CRM с Moodle', { phase: 'lms', manual: [1, 2, 3] }, async ({ step }) => {
  const kamA = await session('kam-a')
  const admin = await session('admin')
  const university = await kamView('kam-a', demo.universityA)
  const work = await demoWorkOf(kamA, university.id)
  const snapshots = await step(4, 'Блок «Обучение (LMS)»: 6 / 3 / 3 / 0, групп 2, преподавателей 1', async () => {
    const value = requireStatus(await api(kamA, 'GET', '/api/interactions/' + work.id + '/learning-snapshots'), 200, 'Снимки')
    const items = value.items ?? value
    const java = items.find((item) => item.courseName === 'Демо: Java-разработчик')
    assert(java && java.participants === 6 && java.completed === 3 && java.notCompleted === 3 && java.unknown === 0 && java.groupsCount === 2 && java.teachers === 1, JSON.stringify(java))
    return java
  })
  await step(5, 'Событие истории с теми же числами', async () => {
    const history = await eventsOf(kamA, work.id)
    const event = history.find((item) => item.comment?.startsWith('Данные LMS: курс «Демо: Java-разработчик»'))
    assert(event && event.comment.includes('6') && event.comment.includes('3'), 'Нет события')
    return event.comment.slice(0, 120)
  })
  await step(6, 'Обновление без изменений не создаёт события', async () => {
    const before = (await eventsOf(kamA, work.id)).length
    const refresh = requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/learning-snapshots/sync'), 200, 'Обновление')
    const after = (await eventsOf(kamA, work.id)).length
    assert(before === after, before + ' → ' + after)
    return JSON.stringify(refresh).slice(0, 100)
  })
  const month = today.slice(0, 8) + '01'
  await step('7–8', 'DEMAND: обучающиеся 6, потоки 1, дата снимка в заголовке', async () => {
    const report = requireStatus(await preview(kamA, { kind: 'DEMAND', from: month, to: today, filters: {}, columns: ['PROGRAM', 'PARTICIPANTS', 'PARALLEL_RUNS'] }), 200, 'DEMAND')
    const row = report.items.find((item) => item.PROGRAM === demo.digitalProgram)
    const title = report.columns.find((column) => column.id === 'PARTICIPANTS').title
    assert(row.PARTICIPANTS === 6 && row.PARALLEL_RUNS === 1 && /\d{2}\.\d{2}\.\d{4}/.test(title), JSON.stringify(row) + ' ' + title)
    return 'обучающихся 6, потоков 1; «' + title + '»'
  })
  await step(9, 'Период до начала потока: нет данных; сентябрь: поток 1', async () => {
    const august = requireStatus(await preview(kamA, { kind: 'DEMAND', from: '2026-08-01', to: '2026-08-31', filters: {}, columns: ['PROGRAM', 'PARTICIPANTS', 'PARALLEL_RUNS'] }), 200, 'Август')
    const september = requireStatus(await preview(kamA, { kind: 'DEMAND', from: '2026-09-01', to: '2026-09-30', filters: {}, columns: ['PROGRAM', 'PARTICIPANTS', 'PARALLEL_RUNS'] }), 200, 'Сентябрь')
    const a = august.items.find((item) => item.PROGRAM === demo.digitalProgram)
    const s = september.items.find((item) => item.PROGRAM === demo.digitalProgram)
    assert((a?.PARALLEL_RUNS ?? 0) === 0 && (a?.PARTICIPANTS ?? null) === null && s.PARALLEL_RUNS === 1 && s.PARTICIPANTS === 6, JSON.stringify([a, s]))
    return 'август: ' + (a ? a.PARTICIPANTS + '/' + a.PARALLEL_RUNS : 'нет строки') + '; сентябрь: ' + s.PARTICIPANTS + '/' + s.PARALLEL_RUNS
  })
  await step(10, 'Список сопоставлений для сверки', async () => {
    const mappings = requireStatus(await api(admin, 'GET', '/api/admin/source-mappings'), 200, 'Сопоставления')
    const course = mappings.find((item) => item.kind === 'COURSE' && item.organizationName === demo.universityA)
    assert(course && course.participants === 6 && course.runStartsOn, 'Сопоставление')
    return course.label + ' → ' + course.organizationName + ', ' + course.programName + ', ' + course.runStartsOn + '…' + course.runEndsOn
  })
  return snapshots
})

scenario('UAT-ИНТ-03', 'Курс без отслеживания завершения: «нет данных»', { phase: 'lms', manual: [1, 7] }, async ({ step }) => {
  const kamB = await session('kam-b')
  const university = await kamView('kam-b', demo.universityB)
  const work = (await organizationWork(kamB, university.id)).find((item) => item.title.includes('Обучение в LMS'))
  await step(2, 'Поток Б1: 4, завершили — нет данных, неизвестно 4, преподавателей 0', async () => {
    const value = requireStatus(await api(kamB, 'GET', '/api/interactions/' + work.id + '/learning-snapshots'), 200, 'Снимки')
    const group = (value.items ?? value).find((item) => item.groupName === 'Поток Б1')
    assert(group.participants === 4 && group.completed === null && group.notCompleted === null && group.unknown === 4 && group.teachers === 0, JSON.stringify(group))
    return '4 / null / null / 4 / 0'
  })
  await step(3, 'История: «завершили: нет данных»', async () => {
    const history = await eventsOf(kamB, work.id)
    const event = history.find((item) => item.comment?.startsWith('Данные LMS'))
    assert(event.comment.includes('нет данных'), event.comment)
    return event.comment.slice(0, 120)
  })
  await step(4, 'DEMAND сентябрь: заявки 4, обучающиеся 4, потоки 1', async () => {
    const report = requireStatus(await preview(kamB, { kind: 'DEMAND', from: '2026-09-01', to: '2026-09-30', filters: {}, columns: ['PROGRAM', 'APPLICATIONS', 'PARTICIPANTS', 'PARALLEL_RUNS'] }), 200, 'DEMAND')
    const row = report.items.find((item) => item.PROGRAM === 'Демо-программа: анализ данных')
    assert(row.APPLICATIONS === 4 && row.PARTICIPANTS === 4 && row.PARALLEL_RUNS === 1, JSON.stringify(row))
    return '4 / 4 / 1'
  })
  await step(5, 'Июль–сентябрь: цифровой университет заявки 14, Moodle — нет данных', async () => {
    const report = requireStatus(await preview(kamB, { kind: 'DEMAND', from: '2026-07-01', to: '2026-09-30', filters: {}, columns: ['PROGRAM', 'APPLICATIONS', 'PARTICIPANTS', 'PARALLEL_RUNS'] }), 200, 'DEMAND')
    const row = report.items.find((item) => item.PROGRAM === demo.digitalProgram)
    assert(row.APPLICATIONS === 14 && row.PARTICIPANTS === null && row.PARALLEL_RUNS === null, JSON.stringify(row))
    return '14 / null / null'
  })
  await step(6, 'XLSX — «нет данных», JSON — null', async () => {
    const request = { kind: 'DEMAND', from: '2026-07-01', to: '2026-09-30', filters: {}, columns: ['PROGRAM', 'APPLICATIONS', 'PARTICIPANTS', 'PARALLEL_RUNS'] }
    const xlsxFile = await reportFile(kamB, { ...request, format: 'XLSX' })
    const json = JSON.parse((await reportFile(kamB, { ...request, format: 'JSON' })).buffer.toString('utf8'))
    const rows = json.rows ?? json.items ?? []
    const row = rows.find((item) => JSON.stringify(item).includes(demo.digitalProgram))
    assert(zipText(xlsxFile.buffer).includes('нет данных') && row && Object.values(row).includes(null), 'Файлы')
    return 'XLSX «нет данных», JSON null'
  })
})

scenario('UAT-КАМ-17', 'Обучение преподавателей отдельно от студентов', { phase: 'lms', notes: ['замечание к тексту сценария: документ, приложенный к комментарию шага 2, уже связан с событием и для записи ПК не выбирается (вложение связывается с одним событием); запись ПК сама создаёт комментарий с документом, поэтому шаг 2 избыточен — для записи использован второй файл'] }, async ({ step }) => {
  const kamA = await session('kam-a')
  const admin = await session('admin')
  const state = readState()
  assert(state.kam17, 'Подготовка КАМ-17 не выполнена в фазе CRM')
  const workId = state.kam17.workId
  await step(1, 'Работа с программой: курс Java 6, преподавателей 1', async () => {
    requireStatus(await api(kamA, 'POST', '/api/interactions/' + workId + '/learning-snapshots/sync'), 200, 'Обновление')
    const value = requireStatus(await api(kamA, 'GET', '/api/interactions/' + workId + '/learning-snapshots'), 200, 'Снимки')
    const java = (value.items ?? value).find((item) => item.courseName === 'Демо: Java-разработчик')
    assert(java && java.participants === 6 && java.teachers === 1, JSON.stringify(java))
    return '6 / 1'
  })
  await step('2', 'Документ, уже приложенный к комментарию, для записи ПК не выбирается', async () => {
    const card = await interactionOf(kamA, workId)
    const bound = await api(kamA, 'POST', '/api/interactions/' + workId + '/teacher-trainings', { body: { version: card.version, stageId: card.currentStageId, trainedOn: today, courseName: 'Работа с продуктом', enrolledCount: 5, completedCount: 4, attachmentId: state.kam17.commentedAttachmentId } })
    requireError(bound, 400, 'Связанный документ')
    return '400: ' + bound.body.message
  })
  await step('3', 'Запись ПК с документом отдельно от студентов', async () => {
    const card = await interactionOf(kamA, workId)
    requireStatus(await api(kamA, 'POST', '/api/interactions/' + workId + '/teacher-trainings', { body: { version: card.version, stageId: card.currentStageId, trainedOn: today, courseName: 'Работа с продуктом', enrolledCount: 5, completedCount: 4, attachmentId: state.kam17.attachmentId, nextCycleOn: shiftDay(today, 1000), remind: true } }), 201, 'Запись ПК')
    const trainings = requireStatus(await api(kamA, 'GET', '/api/interactions/' + workId + '/teacher-trainings'), 200, 'Список')
    assert(trainings.some((item) => item.courseName === 'Работа с продуктом' && item.attachmentId === state.kam17.attachmentId), 'Нет записи')
    return 'записано 5, завершили 4, документ приложен'
  })
  const mappings = requireStatus(await api(admin, 'GET', '/api/admin/source-mappings'), 200, 'Сопоставления')
  const course = mappings.find((item) => item.kind === 'COURSE' && item.organizationName === demo.universityA)
  try {
    await step('4–5', 'Поток «обучение преподавателей» исключён из обучающихся и DEMAND', async () => {
      requireStatus(await api(admin, 'PUT', '/api/admin/source-mappings/' + course.id, { body: { version: course.version, organizationId: course.organizationId, programId: course.programId, runStartsOn: course.runStartsOn, runEndsOn: course.runEndsOn, runKind: 'TEACHERS' } }), 200, 'Вид потока')
      requireStatus(await api(kamA, 'POST', '/api/interactions/' + workId + '/learning-snapshots/sync'), 200, 'Обновление')
      const report = requireStatus(await preview(kamA, { kind: 'DEMAND', from: today.slice(0, 8) + '01', to: today, filters: {}, columns: ['PROGRAM', 'PARTICIPANTS'] }), 200, 'DEMAND')
      const row = report.items.find((item) => item.PROGRAM === demo.digitalProgram)
      assert(!row || row.PARTICIPANTS === null, 'Преподаватели посчитаны: ' + JSON.stringify(row))
      return 'DEMAND: ' + (row ? row.PARTICIPANTS : 'нет строки')
    })
  } finally {
    const current = requireStatus(await api(admin, 'GET', '/api/admin/source-mappings'), 200, 'Сопоставления').find((item) => item.id === course.id)
    requireStatus(await api(admin, 'PUT', '/api/admin/source-mappings/' + course.id, { body: { version: current.version, organizationId: course.organizationId, programId: course.programId, runStartsOn: course.runStartsOn, runEndsOn: course.runEndsOn, runKind: 'STUDENTS' } }), 200, 'Возврат вида потока')
    requireStatus(await api(kamA, 'POST', '/api/interactions/' + workId + '/learning-snapshots/sync'), 200, 'Обновление')
  }
  await step(6, 'Неизвестное — «нет данных», не 0', async () => {
    const report = requireStatus(await preview(kamA, { kind: 'DEMAND', from: today.slice(0, 8) + '01', to: today, filters: {}, columns: ['PROGRAM', 'PARTICIPANTS'] }), 200, 'DEMAND')
    const row = report.items.find((item) => item.PROGRAM === demo.digitalProgram)
    assert(row.PARTICIPANTS === 6, 'После возврата ' + row.PARTICIPANTS)
    return 'после возврата 6'
  })
  await step(7, 'Следующий цикл ПК в плане и напоминаниях', async () => {
    const card = await interactionOf(kamA, workId)
    assert(card.nextAction?.includes('Следующий цикл повышения квалификации') && card.nextActionAt?.startsWith(shiftDay(today, 1000).slice(0, 4)), 'План ' + card.nextAction)
    return card.nextAction + ' ' + card.nextActionAt.slice(0, 10)
  })
  await step(8, 'В блоке LMS и истории нет ФИО', async () => {
    const history = JSON.stringify(await eventsOf(kamA, workId))
    const snapshots = JSON.stringify(requireStatus(await api(kamA, 'GET', '/api/interactions/' + workId + '/learning-snapshots'), 200, 'Снимки'))
    assert(!/Учащийся Демо|Преподаватель Демо/.test(history + snapshots), 'Найдены ФИО')
    return 'нет'
  })
})

const prepareLms = async () => {
  const kamA = await session('kam-a')
  const university = await kamView('kam-a', demo.universityA)
  const programs = await listAll(kamA, '/api/programs', 'Программы')
  const program = programs.find((item) => item.name === demo.digitalProgram)
  const work = await createInteraction(kamA, university.id, 'UAT-КАМ-17 ' + nonce, { programId: program.id })
  const commented = await uploadNamed(kamA, work, 'uat-удостоверение-пк.pdf', 'application/pdf', pdfBase64('пк ' + nonce), 'QUALIFICATION')
  const card = await interactionOf(kamA, work.id)
  requireStatus(await api(kamA, 'POST', '/api/interactions/' + work.id + '/comments', { body: { version: card.version, stageId: card.currentStageId, text: '5 преподавателей записаны на курс работы с продуктом, 4 завершили', attachmentIds: [commented.id] } }), 200, 'Комментарий')
  const free = await uploadNamed(kamA, await interactionOf(kamA, work.id), 'uat-удостоверение-пк-2.pdf', 'application/pdf', pdfBase64('пк 2 ' + nonce), 'QUALIFICATION')
  writeState({ kam17: { workId: work.id, commentedAttachmentId: commented.id, attachmentId: free.id, preparedAt: new Date().toISOString() } })
}

const runScenario = async (item) => {
  const steps = []
  const step = async (number, text, fn) => {
    try {
      const value = await fn()
      steps.push({ number: String(number), text, ok: true, value: typeof value === 'string' ? value : undefined })
      return value
    } catch (error) {
      steps.push({ number: String(number), text, ok: false, message: failureText(error) })
      throw Object.assign(new Error(failureText(error)), { stepNumber: String(number) })
    }
  }
  const started = Date.now()
  try {
    await item.run({ step })
    const status = item.gaps.length ? 'Не пройден' : item.blocked ? 'Заблокирован' : 'Пройден'
    return { id: item.id, title: item.title, status, deviation: [...item.gaps, ...(item.blocked ? [item.blocked] : []), ...item.notes].join('; '), manual: item.manual, steps, seconds: Math.round((Date.now() - started) / 1000) }
  } catch (error) {
    const failed = steps.find((entry) => !entry.ok)
    return { id: item.id, title: item.title, status: 'Не пройден', deviation: (failed ? 'шаг ' + failed.number + ': ' + failed.message : failureText(error)) + (item.gaps.length ? '; ' + item.gaps.join('; ') : ''), manual: item.manual, steps, seconds: Math.round((Date.now() - started) / 1000) }
  }
}

try {
  await connect()
  const phase = process.env.UAT_PHASE || 'crm'
  const only = process.env.UAT_ONLY ? process.env.UAT_ONLY.split(',') : null
  const selected = scenarios.filter((item) => item.phase === phase && (!only || only.includes(item.id)))
  if (phase === 'crm' && (!only || only.includes('UAT-КАМ-17'))) {
    await prepareLms()
  }
  const results = []
  for (const item of selected) {
    results.push(await runScenario(item))
  }
  const output = { phase, nonce, today, results, summary: Object.fromEntries(['Пройден', 'Не пройден', 'Заблокирован'].map((status) => [status, results.filter((item) => item.status === status).length])) }
  console.log(JSON.stringify(output, null, 1))
  if (process.env.UAT_RESULT) {
    fs.writeFileSync(process.env.UAT_RESULT, JSON.stringify(output, null, 1))
  }
  process.exitCode = results.every((item) => item.status === 'Пройден') ? 0 : 1
} catch (error) {
  console.log(JSON.stringify({ uat: 'failed', message: failureText(error) }))
  process.exitCode = 1
} finally {
  await disconnect()
}
