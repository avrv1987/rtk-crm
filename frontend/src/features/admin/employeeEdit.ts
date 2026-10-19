import type { CrmProfile, CrmProfileEvent, CrmProfileUpdate, Team } from '../../shared/api/client'
import { getModules } from '../../shared/modules'
import { roleLabels } from './adminShared'

export type ProfileDraft = {
  displayName: string
  role: CrmProfile['role']
  teamId: string
  active: boolean
  enrolmentOperator: boolean
}

export const profileName = (profile: Pick<CrmProfile, 'displayName'>) => (
  profile.displayName.trim() || 'Профиль без отображаемого имени'
)

export const teamLabel = (teamName: string | null) => teamName ?? 'Без команды'

export const operatorAllowed = (role: CrmProfile['role']) => role === 'USER' || role === 'LEADER'

export const draftOf = (profile: CrmProfile): ProfileDraft => ({
  displayName: profile.displayName,
  role: profile.role,
  teamId: profile.teamId ?? '',
  active: profile.pendingActivation || profile.active,
  enrolmentOperator: profile.enrolmentOperator
})

export const updateOf = (profile: CrmProfile, draft: ProfileDraft): CrmProfileUpdate => {
  const update: CrmProfileUpdate = { version: profile.version }
  const displayName = draft.displayName.trim()
  if (displayName !== profile.displayName) {
    update.displayName = displayName
  }
  if (draft.role !== profile.role && draft.role !== 'PARTNER') {
    update.role = draft.role
  }
  if (draft.teamId !== (profile.teamId ?? '')) {
    update.teamId = draft.teamId === '' ? null : draft.teamId
  }
  if (draft.active !== profile.active) {
    update.active = draft.active
  }
  const enrolmentOperator = draft.enrolmentOperator && operatorAllowed(draft.role)
  // При смене на роль без флага сервер снимет его сам (строка об этом — в accessSummary); при смене КАМ ↔ руководитель
  // снятие флага нужно передать явно, иначе сервер оставит прежний.
  if (enrolmentOperator !== profile.enrolmentOperator && (operatorAllowed(draft.role) || update.role === undefined)) {
    update.enrolmentOperator = enrolmentOperator
  }
  return update
}

export const changesAccess = (update: CrmProfileUpdate) => (
  update.role !== undefined || update.teamId !== undefined || update.active !== undefined
  || update.enrolmentOperator !== undefined
)

const teamNameById = (teams: Pick<Team, 'id' | 'name'>[], teamId: string | null | undefined) => (
  teamId === null || teamId === undefined || teamId === ''
    ? 'Без команды'
    : teams.find((team) => team.id === teamId)?.name ?? 'Неизвестная команда'
)

export const accessSummary = (profile: CrmProfile, update: CrmProfileUpdate, teams: Pick<Team, 'id' | 'name'>[]) => {
  const lines: string[] = []
  if (update.role !== undefined) {
    lines.push(`Роль: ${roleLabels[profile.role]} → ${roleLabels[update.role]}`)
    lines.push('Роль будет назначена учётной записи в Keycloak, прежняя роль снимется.')
  }
  if (update.teamId !== undefined) {
    lines.push(`Команда: ${teamLabel(profile.teamName)} → ${teamNameById(teams, update.teamId)}`)
  }
  if (update.active !== undefined) {
    lines.push(update.active ? 'Доступ к CRM откроется' : 'Доступ к CRM будет закрыт')
  }
  if (update.role === 'MANAGEMENT') {
    lines.push('Роль «Руководство»: просмотр карточек и отчётов всех команд без права изменений; команда не требуется.')
  }
  if (update.enrolmentOperator !== undefined) {
    lines.push(update.enrolmentOperator
      ? 'Флаг «Менеджер учебного процесса»: появится раздел «Зачисление» и загрузка файла оплат с данными слушателей, если включён модуль «Слушатели».'
      : 'Флаг «Менеджер учебного процесса» снимается: раздел «Зачисление» закроется.')
  }
  if (getModules().enrolment && profile.enrolmentOperator && update.role !== undefined && !operatorAllowed(update.role)) {
    lines.push('Флаг «Менеджер учебного процесса» будет снят: он доступен только КАМ и руководителю.')
  }
  lines.push('Новые права действуют со следующего запроса пользователя; ранее сформированные им отчёты станут недоступны для скачивания.')
  return lines
}

export const eventChanges = (event: CrmProfileEvent) => {
  const changes: string[] = []
  if (event.previousDisplayName !== event.displayName) {
    changes.push(`Имя: ${event.previousDisplayName} → ${event.displayName}`)
  }
  if (event.previousRole !== event.role) {
    changes.push(`Роль: ${roleLabels[event.previousRole]} → ${roleLabels[event.role]}`)
  }
  if (event.previousTeamId !== event.teamId) {
    changes.push(`Команда: ${teamLabel(event.previousTeamName)} → ${teamLabel(event.teamName)}`)
  }
  if (event.previousActive !== event.active) {
    changes.push(event.active ? 'Доступ открыт' : 'Доступ закрыт')
  }
  if (event.previousEnrolmentOperator !== event.enrolmentOperator) {
    changes.push(event.enrolmentOperator ? 'Назначен флаг «Менеджер учебного процесса»' : 'Снят флаг «Менеджер учебного процесса»')
  }
  return changes
}
