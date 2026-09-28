import { useState } from 'react'
import { ApiError, type Contact } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { handledAccessError } from '../interactions/workMarks'
import { closePartnerAccess, openPartnerAccess, type PartnerAccess, type PartnerAccessGranted } from './partnerApi'
import './partner.css'

type PartnerAccessControlProps = {
  organizationId: string
  contact: Contact
  access: PartnerAccess | undefined
  canManage: boolean
  canOpen: boolean
  onChanged: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ControlState =
  | { kind: 'idle' }
  | { kind: 'confirmOpen' }
  | { kind: 'confirmClose' }
  | { kind: 'saving' }
  | { kind: 'granted'; granted: PartnerAccessGranted }
  | { kind: 'closed'; access: PartnerAccess }
  | { kind: 'failed'; error: unknown }

const failureText = (error: unknown) => (
  error instanceof ApiError
    ? Object.values(error.fieldErrors ?? {})[0] ?? error.message
    : 'Не удалось связаться с сервисом. Повторите попытку позже.'
)

export const PartnerAccessControl = ({
  organizationId,
  contact,
  access,
  canManage,
  canOpen,
  onChanged,
  onSessionExpired,
  onProfileUnavailable
}: PartnerAccessControlProps) => {
  const [state, setState] = useState<ControlState>({ kind: 'idle' })
  const active = access?.active === true
  const openable = canManage && canOpen && !active && !contact.inactive && contact.personalDataStatus === 'ACTIVE'

  const run = async (action: 'open' | 'close') => {
    setState({ kind: 'saving' })
    try {
      if (action === 'open') {
        setState({ kind: 'granted', granted: await openPartnerAccess(organizationId, contact.id) })
      } else {
        setState({ kind: 'closed', access: await closePartnerAccess(organizationId, contact.id) })
      }
      onChanged()
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }

  if (access === undefined && !openable && state.kind !== 'granted') {
    return null
  }

  return (
    <div className="partner-access" aria-label={`Кабинет вуза: ${contact.name}`}>
      <p className="partner-access__status">
        <span className={`status ${active ? 'status--planned' : 'status--missing'}`}>
          {active ? 'Есть доступ в кабинет вуза' : access === undefined ? 'Нет доступа в кабинет вуза' : 'Доступ в кабинет закрыт'}
        </span>
        {active && access?.login && <span className="partner-access__login">Логин: {access.login}</span>}
      </p>
      {access?.accountSyncRequired && (
        <p className="interaction-field-hint">
          Учётная запись Keycloak ещё не отключена; вход в CRM уже закрыт, администратор повторит синхронизацию.
        </p>
      )}
      {canManage && (
        <div className="interaction-plan-form__actions">
          {openable && (
            <button type="button" className="button--secondary" disabled={state.kind === 'saving'} onClick={() => setState({ kind: 'confirmOpen' })}>
              {access === undefined ? 'Открыть доступ в кабинет' : 'Открыть доступ снова'}
            </button>
          )}
          {active && (
            <button type="button" className="button--secondary" disabled={state.kind === 'saving'} onClick={() => setState({ kind: 'confirmClose' })}>
              Закрыть доступ
            </button>
          )}
        </div>
      )}
      {state.kind === 'saving' && <p role="status">Обращаемся к Keycloak…</p>}
      {state.kind === 'granted' && (
        <div className="partner-access__granted" role="status">
          <p><strong>Доступ открыт.</strong> Передайте представителю вуза логин и временный пароль по защищённому каналу.</p>
          <dl>
            <div><dt>Адрес входа</dt><dd>{window.location.origin}</dd></div>
            <div><dt>Логин</dt><dd><code>{state.granted.login}</code></dd></div>
            <div><dt>Временный пароль</dt><dd><code>{state.granted.temporaryPassword}</code></dd></div>
          </dl>
          <p className="interaction-field-hint">
            Пароль показан один раз и больше нигде не хранится. При первом входе система попросит задать свой пароль.
          </p>
          <button type="button" onClick={() => setState({ kind: 'idle' })}>Я передал пароль, скрыть</button>
        </div>
      )}
      {state.kind === 'closed' && (
        <p role="status">
          Доступ закрыт.{state.access.accountSyncError ? ` Keycloak: ${state.access.accountSyncError}` : ''}
        </p>
      )}
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{failureText(state.error)}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
        </div>
      )}
      <ConfirmDialog
        open={state.kind === 'confirmOpen'}
        title="Открыть доступ в кабинет вуза?"
        description={`${contact.name} получит вход в кабинет вуза: только чтение работ, открытых вузу документов, шагов с отметкой «Показывать вузу», соглашений и имени КАМ. CRM создаст учётную запись с временным паролем, пароль будет показан один раз.`}
        confirmLabel="Открыть доступ"
        onConfirm={() => void run('open')}
        onCancel={() => setState({ kind: 'idle' })}
      />
      <ConfirmDialog
        open={state.kind === 'confirmClose'}
        title="Закрыть доступ в кабинет вуза?"
        description={`${contact.name} больше не сможет войти: профиль будет заблокирован, учётная запись Keycloak отключена, сеансы завершены.`}
        confirmLabel="Закрыть доступ"
        onConfirm={() => void run('close')}
        onCancel={() => setState({ kind: 'idle' })}
      />
    </div>
  )
}
