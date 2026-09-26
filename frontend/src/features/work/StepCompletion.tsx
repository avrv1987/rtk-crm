import { type FormEvent, useRef, useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type Interaction } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessHandlers, commandErrorText, formatDateTime, handledAccessError } from './workShared'
import './workControl.css'

type StepCompletionProps = AccessHandlers & {
  interaction: Interaction
  onCompleted: (interaction: Interaction) => void
  onReload: () => void
}

type CommandState =
  | { kind: 'closed' }
  | { kind: 'open' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }
  | { kind: 'done' }

const isoOf = (value: string) => {
  if (value.length === 0) {
    return null
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

export const StepCompletion = ({
  interaction,
  onCompleted,
  onReload,
  onSessionExpired,
  onProfileUnavailable
}: StepCompletionProps) => {
  const [state, setState] = useState<CommandState>({ kind: 'closed' })
  const [result, setResult] = useState('')
  const [nextAction, setNextAction] = useState('')
  const [nextActionAt, setNextActionAt] = useState('')
  const commandKey = useRef<string | null>(null)
  const hasStep = (interaction.nextAction?.trim() ?? '').length > 0 || interaction.nextActionAt !== null

  if (!hasStep && state.kind !== 'done') {
    return null
  }

  const edit = (change: () => void) => {
    commandKey.current = null
    change()
    if (state.kind === 'failed') {
      setState({ kind: 'open' })
    }
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (state.kind === 'saving') {
      return
    }
    const action = nextAction.trim()
    const dueAt = isoOf(nextActionAt)
    setState({ kind: 'saving' })
    try {
      const updated = await apiClient.completeInteractionStep(
        interaction.id,
        {
          version: interaction.version,
          result: result.trim().length > 0 ? result.trim() : null,
          nextStep: action.length > 0 || dueAt !== null ? { nextAction: action.length > 0 ? action : null, nextActionAt: dueAt } : null
        },
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      commandKey.current = null
      setResult('')
      setNextAction('')
      setNextActionAt('')
      setState({ kind: 'done' })
      onCompleted(updated)
    } catch (error) {
      if (handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', error })
    }
  }

  const stepLabel = interaction.nextAction?.trim() || 'Шаг без текста'
  const deadline = interaction.nextActionAt === null ? '' : `, срок ${formatDateTime(interaction.nextActionAt)}`

  return (
    <section className="step-completion" aria-labelledby={`step-completion-${interaction.id}`}>
      <h6 id={`step-completion-${interaction.id}`}>Выполнение шага</h6>
      {state.kind === 'done' && (
        <p className="step-completion__done" role="status">
          Шаг отмечен выполненным и записан в историю.{hasStep ? ' Новый следующий шаг сохранён.' : ' Задайте следующий шаг, чтобы работа не осталась без плана.'}
        </p>
      )}
      {hasStep && (state.kind === 'closed' || state.kind === 'done') && (
        <div className="step-completion__summary">
          <p>{stepLabel}{deadline}</p>
          <button type="button" className="button--secondary" onClick={() => setState({ kind: 'open' })}>Шаг выполнен</button>
        </div>
      )}
      {hasStep && (state.kind === 'open' || state.kind === 'saving' || state.kind === 'failed') && (
        <form className="step-completion__form" onSubmit={(event) => void submit(event)}>
          <p>Отмечаем выполненным: «{stepLabel}»{deadline}. Запись попадёт в историю, а текущий шаг заменится новым.</p>
          <label>
            Результат (необязательно)
            <textarea value={result} maxLength={4000} onChange={(event) => edit(() => setResult(event.target.value))} />
          </label>
          <label>
            Новый следующий шаг
            <textarea value={nextAction} maxLength={500} onChange={(event) => edit(() => setNextAction(event.target.value))} />
          </label>
          <label>
            Срок нового шага
            <input type="datetime-local" value={nextActionAt} onChange={(event) => edit(() => setNextActionAt(event.target.value))} />
          </label>
          {nextAction.trim().length === 0 && nextActionAt.length === 0 && (
            <p className="step-completion__hint">Если новый шаг не указать, у работы не будет следующего шага и срока.</p>
          )}
          {state.kind === 'failed' && (
            <div className="interaction-command-error" role="alert">
              <p>{commandErrorText(state.error, 'отметить шаг')}</p>
              {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
              {state.error instanceof ApiError && state.error.status === 409 && (
                <button type="button" onClick={onReload}>Обновить карточку</button>
              )}
            </div>
          )}
          <div className="step-completion__actions">
            <button type="submit" disabled={state.kind === 'saving'}>
              {state.kind === 'saving' ? 'Сохраняем…' : 'Отметить выполненным'}
            </button>
            <button type="button" className="button--secondary" disabled={state.kind === 'saving'} onClick={() => setState({ kind: 'closed' })}>
              Отмена
            </button>
          </div>
        </form>
      )}
    </section>
  )
}
