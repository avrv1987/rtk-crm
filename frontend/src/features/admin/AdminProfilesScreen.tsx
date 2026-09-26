import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type CrmProfile,
  type CrmProfileEvent,
  type CrmProfileUpdate,
  type Me,
  type PageCrmProfile,
  type Team
} from '../../shared/api/client'
import { AdminOrganizationsPanel } from './AdminOrganizationsPanel'
import { AdminTeamsPanel, type TeamsState } from './AdminTeamsPanel'
import {
  commandErrorMessage,
  formatDateTime,
  handledSessionError,
  isVersionConflict,
  requestIdOf,
  roleLabels,
  type SessionHandlers
} from './adminShared'

type AdminProfilesScreenProps = SessionHandlers & {
  currentProfile: Me
}

type ProfilesState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageCrmProfile }
  | { kind: 'failed'; requestId?: string }

type ProfileFilter = 'all' | 'pending'

type ProfileDraft = {
  displayName: string
  role: CrmProfile['role']
  teamId: string
  active: boolean
}

type EditState = {
  profile: CrmProfile
  draft: ProfileDraft
  confirming: boolean
  saving: boolean
  error?: unknown
  idempotencyKey: string | null
}

type JournalState =
  | { kind: 'loading'; profileId: string }
  | { kind: 'ready'; profileId: string; events: CrmProfileEvent[] }
  | { kind: 'failed'; profileId: string; requestId?: string }

const profilesPageSize = 50

const profileName = (profile: CrmProfile) => (
  profile.displayName.trim() || 'Профиль без отображаемого имени'
)

const statusOf = (profile: CrmProfile) => {
  if (profile.active) {
    return { label: 'Активен', className: 'admin-profiles__status--active' }
  }
  if (profile.pendingActivation) {
    return { label: 'Ожидает активации', className: 'admin-profiles__status--pending' }
  }
  return { label: 'Заблокирован', className: 'admin-profiles__status--blocked' }
}

const teamLabel = (teamName: string | null) => teamName ?? 'Без команды'

const draftOf = (profile: CrmProfile): ProfileDraft => ({
  displayName: profile.displayName,
  role: profile.role,
  teamId: profile.teamId ?? '',
  active: profile.active
})

const updateOf = (profile: CrmProfile, draft: ProfileDraft): CrmProfileUpdate => {
  const update: CrmProfileUpdate = { version: profile.version }
  const displayName = draft.displayName.trim()
  if (displayName !== profile.displayName) {
    update.displayName = displayName
  }
  if (draft.role !== profile.role) {
    update.role = draft.role
  }
  if (draft.teamId !== (profile.teamId ?? '')) {
    update.teamId = draft.teamId === '' ? null : draft.teamId
  }
  if (draft.active !== profile.active) {
    update.active = draft.active
  }
  return update
}

const changesAccess = (update: CrmProfileUpdate) => (
  update.role !== undefined || update.teamId !== undefined || update.active !== undefined
)

const teamNameById = (teams: Team[], teamId: string | null | undefined) => (
  teamId === null || teamId === undefined || teamId === ''
    ? 'Без команды'
    : teams.find((team) => team.id === teamId)?.name ?? 'Неизвестная команда'
)

const accessSummary = (profile: CrmProfile, update: CrmProfileUpdate, teams: Team[]) => {
  const lines: string[] = []
  if (update.role !== undefined) {
    lines.push(`Роль: ${roleLabels[profile.role]} → ${roleLabels[update.role]}`)
  }
  if (update.teamId !== undefined) {
    lines.push(`Команда: ${teamLabel(profile.teamName)} → ${teamNameById(teams, update.teamId)}`)
  }
  if (update.active !== undefined) {
    lines.push(update.active ? 'Доступ к CRM откроется' : 'Доступ к CRM будет закрыт')
  }
  const nextRole = update.role ?? profile.role
  const nextActive = update.active ?? profile.active
  if (profile.role === 'USER' && (update.teamId !== undefined || nextRole !== 'USER' || !nextActive)) {
    lines.push('Вузы, закреплённые за этим КАМ, получат статус «Требует назначения».')
  }
  lines.push('Новые права действуют со следующего запроса пользователя; ранее сформированные им отчёты станут недоступны для скачивания.')
  return lines
}

const eventChanges = (event: CrmProfileEvent) => {
  const changes: string[] = []
  if (event.previousDisplayName !== event.displayName) {
    changes.push(`Имя: ${event.previousDisplayName} → ${event.displayName}`)
  }
  if (event.previousRole !== event.role) {
    changes.push(`Роль: ${roleLabels[event.previousRole]} → ${roleLabels[event.role]}`)
  }
  if (event.previousTeamId !== event.teamId) {
    changes.push(`Команда: ${teamLabel(event.previousTeamName)} → ${teamLabel(event.teamName)}`)
  }
  if (event.previousActive !== event.active) {
    changes.push(event.active ? 'Доступ открыт' : 'Доступ закрыт')
  }
  return changes
}

export const AdminProfilesScreen = ({ currentProfile, onSessionExpired, onProfileUnavailable }: AdminProfilesScreenProps) => {
  const [profilesState, setProfilesState] = useState<ProfilesState>({ kind: 'loading' })
  const [teamsState, setTeamsState] = useState<TeamsState>({ kind: 'loading' })
  const [teamNamesRevision, setTeamNamesRevision] = useState(0)
  const [filter, setFilter] = useState<ProfileFilter>('all')
  const [pageIndex, setPageIndex] = useState(0)
  const [edit, setEdit] = useState<EditState | null>(null)
  const [journal, setJournal] = useState<JournalState | null>(null)
  const listRequestVersion = useRef(0)
  const journalRequestVersion = useRef(0)
  const teamsRequestVersion = useRef(0)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const loadProfiles = useCallback(async (requestedPage: number, requestedFilter: ProfileFilter) => {
    const requestVersion = ++listRequestVersion.current
    setProfilesState({ kind: 'loading' })
    try {
      const page = await apiClient.listCrmProfiles({
        page: requestedPage,
        size: profilesPageSize,
        sort: 'displayName,asc',
        pending: requestedFilter === 'pending'
      })
      if (requestVersion === listRequestVersion.current) {
        setProfilesState({ kind: 'ready', page })
      }
    } catch (error) {
      if (requestVersion !== listRequestVersion.current || handleError(error)) {
        return
      }
      setProfilesState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [handleError])

  const loadTeams = useCallback(async () => {
    const requestVersion = ++teamsRequestVersion.current
    setTeamsState({ kind: 'loading' })
    try {
      const teams = await apiClient.listTeams()
      if (requestVersion === teamsRequestVersion.current) {
        setTeamsState({ kind: 'ready', teams })
      }
    } catch (error) {
      if (requestVersion !== teamsRequestVersion.current || handleError(error)) {
        return
      }
      setTeamsState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [handleError])

  const loadJournal = useCallback(async (profileId: string) => {
    const requestVersion = ++journalRequestVersion.current
    setJournal({ kind: 'loading', profileId })
    try {
      const events = await apiClient.listCrmProfileEvents(profileId)
      if (requestVersion === journalRequestVersion.current) {
        setJournal({ kind: 'ready', profileId, events })
      }
    } catch (error) {
      if (requestVersion !== journalRequestVersion.current || handleError(error)) {
        return
      }
      setJournal({ kind: 'failed', profileId, requestId: requestIdOf(error) })
    }
  }, [handleError])

  useEffect(() => {
    void loadProfiles(pageIndex, filter)
    return () => {
      listRequestVersion.current += 1
    }
  }, [filter, loadProfiles, pageIndex, teamNamesRevision])

  useEffect(() => {
    void loadTeams()
    return () => {
      teamsRequestVersion.current += 1
      journalRequestVersion.current += 1
    }
  }, [loadTeams])

  const teams = teamsState.kind === 'ready' ? teamsState.teams : []

  const handleTeamsChanged = () => {
    void loadTeams()
    setTeamNamesRevision((current) => current + 1)
  }

  const beginEdit = (profile: CrmProfile) => {
    setEdit({
      profile,
      draft: { ...draftOf(profile), active: profile.pendingActivation || profile.active },
      confirming: false,
      saving: false,
      idempotencyKey: null
    })
  }

  const changeDraft = (changes: Partial<ProfileDraft>) => {
    setEdit((current) => (
      current === null
        ? current
        : { ...current, draft: { ...current.draft, ...changes }, confirming: false, error: undefined, idempotencyKey: null }
    ))
  }

  const toggleJournal = (profileId: string) => {
    if (journal?.profileId === profileId) {
      journalRequestVersion.current += 1
      setJournal(null)
      return
    }
    void loadJournal(profileId)
  }

  const saveProfile = async (confirmed: boolean) => {
    if (edit === null || edit.saving) {
      return
    }
    const update = updateOf(edit.profile, edit.draft)
    if (changesAccess(update) && !confirmed) {
      setEdit({ ...edit, confirming: true, error: undefined })
      return
    }
    const idempotencyKey = edit.idempotencyKey ?? createIdempotencyKey()
    setEdit({ ...edit, saving: true, error: undefined, idempotencyKey })
    try {
      const updated = await apiClient.updateCrmProfile(edit.profile.id, update, idempotencyKey)
      setEdit(null)
      await loadProfiles(pageIndex, filter)
      if (journal?.profileId === updated.id) {
        await loadJournal(updated.id)
      }
    } catch (error) {
      if (handleError(error)) {
        return
      }
      setEdit((current) => (current === null ? current : { ...current, saving: false, error }))
    }
  }

  const submitEdit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void saveProfile(false)
  }

  const reloadAfterConflict = async () => {
    setEdit(null)
    await loadProfiles(pageIndex, filter)
  }

  const changeFilter = (value: ProfileFilter) => {
    setEdit(null)
    setPageIndex(0)
    setFilter(value)
  }

  const renderEditor = (profile: CrmProfile) => {
    if (edit === null || edit.profile.id !== profile.id) {
      return null
    }
    const isCurrentProfile = profile.id === currentProfile.id
    const update = updateOf(profile, edit.draft)
    const hasChanges = Object.keys(update).length > 1
    const nameInvalid = edit.draft.displayName.trim().length === 0
    const teamOptions = teamsState.kind === 'ready' ? teamsState.teams : []
    return (
      <form className="admin-profile-form" onSubmit={submitEdit} aria-label={`Изменение профиля ${profileName(profile)}`}>
        <label>
          Отображаемое имя
          <input
            value={edit.draft.displayName}
            maxLength={200}
            required
            disabled={edit.saving}
            onChange={(event) => changeDraft({ displayName: event.target.value })}
          />
        </label>
        <label>
          Роль
          <select
            value={edit.draft.role}
            disabled={isCurrentProfile || edit.saving}
            onChange={(event) => changeDraft({ role: event.target.value as CrmProfile['role'] })}
          >
            <option value="USER">{roleLabels.USER}</option>
            <option value="LEADER">{roleLabels.LEADER}</option>
            <option value="ADMIN">{roleLabels.ADMIN}</option>
          </select>
        </label>
        <label>
          Команда
          <select
            value={edit.draft.teamId}
            disabled={isCurrentProfile || edit.saving || teamsState.kind !== 'ready'}
            onChange={(event) => changeDraft({ teamId: event.target.value })}
          >
            <option value="">Без команды</option>
            {teamOptions.map((team) => (
              <option key={team.id} value={team.id}>{team.name}</option>
            ))}
          </select>
        </label>
        <label className="admin-profile-form__checkbox">
          <input
            type="checkbox"
            checked={edit.draft.active}
            disabled={isCurrentProfile || edit.saving}
            onChange={(event) => changeDraft({ active: event.target.checked })}
          />
          Доступ к CRM открыт
        </label>
        {isCurrentProfile && (
          <p className="admin-profile-form__hint">Свою роль, команду и доступ изменить нельзя — это делает другой администратор.</p>
        )}
        {profile.pendingActivation && (
          <p className="admin-profile-form__hint">
            Профиль создан при первом входе сотрудника. Выберите роль и команду и откройте доступ — менеджеру и руководителю команда обязательна.
          </p>
        )}
        {edit.confirming && (
          <div
            className="admin-profiles__confirmation"
            role="alertdialog"
            aria-labelledby={`profile-confirmation-${profile.id}`}
          >
            <h3 id={`profile-confirmation-${profile.id}`}>Подтвердите изменение доступа</h3>
            <ul>
              {accessSummary(profile, update, teamOptions).map((line) => <li key={line}>{line}</li>)}
            </ul>
            <div className="admin-profiles__confirmation-actions">
              <button type="button" disabled={edit.saving} onClick={() => void saveProfile(true)}>
                {edit.saving ? 'Сохраняем…' : 'Подтвердить'}
              </button>
              <button
                type="button"
                className="admin-profiles__cancel"
                disabled={edit.saving}
                onClick={() => setEdit({ ...edit, confirming: false })}
              >
                Вернуться к форме
              </button>
            </div>
          </div>
        )}
        {edit.error !== undefined && (
          <div className="interaction-command-error" role="alert">
            <p>{commandErrorMessage(edit.error)}</p>
            {edit.error instanceof ApiError && <p className="request-id">Request ID: {edit.error.requestId}</p>}
            {isVersionConflict(edit.error) && (
              <button type="button" onClick={() => void reloadAfterConflict()}>Обновить список</button>
            )}
          </div>
        )}
        {!edit.confirming && (
          <div className="admin-profiles__confirmation-actions">
            <button type="submit" disabled={!hasChanges || nameInvalid || edit.saving}>
              {edit.saving ? 'Сохраняем…' : 'Сохранить'}
            </button>
            <button type="button" className="admin-profiles__cancel" disabled={edit.saving} onClick={() => setEdit(null)}>
              Отмена
            </button>
          </div>
        )}
      </form>
    )
  }

  const renderJournal = (profile: CrmProfile) => {
    if (journal === null || journal.profileId !== profile.id) {
      return null
    }
    return (
      <section className="admin-profile-journal" aria-label={`Журнал изменений профиля ${profileName(profile)}`}>
        <h4>Журнал изменений</h4>
        {journal.kind === 'loading' && <p role="status">Загружаем журнал…</p>}
        {journal.kind === 'failed' && (
          <div className="organizations-message organizations-message--error" role="alert">
            <p>Не удалось загрузить журнал.</p>
            {journal.requestId && <p className="request-id">Request ID: {journal.requestId}</p>}
            <button type="button" onClick={() => void loadJournal(profile.id)}>Повторить</button>
          </div>
        )}
        {journal.kind === 'ready' && journal.events.length === 0 && (
          <p>Изменений профиля через администрирование ещё не было.</p>
        )}
        {journal.kind === 'ready' && journal.events.length > 0 && (
          <ol>
            {journal.events.map((event) => (
              <li key={event.id}>
                <p>
                  <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
                  {' — '}
                  {event.actorDisplayName}
                </p>
                <ul>
                  {eventChanges(event).map((line) => <li key={line}>{line}</li>)}
                </ul>
                <p className="request-id">Request ID: {event.requestId}</p>
              </li>
            ))}
          </ol>
        )}
      </section>
    )
  }

  return (
    <>
      <section className="admin-profiles" aria-labelledby="admin-profiles-title" aria-busy={profilesState.kind === 'loading'}>
        <div className="admin-profiles__header">
          <div>
            <p className="eyebrow">Администрирование</p>
            <h2 id="admin-profiles-title">Профили CRM</h2>
          </div>
          {profilesState.kind === 'ready' && <p className="admin-profiles__total">Всего: {profilesState.page.total}</p>}
        </div>

        <p className="admin-profiles__intro">
          Назначайте роль и команду, открывайте и закрывайте доступ. Новый сотрудник после первого входа появляется в списке «Ожидают активации».
        </p>

        <label className="admin-profiles__filter">
          Показать
          <select value={filter} onChange={(event) => changeFilter(event.target.value as ProfileFilter)}>
            <option value="all">Все профили</option>
            <option value="pending">Ожидают активации</option>
          </select>
        </label>

        {profilesState.kind === 'loading' && (
          <p className="organizations-message" role="status">Загружаем профили CRM…</p>
        )}

        {profilesState.kind === 'failed' && (
          <div className="organizations-message organizations-message--error" role="alert">
            <p>Не удалось загрузить профили CRM. Повторите попытку.</p>
            {profilesState.requestId && <p className="request-id">Request ID: {profilesState.requestId}</p>}
            <button type="button" onClick={() => void loadProfiles(pageIndex, filter)}>Повторить</button>
          </div>
        )}

        {profilesState.kind === 'ready' && (
          <>
            {profilesState.page.items.length === 0 ? (
              <p className="organizations-message">
                {filter === 'pending' ? 'Нет профилей, ожидающих активации.' : 'Профили CRM пока не найдены.'}
              </p>
            ) : (
              <ul className="admin-profiles__list" aria-label="Список профилей CRM">
                {profilesState.page.items.map((profile) => {
                  const status = statusOf(profile)
                  const isEditing = edit?.profile.id === profile.id
                  return (
                    <li key={profile.id} className="admin-profiles__item">
                      <div className="admin-profiles__identity">
                        <div>
                          <h3>{profileName(profile)}</h3>
                          <p className={`admin-profiles__status ${status.className}`}>{status.label}</p>
                        </div>
                        <dl className="admin-profiles__fields">
                          <div>
                            <dt>Роль</dt>
                            <dd>{roleLabels[profile.role]}</dd>
                          </div>
                          <div>
                            <dt>Команда</dt>
                            <dd>{teamLabel(profile.teamName)}</dd>
                          </div>
                          <div>
                            <dt>Ревизия доступа</dt>
                            <dd>{profile.accessRevision}</dd>
                          </div>
                        </dl>
                        {renderEditor(profile)}
                        {renderJournal(profile)}
                      </div>
                      <div className="admin-profiles__actions">
                        {profile.id === currentProfile.id && <p className="admin-profiles__current">Текущий сеанс</p>}
                        {!isEditing && (
                          <button type="button" disabled={edit?.saving === true} onClick={() => beginEdit(profile)}>
                            {profile.pendingActivation ? 'Активировать' : 'Изменить'}
                          </button>
                        )}
                        <button type="button" className="admin-profiles__cancel" onClick={() => toggleJournal(profile.id)}>
                          {journal?.profileId === profile.id ? 'Скрыть журнал' : 'Журнал'}
                        </button>
                      </div>
                    </li>
                  )
                })}
              </ul>
            )}

            {profilesState.page.total > profilesState.page.size && (
              <nav className="admin-profiles__pagination" aria-label="Страницы профилей CRM">
                <button
                  type="button"
                  disabled={pageIndex === 0 || edit?.saving === true}
                  onClick={() => setPageIndex((current) => Math.max(0, current - 1))}
                >
                  Предыдущая
                </button>
                <p>Страница {profilesState.page.page + 1}</p>
                <button
                  type="button"
                  disabled={(profilesState.page.page + 1) * profilesState.page.size >= profilesState.page.total || edit?.saving === true}
                  onClick={() => setPageIndex((current) => current + 1)}
                >
                  Следующая
                </button>
              </nav>
            )}
          </>
        )}
      </section>
      <AdminTeamsPanel
        teamsState={teamsState}
        onReload={() => void loadTeams()}
        onTeamsChanged={handleTeamsChanged}
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />
      <AdminOrganizationsPanel
        teams={teams}
        teamNamesRevision={teamNamesRevision}
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />
    </>
  )
}
