import { type ChangeEvent, type FormEvent, useEffect, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type LastPaidOrderUpload,
  type PaidOrderUpload as PaidOrderUploadResult
} from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessErrorReporter, responseErrorMessage } from './enrolmentShared'

type PaidOrdersUploadProps = {
  onUploaded: () => void
  onAccessError: AccessErrorReporter
}

type UploadState =
  | { kind: 'idle' }
  | { kind: 'uploading' }
  | { kind: 'done'; result: PaidOrderUploadResult }
  | { kind: 'failed'; error: unknown }

// «У 1 слушателя», «у 5 слушателей»; «1 слушатель перенесён», «2 слушателя перенесены».
const learnersGenitive = (count: number) => (count % 10 === 1 && count % 100 !== 11 ? 'слушателя' : 'слушателей')

const learnersMoved = (count: number) => {
  const last = count % 10
  const lastTwo = count % 100
  if (last === 1 && lastTwo !== 11) {
    return `${count} слушатель перенесён`
  }
  return `${count} ${last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'слушателя' : 'слушателей'} перенесены`
}

const Protocol = ({ result, uploadedAt }: { result: PaidOrderUploadResult; uploadedAt: string | null }) => (
  <section className="enrolment__protocol" aria-labelledby="enrolment-protocol-title">
    <h4 id="enrolment-protocol-title">
      {uploadedAt === null ? 'Протокол загрузки' : `Протокол последней загрузки, ${formatMoscowDateTime(uploadedAt)}`}
    </h4>
    <dl className="enrolment__counters">
      <div><dt>Получено заявок</dt><dd>{result.received}</dd></div>
      <div><dt>Пустых элементов пропущено</dt><dd>{result.emptyElements}</dd></div>
      <div>
        <dt>Новых</dt>
        <dd>{result.created}{result.needsMapping > 0 ? `, ещё ${result.needsMapping} ждут сопоставления курса` : ''}</dd>
      </div>
      <div><dt>Изменились курс или поток</dt><dd>{result.updated}</dd></div>
      <div><dt>Без изменений</dt><dd>{result.skipped}</dd></div>
      {(result.duplicatesInFile ?? 0) > 0 && <div><dt>Повторов заявки в файле</dt><dd>{result.duplicatesInFile}</dd></div>}
      <div><dt>Курс не сопоставлен с программой</dt><dd>{result.needsMapping}</dd></div>
      <div><dt>Не приняты</dt><dd>{result.failed}</dd></div>
      <div><dt>Новых слушателей</dt><dd>{result.learners.newLearners}</dd></div>
      <div><dt>Найдено существующих</dt><dd>{result.learners.foundLearners}</dd></div>
      <div><dt>Зачислений</dt><dd>{result.learners.enrolled}</dd></div>
      <div><dt>Повторных заявок в потоке</dt><dd>{result.learners.repeatOrders}</dd></div>
      <div><dt>Перенесено в другой поток</dt><dd>{result.learners.movedStreams}</dd></div>
    </dl>
    {result.needsMapping > 0 && (
      <p className="notice" role="status">
        {result.unmappedCourses.length > 0
          ? `Курс не сопоставлен с программой CRM: ${result.unmappedCourses.map((course) => `«${course}»`).join(', ')}. `
          : ''}
        Поэтому {result.needsMapping} заявок не считаются ни новыми, ни изменившимися. Курс сопоставляет администратор в
        «Источники данных → Записи для разбора»; после этого заявки попадут в отчёт без повторной загрузки.
      </p>
    )}
    {result.learners.contactsDiffer > 0 && (
      <p className="notice" role="status">
        У {result.learners.contactsDiffer} {learnersGenitive(result.learners.contactsDiffer)} контакты в оплате отличаются от анкеты: анкета не перезаписана.
      </p>
    )}
    {result.heldStreamTransfers > 0 && (
      <p className="notice" role="alert">
        {result.heldStreamTransfers} {result.heldStreamTransfers === 1 ? 'заявка переносит' : 'заявок переносят'} уже зачисленного
        слушателя в поток, которого ещё нет. Перенос не выполнен: проверьте номер потока в файле. Если поток действительно
        новый, нажмите «Создать новые потоки и перенести».
      </p>
    )}
    {result.learners.movedAfterLms > 0 && (
      <p className="notice" role="status">
        {learnersMoved(result.learners.movedAfterLms)} в другой поток после выгрузки в LMS: перенесите их в LMS — кнопкой «Перенести в Moodle» в очереди «Что обработать», если включена передача через Moodle, иначе вручную.
      </p>
    )}
    {result.streams.length > 0 && (
      <div className="catalog-import__table-scroll">
        <table>
          <caption>Потоки в файле</caption>
          <thead>
            <tr>
              <th scope="col">Курс</th>
              <th scope="col">Поток</th>
              <th scope="col">Заявок в файле</th>
            </tr>
          </thead>
          <tbody>
            {result.streams.map((stream) => (
              <tr key={`${stream.course}|${stream.streamNo}`}>
                <td>{stream.course}</td>
                <td>{stream.streamNo}</td>
                <td>{stream.orders}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )}
    {result.issues.length > 0 && (
      <>
        <h4>Замечания к заявкам файла</h4>
        <ul className="enrolment__issues">
          {result.issues.map((issue, index) => (
            <li key={`${issue.position}-${index}`} className={issue.warning ? 'enrolment__issue--warning' : undefined}>
              {issue.orderNumber ? `Заявка ${issue.orderNumber}` : `Элемент ${issue.position} (номера заявки нет)`}
              {issue.field === null ? '' : `, поле «${issue.field}»`}: {issue.message}
              {issue.warning && !issue.message.includes('запись принята') ? ' (запись принята)' : ''}
            </li>
          ))}
        </ul>
      </>
    )}
    <p className="enrolment__run">Запуск источника «Сайт» {result.runId}{result.message ? `: ${result.message}` : ''}.</p>
  </section>
)

export const PaidOrdersUpload = ({ onUploaded, onAccessError }: PaidOrdersUploadProps) => {
  const [file, setFile] = useState<File | null>(null)
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null)
  const [state, setState] = useState<UploadState>({ kind: 'idle' })
  const [open, setOpen] = useState(false)
  const [last, setLast] = useState<LastPaidOrderUpload | null>(null)

  useEffect(() => {
    let active = true
    apiClient.getLastPaidOrderUpload()
      .then((value) => {
        if (active) {
          setLast(value)
        }
      })
      .catch((error: unknown) => {
        // Протокол прошлой загрузки — справочный: без него экран работает, ошибку доступа обработает родитель.
        if (active) {
          onAccessError(error)
        }
      })
    return () => {
      active = false
    }
  }, [onAccessError])

  const selectFile = (event: ChangeEvent<HTMLInputElement>) => {
    setFile(event.target.files?.[0] ?? null)
    setIdempotencyKey(null)
    setState({ kind: 'idle' })
  }

  // confirmNewStreams — повтор того же файла с подтверждением новых потоков: новая команда, свой ключ.
  const upload = async (event: FormEvent<HTMLFormElement> | null, confirmNewStreams = false) => {
    event?.preventDefault()
    if (file === null || state.kind === 'uploading') {
      return
    }
    const key = confirmNewStreams ? createIdempotencyKey() : idempotencyKey ?? createIdempotencyKey()
    setIdempotencyKey(key)
    setState({ kind: 'uploading' })
    try {
      const result = await apiClient.uploadPaidOrders(file, key, confirmNewStreams)
      setState({ kind: 'done', result })
      onUploaded()
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      setState({ kind: 'failed', error })
    }
  }

  return (
    <section className="enrolment__upload" aria-labelledby="enrolment-upload-title">
      <div className="enrolment__upload-head">
        <h3 id="enrolment-upload-title">Загрузка оплат</h3>
        <HintButton label="Как загружаются оплаты">
          Загрузите JSON-файл «Данные оплат» в формате сайта ИТ Школы. Номер заявки, курс и номер потока идут в учёт
          спроса — колонки оплат отчёта «Востребованность программ». Фамилия, имя, отчество, телефон и email сохраняются
          только в зашифрованной анкете слушателя модуля «Слушатели». Повторная загрузка ничего не дублирует и
          достраивает недостающих слушателей.
        </HintButton>
        {!open && state.kind === 'idle' && (
          <button type="button" className="button--secondary" onClick={() => setOpen(true)}>Загрузить файл оплат</button>
        )}
      </div>
      {(open || state.kind !== 'idle') && (
        <form className="enrolment__form" onSubmit={(event) => void upload(event)}>
          <label>
            Файл оплат (JSON)
            <input type="file" accept=".json,application/json" onChange={selectFile} disabled={state.kind === 'uploading'} />
          </label>
          <button type="submit" disabled={file === null || state.kind === 'uploading'}>
            {state.kind === 'uploading' ? 'Загружаем…' : 'Загрузить оплаты'}
          </button>
        </form>
      )}

      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(state.error)}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
        </div>
      )}

      {state.kind === 'done' && <Protocol result={state.result} uploadedAt={null} />}
      {state.kind === 'done' && state.result.heldStreamTransfers > 0 && file !== null && (
        <button type="button" className="button--secondary" onClick={() => void upload(null, true)}>
          Создать новые потоки и перенести
        </button>
      )}
      {state.kind === 'idle' && last !== null && <Protocol result={last.protocol} uploadedAt={last.uploadedAt} />}
    </section>
  )
}
