import { ApiError } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { commandErrorMessage } from './adminShared'

/** Ошибка команды справочников: текст виден, код для поддержки свёрнут. */
export const CatalogCommandError = ({ error, onClose }: { error: unknown; onClose?: () => void }) => (
  <div className="interaction-command-error catalogs-error" role="alert">
    <p>{commandErrorMessage(error)}</p>
    {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
    {onClose !== undefined && <button type="button" className="button--secondary" onClick={onClose}>Закрыть</button>}
  </div>
)

export const CatalogLoadFailure = ({ text, requestId, onRetry }: { text: string; requestId?: string; onRetry: () => void }) => (
  <div className="organizations-message organizations-message--error" role="alert">
    <p>{text}</p>
    <SupportDetails requestId={requestId} />
    <button type="button" onClick={onRetry}>Повторить</button>
  </div>
)
