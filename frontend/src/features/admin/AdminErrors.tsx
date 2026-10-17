import { ApiError } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { commandErrorMessage, isVersionConflict } from './adminShared'

type CommandErrorProps = {
  error: unknown
  onConflict?: () => void
  conflictLabel?: string
}

/** Текст ошибки команды; код для поддержки свёрнут, при конфликте версий — кнопка обновления. */
export const CommandError = ({ error, onConflict, conflictLabel = 'Обновить список' }: CommandErrorProps) => (
  <div className="interaction-command-error" role="alert">
    <p>{commandErrorMessage(error)}</p>
    <SupportDetails requestId={error instanceof ApiError ? error.requestId : undefined} />
    {onConflict !== undefined && isVersionConflict(error) && (
      <button type="button" className="button--secondary" onClick={onConflict}>{conflictLabel}</button>
    )}
  </div>
)

type LoadFailureProps = {
  text: string
  requestId?: string
  onRetry: () => void
}

export const LoadFailure = ({ text, requestId, onRetry }: LoadFailureProps) => (
  <div className="organizations-message organizations-message--error" role="alert">
    <p>{text}</p>
    <SupportDetails requestId={requestId} />
    <button type="button" className="button--secondary" onClick={onRetry}>Повторить</button>
  </div>
)
