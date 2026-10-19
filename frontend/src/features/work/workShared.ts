import { ApiError } from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime, isoFromMoscowInput, todayInMoscow } from '../../shared/format/datetime'
import { withCount } from '../../shared/format/plural'

export type AccessHandlers = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

export const handledAccessError = (error: unknown, handlers: AccessHandlers) => {
  if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
    handlers.onSessionExpired()
    return true
  }
  if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
    handlers.onProfileUnavailable(error.requestId)
    return true
  }
  return false
}

export const requestIdOf = (error: unknown) => (
  error instanceof ApiError ? error.requestId : undefined
)

export const commandErrorText = (error: unknown, action: string) => {
  if (error instanceof ApiError && error.status === 409) {
    return typeof error.currentVersion === 'number'
      ? 'Данные уже изменили. Обновите страницу и повторите действие.'
      // Конфликт состояния (например, «Взаимодействие завершено…») сервер объясняет сам — его текст точнее общего.
      : error.message.trim().length > 0
        ? `Не удалось ${action}: ${error.message}`
        : 'Команда не выполнена из-за конфликта. Обновите страницу и решите, повторять ли действие.'
  }
  if (error instanceof ApiError) {
    const reason = Object.values(error.fieldErrors ?? {})[0] ?? error.message
    return `Не удалось ${action}: ${reason}`
  }
  return 'Не удалось связаться с сервисом. Повторите попытку позже.'
}

const dayMilliseconds = 24 * 60 * 60 * 1000

export const formatDateTime = formatMoscowDateTime
export const formatDate = formatCalendarDate
export const todayIso = todayInMoscow
export const isoFromInput = isoFromMoscowInput

export const daysSince = (value: string, now: number) => {
  const time = new Date(value).getTime()
  return Number.isNaN(time) ? 0 : Math.max(0, Math.floor((now - time) / dayMilliseconds))
}

export const daysLabel = (days: number) => withCount(days, 'день', 'дня', 'дней')

export const eventTypeLabel = (type: string | null) => {
  switch (type) {
    case 'CREATED':
      return 'создание'
    case 'TRANSITIONED':
      return 'смена этапа'
    case 'COMMENTED':
      return 'комментарий'
    case 'STAGES_EDITED':
      return 'правка этапов'
    case 'PLAN_UPDATED':
      return 'изменение плана'
    default:
      return 'событие'
  }
}
