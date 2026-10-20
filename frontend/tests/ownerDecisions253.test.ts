import assert from 'node:assert/strict'
import { test } from 'node:test'
import { cardActions } from '../src/max/maxApi.ts'
import {
  legacyNote,
  legacyUnverified,
  moodleBatchAction,
  moodleCourseText,
  parseMoodleCourse
} from '../src/features/enrolment/enrolmentQueueModel.ts'
import { topDemandRequest, topDemandRows } from '../src/features/control/overviewModel.ts'
import { demandLinkPeriod, demandReportHash } from '../src/features/reports/reportsRoute.ts'
import { readFileSync } from 'node:fs'

test('строка «принято» из прежнего учёта помечена, пока сверка с Moodle её не подтвердила', () => {
  assert.equal(legacyUnverified({ result: 'ACCEPTED', resultSource: 'LEGACY' }), true)
  assert.equal(legacyUnverified({ result: 'ACCEPTED', resultSource: 'API' }), false)
  assert.equal(legacyUnverified({ result: 'FAILED', resultSource: 'LEGACY' }), false)
  assert.equal(legacyNote, 'из прежнего учёта, не сверено с LMS')
})

test('кнопка Moodle в партии: передать непринятые, сверить прежний учёт, иначе ничего', () => {
  const legacy = { result: 'ACCEPTED', resultSource: 'LEGACY' }
  const checked = { result: 'ACCEPTED', resultSource: 'API' }
  assert.equal(moodleBatchAction({ moodleTransferAvailable: true, accepted: 1, rows: 2, items: [checked] }), 'transfer')
  assert.equal(moodleBatchAction({ moodleTransferAvailable: true, accepted: 2, rows: 2, items: [legacy, checked] }), 'verify')
  assert.equal(moodleBatchAction({ moodleTransferAvailable: true, accepted: 1, rows: 1, items: [checked] }), null)
  assert.equal(moodleBatchAction({ moodleTransferAvailable: false, accepted: 0, rows: 2, items: [legacy] }), null)
})

test('уточнение курса Moodle у потока: пусто — по программе, группа только с курсом', () => {
  assert.deepEqual(parseMoodleCourse('', ''), { courseId: null, groupId: null })
  assert.deepEqual(parseMoodleCourse(' 17 ', ''), { courseId: 17, groupId: null })
  assert.deepEqual(parseMoodleCourse('17', '10'), { courseId: 17, groupId: 10 })
  assert.equal(parseMoodleCourse('', '10'), 'Группа задаётся вместе с курсом')
  assert.equal(parseMoodleCourse('0', ''), 'Номер курса Moodle — целое число больше нуля')
  assert.equal(parseMoodleCourse('17', 'А1'), 'Номер группы Moodle — целое число больше нуля')
  assert.equal(moodleCourseText({ moodleCourseId: null, moodleGroupId: null }), 'по сопоставлениям программы потока')
  assert.equal(moodleCourseText({ moodleCourseId: 17, moodleGroupId: 10 }), 'курс 17, группа 10')
})

test('Н6: у завершённой работы в MAX нет «Шаг выполнен» и комментария — сервер их отклоняет', () => {
  const card = (status: 'ACTIVE' | 'PAUSED' | 'COMPLETED') => ({
    interaction: { marks: { status }, nextAction: 'Позвонить', nextActionAt: null }
  }) as unknown as Parameters<typeof cardActions>[0]
  assert.deepEqual(cardActions(card('COMPLETED')), { step: false, comment: false })
  assert.deepEqual(cardActions(card('ACTIVE')), { step: true, comment: true })
  assert.deepEqual(cardActions(card('PAUSED')), { step: true, comment: true })
})

test('Н5: «Взаимодействие» — среди колонок «Работы менеджеров» по умолчанию, рядом с вузом', () => {
  const source = readFileSync(new URL('../src/features/reports/reportSelection.ts', import.meta.url), 'utf8').replace(/\r\n/g, '\n')
  assert.match(source, /MANAGER_WORK: \[\n    'MANAGER', 'ORGANIZATION', 'INTERACTION', 'PRODUCTS',/)
  assert.match(source, /managerWorkDefaultColumns: readonly ReportColumn\[\] = reportColumns\.MANAGER_WORK\.slice\(0, 14\)/)
  const kinds = readFileSync(new URL('../../backend/src/main/java/ru/rtk/crm/report/ReportKind.java', import.meta.url), 'utf8')
  assert.match(kinds, /List\.of\(MANAGER, ORGANIZATION, INTERACTION, PRODUCTS,/)
})

test('топ-5 программ: тот же запрос отчёта «Востребованность» без отборов, по месту, без «данных недостаточно»', () => {
  const request = topDemandRequest('2026-07-24', '2026-10-21')
  assert.equal(request.kind, 'DEMAND')
  assert.equal(request.from, '2026-07-24')
  assert.equal(request.to, '2026-10-21')
  assert.equal(request.sortBy, 'DEMAND_INDEX')
  assert.deepEqual(request.filters?.organizationIds, [])
  assert.deepEqual(request.filters?.programIds, [])
  assert.deepEqual(request.columns, ['PROGRAM', 'DEMAND_RANK', 'DEMAND_INDEX', 'DEMAND_COVERAGE'])

  const rows = topDemandRows([
    { PROGRAM: 'цифровой университет', DEMAND_RANK: 1, DEMAND_INDEX: 57.2, DEMAND_COVERAGE: '90 % веса; малая база: 9 участий' },
    { PROGRAM: 'анализ данных', DEMAND_RANK: 2, DEMAND_INDEX: 52.3, DEMAND_COVERAGE: '65 % веса' },
    { PROGRAM: 'веб-разработка', DEMAND_RANK: null, DEMAND_INDEX: null, DEMAND_COVERAGE: '20 % веса' }
  ])
  assert.deepEqual(rows, [
    { program: 'цифровой университет', place: 1, index: '57,2', coverage: '90 % веса; малая база: 9 участий' },
    { program: 'анализ данных', place: 2, index: '52,3', coverage: '65 % веса' }
  ])
})

test('«Полный отчёт» из сводки открывает отчёт за тот же период без отборов', () => {
  const hash = demandReportHash('2026-07-24', '2026-10-21')
  assert.equal(hash, '#/reports/demand?source=overview&from=2026-07-24&to=2026-10-21')
  assert.deepEqual(demandLinkPeriod(hash.split('?')[1]), { from: '2026-07-24', to: '2026-10-21' })
  assert.equal(demandLinkPeriod('from=2026-07-24&to=2026-10-21'), null)
  assert.equal(demandLinkPeriod('source=overview&from=2026-10-22&to=2026-10-21'), null)
  assert.equal(demandLinkPeriod('source=overview&from=24.07.2026&to=2026-10-21'), null)
  // Отчёт по ссылке строится без отборов: выбор берётся из выбора по умолчанию, а не из сохранённого.
  const source = readFileSync(new URL('../src/features/reports/reportSelection.ts', import.meta.url), 'utf8')
  assert.match(source, /\{ \.\.\.defaultSelection\(today, 'DEMAND'\), columns: selection\.columns, \.\.\.period \}/)
})
