import type { HourUnit, PeopleKind, PeopleOverview, QualificationRecord } from './peopleApi'

export const hourUnitShort: Record<HourUnit, string> = { ACADEMIC: 'акад. ч', ASTRONOMICAL: 'астр. ч' }

// Формы учёта людей и часов описаны полями: один компонент рисует любую из трёх, а перевод черновика в запрос
// и обратно проверяется тестом без браузера.

export type FieldType = 'text' | 'date' | 'hours' | 'select' | 'checkbox' | 'email'

export type Choice = { value: string; label: string }

export type Field = {
  key: string
  label: string
  type: FieldType
  required?: boolean
  hint?: string
  choices?: (options: PeopleOverview['options']) => Choice[]
  empty?: string
}

export type Draft = Record<string, string | boolean>

const unitChoices = (): Choice[] => [
  { value: 'ACADEMIC', label: 'академический час (45 минут)' },
  { value: 'ASTRONOMICAL', label: 'астрономический час (60 минут)' }
]

const interaction: Field = {
  key: 'interactionId', label: 'Взаимодействие', type: 'select', empty: 'Без взаимодействия',
  choices: (options) => options.interactions.map((item) => ({ value: item.id, label: item.title }))
}

const activity: Field = {
  key: 'agreementActivityId', label: 'Мероприятие плана договора', type: 'select', empty: 'Без мероприятия',
  hint: 'Часы засчитываются в мероприятие договора вуза.',
  choices: (options) => options.activities.map((item) => ({
    value: item.id,
    label: `№ ${item.agreementNumber}: ${item.title}${item.kindName === null ? '' : ` (${item.kindName})`}`
  }))
}

const employees = (options: PeopleOverview['options']) => options.employees.map((item) => ({ value: item.id, label: item.title }))

export const peopleFields: Record<PeopleKind, Field[]> = {
  teaching: [
    interaction,
    {
      key: 'agreementId', label: 'Договор', type: 'select', empty: 'Без договора',
      choices: (options) => options.agreements.map((item) => ({ value: item.id, label: item.title }))
    },
    activity,
    { key: 'discipline', label: 'Дисциплина или программа', type: 'text', required: true },
    {
      key: 'performerProfileId', label: 'Сотрудник', type: 'select', empty: 'Не из справочника — исполнитель группы компаний',
      choices: employees
    },
    { key: 'performerName', label: 'ФИО исполнителя группы компаний', type: 'text', hint: 'Если сотрудник не выбран.' },
    { key: 'performerCompany', label: 'Компания исполнителя', type: 'text' },
    { key: 'startsOn', label: 'Начало', type: 'date', required: true },
    { key: 'endsOn', label: 'Окончание', type: 'date', required: true },
    { key: 'hours', label: 'Часы', type: 'hours', required: true },
    { key: 'hourUnit', label: 'Единица часа', type: 'select', choices: unitChoices },
    { key: 'studentGroup', label: 'Группа или поток', type: 'text' },
    {
      key: 'confirmationStatus', label: 'Подтверждение вузом', type: 'select',
      choices: () => [
        { value: 'NOT_REQUESTED', label: 'не запрашивалось' },
        { value: 'REQUESTED', label: 'запрошено у вуза' },
        { value: 'CONFIRMED', label: 'подтверждено вузом' },
        { value: 'REJECTED', label: 'вуз не подтвердил' }
      ]
    },
    { key: 'confirmationDocument', label: 'Документ подтверждения', type: 'text', hint: 'Например, «Справка вуза № 12 от 20.12.2026».' },
    { key: 'confirmedOn', label: 'Дата подтверждения', type: 'date' }
  ],
  internships: [
    interaction,
    activity,
    { key: 'internName', label: 'ФИО стажёра', type: 'text', required: true },
    {
      key: 'internKind', label: 'Стажёр', type: 'select', required: true,
      choices: () => [{ value: 'STUDENT', label: 'студент' }, { value: 'TEACHER', label: 'преподаватель' }]
    },
    { key: 'place', label: 'Место стажировки: подразделение или исполнитель', type: 'text', required: true },
    { key: 'mentorProfileId', label: 'Наставник', type: 'select', empty: 'Не из справочника — указать ФИО', choices: employees },
    { key: 'mentorName', label: 'ФИО наставника', type: 'text', hint: 'Если наставник не выбран из справочника.' },
    { key: 'startsOn', label: 'Начало', type: 'date', required: true },
    { key: 'endsOn', label: 'Окончание', type: 'date', required: true },
    { key: 'internHours', label: 'Часы стажёра', type: 'hours', required: true },
    { key: 'mentorHours', label: 'Часы наставника', type: 'hours' },
    { key: 'hourUnit', label: 'Единица часа', type: 'select', choices: unitChoices },
    {
      key: 'result', label: 'Итог', type: 'select',
      choices: () => [
        { value: 'PLANNED', label: 'запланирована' },
        { value: 'IN_PROGRESS', label: 'идёт' },
        { value: 'COMPLETED', label: 'завершена' },
        { value: 'NOT_COMPLETED', label: 'не завершена' }
      ]
    },
    { key: 'employed', label: 'Трудоустроен после стажировки', type: 'checkbox' },
    { key: 'document', label: 'Документ', type: 'text' }
  ],
  qualifications: [
    interaction,
    activity,
    {
      key: 'teacherRosterId', label: 'Поток Moodle (список на курс LMS)', type: 'select', empty: 'Без потока',
      choices: (options) => options.teacherRosters.map((item) => ({ value: item.id, label: item.title }))
    },
    {
      key: 'contactId', label: 'Преподаватель из контактов вуза', type: 'select', empty: 'Не из контактов — указать ФИО',
      choices: (options) => options.teachers.map((item) => ({ value: item.id, label: item.email === null ? item.name : `${item.name}, ${item.email}` }))
    },
    { key: 'personName', label: 'ФИО преподавателя', type: 'text', hint: 'Если контакт выбран, можно не заполнять.' },
    { key: 'personEmail', label: 'Электронная почта', type: 'email' },
    { key: 'program', label: 'Программа', type: 'text', required: true },
    { key: 'startsOn', label: 'Начало', type: 'date', required: true },
    { key: 'endsOn', label: 'Окончание', type: 'date', required: true },
    { key: 'hours', label: 'Часы', type: 'hours', required: true },
    { key: 'hourUnit', label: 'Единица часа', type: 'select', choices: unitChoices },
    {
      key: 'result', label: 'Итог', type: 'select',
      choices: () => [
        { value: 'IN_PROGRESS', label: 'обучается' },
        { value: 'CERTIFIED', label: 'аттестован' },
        { value: 'NOT_CERTIFIED', label: 'не аттестован' }
      ]
    },
    { key: 'certificateNumber', label: 'Номер удостоверения', type: 'text' },
    { key: 'certificateDate', label: 'Дата удостоверения', type: 'date' },
    { key: 'frdoSubmittedOn', label: 'Передано в ФРДО (дата)', type: 'date', hint: 'Только для аттестованных.' }
  ]
}

// Черновик из записи (для правки) или пустой (для новой записи) с единицей часа из настроек.
export const draftFrom = (
  kind: PeopleKind,
  record: Record<string, unknown> | null,
  defaults: { hourUnit: string; interactionId?: string }
): Draft => {
  const draft: Draft = {}
  for (const field of peopleFields[kind]) {
    const value = record?.[field.key]
    draft[field.key] = field.type === 'checkbox' ? value === true : value === null || value === undefined ? '' : String(value)
  }
  if (record === null) {
    draft.hourUnit = defaults.hourUnit
    if (defaults.interactionId !== undefined) {
      draft.interactionId = defaults.interactionId
    }
  }
  // Для сотрудника из справочника сервер показывает его имя в поле ФИО; в черновике имя не нужно.
  if (record !== null && kind === 'teaching' && record.performerProfileId) {
    draft.performerName = ''
  }
  if (record !== null && kind === 'internships' && record.mentorProfileId) {
    draft.mentorName = ''
  }
  return draft
}

export const payloadFrom = (kind: PeopleKind, draft: Draft, version: number | null): Record<string, unknown> => {
  const payload: Record<string, unknown> = { version }
  for (const field of peopleFields[kind]) {
    const value = draft[field.key]
    if (field.type === 'checkbox') {
      payload[field.key] = value === true
    } else if (typeof value !== 'string' || value.trim() === '') {
      payload[field.key] = null
    } else if (field.type === 'hours') {
      payload[field.key] = Number(value.replace(',', '.'))
    } else {
      payload[field.key] = value.trim()
    }
  }
  return payload
}

export const hoursText = (hours: number | null | undefined, unit: HourUnit) => (
  hours === null || hours === undefined ? '—' : `${hours.toLocaleString('ru-RU')} ${hourUnitShort[unit]}`
)

// Состояние ФРДО одной записью: передано, просрочено, срок или «срок не задан».
export const frdoText = (record: QualificationRecord, formatDate: (value: string) => string) => {
  if (record.result !== 'CERTIFIED') {
    return '—'
  }
  if (record.frdoSubmittedOn !== null) {
    return `передано ${formatDate(record.frdoSubmittedOn)}`
  }
  if (record.frdoDueOn === null) {
    return 'не передано'
  }
  return record.frdoOverdue ? `просрочено: срок ${formatDate(record.frdoDueOn)}` : `передать до ${formatDate(record.frdoDueOn)}`
}
