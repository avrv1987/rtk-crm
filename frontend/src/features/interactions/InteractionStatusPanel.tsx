import { type FormEvent, useRef, useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type Interaction } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { shiftCalendarDate, todayInMoscow } from '../../shared/format/datetime'
import { recordOf, textOf, useFormDraft } from './formDraft'
import { forgetUnsavedDraft, useUnsavedDraft } from './unsavedDrafts'
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
  resumeOn: string
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
    : { base, status, reason: textOf(record.reason), resumeOn: textOf(record.resumeOn) }
}

const actionLabels: Record<WorkStatus, string> = {
  ACTIVE: 'Возобновить взаимодействие',
  PAUSED: 'Приостановить взаимодействие',
  COMPLETED: 'Завершить взаимодействие'
}

const reasonLabels: Record<WorkStatus, string> = {
  ACTIVE: 'Комментарий к возобновлению',
  PAUSED: 'Причина приостановки',
  COMPLETED: 'Итог взаимодействия'
}

const reasonHints: Record<WorkStatus, string> = {
  ACTIVE: 'Необязательно. Взаимодействие вернётся в «Мою работу».',
  PAUSED: 'Обязательно. До даты возврата взаимодействие не мешает в «Моей работе», а в этот день вернётся туда в группу «Вернуться к работе».',
  COMPLETED: 'Обязательно. Взаимодействие уйдёт из «Моей работы» по умолчанию, но останется в карточке вуза, истории и отчётах.'
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
  const [confirmComplete, setConfirmComplete] = useState(false)
  const commandKey = useRef<string | null>(null)
  const current = interaction.marks
  const stale = draft !== null && draft.base !== current.status
  useUnsavedDraft(
    `card:${interaction.id}:status`,
    `статус взаимодействия в карточке «${interaction.title}»`,
    draft !== null && draft.reason.trim().length > 0
  )

  const start = (status: WorkStatus) => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setDraft({ base: current.status, status, reason: '', resumeOn: '' })
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

  const changeResumeOn = (resumeOn: string) => {
    if (draft === null) {
      return
    }
    commandKey.current = null
    setDraft({ ...draft, resumeOn })
  }

  const cancel = () => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setDraft(null)
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (draft?.status === 'COMPLETED') {
      setConfirmComplete(true)
      return
    }
    void send()
  }

  const send = async () => {
    if (draft === null || stale) {
      return
    }
    setState({ kind: 'saving' })
    try {
      const updated = await apiClient.changeInteractionStatus(
        interaction.id,
        {
          version: interaction.version,
          status: draft.status,
          reason: draft.reason.trim() || null,
          resumeOn: draft.status === 'PAUSED' ? draft.resumeOn : null
        },
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      commandKey.current = null
      setDraft(null)
      setState({ kind: 'idle' })
      // После завершения карточка перерисовывается и панель может закрыться раньше, чем эффект с dirty=false
      // снимет метку черновика; черновик в хранилище уже очищен, поэтому метку снимаем сразу.
      forgetUnsavedDraft(`card:${interaction.id}:status`)
      onChanged(updated)
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }

  const reasonRequired = draft !== null && draft.status !== 'ACTIVE'
  const resumeMissing = draft?.status === 'PAUSED' && draft.resumeOn.length === 0
  const tomorrow = shiftCalendarDate(todayInMoscow(), 1)
  const targets = statuses.filter((status) => status !== current.status && !(current.status === 'COMPLETED' && status === 'PAUSED'))

  return (
    <section className="interaction-plan-form work-status" aria-labelledby={`work-status-${interaction.id}`}>
      <h6 id={`work-status-${interaction.id}`} className="visually-hidden">Статус взаимодействия</h6>
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
        <form className="work-status__form" onSubmit={submit}>
          {stale && (
            <div className="interaction-catalog-message interaction-catalog-message--error draft-conflict" role="alert">
              {draft.status === current.status ? (
                <p>Пока черновик ждал сохранения, взаимодействие уже перевели в статус «{workStatusLabels[current.status]}». Черновик можно отменить.</p>
              ) : (
                <>
                  <p>
                    Пока черновик ждал сохранения, статус взаимодействия изменился: был «{workStatusLabels[draft.base]}», сейчас
                    {' '}«{workStatusLabels[current.status]}». Проверьте, нужно ли по-прежнему «{actionLabels[draft.status]}».
                  </p>
                  <button type="button" onClick={acceptCurrentStatus}>Продолжить с текущим статусом</button>
                </>
              )}
            </div>
          )}
          <label>
            <span>{reasonLabels[draft.status]}{reasonRequired && <span className="required-mark" aria-hidden="true"> *</span>}</span>
            <textarea
              value={draft.reason}
              maxLength={1000}
              required={reasonRequired}
              onChange={(event) => changeReason(event.target.value)}
            />
            <span className="interaction-field-hint">{reasonHints[draft.status]}</span>
          </label>
          {draft.status === 'PAUSED' && (
            <label>
              <span>Когда вернуться к работе<span className="required-mark" aria-hidden="true"> *</span></span>
              <input type="date" value={draft.resumeOn} min={tomorrow} required onChange={(event) => changeResumeOn(event.target.value)} />
              <span className="interaction-field-hint">Дата станет следующим шагом «Вернуться к работе после приостановки».</span>
            </label>
          )}
          <div className="interaction-plan-form__actions">
            <button type="submit" disabled={state.kind === 'saving' || stale || resumeMissing || (reasonRequired && draft.reason.trim().length === 0)}>
              {state.kind === 'saving' ? 'Сохраняем…' : actionLabels[draft.status]}
            </button>
            <button type="button" className="button--secondary" disabled={state.kind === 'saving'} onClick={cancel}>Отмена</button>
          </div>
        </form>
      )}
      <ConfirmDialog
        open={confirmComplete}
        title="Завершить взаимодействие?"
        description="Взаимодействие уйдёт из «Моей работы» по умолчанию, но останется в карточке вуза, истории и отчётах. Вернуть его можно кнопкой «Возобновить»."
        confirmLabel="Завершить"
        onCancel={() => setConfirmComplete(false)}
        onConfirm={() => {
          setConfirmComplete(false)
          void send()
        }}
      />
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
