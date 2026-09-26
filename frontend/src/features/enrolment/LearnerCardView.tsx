import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type LearnerCard,
  type LearnerFieldCode,
  type LearnerFieldGroup,
  type LearnerHistoryEntry,
  type LearnerMoveResult,
  type LearnerSummary
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import {
  type AccessErrorReporter,
  type LearnerFieldGroupConfig,
  dateFieldCodes,
  displayFieldValue,
  educationOptions,
  formatDateTime,
  formatIsoDate,
  genderOptions,
  historyDetails,
  learnerFieldGroupLabels,
  learnerFieldGroupsConfig,
  learnerFieldLabels,
  learnerFullName,
  lmsStatusLabel,
  requestIdOf,
  responseErrorMessage,
  snilsDigits
} from './enrolmentShared'

type LearnerCardViewProps = {
  learnerId: string
  initialNotice?: string
  onBack: () => void
  onNavigateLearner: (learnerId: string, notice?: string) => void
  onAccessError: AccessErrorReporter
}

type CardState =
  | { kind: 'loading' }
  | { kind: 'ready'; learner: LearnerCard }
  | { kind: 'failed'; error: unknown }

type HistoryState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; entries: LearnerHistoryEntry[] }
  | { kind: 'failed'; error: unknown }

type MoveProposal = { snils: string; found: LearnerSummary }

type SaveError = {
  fieldErrors: Record<string, string>
  externalErrors: Array<{ code: string; message: string }>
  message: string | null
}

type LearnerGroupCardProps = {
  learnerId: string
  version: number
  config: LearnerFieldGroupConfig
  values: Record<string, string>
  revealedValues: Record<string, string> | undefined
  editable: boolean
  onRevealed: (group: LearnerFieldGroup, values: Record<string, string>) => void
  onSaved: (learner: LearnerCard, group: LearnerFieldGroup) => void
  onReloadRequested: () => void
  onSnilsConflict: (snils: string, found: LearnerSummary) => void
  onAccessError: AccessErrorReporter
}

const LearnerGroupCard = ({
  learnerId,
  version,
  config,
  values,
  revealedValues,
  editable,
  onRevealed,
  onSaved,
  onReloadRequested,
  onSnilsConflict,
  onAccessError
}: LearnerGroupCardProps) => {
  const [revealBusy, setRevealBusy] = useState(false)
  const [revealError, setRevealError] = useState<unknown>(null)
  const [editDraft, setEditDraft] = useState<Record<string, string> | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<SaveError | null>(null)
  const [versionConflict, setVersionConflict] = useState(false)
  const editKeyRef = useRef<string | null>(null)

  const reveal = async (): Promise<Record<string, string> | null> => {
    setRevealBusy(true)
    setRevealError(null)
    try {
      const result = await apiClient.revealLearnerFields(learnerId, { groups: [config.group] })
      onRevealed(config.group, result.values)
      return result.values
    } catch (error) {
      if (onAccessError(error)) {
        return null
      }
      setRevealError(error)
      return null
    } finally {
      setRevealBusy(false)
    }
  }

  const startEdit = async () => {
    const revealedNow = await reveal()
    if (revealedNow === null) {
      return
    }
    editKeyRef.current = null
    setSaveError(null)
    setVersionConflict(false)
    setEditDraft(Object.fromEntries(config.fields.map((code) => [code, revealedNow[code] ?? ''])))
  }

  const setDraftValue = (code: LearnerFieldCode, value: string) => {
    editKeyRef.current = null
    setEditDraft((current) => (current === null ? current : { ...current, [code]: value }))
  }

  const checkSnilsConflict = async (value: string) => {
    try {
      const results = await apiClient.searchLearners({ kind: 'SNILS', value })
      const found = results.find((candidate) => candidate.id !== learnerId)
      if (found !== undefined) {
        onSnilsConflict(value, found)
      }
    } catch (error) {
      onAccessError(error)
    }
  }

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (editDraft === null || saving) {
      return
    }
    const fields: Record<string, string> = {}
    for (const code of config.fields) {
      const current = editDraft[code] ?? ''
      const baseline = revealedValues?.[code] ?? ''
      if (current !== baseline) {
        fields[code] = current
      }
    }
    if (Object.keys(fields).length === 0) {
      setEditDraft(null)
      return
    }
    const key = editKeyRef.current ?? (editKeyRef.current = createIdempotencyKey())
    setSaving(true)
    setSaveError(null)
    try {
      const updated = await apiClient.updateLearner(learnerId, { version, fields }, key)
      editKeyRef.current = null
      setEditDraft(null)
      onSaved(updated, config.group)
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      if (error instanceof ApiError && error.code === 'VERSION_CONFLICT') {
        setVersionConflict(true)
        return
      }
      if (error instanceof ApiError && error.status === 400) {
        const fieldCodeSet = new Set<string>(config.fields)
        const fieldErrors: Record<string, string> = {}
        const externalErrors: Array<{ code: string; message: string }> = []
        for (const [code, message] of Object.entries(error.fieldErrors ?? {})) {
          if (fieldCodeSet.has(code)) {
            fieldErrors[code] = message
          } else {
            externalErrors.push({ code, message })
          }
        }
        setSaveError({ fieldErrors, externalErrors, message: null })
        const submittedSnils = fields.SNILS
        if (fieldErrors.SNILS !== undefined && submittedSnils !== undefined && snilsDigits(submittedSnils).length === 11) {
          void checkSnilsConflict(submittedSnils)
        }
        return
      }
      setSaveError({ fieldErrors: {}, externalErrors: [], message: responseErrorMessage(error) })
    } finally {
      setSaving(false)
    }
  }

  const renderInput = (code: LearnerFieldCode) => {
    const value = editDraft?.[code] ?? ''
    const invalid = saveError?.fieldErrors[code] !== undefined
    if (dateFieldCodes.has(code)) {
      return <input type="date" value={value} aria-invalid={invalid} onChange={(event) => setDraftValue(code, event.target.value)} />
    }
    if (code === 'GENDER') {
      return (
        <select value={value} aria-invalid={invalid} onChange={(event) => setDraftValue(code, event.target.value)}>
          <option value="">не указан</option>
          {genderOptions.map((option) => <option key={option} value={option}>{option}</option>)}
        </select>
      )
    }
    if (code === 'EDUCATION') {
      return (
        <select value={value} aria-invalid={invalid} onChange={(event) => setDraftValue(code, event.target.value)}>
          <option value="">не указано</option>
          {educationOptions.map((option) => <option key={option} value={option}>{option}</option>)}
        </select>
      )
    }
    return (
      <input
        type="text"
        inputMode="text"
        autoComplete="off"
        maxLength={500}
        value={value}
        aria-invalid={invalid}
        onChange={(event) => setDraftValue(code, event.target.value)}
      />
    )
  }

  return (
    <section className="enrolment__group" aria-labelledby={`enrolment-group-${config.group}`}>
      <div className="enrolment__group-header">
        <h4 id={`enrolment-group-${config.group}`}>{learnerFieldGroupLabels[config.group]}</h4>
        {editable && editDraft === null && (
          <div className="enrolment__group-actions">
            {config.revealable && (
              <button type="button" className="button--secondary" disabled={revealBusy} onClick={() => void reveal()}>
                {revealBusy ? 'Показываем…' : 'Показать'}
              </button>
            )}
            <button type="button" className="button--secondary" disabled={revealBusy} onClick={() => void startEdit()}>
              Изменить
            </button>
          </div>
        )}
      </div>

      {revealError !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(revealError)}</p>
          {revealError instanceof ApiError && <SupportDetails requestId={revealError.requestId} code={revealError.code} />}
        </div>
      )}

      {editDraft === null ? (
        <dl className="enrolment__group-values">
          {config.fields.map((code) => {
            const raw = revealedValues?.[code] ?? values[code]
            return (
              <div key={code}>
                <dt>{learnerFieldLabels[code]}</dt>
                <dd>{raw === undefined ? 'не заполнено' : displayFieldValue(code, raw)}</dd>
              </div>
            )
          })}
        </dl>
      ) : (
        <form className="enrolment__group-form" onSubmit={(event) => void save(event)}>
          {saveError !== null && saveError.externalErrors.length > 0 && (
            <ul className="structured-api-error">
              {saveError.externalErrors.map((item) => (
                <li key={item.code}>{learnerFieldLabels[item.code as LearnerFieldCode] ?? item.code}: {item.message}</li>
              ))}
            </ul>
          )}
          {saveError !== null && saveError.message !== null && (
            <div className="interaction-command-error" role="alert"><p>{saveError.message}</p></div>
          )}
          {versionConflict && (
            <div className="interaction-command-error" role="alert">
              <p>Анкета изменена другим пользователем: обновите анкету.</p>
              <button
                type="button"
                onClick={() => {
                  setVersionConflict(false)
                  setEditDraft(null)
                  onReloadRequested()
                }}
              >
                Обновить
              </button>
            </div>
          )}
          {config.fields.map((code) => (
            <label key={code}>
              {learnerFieldLabels[code]}
              {renderInput(code)}
              {saveError?.fieldErrors[code] !== undefined && <p className="field-error" role="alert">{saveError.fieldErrors[code]}</p>}
            </label>
          ))}
          <div className="enrolment__dialog-actions">
            <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
            <button type="button" className="button--secondary" disabled={saving} onClick={() => setEditDraft(null)}>Отмена</button>
          </div>
        </form>
      )}
    </section>
  )
}

type LearnerMoveProposalProps = {
  learnerId: string
  version: number
  current: { fullName: string; phone: string | null; email: string | null; streams: string[] }
  snils: string
  found: LearnerSummary
  onCancel: () => void
  onMoved: (result: LearnerMoveResult) => void
  onAccessError: AccessErrorReporter
}

const LearnerMoveProposal = ({ learnerId, version, current, snils, found, onCancel, onMoved, onAccessError }: LearnerMoveProposalProps) => {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  const move = async () => {
    setBusy(true)
    setError(null)
    try {
      const result = await apiClient.moveLearnerEnrolments(learnerId, { snils, version }, createIdempotencyKey())
      onMoved(result)
    } catch (exception) {
      if (onAccessError(exception)) {
        return
      }
      setError(exception)
    } finally {
      setBusy(false)
    }
  }

  const foundName = learnerFullName(found)
  const foundStreams = found.enrolments.map((enrolment) => `${enrolment.courseName}, поток ${enrolment.streamNo}`)

  return (
    <section className="enrolment__move" role="alertdialog" aria-labelledby="enrolment-move-title">
      <h3 id="enrolment-move-title">СНИЛС уже есть у другого слушателя. Перенести зачисления к слушателю с этим СНИЛС?</h3>
      <div className="enrolment__move-compare">
        <div>
          <h4>Текущая анкета</h4>
          <p>{current.fullName || 'не заполнено'}</p>
          <p>{current.phone ?? '—'}, {current.email ?? '—'}</p>
          <p>{current.streams.length === 0 ? 'потоков нет' : current.streams.join('; ')}</p>
        </div>
        <div>
          <h4>Найденный слушатель</h4>
          <p>{foundName || 'не заполнено'}</p>
          <p>{found.phone ?? '—'}, {found.email ?? '—'}</p>
          <p>{foundStreams.length === 0 ? 'потоков нет' : foundStreams.join('; ')}</p>
        </div>
      </div>
      <p>
        Перенос объединит анкеты: зачисления перейдут к найденному слушателю, его заполненные поля не изменятся, пустые
        получат значения этой анкеты, эта анкета будет удалена. Подтвердите, что это один человек, а не опечатка в СНИЛС.
      </p>
      {error !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(error)}</p>
          {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
        </div>
      )}
      <div className="enrolment__dialog-actions">
        <button type="button" className="button--danger" disabled={busy} onClick={() => void move()}>
          {busy ? 'Переносим…' : 'Перенести'}
        </button>
        <button type="button" className="button--secondary" disabled={busy} onClick={onCancel}>Отмена</button>
      </div>
    </section>
  )
}

export const LearnerCardView = ({ learnerId, initialNotice, onBack, onNavigateLearner, onAccessError }: LearnerCardViewProps) => {
  const [state, setState] = useState<CardState>({ kind: 'loading' })
  const [revealed, setRevealed] = useState<Partial<Record<LearnerFieldGroup, Record<string, string>>>>({})
  const [notice, setNotice] = useState<string | null>(initialNotice ?? null)
  const [tab, setTab] = useState<'enrolments' | 'history'>('enrolments')
  const [history, setHistory] = useState<HistoryState>({ kind: 'idle' })
  const [moveProposal, setMoveProposal] = useState<MoveProposal | null>(null)

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      const learner = await apiClient.getLearner(learnerId)
      setState({ kind: 'ready', learner })
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      setState({ kind: 'failed', error })
    }
  }, [learnerId, onAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const loadHistory = async () => {
    setHistory({ kind: 'loading' })
    try {
      const entries = await apiClient.getLearnerHistory(learnerId)
      setHistory({ kind: 'ready', entries })
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      setHistory({ kind: 'failed', error })
    }
  }

  const openTab = (next: 'enrolments' | 'history') => {
    setTab(next)
    if (next === 'history' && history.kind === 'idle') {
      void loadHistory()
    }
  }

  const onRevealed = (group: LearnerFieldGroup, values: Record<string, string>) => {
    setRevealed((current) => ({ ...current, [group]: values }))
  }

  const onSaved = (learner: LearnerCard, group: LearnerFieldGroup) => {
    setState({ kind: 'ready', learner })
    setRevealed((current) => {
      const next = { ...current }
      delete next[group]
      return next
    })
    setNotice('Анкета сохранена')
    if (tab === 'history') {
      void loadHistory()
    } else {
      setHistory({ kind: 'idle' })
    }
  }

  const onMoved = (result: LearnerMoveResult) => {
    setMoveProposal(null)
    onNavigateLearner(
      result.learnerId,
      `Зачисления перенесены: ${result.enrolmentsMoved}; повторных в тех же потоках удалено: ${result.enrolmentsDropped}.`
    )
  }

  if (state.kind === 'loading') {
    return (
      <section className="enrolment" aria-labelledby="enrolment-learner-title">
        <h2 id="enrolment-learner-title">Анкета</h2>
        <p role="status">Загружаем анкету…</p>
      </section>
    )
  }

  if (state.kind === 'failed') {
    return (
      <section className="enrolment" aria-labelledby="enrolment-learner-title">
        <h2 id="enrolment-learner-title">Анкета</h2>
        <div className="notice notice--error" role="alert">
          <p>{responseErrorMessage(state.error)}</p>
          <SupportDetails requestId={requestIdOf(state.error)} />
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
        <button type="button" className="button--secondary" onClick={onBack}>Назад</button>
      </section>
    )
  }

  const { learner } = state
  const editable = learner.status === 'ACTIVE'

  return (
    <section className="enrolment" aria-labelledby="enrolment-learner-title">
      <div>
        <p className="eyebrow">Оператор зачисления</p>
        <h2 id="enrolment-learner-title">Анкета слушателя</h2>
      </div>

      {notice !== null && <p role="status">{notice}</p>}
      {learner.status === 'RESTRICTED' && (
        <p className="notice" role="status">Обработка анкеты ограничена по обращению субъекта: показ и правка недоступны.</p>
      )}
      {learner.status === 'ANONYMIZED' && <p className="notice" role="status">Анкета обезличена.</p>}
      <p>{learner.complete ? 'Анкета заполнена' : `Заполнено ${learner.filledFields} из ${learner.requiredFields}`}</p>

      {moveProposal !== null && (
        <LearnerMoveProposal
          learnerId={learner.id}
          version={learner.version}
          current={{
            fullName: learnerFullName({
              lastName: learner.values.LAST_NAME ?? null,
              firstName: learner.values.FIRST_NAME ?? null,
              middleName: learner.values.MIDDLE_NAME ?? null
            }),
            phone: learner.values.PHONE ?? null,
            email: learner.values.EMAIL ?? null,
            streams: learner.enrolments.map((enrolment) => `${enrolment.courseName}, поток ${enrolment.streamNo}`)
          }}
          snils={moveProposal.snils}
          found={moveProposal.found}
          onCancel={() => setMoveProposal(null)}
          onMoved={onMoved}
          onAccessError={onAccessError}
        />
      )}

      <div className="enrolment__groups">
        {learnerFieldGroupsConfig.map((config) => (
          <LearnerGroupCard
            key={config.group}
            learnerId={learner.id}
            version={learner.version}
            config={config}
            values={learner.values}
            revealedValues={revealed[config.group]}
            editable={editable}
            onRevealed={onRevealed}
            onSaved={onSaved}
            onReloadRequested={() => void load()}
            onSnilsConflict={(snils, found) => setMoveProposal({ snils, found })}
            onAccessError={onAccessError}
          />
        ))}
      </div>

      <div className="enrolment__tabs">
        <button type="button" aria-pressed={tab === 'enrolments'} onClick={() => openTab('enrolments')}>Зачисления</button>
        <button type="button" aria-pressed={tab === 'history'} onClick={() => openTab('history')}>История</button>
      </div>

      {tab === 'enrolments' && (
        learner.enrolments.length === 0 ? <p>Зачислений нет.</p> : (
          <div className="catalog-import__table-scroll">
            <table aria-label="Зачисления слушателя">
              <thead>
                <tr>
                  <th scope="col">Курс</th>
                  <th scope="col">Поток</th>
                  <th scope="col">Номер заявки</th>
                  <th scope="col">Окончание потока</th>
                  <th scope="col">Статус LMS</th>
                </tr>
              </thead>
              <tbody>
                {learner.enrolments.map((enrolment) => (
                  <tr key={enrolment.id}>
                    <td>{enrolment.courseName}</td>
                    <td>{enrolment.streamNo}</td>
                    <td>{enrolment.orderNumber ?? '—'}</td>
                    <td>{enrolment.streamEndsOn === null ? 'не указана' : formatIsoDate(enrolment.streamEndsOn)}</td>
                    <td>{lmsStatusLabel(enrolment)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {tab === 'history' && (
        <>
          {history.kind === 'loading' && <p role="status">Загружаем историю…</p>}
          {history.kind === 'failed' && (
            <div className="notice notice--error" role="alert">
              <p>{responseErrorMessage(history.error)}</p>
              <SupportDetails requestId={requestIdOf(history.error)} />
              <button type="button" onClick={() => void loadHistory()}>Повторить</button>
            </div>
          )}
          {history.kind === 'ready' && (
            history.entries.length === 0 ? <p>Событий нет.</p> : (
              <ul className="enrolment__history">
                {history.entries.map((entry) => (
                  <li key={entry.id}>
                    <span>{formatDateTime(entry.occurredAt)} — {entry.actorDisplayName} — {entry.actionLabel}</span>
                    {entry.details !== null && <p>{historyDetails(entry.details)}</p>}
                  </li>
                ))}
              </ul>
            )
          )}
        </>
      )}

      <button type="button" className="button--secondary" onClick={onBack}>Назад</button>
    </section>
  )
}
