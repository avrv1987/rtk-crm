import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Organization,
  type OrganizationAssignmentCandidate,
  type OrganizationAssignmentEvent
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'

type OrganizationAssignmentPanelProps = {
  organization: Organization
  onOrganizationChanged: (organization: Organization) => void
  onReload: () => Promise<void>
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type OptionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; candidates: OrganizationAssignmentCandidate[] }
  | { kind: 'failed'; requestId?: string }

type EventsState =
  | { kind: 'loading' }
  | { kind: 'ready'; events: OrganizationAssignmentEvent[] }
  | { kind: 'failed'; requestId?: string }

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

type PendingAssignment = {
  ownerManagerId: string | null
  version: number
  key: string
}

const dateTimeFormatter = new Intl.DateTimeFormat('ru-RU', {
  dateStyle: 'medium',
  timeStyle: 'short'
})

const formatDateTime = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTimeFormatter.format(date)
}

const requestIdOf = (error: unknown) => (
  error instanceof ApiError ? error.requestId : undefined
)

const isUnauthenticated = (error: unknown) => (
  error instanceof ApiError && error.code === 'UNAUTHENTICATED'
)

const isProfileUnavailable = (error: unknown): error is ApiError => (
  error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED'
)

const commandMessage = (error: unknown) => {
  if (error instanceof ApiError && error.status === 409) {
    return typeof error.currentVersion === 'number'
      ? 'Карточку вуза уже изменили. Выбор сохранён; обновите карточку перед новой отправкой.'
      : 'Команда не выполнена из-за конфликта. Выбор сохранён; обновите карточку и решите, отправлять ли его снова.'
  }
  if (error instanceof ApiError) {
    const reason = Object.values(error.fieldErrors ?? {})[0] ?? error.message
    return `Не удалось изменить назначение: ${reason}`
  }
  return 'Не удалось связаться с сервисом. Повторите попытку позже.'
}

export const OrganizationAssignmentPanel = ({
  organization,
  onOrganizationChanged,
  onReload,
  onSessionExpired,
  onProfileUnavailable
}: OrganizationAssignmentPanelProps) => {
  const [optionsState, setOptionsState] = useState<OptionsState>({ kind: 'loading' })
  const [eventsState, setEventsState] = useState<EventsState>({ kind: 'loading' })
  const [selectedOwnerManagerId, setSelectedOwnerManagerId] = useState('')
  const [commandState, setCommandState] = useState<CommandState>({ kind: 'idle' })
  const [confirmingUnassign, setConfirmingUnassign] = useState(false)
  const optionsRequestVersion = useRef(0)
  const eventsRequestVersion = useRef(0)
  const pendingAssignment = useRef<PendingAssignment | null>(null)
  const ownerManagerId = organization.ownerManagerId ?? null

  const loadOptions = useCallback(async () => {
    const requestVersion = ++optionsRequestVersion.current
    setOptionsState({ kind: 'loading' })
    try {
      const candidates = await apiClient.listOrganizationAssignmentOptions(organization.id)
      if (requestVersion === optionsRequestVersion.current) {
        setOptionsState({ kind: 'ready', candidates })
      }
    } catch (error) {
      if (requestVersion !== optionsRequestVersion.current) {
        return
      }
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setOptionsState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired, organization.id])

  const loadEvents = useCallback(async () => {
    const requestVersion = ++eventsRequestVersion.current
    setEventsState({ kind: 'loading' })
    try {
      const events = await apiClient.listOrganizationAssignmentEvents(organization.id)
      if (requestVersion === eventsRequestVersion.current) {
        setEventsState({ kind: 'ready', events })
      }
    } catch (error) {
      if (requestVersion !== eventsRequestVersion.current) {
        return
      }
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setEventsState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired, organization.id])

  useEffect(() => {
    void loadOptions()
    void loadEvents()
    return () => {
      optionsRequestVersion.current += 1
      eventsRequestVersion.current += 1
    }
  }, [loadEvents, loadOptions])

  const candidatesById = useMemo(() => {
    if (optionsState.kind !== 'ready') {
      return new Map<string, OrganizationAssignmentCandidate>()
    }
    return new Map(optionsState.candidates.map((candidate) => [candidate.id, candidate]))
  }, [optionsState])

  const managerLabel = useCallback((id: string | null, displayName?: string | null) => {
    if (displayName?.trim()) {
      return displayName
    }
    if (id === null) {
      return 'Не назначен'
    }
    return candidatesById.get(id)?.displayName ?? 'Профиль менеджера недоступен'
  }, [candidatesById])

  const actorLabel = useCallback((event: OrganizationAssignmentEvent) => {
    if (event.actorDisplayName?.trim()) {
      return event.actorDisplayName
    }
    return candidatesById.get(event.actorProfileId)?.displayName ?? 'Профиль, выполнивший действие'
  }, [candidatesById])

  const eventDescription = useCallback((event: OrganizationAssignmentEvent) => {
    const previous = managerLabel(event.previousOwnerManagerId, event.previousOwnerManagerDisplayName)
    const next = managerLabel(event.ownerManagerId, event.newOwnerManagerDisplayName)
    if (event.ownerManagerId === null) {
      return `Снято назначение: ${previous}.`
    }
    if (event.previousOwnerManagerId === null) {
      return `Назначен ответственный: ${next}.`
    }
    return `Ответственный изменён: ${previous} → ${next}.`
  }, [managerLabel])

  const submitAssignment = useCallback(async (ownerManagerId: string | null) => {
    if (commandState.kind === 'saving') {
      return
    }
    const pending = pendingAssignment.current
    const key = pending !== null && pending.ownerManagerId === ownerManagerId && pending.version === organization.version
      ? pending.key
      : createIdempotencyKey()
    pendingAssignment.current = { ownerManagerId, version: organization.version, key }
    setCommandState({ kind: 'saving' })

    try {
      const result = await apiClient.assignOrganization(
        organization.id,
        { version: organization.version, ownerManagerId },
        key
      )
      pendingAssignment.current = null
      setSelectedOwnerManagerId('')
      setCommandState({ kind: 'idle' })
      onOrganizationChanged(result.organization)
      await Promise.all([loadOptions(), loadEvents()])
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setCommandState({ kind: 'failed', error })
    }
  }, [commandState.kind, loadEvents, loadOptions, onOrganizationChanged, onProfileUnavailable, onSessionExpired, organization.id, organization.version])

  const handleAssignmentSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (selectedOwnerManagerId.length === 0 || selectedOwnerManagerId === ownerManagerId) {
      return
    }
    void submitAssignment(selectedOwnerManagerId)
  }

  const reloadAfterConflict = async () => {
    await Promise.all([onReload(), loadOptions(), loadEvents()])
  }

  const currentOwnerLabel = ownerManagerId === null
    ? 'Не назначен'
    : managerLabel(ownerManagerId, organization.ownerManagerName)

  const confirmUnassign = () => {
    setConfirmingUnassign(false)
    void submitAssignment(null)
  }

  return (
    <section className="organization-assignment" aria-labelledby="organization-assignment-title">
      <div className="organization-assignment__header">
        <div>
          <p className="eyebrow">Руководителю команды</p>
          <h4 id="organization-assignment-title">Назначение ответственного</h4>
        </div>
        <p className="organization-assignment__current">Текущий: {currentOwnerLabel}</p>
      </div>

      {optionsState.kind === 'loading' && (
        <p className="organizations-message" role="status">Загружаем доступных менеджеров…</p>
      )}
      {optionsState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить доступных менеджеров.</p>
          <SupportDetails requestId={optionsState.requestId} />
          <button type="button" onClick={() => void loadOptions()}>Повторить</button>
        </div>
      )}
      {optionsState.kind === 'ready' && (
        optionsState.candidates.length === 0 ? (
          <p className="organizations-message">В команде нет активных менеджеров для назначения.</p>
        ) : (
          <form className="organization-assignment__form" onSubmit={handleAssignmentSubmit}>
            <label>
              Новый ответственный
              <select
                value={selectedOwnerManagerId}
                required
                disabled={commandState.kind === 'saving'}
                onChange={(event) => {
                  setSelectedOwnerManagerId(event.target.value)
                  setCommandState({ kind: 'idle' })
                }}
              >
                <option value="">Выберите менеджера</option>
                {optionsState.candidates.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>{candidate.displayName}</option>
                ))}
              </select>
            </label>
            <button
              type="submit"
              disabled={commandState.kind === 'saving' || selectedOwnerManagerId.length === 0 || selectedOwnerManagerId === ownerManagerId}
            >
              {commandState.kind === 'saving' ? 'Сохраняем…' : 'Назначить ответственного'}
            </button>
          </form>
        )
      )}

      <button
        type="button"
        className="organization-assignment__unassign"
        disabled={commandState.kind === 'saving' || ownerManagerId === null}
        onClick={() => setConfirmingUnassign(true)}
      >
        {commandState.kind === 'saving' ? 'Сохраняем…' : 'Снять назначение'}
      </button>
      <ConfirmDialog
        open={confirmingUnassign}
        title="Снять ответственного?"
        description={`${currentOwnerLabel} потеряет доступ к вузу «${organization.name}» и его взаимодействиям. Вуз попадёт в «Требует назначения», история сохранится.`}
        confirmLabel="Снять назначение"
        onConfirm={confirmUnassign}
        onCancel={() => setConfirmingUnassign(false)}
      />

      {commandState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandMessage(commandState.error)}</p>
          {commandState.error instanceof ApiError && <SupportDetails requestId={commandState.error.requestId} code={commandState.error.code} />}
          {commandState.error instanceof ApiError && commandState.error.status === 409 && (
            <button type="button" onClick={() => void reloadAfterConflict()}>Обновить карточку</button>
          )}
        </div>
      )}

      <section className="organization-assignment__history" aria-labelledby="organization-assignment-history-title">
        <div className="organization-assignment__history-header">
          <h5 id="organization-assignment-history-title">История назначений</h5>
          <button type="button" className="button--secondary" onClick={() => void loadEvents()}>Обновить историю</button>
        </div>
        {eventsState.kind === 'loading' && (
          <p className="organizations-message" role="status">Загружаем историю назначений…</p>
        )}
        {eventsState.kind === 'failed' && (
          <div className="organizations-message organizations-message--error" role="alert">
            <p>Не удалось загрузить историю назначений.</p>
            <SupportDetails requestId={eventsState.requestId} />
            <button type="button" onClick={() => void loadEvents()}>Повторить</button>
          </div>
        )}
        {eventsState.kind === 'ready' && (
          eventsState.events.length === 0 ? (
            <p className="organizations-message">Изменений назначения пока не было.</p>
          ) : (
            <ol className="organization-assignment__events">
              {eventsState.events.map((event) => (
                <li key={event.id}>
                  <div>
                    <strong>{eventDescription(event)}</strong>
                    <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
                  </div>
                  <p>Выполнил: {actorLabel(event)}</p>
                </li>
              ))}
            </ol>
          )
        )}
      </section>
    </section>
  )
}
