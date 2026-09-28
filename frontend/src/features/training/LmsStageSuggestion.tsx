import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { apiClient, createIdempotencyKey, type Interaction } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { todayInMoscow } from '../../shared/format/datetime'
import { accessHandled, errorText, fieldErrors, formatDate, requestIdOf } from '../sources/sourceFormat'
import { findLmsStageSuggestion, type LmsStageSuggestion } from './lmsStageSuggestionRules'
import '../sources/sources.css'

type LmsStageSuggestionPanelProps = {
  interaction: Interaction
  canEdit: boolean
  onChanged: (interaction: Interaction, text?: string) => void
  onOpenTransition: () => void
  onOpenCycles: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type SnapshotsState =
  | { kind: 'loading' }
  | { kind: 'ready'; suggestion: LmsStageSuggestion | null }
  | { kind: 'failed' }

type Draft = {
  trainedOn: string
  courseName: string
  enrolledCount: string
  completedCount: string
}

const draftFrom = (suggestion: LmsStageSuggestion): Draft => ({
  trainedOn: suggestion.defaultTrainedOn,
  courseName: suggestion.snapshot.courseName,
  enrolledCount: String(suggestion.snapshot.participants),
  completedCount: suggestion.snapshot.completed === null || suggestion.snapshot.completed === undefined
    ? ''
    : String(suggestion.snapshot.completed)
})

export const LmsStageSuggestionPanel = ({
  interaction,
  canEdit,
  onChanged,
  onOpenTransition,
  onOpenCycles,
  onSessionExpired,
  onProfileUnavailable
}: LmsStageSuggestionPanelProps) => {
  const [state, setState] = useState<SnapshotsState>({ kind: 'loading' })
  const [confirming, setConfirming] = useState(false)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [failure, setFailure] = useState<unknown>(undefined)
  const trainingKey = useRef<string | null>(null)
  const completionKey = useRef<string | null>(null)

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  useEffect(() => {
    setConfirming(false)
    setDraft(null)
    setFailure(undefined)
  }, [interaction.id])

  useEffect(() => {
    if (interaction.program === null) {
      setState({ kind: 'ready', suggestion: null })
      return
    }
    let active = true
    setState({ kind: 'loading' })
    apiClient.listInteractionLearningSnapshots(interaction.id)
      .then((snapshots) => {
        if (active) {
          setState({ kind: 'ready', suggestion: findLmsStageSuggestion(interaction, snapshots, todayInMoscow()) })
        }
      })
      .catch((error: unknown) => {
        if (active && !handled(error)) {
          setState({ kind: 'failed' })
        }
      })
    return () => {
      active = false
    }
  }, [handled, interaction, interaction.version])

  if (!canEdit || state.kind === 'loading' || state.kind === 'failed') {
    return null
  }
  const { suggestion } = state
  if (suggestion === null) {
    return null
  }

  const openConfirm = () => {
    trainingKey.current = null
    completionKey.current = null
    setDraft(draftFrom(suggestion))
    setFailure(undefined)
    setConfirming(true)
  }

  const cancel = () => {
    setConfirming(false)
    setDraft(null)
    setFailure(undefined)
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (draft === null || saving) {
      return
    }
    setSaving(true)
    setFailure(undefined)
    try {
      const created = await apiClient.createTeacherTraining(interaction.id, {
        version: interaction.version,
        stageId: suggestion.stage.id,
        trainedOn: draft.trainedOn,
        courseName: draft.courseName,
        enrolledCount: Number(draft.enrolledCount),
        completedCount: draft.completedCount === '' ? null : Number(draft.completedCount),
        attachmentId: null,
        nextCycleOn: null,
        remind: false
      }, trainingKey.current ?? (trainingKey.current = createIdempotencyKey()))
      trainingKey.current = null
      let latest = created.interaction
      if (!suggestion.isCurrentStage) {
        latest = await apiClient.completeInteractionStage(
          interaction.id,
          {
            version: latest.version,
            stageId: suggestion.stage.id,
            completedOn: draft.trainedOn,
            comment: 'Отмечено по данным LMS.',
            attachmentIds: []
          },
          completionKey.current ?? (completionKey.current = createIdempotencyKey())
        )
        completionKey.current = null
      }
      setConfirming(false)
      setDraft(null)
      onChanged(
        latest,
        suggestion.isCurrentStage
          ? 'Запись об обучении преподавателей сохранена по данным LMS. Чтобы завершить этап, перейдите на следующий.'
          : 'Запись об обучении преподавателей сохранена, этап отмечен выполненным по данным LMS.'
      )
      if (suggestion.isCurrentStage) {
        onOpenTransition()
      }
    } catch (error) {
      if (!handled(error)) {
        setFailure(error)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="interaction-learning lms-stage-suggestion" aria-labelledby="lms-stage-suggestion-title">
      <h6 id="lms-stage-suggestion-title">Предложение по данным LMS</h6>
      <p>
        Поток «{suggestion.snapshot.courseName}»{suggestion.snapshot.groupName ? `, группа «${suggestion.snapshot.groupName}»` : ''}:
        записано {suggestion.snapshot.participants}, завершили {suggestion.snapshot.completed}. Этап «{suggestion.stage.name}» этой
        работы ещё не отмечен выполненным.
      </p>
      {!confirming && (
        <div className="source-panel__row">
          <button type="button" onClick={openConfirm}>Отметить этап выполненным по данным LMS</button>
          {suggestion.offerCycle && (
            <button type="button" className="button--secondary" onClick={onOpenCycles}>Запустить новый цикл</button>
          )}
        </div>
      )}
      {confirming && draft !== null && (
        <form className="source-form" onSubmit={(event) => void submit(event)}>
          <p className="source-form__wide">
            Запись «Обучение преподавателей» заполнена по данным LMS. Проверьте значения перед сохранением.
          </p>
          <label>
            <span>Дата обучения<span className="required-mark" aria-hidden="true"> *</span></span>
            <input
              type="date"
              required
              max={todayInMoscow()}
              value={draft.trainedOn}
              disabled={saving}
              onChange={(event) => setDraft({ ...draft, trainedOn: event.target.value })}
            />
          </label>
          <label>
            <span>Курс<span className="required-mark" aria-hidden="true"> *</span></span>
            <input
              required
              maxLength={300}
              value={draft.courseName}
              disabled={saving}
              onChange={(event) => setDraft({ ...draft, courseName: event.target.value })}
            />
          </label>
          <label>
            <span>Записано преподавателей<span className="required-mark" aria-hidden="true"> *</span></span>
            <input
              type="number"
              required
              min={0}
              value={draft.enrolledCount}
              disabled={saving}
              onChange={(event) => setDraft({ ...draft, enrolledCount: event.target.value })}
            />
          </label>
          <label>
            Завершили (пусто — нет данных)
            <input
              type="number"
              min={0}
              value={draft.completedCount}
              disabled={saving}
              onChange={(event) => setDraft({ ...draft, completedCount: event.target.value })}
            />
          </label>
          <div className="source-form__actions">
            <button type="submit" disabled={saving}>
              {saving ? 'Сохраняем…' : suggestion.isCurrentStage ? 'Подтвердить и перейти на следующий этап' : 'Подтвердить и отметить этап'}
            </button>
            <button type="button" className="button--secondary" onClick={cancel} disabled={saving}>Отмена</button>
          </div>
          {failure !== undefined && (
            <div className="source-form__wide" role="alert">
              <p className="source-error">{errorText(failure, 'Предложение не подтверждено.')}</p>
              {fieldErrors(failure).map((text) => <p key={text}>{text}</p>)}
              <SupportDetails requestId={requestIdOf(failure)} />
            </div>
          )}
        </form>
      )}
      <p className="data-sources__hint">Поток: {formatDate(suggestion.snapshot.runStartsOn)} — {formatDate(suggestion.snapshot.runEndsOn)}.</p>
    </section>
  )
}
