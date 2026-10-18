import { useCallback, useEffect, useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import {
  getEnrolmentBatch,
  importBatchProtocol,
  recordBatchResults,
  transferBatchToMoodle,
  type BatchItemResult,
  type BatchResultsApplied,
  type EnrolmentBatch
} from './enrolmentBatchApi'
import { batchResultText } from './enrolmentQueueModel'
import { enrolmentHref } from './enrolmentRoute'
import { type AccessErrorReporter, formatDateTime, learnerFullName, responseErrorMessage } from './enrolmentShared'

type EnrolmentBatchViewProps = {
  batchId: string
  onAccessError: AccessErrorReporter
}

type BatchState =
  | { kind: 'loading' }
  | { kind: 'ready'; batch: EnrolmentBatch }
  | { kind: 'failed'; error: unknown }

export const EnrolmentBatchView = ({ batchId, onAccessError }: EnrolmentBatchViewProps) => {
  const [state, setState] = useState<BatchState>({ kind: 'loading' })
  const [selected, setSelected] = useState<string[]>([])
  const [errorText, setErrorText] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>(null)
  const [applied, setApplied] = useState<BatchResultsApplied | null>(null)
  const [protocol, setProtocol] = useState<File | null>(null)
  const [moodleNote, setMoodleNote] = useState<string | null>(null)
  // Ключ повтора держится до успешного ответа: повторная отправка после обрыва связи не запишет результат дважды.
  const [commandKey, setCommandKey] = useState(createIdempotencyKey)

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', batch: await getEnrolmentBatch(batchId) })
    } catch (exception) {
      if (!onAccessError(exception)) {
        setState({ kind: 'failed', error: exception })
      }
    }
  }, [batchId, onAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const run = async (action: () => Promise<BatchResultsApplied | unknown>) => {
    setBusy(true)
    setFailure(null)
    try {
      const result = await action()
      if (result !== null && typeof result === 'object' && 'batch' in result) {
        setApplied(result as BatchResultsApplied)
        setState({ kind: 'ready', batch: (result as BatchResultsApplied).batch })
      } else {
        await load()
      }
      setSelected([])
      setCommandKey(createIdempotencyKey())
    } catch (exception) {
      if (!onAccessError(exception)) {
        setFailure(exception)
      }
    } finally {
      setBusy(false)
    }
  }

  const mark = (result: BatchItemResult) => run(() => recordBatchResults(batchId, selected.map((itemId) => ({
    itemId,
    result,
    error: result === 'FAILED' ? errorText : null
  })), commandKey))

  if (state.kind === 'loading') {
    return <p role="status">Загружаем партию…</p>
  }
  if (state.kind === 'failed') {
    return (
      <section className="enrolment" aria-label="Партия LMS">
        <div className="notice notice--error" role="alert">
          <p>{responseErrorMessage(state.error)}</p>
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
      </section>
    )
  }

  const { batch } = state
  const selectable = batch.items.filter((item) => item.result !== 'ACCEPTED')
  return (
    <section className="enrolment" aria-labelledby="enrolment-batch-title">
      <div>
        <h2 id="enrolment-batch-title">Партия для LMS от {formatDateTime(batch.createdAt)}</h2>
        <p>
          <a href={enrolmentHref({ kind: 'stream', streamId: batch.streamId })}>{batch.streamTitle}</a>
          {' · '}{batch.format === 'MOODLE_CSV' ? 'CSV Moodle' : 'XLSX по шаблону'}
          {batch.createdByName !== null && <> · сформировал {batch.createdByName}</>}
          {batch.legacy && <> · перенесена из отметок до учёта партий</>}
        </p>
        {batch.checksum !== null && <p className="enrolment__hint enrolment-batch__checksum">Контрольная сумма файла (SHA-256): <code>{batch.checksum}</code></p>}
      </div>
      <dl className="enrolment__funnel" aria-label="Итог партии">
        <div><dt>Строк</dt><dd>{batch.rows}</dd></div>
        <div><dt>Принято LMS</dt><dd>{batch.accepted}</dd></div>
        <div><dt>Ошибки</dt><dd>{batch.failed}</dd></div>
        <div><dt>Ожидает результата</dt><dd>{batch.pending}</dd></div>
      </dl>
      <p className="enrolment__hint">
        {batch.sentAt === null
          ? 'Файл сформирован, но партия ещё не отмечена переданной в LMS.'
          : `Передана в LMS ${formatDateTime(batch.sentAt)}. «Принято» ставится только по результату LMS.`}
      </p>

      <form className="enrolment__stream-actions" onSubmit={(event) => {
        event.preventDefault()
        if (protocol !== null) {
          void run(() => importBatchProtocol(batchId, protocol, commandKey))
        }
      }}>
        <label>
          Протокол приёма LMS (CSV или XLSX: строка, результат, ошибка)
          <input type="file" accept=".csv,.xlsx,.xls" disabled={busy} onChange={(event) => setProtocol(event.target.files?.[0] ?? null)} />
        </label>
        <button type="submit" disabled={busy || protocol === null}>Загрузить протокол</button>
        {batch.moodleTransferAvailable && batch.accepted < batch.rows && (
          <button type="button" disabled={busy} onClick={() => void run(async () => {
            const result = await transferBatchToMoodle(batchId, commandKey)
            setMoodleNote(result.stoppedReason === null
              ? `Отправлено в Moodle строк: ${result.sent}; принято ${result.accepted}, ошибок ${result.failed}.`
              : `Передача остановлена: ${result.stoppedReason}. Принято ${result.accepted}; остальные строки ждут, повторите позже.`)
            return { batch: result.batch, changed: result.accepted + result.failed, skipped: [] }
          })}>
            Передать через Moodle
          </button>
        )}
        {batch.sentAt === null && (
          <button type="button" className="button--secondary" disabled={busy}
            onClick={() => void run(() => apiClient.markRosterExportTransferred(batch.id, commandKey))}>
            Отметить партию переданной
          </button>
        )}
      </form>

      {moodleNote !== null && <p className="notice" role="status">{moodleNote}</p>}
      {applied !== null && (
        <div className="notice" role="status">
          <p>Изменено строк: {applied.changed}.</p>
          {applied.skipped.length > 0 && <ul>{applied.skipped.map((line) => <li key={line}>{line}</li>)}</ul>}
        </div>
      )}
      {failure !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(failure)}</p>
          {failure instanceof ApiError && <SupportDetails requestId={failure.requestId} code={failure.code} />}
        </div>
      )}

      <div className="enrolment__stream-actions">
        <span>Выбрано: {selected.length}</span>
        <button type="button" disabled={busy || selected.length === 0} onClick={() => void mark('ACCEPTED')}>Принята LMS</button>
        <label>
          Причина ошибки
          <input value={errorText} maxLength={500} onChange={(event) => setErrorText(event.target.value)} />
        </label>
        <button type="button" className="button--secondary" disabled={busy || selected.length === 0 || errorText.trim() === ''}
          onClick={() => void mark('FAILED')}>Ошибка LMS</button>
      </div>

      <div className="catalog-import__table-scroll">
        <table aria-label="Строки партии">
          <thead>
            <tr>
              <th scope="col">
                <input type="checkbox" aria-label="Выбрать все не принятые"
                  checked={selectable.length > 0 && selected.length === selectable.length}
                  onChange={(event) => setSelected(event.target.checked ? selectable.map((item) => item.id) : [])} />
              </th>
              <th scope="col">Строка файла</th>
              <th scope="col">Слушатель</th>
              <th scope="col">Заявка</th>
              <th scope="col">Версия анкеты</th>
              <th scope="col">Результат LMS</th>
            </tr>
          </thead>
          <tbody>
            {batch.items.map((item) => (
              <tr key={item.id}>
                <td>
                  <input type="checkbox" aria-label={`Строка ${item.rowNo}`} disabled={item.result === 'ACCEPTED'}
                    checked={selected.includes(item.id)}
                    onChange={(event) => setSelected(event.target.checked
                      ? [...selected, item.id]
                      : selected.filter((id) => id !== item.id))} />
                </td>
                <td>{item.rowNo}</td>
                <td>
                  {item.lastName === null ? 'Анкета недоступна' : (
                    <a href={enrolmentHref({ kind: 'learner', learnerId: item.learnerId, streamId: batch.streamId })}>
                      {learnerFullName({ lastName: item.lastName, firstName: item.firstName, middleName: null })}
                    </a>
                  )}
                </td>
                <td>{item.orderNumber ?? '—'}</td>
                <td>
                  {item.learnerVersion ?? '—'}
                  {item.learnerVersion !== null && item.currentLearnerVersion !== null && item.currentLearnerVersion > item.learnerVersion
                    && ' (анкету изменили после выгрузки)'}
                </td>
                <td>{batchResultText[item.result]}{item.error !== null && `: ${item.error}`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
