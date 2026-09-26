import { ApiError, type CrmProfile } from '../../shared/api/client'

export type SessionHandlers = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

export const roleLabels: Record<CrmProfile['role'], string> = {
  USER: 'Менеджер (КАМ)',
  LEADER: 'Руководитель команды',
  ADMIN: 'Администратор',
  MANAGEMENT: 'Руководство (только чтение)'
}

const dateTimeFormatter = new Intl.DateTimeFormat('ru-RU', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Europe/Moscow'
})

export const formatDateTime = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTimeFormatter.format(date)
}

export const requestIdOf = (error: unknown) => (
  error instanceof ApiError ? error.requestId : undefined
)

export const isVersionConflict = (error: unknown) => (
  error instanceof ApiError && error.code === 'VERSION_CONFLICT'
)

export const handledSessionError = (error: unknown, handlers: SessionHandlers) => {
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

export const commandErrorMessage = (error: unknown) => {
  if (!(error instanceof ApiError)) {
    return 'Не удалось связаться с сервисом. Повторите попытку позже.'
  }
  if (error.code === 'VERSION_CONFLICT') {
    return 'Данные уже изменены другим действием. Обновите список и повторите.'
  }
  if (error.code === 'LAST_ACTIVE_ADMIN') {
    return 'Нельзя оставить систему без активного администратора.'
  }
  if (error.code === 'IDEMPOTENCY_CONFLICT') {
    return 'Команда уже выполнялась с другими данными. Повторите действие.'
  }
  if (error.code === 'NOT_FOUND') {
    return 'Запись не найдена. Обновите список.'
  }
  const fieldMessages = Object.values(error.fieldErrors ?? {})
  if (fieldMessages.length > 0) {
    return fieldMessages.join(' ')
  }
  if (error.status === 403) {
    return 'Недостаточно прав для этого действия.'
  }
  return 'Не удалось сохранить изменения. Проверьте данные и повторите попытку.'
}
