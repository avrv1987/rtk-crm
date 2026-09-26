import { useEffect, useId, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Organization,
  type OrganizationAssignmentCandidate
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessHandlers, commandErrorText, handledAccessError, requestIdOf } from '../work/workShared'
import '../work/workControl.css'

type OrganizationBulkTransferProps = AccessHandlers & {
  organizations: Organization[]
  onClear: () => void
  onTransferred: () => Promise<void>
}

type OptionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; candidates: OrganizationAssignmentCandidate[] }
  | { kind: 'failed'; requestId?: string }

type CommandState =
  | { kind: 'idle' }
  | { kind: 'confirming' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }
  | { kind: 'done'; assigned: number; unchanged: number }

const currentOwner = (organization: Organization) => (
  organization.requiresAssignment ? 'Требует назначения' : organization.ownerManagerName ?? 'Не назначен'
)

export const OrganizationBulkTransfer = ({
  organizations,
  onClear,
  onTransferred,
  onSessionExpired,
  onProfileUnavailable
}: OrganizationBulkTransferProps) => {
  const [optionsState, setOptionsState] = useState<OptionsState>({ kind: 'loading' })
  const [commandState, setCommandState] = useState<CommandState>({ kind: 'idle' })
  const [targets, setTargets] = useState<Record<string, string>>({})
  const [common, setCommon] = useState('')
  const commandKey = useRef<string | null>(null)
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const firstOrganizationId = organizations[0]?.id

  useEffect(() => {
    if (firstOrganizationId === undefined) {
      return
    }
    let cancelled = false
    setOptionsState({ kind: 'loading' })
    apiClient.listOrganizationAssignmentOptions(firstOrganizationId)
      .then((candidates) => {
        if (!cancelled) {
          setOptionsState({ kind: 'ready', candidates })
        }
      })
      .catch((error: unknown) => {
        if (cancelled || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
          return
        }
        setOptionsState({ kind: 'failed', requestId: requestIdOf(error) })
      })
    return () => {
      cancelled = true
    }
  }, [firstOrganizationId, onProfileUnavailable, onSessionExpired])

  const confirming = commandState.kind === 'confirming'

  useEffect(() => {
    const dialog = dialogRef.current
    if (dialog === null) {
      return
    }
    if (confirming && !dialog.open) {
      dialog.showModal()
    }
    if (!confirming && dialog.open) {
      dialog.close()
    }
  }, [confirming])

  const doneText = commandState.kind === 'done'
    ? `Передано вузов: ${commandState.assigned}.${commandState.unchanged > 0 ? ` Уже были у выбранного менеджера: ${commandState.unchanged}.` : ''}`
    : null

  if (organizations.length === 0) {
    return doneText === null ? null : <p className="organization-bulk__done" role="status">{doneText}</p>
  }

  const candidates = optionsState.kind === 'ready' ? optionsState.candidates : []
  const candidateName = (id: string) => candidates.find((candidate) => candidate.id === id)?.displayName ?? 'Выбранный менеджер'
  const targetOf = (organization: Organization) => targets[organization.id] ?? common
  const ready = organizations.every((organization) => targetOf(organization).length > 0)
  const saving = commandState.kind === 'saving'

  const edit = (change: () => void) => {
    commandKey.current = null
    change()
    if (commandState.kind === 'failed' || commandState.kind === 'done') {
      setCommandState({ kind: 'idle' })
    }
  }

  const transfer = async () => {
    setCommandState({ kind: 'saving' })
    try {
      const result = await apiClient.bulkAssignOrganizations(
        {
          items: organizations.map((organization) => ({
            organizationId: organization.id,
            version: organization.version,
            ownerManagerId: targetOf(organization)
          }))
        },
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      commandKey.current = null
      setTargets({})
      setCommon('')
      setCommandState({ kind: 'done', assigned: result.assigned.length, unchanged: result.unchangedOrganizationIds.length })
      await onTransferred()
    } catch (error) {
      if (handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setCommandState({ kind: 'failed', error })
    }
  }

  return (
    <section className="organization-bulk" aria-labelledby="organization-bulk-title">
      <div className="organization-bulk__header">
        <h3 id="organization-bulk-title">Передача выбранных вузов: {organizations.length}</h3>
        <button type="button" className="button--secondary" disabled={saving} onClick={onClear}>Снять выбор</button>
      </div>
      {optionsState.kind === 'loading' && <p className="work-control__message" role="status">Загружаем менеджеров команды…</p>}
      {optionsState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось загрузить менеджеров команды.</p>
          <SupportDetails requestId={optionsState.requestId} />
        </div>
      )}
      {optionsState.kind === 'ready' && candidates.length === 0 && (
        <p className="work-control__message">В команде нет активных менеджеров для назначения.</p>
      )}
      {optionsState.kind === 'ready' && candidates.length > 0 && (
        <form
          className="organization-bulk__form"
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !saving) {
              setCommandState({ kind: 'confirming' })
            }
          }}
        >
          <label>
            Передать всем выбранным
            <select value={common} disabled={saving} onChange={(event) => edit(() => {
              setCommon(event.target.value)
              setTargets({})
            })}>
              <option value="">Выберите менеджера</option>
              {candidates.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.displayName}</option>)}
            </select>
          </label>
          <ul className="organization-bulk__items" aria-label="Новый ответственный по каждому вузу">
            {organizations.map((organization) => (
              <li key={organization.id}>
                <span>
                  <strong>{organization.name}</strong>
                  <span className="organization-bulk__owner">Сейчас: {currentOwner(organization)}</span>
                </span>
                <select
                  aria-label={`Новый ответственный для «${organization.name}»`}
                  value={targetOf(organization)}
                  disabled={saving}
                  onChange={(event) => edit(() => setTargets((current) => ({ ...current, [organization.id]: event.target.value })))}
                >
                  <option value="">Выберите менеджера</option>
                  {candidates.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.displayName}</option>)}
                </select>
              </li>
            ))}
          </ul>
          <button type="submit" disabled={!ready || saving}>{saving ? 'Передаём…' : 'Передать выбранные'}</button>
        </form>
      )}
      {doneText !== null && <p className="organization-bulk__done" role="status">{doneText}</p>}
      {commandState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandErrorText(commandState.error, 'передать вузы')}</p>
          {commandState.error instanceof ApiError && <SupportDetails requestId={commandState.error.requestId} code={commandState.error.code} />}
          {commandState.error instanceof ApiError && commandState.error.status === 409 && (
            <button type="button" onClick={() => void onTransferred()}>Обновить список</button>
          )}
        </div>
      )}
      <dialog
        ref={dialogRef}
        className="confirm-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onCancel={(event) => {
          event.preventDefault()
          setCommandState({ kind: 'idle' })
        }}
      >
        <h2 id={titleId}>Передать вузы: {organizations.length}?</h2>
        <ul className="organization-bulk__confirm">
          {organizations.map((organization) => (
            <li key={organization.id}>
              «{organization.name}»: {currentOwner(organization)} → {candidateName(targetOf(organization))}
            </li>
          ))}
        </ul>
        <p>Новые ответственные получат доступ к вузам и всей истории, прежние — потеряют. Все передачи выполняются одной командой: если одна не пройдёт, не изменится ни один вуз.</p>
        <div className="confirm-dialog__actions">
          <button type="button" className="button--secondary" onClick={() => setCommandState({ kind: 'idle' })}>Отмена</button>
          <button type="button" onClick={() => void transfer()}>Передать</button>
        </div>
      </dialog>
    </section>
  )
}
