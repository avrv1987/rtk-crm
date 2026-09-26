import { ApiError } from '../../shared/api/client'

const errorText = (error: unknown, fallback: string) => {
  if (!(error instanceof ApiError)) {
    return 'Не удалось связаться с сервисом. Проверьте соединение и повторите попытку.'
  }
  if (error.code === 'REPORT_ACCESS_CHANGED') {
    return 'Права доступа изменились после заказа, поэтому файл не выдаётся. Сформируйте отчёт заново.'
  }
  if (error.code === 'REPORT_CAPACITY_EXCEEDED') {
    return 'Сейчас все места для построения отчётов заняты. Повторите заказ через 30 секунд.'
  }
  if (error.code === 'VALIDATION_ERROR') {
    return 'Проверьте параметры отчёта.'
  }
  return fallback
}

type ErrorNoticeProps = {
  error: unknown
  message: string
  onRetry?: () => void
  retryLabel?: string
}

export const ErrorNotice = ({ error, message, onRetry, retryLabel = 'Повторить' }: ErrorNoticeProps) => {
  const fieldErrors = error instanceof ApiError ? Object.entries(error.fieldErrors ?? {}) : []
  return (
    <div className="interaction-command-error" role="alert">
      <p>{errorText(error, message)}</p>
      {error instanceof ApiError && (
        <div className="structured-api-error">
          <p>Код: {error.code}</p>
          <p>{error.message}</p>
          {fieldErrors.length > 0 && (
            <ul>
              {fieldErrors.map(([field, text]) => <li key={field}>{field}: {text}</li>)}
            </ul>
          )}
          <p className="request-id">Request ID: {error.requestId}</p>
        </div>
      )}
      {onRetry && <button type="button" onClick={onRetry}>{retryLabel}</button>}
    </div>
  )
}
