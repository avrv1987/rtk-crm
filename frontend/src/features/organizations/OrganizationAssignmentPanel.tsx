import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Organization,
  type OrganizationAssignmentCandidate
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { useUnsavedDraft } from '../interactions/unsavedDrafts'
import { OrganizationAssignmentHistory } from './OrganizationAssignmentHistory'
import { OrganizationDeputyPanel } from './OrganizationDeputyPanel'

type OrganizationAssignmentPanelProps = {
  organization: Organization
  profileId: string
  onOrganizationChanged: (organization: Organization) => void
  onReload: () => Promise<void>
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type OptionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; candidates: OrganizationAssignmentCandidate[] }
  | { kind: 'failed'; requestId?: string }

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

type PendingAssignment = {
  ownerManagerId: string | null
  version: number
  handoverNote: string | null
  key: string
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
  profileId,
  onOrganizationChanged,
  onReload,
  onSessionExpired,
  onProfileUnavailable
}: OrganizationAssignmentPanelProps) => {
  const [optionsState, setOptionsState] = useState<OptionsState>({ kind: 'loading' })
  const [historyRevision, setHistoryRevision] = useState(0)
  const [selectedOwnerManagerId, setSelectedOwnerManagerId] = useState('')
  const [handoverNote, setHandoverNote] = useState('')
  const [commandState, setCommandState] = useState<CommandState>({ kind: 'idle' })
  const [confirmingUnassign, setConfirmingUnassign] = useState(false)
  const optionsRequestVersion = useRef(0)
  const pendingAssignment = useRef<PendingAssignment | null>(null)
  const ownerManagerId = organization.ownerManagerId ?? null
  useUnsavedDraft(`organization:${organization.id}:handover`, `комментарий к передаче вуза «${organization.name}»`, handoverNote.trim().length > 0)

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

  useEffect(() => {
    void loadOptions()
    return () => {
      optionsRequestVersion.current += 1
    }
  }, [loadOptions])

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
    return candidatesById.get(id)?.displayName ?? 'Профиль КАМ недоступен'
  }, [candidatesById])

  const submitAssignment = useCallback(async (ownerManagerId: string | null) => {
    if (commandState.kind === 'saving') {
      return
    }
    const note = handoverNote.trim() || null
    const pending = pendingAssignment.current
    const key = pending !== null
      && pending.ownerManagerId === ownerManagerId
      && pending.version === organization.version
      && pending.handoverNote === note
      ? pending.key
      : createIdempotencyKey()
    pendingAssignment.current = { ownerManagerId, version: organization.version, handoverNote: note, key }
    setCommandState({ kind: 'saving' })

    try {
      const result = await apiClient.assignOrganization(
        organization.id,
        { version: organization.version, ownerManagerId, handoverNote: note },
        key
      )
      pendingAssignment.current = null
      setSelectedOwnerManagerId('')
      setHandoverNote('')
      setCommandState({ kind: 'idle' })
      setHistoryRevision((revision) => revision + 1)
      onOrganizationChanged(result.organization)
      await loadOptions()
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
  }, [commandState.kind, handoverNote, loadOptions, onOrganizationChanged, onProfileUnavailable, onSessionExpired, organization.id, organization.version])

  const handleAssignmentSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (selectedOwnerManagerId.length === 0 || selectedOwnerManagerId === ownerManagerId) {
      return
    }
    void submitAssignment(selectedOwnerManagerId)
  }

  const reloadAfterConflict = async () => {
    setHistoryRevision((revision) => revision + 1)
    await Promise.all([onReload(), loadOptions()])
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
        <p className="organizations-message" role="status">Загружаем доступных КАМ…</p>
      )}
      {optionsState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить доступных КАМ.</p>
          <SupportDetails requestId={optionsState.requestId} />
          <button type="button" onClick={() => void loadOptions()}>Повторить</button>
        </div>
      )}
      {optionsState.kind === 'ready' && (
        optionsState.candidates.length === 0 ? (
          <p className="organizations-message">В команде нет активных КАМ для назначения.</p>
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
                <option value="">Выберите КАМ</option>
                {optionsState.candidates.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>
                    {candidate.id === profileId ? `${candidate.displayName} (вы)` : candidate.displayName}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Комментарий к передаче
              <textarea
                value={handoverNote}
                maxLength={2000}
                disabled={commandState.kind === 'saving'}
                placeholder="Что обещано, что ждут от РТК, к кому обращаться"
                onChange={(event) => {
                  setHandoverNote(event.target.value)
                  setCommandState({ kind: 'idle' })
                }}
              />
              <span className="interaction-field-hint">Необязательно. Новый ответственный увидит комментарий в истории назначений вуза.</span>
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
        description={ownerManagerId === profileId
          ? `Вы останетесь руководителем команды и сохраните доступ к вузу «${organization.name}». Вуз попадёт в «Требует назначения», история сохранится.`
          : `${currentOwnerLabel} потеряет доступ к вузу «${organization.name}» и его взаимодействиям, если не руководит командой. Вуз попадёт в «Требует назначения», история сохранится.`}
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

      <OrganizationDeputyPanel
        organization={organization}
        onChanged={async () => {
          await onReload()
          setHistoryRevision((value) => value + 1)
        }}
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />

      <OrganizationAssignmentHistory
        organizationId={organization.id}
        revision={historyRevision}
        includeDeputies
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />
    </section>
  )
}
