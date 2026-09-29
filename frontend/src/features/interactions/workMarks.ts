import { ApiError, type ContactRole, type InteractionMarks } from '../../shared/api/client'
import { issueBadges } from '../issues/issueModel'
import { formatMoscowDateTime } from './moscowTime'

export type WorkStatus = InteractionMarks['status']
export type WaitingOn = NonNullable<InteractionMarks['waitingOn']>

export const workStatusLabels: Record<WorkStatus, string> = {
  ACTIVE: 'Активна',
  PAUSED: 'Приостановлена',
  COMPLETED: 'Завершена'
}

export const waitingLabels: Record<WaitingOn, string> = {
  UNIVERSITY: 'Ждём вуз',
  RTK: 'Ждём РТК'
}

export const contactRoleLabels: Record<ContactRole, string> = {
  SIGNATORY: 'Подписант (ЛПР)',
  IMPLEMENTER: 'Исполнитель (внедрение)',
  TEACHER: 'Преподаватель',
  APPROVER: 'Согласует документы',
  OTHER: 'Другое'
}

export const contactRoles = Object.keys(contactRoleLabels) as ContactRole[]

export type MarkBadge = {
  key: string
  tone: 'overdue' | 'missing' | 'planned'
  label: string
  title?: string
  issues?: boolean
}

export const markBadges = (marks: InteractionMarks): MarkBadge[] => {
  const badges: MarkBadge[] = []
  if (marks.status !== 'ACTIVE') {
    badges.push({ key: 'status', tone: 'planned', label: workStatusLabels[marks.status], title: marks.statusReason ?? undefined })
  }
  if (marks.waitingOn !== null) {
    badges.push({ key: 'waiting', tone: 'missing', label: waitingLabels[marks.waitingOn], title: marks.waitingNote ?? undefined })
  }
  issueBadges(marks).forEach((badge) => badges.push({ ...badge, issues: true }))
  return badges
}

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
