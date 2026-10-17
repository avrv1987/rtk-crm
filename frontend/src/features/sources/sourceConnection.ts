import type { SourceSettings, SourceSettingsUpdate } from '../../shared/api/client'

export type ConnectionSource = 'MOODLE' | 'WEBSITE' | 'CUSTOMER_CRM'

export type Draft = {
  moodleBaseUrl: string
  moodleToken: string
  moodleCourseIds: string
  moodleStudentRoles: string
  moodleTeacherRoles: string
  websiteBaseUrl: string
  websiteToken: string
  crmBaseUrl: string
  crmToken: string
  syncCron: string
}

export const draftOf = (settings: SourceSettings): Draft => ({
  moodleBaseUrl: settings.moodleBaseUrl ?? '',
  moodleToken: '',
  moodleCourseIds: settings.moodleCourseIds.join(', '),
  moodleStudentRoles: settings.moodleStudentRoles.join(', '),
  moodleTeacherRoles: settings.moodleTeacherRoles.join(', '),
  websiteBaseUrl: settings.websiteBaseUrl ?? '',
  websiteToken: '',
  crmBaseUrl: settings.crmBaseUrl ?? '',
  crmToken: '',
  syncCron: settings.syncCron ?? ''
})

export const list = (value: string) => value.split(/[\s,;]+/).filter((item) => item !== '')

const urlValid = (value: string, initial: string | null | undefined) => (
  value.trim() === '' || value.trim() === (initial ?? '') || /^https:\/\/[^\s/?#@]+[^\s?#]*$/i.test(value.trim())
)

// Проверяем только поля своего источника: чужая невалидная правка не должна блокировать сохранение.
export const validate = (draft: Draft, settings: SourceSettings, source: ConnectionSource): Record<string, string> => {
  const errors: Record<string, string> = {}
  if (source === 'MOODLE') {
    if (!urlValid(draft.moodleBaseUrl, settings.moodleBaseUrl)) {
      errors.moodleBaseUrl = 'Адрес Moodle должен начинаться с https:// и не содержать логина, параметров и якоря.'
    }
    if (list(draft.moodleCourseIds).some((item) => !/^[1-9]\d{0,17}$/.test(item))) {
      errors.moodleCourseIds = 'Номера курсов — целые положительные числа через запятую.'
    }
    if (list(draft.moodleStudentRoles).length === 0) {
      errors.moodleStudentRoles = 'Укажите хотя бы одну роль обучающихся.'
    }
    if (/\s/.test(draft.moodleToken.trim())) {
      errors.token = 'Токен не должен содержать пробелов.'
    }
  }
  if (source === 'WEBSITE') {
    if (!urlValid(draft.websiteBaseUrl, settings.websiteBaseUrl)) {
      errors.websiteBaseUrl = 'Адрес сайта должен начинаться с https:// и не содержать логина, параметров и якоря.'
    }
    if (/\s/.test(draft.websiteToken.trim())) {
      errors.token = 'Токен не должен содержать пробелов.'
    }
    if (draft.syncCron.trim() !== '' && draft.syncCron.trim().split(/\s+/).length !== 6) {
      errors.syncCron = 'Расписание — шесть полей: секунды, минуты, часы, день, месяц, день недели.'
    }
  }
  if (source === 'CUSTOMER_CRM') {
    if (!urlValid(draft.crmBaseUrl, settings.crmBaseUrl)) {
      errors.crmBaseUrl = 'Адрес CRM заказчика должен начинаться с https:// и не содержать логина, параметров и якоря.'
    }
    if (/\s/.test(draft.crmToken.trim())) {
      errors.token = 'Токен не должен содержать пробелов.'
    }
  }
  return errors
}

const orNull = (value: string) => (value.trim() === '' ? null : value.trim())

// Один запрос сохраняет все источники сразу, поэтому поля чужих источников берём из сохранённых настроек, а не из черновика.
export const payloadOf = (draft: Draft, settings: SourceSettings, source: ConnectionSource): SourceSettingsUpdate => {
  const base = draftOf(settings)
  const own: Draft = {
    ...base,
    ...(source === 'MOODLE' && {
      moodleBaseUrl: draft.moodleBaseUrl,
      moodleToken: draft.moodleToken,
      moodleCourseIds: draft.moodleCourseIds,
      moodleStudentRoles: draft.moodleStudentRoles,
      moodleTeacherRoles: draft.moodleTeacherRoles
    }),
    ...(source === 'WEBSITE' && { websiteBaseUrl: draft.websiteBaseUrl, websiteToken: draft.websiteToken, syncCron: draft.syncCron }),
    ...(source === 'CUSTOMER_CRM' && { crmBaseUrl: draft.crmBaseUrl, crmToken: draft.crmToken })
  }
  return {
    moodleBaseUrl: orNull(own.moodleBaseUrl),
    moodleToken: orNull(own.moodleToken),
    moodleCourseIds: list(own.moodleCourseIds).map(Number),
    moodleStudentRoles: list(own.moodleStudentRoles),
    moodleTeacherRoles: list(own.moodleTeacherRoles),
    websiteBaseUrl: orNull(own.websiteBaseUrl),
    websiteToken: orNull(own.websiteToken),
    crmBaseUrl: orNull(own.crmBaseUrl),
    crmToken: orNull(own.crmToken),
    syncCron: orNull(own.syncCron)
  }
}
