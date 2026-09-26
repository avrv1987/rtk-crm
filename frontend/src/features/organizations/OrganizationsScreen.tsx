import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, apiClient, type Me, type Organization, type PageOrganization } from '../../shared/api/client'
import { Pagination } from '../../shared/ui/Pagination'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { AgreementsPanel } from '../agreements/AgreementsPanel'
import { InteractionsPanel } from '../interactions/InteractionsPanel'
import { OrganizationAssignmentHistory } from './OrganizationAssignmentHistory'
import { OrganizationAssignmentPanel } from './OrganizationAssignmentPanel'
import { OrganizationCatalogPanel, OrganizationStatusBadge } from './OrganizationCatalogPanel'
import { OrganizationForm, organizationTypeLabels } from './OrganizationForm'
import { OrganizationBulkTransfer } from './OrganizationBulkTransfer'
import { formatDate } from '../work/workShared'

type OrganizationsScreenProps = {
  profileId: string
  role: Me['role']
  selectedOrganizationId?: string
  selectedInteractionId?: string
  query: string
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListStatus = 'CURRENT' | 'PENDING' | 'ARCHIVED'

type ListFilters = {
  q: string
  requiresAssignment: boolean
  status: ListStatus
  page: number
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageOrganization; refreshing: boolean }
  | { kind: 'failed'; requestId?: string }

type DetailState =
  | { kind: 'idle' }
  | { kind: 'loading'; id: Organization['id'] }
  | { kind: 'ready'; organization: Organization }
  | { kind: 'failed'; id: Organization['id']; requestId?: string }

const pageSize = 25
const searchDelayMilliseconds = 300

const dateTimeFormatter = new Intl.DateTimeFormat('ru-RU', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Europe/Moscow'
})

const organizationTypeLabel = (type: Organization['type']) => organizationTypeLabels[type]

const listStatuses: Array<{ value: ListStatus; label: string }> = [
  { value: 'CURRENT', label: 'Действующие' },
  { value: 'PENDING', label: 'Ожидают подтверждения' },
  { value: 'ARCHIVED', label: 'Архив' }
]

const formatUpdatedAt = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTimeFormatter.format(date)
}

const filtersFromQuery = (query: string, role: Me['role']): ListFilters => {
  const params = new URLSearchParams(query)
  const page = Number(params.get('page') ?? '0')
  const status = listStatuses.find((item) => item.value === params.get('status'))?.value ?? 'CURRENT'
  return {
    q: params.get('q') ?? '',
    requiresAssignment: role === 'LEADER' && params.get('requiresAssignment') === 'true',
    status,
    page: Number.isInteger(page) && page > 0 ? page : 0
  }
}

const queryFromFilters = (filters: ListFilters) => {
  const params = new URLSearchParams()
  if (filters.q.trim().length > 0) {
    params.set('q', filters.q.trim())
  }
  if (filters.requiresAssignment) {
    params.set('requiresAssignment', 'true')
  }
  if (filters.status !== 'CURRENT') {
    params.set('status', filters.status)
  }
  if (filters.page > 0) {
    params.set('page', filters.page.toString())
  }
  return params.toString()
}

const requestIdOf = (error: unknown) => (
  error instanceof ApiError ? error.requestId : undefined
)

const ownerLabel = (organization: Organization) => {
  if (!organization.requiresAssignment) {
    return organization.ownerManagerName ?? 'Назначен'
  }
  return organization.ownerManagerName === null
    ? 'Требует назначения: ответственный не назначен'
    : `Требует назначения: ${organization.ownerManagerName} больше не активный КАМ команды`
}

const deputyLabel = (organization: Organization) => (
  organization.deputyManagerName === null
    ? null
    : `${organization.deputyManagerName}${organization.deputyEndsOn === null ? '' : ` до ${formatDate(organization.deputyEndsOn)}`}`
)

export const OrganizationsScreen = ({
  profileId,
  role,
  selectedOrganizationId,
  selectedInteractionId,
  query,
  onSessionExpired,
  onProfileUnavailable
}: OrganizationsScreenProps) => {
  const [filters, setFilters] = useState<ListFilters>(() => filtersFromQuery(query, role))
  const [searchText, setSearchText] = useState(filters.q)
  const [listState, setListState] = useState<ListState>({ kind: 'loading' })
  const [detailState, setDetailState] = useState<DetailState>({ kind: 'idle' })
  const [creating, setCreating] = useState(false)
  const [createdMessage, setCreatedMessage] = useState<string | null>(null)
  const [selected, setSelected] = useState<Record<string, Organization>>({})
  const listRequestVersion = useRef(0)
  const detailRequestVersion = useRef(0)
  const detailHeading = useRef<HTMLHeadingElement>(null)

  const handleAccessError = useCallback((error: unknown) => {
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

  const loadOrganizations = useCallback(async (current: ListFilters) => {
    const requestVersion = ++listRequestVersion.current
    setListState((state) => state.kind === 'ready' ? { ...state, refreshing: true } : { kind: 'loading' })
    try {
      const page = await apiClient.listOrganizations({
        page: current.page,
        size: pageSize,
        sort: 'name,asc',
        q: current.q,
        requiresAssignment: current.requiresAssignment,
        status: current.status
      })
      if (requestVersion !== listRequestVersion.current) {
        return
      }
      if (page.items.length === 0 && page.total > 0 && current.page > 0) {
        setFilters((state) => ({ ...state, page: 0 }))
        return
      }
      setListState({ kind: 'ready', page, refreshing: false })
    } catch (error) {
      if (requestVersion !== listRequestVersion.current || handleAccessError(error)) {
        return
      }
      setListState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [handleAccessError])

  const loadOrganization = useCallback(async (id: Organization['id']) => {
    const requestVersion = ++detailRequestVersion.current
    setDetailState({ kind: 'loading', id })
    try {
      const organization = await apiClient.getOrganization(id)
      if (requestVersion === detailRequestVersion.current) {
        setDetailState({ kind: 'ready', organization })
      }
    } catch (error) {
      if (requestVersion !== detailRequestVersion.current || handleAccessError(error)) {
        return
      }
      setDetailState({ kind: 'failed', id, requestId: requestIdOf(error) })
    }
  }, [handleAccessError])

  const replaceOrganization = useCallback((organization: Organization) => {
    setDetailState((current) => (
      current.kind === 'ready' && current.organization.id === organization.id
        ? { kind: 'ready', organization }
        : current
    ))
    setListState((current) => (
      current.kind === 'ready'
        ? {
          ...current,
          page: {
            ...current.page,
            items: current.page.items.map((item) => item.id === organization.id ? organization : item)
          }
        }
        : current
    ))
  }, [])

  const refreshOrganization = useCallback(async (id: Organization['id']) => {
    try {
      replaceOrganization(await apiClient.getOrganization(id))
    } catch (error) {
      if (!handleAccessError(error)) {
        void loadOrganization(id)
      }
    }
  }, [handleAccessError, loadOrganization, replaceOrganization])

  useEffect(() => {
    void loadOrganizations(filters)
  }, [filters, loadOrganizations])

  const listQuery = queryFromFilters(filters)
  const listSuffix = listQuery.length > 0 ? `?${listQuery}` : ''

  useEffect(() => {
    const path = window.location.hash.split('?')[0]
    window.history.replaceState(null, '', `${path}${listSuffix}`)
  }, [listSuffix, query])

  useEffect(() => {
    if (selectedOrganizationId === undefined) {
      detailRequestVersion.current += 1
      setDetailState({ kind: 'idle' })
      return
    }
    void loadOrganization(selectedOrganizationId)
  }, [loadOrganization, selectedOrganizationId])

  useEffect(() => () => {
    listRequestVersion.current += 1
    detailRequestVersion.current += 1
  }, [])

  useEffect(() => {
    if (searchText.trim() === filters.q.trim()) {
      return
    }
    const timer = window.setTimeout(() => {
      setFilters((current) => ({ ...current, q: searchText, page: 0 }))
    }, searchDelayMilliseconds)
    return () => window.clearTimeout(timer)
  }, [filters.q, searchText])

  const detailOrganizationId = detailState.kind === 'ready' ? detailState.organization.id : undefined

  useEffect(() => {
    if (detailOrganizationId !== undefined && selectedInteractionId === undefined) {
      detailHeading.current?.focus()
    }
  }, [detailOrganizationId, selectedInteractionId])

  const filtered = filters.q.trim().length > 0 || filters.requiresAssignment || filters.status !== 'CURRENT'

  const toggleSelected = (organization: Organization, checked: boolean) => {
    setSelected((current) => {
      const next = { ...current }
      if (checked) {
        next[organization.id] = organization
      } else {
        delete next[organization.id]
      }
      return next
    })
  }

  const pageItems = listState.kind === 'ready' ? listState.page.items : []
  const allOnPageSelected = pageItems.length > 0 && pageItems.every((organization) => selected[organization.id] !== undefined)
  const selectedOrganizations = Object.values(selected)

  const afterTransfer = async () => {
    setSelected({})
    await Promise.all([
      loadOrganizations(filters),
      selectedOrganizationId === undefined ? Promise.resolve() : loadOrganization(selectedOrganizationId)
    ])
  }

  const resetFilters = () => {
    setSearchText('')
    setFilters({ q: '', requiresAssignment: false, status: 'CURRENT', page: 0 })
  }

  const organizationCreated = (organization: Organization) => {
    setCreating(false)
    setCreatedMessage(organization.status === 'PENDING'
      ? `«${organization.name}» создана и отправлена руководителю на подтверждение. Работу можно начинать сразу.`
      : `«${organization.name}» создана.`)
    void loadOrganizations(filters)
    window.location.hash = `#/organizations/${organization.id}${listSuffix}`
  }

  const organizationChanged = (organization: Organization) => {
    setCreatedMessage(null)
    replaceOrganization(organization)
    void loadOrganizations(filters)
  }

  return (
    <section className="organizations-panel" aria-labelledby="organizations-title">
      <div className={`organizations-layout${selectedOrganizationId === undefined ? '' : ' organizations-layout--has-selection'}`}>
        <div className="organizations-column">
          <div className="organizations-header">
            <h2 id="organizations-title">Вузы</h2>
            <p className="organizations-total" role="status">
              {listState.kind === 'ready' && (listState.refreshing ? 'Обновляем…' : `Найдено: ${listState.page.total}`)}
            </p>
          </div>

          {role !== 'MANAGEMENT' && !creating && (
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
          {role !== 'MANAGEMENT' && creating && (
            <OrganizationForm
              title="Новая организация"
              submitLabel={role === 'LEADER' ? 'Создать организацию' : 'Создать и отправить на подтверждение'}
              hint={role === 'LEADER'
                ? 'Организация появится в команде без ответственного; назначьте КАМ в карточке.'
                : 'Вы станете ответственным. Руководитель команды подтвердит организацию; работу можно вести сразу.'}
              linkDuplicates
              onSubmit={async (payload, idempotencyKey) => {
                organizationCreated(await apiClient.createOrganization(payload, idempotencyKey))
              }}
              onCancel={() => setCreating(false)}
              onSessionError={handleAccessError}
            />
          )}

          <form className="organizations-filters" role="search" aria-label="Отбор вузов" onSubmit={(event) => event.preventDefault()}>
            <label>
              Поиск по названию
              <input
                type="search"
                value={searchText}
                maxLength={200}
                onChange={(event) => setSearchText(event.target.value)}
              />
            </label>
            {role === 'LEADER' && (
              <label className="checkbox-field">
                <input
                  type="checkbox"
                  checked={filters.requiresAssignment}
                  onChange={(event) => setFilters((current) => ({ ...current, requiresAssignment: event.target.checked, page: 0 }))}
                />
                Только требующие назначения
              </label>
            )}
            <label>
              Состояние
              <select
                value={filters.status}
                onChange={(event) => setFilters((current) => ({ ...current, status: event.target.value as ListStatus, page: 0 }))}
              >
                {listStatuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
              </select>
            </label>
          </form>

          {listState.kind === 'loading' && (
            <p className="organizations-message" role="status">Загружаем доступные вузы…</p>
          )}

          {listState.kind === 'failed' && (
            <div className="notice notice--error" role="alert">
              <p>Не удалось загрузить вузы. Повторите попытку.</p>
              <SupportDetails requestId={listState.requestId} />
              <button type="button" onClick={() => void loadOrganizations(filters)}>Повторить</button>
            </div>
          )}

          {listState.kind === 'ready' && listState.page.items.length === 0 && (
            filtered ? (
              <div className="notice">
                <p>По выбранным условиям вузов нет.</p>
                <button type="button" className="button--secondary" onClick={resetFilters}>Сбросить фильтры</button>
              </div>
            ) : (
              <p className="organizations-message">
                {role === 'LEADER' && 'У команды пока нет вузов.'}
                {role === 'MANAGEMENT' && 'В CRM пока нет вузов.'}
                {role === 'USER' && 'Вам пока не назначены вузы. Назначение выполняет руководитель команды.'}
              </p>
            )
          )}

          {role === 'LEADER' && (
            <OrganizationBulkTransfer
              organizations={selectedOrganizations}
              onClear={() => setSelected({})}
              onTransferred={afterTransfer}
              onSessionExpired={onSessionExpired}
              onProfileUnavailable={onProfileUnavailable}
            />
          )}

          {role === 'LEADER' && pageItems.length > 0 && (
            <label className="checkbox-field organizations-select-all">
              <input
                type="checkbox"
                checked={allOnPageSelected}
                onChange={(event) => pageItems.forEach((organization) => toggleSelected(organization, event.target.checked))}
              />
              Выбрать все вузы на странице для передачи
            </label>
          )}

          {listState.kind === 'ready' && listState.page.items.length > 0 && (
            <ul className="organizations-list" aria-label="Список организаций" aria-busy={listState.refreshing}>
              {listState.page.items.map((organization) => {
                const isSelected = organization.id === selectedOrganizationId
                const deputy = deputyLabel(organization)
                return (
                  <li key={organization.id} className={role === 'LEADER' ? 'organizations-list__selectable' : undefined}>
                    {role === 'LEADER' && (
                      <input
                        type="checkbox"
                        className="organizations-list__select"
                        aria-label={`Выбрать «${organization.name}» для передачи`}
                        checked={selected[organization.id] !== undefined}
                        onChange={(event) => toggleSelected(organization, event.target.checked)}
                      />
                    )}
                    <a
                      className={`organization-list-item${isSelected ? ' organization-list-item--selected' : ''}`}
                      href={`#/organizations/${organization.id}${listSuffix}`}
                      aria-current={isSelected ? 'true' : undefined}
                    >
                      <span className="organization-list-item__name">{organization.name}</span>
                      <span className="organization-list-item__meta">
                        <span className="organization-list-item__type">{organizationTypeLabel(organization.type)}</span>
                        <OrganizationStatusBadge status={organization.status} />
                        {(role === 'LEADER' || role === 'MANAGEMENT') && (
                          organization.requiresAssignment
                            ? <span className="status status--missing">Требует назначения</span>
                            : <span className="organization-list-item__owner">{organization.ownerManagerName}</span>
                        )}
                        {organization.inherited && !organization.requiresAssignment && (
                          <span className="status status--missing" title="Контакты ещё не подтверждены новым ответственным">Унаследован</span>
                        )}
                        {role === 'MANAGEMENT' && organization.teamName !== null && (
                          <span className="organization-list-item__type">{organization.teamName}</span>
                        )}
                        {deputy !== null && <span className="status status--planned">Замещение: {deputy}</span>}
                      </span>
                    </a>
                  </li>
                )
              })}
            </ul>
          )}

          {listState.kind === 'ready' && (
            <Pagination
              label="Страницы списка вузов"
              page={listState.page.page}
              size={pageSize}
              total={listState.page.total}
              disabled={listState.refreshing}
              onChange={(page) => setFilters((current) => ({ ...current, page }))}
            />
          )}
        </div>

        <section className="organization-detail" aria-labelledby="organization-detail-title">
          {selectedOrganizationId !== undefined && (
            <a className="organizations-back" href={`#/organizations${listSuffix}`}>← Все вузы</a>
          )}
          {detailState.kind === 'idle' && (
            <>
              <h3 id="organization-detail-title">Карточка вуза</h3>
              <p className="organizations-message">Выберите вуз в списке, чтобы открыть карточку.</p>
            </>
          )}
          {detailState.kind === 'loading' && (
            <>
              <h3 id="organization-detail-title">Карточка вуза</h3>
              <p className="organizations-message" role="status">Загружаем карточку вуза…</p>
            </>
          )}
          {detailState.kind === 'failed' && (
            <>
              <h3 id="organization-detail-title">Карточка вуза</h3>
              <div className="notice notice--error" role="alert">
                <p>Не удалось открыть карточку вуза. Возможно, организация передана другому КАМ; обновите список или повторите попытку.</p>
                <SupportDetails requestId={detailState.requestId} />
                <button type="button" onClick={() => void loadOrganization(detailState.id)}>Повторить</button>
              </div>
            </>
          )}
          {detailState.kind === 'ready' && (
            <>
              <h3 id="organization-detail-title" ref={detailHeading} tabIndex={-1}>{detailState.organization.name}</h3>
              <dl className="organization-detail__fields">
                <div>
                  <dt>Тип</dt>
                  <dd>{organizationTypeLabel(detailState.organization.type)}</dd>
                </div>
                <div>
                  <dt>Команда</dt>
                  <dd>{detailState.organization.teamName ?? 'Не указана'}</dd>
                </div>
                <div>
                  <dt>Ответственный КАМ</dt>
                  <dd className={detailState.organization.requiresAssignment ? 'organization-detail__attention' : undefined}>
                    {ownerLabel(detailState.organization)}
                  </dd>
                </div>
                {detailState.organization.inherited && !detailState.organization.requiresAssignment && (
                  <div>
                    <dt>После передачи</dt>
                    <dd className="organization-detail__attention">
                      {role === 'USER'
                        ? 'Унаследованный вуз: вы ещё не подтвердили ни одного контакта. Свяжитесь с контактом и отметьте у него «Контакт подтверждён».'
                        : `Унаследованный вуз: ${detailState.organization.ownerManagerName ?? 'новый ответственный'} ещё не подтвердил ни одного контакта после передачи.`}
                    </dd>
                  </div>
                )}
                {deputyLabel(detailState.organization) !== null && (
                  <div>
                    <dt>Заместитель</dt>
                    <dd>{deputyLabel(detailState.organization)}</dd>
                  </div>
                )}
                <div>
                  <dt>Обновлено</dt>
                  <dd>{formatUpdatedAt(detailState.organization.updatedAt)}</dd>
                </div>
              </dl>
              <OrganizationCatalogPanel
                key={`catalog:${detailState.organization.id}`}
                organization={detailState.organization}
                canEdit={role === 'LEADER'}
                canApprove={role === 'LEADER'}
                canArchive={role === 'LEADER' || detailState.organization.ownerManagerId === profileId}
                canRestore={role === 'LEADER'}
                linkDuplicates
                update={(payload, idempotencyKey) => apiClient.updateOrganization(detailState.organization.id, payload, idempotencyKey)}
                changeStatus={(payload, idempotencyKey) => (
                  apiClient.changeOrganizationStatus(detailState.organization.id, payload, idempotencyKey)
                )}
                onChanged={organizationChanged}
                onSessionError={handleAccessError}
              />
              {role === 'USER' && (
                <OrganizationAssignmentHistory
                  key={detailState.organization.id}
                  organizationId={detailState.organization.id}
                  revision={0}
                  onSessionExpired={onSessionExpired}
                  onProfileUnavailable={onProfileUnavailable}
                />
              )}
              {role === 'LEADER' && (
                <OrganizationAssignmentPanel
                  key={detailState.organization.id}
                  organization={detailState.organization}
                  profileId={profileId}
                  onOrganizationChanged={replaceOrganization}
                  onReload={() => loadOrganization(detailState.organization.id)}
                  onSessionExpired={onSessionExpired}
                  onProfileUnavailable={onProfileUnavailable}
                />
              )}
              <InteractionsPanel
                key={`${detailState.organization.id}:${selectedInteractionId ?? ''}`}
                organizationId={detailState.organization.id}
                initialInteractionId={selectedInteractionId}
                profileId={profileId}
                role={role}
                onContactsChanged={() => void refreshOrganization(detailState.organization.id)}
                onSessionExpired={onSessionExpired}
                onProfileUnavailable={onProfileUnavailable}
              />
              <AgreementsPanel
                key={`agreements:${detailState.organization.id}`}
                organizationId={detailState.organization.id}
                role={role}
                onSessionExpired={onSessionExpired}
                onProfileUnavailable={onProfileUnavailable}
              />
            </>
          )}
        </section>
      </div>
    </section>
  )
}
