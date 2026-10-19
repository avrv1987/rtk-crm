import { useState } from 'react'
import { ApiError, createIdempotencyKey } from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { saveFile } from '../admin/saveFile'
import { handledAccessError } from '../interactions/workMarks'
import { attachmentAccept, attachmentFormats } from '../documents/documentsApi'
import { attachmentFileProblem, attachmentLimitMegabytes, attachmentNameLimit } from '../documents/attachmentRules'
import {
  confirmPartnerDocument,
  downloadPartnerDocument,
  respondPartnerRequest,
  type PartnerReceipt,
  type PartnerRequestForPartner
} from './partnerApi'
import { answerable, fileStateLabels, partnerRequestStatusLabels } from './partnerRequestModel'

type PartnerRequestCardProps = {
  request: PartnerRequestForPartner
  onChanged: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type SendState =
  | { kind: 'idle' }
  | { kind: 'sending' }
  | { kind: 'sent'; receipt: PartnerReceipt }
  | { kind: 'failed'; error: unknown }

const receiptLine = (receipt: PartnerReceipt) => {
  const parts = [`Квитанция № ${receipt.receiptNumber} от ${formatMoscowDateTime(receipt.submittedAt)}`]
  parts.push(receipt.kind === 'QUESTION' ? 'вопрос школе' : 'ответ')
  if (receipt.fileName !== null) {
    parts.push(`файл «${receipt.fileName}»${receipt.fileState === null ? '' : `, ${fileStateLabels[receipt.fileState]}`}`)
  }
  return parts.join(' · ')
}

export const PartnerRequestCard = ({ request, onChanged, onSessionExpired, onProfileUnavailable }: PartnerRequestCardProps) => {
  const [message, setMessage] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const fileProblem = file === null ? null : attachmentFileProblem(file)
  // Ключ повтора живёт, пока ответ не принят: повторная отправка после обрыва связи уходит с тем же ключом,
  // и сервер вернёт ту же квитанцию вместо второго ответа.
  const [idempotencyKey, setIdempotencyKey] = useState(createIdempotencyKey)
  const [state, setState] = useState<SendState>({ kind: 'idle' })
  const [busyDocument, setBusyDocument] = useState<string | null>(null)
  const [documentError, setDocumentError] = useState<unknown>(null)

  const send = async (kind: 'ANSWER' | 'QUESTION') => {
    setState({ kind: 'sending' })
    try {
      const receipt = await respondPartnerRequest(request.id, {
        kind,
        version: request.version,
        message,
        file: kind === 'QUESTION' ? null : file
      }, idempotencyKey)
      setState({ kind: 'sent', receipt })
      setMessage('')
      setFile(null)
      setIdempotencyKey(createIdempotencyKey())
      onChanged()
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }

  const documentAction = async (id: string, name: string, action: 'download' | 'confirm') => {
    setBusyDocument(id)
    setDocumentError(null)
    try {
      if (action === 'download') {
        saveFile(await downloadPartnerDocument(id), name)
      } else {
        await confirmPartnerDocument(id)
        onChanged()
      }
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setDocumentError(error)
      }
    } finally {
      setBusyDocument(null)
    }
  }

  const canAnswer = answerable(request.status)
  const sending = state.kind === 'sending'
  return (
    <li className="partner-card partner-request">
      <div className="partner-card__header">
        <strong>{request.action}</strong>
        <span className={`status ${request.status === 'RETURNED' ? 'status--overdue' : request.status === 'CANCELLED' ? 'status--missing' : 'status--planned'}`}>
          {partnerRequestStatusLabels[request.status]}
        </span>
      </div>
      <p className="partner-card__meta">
        {request.workTitle}{request.dueOn !== null && <> · срок до {formatCalendarDate(request.dueOn)}</>}
      </p>
      <dl className="partner-card__facts">
        <div><dt>Ожидаемый результат</dt><dd>{request.expectedResult}</dd></div>
        {request.clarification !== null && <div><dt>Уточнение школы</dt><dd>{request.clarification}</dd></div>}
        {request.returnReason !== null && <div><dt>Причина возврата</dt><dd>{request.returnReason}</dd></div>}
      </dl>
      {request.materials.length > 0 && (
        <div className="partner-card__stages">
          <p>Материалы школы</p>
          <ul className="partner-request__materials">
            {request.materials.map((material) => (
              <li key={material.id}>
                <span>{material.name}, версия {material.revision}</span>
                {material.downloadable ? (
                  <>
                    <button type="button" className="button--secondary" disabled={busyDocument === material.id}
                      onClick={() => void documentAction(material.id, material.name, 'download')}>Скачать</button>
                    {material.receivedAt === null ? (
                      <button type="button" className="button--secondary" disabled={busyDocument === material.id}
                        onClick={() => void documentAction(material.id, material.name, 'confirm')}>Материалы получены</button>
                    ) : (
                      <span className="partner-card__meta">Получение подтверждено {formatMoscowDateTime(material.receivedAt)}</span>
                    )}
                  </>
                ) : (
                  <span className="partner-card__meta">файл проверяется или снят с публикации</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {documentError !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{documentError instanceof ApiError ? documentError.message : 'Не удалось выполнить действие с файлом.'}</p>
          {documentError instanceof ApiError && <SupportDetails requestId={documentError.requestId} code={documentError.code} />}
        </div>
      )}
      {request.responses.length > 0 && (
        <div className="partner-card__stages">
          <p>Отправлено вузом</p>
          <ul>
            {request.responses.map((receipt) => (
              <li key={receipt.receiptNumber}>
                {receiptLine(receipt)}
                {receipt.message !== null && <div className="partner-request__message">{receipt.message}</div>}
                {receipt.decision === 'ACCEPTED' && <div>Принято школой.</div>}
                {receipt.decision === 'RETURNED' && <div>Возвращено на доработку: {receipt.decisionReason}</div>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {state.kind === 'sent' && (
        <p className="notice" role="status">Отправлено. {receiptLine(state.receipt)}.</p>
      )}
      {canAnswer && (
        <form className="partner-request__form" onSubmit={(event) => { event.preventDefault(); void send('ANSWER') }}>
          <label>
            Ответ школе
            <textarea value={message} maxLength={4000} rows={3} disabled={sending} onChange={(event) => setMessage(event.target.value)} />
          </label>
          <label>
            Файл (необязательно)
            <input type="file" accept={attachmentAccept} disabled={sending} onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
            <span className="partner-request__hint">До {attachmentLimitMegabytes} МБ: {attachmentFormats}. Имя файла — до {attachmentNameLimit} символов.</span>
            {fileProblem !== null && <span className="partner-request__error" role="alert">{fileProblem}</span>}
          </label>
          <div className="partner-request__actions">
            <button type="submit" disabled={sending || fileProblem !== null || (message.trim().length === 0 && file === null)}>
              {sending ? 'Отправляем…' : 'Отправить ответ'}
            </button>
            <button type="button" className="button--secondary" disabled={sending || message.trim().length === 0 || file !== null}
              onClick={() => void send('QUESTION')}>Задать вопрос по запросу</button>
          </div>
        </form>
      )}
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{state.error instanceof ApiError
            ? state.error.fieldErrors?.file ?? state.error.fieldErrors?.message ?? state.error.message
            : 'Связь прервалась. Нажмите «Отправить ответ» ещё раз: повторная отправка не создаст второй ответ.'}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
          {state.error instanceof ApiError && state.error.status === 409 && (
            <button type="button" onClick={onChanged}>Обновить кабинет</button>
          )}
        </div>
      )}
    </li>
  )
}
