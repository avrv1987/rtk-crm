import { useCallback, useEffect, useRef, useState } from 'react'
import {
  apiClient,
  type InteractionIssue,
  type Me,
  type Organization,
  type ReportManagerOption
} from '../../shared/api/client'
import { todayInMoscow } from '../../shared/format/datetime'
import { Pagination } from '../../shared/ui/Pagination'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { saveFile } from '../admin/saveFile'
import { daysLabel, formatDate, handledAccessError, requestIdOf } from '../work/workShared'
import {
  calendarDaysBetween,
  emptyIssueFilters,
  type IssueFilters,
  isIssueOverdue,
  issueFiltersFromQuery,
  issueKinds,
  issueLevelLabels,
  issueLevels,
  issueListParams,
  issueQueryFromFilters,
  issuesHref,
  issueStatusLabels,
  type IssueStatusFilter,
  issueTitle
} from './issueModel'
import '../work/workControl.css'
import './issues.css'

type IssuesScreenProps = {
  profile: Me
  initialQuery: string
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; items: InteractionIssue[]; total: number; page: number; refreshing: boolean }
  | { kind: 'failed'; error: unknown }

type Options = { organizations: Organization[]; managers: ReportManagerOption[] }

type DownloadState = { kind: 'idle' } | { kind: 'saving' } | { kind: 'failed'; error: unknown }

const pageSize = 25
const statuses: IssueStatusFilter[] = ['OPEN', 'RESOLVED', 'ALL']
const kindFilterLabels = { PROBLEM: 'Проблемы', RISK: 'Риски' } as const

const loadAllOrganizations = async () => {
  const items: Organization[] = []
  for (let page = 0; ; page += 1) {
    const result = await apiClient.listOrganizations({ page, size: 100, sort: 'name,asc' })
    items.push(...result.items)
    if (result.items.length === 0 || items.length >= result.total) {
      return items
    }
  }
}

const ageOf = (issue: InteractionIssue, today: string) => calendarDaysBetween(
  todayInMoscow(new Date(issue.createdAt)),
  issue.resolvedAt === null ? today : todayInMoscow(new Date(issue.resolvedAt))
)

export const IssuesScreen = ({ profile, initialQuery, onSessionExpired, onProfileUnavailable }: IssuesScreenProps) => {
  const [filters, setFilters] = useState<IssueFilters>(() => issueFiltersFromQuery(initialQuery))
  const [listState, setListState] = useState<ListState>({ kind: 'loading' })
  const [options, setOptions] = useState<Options>({ organizations: [], managers: [] })
  const [download, setDownload] = useState<DownloadState>({ kind: 'idle' })
  const requestVersion = useRef(0)
  const handlers = { onSessionExpired, onProfileUnavailable }

  const load = useCallback(async (current: IssueFilters) => {
    const version = ++requestVersion.current
    setListState((state) => state.kind === 'ready' ? { ...state, refreshing: true } : { kind: 'loading' })
    try {
      const page = await apiClient.listIssues({ ...issueListParams(current), page: current.page, size: pageSize })
      if (version === requestVersion.current) {
        setListState({ kind: 'ready', items: page.items, total: page.total, page: page.page, refreshing: false })
      }
    } catch (error) {
      if (version === requestVersion.current && !handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setListState({ kind: 'failed', error })
      }
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    const query = issueQueryFromFilters(filters)
    window.history.replaceState(null, '', query.length > 0 ? `#/issues?${query}` : '#/issues')
    void load(filters)
  }, [filters, load])

  useEffect(() => {
    void Promise.all([loadAllOrganizations(), apiClient.listReportManagers()])
      .then(([organizations, managers]) => setOptions({ organizations, managers }))
      .catch((error: unknown) => {
        handledAccessError(error, { onSessionExpired, onProfileUnavailable })
      })
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => () => {
    requestVersion.current += 1
  }, [])

  const change = (next: Partial<IssueFilters>) => setFilters((current) => ({ ...current, ...next, page: 0 }))

  const saveXlsx = async () => {
    setDownload({ kind: 'saving' })
    try {
      saveFile(await apiClient.downloadIssues(issueListParams(filters)), `Проблемы и риски ${todayInMoscow()}.xlsx`)
      setDownload({ kind: 'idle' })
    } catch (error) {
      if (!handledAccessError(error, handlers)) {
        setDownload({ kind: 'failed', error })
      }
    }
  }

  const today = todayInMoscow()
  const responsibles = options.managers.some((manager) => manager.id === profile.id)
    ? options.managers.map((manager) => ({ id: manager.id, label: manager.id === profile.id ? `${manager.displayName} (я)` : manager.displayName }))
    : [{ id: profile.id, label: 'Я' }, ...options.managers.map((manager) => ({ id: manager.id, label: manager.displayName }))]
  const filtered = issueQueryFromFilters({ ...filters, page: 0 }).length > 0

  return (
    <section className="issues-page" aria-labelledby="issues-results-title">
      <p className="work__intro">
        {profile.role === 'USER' && 'Проблемы и риски по вашим работам и работам, где вы заместитель.'}
        {profile.role === 'LEADER' && 'Проблемы и риски по работам команды.'}
        {profile.role === 'MANAGEMENT' && 'Проблемы и риски по работам всех команд, только просмотр.'}
      </p>

      <form className="work-filters" aria-label="Отбор проблем и рисков" onSubmit={(event) => event.preventDefault()}>
        <label>
          Вид
          <select
            value={filters.kind}
            onChange={(event) => {
              const kind = issueKinds.find((item) => item === event.target.value) ?? ''
              change({ kind, riskLevel: kind === 'PROBLEM' ? '' : filters.riskLevel })
            }}
          >
            <option value="">Проблемы и риски</option>
            {issueKinds.map((kind) => <option key={kind} value={kind}>{kindFilterLabels[kind]}</option>)}
          </select>
        </label>
        <label>
          Уровень риска
          <select
            value={filters.riskLevel}
            disabled={filters.kind === 'PROBLEM'}
            onChange={(event) => change({ riskLevel: issueLevels.find((level) => level === event.target.value) ?? '' })}
          >
            <option value="">Любой</option>
            {issueLevels.map((level) => <option key={level} value={level}>{issueLevelLabels[level]}</option>)}
          </select>
        </label>
        <label>
          Ответственный
          <select value={filters.responsible} onChange={(event) => change({ responsible: event.target.value })}>
            <option value="">Все</option>
            {filters.responsible.length > 0 && !responsibles.some((option) => option.id === filters.responsible) && (
              <option value={filters.responsible}>Выбранный ранее сотрудник</option>
            )}
            {responsibles.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
          </select>
        </label>
        <label>
          Вуз
          <select value={filters.organizationId} onChange={(event) => change({ organizationId: event.target.value })}>
            <option value="">Все вузы</option>
            {filters.organizationId.length > 0 && !options.organizations.some((item) => item.id === filters.organizationId) && (
              <option value={filters.organizationId}>Выбранный ранее вуз</option>
            )}
            {options.organizations.map((organization) => (
              <option key={organization.id} value={organization.id}>{organization.name}</option>
            ))}
          </select>
        </label>
        <label>
          Статус
          <select
            value={filters.status}
            onChange={(event) => change({ status: statuses.find((status) => status === event.target.value) ?? 'OPEN' })}
          >
            {statuses.map((status) => <option key={status} value={status}>{issueStatusLabels[status]}</option>)}
          </select>
        </label>
        <label className="checkbox-field">
          <input type="checkbox" checked={filters.overdue} onChange={(event) => change({ overdue: event.target.checked })} />
          Только с прошедшим сроком
        </label>
        {filtered && (
          <button type="button" className="button--secondary work-filters__reset" onClick={() => setFilters(emptyIssueFilters)}>
            Сбросить фильтры
          </button>
        )}
      </form>

      <div className="issues-page__header">
        <h2 id="issues-results-title">Записи</h2>
        <p className="work__total" role="status">
          {listState.kind === 'ready' && (listState.refreshing ? 'Обновляем…' : `Найдено: ${listState.total}`)}
          {listState.kind === 'loading' && 'Загружаем…'}
        </p>
        <button
          type="button"
          className="button--secondary"
          disabled={download.kind === 'saving' || listState.kind !== 'ready' || listState.total === 0}
          onClick={() => void saveXlsx()}
        >
          {download.kind === 'saving' ? 'Готовим файл…' : 'Скачать XLSX'}
        </button>
      </div>

      {download.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось скачать файл. Сузьте отбор или повторите попытку.</p>
          <SupportDetails requestId={requestIdOf(download.error)} />
        </div>
      )}

      {listState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось загрузить проблемы и риски. Повторите попытку.</p>
          <SupportDetails requestId={requestIdOf(listState.error)} />
          <button type="button" onClick={() => void load(filters)}>Повторить</button>
        </div>
      )}

      {listState.kind === 'ready' && listState.items.length === 0 && (
        <div className="notice">
          <p>{filtered ? 'По выбранным условиям записей нет.' : 'Открытых проблем и рисков нет.'}</p>
        </div>
      )}

      {listState.kind === 'ready' && listState.items.length > 0 && (
        <div className="work-control__scroll">
          <table className="work-control__table work-control__table--cards" aria-labelledby="issues-results-title" aria-busy={listState.refreshing}>
            <thead>
              <tr>
                <th scope="col">Работа</th>
                <th scope="col">Вуз</th>
                <th scope="col">КАМ</th>
                <th scope="col">Вид</th>
                <th scope="col">Описание</th>
                <th scope="col">Ответственный</th>
                <th scope="col">Срок</th>
                <th scope="col">Возраст</th>
              </tr>
            </thead>
            <tbody>
              {listState.items.map((issue) => (
                <tr key={issue.id}>
                  <td data-label="Работа">
                    <a href={issuesHref(issue.organizationId, issue.interactionId)}>{issue.interactionTitle}</a>
                  </td>
                  <td data-label="Вуз">{issue.organizationName}</td>
                  <td data-label="КАМ">{issue.ownerManagerName ?? 'Требует назначения'}</td>
                  <td data-label="Вид">
                    {issueTitle(issue)}
                    {issue.status === 'RESOLVED' && <span className="work-control__hint">решена</span>}
                  </td>
                  <td data-label="Описание" className="issues-page__description">{issue.description}</td>
                  <td data-label="Ответственный">{issue.responsibleName}</td>
                  <td data-label="Срок" className={`issues-page__due${isIssueOverdue(issue, today) ? ' issues-page__overdue' : ''}`}>
                    {issue.dueOn === null ? 'не указан' : formatDate(issue.dueOn)}
                    {isIssueOverdue(issue, today) && <span className="work-control__hint">срок прошёл</span>}
                  </td>
                  <td data-label="Возраст">{daysLabel(ageOf(issue, today))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {listState.kind === 'ready' && (
        <Pagination
          label="Страницы проблем и рисков"
          page={listState.page}
          size={pageSize}
          total={listState.total}
          disabled={listState.refreshing}
          onChange={(page) => setFilters((current) => ({ ...current, page }))}
        />
      )}
    </section>
  )
}
