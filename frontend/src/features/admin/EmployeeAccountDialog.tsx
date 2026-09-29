import { useState, type FormEvent } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type AccountCredentials,
  type CrmProfile,
  type NewEmployee,
  type Team
} from '../../shared/api/client'
import { CardDialog } from '../interactions/cardUi'
import { commandErrorMessage, handledSessionError, roleLabels, type SessionHandlers } from './adminShared'

export type AccountAction =
  | { kind: 'create' }
  | { kind: 'reset'; profile: CrmProfile }
  | { kind: 'logout'; profile: CrmProfile }
  | { kind: 'otp'; profile: CrmProfile }
  | { kind: 'email'; profile: CrmProfile }

type EmployeeAccountDialogProps = SessionHandlers & {
  action: AccountAction | null
  teams: Team[]
  onClose: () => void
  onChanged: () => void
}

type Draft = {
  displayName: string
  login: string
  email: string
  role: NewEmployee['role']
  teamId: string
}

type Result =
  | { kind: 'credentials'; credentials: AccountCredentials }
  | { kind: 'done'; message: string }

const emptyDraft: Draft = { displayName: '', login: '', email: '', role: 'USER', teamId: '' }

const teamRequired = (role: NewEmployee['role']) => role === 'USER' || role === 'LEADER'

const titles: Record<AccountAction['kind'], string> = {
  create: 'Новый сотрудник',
  reset: 'Сбросить пароль',
  logout: 'Завершить сеансы',
  otp: 'Сбросить второй фактор',
  email: 'Сменить почту'
}

const OneTimePassword = ({ credentials, created }: { credentials: AccountCredentials; created: boolean }) => {
  const [copied, setCopied] = useState<'yes' | 'failed' | null>(null)
  const password = credentials.temporaryPassword
  const syncError = credentials.profile.accountSyncError
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(password ?? '')
      setCopied('yes')
    } catch {
      setCopied('failed')
    }
  }
  if (password === null) {
    return (
      <p role="status">
        Запрос уже выполнялся, пароль был показан тогда. Если его не сохранили, выдайте новый: меню «⋯» → «Сбросить пароль».
      </p>
    )
  }
  return (
    <div className="employee-account__password" role="status">
      <p>
        <strong>{created ? 'Сотрудник заведён.' : 'Новый временный пароль выдан.'}</strong>{' '}
        Передайте логин и пароль сотруднику лично или по защищённому каналу.
      </p>
      <dl>
        <div><dt>Адрес входа</dt><dd>{window.location.origin}</dd></div>
        <div><dt>Логин</dt><dd><code>{credentials.login}</code></dd></div>
        <div><dt>Временный пароль</dt><dd><code>{password}</code></dd></div>
      </dl>
      <div className="security-actions">
        <button type="button" className="button--secondary" onClick={() => void copy()}>Скопировать пароль</button>
        {copied === 'yes' && <span>Скопировано</span>}
        {copied === 'failed' && <span>Не удалось скопировать — выделите пароль вручную</span>}
      </div>
      <p className="interaction-field-hint">
        Пароль показан один раз и нигде не хранится. При первом входе Keycloak попросит задать свой пароль.
      </p>
      {syncError && (
        <p className="interaction-command-error" role="alert">
          Второй фактор администратору не назначен ({syncError}). Когда связь восстановится, выберите в меню «⋯» у
          профиля «Сбросить второй фактор».
        </p>
      )}
    </div>
  )
}

export const EmployeeAccountDialog = ({
  action,
  teams,
  onClose,
  onChanged,
  onSessionExpired,
  onProfileUnavailable
}: EmployeeAccountDialogProps) => {
  const [draft, setDraft] = useState<Draft>(emptyDraft)
  const [email, setEmail] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>()
  const [result, setResult] = useState<Result | null>(null)
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null)

  const close = () => {
    setDraft(emptyDraft)
    setEmail('')
    setSaving(false)
    setError(undefined)
    setResult(null)
    setIdempotencyKey(null)
    onClose()
  }

  const changeDraft = (changes: Partial<Draft>) => {
    setDraft((current) => ({ ...current, ...changes }))
    setError(undefined)
    setIdempotencyKey(null)
  }

  const run = async (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault()
    if (action === null || saving) {
      return
    }
    const key = idempotencyKey ?? createIdempotencyKey()
    setIdempotencyKey(key)
    setSaving(true)
    setError(undefined)
    try {
      if (action.kind === 'create') {
        const payload: NewEmployee = {
          displayName: draft.displayName.trim(),
          login: draft.login.trim(),
          email: draft.email.trim(),
          role: draft.role,
          teamId: draft.teamId === '' ? null : draft.teamId
        }
        setResult({ kind: 'credentials', credentials: await apiClient.createEmployeeAccount(payload, key) })
      } else if (action.kind === 'reset') {
        setResult({ kind: 'credentials', credentials: await apiClient.resetCrmProfilePassword(action.profile.id, key) })
      } else if (action.kind === 'otp') {
        await apiClient.resetCrmProfileSecondFactor(action.profile.id, key)
        setResult({ kind: 'done', message: 'Второй фактор сброшен.' })
      } else if (action.kind === 'logout') {
        await apiClient.endCrmProfileSessions(action.profile.id, key)
        setResult({ kind: 'done', message: 'Сеансы завершены: сотруднику нужно войти заново.' })
      } else {
        await apiClient.changeCrmProfileEmail(action.profile.id, email.trim(), key)
        setResult({ kind: 'done', message: 'Почта учётной записи изменена. Логин остался прежним.' })
      }
      onChanged()
    } catch (caught) {
      if (!handledSessionError(caught, { onSessionExpired, onProfileUnavailable })) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  const renderError = () => error !== undefined && (
    <div className="interaction-command-error" role="alert">
      <p>{commandErrorMessage(error)}</p>
      {error instanceof ApiError && <p className="request-id">Request ID: {error.requestId}</p>}
    </div>
  )

  const renderActions = (label: string, disabled = false, danger = false) => (
    <div className="confirm-dialog__actions">
      <button type="button" className="button--secondary" disabled={saving} onClick={close}>Отмена</button>
      <button type="submit" className={danger ? 'button--danger' : undefined} disabled={saving || disabled}>
        {saving ? 'Обращаемся к Keycloak…' : label}
      </button>
    </div>
  )

  const renderBody = () => {
    if (action === null) {
      return null
    }
    if (result?.kind === 'credentials') {
      return (
        <>
          <OneTimePassword credentials={result.credentials} created={action.kind === 'create'} />
          <div className="confirm-dialog__actions">
            <button type="button" onClick={close}>Я передал пароль, закрыть</button>
          </div>
        </>
      )
    }
    if (result?.kind === 'done') {
      return (
        <>
          <p role="status">{result.message}</p>
          <div className="confirm-dialog__actions">
            <button type="button" onClick={close}>Готово</button>
          </div>
        </>
      )
    }
    if (action.kind === 'create') {
      const teamOptions = teams.filter((team) => !team.archived)
      const incomplete = draft.displayName.trim() === '' || draft.login.trim() === '' || draft.email.trim() === ''
        || (teamRequired(draft.role) && draft.teamId === '')
      return (
        <form className="admin-profile-form employee-account__form" onSubmit={(event) => void run(event)}>
          <p className="admin-profile-form__hint">
            CRM создаст учётную запись Keycloak и активный профиль, выдаст временный пароль — он будет показан один раз.
            Писем система не отправляет.
          </p>
          <label>
            ФИО *
            <input
              value={draft.displayName}
              maxLength={200}
              required
              autoComplete="off"
              disabled={saving}
              onChange={(event) => changeDraft({ displayName: event.target.value })}
            />
          </label>
          <label>
            Логин *
            <input
              value={draft.login}
              maxLength={63}
              required
              autoComplete="off"
              spellCheck={false}
              disabled={saving}
              onChange={(event) => changeDraft({ login: event.target.value })}
            />
            <span className="interaction-field-hint">Латиница, цифры, точка, дефис; потом не меняется.</span>
          </label>
          <label>
            Почта *
            <input
              type="email"
              value={draft.email}
              maxLength={254}
              required
              autoComplete="off"
              disabled={saving}
              onChange={(event) => changeDraft({ email: event.target.value })}
            />
          </label>
          <label>
            Роль *
            <select
              value={draft.role}
              disabled={saving}
              onChange={(event) => changeDraft({ role: event.target.value as NewEmployee['role'] })}
            >
              <option value="USER">{roleLabels.USER}</option>
              <option value="LEADER">{roleLabels.LEADER}</option>
              <option value="ADMIN">{roleLabels.ADMIN}</option>
              <option value="MANAGEMENT">{roleLabels.MANAGEMENT}</option>
            </select>
          </label>
          <label>
            {teamRequired(draft.role) ? 'Команда *' : 'Команда'}
            <select
              value={draft.teamId}
              required={teamRequired(draft.role)}
              disabled={saving}
              onChange={(event) => changeDraft({ teamId: event.target.value })}
            >
              <option value="">{teamRequired(draft.role) ? 'Выберите команду' : 'Без команды'}</option>
              {teamOptions.map((team) => <option key={team.id} value={team.id}>{team.name}</option>)}
            </select>
          </label>
          <p className="admin-profile-form__hint">
            Представителю вуза доступ открывают в карточке вуза, во вкладке «Контакты». Флаг «Оператор зачисления» ставят
            после заведения кнопкой «Изменить».
          </p>
          {renderError()}
          {renderActions('Завести сотрудника', incomplete)}
        </form>
      )
    }
    const name = action.profile.displayName
    if (action.kind === 'email') {
      return (
        <form className="admin-profile-form employee-account__form" onSubmit={(event) => void run(event)}>
          <p className="admin-profile-form__hint">
            Почта учётной записи Keycloak сотрудника «{name}». Логин {action.profile.login ? `«${action.profile.login}» ` : ''}не меняется.
          </p>
          <label>
            Новая почта *
            <input
              type="email"
              value={email}
              maxLength={254}
              required
              autoComplete="off"
              disabled={saving}
              onChange={(event) => {
                setEmail(event.target.value)
                setError(undefined)
                setIdempotencyKey(null)
              }}
            />
          </label>
          {renderError()}
          {renderActions('Сменить почту', email.trim() === '')}
        </form>
      )
    }
    if (action.kind === 'otp') {
      const required = action.profile.role === 'ADMIN' || action.profile.enrolmentOperator
      return (
        <form className="employee-account__form" onSubmit={(event) => void run(event)}>
          <p>
            {`Приложение для кодов входа у «${name}» будет отвязано. `}
            {required
              ? 'При следующем входе система попросит подключить приложение заново.'
              : 'Дальше сотрудник входит только по паролю, пока сам не подключит приложение.'}
            {' Пароль и сеансы не меняются.'}
          </p>
          {renderError()}
          {renderActions('Сбросить второй фактор', false, true)}
        </form>
      )
    }
    return (
      <form className="employee-account__form" onSubmit={(event) => void run(event)}>
        <p>
          {action.kind === 'reset'
            ? `Сотрудник «${name}» получит новый временный пароль, прежний перестанет действовать. Пароль будет показан один раз; при входе Keycloak попросит задать свой. Действующие сеансы не завершаются — при подозрении на утечку завершите и их.`
            : `Все сеансы «${name}» в Keycloak и CRM будут завершены: на следующем действии сотруднику придётся войти заново. Учётная запись остаётся включённой — чтобы закрыть доступ совсем, снимите «Доступ к CRM открыт».`}
        </p>
        {renderError()}
        {renderActions(action.kind === 'reset' ? 'Выдать новый пароль' : 'Завершить сеансы', false, true)}
      </form>
    )
  }

  return (
    <CardDialog open={action !== null} title={action === null ? '' : titles[action.kind]} onClose={close}>
      {renderBody()}
    </CardDialog>
  )
}
