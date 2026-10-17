import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  type CrmProfile,
  type Me,
  type PageCrmProfile
} from '../../shared/api/client'
import { getModules } from '../../shared/modules'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { EmptyState } from '../../shared/ui/EmptyState'
import { FilterButton, FilterPanel } from '../../shared/ui/FilterPanel'
import { HintButton } from '../../shared/ui/HintButton'
import { Pagination } from '../../shared/ui/Pagination'
import { Segmented } from '../../shared/ui/Segmented'
import { Toast } from '../../shared/ui/Toast'
import { CommandError, LoadFailure } from './AdminErrors'
import { EmployeeAccountDialog, type AccountAction } from './EmployeeAccountDialog'
import { EmployeeEditorDialog } from './EmployeeEditorDialog'
import { EmployeeJournalDialog } from './EmployeeJournalDialog'
import {
  commandErrorMessage,
  formatDateTime,
  handledSessionError,
  requestIdOf,
  roleLabels,
  type SessionHandlers
} from './adminShared'
import { profileName, teamLabel } from './employeeEdit'
import {
  employeeFilterParams,
  employeeMenuKeys,
  filterWithSegment,
  isEmployeeFiltered,
  noEmployeeFilter,
  panelFilterCount,
  roleSourceLabel,
  segmentOf,
  syncActionLabel,
  type EmployeeFilter,
  type EmployeeMenuKey,
  type EmployeeSegment,
  type EmployeeStatus
} from './employeeFilters'
import { saveFile, today } from './saveFile'
import { useAdminTeams } from './useAdminTeams'
import './adminUi.css'
import './security.css'

type AdminEmployeesScreenProps = SessionHandlers & {
  currentProfile: Me
  initialStatus?: '' | EmployeeStatus
}

type ProfilesState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageCrmProfile }
  | { kind: 'failed'; requestId?: string }

type AccountSyncState =
  | { kind: 'syncing'; profileId: string }
  | { kind: 'failed'; profileId: string; error: unknown }

const profilesPageSize = 50
const searchDelayMilliseconds = 300

const statusOf = (profile: CrmProfile) => {
  if (profile.active) {
    return { label: 'Активен', tone: 'ok' }
  }
  if (profile.pendingActivation) {
    return { label: 'Ожидает активации', tone: 'warn' }
  }
  return { label: 'Заблокирован', tone: 'danger' }
}

const statusOptions: { value: '' | EmployeeStatus; label: string }[] = [
  { value: '', label: 'Все' },
  { value: 'ACTIVE', label: 'Активные' },
  { value: 'PENDING', label: 'Ожидают активации' },
  { value: 'BLOCKED', label: 'Заблокированные' },
  { value: 'SYNC_REQUIRED', label: 'Требуют синхронизации' }
]

const menuLabels: Record<Exclude<EmployeeMenuKey, 'sync'>, string> = {
  edit: 'Изменить',
  journal: 'Журнал изменений',
  reset: 'Сбросить пароль',
  logout: 'Завершить сеансы',
  otp: 'Сбросить второй фактор',
  email: 'Сменить почту'
}

export const AdminEmployeesScreen = ({
  currentProfile,
  initialStatus = '',
  onSessionExpired,
  onProfileUnavailable
}: AdminEmployeesScreenProps) => {
  const handlers = { onSessionExpired, onProfileUnavailable }
  const teamsData = useAdminTeams(handlers)
  const [profilesState, setProfilesState] = useState<ProfilesState>({ kind: 'loading' })
  const [filter, setFilter] = useState<EmployeeFilter>({ ...noEmployeeFilter, status: initialStatus })
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [searchText, setSearchText] = useState('')
  const [search, setSearch] = useState('')
  const [pageIndex, setPageIndex] = useState(0)
  const [accountSync, setAccountSync] = useState<AccountSyncState | null>(null)
  const [editing, setEditing] = useState<CrmProfile | null>(null)
  const [journalOf, setJournalOf] = useState<CrmProfile | null>(null)
  const [accountAction, setAccountAction] = useState<AccountAction | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [busy, setBusy] = useState<'migrate' | 'export' | null>(null)
  const [actionError, setActionError] = useState<unknown>(undefined)
  const listRequestVersion = useRef(0)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const loadProfiles = useCallback(async () => {
    const requestVersion = ++listRequestVersion.current
    setProfilesState({ kind: 'loading' })
    try {
      const page = await apiClient.listCrmProfiles({
        page: pageIndex,
        size: profilesPageSize,
        sort: 'displayName,asc',
        ...employeeFilterParams(filter, search)
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
  }, [filter, handleError, pageIndex, search])

  useEffect(() => {
    void loadProfiles()
    return () => {
      listRequestVersion.current += 1
    }
  }, [loadProfiles])

  useEffect(() => {
    if (searchText.trim() === search) {
      return
    }
    const timer = window.setTimeout(() => {
      setSearch(searchText.trim())
      setPageIndex(0)
    }, searchDelayMilliseconds)
    return () => window.clearTimeout(timer)
  }, [search, searchText])

  const changeFilter = (next: EmployeeFilter) => {
    setPageIndex(0)
    setFilter(next)
  }

  const resetFilters = () => {
    changeFilter(noEmployeeFilter)
    setSearchText('')
    setSearch('')
  }

  const migrateRoles = async () => {
    if (busy !== null) {
      return
    }
    setBusy('migrate')
    setActionError(undefined)
    try {
      const result = await apiClient.migrateCrmRolesToKeycloak()
      const base = result.assigned === 0 && result.kept === 0 && result.failed === 0
        ? 'Роли уже перенесены в Keycloak: изменений нет'
        : `Роли перенесены в Keycloak: назначено ${result.assigned}, уже были ${result.kept}`
      setToast(`${base}${result.failed > 0 ? `, не перенесено ${result.failed} — повторите позже` : ''}.`)
      await loadProfiles()
    } catch (error) {
      if (!handleError(error)) {
        setActionError(error)
      }
    } finally {
      setBusy(null)
    }
  }

  const exportProfiles = async () => {
    if (busy !== null) {
      return
    }
    setBusy('export')
    setActionError(undefined)
    try {
      saveFile(await apiClient.exportCrmProfiles(employeeFilterParams(filter, search)), `Профили CRM ${today()}.xlsx`)
    } catch (error) {
      if (!handleError(error)) {
        setActionError(error)
      }
    } finally {
      setBusy(null)
    }
  }

  const syncAccount = async (profile: CrmProfile) => {
    setAccountSync({ kind: 'syncing', profileId: profile.id })
    try {
      await apiClient.syncCrmProfileAccount(profile.id)
      setAccountSync(null)
      await loadProfiles()
    } catch (error) {
      if (!handleError(error)) {
        setAccountSync({ kind: 'failed', profileId: profile.id, error })
      }
    }
  }

  const saved = (updated: CrmProfile) => {
    setEditing(null)
    setAccountSync(updated.accountSyncError ? { kind: 'failed', profileId: updated.id, error: updated.accountSyncError } : null)
    setToast(`Профиль «${profileName(updated)}» сохранён.`)
    void loadProfiles()
  }

  const reloadAfterConflict = () => {
    setEditing(null)
    void loadProfiles()
  }

  const rowMenu = (profile: CrmProfile): ActionMenuItem[] => {
    const select: Record<EmployeeMenuKey, () => void> = {
      edit: () => setEditing(profile),
      journal: () => setJournalOf(profile),
      reset: () => setAccountAction({ kind: 'reset', profile }),
      logout: () => setAccountAction({ kind: 'logout', profile }),
      otp: () => setAccountAction({ kind: 'otp', profile }),
      email: () => setAccountAction({ kind: 'email', profile }),
      sync: () => void syncAccount(profile)
    }
    return employeeMenuKeys(profile, currentProfile.id).map((key) => ({
      label: key === 'sync' ? syncActionLabel(profile) : menuLabels[key],
      onSelect: select[key],
      separated: key === 'sync'
    }))
  }

  const renderSyncRow = (profile: CrmProfile) => {
    if (!profile.accountSyncRequired && !profile.roleReconcileRequired) {
      return null
    }
    const syncing = accountSync?.kind === 'syncing' && accountSync.profileId === profile.id
    const failure = accountSync?.kind === 'failed' && accountSync.profileId === profile.id ? accountSync.error : null
    return (
      <tr className="admin-table__detail">
        <td colSpan={7}>
          <div className="security-account" role="status">
            {profile.roleReconcileRequired && (
              <p>
                Роль в Keycloak может отличаться от роли в CRM: изменение роли не завершилось. Требует сверки — CRM запишет
                в Keycloak роль «{roleLabels[profile.role]}».
              </p>
            )}
            {profile.accountSyncRequired && (
              <p>
                {profile.active
                  ? 'Учётная запись Keycloak отключена, хотя доступ к CRM открыт: требует синхронизации.'
                  : 'Доступ к CRM закрыт, но учётная запись Keycloak ещё не отключена: требует синхронизации.'}
              </p>
            )}
            {failure !== null && (
              <p>
                {typeof failure === 'string'
                  ? failure
                  : failure instanceof ApiError && failure.code === 'ACCOUNT_SYNC_FAILED' ? failure.message : commandErrorMessage(failure)}
              </p>
            )}
            <div className="security-actions">
              <button type="button" className="button--secondary admin-table__action" disabled={syncing} onClick={() => void syncAccount(profile)}>
                {syncing ? 'Синхронизируем…' : syncActionLabel(profile)}
              </button>
            </div>
          </div>
        </td>
      </tr>
    )
  }

  const headerMenu: ActionMenuItem[] = [
    { label: busy === 'migrate' ? 'Переносим роли…' : 'Перенести роли в Keycloak', onSelect: () => void migrateRoles() },
    { label: busy === 'export' ? 'Готовим файл…' : 'Выгрузить в Excel', onSelect: () => void exportProfiles() }
  ]

  const segment = segmentOf(filter)
  const pendingTotal = profilesState.kind === 'ready' ? profilesState.page.pendingTotal : 0
  const segments: { value: EmployeeSegment; label: string }[] = [
    { value: 'ALL', label: 'Все' },
    { value: 'PENDING', label: pendingTotal > 0 ? `Ожидают · ${pendingTotal}` : 'Ожидают' },
    { value: 'BLOCKED', label: 'Заблокированы' }
  ]
  const teams = teamsData.teams

  return (
    <section className="admin-page" aria-labelledby="admin-employees-title" aria-busy={profilesState.kind === 'loading'}>
      <div className="page-title">
        <h2 id="admin-employees-title">Сотрудники</h2>
        <HintButton label="О разделе «Сотрудники»">
          «Новый сотрудник» заводит учётную запись Keycloak и профиль CRM сразу с ролью и командой и выдаёт временный пароль.
          Сброс пароля, завершение сеансов и смена почты — в меню «⋯» у профиля. Сотрудник, которого завели в Keycloak вручную,
          после первого входа появляется в списке «Ожидают активации». Закрытие доступа сразу отключает учётную запись в Keycloak,
          открытие — включает; если Keycloak недоступен, профиль помечается «требует синхронизации» с причиной.
          Роль сотрудника хранится в Keycloak. Смена роли здесь сразу назначает роль в Keycloak; роль, изменённую в консоли
          Keycloak, CRM применяет при следующем входе сотрудника. Команду и флаг «Оператор зачисления» ведёт CRM. В Keycloak
          можно завести и свою роль, например «Отдел развития», включив в неё одну из ролей CRM — подробнее в справке.
          Пароль и второй фактор другого администратора здесь не сбрасываются: он меняет их сам в Keycloak.
        </HintButton>
        {profilesState.kind === 'ready' && <span className="page-title__count">Всего: {profilesState.page.total}</span>}
        <span className="page-title__spacer" />
        <button type="button" onClick={() => setAccountAction({ kind: 'create' })}>Новый сотрудник</button>
        <ActionMenu label="Ещё действия: сотрудники" items={headerMenu} />
      </div>

      <div className="admin-toolbar">
        <Segmented
          label="Показать сотрудников"
          value={segment ?? ('' as EmployeeSegment)}
          options={segments}
          onChange={(value) => changeFilter(filterWithSegment(filter, value))}
        />
        <div className="admin-toolbar__search">
          <input
            type="search"
            value={searchText}
            maxLength={200}
            placeholder="Имя или логин"
            aria-label="Имя или логин"
            onChange={(event) => setSearchText(event.target.value)}
          />
        </div>
        <FilterButton count={panelFilterCount(filter)} onClick={() => setFiltersOpen(true)} />
      </div>

      {actionError !== undefined && <CommandError error={actionError} />}

      {profilesState.kind === 'loading' && <p className="organizations-message" role="status">Загружаем профили CRM…</p>}
      {profilesState.kind === 'failed' && (
        <LoadFailure
          text="Не удалось загрузить профили CRM. Повторите попытку."
          requestId={profilesState.requestId}
          onRetry={() => void loadProfiles()}
        />
      )}

      {profilesState.kind === 'ready' && (
        profilesState.page.items.length === 0 ? (
          <EmptyState
            text={isEmployeeFiltered(filter, search) ? 'По выбранным условиям профили не найдены.' : 'Профили CRM пока не найдены.'}
            action={isEmployeeFiltered(filter, search)
              ? <button type="button" className="button--secondary" onClick={resetFilters}>Сбросить фильтры</button>
              : undefined}
          />
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table" aria-label="Список профилей CRM">
              <thead>
                <tr>
                  <th scope="col">Сотрудник</th>
                  <th scope="col">Роль</th>
                  <th scope="col">Команда или вуз</th>
                  <th scope="col">Логин</th>
                  <th scope="col">Доступ</th>
                  <th scope="col">Последний вход (МСК)</th>
                  <th scope="col"><span className="admin-visually-hidden">Действия</span></th>
                </tr>
              </thead>
              <tbody>
                {profilesState.page.items.map((profile) => {
                  const status = statusOf(profile)
                  const source = roleSourceLabel(profile)
                  const partner = profile.role === 'PARTNER'
                  return (
                    <Fragment key={profile.id}>
                      <tr>
                        <td className="admin-table__cell-main">
                          <span className="admin-table__main">{profileName(profile)}</span>
                          {profile.id === currentProfile.id && <span className="admin-chip"> Текущий сеанс</span>}
                          {profile.pendingActivation && profile.activationRequestedAt && (
                            <span className="admin-table__sub admin-table__sub--warning">
                              Сотрудник сообщил о себе {formatDateTime(profile.activationRequestedAt)}
                            </span>
                          )}
                          {(profile.accountSyncRequired || profile.roleReconcileRequired) && (
                            <span className="admin-table__sub admin-table__sub--warning">
                              {profile.accountSyncRequired ? 'требует синхронизации' : 'Роль в Keycloak может отличаться'}
                            </span>
                          )}
                        </td>
                        <td data-label="Роль">
                          <span>
                            {roleLabels[profile.role]}
                            {source !== null && (
                              <span className={`admin-table__sub${source === 'из Keycloak' ? '' : ' admin-table__sub--warning'}`}>{source}</span>
                            )}
                            {getModules().enrolment && profile.enrolmentOperator && (
                              <span className="admin-table__sub">Оператор зачисления</span>
                            )}
                          </span>
                        </td>
                        <td data-label={partner ? 'Вуз' : 'Команда'}>
                          {partner ? profile.partnerOrganizationName : teamLabel(profile.teamName)}
                        </td>
                        <td data-label="Логин">{profile.login ?? '—'}</td>
                        <td data-label="Доступ"><span className={`admin-chip admin-chip--${status.tone}`}>{status.label}</span></td>
                        <td data-label="Последний вход (МСК)">
                          {profile.pendingActivation ? '—' : profile.lastLoginAt ? formatDateTime(profile.lastLoginAt) : 'ещё не входил'}
                        </td>
                        <td className="admin-table__actions">
                          {profile.pendingActivation && (
                            <button type="button" className="button--secondary admin-table__action" onClick={() => setEditing(profile)}>
                              Активировать
                            </button>
                          )}
                          <ActionMenu label={`Действия: ${profileName(profile)}`} items={rowMenu(profile)} />
                        </td>
                      </tr>
                      {renderSyncRow(profile)}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )
      )}

      {profilesState.kind === 'ready' && (
        <Pagination
          label="Страницы профилей CRM"
          page={profilesState.page.page}
          size={profilesState.page.size}
          total={profilesState.page.total}
          onChange={setPageIndex}
        />
      )}

      <FilterPanel open={filtersOpen} onClose={() => setFiltersOpen(false)} onReset={() => changeFilter(noEmployeeFilter)}>
        <div className="admin-form">
          <label>
            Состояние
            <select value={filter.status} onChange={(event) => changeFilter({ ...filter, status: event.target.value as EmployeeFilter['status'] })}>
              {statusOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </label>
          <label>
            Роль
            <select value={filter.role} onChange={(event) => changeFilter({ ...filter, role: event.target.value as EmployeeFilter['role'] })}>
              <option value="">Все роли</option>
              <option value="USER">{roleLabels.USER}</option>
              <option value="LEADER">{roleLabels.LEADER}</option>
              <option value="ADMIN">{roleLabels.ADMIN}</option>
              <option value="MANAGEMENT">{roleLabels.MANAGEMENT}</option>
              <option value="PARTNER">{roleLabels.PARTNER}</option>
            </select>
          </label>
          <label>
            Команда
            <select value={filter.teamId} onChange={(event) => changeFilter({ ...filter, teamId: event.target.value })}>
              <option value="">Все команды</option>
              {teams.map((team) => (
                <option key={team.id} value={team.id}>{team.name}{team.archived ? ' (архив)' : ''}</option>
              ))}
            </select>
          </label>
        </div>
      </FilterPanel>

      <EmployeeEditorDialog
        profile={editing}
        currentProfile={currentProfile}
        teamsState={teamsData.state}
        onClose={() => setEditing(null)}
        onSaved={saved}
        onReload={reloadAfterConflict}
        {...handlers}
      />
      <EmployeeJournalDialog profile={journalOf} onClose={() => setJournalOf(null)} {...handlers} />
      <EmployeeAccountDialog
        action={accountAction}
        teams={teams}
        onClose={() => setAccountAction(null)}
        onChanged={() => void loadProfiles()}
        {...handlers}
      />
      <Toast message={toast} onClose={() => setToast(null)} />
    </section>
  )
}
