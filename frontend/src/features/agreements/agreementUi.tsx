import { useCallback, useRef } from 'react'
import { ApiError, createIdempotencyKey } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'

export const useAccessErrorHandler = (
  onSessionExpired: () => void,
  onProfileUnavailable: (requestId: string) => void
) => useCallback((error: unknown) => {
  if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
    onSessionExpired()
    return true
  }
  if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
    onProfileUnavailable(error.requestId)
    return true
  }
  return false
}, [onProfileUnavailable, onSessionExpired])

export const useCommandKey = () => {
  const current = useRef<{ payload: string; key: string } | null>(null)
  return {
    keyFor: (payload: unknown) => {
      const serialized = JSON.stringify(payload)
      if (current.current?.payload !== serialized) {
        current.current = { payload: serialized, key: createIdempotencyKey() }
      }
      return current.current.key
    },
    settle: (error?: unknown) => {
      if (error === undefined || error instanceof ApiError) {
        current.current = null
      }
    }
  }
}

const fieldLabels: Record<string, string> = {
  number: 'Номер',
  validUntil: 'Срок действия',
  plannedKind: 'Вид плана',
  plannedOn: 'Плановая дата',
  fileAttachmentId: 'Файл соглашения',
  kindId: 'Вид мероприятия',
  title: 'Мероприятие',
  plannedEnd: 'Плановое окончание',
  actualEnd: 'Фактическое окончание',
  responsibleProfileId: 'Ответственный',
  interactionIds: 'Работы',
  attachmentIds: 'Подтверждения',
  name: 'Название',
  to: 'Период',
  version: 'Версия'
}

export const errorMessage = (error: unknown, fallback: string) => {
  if (!(error instanceof ApiError)) {
    return 'Не удалось связаться с сервисом. Проверьте соединение и повторите попытку.'
  }
  if (error.code === 'VERSION_CONFLICT') {
    return `${error.message}. Обновите данные и повторите изменение.`
  }
  if (error.code === 'NOT_FOUND' || error.code === 'FORBIDDEN' || error.code === 'CONFIRMATION_LIMIT') {
    return error.message
  }
  if (error.code === 'VALIDATION_ERROR') {
    return 'Проверьте введённые данные.'
  }
  return fallback
}

type CommandErrorProps = {
  error: unknown
  fallback: string
  onRetry?: () => void
  retryLabel?: string
}

export const CommandError = ({ error, fallback, onRetry, retryLabel = 'Повторить' }: CommandErrorProps) => {
  const fieldErrors = error instanceof ApiError ? Object.entries(error.fieldErrors ?? {}) : []
  return (
    <div className="interaction-command-error" role="alert">
      <p>{errorMessage(error, fallback)}</p>
      {fieldErrors.length > 0 && (
        <ul>
          {fieldErrors.map(([field, text]) => <li key={field}>{fieldLabels[field] ?? field}: {text}</li>)}
        </ul>
      )}
      {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
      {onRetry && <button type="button" className="button--secondary" onClick={onRetry}>{retryLabel}</button>}
    </div>
  )
}
