import { type ChangeEvent, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type QuestionnaireImport,
  type QuestionnaireIssue,
  type QuestionnaireRow
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessErrorReporter, fieldCodeLabels, learnerFieldLabels, responseErrorMessage } from './enrolmentShared'

type QuestionnaireImportDialogProps = {
  streamId: string
  onApplied: () => void
  onAccessError: AccessErrorReporter
  onClose: () => void
}

type PreviewState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; file: File; result: QuestionnaireImport }
  | { kind: 'failed'; error: unknown }

const statusLabels: Record<QuestionnaireRow['status'], string> = {
  UPDATE: 'Обновить',
  UNCHANGED: 'Без изменений',
  ERROR: 'Ошибка',
  CONFLICT: 'Конфликт'
}

const describeIssue = (issue: QuestionnaireIssue) => {
  const name = issue.field !== null ? learnerFieldLabels[issue.field] : issue.header
  const prefix = issue.column !== null
    ? `${issue.column}${name !== null ? ` «${name}»` : ''}: `
    : name !== null ? `«${name}»: ` : ''
  return `${prefix}${issue.message}${issue.warning ? ' (предупреждение)' : ''}`
}

export const QuestionnaireImportDialog = ({ streamId, onApplied, onAccessError, onClose }: QuestionnaireImportDialogProps) => {
  const [preview, setPreview] = useState<PreviewState>({ kind: 'idle' })
  const [applyBusy, setApplyBusy] = useState(false)
  const [applyError, setApplyError] = useState<unknown>(null)
  const [applyResult, setApplyResult] = useState<QuestionnaireImport | null>(null)
  const [applyKey, setApplyKey] = useState<string | null>(null)
  const [conflictNotice, setConflictNotice] = useState(false)

  const runPreview = async (file: File) => {
    setPreview({ kind: 'loading' })
    try {
      const result = await apiClient.previewQuestionnaireImport(streamId, file)
      setPreview({ kind: 'ready', file, result })
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      setPreview({ kind: 'failed', error })
    }
  }

  const selectFile = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null
    setApplyResult(null)
    setApplyError(null)
    setApplyKey(null)
    setConflictNotice(false)
    if (file === null) {
      setPreview({ kind: 'idle' })
      return
    }
    void runPreview(file)
  }

  const apply = async () => {
    if (preview.kind !== 'ready' || applyBusy) {
      return
    }
    const key = applyKey ?? createIdempotencyKey()
    setApplyKey(key)
    setApplyBusy(true)
    setApplyError(null)
    try {
      const result = await apiClient.applyQuestionnaireImport(streamId, preview.file, preview.result.fingerprint, key)
      setApplyResult(result)
      onApplied()
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      if (error instanceof ApiError && error.code === 'VERSION_CONFLICT') {
        setApplyKey(null)
        setConflictNotice(true)
        await runPreview(preview.file)
        return
      }
      setApplyError(error)
    } finally {
      setApplyBusy(false)
    }
  }

  const rowsResult = applyResult ?? (preview.kind === 'ready' ? preview.result : null)

  return (
    <section className="enrolment__dialog" aria-labelledby="enrolment-template-title">
      <h3 id="enrolment-template-title">Загрузка заполненного шаблона</h3>
      <label>
        Файл шаблона (XLSX или XLS)
        <input
          type="file"
          accept=".xlsx,.xls,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          disabled={preview.kind === 'loading' || applyBusy}
          onChange={selectFile}
        />
      </label>

      {preview.kind === 'loading' && <p role="status">Строим предпросмотр…</p>}

      {preview.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(preview.error)}</p>
          {preview.error instanceof ApiError && <SupportDetails requestId={preview.error.requestId} code={preview.error.code} />}
        </div>
      )}

      {conflictNotice && (
        <p className="notice" role="status">Анкеты изменились после предпросмотра: предпросмотр построен заново.</p>
      )}

      {rowsResult !== null && (
        <>
          {applyResult === null ? (
            <dl className="enrolment__counters">
              <div><dt>Обновить</dt><dd>{rowsResult.updated}</dd></div>
              <div><dt>Без изменений</dt><dd>{rowsResult.unchanged}</dd></div>
              <div><dt>Ошибок</dt><dd>{rowsResult.errors}</dd></div>
              <div><dt>Конфликтов</dt><dd>{rowsResult.conflicts}</dd></div>
            </dl>
          ) : (
            <p role="status">
              Применено {applyResult.updated}, без изменений {applyResult.unchanged}, пропущено с ошибками и конфликтами {applyResult.errors + applyResult.conflicts}.
            </p>
          )}
          {rowsResult.ignoredHeaders.length > 0 && (
            <p className="notice" role="status">
              Столбцы не из шаблона, их значения не читаются: {rowsResult.ignoredHeaders.join(', ')}.
            </p>
          )}
          <div className="catalog-import__table-scroll">
            <table aria-label="Строки заполненного шаблона">
              <thead>
                <tr>
                  <th scope="col">Строка</th>
                  <th scope="col">ФИО из файла</th>
                  <th scope="col">Статус</th>
                  <th scope="col">Изменяемые поля</th>
                  <th scope="col">Замечания</th>
                </tr>
              </thead>
              <tbody>
                {rowsResult.rows.map((row) => (
                  <tr key={row.rowNumber}>
                    <td>{row.rowNumber}</td>
                    <td>{[row.lastName, row.firstName].filter((part) => part !== null).join(' ') || '—'}</td>
                    <td>{statusLabels[row.status]}</td>
                    <td>{row.changedFields.length === 0 ? '—' : fieldCodeLabels(row.changedFields)}</td>
                    <td>
                      {row.issues.length === 0 ? '—' : (
                        <ul>
                          {row.issues.map((issue, index) => (
                            <li key={index} className={issue.warning ? 'enrolment__issue--warning' : undefined}>
                              {describeIssue(issue)}
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {applyError !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(applyError)}</p>
          {applyError instanceof ApiError && <SupportDetails requestId={applyError.requestId} code={applyError.code} />}
        </div>
      )}

      <div className="enrolment__dialog-actions">
        {applyResult === null && (
          <button type="button" disabled={preview.kind !== 'ready' || preview.result.updated === 0 || applyBusy} onClick={() => void apply()}>
            {applyBusy ? 'Применяем…' : 'Применить'}
          </button>
        )}
        <button type="button" className="button--secondary" onClick={onClose}>{applyResult === null ? 'Отмена' : 'Закрыть'}</button>
      </div>
    </section>
  )
}
