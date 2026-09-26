import { useCallback, useEffect, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type EnrolmentStreamLearners,
  type RosterExport,
  type StreamLearner
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { LmsRosterDialog } from './LmsRosterDialog'
import { QuestionnaireImportDialog } from './QuestionnaireImportDialog'
import {
  type AccessErrorReporter,
  fieldCodeLabels,
  formatDateTime,
  formatIsoDate,
  learnerFullName,
  lmsStatusLabel,
  requestIdOf,
  responseErrorMessage
} from './enrolmentShared'

type EnrolmentStreamViewProps = {
  streamId: string
  onBack: () => void
  onOpenLearner: (learnerId: string) => void
  onAccessError: AccessErrorReporter
}

type StreamState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: EnrolmentStreamLearners }
  | { kind: 'failed'; error: unknown }

type EndDateState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

type MarkState =
  | { exportId: string; kind: 'busy' }
  | { exportId: string; kind: 'done'; marked: number }
  | { exportId: string; kind: 'failed'; error: unknown }

const learnerNotes = (learner: StreamLearner): string[] => {
  const notes: string[] = []
  if (learner.status === 'RESTRICTED') {
    notes.push('Обработка ограничена')
  }
  if (learner.duplicateEmail) {
    notes.push('email совпадает с другим слушателем')
  }
  if (learner.missingForLms.length > 0) {
    notes.push(`нет обязательных для LMS: ${fieldCodeLabels(learner.missingForLms)}`)
  }
  return notes
}

export const EnrolmentStreamView = ({ streamId, onBack, onOpenLearner, onAccessError }: EnrolmentStreamViewProps) => {
  const [state, setState] = useState<StreamState>({ kind: 'loading' })
  const [endsOnDraft, setEndsOnDraft] = useState('')
  const [endDateKey, setEndDateKey] = useState<string | null>(null)
  const [endDateState, setEndDateState] = useState<EndDateState>({ kind: 'idle' })
  const [endDateConflict, setEndDateConflict] = useState(false)
  const [markState, setMarkState] = useState<MarkState | null>(null)
  const [openDialog, setOpenDialog] = useState<'template' | 'lms' | null>(null)

  const load = useCallback(async () => {
    try {
      const data = await apiClient.listEnrolmentStreamLearners(streamId)
      setState({ kind: 'ready', data })
      setEndsOnDraft(data.stream.endsOn ?? '')
      setEndDateState({ kind: 'idle' })
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      setState({ kind: 'failed', error })
    }
  }, [onAccessError, streamId])

  useEffect(() => {
    void load()
  }, [load])

  const saveEndDate = async () => {
    if (state.kind !== 'ready' || endDateState.kind === 'saving') {
      return
    }
    const key = endDateKey ?? createIdempotencyKey()
    setEndDateKey(key)
    setEndDateState({ kind: 'saving' })
    try {
      const stream = await apiClient.updateEnrolmentStream(
        streamId,
        { version: state.data.stream.version, endsOn: endsOnDraft === '' ? null : endsOnDraft },
        key
      )
      setEndDateKey(null)
      setState({ kind: 'ready', data: { ...state.data, stream } })
      setEndDateState({ kind: 'idle' })
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      if (error instanceof ApiError && error.code === 'VERSION_CONFLICT') {
        setEndDateKey(null)
        await load()
        setEndDateConflict(true)
        return
      }
      setEndDateState({ kind: 'failed', error })
    }
  }

  const markTransferred = async (exportId: RosterExport['exportId']) => {
    setMarkState({ exportId, kind: 'busy' })
    try {
      const result = await apiClient.markRosterExportTransferred(exportId, createIdempotencyKey())
      setMarkState({ exportId, kind: 'done', marked: result.marked })
      await load()
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      setMarkState({ exportId, kind: 'failed', error })
    }
  }

  if (state.kind === 'loading') {
    return (
      <section className="enrolment" aria-labelledby="enrolment-stream-title">
        <h2 id="enrolment-stream-title">Поток</h2>
        <p role="status">Загружаем поток…</p>
      </section>
    )
  }

  if (state.kind === 'failed') {
    return (
      <section className="enrolment" aria-labelledby="enrolment-stream-title">
        <h2 id="enrolment-stream-title">Поток</h2>
        <div className="notice notice--error" role="alert">
          <p>{responseErrorMessage(state.error)}</p>
          <SupportDetails requestId={requestIdOf(state.error)} />
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
        <button type="button" className="button--secondary" onClick={onBack}>Назад к потокам</button>
      </section>
    )
  }

  const { stream, learners, exports, roster } = state.data

  return (
    <section className="enrolment" aria-labelledby="enrolment-stream-title">
      <div>
        <p className="eyebrow">Оператор зачисления</p>
        <h2 id="enrolment-stream-title">{stream.courseName}, поток {stream.streamNo}</h2>
        <p>{stream.programName ?? 'Программа не сопоставлена'}</p>
      </div>

      <div className="enrolment__end-date">
        <label>
          Дата окончания потока
          <input
            type="date"
            value={endsOnDraft}
            disabled={endDateState.kind === 'saving'}
            onChange={(event) => {
              setEndsOnDraft(event.target.value)
              setEndDateKey(null)
              setEndDateConflict(false)
            }}
          />
        </label>
        <button type="button" disabled={endDateState.kind === 'saving'} onClick={() => void saveEndDate()}>
          {endDateState.kind === 'saving' ? 'Сохраняем…' : 'Сохранить'}
        </button>
        <p>Хранить до: {stream.keepUntil === null ? 'срок не определён' : formatIsoDate(stream.keepUntil)}</p>
      </div>
      {endDateConflict && <p className="notice" role="status">Поток уже изменили, данные обновлены.</p>}
      {endDateState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(endDateState.error)}</p>
          {endDateState.error instanceof ApiError && (
            <SupportDetails requestId={endDateState.error.requestId} code={endDateState.error.code} />
          )}
        </div>
      )}

      <div className="enrolment__stream-actions">
        <button type="button" className="button--secondary" onClick={() => setOpenDialog(openDialog === 'template' ? null : 'template')}>
          Загрузить заполненный шаблон
        </button>
        <button type="button" className="button--secondary" onClick={() => setOpenDialog(openDialog === 'lms' ? null : 'lms')}>
          Выгрузить для LMS
        </button>
      </div>

      {openDialog === 'template' && (
        <QuestionnaireImportDialog
          streamId={streamId}
          onApplied={() => void load()}
          onAccessError={onAccessError}
          onClose={() => setOpenDialog(null)}
        />
      )}
      {openDialog === 'lms' && (
        <LmsRosterDialog
          streamId={streamId}
          roster={roster}
          exports={exports}
          onExported={() => void load()}
          onAccessError={onAccessError}
          onClose={() => setOpenDialog(null)}
        />
      )}

      <section className="enrolment__section" aria-labelledby="enrolment-stream-learners-title">
        <h3 id="enrolment-stream-learners-title">Слушатели потока</h3>
        {learners.length === 0 ? (
          <p>В потоке пока нет слушателей.</p>
        ) : (
          <div className="catalog-import__table-scroll">
            <table aria-label="Слушатели потока">
              <thead>
                <tr>
                  <th scope="col">ФИО</th>
                  <th scope="col">Номер заявки</th>
                  <th scope="col">Телефон</th>
                  <th scope="col">Email</th>
                  <th scope="col">Полнота</th>
                  <th scope="col">Статус LMS</th>
                  <th scope="col">Пометки</th>
                  <th scope="col">Действие</th>
                </tr>
              </thead>
              <tbody>
                {learners.map((learner) => {
                  const notes = learnerNotes(learner)
                  return (
                    <tr key={learner.learnerId}>
                      <td>{learner.status === 'ANONYMIZED' ? 'Анкета обезличена' : learnerFullName(learner)}</td>
                      <td>{learner.orderNumber ?? '—'}</td>
                      <td>{learner.phone ?? '—'}</td>
                      <td>{learner.email ?? '—'}</td>
                      <td>{learner.complete ? 'Анкета заполнена' : `Заполнено ${learner.filledFields} из ${learner.requiredFields}`}</td>
                      <td>{lmsStatusLabel(learner)}</td>
                      <td>{notes.length === 0 ? '—' : notes.join('; ')}</td>
                      <td>
                        <button type="button" className="button--secondary" onClick={() => onOpenLearner(learner.learnerId)}>
                          Открыть анкету
                        </button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="enrolment__section" aria-labelledby="enrolment-stream-exports-title">
        <h3 id="enrolment-stream-exports-title">Выгрузки для LMS</h3>
        {exports.length === 0 ? (
          <p>Выгрузок пока не было.</p>
        ) : (
          <ul className="enrolment__exports">
            {exports.map((rosterExport) => (
              <li key={rosterExport.exportId}>
                <span>
                  {formatDateTime(rosterExport.exportedAt)} — строк: {rosterExport.rows}, передано: {rosterExport.transferred}
                </span>
                <button
                  type="button"
                  className="button--secondary"
                  disabled={markState?.kind === 'busy'}
                  onClick={() => void markTransferred(rosterExport.exportId)}
                >
                  {markState?.exportId === rosterExport.exportId && markState.kind === 'busy' ? 'Отмечаем…' : 'Отметить переданными'}
                </button>
                {markState?.exportId === rosterExport.exportId && markState.kind === 'done' && (
                  <p role="status">
                    {markState.marked === 0 ? 'В выгрузке нет неотмеченных слушателей' : `Отмечено переданными: ${markState.marked}`}
                  </p>
                )}
                {markState?.exportId === rosterExport.exportId && markState.kind === 'failed' && (
                  <div className="interaction-command-error" role="alert">
                    <p>{responseErrorMessage(markState.error)}</p>
                    {markState.error instanceof ApiError && (
                      <SupportDetails requestId={markState.error.requestId} code={markState.error.code} />
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <button type="button" className="button--secondary" onClick={onBack}>Назад к потокам</button>
    </section>
  )
}
