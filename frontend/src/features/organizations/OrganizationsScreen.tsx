import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, apiClient, type Me, type Organization, type PageOrganization } from '../../shared/api/client'
import { Pagination } from '../../shared/ui/Pagination'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { InteractionsPanel } from '../interactions/InteractionsPanel'
import { OrganizationAssignmentPanel } from './OrganizationAssignmentPanel'

type OrganizationsScreenProps = {
  profileId: string
  role: Me['role']
  selectedOrganizationId?: string
  selectedInteractionId?: string
  query: string
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListFilters = {
  q: string
  requiresAssignment: boolean
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
  timeStyle: 'short'
})

const organizationTypeLabel = (type: Organization['type']) => (
  type === 'UNIVERSITY' ? 'Университет' : 'Школа'
)

const formatUpdatedAt = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTimeFormatter.format(date)
}

const filtersFromQuery = (query: string, role: Me['role']): ListFilters => {
  const params = new URLSearchParams(query)
  const page = Number(params.get('page') ?? '0')
  return {
    q: params.get('q') ?? '',
    requiresAssignment: role === 'LEADER' && params.get('requiresAssignment') === 'true',
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
    : `Требует назначения: ${organization.ownerManagerName} больше не активный менеджер команды`
}

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
        requiresAssignment: current.requiresAssignment
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

  const filtered = filters.q.trim().length > 0 || filters.requiresAssignment

  const resetFilters = () => {
    setSearchText('')
    setFilters({ q: '', requiresAssignment: false, page: 0 })
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
                {role === 'LEADER' ? 'У команды пока нет вузов.' : 'Вам пока не назначены вузы. Назначение выполняет руководитель команды.'}
              </p>
            )
          )}

          {listState.kind === 'ready' && listState.page.items.length > 0 && (
            <ul className="organizations-list" aria-label="Список организаций" aria-busy={listState.refreshing}>
              {listState.page.items.map((organization) => {
                const isSelected = organization.id === selectedOrganizationId
                return (
                  <li key={organization.id}>
                    <a
                      className={`organization-list-item${isSelected ? ' organization-list-item--selected' : ''}`}
                      href={`#/organizations/${organization.id}${listSuffix}`}
                      aria-current={isSelected ? 'true' : undefined}
                    >
                      <span className="organization-list-item__name">{organization.name}</span>
                      <span className="organization-list-item__meta">
                        <span className="organization-list-item__type">{organizationTypeLabel(organization.type)}</span>
                        {role === 'LEADER' && (
                          organization.requiresAssignment
                            ? <span className="status status--missing">Требует назначения</span>
                            : <span className="organization-list-item__owner">{organization.ownerManagerName}</span>
                        )}
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
                <p>Не удалось открыть карточку вуза. Возможно, вуз передан другому менеджеру; обновите список или повторите попытку.</p>
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
                  <dt>Ответственный менеджер</dt>
                  <dd className={detailState.organization.requiresAssignment ? 'organization-detail__attention' : undefined}>
                    {ownerLabel(detailState.organization)}
                  </dd>
                </div>
                <div>
                  <dt>Обновлено</dt>
                  <dd>{formatUpdatedAt(detailState.organization.updatedAt)}</dd>
                </div>
              </dl>
              {role === 'LEADER' && (
                <OrganizationAssignmentPanel
                  key={detailState.organization.id}
                  organization={detailState.organization}
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
