import { type FormEvent, useRef, useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type Interaction } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { recordOf, textOf, useFormDraft } from './formDraft'
import { useUnsavedDraft } from './unsavedDrafts'
import { commandFailureMessage, handledAccessError, type WorkStatus, workStatusLabels } from './workMarks'
import './workCard.css'

type InteractionStatusPanelProps = {
  interaction: Interaction
  profileId: string
  onChanged: (interaction: Interaction) => void
  onRefresh: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type StatusDraft = {
  base: WorkStatus
  status: WorkStatus
  reason: string
}

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

const statuses: WorkStatus[] = ['ACTIVE', 'PAUSED', 'COMPLETED']

const parseDraft = (value: unknown): StatusDraft | null => {
  const record = recordOf(value)
  const base = statuses.find((item) => item === record?.base)
  const status = statuses.find((item) => item === record?.status)
  return record === null || base === undefined || status === undefined
    ? null
    : { base, status, reason: textOf(record.reason) }
}

const actionLabels: Record<WorkStatus, string> = {
  ACTIVE: 'Возобновить работу',
  PAUSED: 'Приостановить работу',
  COMPLETED: 'Завершить работу'
}

const reasonLabels: Record<WorkStatus, string> = {
  ACTIVE: 'Комментарий к возобновлению',
  PAUSED: 'Причина приостановки',
  COMPLETED: 'Итог работы'
}

const reasonHints: Record<WorkStatus, string> = {
  ACTIVE: 'Необязательно. Работа вернётся в «Мою работу».',
  PAUSED: 'Обязательно. Работа уйдёт из «Моей работы» по умолчанию, но останется в карточке вуза, истории и отчётах.',
  COMPLETED: 'Обязательно. Работа уйдёт из «Моей работы» по умолчанию, но останется в карточке вуза, истории и отчётах.'
}

export const InteractionStatusPanel = ({
  interaction,
  profileId,
  onChanged,
  onRefresh,
  onSessionExpired,
  onProfileUnavailable
}: InteractionStatusPanelProps) => {
  const [draft, setDraft] = useFormDraft(profileId, `card:${interaction.id}:status`, parseDraft)
  const [state, setState] = useState<CommandState>({ kind: 'idle' })
  const commandKey = useRef<string | null>(null)
  const current = interaction.marks
  const stale = draft !== null && draft.base !== current.status
  useUnsavedDraft(
    `card:${interaction.id}:status`,
    `статус работы в карточке «${interaction.title}»`,
    draft !== null && draft.reason.trim().length > 0
  )

  const start = (status: WorkStatus) => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setDraft({ base: current.status, status, reason: '' })
  }

  const acceptCurrentStatus = () => {
    if (draft !== null) {
      commandKey.current = null
      setState({ kind: 'idle' })
      setDraft({ ...draft, base: current.status })
    }
  }

  const changeReason = (reason: string) => {
    if (draft === null) {
      return
    }
    commandKey.current = null
    setDraft({ ...draft, reason })
  }

  const cancel = () => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setDraft(null)
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (draft === null || stale) {
      return
    }
    setState({ kind: 'saving' })
    try {
      const updated = await apiClient.changeInteractionStatus(
        interaction.id,
        { version: interaction.version, status: draft.status, reason: draft.reason.trim() || null },
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      commandKey.current = null
      setDraft(null)
      setState({ kind: 'idle' })
      onChanged(updated)
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }

  const reasonRequired = draft !== null && draft.status !== 'ACTIVE'
  const targets = statuses.filter((status) => status !== current.status)

  return (
    <section className="interaction-plan-form work-status" aria-labelledby={`work-status-${interaction.id}`}>
      <h6 id={`work-status-${interaction.id}`}>Статус работы</h6>
      <p className="work-status__current">
        <span className={`status status--${current.status === 'ACTIVE' ? 'planned' : 'missing'}`}>{workStatusLabels[current.status]}</span>
        {current.statusReason && <span>{current.status === 'COMPLETED' ? 'Итог' : current.status === 'PAUSED' ? 'Причина' : 'Комментарий'}: {current.statusReason}</span>}
      </p>
      {draft === null ? (
        <div className="interaction-plan-form__actions">
          {targets.map((status) => (
            <button key={status} type="button" className="button--secondary" onClick={() => start(status)}>
              {actionLabels[status]}
            </button>
          ))}
        </div>
      ) : (
        <form className="work-status__form" onSubmit={(event) => void submit(event)}>
          {stale && (
            <div className="interaction-catalog-message interaction-catalog-message--error draft-conflict" role="alert">
              {draft.status === current.status ? (
                <p>Пока черновик ждал сохранения, работу уже перевели в статус «{workStatusLabels[current.status]}». Черновик можно отменить.</p>
              ) : (
                <>
                  <p>
                    Пока черновик ждал сохранения, статус работы изменился: был «{workStatusLabels[draft.base]}», сейчас
                    {' '}«{workStatusLabels[current.status]}». Проверьте, нужно ли по-прежнему «{actionLabels[draft.status]}».
                  </p>
                  <button type="button" onClick={acceptCurrentStatus}>Продолжить с текущим статусом</button>
                </>
              )}
            </div>
          )}
          <label>
            {reasonLabels[draft.status]}
            <textarea
              value={draft.reason}
              maxLength={1000}
              required={reasonRequired}
              onChange={(event) => changeReason(event.target.value)}
            />
            <span className="interaction-field-hint">{reasonHints[draft.status]}</span>
          </label>
          <div className="interaction-plan-form__actions">
            <button type="submit" disabled={state.kind === 'saving' || stale || (reasonRequired && draft.reason.trim().length === 0)}>
              {state.kind === 'saving' ? 'Сохраняем…' : actionLabels[draft.status]}
            </button>
            <button type="button" className="button--secondary" disabled={state.kind === 'saving'} onClick={cancel}>Отменить</button>
          </div>
        </form>
      )}
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandFailureMessage(state.error)}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
          {state.error instanceof ApiError && state.error.status === 409 && (
            <button type="button" onClick={onRefresh}>Обновить карточку</button>
          )}
        </div>
      )}
    </section>
  )
}
