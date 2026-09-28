import { ApiError } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'

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
  const text = errorText(error, message)
  const fieldErrors = error instanceof ApiError ? Object.values(error.fieldErrors ?? {}) : []
  return (
    <div className="interaction-command-error report-error" role="alert">
      <p>{text}</p>
      {error instanceof ApiError && error.message !== '' && error.message !== text && (
        <p className="report-error__detail">{error.message}</p>
      )}
      {fieldErrors.length > 0 && (
        <ul className="report-error__detail">
          {fieldErrors.map((item) => <li key={item}>{item}</li>)}
        </ul>
      )}
      {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
      {onRetry && <button type="button" onClick={onRetry}>{retryLabel}</button>}
    </div>
  )
}
