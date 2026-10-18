import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  type InteractionListItem,
  type InteractionWorkStatusFilter,
  type Me,
  type Organization,
  type ReportManagerOption
} from '../../shared/api/client'
import { FilterButton, FilterPanel } from '../../shared/ui/FilterPanel'
import { Pagination } from '../../shared/ui/Pagination'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { FilterChips, type ActiveChip } from '../control/FilterFields'
import { InteractionMarkBadges } from '../interactions/InteractionMarkBadges'
import { issuesHref } from '../issues/issueModel'
import {
  activeFilterCount,
  dueOptions,
  emptyWorkFilters,
  filtersFromQuery,
  hasFilters,
  isFlag,
  licenseYears,
  positiveInteger,
  queryFromFilters,
  stageDayOptions,
  statusOptions,
  unassigned,
  workFlagLabels,
  workFlags,
  type WorkFilters
} from './workFilters'
import { daysLabel, daysSince, eventTypeLabel, formatDate, formatDateTime } from './workShared'
import './workControl.css'

type WorkListProps = {
  role: Me['role']
  path: 'work' | 'control'
  initialQuery: string
  refreshSignal: number
  title: string
  onTotal?: (total: number) => void
  onFilters?: (filters: WorkFilters) => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; items: InteractionListItem[]; total: number; page: number; refreshing: boolean }
  | { kind: 'failed'; error: unknown }

type OptionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; stages: string[]; organizations: Organization[]; managers: ReportManagerOption[] }
  | { kind: 'failed'; error: unknown }

const pageSize = 25
const searchDelayMilliseconds = 300

const dueStatus = (item: InteractionListItem, now: number) => {
  const action = item.nextAction?.trim() ?? ''
  if (action.length === 0 && item.nextActionAt === null) {
    return { tone: 'missing', label: 'Шаг и срок не заданы' }
  }
  if (item.nextActionAt === null) {
    return { tone: 'missing', label: 'Срок не задан' }
  }
  const dueAt = new Date(item.nextActionAt).getTime()
  const dateLabel = formatDateTime(item.nextActionAt)
  if (action.length === 0) {
    return dueAt < now
      ? { tone: 'overdue', label: `Просрочен с ${dateLabel}, шаг не задан` }
      : { tone: 'missing', label: `Шаг не задан, срок ${dateLabel}` }
  }
  if (dueAt < now) {
    return { tone: 'overdue', label: `Просрочен с ${dateLabel}` }
  }
  return { tone: 'planned', label: dateLabel }
}

const ownerLabel = (item: InteractionListItem) => {
  const owner = item.ownerManagerName ?? 'Требует назначения'
  if (item.deputyManagerName === null) {
    return owner
  }
  const until = item.deputyEndsOn === null ? '' : ` до ${formatDate(item.deputyEndsOn)}`
  return `${owner} (заместитель ${item.deputyManagerName}${until})`
}

const loadAllPages = async <T,>(loadPage: (page: number) => Promise<{ items: T[]; total: number }>) => {
  const items: T[] = []
  for (let page = 0; ; page += 1) {
    const result = await loadPage(page)
    items.push(...result.items)
    if (result.items.length === 0 || items.length >= result.total) {
      return items
    }
  }
}

export const WorkList = ({ role, path, initialQuery, refreshSignal, title, onTotal, onFilters, onSessionExpired, onProfileUnavailable }: WorkListProps) => {
  const [filters, setFilters] = useState<WorkFilters>(() => filtersFromQuery(initialQuery))
  const [searchText, setSearchText] = useState(filters.q)
  const [listState, setListState] = useState<ListState>({ kind: 'loading' })
  const [optionsState, setOptionsState] = useState<OptionsState>({ kind: 'loading' })
  const [panelOpen, setPanelOpen] = useState(false)
  const listRequestVersion = useRef(0)
  const resultsHeading = useRef<HTMLHeadingElement>(null)
  const focusResults = useRef(false)
  const currentFilters = useRef(filters)
  const totalRef = useRef(onTotal)
  const filtersRef = useRef(onFilters)
  const seesTeamWork = role === 'LEADER' || role === 'MANAGEMENT'
  const prefix = path === 'control' ? 'view=list' : ''

  currentFilters.current = filters
  totalRef.current = onTotal
  filtersRef.current = onFilters

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

  const loadList = useCallback(async (current: WorkFilters) => {
    const requestVersion = ++listRequestVersion.current
    setListState((state) => state.kind === 'ready' ? { ...state, refreshing: true } : { kind: 'loading' })
    try {
      const page = await apiClient.listInteractions({
        organizationId: current.organizationId || undefined,
        q: current.q,
        due: current.due || undefined,
        stage: current.stage,
        flag: current.flag || undefined,
        licenseExpiresBy: current.licenseExpiresBy === '' ? undefined : Number(current.licenseExpiresBy),
        responsible: current.responsible || undefined,
        status: current.status === 'ACTIVE' ? undefined : current.status,
        minDaysOnStage: current.minDaysOnStage ?? undefined,
        noContactDays: current.noContactDays ?? undefined,
        page: current.page,
        size: pageSize,
        sort: 'nextActionAt,asc'
      })
      if (requestVersion !== listRequestVersion.current) {
        return
      }
      if (page.items.length === 0 && page.total > 0 && current.page > 0) {
        setFilters((state) => ({ ...state, page: 0 }))
        return
      }
      setListState({ kind: 'ready', items: page.items, total: page.total, page: page.page, refreshing: false })
      if (!hasFilters(current)) {
        totalRef.current?.(page.total)
      }
    } catch (error) {
      if (requestVersion !== listRequestVersion.current || handleAccessError(error)) {
        return
      }
      setListState({ kind: 'failed', error })
    }
  }, [handleAccessError])

  const loadOptions = useCallback(async () => {
    setOptionsState({ kind: 'loading' })
    try {
      const [stages, organizations, managers] = await Promise.all([
        apiClient.listReportStages(),
        loadAllPages((page) => apiClient.listOrganizations({ page, size: 100, sort: 'name,asc' })),
        seesTeamWork ? apiClient.listReportManagers() : Promise.resolve([])
      ])
      setOptionsState({ kind: 'ready', stages, organizations, managers })
    } catch (error) {
      if (!handleAccessError(error)) {
        setOptionsState({ kind: 'failed', error })
      }
    }
  }, [handleAccessError, seesTeamWork])

  useEffect(() => {
    void loadOptions()
  }, [loadOptions])

  useEffect(() => {
    const query = [prefix, queryFromFilters(filters)].filter((part) => part !== '').join('&')
    // replaceState не порождает hashchange: адрес отражает отбор, но экран не перерисовывается по кругу.
    window.history.replaceState(null, '', query.length > 0 ? `#/${path}?${query}` : `#/${path}`)
    filtersRef.current?.(filters)
    void loadList(filters)
  }, [filters, loadList, path, prefix, refreshSignal])

  useEffect(() => () => {
    listRequestVersion.current += 1
  }, [])

  useEffect(() => {
    const applyLinkedFilters = () => {
      const [hashPath, query = ''] = window.location.hash.replace(/^#\/?/, '').split('?')
      if (hashPath !== path) {
        return
      }
      const linked = filtersFromQuery(query)
      if (queryFromFilters(linked) === queryFromFilters(currentFilters.current)) {
        return
      }
      focusResults.current = true
      setSearchText(linked.q)
      setFilters(linked)
    }
    window.addEventListener('hashchange', applyLinkedFilters)
    return () => window.removeEventListener('hashchange', applyLinkedFilters)
  }, [path])

  useEffect(() => {
    if (searchText.trim() === filters.q.trim()) {
      return
    }
    const timer = window.setTimeout(() => {
      setFilters((current) => ({ ...current, q: searchText, page: 0 }))
    }, searchDelayMilliseconds)
    return () => window.clearTimeout(timer)
  }, [filters.q, searchText])

  useEffect(() => {
    if (listState.kind === 'ready' && !listState.refreshing && focusResults.current) {
      focusResults.current = false
      resultsHeading.current?.scrollIntoView({ block: 'start' })
      resultsHeading.current?.focus()
    }
  }, [listState])

  const changeFilter = (change: Partial<WorkFilters>) => {
    setFilters((current) => ({ ...current, ...change, page: 0 }))
  }

  const changePage = (page: number) => {
    focusResults.current = true
    setFilters((current) => ({ ...current, page }))
  }

  const resetFilters = () => {
    setSearchText('')
    setFilters(emptyWorkFilters)
  }

  const now = Date.now()
  const organizationOptions = optionsState.kind === 'ready' ? optionsState.organizations : []
  const stageOptions = optionsState.kind === 'ready' ? optionsState.stages : []
  const managerOptions = optionsState.kind === 'ready' ? optionsState.managers : []
  const stageDays = filters.minDaysOnStage === null || stageDayOptions.includes(filters.minDaysOnStage)
    ? stageDayOptions
    : [...stageDayOptions, filters.minDaysOnStage].sort((a, b) => a - b)
  const filtered = hasFilters(filters)
  const managerName = (id: string) => (id === unassigned ? 'Требует назначения' : managerOptions.find((manager) => manager.id === id)?.displayName ?? 'выбранный ранее КАМ')
  const chips: ActiveChip[] = [
    filters.due !== '' && { key: 'due', label: `Срок: ${dueOptions.find((option) => option.value === filters.due)?.label ?? ''}`, onRemove: () => changeFilter({ due: '' }) },
    filters.stage !== '' && { key: 'stage', label: `Этап: ${filters.stage}`, onRemove: () => changeFilter({ stage: '' }) },
    filters.organizationId !== '' && {
      key: 'organization',
      label: `Вуз: ${organizationOptions.find((item) => item.id === filters.organizationId)?.name ?? 'выбранный ранее вуз'}`,
      onRemove: () => changeFilter({ organizationId: '' })
    },
    filters.flag !== '' && { key: 'flag', label: `Отметка: ${workFlagLabels[filters.flag]}`, onRemove: () => changeFilter({ flag: '' }) },
    filters.licenseExpiresBy !== '' && { key: 'license', label: `Лицензия до ${filters.licenseExpiresBy} года`, onRemove: () => changeFilter({ licenseExpiresBy: '' }) },
    filters.responsible !== '' && { key: 'responsible', label: `КАМ: ${managerName(filters.responsible)}`, onRemove: () => changeFilter({ responsible: '' }) },
    filters.status !== 'ACTIVE' && {
      key: 'status',
      label: statusOptions.find((option) => option.value === filters.status)?.label ?? '',
      onRemove: () => changeFilter({ status: 'ACTIVE' })
    },
    filters.minDaysOnStage !== null && {
      key: 'days',
      label: `На этапе дольше ${daysLabel(filters.minDaysOnStage)}`,
      onRemove: () => changeFilter({ minDaysOnStage: null })
    },
    filters.noContactDays !== null && {
      key: 'noContact',
      label: `Нет контакта больше ${daysLabel(filters.noContactDays)}`,
      onRemove: () => changeFilter({ noContactDays: null })
    }
  ].filter((chip): chip is ActiveChip => chip !== false)

  return (
    <section className="work-list-view" aria-labelledby="work-results-title">
      <div className="work__results-header">
        <h2 id="work-results-title" ref={resultsHeading} tabIndex={-1}>{title}</h2>
        <p className="work__total" role="status">
          {listState.kind === 'ready' && (listState.refreshing ? 'Обновляем…' : `Найдено: ${listState.total}`)}
          {listState.kind === 'loading' && 'Загружаем взаимодействия…'}
        </p>
      </div>

      <form className="work-filters" role="search" aria-label="Поиск и фильтры взаимодействий" onSubmit={(event) => event.preventDefault()}>
        <label className="work-filters__search">
          Поиск по названию взаимодействия или вуза
          <input type="search" value={searchText} maxLength={200} onChange={(event) => setSearchText(event.target.value)} />
        </label>
        <FilterButton count={activeFilterCount(filters)} onClick={() => setPanelOpen(true)} />
      </form>
      <FilterChips chips={chips} onReset={resetFilters} />

      <FilterPanel open={panelOpen} onClose={() => setPanelOpen(false)} onReset={resetFilters}>
        <fieldset className="work-filters__due">
          <legend>Срок следующего шага</legend>
          {dueOptions.map((option) => (
            <label key={option.value || 'all'} className="checkbox-field">
              <input
                type="radio"
                name={`work-due-${path}`}
                value={option.value}
                checked={filters.due === option.value}
                onChange={() => changeFilter({ due: option.value })}
              />
              {option.label}
            </label>
          ))}
        </fieldset>
        <label>
          Этап
          <select
            value={filters.stage}
            disabled={optionsState.kind !== 'ready' && filters.stage.length === 0}
            onChange={(event) => changeFilter({ stage: event.target.value })}
          >
            <option value="">Все этапы</option>
            {filters.stage.length > 0 && !stageOptions.includes(filters.stage) && <option value={filters.stage}>{filters.stage}</option>}
            {stageOptions.map((stage) => <option key={stage} value={stage}>{stage}</option>)}
          </select>
        </label>
        <label>
          Вуз
          <select
            value={filters.organizationId}
            disabled={optionsState.kind !== 'ready' && filters.organizationId.length === 0}
            onChange={(event) => changeFilter({ organizationId: event.target.value })}
          >
            <option value="">Все вузы</option>
            {filters.organizationId.length > 0 && !organizationOptions.some((item) => item.id === filters.organizationId) && (
              <option value={filters.organizationId}>Выбранный ранее вуз</option>
            )}
            {organizationOptions.map((organization) => <option key={organization.id} value={organization.id}>{organization.name}</option>)}
          </select>
        </label>
        <label>
          Отметка
          <select value={filters.flag} onChange={(event) => changeFilter({ flag: isFlag(event.target.value) ? event.target.value : '' })}>
            <option value="">Все взаимодействия</option>
            {workFlags.map((flag) => <option key={flag} value={flag}>{workFlagLabels[flag]}</option>)}
          </select>
        </label>
        <label>
          Лицензия истекает
          <select value={filters.licenseExpiresBy} onChange={(event) => changeFilter({ licenseExpiresBy: event.target.value })}>
            <option value="">Любой срок</option>
            {filters.licenseExpiresBy !== '' && !licenseYears.includes(filters.licenseExpiresBy) && (
              <option value={filters.licenseExpiresBy}>до {filters.licenseExpiresBy} года включительно</option>
            )}
            {licenseYears.map((year) => <option key={year} value={year}>до {year} года включительно</option>)}
          </select>
        </label>
        {seesTeamWork && (
          <label>
            КАМ
            <select
              value={filters.responsible}
              disabled={optionsState.kind !== 'ready' && filters.responsible.length === 0}
              onChange={(event) => changeFilter({ responsible: event.target.value })}
            >
              <option value="">Все КАМ</option>
              <option value={unassigned}>Требует назначения</option>
              {filters.responsible.length > 0 && filters.responsible !== unassigned && !managerOptions.some((manager) => manager.id === filters.responsible) && (
                <option value={filters.responsible}>Выбранный ранее КАМ</option>
              )}
              {managerOptions.map((manager) => (
                <option key={manager.id} value={manager.id}>{manager.active ? manager.displayName : `${manager.displayName} (неактивен)`}</option>
              ))}
            </select>
          </label>
        )}
        <label>
          Статус взаимодействия
          <select value={filters.status} onChange={(event) => changeFilter({ status: event.target.value as InteractionWorkStatusFilter })}>
            {statusOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </label>
        <label>
          На текущем этапе
          <select value={filters.minDaysOnStage ?? ''} onChange={(event) => changeFilter({ minDaysOnStage: positiveInteger(event.target.value) })}>
            <option value="">Любой срок</option>
            {stageDays.map((days) => <option key={days} value={days}>дольше {daysLabel(days)}</option>)}
          </select>
        </label>
        <label>
          Нет контакта с вузом
          <select value={filters.noContactDays ?? ''} onChange={(event) => changeFilter({ noContactDays: positiveInteger(event.target.value) })}>
            <option value="">Не важно</option>
            {(filters.noContactDays === null || stageDayOptions.includes(filters.noContactDays)
              ? stageDayOptions
              : [...stageDayOptions, filters.noContactDays].sort((a, b) => a - b)).map((days) => (
              <option key={days} value={days}>больше {daysLabel(days)}</option>
            ))}
          </select>
        </label>
        {optionsState.kind === 'failed' && (
          <div className="notice notice--error" role="alert">
            <p>Не удалось загрузить списки этапов, вузов и КАМ. Поиск и остальные фильтры работают.</p>
            <SupportDetails requestId={optionsState.error instanceof ApiError ? optionsState.error.requestId : undefined} />
            <button type="button" className="button--secondary" onClick={() => void loadOptions()}>Повторить</button>
          </div>
        )}
      </FilterPanel>

      {listState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось загрузить взаимодействия. Повторите попытку.</p>
          <SupportDetails requestId={listState.error instanceof ApiError ? listState.error.requestId : undefined} />
          <button type="button" onClick={() => void loadList(filters)}>Повторить</button>
        </div>
      )}

      {listState.kind === 'ready' && listState.items.length === 0 && (
        filtered ? (
          <div className="empty-state">
            <p>По выбранным условиям взаимодействий нет.</p>
            <button type="button" className="button--secondary" onClick={resetFilters}>Сбросить фильтры</button>
          </div>
        ) : (
          <div className="empty-state">
            <p>Активных взаимодействий нет. Завершённые показывает фильтр «Статус взаимодействия».</p>
            <a className="button-link" href="#/organizations">Перейти к вузам</a>
          </div>
        )
      )}

      {listState.kind === 'ready' && listState.items.length > 0 && (
        <table className="work-table" aria-labelledby="work-results-title" aria-busy={listState.refreshing}>
          <thead>
            <tr>
              <th scope="col">Взаимодействие и вуз</th>
              <th scope="col">Этап</th>
              <th scope="col">Следующий шаг</th>
              <th scope="col">Срок</th>
              {seesTeamWork && <th scope="col">КАМ</th>}
            </tr>
          </thead>
          <tbody>
            {listState.items.map((item) => {
              const status = dueStatus(item, now)
              return (
                <tr key={item.id} className="work-row">
                  <td data-label="Взаимодействие и вуз">
                    <a className="work-item__title" href={`#/organizations/${item.organizationId}/${item.id}`}>{item.title}</a>
                    <a className="work-item__organization" href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a>
                    <InteractionMarkBadges marks={item.marks} issuesHref={issuesHref(item.organizationId, item.id)} />
                    <span className="work-item__meta">
                      {`Последнее событие: ${item.lastEventAt === null ? 'событий нет' : `${formatDateTime(item.lastEventAt)}, ${eventTypeLabel(item.lastEventType)}`}`}
                      {` · Программа: ${item.programName ?? 'не указана'}`}
                      {!seesTeamWork && item.deputyManagerName !== null && ` · КАМ: ${ownerLabel(item)}`}
                    </span>
                  </td>
                  <td data-label="Этап">
                    <span>{item.currentStageName}</span>
                    <span className="work-item__meta">на этапе {daysLabel(daysSince(item.stageEnteredAt, now))}</span>
                  </td>
                  <td data-label="Следующий шаг">{item.nextAction?.trim() || 'Не задан'}</td>
                  <td data-label="Срок"><span className={`status status--${status.tone}`}>{status.label}</span></td>
                  {seesTeamWork && <td data-label="КАМ">{ownerLabel(item)}</td>}
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      {listState.kind === 'ready' && (
        <Pagination
          label="Страницы взаимодействий"
          page={listState.page}
          size={pageSize}
          total={listState.total}
          disabled={listState.refreshing}
          onChange={changePage}
        />
      )}
    </section>
  )
}
