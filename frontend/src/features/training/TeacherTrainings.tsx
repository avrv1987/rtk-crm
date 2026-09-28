import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  apiClient,
  createIdempotencyKey,
  type Interaction,
  type TeacherTraining
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { accessHandled, errorText, fieldErrors, formatDate, formatDateTime, requestIdOf, todayIso } from '../sources/sourceFormat'
import '../sources/sources.css'

type TeacherTrainingsProps = {
  interaction: Interaction
  canEdit: boolean
  onSaved: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; trainings: TeacherTraining[] }
  | { kind: 'failed'; requestId: string | undefined }

type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved' }
  | { kind: 'failed'; error: unknown }

type Draft = {
  trainedOn: string
  courseName: string
  enrolledCount: string
  completedCount: string
  attachmentId: string
  nextCycleOn: string
  remind: boolean
}

const emptyDraft = (): Draft => ({
  trainedOn: todayIso(),
  courseName: '',
  enrolledCount: '',
  completedCount: '',
  attachmentId: '',
  nextCycleOn: '',
  remind: true
})

export const TeacherTrainings = ({
  interaction,
  canEdit,
  onSaved,
  onSessionExpired,
  onProfileUnavailable
}: TeacherTrainingsProps) => {
  const [list, setList] = useState<ListState>({ kind: 'loading' })
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<Draft>(emptyDraft)
  const [save, setSave] = useState<SaveState>({ kind: 'idle' })
  const saveKey = useRef<string | null>(null)

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  useEffect(() => {
    setOpen(false)
    setDraft(emptyDraft())
    setSave({ kind: 'idle' })
    saveKey.current = null
  }, [interaction.id])

  useEffect(() => {
    let active = true
    apiClient.listTeacherTrainings(interaction.id)
      .then((trainings) => {
        if (active) {
          setList({ kind: 'ready', trainings })
        }
      })
      .catch((error: unknown) => {
        if (active && !handled(error)) {
          setList({ kind: 'failed', requestId: requestIdOf(error) })
        }
      })
    return () => {
      active = false
    }
  }, [handled, interaction.id, interaction.version])

  const documents = interaction.attachments.filter((attachment) => (
    attachment.status === 'CLEAN' && attachment.eventId === null && attachment.stageId === interaction.currentStageId
  ))

  const update = (patch: Partial<Draft>) => {
    saveKey.current = null
    setDraft((current) => ({ ...current, ...patch }))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setSave({ kind: 'saving' })
    try {
      await apiClient.createTeacherTraining(interaction.id, {
        version: interaction.version,
        stageId: interaction.currentStageId,
        trainedOn: draft.trainedOn,
        courseName: draft.courseName,
        enrolledCount: Number(draft.enrolledCount),
        completedCount: draft.completedCount === '' ? null : Number(draft.completedCount),
        attachmentId: draft.attachmentId === '' ? null : draft.attachmentId,
        nextCycleOn: draft.nextCycleOn === '' ? null : draft.nextCycleOn,
        remind: draft.nextCycleOn !== '' && draft.remind
      }, saveKey.current ?? (saveKey.current = createIdempotencyKey()))
      saveKey.current = null
      setDraft(emptyDraft())
      setOpen(false)
      setSave({ kind: 'saved' })
      onSaved()
    } catch (error) {
      if (!handled(error)) {
        setSave({ kind: 'failed', error })
      }
    }
  }

  const trainings = list.kind === 'ready' ? list.trainings : []
  const nextCycle = trainings.find((training) => training.nextCycleOn)?.nextCycleOn

  return (
    <section className="interaction-learning" aria-labelledby="teacher-trainings-title">
      <h6 id="teacher-trainings-title">Обучение преподавателей (повышение квалификации)</h6>
      <p>Записи о повышении квалификации преподавателей вуза. Они не входят в число обучающихся и в отчёт «Востребованность программ».</p>
      {list.kind === 'loading' && <p role="status">Загружаем записи об обучении преподавателей…</p>}
      {list.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить записи об обучении преподавателей.</p>
          <SupportDetails requestId={list.requestId} />
        </div>
      )}
      {list.kind === 'ready' && trainings.length === 0 && <p>Записей пока нет.</p>}
      {nextCycle && <p><strong>Следующий цикл повышения квалификации:</strong> {formatDate(nextCycle)}</p>}
      {trainings.length > 0 && (
        <ul className="source-list">
          {trainings.map((training) => (
            <li key={training.id}>
              <strong>{training.courseName}, {formatDate(training.trainedOn)}</strong>
              <span>
                Записано: {training.enrolledCount}; завершили: {training.completedCount ?? 'нет данных'}
                {training.nextCycleOn ? `; следующий цикл: ${formatDate(training.nextCycleOn)}` : ''}
              </span>
              <span>Документ о ПК: {training.attachmentName ?? 'не приложен'}</span>
              <span className="data-sources__hint">
                Внёс(ла) {training.createdByName ?? 'пользователь'} {formatDateTime(training.createdAt)}
              </span>
            </li>
          ))}
        </ul>
      )}
      {save.kind === 'saved' && <p role="status">Запись об обучении преподавателей сохранена и добавлена в историю.</p>}
      {canEdit && !open && (
        <div className="source-panel__row">
          <button type="button" className="button--secondary" onClick={() => { setOpen(true); setSave({ kind: 'idle' }) }}>
            Добавить запись об обучении
          </button>
        </div>
      )}
      {canEdit && open && (
        <form className="source-form" onSubmit={(event) => void submit(event)}>
          <label>
            <span>Дата обучения<span className="required-mark" aria-hidden="true"> *</span></span>
            <input type="date" required max={todayIso()} value={draft.trainedOn} onChange={(event) => update({ trainedOn: event.target.value })} />
          </label>
          <label>
            <span>Курс<span className="required-mark" aria-hidden="true"> *</span></span>
            <input required maxLength={300} value={draft.courseName} onChange={(event) => update({ courseName: event.target.value })} />
          </label>
          <label>
            <span>Записано преподавателей<span className="required-mark" aria-hidden="true"> *</span></span>
            <input type="number" required min={0} value={draft.enrolledCount} onChange={(event) => update({ enrolledCount: event.target.value })} />
          </label>
          <label>
            Завершили (пусто — нет данных)
            <input type="number" min={0} value={draft.completedCount} onChange={(event) => update({ completedCount: event.target.value })} />
          </label>
          <label className="source-form__wide">
            Документ о повышении квалификации
            <select value={draft.attachmentId} onChange={(event) => update({ attachmentId: event.target.value })}>
              <option value="">Без документа</option>
              {documents.map((attachment) => (
                <option key={attachment.id} value={attachment.id}>{attachment.originalName}</option>
              ))}
            </select>
            {documents.length === 0 && (
              <span>Чтобы приложить документ, сначала загрузите его во вкладке «Документы» на текущем этапе.</span>
            )}
          </label>
          <label>
            Дата следующего цикла ПК
            <input
              type="date"
              min={draft.trainedOn === '' ? undefined : draft.trainedOn}
              value={draft.nextCycleOn}
              onChange={(event) => update({ nextCycleOn: event.target.value })}
            />
          </label>
          <label className="source-form__check">
            <input
              type="checkbox"
              checked={draft.remind}
              disabled={draft.nextCycleOn === ''}
              onChange={(event) => update({ remind: event.target.checked })}
            />
            Напомнить: поставить следующим шагом карточки
          </label>
          <div className="source-form__actions">
            <button type="submit" disabled={save.kind === 'saving'}>
              {save.kind === 'saving' ? 'Сохраняем…' : 'Сохранить запись'}
            </button>
            <button type="button" className="button--secondary" onClick={() => setOpen(false)} disabled={save.kind === 'saving'}>
              Отмена
            </button>
          </div>
          {save.kind === 'failed' && (
            <div className="source-form__wide" role="alert">
              <p className="source-error">{errorText(save.error, 'Запись не сохранена.')}</p>
              {fieldErrors(save.error).map((text) => <p key={text}>{text}</p>)}
              <SupportDetails requestId={requestIdOf(save.error)} />
            </div>
          )}
        </form>
      )}
    </section>
  )
}
