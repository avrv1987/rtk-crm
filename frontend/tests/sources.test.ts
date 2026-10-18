import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { draftOf, payloadOf, validate } from '../src/features/sources/sourceConnection.ts'
import { filterMappings, mappingKindLabel } from '../src/features/sources/sourceMappings.ts'
import { filterReviewRecords, reviewSourceCounts } from '../src/features/sources/sourceReview.ts'
import {
  initialSourcesTab,
  parseSourcesHash,
  sourceAlertOf,
  sourceState,
  sourcesHash,
  syncBlockReason,
  tokenMustBeRetyped
} from '../src/features/sources/sourceTabs.ts'
import { queueSummary } from '../src/features/sources/crmExchange.ts'

const source = (patch: Record<string, unknown>) => ({
  source: 'WEBSITE',
  title: 'Сайт ИТ Школы',
  adapterAvailable: true,
  configured: true,
  proposedContract: false,
  problemCount: 0,
  stale: false,
  lastRun: null,
  ...patch
}) as never

test('параметр tab читается из адреса, чужие адреса и неизвестные вкладки дают null', () => {
  assert.deepEqual(parseSourcesHash('#/admin/sources?tab=review'), { tab: 'review', mappingsSource: null })
  assert.deepEqual(parseSourcesHash('#/admin/sources?tab=mappings&source=moodle'), { tab: 'mappings', mappingsSource: 'moodle' })
  assert.equal(parseSourcesHash('#/admin/sources').tab, null)
  assert.equal(parseSourcesHash('#/admin/sources?tab=nope').tab, null)
  assert.equal(parseSourcesHash('#/admin/profiles?tab=crm').tab, null)
  assert.equal(sourcesHash('crm'), '#/admin/sources?tab=crm')
  assert.equal(sourcesHash('mappings', 'website'), '#/admin/sources?tab=mappings&source=website')
  assert.equal(parseSourcesHash(sourcesHash('labor-market')).tab, 'labor-market')
})

test('без параметра открывается первый источник с проблемой, иначе «Сайт»', () => {
  const ok = (code: string) => source({ source: code })
  assert.equal(initialSourcesTab([ok('WEBSITE'), ok('MOODLE')]), 'website')
  assert.equal(initialSourcesTab([ok('WEBSITE'), source({ source: 'MOODLE', lastRun: { status: 'FAILED' } })]), 'moodle')
  assert.equal(initialSourcesTab([ok('WEBSITE'), ok('MOODLE'), source({ source: 'CUSTOMER_CRM', stale: true })]), 'crm')
  assert.equal(initialSourcesTab([source({ source: 'MOODLE', problemCount: 2 }), source({ source: 'CUSTOMER_CRM', stale: true })]), 'moodle')
  assert.equal(initialSourcesTab([source({ source: 'CUSTOMER_CRM', stale: true }), source({ source: 'WEBSITE', problemCount: 1 })]), 'website')
})

test('состояние источника выражено словом и учитывает выполняющийся запуск', () => {
  assert.equal(sourceState(source({ adapterAvailable: false })), 'disabled')
  assert.equal(sourceState(source({ configured: false })), 'unconfigured')
  assert.equal(sourceState(source({ lastRun: { status: 'RUNNING' } })), 'running')
  assert.equal(sourceState(source({ lastRun: { status: 'FAILED' } })), 'failed')
  assert.equal(sourceState(source({ stale: true })), 'stale')
  assert.equal(sourceState(source({})), 'ok')
})

test('причина блокировки запуска без служебных слов', () => {
  assert.equal(syncBlockReason(source({ adapterAvailable: false })), 'Адаптер источника ещё не подключён.')
  assert.match(syncBlockReason(source({ configured: false, source: 'MOODLE' })) ?? '', /адрес, токен и курсы Moodle в «Настройках подключения»/)
  assert.match(syncBlockReason(source({ configured: false, source: 'CUSTOMER_CRM' })) ?? '', /адрес CRM заказчика/)
  assert.equal(syncBlockReason(source({ lastRun: { status: 'PENDING' } })), 'Синхронизация уже выполняется.')
  assert.equal(syncBlockReason(source({})), null)
})

test('токен привязан к адресу: смена адреса без нового токена требует ввода токена', () => {
  assert.equal(tokenMustBeRetyped('https://new.example.ru', 'https://old.example.ru', ''), true)
  assert.equal(tokenMustBeRetyped('https://new.example.ru', 'https://old.example.ru', 'abc'), false)
  assert.equal(tokenMustBeRetyped('https://old.example.ru', 'https://old.example.ru', ''), false)
  assert.equal(tokenMustBeRetyped('', 'https://old.example.ru', ''), false)
  assert.equal(tokenMustBeRetyped('https://new.example.ru', null, ''), true)
})

test('строка сбоя ведёт на вкладку источника и считает остальные проблемы', () => {
  assert.equal(sourceAlertOf([source({})], false), null)
  const failed = sourceAlertOf([source({ source: 'MOODLE', title: 'LMS Moodle', lastRun: { status: 'FAILED' } }), source({ stale: true })], false)
  assert.deepEqual(failed, { kind: 'failed', text: 'Сбой синхронизации «LMS Moodle» — подробнее', href: '#/admin/sources?tab=moodle', more: 1 })
  assert.equal(sourceAlertOf([source({ stale: true })], false)?.kind, 'stale')
  assert.equal(sourceAlertOf([], true)?.href, '#/admin/sources')
  assert.equal(sourceAlertOf([source({ configured: false, lastRun: { status: 'FAILED' } })], false), null)
})

const settings = {
  moodleBaseUrl: 'https://lms.example.ru',
  moodleCourseIds: [2, 3],
  moodleStudentRoles: ['student'],
  moodleTeacherRoles: [],
  websiteBaseUrl: 'https://site.example.ru',
  crmBaseUrl: null,
  syncCron: '0 0 * * * *'
} as never

test('сохранение источника не затрагивает поля других источников', () => {
  const draft = { ...draftOf(settings), moodleBaseUrl: 'https://other.example.ru', websiteBaseUrl: 'https://changed.example.ru', websiteToken: 'secret' }
  const moodle = payloadOf(draft, settings, 'MOODLE')
  assert.equal(moodle.moodleBaseUrl, 'https://other.example.ru')
  assert.equal(moodle.websiteBaseUrl, 'https://site.example.ru')
  assert.equal(moodle.websiteToken, null)
  const website = payloadOf(draft, settings, 'WEBSITE')
  assert.equal(website.websiteBaseUrl, 'https://changed.example.ru')
  assert.equal(website.websiteToken, 'secret')
  assert.equal(website.moodleBaseUrl, 'https://lms.example.ru')
  assert.deepEqual(website.moodleCourseIds, [2, 3])
})

test('проверка полей касается только своего источника', () => {
  const draft = { ...draftOf(settings), moodleBaseUrl: 'http://bad', syncCron: '1 2', moodleStudentRoles: '' }
  assert.deepEqual(Object.keys(validate(draft, settings, 'MOODLE')).sort(), ['moodleBaseUrl', 'moodleStudentRoles'])
  assert.deepEqual(Object.keys(validate(draft, settings, 'WEBSITE')), ['syncCron'])
  assert.deepEqual(validate(draft, settings, 'CUSTOMER_CRM'), {})
})

test('записи для разбора и сопоставления фильтруются по поиску и источнику', () => {
  const records = [
    { source: 'WEBSITE', externalId: 'p-1', organizationName: 'Политех' },
    { source: 'MOODLE', externalId: '2', organizationName: 'Java-разработчик', programName: 'Разработка' },
    { source: 'CUSTOMER_CRM', externalId: 'c-5', contactName: 'Иванова Мария' }
  ] as never[]
  assert.equal(filterReviewRecords(records, '', 'ALL').length, 3)
  assert.equal(filterReviewRecords(records, 'java', 'ALL').length, 1)
  assert.equal(filterReviewRecords(records, 'иванова', 'CUSTOMER_CRM').length, 1)
  assert.equal(filterReviewRecords(records, 'иванова', 'MOODLE').length, 0)
  assert.deepEqual(reviewSourceCounts(records as never), { ALL: 3, WEBSITE: 1, MOODLE: 1, CUSTOMER_CRM: 1, LABOR_MARKET: 0 })

  const mappings = [
    { source: 'MOODLE', kind: 'COURSE', label: 'Курс 1', externalKey: '1' },
    { kind: 'GROUP', label: 'Группа', externalKey: '1:2' },
    { source: 'WEBSITE', kind: 'PROGRAM', label: 'Программа', externalKey: 'x' }
  ] as never[]
  assert.equal(filterMappings(mappings, '', 'MOODLE').length, 2)
  assert.equal(filterMappings(mappings, '', 'WEBSITE').length, 1)
  assert.equal(filterMappings(mappings, 'курс', 'ALL').length, 1)
  const crm = { source: 'CUSTOMER_CRM', kind: 'ORGANIZATION', label: 'Организация', externalKey: 'crm-org-001' } as never
  assert.equal(filterMappings([...mappings, crm], '', 'CUSTOMER_CRM').length, 1)
  assert.equal(mappingKindLabel(crm), 'Вуз в CRM заказчика')
  assert.equal(mappingKindLabel({ source: 'WEBSITE', kind: 'ORGANIZATION' }), 'Вуз на сайте')
})

test('сводка очереди CRM называет все три состояния', () => {
  assert.equal(queueSummary({ queued: 5, sent: 12, error: 3 }), 'В очереди: 5, отправлено: 12, с ошибкой: 3')
})

test('история запусков привязана к источнику ключом и при смене вкладки не показывает прежний источник', () => {
  const card = readFileSync(new URL('../src/features/sources/SourceSyncCard.tsx', import.meta.url), 'utf8')
  assert.match(card, /<SourceRunHistory key=\{source\.source\}/)
})
