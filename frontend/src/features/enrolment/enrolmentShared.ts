import { ApiError, type LearnerFieldCode, type LearnerFieldGroup, type StreamLearner } from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime } from '../../shared/format/datetime'

export type EnrolmentAccessHandlers = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
  onForbidden: () => void
  onUnavailable: () => void
}

export type AccessErrorReporter = (error: unknown) => boolean

export const handledEnrolmentAccessError = (error: unknown, handlers: EnrolmentAccessHandlers): boolean => {
  if (!(error instanceof ApiError)) {
    return false
  }
  if (error.code === 'UNAUTHENTICATED') {
    handlers.onSessionExpired()
    return true
  }
  if (error.code === 'CRM_PROFILE_REQUIRED') {
    handlers.onProfileUnavailable(error.requestId)
    return true
  }
  if (error.status === 404 && error.message.includes('модуль «Слушатели» выключен')) {
    handlers.onUnavailable()
    return true
  }
  if (error.status === 403) {
    handlers.onForbidden()
    return true
  }
  return false
}

export const requestIdOf = (error: unknown) => (error instanceof ApiError ? error.requestId : undefined)

export const responseErrorMessage = (error: unknown) => {
  if (!(error instanceof ApiError)) {
    return 'Не удалось связаться с сервисом. Повторите попытку позже.'
  }
  const fields = Object.values(error.fieldErrors ?? {})
  return fields.length > 0 ? fields.join(' ') : error.message
}

/** @deprecated используйте formatCalendarDate из shared/format/datetime — оставлено для совместимости импортов. */
export const formatIsoDate = formatCalendarDate

/** @deprecated используйте formatMoscowDateTime из shared/format/datetime — оставлено для совместимости импортов. */
export const formatDateTime = formatMoscowDateTime

export const snilsDigits = (value: string) => value.replace(/\D/g, '')

export const learnerFieldLabels: Record<LearnerFieldCode, string> = {
  LAST_NAME: 'Фамилия',
  FIRST_NAME: 'Имя',
  MIDDLE_NAME: 'Отчество',
  PHONE: 'Номер телефона',
  EMAIL: 'Email',
  SNILS: 'СНИЛС',
  PASSPORT_SERIES: 'Серия паспорта',
  PASSPORT_NUMBER: 'Номер паспорта',
  PASSPORT_ISSUED_BY: 'Кем выдан паспорт',
  PASSPORT_ISSUE_DATE: 'Дата выдачи паспорта',
  PASSPORT_DIVISION_CODE: 'Код подразделения',
  GENDER: 'Пол',
  BIRTH_DATE: 'Дата рождения',
  REGION: 'Регион',
  LOCALITY: 'Населённый пункт',
  STREET: 'Улица',
  HOUSE: 'Дом',
  APARTMENT: 'Квартира',
  POSTAL_CODE: 'Индекс',
  FIRST_NAME_DATIVE: 'Имя в дательном падеже',
  LAST_NAME_DATIVE: 'Фамилия в дательном падеже',
  MIDDLE_NAME_DATIVE: 'Отчество в дательном падеже',
  EDUCATION: 'Образование',
  DIPLOMA_PROFESSION: 'Профессия по диплому',
  DIPLOMA_INSTITUTION: 'Учебное заведение по диплому',
  DIPLOMA_LAST_NAME: 'Фамилия, указанная в дипломе',
  DIPLOMA_NUMBER: 'Номер диплома',
  DIPLOMA_SERIES: 'Серия диплома',
  DIPLOMA_REGISTRATION_NUMBER: 'Регистрационный номер диплома',
  DIPLOMA_ISSUE_DATE: 'Дата выдачи диплома'
}

export const learnerFieldGroupLabels: Record<LearnerFieldGroup, string> = {
  MAIN: 'Основное',
  DOCUMENTS: 'Документы',
  PERSONAL: 'Личные сведения',
  ADDRESS: 'Адрес регистрации',
  DATIVE: 'Дательный падеж',
  EDUCATION: 'Образование'
}

export type LearnerFieldGroupConfig = {
  group: LearnerFieldGroup
  fields: LearnerFieldCode[]
  revealable: boolean
}

export const learnerFieldGroupsConfig: LearnerFieldGroupConfig[] = [
  { group: 'MAIN', fields: ['LAST_NAME', 'FIRST_NAME', 'MIDDLE_NAME', 'PHONE', 'EMAIL'], revealable: true },
  {
    group: 'DOCUMENTS',
    fields: ['SNILS', 'PASSPORT_SERIES', 'PASSPORT_NUMBER', 'PASSPORT_ISSUED_BY', 'PASSPORT_ISSUE_DATE', 'PASSPORT_DIVISION_CODE'],
    revealable: true
  },
  { group: 'PERSONAL', fields: ['GENDER', 'BIRTH_DATE'], revealable: true },
  { group: 'ADDRESS', fields: ['REGION', 'LOCALITY', 'STREET', 'HOUSE', 'APARTMENT', 'POSTAL_CODE'], revealable: true },
  { group: 'DATIVE', fields: ['FIRST_NAME_DATIVE', 'LAST_NAME_DATIVE', 'MIDDLE_NAME_DATIVE'], revealable: false },
  {
    group: 'EDUCATION',
    fields: [
      'EDUCATION', 'DIPLOMA_PROFESSION', 'DIPLOMA_INSTITUTION', 'DIPLOMA_LAST_NAME', 'DIPLOMA_NUMBER', 'DIPLOMA_SERIES',
      'DIPLOMA_REGISTRATION_NUMBER', 'DIPLOMA_ISSUE_DATE'
    ],
    revealable: true
  }
]

export const dateFieldCodes = new Set<LearnerFieldCode>(['BIRTH_DATE', 'PASSPORT_ISSUE_DATE', 'DIPLOMA_ISSUE_DATE'])

export const genderOptions = ['М', 'Ж']

export const educationOptions = [
  'Без образования',
  'Основное общее образование - 9 классов',
  'Среднее общее образование - 11 классов',
  'Среднее профессиональное образование',
  'Высшее образование – бакалавриат',
  'Высшее образование – специалитет, магистратура',
  'Высшее образование – подготовка кадров высшей квалификации'
]

export const displayFieldValue = (code: LearnerFieldCode, value: string) => (
  dateFieldCodes.has(code) ? formatIsoDate(value) : value
)

export const fieldCodeLabels = (codes: LearnerFieldCode[]) => codes.map((code) => learnerFieldLabels[code]).join(', ')

export const lmsStatusLabel = (learner: Pick<StreamLearner, 'lmsStatus' | 'lmsExportedAt' | 'lmsTransferredAt'>) => {
  if (learner.lmsStatus === 'TRANSFERRED') {
    return `Передано в LMS ${learner.lmsTransferredAt === null ? '' : formatIsoDate(learner.lmsTransferredAt)}`.trim()
  }
  if (learner.lmsStatus === 'EXPORTED') {
    return `Выгружено ${learner.lmsExportedAt === null ? '' : formatIsoDate(learner.lmsExportedAt)}`.trim()
  }
  return 'Ожидает'
}

export const learnerFullName = (values: { lastName: string | null; firstName: string | null; middleName: string | null }) => (
  [values.lastName, values.firstName, values.middleName].filter((part) => part !== null && part.length > 0).join(' ')
)

const codePattern = /\b([A-Z][A-Z_]*)\b/g

export const historyDetails = (details: string | null): string | null => (
  details === null
    ? null
    : details.replace(codePattern, (code) => (
      learnerFieldLabels[code as LearnerFieldCode] ?? learnerFieldGroupLabels[code as LearnerFieldGroup] ?? code
    ))
)
