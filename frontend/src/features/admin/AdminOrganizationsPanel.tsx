import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type AdminOrganization,
  type PageAdminOrganization,
  type Team
} from '../../shared/api/client'
import {
  commandErrorMessage,
  handledSessionError,
  isVersionConflict,
  requestIdOf,
  type SessionHandlers
} from './adminShared'
import { OrganizationCatalogPanel, OrganizationStatusBadge } from '../organizations/OrganizationCatalogPanel'
import { OrganizationForm, organizationTypeLabels } from '../organizations/OrganizationForm'

type AdminOrganizationsPanelProps = SessionHandlers & {
  teams: Team[]
  teamNamesRevision: number
}

type OrganizationsState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageAdminOrganization }
  | { kind: 'failed'; requestId?: string }

type Transfer = {
  organization: AdminOrganization
  teamId: string
  confirming: boolean
  saving: boolean
  error?: unknown
  idempotencyKey: string | null
}

const organizationsPageSize = 25

const organizationTypeLabel = (type: AdminOrganization['type']) => organizationTypeLabels[type]

type ListStatus = 'CURRENT' | 'PENDING' | 'ARCHIVED' | 'ALL'

const listStatuses: Array<{ value: ListStatus; label: string }> = [
  { value: 'CURRENT', label: 'Действующие' },
  { value: 'PENDING', label: 'Ожидают подтверждения' },
  { value: 'ARCHIVED', label: 'Архив' },
  { value: 'ALL', label: 'Все' }
]

const searchDelayMilliseconds = 300

export const AdminOrganizationsPanel = ({
  teams,
  teamNamesRevision,
  onSessionExpired,
  onProfileUnavailable
}: AdminOrganizationsPanelProps) => {
  const [organizationsState, setOrganizationsState] = useState<OrganizationsState>({ kind: 'loading' })
  const [pageIndex, setPageIndex] = useState(0)
  const [transfer, setTransfer] = useState<Transfer | null>(null)
  const [searchText, setSearchText] = useState('')
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<ListStatus>('CURRENT')
  const [creating, setCreating] = useState(false)
  const [createdMessage, setCreatedMessage] = useState<string | null>(null)
  const requestVersion = useRef(0)
  const activeTeams = teams.filter((team) => !team.archived)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const loadOrganizations = useCallback(async (requestedPage: number) => {
    const version = ++requestVersion.current
    setOrganizationsState({ kind: 'loading' })
    try {
      const page = await apiClient.listAdminOrganizations({ page: requestedPage, size: organizationsPageSize, q: search, status })
      if (version === requestVersion.current) {
        setOrganizationsState({ kind: 'ready', page })
      }
    } catch (error) {
      if (version !== requestVersion.current || handleError(error)) {
        return
      }
      setOrganizationsState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [handleError, search, status])

  useEffect(() => {
    void loadOrganizations(pageIndex)
    return () => {
      requestVersion.current += 1
    }
  }, [loadOrganizations, pageIndex, teamNamesRevision])

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

  const replaceOrganization = (organization: AdminOrganization) => {
    setOrganizationsState((current) => (
      current.kind === 'ready'
        ? {
            kind: 'ready',
            page: {
              ...current.page,
              items: current.page.items.map((item) => (item.id === organization.id ? organization : item))
            }
          }
        : current
    ))
  }

  const confirmTransfer = async () => {
    if (transfer === null || transfer.saving) {
      return
    }
    const idempotencyKey = transfer.idempotencyKey ?? createIdempotencyKey()
    setTransfer({ ...transfer, saving: true, error: undefined, idempotencyKey })
    try {
      await apiClient.transferOrganizationTeam(
        transfer.organization.id,
        { teamId: transfer.teamId, version: transfer.organization.version },
        idempotencyKey
      )
      setTransfer(null)
      await loadOrganizations(pageIndex)
    } catch (error) {
      if (handleError(error)) {
        return
      }
      setTransfer((current) => (current === null ? current : { ...current, saving: false, error }))
    }
  }

  const reloadAfterConflict = async () => {
    setTransfer(null)
    await loadOrganizations(pageIndex)
  }

  const targetTeamName = (teamId: string) => teams.find((team) => team.id === teamId)?.name ?? 'выбранную команду'

  return (
    <section className="admin-profiles" aria-labelledby="admin-organizations-title" aria-busy={organizationsState.kind === 'loading'}>
      <div className="admin-profiles__header">
        <div>
          <p className="eyebrow">Администрирование</p>
          <h2 id="admin-organizations-title">Организации и команды</h2>
        </div>
        {organizationsState.kind === 'ready' && <p className="admin-profiles__total">Всего: {organizationsState.page.total}</p>}
      </div>
      <p className="admin-profiles__intro">
        Служебный список без контактов и взаимодействий. Здесь добавляют организации (вуз, колледж, школа), правят название и реквизиты, подтверждают заявки КАМ, архивируют и переносят организацию в другую команду; КАМ другой команды снимается с организации.
      </p>

      <form className="organizations-filters" role="search" aria-label="Отбор организаций" onSubmit={(event) => event.preventDefault()}>
        <label>
          Поиск по названию
          <input type="search" value={searchText} maxLength={200} onChange={(event) => setSearchText(event.target.value)} />
        </label>
        <label>
          Состояние
          <select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as ListStatus)
              setPageIndex(0)
            }}
          >
            {listStatuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
      </form>
      {!creating && (
        <button
          type="button"
          className="organizations-create"
          onClick={() => {
            setCreatedMessage(null)
            setCreating(true)
          }}
        >
          Добавить организацию
        </button>
      )}
      {createdMessage !== null && <p className="notice" role="status">{createdMessage}</p>}
      {creating && (
        <OrganizationForm
          title="Новая организация"
          submitLabel="Создать организацию"
          hint="Организация появится в выбранной команде со статусом «Требует назначения»; КАМ назначает руководитель команды."
          teams={activeTeams}
          linkDuplicates={false}
          onSubmit={async (payload, idempotencyKey) => {
            const created = await apiClient.createAdminOrganization(payload, idempotencyKey)
            setCreating(false)
            setCreatedMessage(`«${created.name}» создана в команде «${created.teamName}».`)
            await loadOrganizations(pageIndex)
          }}
          onCancel={() => setCreating(false)}
          onSessionError={handleError}
        />
      )}

      {organizationsState.kind === 'loading' && <p className="organizations-message" role="status">Загружаем организации…</p>}
      {organizationsState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить организации. Повторите попытку.</p>
          {organizationsState.requestId && <p className="request-id">Request ID: {organizationsState.requestId}</p>}
          <button type="button" onClick={() => void loadOrganizations(pageIndex)}>Повторить</button>
        </div>
      )}
      {organizationsState.kind === 'ready' && organizationsState.page.items.length === 0 && (
        <p className="organizations-message">{search.length > 0 || status !== 'CURRENT' ? 'По выбранным условиям организаций нет.' : 'Организаций пока нет.'}</p>
      )}
      {organizationsState.kind === 'ready' && organizationsState.page.items.length > 0 && (
        <ul className="admin-profiles__list" aria-label="Организации и их команды">
          {organizationsState.page.items.map((organization) => {
            const isSelected = transfer?.organization.id === organization.id
            const selectedTeamId = isSelected ? transfer.teamId : ''
            return (
              <li key={organization.id} className="admin-profiles__item">
                <div className="admin-profiles__identity">
                  <h3>{organization.name} <OrganizationStatusBadge status={organization.status} /></h3>
                  <dl className="admin-profiles__fields">
                    <div>
                      <dt>Тип</dt>
                      <dd>{organizationTypeLabel(organization.type)}</dd>
                    </div>
                    <div>
                      <dt>Команда</dt>
                      <dd>{organization.teamName}</dd>
                    </div>
                    <div>
                      <dt>Ответственный КАМ</dt>
                      <dd>{organization.ownerManagerName ?? 'Требует назначения'}</dd>
                    </div>
                  </dl>
                  <OrganizationCatalogPanel
                    organization={organization}
                    canEdit
                    canApprove
                    canArchive
                    canRestore
                    linkDuplicates={false}
                    update={(payload, idempotencyKey) => apiClient.updateAdminOrganization(organization.id, payload, idempotencyKey)}
                    changeStatus={(payload, idempotencyKey) => (
                      apiClient.changeAdminOrganizationStatus(organization.id, payload, idempotencyKey)
                    )}
                    onChanged={replaceOrganization}
                    onSessionError={handleError}
                  />
                  <div className="admin-team-form">
                    <label>
                      Перенести в команду
                      <select
                        value={selectedTeamId}
                        disabled={transfer?.saving === true}
                        onChange={(event) => setTransfer(event.target.value === ''
                          ? null
                          : {
                              organization,
                              teamId: event.target.value,
                              confirming: false,
                              saving: false,
                              idempotencyKey: null
                            })}
                      >
                        <option value="">Выберите команду</option>
                        {activeTeams.filter((team) => team.id !== organization.teamId).map((team) => (
                          <option key={team.id} value={team.id}>{team.name}</option>
                        ))}
                      </select>
                    </label>
                    <button
                      type="button"
                      disabled={!isSelected || transfer.confirming || transfer.saving}
                      onClick={() => isSelected && setTransfer({ ...transfer, confirming: true })}
                    >
                      Перенести
                    </button>
                  </div>
                  {isSelected && transfer.confirming && (
                    <div
                      className="admin-profiles__confirmation"
                      role="alertdialog"
                      aria-labelledby={`transfer-confirmation-${organization.id}`}
                    >
                      <h3 id={`transfer-confirmation-${organization.id}`}>Подтвердите перенос организации</h3>
                      <p>
                        «{organization.name}» перейдёт из команды «{organization.teamName}» в команду «{targetTeamName(transfer.teamId)}».
                        {organization.ownerManagerName !== null
                          ? ` КАМ «${organization.ownerManagerName}» будет снят, организация получит статус «Требует назначения».`
                          : ''}
                        {' '}Руководитель прежней команды потеряет доступ к карточке со следующего запроса.
                      </p>
                      {transfer.error !== undefined && (
                        <div className="interaction-command-error" role="alert">
                          <p>{commandErrorMessage(transfer.error)}</p>
                          {transfer.error instanceof ApiError && <p className="request-id">Request ID: {transfer.error.requestId}</p>}
                          {isVersionConflict(transfer.error) && (
                            <button type="button" onClick={() => void reloadAfterConflict()}>Обновить список</button>
                          )}
                        </div>
                      )}
                      <div className="admin-profiles__confirmation-actions">
                        {!isVersionConflict(transfer.error) && (
                          <button type="button" disabled={transfer.saving} onClick={() => void confirmTransfer()}>
                            {transfer.saving ? 'Переносим…' : 'Подтвердить перенос'}
                          </button>
                        )}
                        <button
                          type="button"
                          className="admin-profiles__cancel"
                          disabled={transfer.saving}
                          onClick={() => setTransfer(null)}
                        >
                          Отмена
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}
      {organizationsState.kind === 'ready' && organizationsState.page.total > organizationsState.page.size && (
        <nav className="admin-profiles__pagination" aria-label="Страницы организаций">
          <button
            type="button"
            disabled={pageIndex === 0 || transfer?.saving === true}
            onClick={() => setPageIndex((current) => Math.max(0, current - 1))}
          >
            Предыдущая
          </button>
          <p>Страница {organizationsState.page.page + 1}</p>
          <button
            type="button"
            disabled={
              (organizationsState.page.page + 1) * organizationsState.page.size >= organizationsState.page.total
              || transfer?.saving === true
            }
            onClick={() => setPageIndex((current) => current + 1)}
          >
            Следующая
          </button>
        </nav>
      )}
    </section>
  )
}
