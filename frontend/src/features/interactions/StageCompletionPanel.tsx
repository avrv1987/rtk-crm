import { type FormEvent, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Attachment,
  type Interaction,
  type InteractionStage
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { todayInMoscow } from '../../shared/format/datetime'

type StageCompletionPanelProps = {
  interaction: Interaction
  onChanged: (interaction: Interaction) => void
  onReload: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving'; clearingStageId?: InteractionStage['id'] }
  | { kind: 'failed'; error: unknown; version: number }

type PendingCommand = {
  version: number
  key: string
}

const moscowToday = todayInMoscow

export const formatCompletionDate = (value: string) => value.split('-').reverse().join('.')

const failureMessage = (error: unknown) => {
  if (error instanceof ApiError && error.status === 409) {
    return 'Карточку уже изменили. Выбор сохранён; обновите карточку перед новой отправкой.'
  }
  if (error instanceof ApiError) {
    const reason = Object.values(error.fieldErrors ?? {})[0] ?? error.message
    return `Отметка не сохранена: ${reason}`
  }
  return 'Не удалось связаться с сервисом. Выбор сохранён; повторите попытку позже.'
}

const keyFor = (pending: { current: PendingCommand | null }, version: number) => {
  if (pending.current === null || pending.current.version !== version) {
    pending.current = { version, key: createIdempotencyKey() }
  }
  return pending.current.key
}

export const StageCompletionPanel = ({
  interaction,
  onChanged,
  onReload,
  onSessionExpired,
  onProfileUnavailable
}: StageCompletionPanelProps) => {
  const [stageId, setStageId] = useState('')
  const [completedOn, setCompletedOn] = useState(moscowToday)
  const [comment, setComment] = useState('')
  const [attachmentIds, setAttachmentIds] = useState<Attachment['id'][]>([])
  const [state, setState] = useState<CommandState>({ kind: 'idle' })
  const markCommand = useRef<PendingCommand | null>(null)
  const clearCommand = useRef<PendingCommand | null>(null)
  const clearCommandStage = useRef<InteractionStage['id'] | null>(null)

  const stageNames = new Map(interaction.stages.map((stage) => [stage.id, stage.name]))
  const completionByStage = new Map(interaction.stageCompletions.map((completion) => [completion.stageId, completion]))
  const markableStages = interaction.stages.filter((stage) => stage.id !== interaction.currentStageId)
  const stageFiles = interaction.attachments.filter((attachment) => (
    attachment.status === 'CLEAN' && attachment.eventId === null && attachment.stageId === stageId
  ))
  const saving = state.kind === 'saving'
  const failure = state.kind === 'failed' && state.version === interaction.version ? state.error : undefined

  const edited = () => {
    markCommand.current = null
    setState({ kind: 'idle' })
  }

  const handleFailure = (error: unknown) => {
    if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
      onSessionExpired()
      return
    }
    if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
      onProfileUnavailable(error.requestId)
      return
    }
    setState({ kind: 'failed', error, version: interaction.version })
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (saving || stageId.length === 0 || completedOn.length === 0) {
      return
    }
    setState({ kind: 'saving' })
    try {
      const updated = await apiClient.completeInteractionStage(
        interaction.id,
        {
          version: interaction.version,
          stageId,
          completedOn,
          comment: comment.trim().length > 0 ? comment.trim() : null,
          attachmentIds: attachmentIds.filter((id) => stageFiles.some((attachment) => attachment.id === id))
        },
        keyFor(markCommand, interaction.version)
      )
      markCommand.current = null
      setStageId('')
      setCompletedOn(moscowToday())
      setComment('')
      setAttachmentIds([])
      setState({ kind: 'idle' })
      onChanged(updated)
    } catch (error) {
      handleFailure(error)
    }
  }

  const clear = async (target: InteractionStage['id']) => {
    if (saving) {
      return
    }
    if (clearCommandStage.current !== target) {
      clearCommand.current = null
      clearCommandStage.current = target
    }
    setState({ kind: 'saving', clearingStageId: target })
    try {
      const updated = await apiClient.clearInteractionStageCompletion(
        interaction.id,
        target,
        interaction.version,
        keyFor(clearCommand, interaction.version)
      )
      clearCommand.current = null
      clearCommandStage.current = null
      setState({ kind: 'idle' })
      onChanged(updated)
    } catch (error) {
      handleFailure(error)
    }
  }

  const toggleAttachment = (id: Attachment['id']) => {
    setAttachmentIds((current) => current.includes(id) ? current.filter((value) => value !== id) : [...current, id])
    edited()
  }

  return (
    <section className="interaction-stage-editor stage-completion" aria-labelledby="stage-completion-title">
      <h6 id="stage-completion-title">Отметить этап выполненным</h6>
      <p>Текущий этап не меняется. Так фиксируют работу, которая идёт параллельно, например обучение преподавателей во время сопровождения внедрения.</p>
      {interaction.stageCompletions.length > 0 && (
        <ul className="stage-completion__list" aria-label="Этапы, отмеченные выполненными">
          {interaction.stageCompletions.map((completion) => (
            <li key={completion.stageId}>
              <div>
                <strong>{stageNames.get(completion.stageId) ?? 'Этап недоступен'}</strong>
                <span>Выполнен {formatCompletionDate(completion.completedOn)}; отметка: {completion.actorDisplayName}</span>
                {completion.comment !== null && <p>{completion.comment}</p>}
              </div>
              <button
                type="button"
                className="button--secondary"
                disabled={saving}
                onClick={() => void clear(completion.stageId)}
              >
                {state.kind === 'saving' && state.clearingStageId === completion.stageId ? 'Снимаем…' : 'Снять отметку'}
              </button>
            </li>
          ))}
        </ul>
      )}
      <form className="stage-completion__form" onSubmit={(event) => void submit(event)}>
        <label>
          <span>Этап<span className="required-mark" aria-hidden="true"> *</span></span>
          <select
            value={stageId}
            required
            disabled={saving}
            onChange={(event) => {
              setStageId(event.target.value)
              setAttachmentIds([])
              edited()
            }}
          >
            <option value="">Выберите этап</option>
            {markableStages.map((stage) => {
              const completion = completionByStage.get(stage.id)
              return (
                <option key={stage.id} value={stage.id}>
                  {stage.order + 1}. {stage.name}{completion === undefined ? '' : ` (выполнен ${formatCompletionDate(completion.completedOn)})`}
                </option>
              )
            })}
          </select>
        </label>
        <label>
          <span>Дата выполнения<span className="required-mark" aria-hidden="true"> *</span></span>
          <input
            type="date"
            value={completedOn}
            max={moscowToday()}
            required
            disabled={saving}
            onChange={(event) => {
              setCompletedOn(event.target.value)
              edited()
            }}
          />
        </label>
        <label>
          Комментарий к отметке
          <textarea
            value={comment}
            maxLength={4000}
            disabled={saving}
            placeholder="Например, обучено 5 преподавателей"
            onChange={(event) => {
              setComment(event.target.value)
              edited()
            }}
          />
        </label>
        <fieldset className="interaction-catalog-picker">
          <legend>Файлы этапа</legend>
          {stageId.length === 0 && <p>Сначала выберите этап.</p>}
          {stageId.length > 0 && stageFiles.length === 0 && (
            <p>Для этого этапа нет очищенных файлов без события. Файл можно загрузить в блоке «Загрузить документ», выбрав этот этап.</p>
          )}
          {stageFiles.length > 0 && (
            <ul>
              {stageFiles.map((attachment) => (
                <li key={attachment.id}>
                  <label>
                    <input
                      type="checkbox"
                      checked={attachmentIds.includes(attachment.id)}
                      disabled={saving}
                      onChange={() => toggleAttachment(attachment.id)}
                    />
                    <span>{attachment.originalName}</span>
                  </label>
                </li>
              ))}
            </ul>
          )}
        </fieldset>
        <button type="submit" disabled={saving || stageId.length === 0 || completedOn.length === 0}>
          {state.kind === 'saving' && state.clearingStageId === undefined ? 'Сохраняем…' : 'Отметить выполненным'}
        </button>
      </form>
      {failure !== undefined && (
        <div className="interaction-command-error" role="alert">
          <p>{failureMessage(failure)}</p>
          {failure instanceof ApiError && <SupportDetails requestId={failure.requestId} code={failure.code} />}
          {failure instanceof ApiError && failure.status === 409 && (
            <button type="button" onClick={onReload}>Обновить карточку</button>
          )}
        </div>
      )}
    </section>
  )
}
