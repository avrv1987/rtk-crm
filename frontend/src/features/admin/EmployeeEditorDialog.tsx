import { useCallback, useEffect, useState, type FormEvent } from 'react'
import {
  apiClient,
  createIdempotencyKey,
  type CrmProfile,
  type CrmProfileOrganizations,
  type LoginLockoutStatus,
  type Me
} from '../../shared/api/client'
import { getModules } from '../../shared/modules'
import { CardDialog } from '../interactions/cardUi'
import { CommandError, LoadFailure } from './AdminErrors'
import { handledSessionError, requestIdOf, roleLabels, type SessionHandlers } from './adminShared'
import {
  accessSummary,
  changesAccess,
  draftOf,
  operatorAllowed,
  profileName,
  updateOf,
  type ProfileDraft
} from './employeeEdit'
import { lockoutText, transferSummary, unassignsOrganizations } from './employeeFilters'
import type { TeamsState } from './useAdminTeams'
import './adminUi.css'

type EmployeeEditorDialogProps = SessionHandlers & {
  profile: CrmProfile | null
  currentProfile: Me
  teamsState: TeamsState
  onClose: () => void
  onSaved: (updated: CrmProfile) => void
  onReload: () => void
}

type EditorFormProps = Omit<EmployeeEditorDialogProps, 'profile'> & { profile: CrmProfile }

type Loaded<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T }
  | { kind: 'failed'; requestId?: string }

const VISIBLE_ORGANIZATIONS = 10

// Перед блокировкой, сменой роли или команды КАМ: его вузы и выбор КАМ команды, которому их передать той же командой.
const OrganizationsTransfer = ({
  profile,
  transferTo,
  disabled,
  onChange,
  ...handlers
}: SessionHandlers & {
  profile: CrmProfile
  transferTo: string
  disabled: boolean
  onChange: (profileId: string) => void
}) => {
  const [state, setState] = useState<Loaded<CrmProfileOrganizations>>({ kind: 'loading' })
  const { onSessionExpired, onProfileUnavailable } = handlers

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', value: await apiClient.listCrmProfileOrganizations(profile.id) })
    } catch (error) {
      if (!handledSessionError(error, { onSessionExpired, onProfileUnavailable })) {
        setState({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }, [onProfileUnavailable, onSessionExpired, profile.id])

  useEffect(() => {
    void load()
  }, [load])

  if (state.kind === 'loading') {
    return <p role="status">Загружаем вузы сотрудника…</p>
  }
  if (state.kind === 'failed') {
    return <LoadFailure text="Не удалось загрузить вузы сотрудника." requestId={state.requestId} onRetry={() => void load()} />
  }
  const { organizations, candidates } = state.value
  const recipient = candidates.find((candidate) => candidate.id === transferTo)?.displayName ?? null
  return (
    <div className="employee-transfer">
      <p>{transferSummary(organizations.length, recipient)}</p>
      {organizations.length > 0 && (
        <>
          <ul>
            {organizations.slice(0, VISIBLE_ORGANIZATIONS).map((organization) => (
              <li key={organization.id}><a href={`#/organizations/${organization.id}`}>{organization.name}</a></li>
            ))}
            {organizations.length > VISIBLE_ORGANIZATIONS && <li>и ещё {organizations.length - VISIBLE_ORGANIZATIONS}</li>}
          </ul>
          <label>
            Передать вузы
            <select value={transferTo} disabled={disabled} onChange={(event) => onChange(event.target.value)}>
              <option value="">Не передавать — вузы останутся без КАМ</option>
              {candidates.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.displayName}</option>)}
            </select>
          </label>
          {candidates.length === 0 && <p className="admin-form__hint">В команде нет другого действующего КАМ.</p>}
        </>
      )}
    </div>
  )
}

// Счётчик неудачных входов и временная блокировка Keycloak: в CRM сотрудник остаётся «Активен», а войти не может.
const LoginLockoutPanel = ({ profile, ...handlers }: SessionHandlers & { profile: CrmProfile }) => {
  const [state, setState] = useState<Loaded<LoginLockoutStatus>>({ kind: 'loading' })
  const [releasing, setReleasing] = useState(false)
  const [error, setError] = useState<unknown>(undefined)
  const { onSessionExpired, onProfileUnavailable } = handlers

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', value: await apiClient.getCrmProfileLoginLockout(profile.id) })
    } catch (failure) {
      if (!handledSessionError(failure, { onSessionExpired, onProfileUnavailable })) {
        setState({ kind: 'failed', requestId: requestIdOf(failure) })
      }
    }
  }, [onProfileUnavailable, onSessionExpired, profile.id])

  useEffect(() => {
    void load()
  }, [load])

  const release = async () => {
    setReleasing(true)
    setError(undefined)
    try {
      setState({ kind: 'ready', value: await apiClient.releaseCrmProfileLoginLockout(profile.id) })
    } catch (failure) {
      if (!handledSessionError(failure, { onSessionExpired, onProfileUnavailable })) {
        setError(failure)
      }
    } finally {
      setReleasing(false)
    }
  }

  return (
    <section className="employee-login" aria-label="Вход в систему">
      <h3>Вход в систему</h3>
      {state.kind === 'loading' && <p role="status">Спрашиваем Keycloak…</p>}
      {state.kind === 'failed' && (
        <LoadFailure text="Не удалось получить неудачные входы из Keycloak." requestId={state.requestId} onRetry={() => void load()} />
      )}
      {state.kind === 'ready' && (
        <>
          <p role={state.value.locked ? 'alert' : undefined}>{lockoutText(state.value)}</p>
          {state.value.available && (state.value.locked || state.value.failures > 0) && (
            <button type="button" className="button--secondary" disabled={releasing} onClick={() => void release()}>
              {releasing ? 'Снимаем…' : 'Снять блокировку входа'}
            </button>
          )}
        </>
      )}
      {error !== undefined && <CommandError error={error} />}
    </section>
  )
}

const EditorForm = ({
  profile,
  currentProfile,
  teamsState,
  onClose,
  onSaved,
  onReload,
  onSessionExpired,
  onProfileUnavailable
}: EditorFormProps) => {
  const [draft, setDraft] = useState<ProfileDraft>(() => draftOf(profile))
  const [confirming, setConfirming] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(undefined)
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null)
  const [transferTo, setTransferTo] = useState('')
  const isCurrentProfile = profile.id === currentProfile.id
  const partner = profile.role === 'PARTNER'
  const update = updateOf(profile, draft)
  const hasChanges = Object.keys(update).length > 1
  const nameInvalid = draft.displayName.trim().length === 0
  const teamOptions = teamsState.kind === 'ready'
    ? teamsState.teams.filter((team) => !team.archived || team.id === profile.teamId)
    : []

  const change = (changes: Partial<ProfileDraft>) => {
    setDraft((current) => ({ ...current, ...changes }))
    setConfirming(false)
    setError(undefined)
    setIdempotencyKey(null)
    setTransferTo('')
  }

  const unassigns = unassignsOrganizations(profile, update)

  const changeTransfer = (profileId: string) => {
    setTransferTo(profileId)
    setError(undefined)
    setIdempotencyKey(null)
  }

  const save = async (confirmed: boolean) => {
    if (saving) {
      return
    }
    if (changesAccess(update) && !confirmed) {
      setConfirming(true)
      setError(undefined)
      return
    }
    const key = idempotencyKey ?? createIdempotencyKey()
    setSaving(true)
    setError(undefined)
    setIdempotencyKey(key)
    try {
      const payload = unassigns && transferTo !== '' ? { ...update, transferOrganizationsTo: transferTo } : update
      onSaved(await apiClient.updateCrmProfile(profile.id, payload, key))
    } catch (failure) {
      if (!handledSessionError(failure, { onSessionExpired, onProfileUnavailable })) {
        setSaving(false)
        setError(failure)
      }
    }
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void save(false)
  }

  return (
    <form className="admin-form admin-form--cols" onSubmit={submit} aria-label={`Изменение профиля ${profileName(profile)}`}>
      <label className="admin-form__wide">
        Отображаемое имя *
        <input
          value={draft.displayName}
          maxLength={200}
          required
          disabled={saving}
          onChange={(event) => change({ displayName: event.target.value })}
        />
      </label>
      <label>
        Роль
        <select
          value={draft.role}
          disabled={isCurrentProfile || partner || saving}
          onChange={(event) => change({ role: event.target.value as CrmProfile['role'] })}
        >
          <option value="USER">{roleLabels.USER}</option>
          <option value="LEADER">{roleLabels.LEADER}</option>
          <option value="ADMIN">{roleLabels.ADMIN}</option>
          <option value="MANAGEMENT">{roleLabels.MANAGEMENT}</option>
          {partner && <option value="PARTNER">{roleLabels.PARTNER}</option>}
        </select>
      </label>
      <label>
        Команда
        <select
          value={draft.teamId}
          disabled={isCurrentProfile || partner || saving || teamsState.kind !== 'ready'}
          onChange={(event) => change({ teamId: event.target.value })}
        >
          <option value="">Без команды</option>
          {teamOptions.map((team) => (
            <option key={team.id} value={team.id}>{team.name}</option>
          ))}
        </select>
      </label>
      <label className="admin-form__check admin-form__wide">
        <input
          type="checkbox"
          checked={draft.active}
          disabled={isCurrentProfile || (partner && !profile.active) || saving}
          onChange={(event) => change({ active: event.target.checked })}
        />
        {partner ? 'Доступ в кабинет вуза открыт' : 'Доступ к CRM открыт'}
      </label>
      {partner && (
        <p className="admin-form__hint admin-form__wide">
          Координатор вуза «{profile.partnerOrganizationName}». Здесь доступ можно только закрыть; открывают его
          ответственный КАМ или руководитель команды в карточке вуза, во вкладке «Контакты».
        </p>
      )}
      {getModules().enrolment && (
        <label className="admin-form__check admin-form__wide">
          <input
            type="checkbox"
            checked={draft.enrolmentOperator && operatorAllowed(draft.role)}
            disabled={saving || !operatorAllowed(draft.role)}
            onChange={(event) => change({ enrolmentOperator: event.target.checked })}
          />
          Оператор зачисления: загрузка оплат и данные слушателей
        </label>
      )}
      {getModules().enrolment && !operatorAllowed(draft.role) && (
        <p className="admin-form__hint admin-form__wide">Флаг «Оператор зачисления» доступен только КАМ и руководителю команды.</p>
      )}
      {isCurrentProfile && (
        <p className="admin-form__hint admin-form__wide">Свою роль, команду и доступ изменить нельзя — это делает другой администратор.</p>
      )}
      {profile.pendingActivation && (
        <p className="admin-form__hint admin-form__wide">
          Профиль создан при первом входе сотрудника. Выберите роль и команду и откройте доступ — КАМ и руководителю команда обязательна.
        </p>
      )}
      {confirming && (
        <div className="admin-profiles__confirmation admin-form__wide" role="alertdialog" aria-labelledby={`profile-confirmation-${profile.id}`}>
          <h3 id={`profile-confirmation-${profile.id}`}>Подтвердите изменение доступа</h3>
          <ul>
            {accessSummary(profile, update, teamOptions).map((line) => <li key={line}>{line}</li>)}
          </ul>
          {unassigns && (
            <OrganizationsTransfer
              profile={profile}
              transferTo={transferTo}
              disabled={saving}
              onChange={changeTransfer}
              onSessionExpired={onSessionExpired}
              onProfileUnavailable={onProfileUnavailable}
            />
          )}
          <div className="admin-form__actions">
            <button type="button" className="button--secondary" disabled={saving} onClick={() => setConfirming(false)}>
              Вернуться к форме
            </button>
            <button type="button" disabled={saving} onClick={() => void save(true)}>
              {saving ? 'Сохраняем…' : 'Подтвердить'}
            </button>
          </div>
        </div>
      )}
      {error !== undefined && (
        <div className="admin-form__wide">
          <CommandError error={error} onConflict={onReload} />
        </div>
      )}
      {!confirming && (
        <div className="admin-form__actions admin-form__wide">
          <button type="button" className="button--secondary" disabled={saving} onClick={onClose}>Отмена</button>
          <button type="submit" disabled={!hasChanges || nameInvalid || saving}>
            {saving ? 'Сохраняем…' : 'Сохранить'}
          </button>
        </div>
      )}
    </form>
  )
}

export const EmployeeEditorDialog = ({ profile, ...rest }: EmployeeEditorDialogProps) => (
  <CardDialog
    open={profile !== null}
    title={profile === null ? '' : `${profile.pendingActivation ? 'Активация' : 'Изменение'} профиля: ${profileName(profile)}`}
    onClose={rest.onClose}
  >
    {profile !== null && <EditorForm key={profile.id} profile={profile} {...rest} />}
    {profile !== null && (
      <LoginLockoutPanel
        key={`login-${profile.id}`}
        profile={profile}
        onSessionExpired={rest.onSessionExpired}
        onProfileUnavailable={rest.onProfileUnavailable}
      />
    )}
  </CardDialog>
)
