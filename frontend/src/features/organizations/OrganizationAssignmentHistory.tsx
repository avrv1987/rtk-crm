import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  type Organization,
  type OrganizationAssignmentEvent,
  type OrganizationDeputy
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { deputyHistoryEntries } from './OrganizationDeputyPanel'

type OrganizationAssignmentHistoryProps = {
  organizationId: Organization['id']
  revision: number
  includeDeputies?: boolean
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type EventsState =
  | { kind: 'loading' }
  | { kind: 'ready'; events: OrganizationAssignmentEvent[]; deputies: OrganizationDeputy[] }
  | { kind: 'failed'; requestId?: string }

const dateTimeFormatter = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' })

const formatDateTime = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTimeFormatter.format(date)
}

const reasonLabels: Record<NonNullable<OrganizationAssignmentEvent['reason']>, string> = {
  PROFILE_BLOCKED: 'КАМ заблокирован администратором',
  PROFILE_ROLE_CHANGED: 'у КАМ изменена роль',
  PROFILE_TEAM_CHANGED: 'КАМ переведён в другую команду',
  ORGANIZATION_TEAM_CHANGED: 'вуз передан в другую команду',
  IMPORT: 'назначение из импорта справочника'
}

const managerLabel = (id: string | null, displayName?: string | null) => (
  displayName?.trim() ? displayName : id === null ? 'Не назначен' : 'Профиль менеджера недоступен'
)

const assignmentEventDescription = (event: OrganizationAssignmentEvent) => {
  const previous = managerLabel(event.previousOwnerManagerId, event.previousOwnerManagerDisplayName)
  const next = managerLabel(event.ownerManagerId, event.newOwnerManagerDisplayName)
  if (event.ownerManagerId === null) {
    return `Снято назначение: ${previous}.`
  }
  if (event.previousOwnerManagerId === null) {
    return `Назначен ответственный: ${next}.`
  }
  return `Ответственный изменён: ${previous} → ${next}.`
}

type HistoryRow = {
  id: string
  occurredAt: string
  description: string
  actor: string
  reason?: OrganizationAssignmentEvent['reason']
  handoverNote?: string | null
}

const historyRows = (events: OrganizationAssignmentEvent[], deputies: OrganizationDeputy[]): HistoryRow[] => [
  ...events.map((event) => ({
    id: event.id,
    occurredAt: event.occurredAt,
    description: assignmentEventDescription(event),
    actor: event.actorDisplayName?.trim() || 'Профиль, выполнивший действие',
    reason: event.reason,
    handoverNote: event.handoverNote
  })),
  ...deputyHistoryEntries(deputies)
].sort((left, right) => Date.parse(left.occurredAt) - Date.parse(right.occurredAt))

export const OrganizationAssignmentHistory = ({
  organizationId,
  revision,
  includeDeputies = false,
  onSessionExpired,
  onProfileUnavailable
}: OrganizationAssignmentHistoryProps) => {
  const [state, setState] = useState<EventsState>({ kind: 'loading' })
  const requestVersion = useRef(0)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    setState({ kind: 'loading' })
    try {
      const [events, deputies] = await Promise.all([
        apiClient.listOrganizationAssignmentEvents(organizationId),
        includeDeputies ? apiClient.listOrganizationDeputies(organizationId) : Promise.resolve([])
      ])
      if (version === requestVersion.current) {
        setState({ kind: 'ready', events, deputies })
      }
    } catch (error) {
      if (version !== requestVersion.current) {
        return
      }
      if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
        onSessionExpired()
        return
      }
      if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
        onProfileUnavailable(error.requestId)
        return
      }
      setState({ kind: 'failed', requestId: error instanceof ApiError ? error.requestId : undefined })
    }
  }, [includeDeputies, onProfileUnavailable, onSessionExpired, organizationId])

  useEffect(() => {
    void load()
    return () => {
      requestVersion.current += 1
    }
  }, [load, revision])

  const latestHandover = state.kind === 'ready'
    ? [...state.events].reverse().find((event) => event.ownerManagerId !== null)
    : undefined

  return (
    <section className="organization-assignment__history" aria-labelledby={`assignment-history-${organizationId}`}>
      <div className="organization-assignment__history-header">
        <h5 id={`assignment-history-${organizationId}`}>История назначений</h5>
        <button type="button" className="button--secondary" onClick={() => void load()}>Обновить историю</button>
      </div>
      {latestHandover?.handoverNote && (
        <div className="notice organization-assignment__handover" role="note">
          <p><strong>Комментарий к передаче</strong> ({formatDateTime(latestHandover.occurredAt)}, {latestHandover.actorDisplayName ?? 'руководитель'}):</p>
          <p>{latestHandover.handoverNote}</p>
        </div>
      )}
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем историю назначений…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить историю назначений.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && (
        state.events.length + state.deputies.length === 0 ? (
          <p className="organizations-message">Изменений назначения пока не было.</p>
        ) : (
          <ol className="organization-assignment__events">
            {historyRows(state.events, state.deputies).map((row) => (
              <li key={row.id}>
                <div>
                  <strong>{row.description}</strong>
                  <time dateTime={row.occurredAt}>{formatDateTime(row.occurredAt)}</time>
                </div>
                {row.reason && <p>Причина: {reasonLabels[row.reason]}.</p>}
                {row.handoverNote && <p>Комментарий к передаче: {row.handoverNote}</p>}
                <p>Выполнил: {row.actor}</p>
              </li>
            ))}
          </ol>
        )
      )}
    </section>
  )
}
