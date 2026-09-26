import { type ChangeEvent, type FormEvent, useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type PaidOrderUpload as PaidOrderUploadResult } from '../../shared/api/client'
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

export const PaidOrdersUpload = ({ onUploaded, onAccessError }: PaidOrdersUploadProps) => {
  const [file, setFile] = useState<File | null>(null)
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null)
  const [state, setState] = useState<UploadState>({ kind: 'idle' })

  const selectFile = (event: ChangeEvent<HTMLInputElement>) => {
    setFile(event.target.files?.[0] ?? null)
    setIdempotencyKey(null)
    setState({ kind: 'idle' })
  }

  const upload = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (file === null || state.kind === 'uploading') {
      return
    }
    const key = idempotencyKey ?? createIdempotencyKey()
    setIdempotencyKey(key)
    setState({ kind: 'uploading' })
    try {
      const result = await apiClient.uploadPaidOrders(file, key)
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
      <h3 id="enrolment-upload-title">Загрузка оплат</h3>
      <p className="enrolment__intro">
        Загрузите JSON-файл «Данные оплат» в формате сайта ИТ Школы. Номер заявки, курс и номер потока идут в учёт
        спроса — колонки оплат отчёта «Востребованность программ». Фамилия, имя, отчество, телефон и email сохраняются
        только в зашифрованной анкете слушателя модуля «Слушатели». Повторная загрузка ничего не дублирует и
        достраивает недостающих слушателей.
      </p>
      <form className="enrolment__form" onSubmit={(event) => void upload(event)}>
        <label>
          Файл оплат (JSON)
          <input type="file" accept=".json,application/json" onChange={selectFile} disabled={state.kind === 'uploading'} />
        </label>
        <button type="submit" disabled={file === null || state.kind === 'uploading'}>
          {state.kind === 'uploading' ? 'Загружаем…' : 'Загрузить оплаты'}
        </button>
      </form>

      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(state.error)}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
        </div>
      )}

      {state.kind === 'done' && (
        <section className="enrolment__protocol" aria-labelledby="enrolment-protocol-title">
          <h4 id="enrolment-protocol-title">Протокол загрузки</h4>
          <dl className="enrolment__counters">
            <div><dt>Получено заявок</dt><dd>{state.result.received}</dd></div>
            <div><dt>Пустых элементов пропущено</dt><dd>{state.result.emptyElements}</dd></div>
            <div><dt>Новых</dt><dd>{state.result.created}</dd></div>
            <div><dt>Изменились курс или поток</dt><dd>{state.result.updated}</dd></div>
            <div><dt>Без изменений</dt><dd>{state.result.skipped}</dd></div>
            <div><dt>Курс не сопоставлен с программой</dt><dd>{state.result.needsMapping}</dd></div>
            <div><dt>Не приняты</dt><dd>{state.result.failed}</dd></div>
            <div><dt>Новых слушателей</dt><dd>{state.result.learners.newLearners}</dd></div>
            <div><dt>Найдено существующих</dt><dd>{state.result.learners.foundLearners}</dd></div>
            <div><dt>Зачислений</dt><dd>{state.result.learners.enrolled}</dd></div>
            <div><dt>Повторных заявок в потоке</dt><dd>{state.result.learners.repeatOrders}</dd></div>
            <div><dt>Перенесено в другой поток</dt><dd>{state.result.learners.movedStreams}</dd></div>
          </dl>
          {state.result.needsMapping > 0 && (
            <p className="notice" role="status">
              Курсы без программы CRM сопоставляет администратор в «Источники данных → Записи для разбора»; после этого заявки попадут в отчёт без повторной загрузки.
            </p>
          )}
          {state.result.learners.contactsDiffer > 0 && (
            <p className="notice" role="status">
              У {state.result.learners.contactsDiffer} слушателей контакты в оплате отличаются от анкеты: анкета не перезаписана.
            </p>
          )}
          {state.result.learners.movedAfterLms > 0 && (
            <p className="notice" role="status">
              {state.result.learners.movedAfterLms} слушателей перенесены в другой поток после выгрузки в LMS: исключите их из прежнего потока в LMS вручную.
            </p>
          )}
          {state.result.streams.length > 0 && (
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
                  {state.result.streams.map((stream) => (
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
          {state.result.issues.length > 0 && (
            <>
              <h4>Замечания к элементам файла</h4>
              <ul className="enrolment__issues">
                {state.result.issues.map((issue, index) => (
                  <li key={`${issue.position}-${index}`} className={issue.warning ? 'enrolment__issue--warning' : undefined}>
                    Элемент {issue.position}{issue.field === null ? '' : `, поле «${issue.field}»`}: {issue.message}
                    {issue.warning ? ' (запись принята)' : ''}
                  </li>
                ))}
              </ul>
            </>
          )}
          <p className="enrolment__run">Запуск источника «Сайт» {state.result.runId}{state.result.message ? `: ${state.result.message}` : ''}.</p>
        </section>
      )}
    </section>
  )
}
