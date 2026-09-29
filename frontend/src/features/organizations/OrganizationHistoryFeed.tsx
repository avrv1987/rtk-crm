import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  type Organization,
  type OrganizationHistoryItem
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { formatMoscowDateTime } from '../interactions/moscowTime'
import { changeText } from '../interactions/OrganizationContacts'
import { historyGroups, historyTitle, kindsOfGroup, type HistoryGroup } from './organizationHistory'

type OrganizationHistoryFeedProps = {
  organizationId: Organization['id']
  workHref: (interactionId: string) => string
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Filters = {
  group: HistoryGroup
  from: string
  to: string
}

type FeedState = {
  status: 'loading' | 'ready' | 'failed'
  items: OrganizationHistoryItem[]
  total: number
  page: number
  loadingMore: boolean
  moreFailed: boolean
  requestId?: string
}

const pageSize = 25

const initialFilters: Filters = { group: 'ALL', from: '', to: '' }

const initialState: FeedState = { status: 'loading', items: [], total: 0, page: 0, loadingMore: false, moreFailed: false }

export const OrganizationHistoryFeed = ({
  organizationId,
  workHref,
  onSessionExpired,
  onProfileUnavailable
}: OrganizationHistoryFeedProps) => {
  const [filters, setFilters] = useState<Filters>(initialFilters)
  const [state, setState] = useState<FeedState>(initialState)
  const requestVersion = useRef(0)
  const periodInvalid = filters.from !== '' && filters.to !== '' && filters.from > filters.to

  const load = useCallback(async (requested: Filters, page: number) => {
    const version = ++requestVersion.current
    setState((current) => (page === 0 ? { ...initialState } : { ...current, loadingMore: true, moreFailed: false }))
    try {
      const result = await apiClient.listOrganizationHistory(organizationId, {
        kinds: kindsOfGroup(requested.group),
        from: requested.from,
        to: requested.to,
        page,
        size: pageSize
      })
      if (version === requestVersion.current) {
        setState((current) => ({
          status: 'ready',
          items: page === 0 ? result.items : [...current.items, ...result.items],
          total: result.total,
          page,
          loadingMore: false,
          moreFailed: false
        }))
      }
    } catch (error) {
      if (version !== requestVersion.current) {
        return
      }
      if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
        onSessionExpired()
        return
      }
      if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
        onProfileUnavailable(error.requestId)
        return
      }
      setState((current) => ({
        ...current,
        status: page === 0 ? 'failed' : 'ready',
        loadingMore: false,
        moreFailed: page > 0,
        requestId: error instanceof ApiError ? error.requestId : undefined
      }))
    }
  }, [onProfileUnavailable, onSessionExpired, organizationId])

  useEffect(() => {
    if (periodInvalid) {
      return undefined
    }
    void load(filters, 0)
    return () => {
      requestVersion.current += 1
    }
  }, [filters, load, periodInvalid])

  const hasMore = state.items.length < state.total

  return (
    <section className="organization-history" aria-labelledby={`organization-history-${organizationId}`} aria-busy={state.status === 'loading'}>
      <div className="organization-history__header">
        <h4 id={`organization-history-${organizationId}`}>История вуза</h4>
        {state.status === 'ready' && <p className="organization-history__total">Событий: {state.total}</p>}
      </div>
      <form className="organization-history__filters" aria-label="Фильтры истории" onSubmit={(event) => event.preventDefault()}>
        <label>
          Вид события
          <select
            value={filters.group}
            onChange={(event) => setFilters({ ...filters, group: event.target.value as HistoryGroup })}
          >
            {historyGroups.map((group) => <option key={group.id} value={group.id}>{group.label}</option>)}
          </select>
        </label>
        <label>
          С даты
          <input type="date" value={filters.from} max={filters.to || undefined} onChange={(event) => setFilters({ ...filters, from: event.target.value })} />
        </label>
        <label>
          По дату
          <input type="date" value={filters.to} min={filters.from || undefined} onChange={(event) => setFilters({ ...filters, to: event.target.value })} />
        </label>
        <button type="button" className="button--secondary" onClick={() => setFilters(initialFilters)}>Сбросить</button>
      </form>
      {periodInvalid && <p className="organizations-message organizations-message--error" role="alert">Начало периода позже его конца.</p>}
      {state.status === 'loading' && <p className="organizations-message" role="status">Загружаем историю…</p>}
      {state.status === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить историю.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" onClick={() => void load(filters, 0)}>Повторить</button>
        </div>
      )}
      {state.status === 'ready' && state.items.length === 0 && (
        <p className="organizations-message">По этим условиям событий нет.</p>
      )}
      {state.status === 'ready' && state.items.length > 0 && (
        <ol className="organization-history__list" aria-label="События вуза">
          {state.items.map((item) => (
            <li key={item.id} className="organization-history__item">
              <div className="organization-history__meta">
                <strong>{historyTitle(item)}</strong>
                <time dateTime={item.occurredAt}>{formatMoscowDateTime(item.occurredAt)}</time>
              </div>
              {item.interactionId && (
                <p>
                  Работа: <a href={workHref(item.interactionId)}>{item.interactionTitle ?? 'Открыть работу'}</a>
                </p>
              )}
              {item.comment && (
                <p>{item.kind === 'ASSIGNMENT' ? `Комментарий к передаче: ${item.comment}` : item.comment}</p>
              )}
              {item.changes?.map((change) => <p key={change.field}>{changeText(change)}</p>)}
              <p className="organization-history__actor">Выполнил: {item.actorName?.trim() || 'профиль, выполнивший действие'}</p>
            </li>
          ))}
        </ol>
      )}
      {state.status === 'ready' && state.moreFailed && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить следующие события.</p>
          <SupportDetails requestId={state.requestId} />
        </div>
      )}
      {state.status === 'ready' && hasMore && (
        <button
          type="button"
          className="button--secondary"
          disabled={state.loadingMore}
          onClick={() => void load(filters, state.page + 1)}
        >
          {state.loadingMore ? 'Загружаем…' : 'Показать ещё'}
        </button>
      )}
    </section>
  )
}
