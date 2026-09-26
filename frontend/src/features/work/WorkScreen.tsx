import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  type InteractionDue,
  type InteractionFlagFilter,
  type InteractionListItem,
  type InteractionWorkStatusFilter,
  type Me,
  type Organization,
  type ReportManagerOption
} from '../../shared/api/client'
import { Pagination } from '../../shared/ui/Pagination'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { InteractionMarkBadges } from '../interactions/InteractionMarkBadges'
import { reportFlagLabels, reportFlags } from '../reports/reportSelection'
import { TeamIndicators } from './TeamIndicators'
import { TeamsSummary } from './TeamsSummary'
import { daysLabel, daysSince, eventTypeLabel, formatDate, formatDateTime } from './workShared'

type WorkScreenProps = {
  role: Me['role']
  initialQuery: string
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type WorkFilters = {
  q: string
  due: InteractionDue | ''
  stage: string
  organizationId: string
  flag: InteractionFlagFilter | ''
  licenseExpiresBy: string
  responsible: string
  status: InteractionWorkStatusFilter
  minDaysOnStage: number | null
  page: number
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; items: InteractionListItem[]; total: number; page: number; refreshing: boolean }
  | { kind: 'failed'; error: unknown }

type OptionsState =
  | { kind: 'loading' }
  | {
    kind: 'ready'
    stages: string[]
    organizations: Pick<Organization, 'id' | 'name'>[]
    managers: ReportManagerOption[]
  }
  | { kind: 'failed'; error: unknown }

const pageSize = 25
const searchDelayMilliseconds = 300
const unassigned = 'UNASSIGNED'

const dueOptions: { value: InteractionDue | ''; label: string }[] = [
  { value: '', label: 'Все' },
  { value: 'OVERDUE', label: 'Просрочено' },
  { value: 'THIS_WEEK', label: 'Срок на этой неделе' },
  { value: 'NO_NEXT_STEP', label: 'Без следующего шага или срока' }
]

const currentYear = new Date().getFullYear()

const licenseYears = Array.from({ length: 7 }, (_, index) => (currentYear - 1 + index).toString())

const statusOptions: { value: InteractionWorkStatusFilter; label: string }[] = [
  { value: 'ACTIVE', label: 'Активные' },
  { value: 'PAUSED', label: 'Приостановленные' },
  { value: 'COMPLETED', label: 'Завершённые' },
  { value: 'ALL', label: 'Все, включая завершённые' }
]

const stageDayOptions = [7, 14, 30, 60, 90]

const emptyFilters: WorkFilters = {
  q: '',
  due: '',
  stage: '',
  organizationId: '',
  flag: '',
  licenseExpiresBy: '',
  responsible: '',
  status: 'ACTIVE',
  minDaysOnStage: null,
  page: 0
}

const isDue = (value: string): value is InteractionDue => dueOptions.some((option) => option.value !== '' && option.value === value)

const isFlag = (value: string): value is InteractionFlagFilter => reportFlags.some((flag) => flag === value)

const isStatus = (value: string): value is InteractionWorkStatusFilter => statusOptions.some((option) => option.value === value)

const positiveInteger = (value: string | null) => {
  const number = Number(value ?? '')
  return Number.isInteger(number) && number > 0 ? number : null
}

const filtersFromQuery = (query: string): WorkFilters => {
  const params = new URLSearchParams(query)
  const due = params.get('due') ?? ''
  const flag = params.get('flag') ?? ''
  const status = params.get('status') ?? 'ACTIVE'
  return {
    q: params.get('q') ?? '',
    due: isDue(due) ? due : '',
    stage: params.get('stage') ?? '',
    organizationId: params.get('organization') ?? '',
    flag: isFlag(flag) ? flag : '',
    licenseExpiresBy: /^\d{4}$/.test(params.get('license') ?? '') ? params.get('license') ?? '' : '',
    responsible: params.get('responsible') ?? '',
    status: isStatus(status) ? status : 'ACTIVE',
    minDaysOnStage: positiveInteger(params.get('minDaysOnStage')),
    page: positiveInteger(params.get('page')) ?? 0
  }
}

const queryFromFilters = (filters: WorkFilters) => {
  const params = new URLSearchParams()
  if (filters.q.trim().length > 0) {
    params.set('q', filters.q.trim())
  }
  if (filters.due !== '') {
    params.set('due', filters.due)
  }
  if (filters.stage.length > 0) {
    params.set('stage', filters.stage)
  }
  if (filters.organizationId.length > 0) {
    params.set('organization', filters.organizationId)
  }
  if (filters.flag !== '') {
    params.set('flag', filters.flag)
  }
  if (filters.licenseExpiresBy !== '') {
    params.set('license', filters.licenseExpiresBy)
  }
  if (filters.responsible.length > 0) {
    params.set('responsible', filters.responsible)
  }
  if (filters.status !== 'ACTIVE') {
    params.set('status', filters.status)
  }
  if (filters.minDaysOnStage !== null) {
    params.set('minDaysOnStage', filters.minDaysOnStage.toString())
  }
  if (filters.page > 0) {
    params.set('page', filters.page.toString())
  }
  return params.toString()
}

const hasFilters = (filters: WorkFilters) => (
  filters.q.trim().length > 0
  || filters.due !== ''
  || filters.stage.length > 0
  || filters.organizationId.length > 0
  || filters.responsible.length > 0
  || filters.status !== 'ACTIVE'
  || filters.minDaysOnStage !== null
  || filters.flag !== ''
  || filters.licenseExpiresBy !== ''
)

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

export const WorkScreen = ({ role, initialQuery, onSessionExpired, onProfileUnavailable }: WorkScreenProps) => {
  const [filters, setFilters] = useState<WorkFilters>(() => filtersFromQuery(initialQuery))
  const [searchText, setSearchText] = useState(filters.q)
  const [listState, setListState] = useState<ListState>({ kind: 'loading' })
  const [optionsState, setOptionsState] = useState<OptionsState>({ kind: 'loading' })
  const listRequestVersion = useRef(0)
  const resultsHeading = useRef<HTMLHeadingElement>(null)
  const focusResults = useRef(false)
  const currentFilters = useRef(filters)
  const seesTeamWork = role === 'LEADER' || role === 'MANAGEMENT'

  currentFilters.current = filters

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
    const query = queryFromFilters(filters)
    window.history.replaceState(null, '', query.length > 0 ? `#/work?${query}` : '#/work')
    void loadList(filters)
  }, [filters, loadList])

  useEffect(() => () => {
    listRequestVersion.current += 1
  }, [])

  useEffect(() => {
    const applyLinkedFilters = () => {
      const [path, query = ''] = window.location.hash.replace(/^#\/?/, '').split('?')
      if (path !== 'work') {
        return
      }
      const linked = filtersFromQuery(query)
      if (queryFromFilters(linked) === queryFromFilters(currentFilters.current)) {
        return
      }
      setSearchText(linked.q)
      setFilters(linked)
    }
    window.addEventListener('hashchange', applyLinkedFilters)
    return () => window.removeEventListener('hashchange', applyLinkedFilters)
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

  useEffect(() => {
    if (listState.kind === 'ready' && !listState.refreshing && focusResults.current) {
      focusResults.current = false
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
    setFilters(emptyFilters)
  }

  const now = Date.now()
  const organizationOptions = optionsState.kind === 'ready' ? optionsState.organizations : []
  const stageOptions = optionsState.kind === 'ready' ? optionsState.stages : []
  const managerOptions = optionsState.kind === 'ready' ? optionsState.managers : []
  const stageDays = filters.minDaysOnStage === null || stageDayOptions.includes(filters.minDaysOnStage)
    ? stageDayOptions
    : [...stageDayOptions, filters.minDaysOnStage].sort((a, b) => a - b)
  const filtered = hasFilters(filters)

  return (
    <section className="work" aria-labelledby="work-results-title">
      <p className="work__intro">
        {role === 'MANAGEMENT' && 'Взаимодействия всех команд в режиме просмотра. Сначала идут ближайшие и просроченные сроки следующего шага.'}
        {role === 'LEADER' && 'Взаимодействия по всем вузам команды. Сначала идут ближайшие и просроченные сроки следующего шага.'}
        {role === 'USER' && 'Взаимодействия по вашим вузам. Сначала идут ближайшие и просроченные сроки следующего шага.'}
      </p>

      {role === 'LEADER' && <TeamIndicators onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} />}
      {role === 'MANAGEMENT' && <TeamsSummary onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} />}

      <form className="work-filters" role="search" aria-label="Отбор взаимодействий" onSubmit={(event) => event.preventDefault()}>
        <label className="work-filters__search">
          Поиск по названию взаимодействия или вуза
          <input
            type="search"
            value={searchText}
            maxLength={200}
            onChange={(event) => setSearchText(event.target.value)}
          />
        </label>
        <fieldset className="segmented">
          <legend>Срок следующего шага</legend>
          {dueOptions.map((option) => (
            <label key={option.value || 'all'} className="segmented__option">
              <input
                type="radio"
                name="work-due"
                value={option.value}
                checked={filters.due === option.value}
                onChange={() => changeFilter({ due: option.value })}
              />
              <span>{option.label}</span>
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
            {filters.stage.length > 0 && !stageOptions.includes(filters.stage) && (
              <option value={filters.stage}>{filters.stage}</option>
            )}
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
            {organizationOptions.map((organization) => (
              <option key={organization.id} value={organization.id}>{organization.name}</option>
            ))}
          </select>
        </label>
        <label>
          Отметка
          <select
            value={filters.flag}
            onChange={(event) => changeFilter({ flag: isFlag(event.target.value) ? event.target.value : '' })}
          >
            <option value="">Все работы</option>
            {reportFlags.map((flag) => <option key={flag} value={flag}>{reportFlagLabels[flag]}</option>)}
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
            Ответственный
            <select
              value={filters.responsible}
              disabled={optionsState.kind !== 'ready' && filters.responsible.length === 0}
              onChange={(event) => changeFilter({ responsible: event.target.value })}
            >
              <option value="">Все ответственные</option>
              <option value={unassigned}>Требует назначения</option>
              {filters.responsible.length > 0 && filters.responsible !== unassigned
                && !managerOptions.some((manager) => manager.id === filters.responsible) && (
                <option value={filters.responsible}>Выбранный ранее КАМ</option>
              )}
              {managerOptions.map((manager) => (
                <option key={manager.id} value={manager.id}>
                  {manager.active ? manager.displayName : `${manager.displayName} (неактивен)`}
                </option>
              ))}
            </select>
          </label>
        )}
        <label>
          Статус работы
          <select value={filters.status} onChange={(event) => changeFilter({ status: event.target.value as InteractionWorkStatusFilter })}>
            {statusOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </label>
        <label>
          На текущем этапе
          <select
            value={filters.minDaysOnStage ?? ''}
            onChange={(event) => changeFilter({ minDaysOnStage: positiveInteger(event.target.value) })}
          >
            <option value="">Любой срок</option>
            {stageDays.map((days) => <option key={days} value={days}>дольше {daysLabel(days)}</option>)}
          </select>
        </label>
        {filtered && (
          <button type="button" className="button--secondary work-filters__reset" onClick={resetFilters}>Сбросить фильтры</button>
        )}
      </form>

      {optionsState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось загрузить списки этапов, вузов и ответственных для фильтров. Поиск и остальные отборы работают.</p>
          <SupportDetails requestId={optionsState.error instanceof ApiError ? optionsState.error.requestId : undefined} />
          <button type="button" className="button--secondary" onClick={() => void loadOptions()}>Повторить</button>
        </div>
      )}

      <div className="work__results-header">
        <h2 id="work-results-title" ref={resultsHeading} tabIndex={-1}>Взаимодействия</h2>
        <p className="work__total" role="status">
          {listState.kind === 'ready' && (listState.refreshing ? 'Обновляем…' : `Найдено: ${listState.total}`)}
          {listState.kind === 'loading' && 'Загружаем взаимодействия…'}
        </p>
      </div>

      {listState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось загрузить взаимодействия. Повторите попытку.</p>
          <SupportDetails requestId={listState.error instanceof ApiError ? listState.error.requestId : undefined} />
          <button type="button" onClick={() => void loadList(filters)}>Повторить</button>
        </div>
      )}

      {listState.kind === 'ready' && listState.items.length === 0 && (
        filtered ? (
          <div className="notice">
            <p>По выбранным условиям взаимодействий нет.</p>
            <button type="button" className="button--secondary" onClick={resetFilters}>Сбросить фильтры</button>
          </div>
        ) : (
          <div className="notice">
            <p>Активных взаимодействий нет. Завершённые работы показывает отбор «Статус работы».</p>
            <a className="button-link" href="#/organizations">Перейти к вузам</a>
          </div>
        )
      )}

      {listState.kind === 'ready' && listState.items.length > 0 && (
        <ul className="work-list" aria-labelledby="work-results-title" aria-busy={listState.refreshing}>
          {listState.items.map((item) => {
            const status = dueStatus(item, now)
            return (
              <li key={item.id} className="work-item">
                <div className="work-item__heading">
                  <a className="work-item__title" href={`#/organizations/${item.organizationId}/${item.id}`}>{item.title}</a>
                  <a className="work-item__organization" href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a>
                  <InteractionMarkBadges marks={item.marks} />
                </div>
                <dl className="work-item__facts">
                  <div>
                    <dt>Этап</dt>
                    <dd>{item.currentStageName}</dd>
                  </div>
                  <div>
                    <dt>Дней на этапе</dt>
                    <dd>{daysLabel(daysSince(item.stageEnteredAt, now))}</dd>
                  </div>
                  <div>
                    <dt>Следующий шаг</dt>
                    <dd>{item.nextAction?.trim() || 'Не задан'}</dd>
                  </div>
                  <div>
                    <dt>Срок</dt>
                    <dd><span className={`status status--${status.tone}`}>{status.label}</span></dd>
                  </div>
                  <div>
                    <dt>Последнее событие</dt>
                    <dd>
                      {item.lastEventAt === null
                        ? 'Событий нет'
                        : `${formatDateTime(item.lastEventAt)}, ${eventTypeLabel(item.lastEventType)}`}
                    </dd>
                  </div>
                  <div>
                    <dt>Программа</dt>
                    <dd>{item.programName ?? 'Не указана'}</dd>
                  </div>
                  {(seesTeamWork || item.deputyManagerName !== null) && (
                    <div>
                      <dt>Ответственный</dt>
                      <dd>{ownerLabel(item)}</dd>
                    </div>
                  )}
                </dl>
              </li>
            )
          })}
        </ul>
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
