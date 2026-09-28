import { useState } from 'react'
import { ApiError, apiClient } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import '../admin/security.css'

type RequestState =
  | { kind: 'idle' }
  | { kind: 'sending' }
  | { kind: 'sent'; requestedAt: string }
  | { kind: 'failed'; requestId?: string }

const accountUrl = '/idp/realms/rtk-crm/account'

export const PasswordLink = () => (
  <a className="password-link" href={accountUrl} target="_blank" rel="noopener noreferrer">Сменить пароль</a>
)

export const ActivationRequest = ({ onSessionExpired }: { onSessionExpired: () => void }) => {
  const [state, setState] = useState<RequestState>({ kind: 'idle' })

  const send = async () => {
    setState({ kind: 'sending' })
    try {
      const { requestedAt } = await apiClient.requestProfileActivation()
      setState({ kind: 'sent', requestedAt })
    } catch (error) {
      if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
        onSessionExpired()
        return
      }
      setState({ kind: 'failed', requestId: error instanceof ApiError ? error.requestId : undefined })
    }
  }

  return (
    <>
      <div className="session-help">
        <button type="button" disabled={state.kind === 'sending' || state.kind === 'sent'} onClick={() => void send()}>
          {state.kind === 'sending' ? 'Отправляем…' : 'Сообщить администратору'}
        </button>
      </div>
      {state.kind === 'sent' && (
        <p className="security-success" role="status">
          Администратор увидит вашу просьбу в списке профилей CRM ({formatMoscowDateTime(state.requestedAt)}).
          Войдите снова после того, как он откроет доступ.
        </p>
      )}
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>Не удалось отправить сообщение. Повторите попытку позже или обратитесь к администратору напрямую.</p>
          {state.requestId && <p className="request-id">Request ID: {state.requestId}</p>}
        </div>
      )}
    </>
  )
}
