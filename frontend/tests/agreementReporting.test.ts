import assert from 'node:assert/strict'
import { test } from 'node:test'
import { adminSectionsFor } from '../src/features/admin/adminSections.ts'
import {
  activityListStatusLabels,
  activityStatuses,
  activityStatusLabels,
  columnTitle,
  daysLeftLabel,
  deadlinesInput,
  deadlinesValid,
  formatMoney,
  formatQuantities,
  formatReportCell,
  listEditable,
  moveColumn,
  referenceOptions,
  reportTable,
  requiredFields,
  settingsDraft,
  settingsInput,
  templateInput,
  thresholdMetLabel,
  toggleColumn,
  type ReferenceItem
} from '../src/features/agreements/reportingModel.ts'

test('деньги — как на сервере: два знака, пробел между разрядами, запятая; пустое — пусто', () => {
  assert.equal(formatMoney(1234.5), '1 234,50')
  assert.equal(formatMoney(0), '0,00')
  assert.equal(formatMoney(1234567.891), '1 234 567,89')
  assert.equal(formatMoney(999), '999,00')
  assert.equal(formatMoney(null), '')
  assert.equal(formatMoney(undefined), '')
})

test('ячейка отчёта: деньги только в денежных колонках, числа с запятой, пустое — пусто', () => {
  assert.equal(formatReportCell('COST', 1500), '1 500,00')
  assert.equal(formatReportCell('RATE', 12.3), '12,30')
  assert.equal(formatReportCell('ACTUAL_VALUATION', null), '')
  assert.equal(formatReportCell('PLANNED_VOLUME', 1500), '1500')
  assert.equal(formatReportCell('PARTICIPANTS', 2.5), '2,5')
  assert.equal(formatReportCell('TITLE', 'Лекция'), 'Лекция')
})

test('таблица отчёта: подписи колонок из шаблона или по умолчанию, строки в порядке колонок', () => {
  const options = [
    { code: 'TITLE', defaultTitle: 'Мероприятие' },
    { code: 'COST', defaultTitle: 'Стоимость, ₽' },
    { code: 'PARTICIPANTS', defaultTitle: 'Охват' }
  ]
  const table = reportTable({
    columns: [{ code: 'TITLE', title: null }, { code: 'COST', title: 'Сумма' }, { code: 'PARTICIPANTS', title: '  ' }],
    rows: [['Хакатон', 250000, 40], ['Лекция', null, null]]
  }, options)
  assert.deepEqual(table.headers, [
    { code: 'TITLE', title: 'Мероприятие', money: false },
    { code: 'COST', title: 'Сумма', money: true },
    { code: 'PARTICIPANTS', title: 'Охват', money: false }
  ])
  assert.deepEqual(table.rows, [['Хакатон', '250 000,00', '40'], ['Лекция', '', '']])
  assert.equal(columnTitle({ code: 'UNKNOWN', title: null }, options), 'UNKNOWN')
})

test('количество в сводах — «единица: план / факт» через «; », порог — да, нет или «нет базы»', () => {
  assert.equal(formatQuantities([{ unit: 'чел.', planned: 10, actual: 8 }, { unit: 'ч', planned: 4, actual: 0 }]), 'чел.: 10 / 8; ч: 4 / 0')
  assert.equal(formatQuantities([]), '')
  assert.equal(thresholdMetLabel(true), 'да')
  assert.equal(thresholdMetLabel(false), 'нет')
  assert.equal(thresholdMetLabel(null), 'нет базы')
})

test('подписи статусов мероприятия и перечня', () => {
  assert.deepEqual(activityStatuses.map((status) => activityStatusLabels[status]), [
    'Запланировано', 'Выполняется', 'Проведено', 'Подтверждено вузом', 'Отклонено', 'Отменено'
  ])
  assert.deepEqual(Object.values(activityListStatusLabels), [
    'Проект', 'На согласовании', 'Согласован', 'Согласован без ответа', 'Возвращён'
  ])
  assert.ok(listEditable('DRAFT') && listEditable('RETURNED'))
  assert.ok(!listEditable('REVIEW') && !listEditable('APPROVED') && !listEditable('APPROVED_SILENTLY'))
})

test('перестановка колонок шаблона: вверх, вниз, края не двигаются', () => {
  const columns = ['A', 'B', 'C']
  assert.deepEqual(moveColumn(columns, 1, -1), ['B', 'A', 'C'])
  assert.deepEqual(moveColumn(columns, 1, 1), ['A', 'C', 'B'])
  assert.deepEqual(moveColumn(columns, 0, -1), ['A', 'B', 'C'])
  assert.deepEqual(moveColumn(columns, 2, 1), ['A', 'B', 'C'])
  assert.deepEqual(columns, ['A', 'B', 'C'])
})

test('включение и выключение колонки, пустая подпись уходит как null', () => {
  const columns = [{ code: 'TITLE', title: 'Название' }]
  const added = toggleColumn(columns, 'COST', true)
  assert.deepEqual(added, [{ code: 'TITLE', title: 'Название' }, { code: 'COST', title: null }])
  assert.deepEqual(toggleColumn(added, 'COST', true), added)
  assert.deepEqual(toggleColumn(added, 'TITLE', false), [{ code: 'COST', title: null }])
  assert.deepEqual(templateInput([{ code: 'TITLE', title: '  Мероприятие ' }, { code: 'COST', title: ' ' }]), {
    columns: [{ code: 'TITLE', title: 'Мероприятие' }, { code: 'COST', title: null }]
  })
})

test('обязательные атрибуты: документ и факт — только у проведённого, у отменённого и отклонённого — ничего', () => {
  const all = ['EDUCATION_LEVEL', 'PARTICIPANTS', 'UNIT_QUANTITY', 'COST', 'EXECUTOR', 'DOCUMENT'] as const
  const planned = requiredFields(all, 'PLANNED')
  assert.equal(planned.educationLevel, true)
  assert.equal(planned.unit, true)
  assert.equal(planned.plannedVolume, true)
  assert.equal(planned.actualVolume, false)
  assert.equal(planned.document, false)
  const done = requiredFields(all, 'DONE')
  assert.equal(done.actualVolume, true)
  assert.equal(done.document, true)
  assert.equal(requiredFields(all, 'CONFIRMED').document, true)
  for (const status of ['CANCELLED', 'REJECTED'] as const) {
    assert.ok(Object.values(requiredFields(all, status)).every((value) => !value))
  }
  assert.ok(Object.values(requiredFields([], 'DONE')).every((value) => !value))
})

test('архивное значение справочника предлагается, только если уже стоит у мероприятия', () => {
  const item = (id: string, category: ReferenceItem['category'], archived: boolean): ReferenceItem => (
    { id, category, name: id, baseAmount: null, sortOrder: 0, archived, version: 0 }
  )
  const items = [item('u1', 'UNIT', false), item('u2', 'UNIT', true), item('l1', 'EDUCATION_LEVEL', false)]
  assert.deepEqual(referenceOptions(items, 'UNIT', null).map((value) => value.id), ['u1'])
  assert.deepEqual(referenceOptions(items, 'UNIT', 'u2').map((value) => value.id), ['u1', 'u2'])
  assert.deepEqual(referenceOptions(items, 'EDUCATION_LEVEL', 'u2').map((value) => value.id), ['l1'])
})

test('настройки: пустые сроки уходят как null, числа — числами', () => {
  const settings = {
    periodKind: 'ACADEMIC' as const,
    academicYearStartMonth: 9,
    silenceDays: null,
    repeatSilenceDays: 10,
    reminderDays: 14,
    thresholdEnabled: true,
    thresholdPercent: 2.5,
    thresholdBase: null,
    thresholdBaseLabel: null
  }
  const draft = settingsDraft(settings)
  assert.equal(draft.silenceDays, '')
  assert.deepEqual(settingsInput(draft), settings)
  assert.deepEqual(settingsInput({ ...draft, thresholdBase: '1000,5', thresholdBaseLabel: ' выручка ' }), {
    ...settings, thresholdBase: 1000.5, thresholdBaseLabel: 'выручка'
  })
})

test('сроки цикла: подпись «через N дн.» и «сегодня», пустое «что сдаётся» — null, без названия или даты не сохранить', () => {
  assert.equal(daysLeftLabel(0), 'сегодня')
  assert.equal(daysLeftLabel(5), 'через 5 дн.')
  const drafts = [{ name: ' Итоговый перечень ', dueOn: '2027-03-01', deliverable: ' ', annual: true }]
  assert.deepEqual(deadlinesInput(drafts), { deadlines: [{ name: 'Итоговый перечень', dueOn: '2027-03-01', deliverable: null, annual: true }] })
  assert.ok(deadlinesValid(drafts))
  assert.ok(!deadlinesValid([{ name: '', dueOn: '2027-03-01', deliverable: '', annual: false }]))
  assert.ok(!deadlinesValid([{ name: 'Срок', dueOn: '', deliverable: '', annual: false }]))
  assert.ok(deadlinesValid([]))
})

test('раздел «Отчётность по соглашениям» — только у администратора, в группе «Данные»', () => {
  const section = adminSectionsFor('ADMIN').find((entry) => entry.slug === 'agreement-reporting')
  assert.equal(section?.group, 'Данные')
  assert.equal(section?.label, 'Отчётность по соглашениям')
  assert.ok(!adminSectionsFor('LEADER').some((entry) => entry.slug === 'agreement-reporting'))
})
