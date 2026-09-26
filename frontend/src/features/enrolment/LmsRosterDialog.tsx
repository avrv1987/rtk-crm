import { useState } from 'react'
import { ApiError, apiClient, type LmsRosterRequest, type RosterExport, type RosterSummary } from '../../shared/api/client'
import { saveFile } from '../admin/saveFile'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessErrorReporter, formatDateTime, responseErrorMessage } from './enrolmentShared'

type LmsRosterDialogProps = {
  streamId: string
  roster: RosterSummary
  exports: RosterExport[]
  onExported: () => void
  onAccessError: AccessErrorReporter
  onClose: () => void
}

export const LmsRosterDialog = ({ streamId, roster, exports, onExported, onAccessError, onClose }: LmsRosterDialogProps) => {
  const [mode, setMode] = useState<LmsRosterRequest['mode']>('PENDING')
  const [incomplete, setIncomplete] = useState<LmsRosterRequest['incomplete'] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [justExported, setJustExported] = useState<string | null>(null)

  const scope = mode === 'PENDING' ? roster.pending : roster.all
  const effectiveIncomplete = scope.missingRequired > 0 ? incomplete : 'INCLUDE'
  const matchingExport = exports.find((rosterExport) => rosterExport.exportId === justExported) ?? null

  const changeMode = (nextMode: LmsRosterRequest['mode']) => {
    setMode(nextMode)
    setIncomplete(null)
    setJustExported(null)
    setError(null)
  }

  const download = async () => {
    if (scope.rows === 0 || busy || effectiveIncomplete === null) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      const result = await apiClient.exportLmsRoster(streamId, { mode, incomplete: effectiveIncomplete })
      saveFile(result.blob, result.fileName)
      setJustExported(result.exportId)
      onExported()
    } catch (exception) {
      if (onAccessError(exception)) {
        return
      }
      setError(exception)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="enrolment__dialog" aria-labelledby="enrolment-roster-title">
      <h3 id="enrolment-roster-title">Выгрузка для LMS</h3>

      <fieldset className="enrolment__roster-mode">
        <legend>Режим</legend>
        <label>
          <input type="radio" name="lms-roster-mode" checked={mode === 'PENDING'} onChange={() => changeMode('PENDING')} />
          Только не переданные
        </label>
        <label>
          <input type="radio" name="lms-roster-mode" checked={mode === 'ALL'} onChange={() => changeMode('ALL')} />
          Все слушатели потока
        </label>
      </fieldset>

      {scope.rows === 0 ? (
        <p>
          {scope.unavailable > 0
            ? 'Анкеты этих слушателей не выгружаются: обработка ограничена или анкета обезличена.'
            : 'Новых оплативших нет: все слушатели потока переданы в LMS.'}
        </p>
      ) : (
        <>
          <p>Строк в файле: {scope.rows}.</p>
          {scope.unavailable > 0 && (
            <p className="notice" role="status">
              Не выгружаются {scope.unavailable}: обработка ограничена или анкета обезличена.
            </p>
          )}
          {scope.duplicateEmails > 0 && (
            <p className="notice" role="status">
              У {scope.duplicateEmails} слушателей потока совпадает email: LMS может не принять повтор. Строки не удаляются.
            </p>
          )}
          {scope.missingRequired > 0 && (
            <fieldset className="enrolment__roster-mode">
              <legend>Без обязательных полей (Фамилия, Имя, телефон, email): {scope.missingRequired}</legend>
              <label>
                <input
                  type="radio"
                  name="lms-roster-incomplete"
                  checked={incomplete === 'INCLUDE'}
                  onChange={() => setIncomplete('INCLUDE')}
                />
                Выгрузить с пустыми ячейками
              </label>
              <label>
                <input
                  type="radio"
                  name="lms-roster-incomplete"
                  checked={incomplete === 'EXCLUDE'}
                  onChange={() => setIncomplete('EXCLUDE')}
                />
                Исключить
              </label>
            </fieldset>
          )}
        </>
      )}

      {error !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(error)}</p>
          {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
        </div>
      )}

      {matchingExport !== null && (
        <p role="status">Файл сформирован: выгрузка от {formatDateTime(matchingExport.exportedAt)}, строк {matchingExport.rows}.</p>
      )}

      <div className="enrolment__dialog-actions">
        <button type="button" disabled={scope.rows === 0 || busy || effectiveIncomplete === null} onClick={() => void download()}>
          {busy ? 'Формируем…' : 'Скачать файл'}
        </button>
        <button type="button" className="button--secondary" onClick={onClose}>Закрыть</button>
      </div>
    </section>
  )
}
