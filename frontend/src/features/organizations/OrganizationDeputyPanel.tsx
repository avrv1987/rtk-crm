import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Organization,
  type OrganizationAssignmentCandidate,
  type OrganizationDeputy
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import {
  type AccessHandlers,
  commandErrorText,
  formatDate,
  handledAccessError,
  requestIdOf,
  todayIso
} from '../work/workShared'
import '../work/workControl.css'

type OrganizationDeputyPanelProps = AccessHandlers & {
  organization: Organization
  onChanged: () => Promise<void>
}

type DataState =
  | { kind: 'loading' }
  | { kind: 'ready'; deputies: OrganizationDeputy[]; candidates: OrganizationAssignmentCandidate[] }
  | { kind: 'failed'; requestId?: string }

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

export type AssignmentHistoryEntry = {
  id: string
  occurredAt: string
  description: string
  actor: string
}

const period = (deputy: OrganizationDeputy) => `с ${formatDate(deputy.startsOn)} по ${formatDate(deputy.endsOn)}`

export const deputyHistoryEntries = (deputies: OrganizationDeputy[]): AssignmentHistoryEntry[] => deputies.flatMap((deputy) => {
  const entries: AssignmentHistoryEntry[] = [{
    id: `${deputy.id}:assigned`,
    occurredAt: deputy.createdAt,
    description: `Назначен заместитель: ${deputy.deputyDisplayName} ${period(deputy)}`,
    actor: deputy.actorDisplayName
  }]
  if (deputy.endedAt !== null) {
    entries.push({
      id: `${deputy.id}:ended`,
      occurredAt: deputy.endedAt,
      description: deputy.endedByProfileId === null
        ? `Замещение закончилось по окончании периода: ${deputy.deputyDisplayName}, доступ заместителя закрыт.`
        : `Замещение завершено досрочно: ${deputy.deputyDisplayName}, доступ заместителя закрыт.`,
      actor: deputy.endedByDisplayName ?? 'CRM автоматически'
    })
  }
  return entries
})

const addDays = (isoDate: string, days: number) => {
  const date = new Date(`${isoDate}T00:00:00`)
  date.setDate(date.getDate() + days)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 10)
}

export const OrganizationDeputyPanel = ({
  organization,
  onChanged,
  onSessionExpired,
  onProfileUnavailable
}: OrganizationDeputyPanelProps) => {
  const [dataState, setDataState] = useState<DataState>({ kind: 'loading' })
  const requestVersion = useRef(0)
  const [deputyProfileId, setDeputyProfileId] = useState('')
  const [startsOn, setStartsOn] = useState(todayIso)
  const [endsOn, setEndsOn] = useState(() => addDays(todayIso(), 7))
  const [commandState, setCommandState] = useState<CommandState>({ kind: 'idle' })
  const [confirmingEnd, setConfirmingEnd] = useState(false)
  const commandKey = useRef<string | null>(null)
  const deputies = dataState.kind === 'ready' ? dataState.deputies : undefined
  const open = deputies?.find((deputy) => deputy.status !== 'ENDED')
  const deputyCandidates = dataState.kind === 'ready'
    ? dataState.candidates.filter((candidate) => candidate.id !== organization.ownerManagerId)
    : []

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    try {
      const [loadedDeputies, candidates] = await Promise.all([
        apiClient.listOrganizationDeputies(organization.id),
        apiClient.listOrganizationAssignmentOptions(organization.id)
      ])
      if (version === requestVersion.current) {
        setDataState({ kind: 'ready', deputies: loadedDeputies, candidates })
      }
    } catch (error) {
      if (version !== requestVersion.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setDataState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired, organization.id])

  useEffect(() => {
    void load()
    return () => {
      requestVersion.current += 1
    }
  }, [load, organization.version])

  const run = async (command: () => Promise<OrganizationDeputy>) => {
    setCommandState({ kind: 'saving' })
    try {
      await command()
      commandKey.current = null
      setCommandState({ kind: 'idle' })
      setDeputyProfileId('')
      await Promise.all([onChanged(), load()])
    } catch (error) {
      if (handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setCommandState({ kind: 'failed', error })
    }
  }

  const edit = (change: () => void) => {
    commandKey.current = null
    change()
    setCommandState({ kind: 'idle' })
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (commandState.kind === 'saving' || deputyProfileId.length === 0) {
      return
    }
    const key = commandKey.current ?? (commandKey.current = createIdempotencyKey())
    void run(() => apiClient.assignOrganizationDeputy(organization.id, { deputyProfileId, startsOn, endsOn }, key))
  }

  const endDeputy = () => {
    setConfirmingEnd(false)
    if (open === undefined) {
      return
    }
    const key = commandKey.current ?? (commandKey.current = createIdempotencyKey())
    void run(() => apiClient.endOrganizationDeputy(organization.id, open.id, key))
  }

  return (
    <section className="organization-deputy" aria-labelledby="organization-deputy-title">
      <h5 id="organization-deputy-title">Заместитель на время отсутствия</h5>
      <p className="organization-deputy__note">
        Заместитель видит вуз и ведёт его работы от своего имени, ответственный не меняется и сохраняет доступ.
        После последнего дня периода доступ заместителя закрывается автоматически.
      </p>
      {dataState.kind === 'loading' && <p className="work-control__message" role="status">Загружаем замещения…</p>}
      {dataState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось загрузить замещения.</p>
          <SupportDetails requestId={dataState.requestId} />
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {open !== undefined && (
        <div className="organization-deputy__current">
          <p>
            <strong>{open.deputyDisplayName}</strong> {period(open)}
            {open.status === 'SCHEDULED' ? ' — начнётся в первый день периода' : ' — замещение действует'}
          </p>
          <button
            type="button"
            className="button--secondary"
            disabled={commandState.kind === 'saving'}
            onClick={() => setConfirmingEnd(true)}
          >
            {commandState.kind === 'saving' ? 'Сохраняем…' : 'Завершить замещение'}
          </button>
        </div>
      )}
      {deputies !== undefined && open === undefined && (
        organization.requiresAssignment || deputyCandidates.length === 0 ? (
          <p className="work-control__message">
            {organization.requiresAssignment
              ? 'Сначала назначьте ответственного: заместитель подменяет активного КАМ.'
              : 'В команде нет другого активного менеджера для замещения.'}
          </p>
        ) : (
          <form className="organization-deputy__form" onSubmit={submit}>
            <label>
              Заместитель
              <select
                value={deputyProfileId}
                required
                disabled={commandState.kind === 'saving'}
                onChange={(event) => edit(() => setDeputyProfileId(event.target.value))}
              >
                <option value="">Выберите менеджера</option>
                {deputyCandidates.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>{candidate.displayName}</option>
                ))}
              </select>
            </label>
            <label>
              С
              <input
                type="date"
                value={startsOn}
                required
                disabled={commandState.kind === 'saving'}
                onChange={(event) => edit(() => setStartsOn(event.target.value))}
              />
            </label>
            <label>
              По
              <input
                type="date"
                value={endsOn}
                min={startsOn}
                required
                disabled={commandState.kind === 'saving'}
                onChange={(event) => edit(() => setEndsOn(event.target.value))}
              />
            </label>
            <button type="submit" disabled={commandState.kind === 'saving' || deputyProfileId.length === 0}>
              {commandState.kind === 'saving' ? 'Сохраняем…' : 'Назначить заместителя'}
            </button>
          </form>
        )
      )}
      {commandState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandErrorText(commandState.error, 'изменить замещение')}</p>
          {commandState.error instanceof ApiError && <SupportDetails requestId={commandState.error.requestId} code={commandState.error.code} />}
          {commandState.error instanceof ApiError && commandState.error.status === 409 && (
            <button type="button" onClick={() => void Promise.all([onChanged(), load()])}>Обновить карточку</button>
          )}
        </div>
      )}
      <ConfirmDialog
        open={confirmingEnd}
        title="Завершить замещение?"
        description={open === undefined
          ? ''
          : `${open.deputyDisplayName} потеряет доступ к вузу «${organization.name}». Записи заместителя в истории сохранятся.`}
        confirmLabel="Завершить замещение"
        onConfirm={endDeputy}
        onCancel={() => setConfirmingEnd(false)}
      />
    </section>
  )
}
