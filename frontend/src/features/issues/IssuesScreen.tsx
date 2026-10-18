import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type InteractionIssue,
  type Me,
  type Organization,
  type ReportManagerOption
} from '../../shared/api/client'
import { todayInMoscow } from '../../shared/format/datetime'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { FilterButton, FilterPanel } from '../../shared/ui/FilterPanel'
import { HintButton } from '../../shared/ui/HintButton'
import { CardDialog } from '../interactions/cardUi'
import { Pagination } from '../../shared/ui/Pagination'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { terms } from '../../shared/ui/terms'
import { saveFile } from '../admin/saveFile'
import { FilterChips, type ActiveChip } from '../control/FilterFields'
import { commandErrorText, daysLabel, formatDate, formatDateTime, handledAccessError, requestIdOf } from '../work/workShared'
import {
  calendarDaysBetween,
  canRespond,
  closedOrganizationHint,
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
  const [panelOpen, setPanelOpen] = useState(false)
  const [responding, setResponding] = useState<{ issue: InteractionIssue; resolve: boolean } | null>(null)
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
  const chips: ActiveChip[] = [
    filters.kind !== '' && { key: 'kind', label: `Вид: ${kindFilterLabels[filters.kind].toLowerCase()}`, onRemove: () => change({ kind: '' }) },
    filters.riskLevel !== '' && { key: 'level', label: `Уровень риска: ${issueLevelLabels[filters.riskLevel]}`, onRemove: () => change({ riskLevel: '' }) },
    filters.responsible !== '' && {
      key: 'responsible',
      label: `Кто решает: ${responsibles.find((option) => option.id === filters.responsible)?.label ?? 'выбранный ранее сотрудник'}`,
      onRemove: () => change({ responsible: '' })
    },
    filters.organizationId !== '' && {
      key: 'organization',
      label: `Вуз: ${options.organizations.find((item) => item.id === filters.organizationId)?.name ?? 'выбранный ранее вуз'}`,
      onRemove: () => change({ organizationId: '' })
    },
    filters.status !== 'OPEN' && { key: 'status', label: `Статус: ${issueStatusLabels[filters.status].toLowerCase()}`, onRemove: () => change({ status: 'OPEN' }) },
    filters.overdue && { key: 'overdue', label: 'Только с прошедшим сроком', onRemove: () => change({ overdue: false }) }
  ].filter((chip): chip is ActiveChip => chip !== false)

  return (
    <section className="issues-page" aria-labelledby="issues-results-title">
      <div className="control-toolbar">
        <HintButton label="Что на этом экране">
          {profile.role === 'USER' && 'Проблемы и риски по вашим взаимодействиям и тем, где вы заместитель. '}
          {profile.role === 'LEADER' && 'Проблемы и риски по взаимодействиям команды. '}
          {profile.role === 'MANAGEMENT' && 'Проблемы и риски по взаимодействиям всех команд, только просмотр. '}
          Строка открывает карточку взаимодействия сразу с окном «Проблемы и риски».
        </HintButton>
        <FilterButton count={chips.length} onClick={() => setPanelOpen(true)} />
        <ActionMenu
          label={terms.moreActions}
          items={[{
            label: download.kind === 'saving' ? 'Готовим файл…' : `${terms.export} XLSX`,
            onSelect: () => {
              if (download.kind !== 'saving' && listState.kind === 'ready' && listState.total > 0) {
                void saveXlsx()
              }
            }
          }]}
        />
      </div>
      <FilterChips chips={chips} onReset={() => setFilters(emptyIssueFilters)} />

      <FilterPanel open={panelOpen} onClose={() => setPanelOpen(false)} onReset={() => setFilters(emptyIssueFilters)}>
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
          Кто решает
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
      </FilterPanel>

      <div className="issues-page__header">
        <h2 id="issues-results-title">Записи</h2>
        <p className="work__total" role="status">
          {listState.kind === 'ready' && (listState.refreshing ? 'Обновляем…' : `Найдено: ${listState.total}`)}
          {listState.kind === 'loading' && 'Загружаем…'}
        </p>
      </div>

      {download.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось выгрузить файл. Сузьте фильтры или повторите попытку.</p>
          <SupportDetails requestId={requestIdOf(download.error)} />
        </div>
      )}
      {download.kind === 'saving' && <p className="work__total" role="status">Готовим файл XLSX…</p>}

      {listState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось загрузить проблемы и риски. Повторите попытку.</p>
          <SupportDetails requestId={requestIdOf(listState.error)} />
          <button type="button" onClick={() => void load(filters)}>Повторить</button>
        </div>
      )}

      {listState.kind === 'ready' && listState.items.length === 0 && (
        <div className="empty-state">
          <p>{filtered ? 'По выбранным фильтрам записей нет.' : 'Открытых проблем и рисков нет.'}</p>
        </div>
      )}

      {listState.kind === 'ready' && listState.items.length > 0 && (
        <div className="work-control__scroll">
          <table className="work-control__table work-control__table--cards" aria-labelledby="issues-results-title" aria-busy={listState.refreshing}>
            <thead>
              <tr>
                <th scope="col">Взаимодействие</th>
                <th scope="col">Вуз</th>
                <th scope="col">КАМ вуза</th>
                <th scope="col">Вид</th>
                <th scope="col">Описание</th>
                <th scope="col">Кто решает</th>
                <th scope="col">Срок</th>
                <th scope="col">Возраст</th>
                <th scope="col"><span className="visually-hidden">Действия</span></th>
              </tr>
            </thead>
            <tbody>
              {listState.items.map((issue) => (
                <tr key={issue.id}>
                  <td data-label="Взаимодействие">
                    {issue.organizationAccessible
                      ? <a href={issuesHref(issue.organizationId, issue.interactionId)}>{issue.interactionTitle}</a>
                      : issue.interactionTitle}
                  </td>
                  <td data-label="Вуз">
                    {issue.organizationAccessible
                      ? <a href={`#/organizations/${issue.organizationId}`}>{issue.organizationName}</a>
                      : <>{issue.organizationName}<span className="work-control__hint">{closedOrganizationHint(issue)}</span></>}
                  </td>
                  <td data-label="КАМ вуза">{issue.ownerManagerName ?? 'Требует назначения'}</td>
                  <td data-label="Вид">
                    {issueTitle(issue)}
                    {issue.status === 'RESOLVED' && <span className="work-control__hint">решена</span>}
                  </td>
                  <td data-label="Описание" className="issues-page__description">
                    {issue.organizationAccessible
                      ? <a className="issues-page__open" href={issuesHref(issue.organizationId, issue.interactionId)}>{issue.description}</a>
                      : issue.description}
                    {issue.lastComment !== null && (
                      <span className="work-control__hint">
                        Комментарий ответственного{issue.lastCommentByName === null ? '' : ` (${issue.lastCommentByName}`}
                        {issue.lastCommentAt === null ? '' : `, ${formatDateTime(issue.lastCommentAt)}`}{issue.lastCommentByName === null ? '' : ')'}: {issue.lastComment}
                      </span>
                    )}
                    {issue.resolution !== null && <span className="work-control__hint">Как решили: {issue.resolution}</span>}
                  </td>
                  <td data-label="Кто решает">{issue.responsibleName}</td>
                  <td data-label="Срок" className={`issues-page__due${isIssueOverdue(issue, today) ? ' issues-page__overdue' : ''}`}>
                    {issue.dueOn === null ? 'не указан' : formatDate(issue.dueOn)}
                    {isIssueOverdue(issue, today) && <span className="work-control__hint">срок прошёл</span>}
                  </td>
                  <td data-label="Возраст">{daysLabel(ageOf(issue, today))}</td>
                  <td data-label="Действия">
                    {canRespond(issue, profile) && (
                      <span className="issues-page__actions">
                        <button type="button" className="button--secondary" onClick={() => setResponding({ issue, resolve: false })}>Комментарий</button>
                        <button type="button" className="button--secondary" onClick={() => setResponding({ issue, resolve: true })}>Решено</button>
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <IssueResponseDialog
        target={responding}
        onClose={() => setResponding(null)}
        onSaved={(updated) => {
          setResponding(null)
          setListState((state) => state.kind === 'ready'
            ? { ...state, items: state.items.map((item) => item.id === updated.id ? updated : item) }
            : state)
        }}
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />

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

type IssueResponseDialogProps = {
  target: { issue: InteractionIssue; resolve: boolean } | null
  onClose: () => void
  onSaved: (issue: InteractionIssue) => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

// Ответ ответственного из реестра: комментарий или «Решено» с обязательным «Как решили». Пишется и в историю
// взаимодействия, чтобы КАМ вуза увидел ответ.
const IssueResponseDialog = ({ target, onClose, onSaved, onSessionExpired, onProfileUnavailable }: IssueResponseDialogProps) => {
  const [text, setText] = useState('')
  const [state, setState] = useState<{ kind: 'idle' } | { kind: 'saving' } | { kind: 'failed'; error: unknown }>({ kind: 'idle' })
  const commandKey = useRef<string | null>(null)

  useEffect(() => {
    setText('')
    setState({ kind: 'idle' })
    commandKey.current = null
  }, [target])

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (target === null || text.trim().length === 0) {
      return
    }
    setState({ kind: 'saving' })
    const key = commandKey.current ?? (commandKey.current = createIdempotencyKey())
    try {
      const updated = target.resolve
        ? await apiClient.resolveIssueAsResponsible(target.issue.id, { text: text.trim() }, key)
        : await apiClient.commentIssueAsResponsible(target.issue.id, { text: text.trim() }, key)
      onSaved(updated)
    } catch (error) {
      if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setState({ kind: 'failed', error })
      }
    }
  }

  return (
    <CardDialog open={target !== null} title={target?.resolve ? 'Решено' : 'Комментарий ответственного'} onClose={onClose}>
      {target !== null && (
        <form className="issues-page__response" onSubmit={(event) => void submit(event)}>
          <p>{issueTitle(target.issue)}: {target.issue.description}</p>
          <label>
            {target.resolve ? 'Как решили' : 'Комментарий'}<span className="required-mark" aria-hidden="true"> *</span>
            <textarea value={text} maxLength={1000} required onChange={(event) => {
              commandKey.current = null
              setText(event.target.value)
            }} />
          </label>
          <p className="work-control__hint">Запись появится в истории взаимодействия; КАМ вуза её увидит.</p>
          {state.kind === 'failed' && (
            <div className="notice notice--error" role="alert">
              <p>{commandErrorText(state.error, target.resolve ? 'закрыть запись' : 'сохранить комментарий')}</p>
              <SupportDetails requestId={requestIdOf(state.error)} code={state.error instanceof ApiError ? state.error.code : undefined} />
            </div>
          )}
          <div className="issues-page__response-actions">
            <button type="submit" disabled={state.kind === 'saving' || text.trim().length === 0}>
              {state.kind === 'saving' ? 'Сохраняем…' : target.resolve ? 'Отметить решённой' : 'Сохранить комментарий'}
            </button>
            <button type="button" className="button--secondary" onClick={onClose}>Отмена</button>
          </div>
        </form>
      )}
    </CardDialog>
  )
}
