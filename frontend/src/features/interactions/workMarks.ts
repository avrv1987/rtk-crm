import { ApiError, type ContactRole } from '../../shared/api/client'
import { formatMoscowDateTime } from './moscowTime'

export { markBadges, waitingLabels, workStatusLabels, type MarkBadge, type WaitingOn, type WorkStatus } from './markLabels'

export const contactRoleLabels: Record<ContactRole, string> = {
  SIGNATORY: 'Подписант (ЛПР)',
  IMPLEMENTER: 'Исполнитель (внедрение)',
  TEACHER: 'Преподаватель',
  APPROVER: 'Согласует документы',
  OTHER: 'Другое'
}

export const contactRoles = Object.keys(contactRoleLabels) as ContactRole[]

export const commandFailureMessage = (error: unknown) => {
  if (error instanceof ApiError && error.status === 409) {
    return typeof error.currentVersion === 'number'
      ? 'Карточку уже изменили. Черновик сохранён; обновите карточку перед новой отправкой.'
      : 'Команда не выполнена из-за конфликта. Черновик сохранён; обновите карточку и решите, отправлять ли его снова.'
  }
  if (error instanceof ApiError) {
    const reason = Object.values(error.fieldErrors ?? {})[0]
    return reason === undefined
      ? 'Команда не выполнена. Черновик сохранён; проверьте данные и повторите попытку.'
      : `Команда не выполнена: ${reason}. Черновик сохранён.`
  }
  return 'Не удалось связаться с сервисом. Черновик сохранён; повторите попытку позже.'
}

export const handledAccessError = (
  error: unknown,
  onSessionExpired: () => void,
  onProfileUnavailable: (requestId: string) => void
) => {
  if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
    onSessionExpired()
    return true
  }
  if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
    onProfileUnavailable(error.requestId)
    return true
  }
  return false
}


export const formatDateTime = formatMoscowDateTime
