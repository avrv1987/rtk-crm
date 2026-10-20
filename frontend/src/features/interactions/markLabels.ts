import type { InteractionMarks } from '../../shared/api/client'
import { issueBadges } from '../issues/issueModel.ts'

// Словари отметок без импорта значений клиента API: их берёт и основная CRM (через workMarks), и мини-приложение MAX,
// в бандл которого клиент CRM с сеансом Keycloak попадать не должен.

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
