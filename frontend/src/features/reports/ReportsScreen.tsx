import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type ReportColumn,
  type ReportJob,
  type ReportKind,
  type ReportPreview,
  type ReportPreviewRequest,
  type ReportRequest,
  type StatisticsRequest,
  type StatisticsResult
} from '../../shared/api/client'
import { loadReportSelection, saveReportSelection } from '../interactions/drafts'
import {
  columnTitle,
  defaultSelection,
  demandSorts,
  groupingProblem,
  groupingTitles,
  groupingsFor,
  normalizeSelection,
  periodProblem,
  reportColumns,
  reportFormats,
  reportKinds,
  selectionProblem,
  toPreviewRequest,
  toStatisticsRequest,
  type ReportSelection
} from './reportSelection'
import { StatisticsChart, chartBars } from './StatisticsChart'

type ReportsScreenProps = {
  profileId: string
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type FilterOption = {
  id: string
  label: string
}

type FilterOptions = {
  organizations: FilterOption[]
  directions: FilterOption[]
  programs: FilterOption[]
  products: FilterOption[]
  managers: FilterOption[]
  stages: FilterOption[]
}

type OptionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; options: FilterOptions }
  | { kind: 'failed'; error: unknown }

type PreviewState =
  | { kind: 'idle' }
  | { kind: 'loading'; request: ReportPreviewRequest }
  | { kind: 'ready'; request: ReportPreviewRequest; preview: ReportPreview }
  | { kind: 'failed'; request: ReportPreviewRequest; error: unknown }

type StatisticsState =
  | { kind: 'idle' }
  | { kind: 'loading'; request: StatisticsRequest }
  | { kind: 'ready'; request: StatisticsRequest; result: StatisticsResult }
  | { kind: 'failed'; request: StatisticsRequest; error: unknown }

type OrderTarget = 'report' | 'chart'

type JobsState =
  | { kind: 'loading' }
  | { kind: 'ready'; jobs: ReportJob[] }
  | { kind: 'failed'; error: unknown }

type DownloadState =
  | { kind: 'idle' }
  | { kind: 'downloading'; id: string }
  | { kind: 'failed'; id: string; error: unknown }

const previewPageSize = 50

const pollIntervalMs = 1000

const kindLabels: Record<ReportKind, string> = {
  PORTFOLIO: 'Портфель взаимодействий',
  EVENTS: 'События за период',
  DEMAND: 'Востребованность программ'
}

const kindHints: Record<ReportKind, string> = {
  PORTFOLIO: 'Строка — взаимодействие. Этап и ответственный — текущие на момент формирования.',
  EVENTS: 'Строка — событие: создание, переход или комментарий. Ответственный — КАМ на момент события.',
  DEMAND: 'Строка — ИТ-программа. Заявки — заявки на обучение с сайта по предложенному контракту (демонстрационный стенд) в ваших вузах, период — по дате подачи. Обучающиеся (участия, не уникальные люди) — последний снимок потоков Moodle: курсов и групп, сопоставленных с датами начала и окончания, период к ним не применяется. Параллельные потоки — потоки, которые идут в последний день периода, а если он не задан — сегодня. «Нет данных» — источник не дал значения, это не ноль. Единого рейтинга нет: выберите показатель для сортировки.'
}

const demandSortLabels: Record<(typeof demandSorts)[number], string> = {
  APPLICATIONS: 'по заявкам',
  PARTICIPANTS: 'по обучающимся',
  PARALLEL_RUNS: 'по параллельным потокам'
}

const dateColumns: ReadonlySet<ReportColumn> = new Set<ReportColumn>(['CREATED_AT', 'LAST_EVENT_AT', 'NEXT_ACTION_AT', 'EVENT_AT'])

const moscowDateTime = new Intl.DateTimeFormat('ru-RU', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Europe/Moscow'
})

const localDateTime = new Intl.DateTimeFormat('ru-RU', {
  dateStyle: 'medium',
  timeStyle: 'short'
})

const formatDateTime = (formatter: Intl.DateTimeFormat, value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : formatter.format(date)
}

const isFinished = (job: ReportJob) => job.status === 'SUCCEEDED' || job.status === 'FAILED'

const jobStatusText = (job: ReportJob) => {
  if (job.status === 'PENDING') {
    return 'В очереди'
  }
  if (job.status === 'RUNNING') {
    return job.rowCount === null || job.rowCount === undefined
      ? 'Выбираем строки'
      : `Формируем файл, строк: ${job.rowCount}`
  }
  if (job.status === 'SUCCEEDED') {
    return `Готов, строк: ${job.rowCount ?? 0}`
  }
  return 'Не построен'
}

const jobTitle = (job: ReportJob) => (
  job.groupBy === null || job.groupBy === undefined
    ? `${kindLabels[job.kind]}, ${job.format}`
    : `Диаграмма ${groupingTitles[job.groupBy]} — ${kindLabels[job.kind]}, ${job.format}`
)

const upsertJob = (jobs: ReportJob[], job: ReportJob) => (
  jobs.some((item) => item.id === job.id)
    ? jobs.map((item) => item.id === job.id && !isFinished(item) ? job : item)
    : [job, ...jobs]
)

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

const catalogOptions = (items: { id: string; name: string }[]): FilterOption[] => (
  items.map((item) => ({ id: item.id, label: item.name }))
)

const known = (ids: string[], options: FilterOption[]) => ids.filter((id) => options.some((option) => option.id === id))

const errorText = (error: unknown, fallback: string) => {
  if (!(error instanceof ApiError)) {
    return 'Не удалось связаться с сервисом. Проверьте соединение и повторите попытку.'
  }
  if (error.code === 'REPORT_ACCESS_CHANGED') {
    return 'Права доступа изменились после заказа, поэтому файл не выдаётся. Сформируйте отчёт заново.'
  }
  if (error.code === 'REPORT_CAPACITY_EXCEEDED') {
    return 'Сейчас все места для построения отчётов заняты. Повторите заказ через 30 секунд.'
  }
  if (error.code === 'VALIDATION_ERROR') {
    return 'Проверьте параметры отчёта.'
  }
  return fallback
}

type ErrorNoticeProps = {
  error: unknown
  message: string
  onRetry?: () => void
  retryLabel?: string
}

const ErrorNotice = ({ error, message, onRetry, retryLabel = 'Повторить' }: ErrorNoticeProps) => {
  const fieldErrors = error instanceof ApiError ? Object.entries(error.fieldErrors ?? {}) : []
  return (
    <div className="interaction-command-error" role="alert">
      <p>{errorText(error, message)}</p>
      {error instanceof ApiError && (
        <div className="structured-api-error">
          <p>Код: {error.code}</p>
          <p>{error.message}</p>
          {fieldErrors.length > 0 && (
            <ul>
              {fieldErrors.map(([field, text]) => <li key={field}>{field}: {text}</li>)}
            </ul>
          )}
          <p className="request-id">Request ID: {error.requestId}</p>
        </div>
      )}
      {onRetry && <button type="button" onClick={onRetry}>{retryLabel}</button>}
    </div>
  )
}

type MultiSelectProps = {
  legend: string
  options: FilterOption[]
  selected: string[]
  onChange: (ids: string[]) => void
  includeNone?: boolean
  onIncludeNoneChange?: (value: boolean) => void
}

const MultiSelect = ({ legend, options, selected, onChange, includeNone, onIncludeNoneChange }: MultiSelectProps) => {
  const [search, setSearch] = useState('')
  const query = search.trim().toLocaleLowerCase('ru-RU')
  const visible = query.length === 0
    ? options
    : options.filter((option) => option.label.toLocaleLowerCase('ru-RU').includes(query))
  const count = selected.length + (includeNone === true ? 1 : 0)

  const toggle = (id: string, checked: boolean) => {
    onChange(checked ? [...selected, id] : selected.filter((item) => item !== id))
  }

  const clear = () => {
    onChange([])
    onIncludeNoneChange?.(false)
  }

  return (
    <fieldset className="report-filter">
      <legend>{legend}</legend>
      <div className="report-filter__summary">
        <span>{count === 0 ? 'Все значения' : `Выбрано: ${count}`}</span>
        {count > 0 && (
          <button type="button" className="report-filter__clear" onClick={clear}>Снять выбор</button>
        )}
      </div>
      {options.length > 6 && (
        <label className="report-filter__search">
          Найти
          <input type="search" value={search} onChange={(event) => setSearch(event.target.value)} />
        </label>
      )}
      <ul className="report-filter__options">
        {onIncludeNoneChange && (
          <li>
            <label>
              <input
                type="checkbox"
                checked={includeNone === true}
                onChange={(event) => onIncludeNoneChange(event.target.checked)}
              />
              <span className="report-filter__none">Не указано</span>
            </label>
          </li>
        )}
        {visible.map((option) => (
          <li key={option.id}>
            <label>
              <input
                type="checkbox"
                checked={selected.includes(option.id)}
                onChange={(event) => toggle(option.id, event.target.checked)}
              />
              <span>{option.label}</span>
            </label>
          </li>
        ))}
      </ul>
      {options.length === 0 && <p className="report-filter__empty">Нет доступных значений.</p>}
      {options.length > 0 && visible.length === 0 && <p className="report-filter__empty">Ничего не найдено.</p>}
    </fieldset>
  )
}

export const ReportsScreen = ({ profileId, onSessionExpired, onProfileUnavailable }: ReportsScreenProps) => {
  const [selection, setSelection] = useState<ReportSelection>(() => normalizeSelection(loadReportSelection(profileId)))
  const [optionsState, setOptionsState] = useState<OptionsState>({ kind: 'loading' })
  const [previewState, setPreviewState] = useState<PreviewState>({ kind: 'idle' })
  const [statisticsState, setStatisticsState] = useState<StatisticsState>({ kind: 'idle' })
  const [jobsState, setJobsState] = useState<JobsState>({ kind: 'loading' })
  const [pollError, setPollError] = useState<unknown>(null)
  const [ordering, setOrdering] = useState(false)
  const [orderError, setOrderError] = useState<{ target: OrderTarget; error: unknown } | null>(null)
  const [chartJobId, setChartJobId] = useState<string | null>(null)
  const [downloadState, setDownloadState] = useState<DownloadState>({ kind: 'idle' })
  const previewVersion = useRef(0)
  const statisticsVersion = useRef(0)
  const orderKey = useRef<{ payload: string; key: string } | null>(null)
  const autoDownloadIds = useRef(new Set<string>())

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

  const update = (patch: Partial<ReportSelection>) => {
    setSelection((current) => ({ ...current, ...patch }))
  }

  const loadOptions = useCallback(async () => {
    setOptionsState({ kind: 'loading' })
    try {
      const [organizations, directions, programs, products, managers, stages] = await Promise.all([
        loadAllPages((page) => apiClient.listOrganizations({ page, size: 100, sort: 'name,asc' })),
        loadAllPages((page) => apiClient.listDirections({ page, size: 100 })),
        loadAllPages((page) => apiClient.listPrograms({ page, size: 100 })),
        loadAllPages((page) => apiClient.listProducts({ page, size: 100 })),
        apiClient.listReportManagers(),
        apiClient.listReportStages()
      ])
      const options: FilterOptions = {
        organizations: organizations.map((organization) => ({
          id: organization.id,
          label: organization.type === 'SCHOOL' ? `${organization.name} (школа)` : organization.name
        })),
        directions: catalogOptions(directions),
        programs: catalogOptions(programs),
        products: catalogOptions(products),
        managers: managers.map((manager) => ({
          id: manager.id,
          label: manager.active ? manager.displayName : `${manager.displayName} (неактивен)`
        })),
        stages: stages.map((stage) => ({ id: stage, label: stage }))
      }
      setOptionsState({ kind: 'ready', options })
      setSelection((current) => ({
        ...current,
        organizationIds: known(current.organizationIds, options.organizations),
        directionIds: known(current.directionIds, options.directions),
        programIds: known(current.programIds, options.programs),
        productIds: known(current.productIds, options.products),
        managerIds: known(current.managerIds, options.managers),
        stages: known(current.stages, options.stages)
      }))
    } catch (error) {
      if (!handleAccessError(error)) {
        setOptionsState({ kind: 'failed', error })
      }
    }
  }, [handleAccessError])

  const loadJobs = useCallback(async () => {
    setJobsState({ kind: 'loading' })
    setPollError(null)
    try {
      setJobsState({ kind: 'ready', jobs: await apiClient.listRecentReportJobs() })
    } catch (error) {
      if (!handleAccessError(error)) {
        setJobsState({ kind: 'failed', error })
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void loadOptions()
    void loadJobs()
    return () => {
      previewVersion.current += 1
      statisticsVersion.current += 1
    }
  }, [loadJobs, loadOptions])

  useEffect(() => {
    const pendingDownloads = autoDownloadIds.current
    return () => pendingDownloads.clear()
  }, [])

  useEffect(() => {
    saveReportSelection(profileId, selection)
  }, [profileId, selection])

  const downloadJob = useCallback(async (job: ReportJob) => {
    setDownloadState({ kind: 'downloading', id: job.id })
    try {
      const blob = await apiClient.downloadReportJobResult(job.id)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = job.fileName ?? `report.${job.format.toLowerCase()}`
      document.body.append(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 0)
      setDownloadState({ kind: 'idle' })
    } catch (error) {
      if (!handleAccessError(error)) {
        setDownloadState({ kind: 'failed', id: job.id, error })
      }
    }
  }, [handleAccessError])

  const refreshJobs = useCallback(async (ids: string[]) => {
    const results = await Promise.allSettled(ids.map((id) => apiClient.getReportJob(id)))
    const refreshed: ReportJob[] = []
    const gone = new Set<string>()
    let failure: unknown = null
    for (const [index, result] of results.entries()) {
      if (result.status === 'fulfilled') {
        refreshed.push(result.value)
        continue
      }
      failure = result.reason
      if (result.reason instanceof ApiError && (result.reason.status === 404 || result.reason.status === 410)) {
        gone.add(ids[index])
      }
    }
    setJobsState((current) => ({
      kind: 'ready',
      jobs: refreshed.reduce(upsertJob, (current.kind === 'ready' ? current.jobs : []).filter((job) => !gone.has(job.id)))
    }))
    for (const job of refreshed) {
      if (isFinished(job) && autoDownloadIds.current.delete(job.id) && job.resultReady) {
        void downloadJob(job)
      }
    }
    if (failure !== null && !handleAccessError(failure)) {
      setPollError(failure)
    }
  }, [downloadJob, handleAccessError])

  useEffect(() => {
    if (jobsState.kind !== 'ready' || pollError !== null) {
      return
    }
    const active = jobsState.jobs.filter((job) => !isFinished(job)).map((job) => job.id)
    if (active.length === 0) {
      return
    }
    const timer = window.setTimeout(() => void refreshJobs(active), pollIntervalMs)
    return () => window.clearTimeout(timer)
  }, [jobsState, pollError, refreshJobs])

  const problem = selectionProblem(selection)
  const blocked = problem !== null || optionsState.kind !== 'ready'
  const currentRequest = toPreviewRequest(selection)
  const statisticsProblem = groupingProblem(selection)
  const statisticsBlocked = periodProblem(selection) !== null || statisticsProblem !== null || optionsState.kind !== 'ready'
  const currentStatisticsRequest = toStatisticsRequest(selection)
  const columns = reportColumns[selection.kind]
  const selectedColumns = selection.columns[selection.kind]

  const runPreview = async (request: ReportPreviewRequest, page: number) => {
    const version = ++previewVersion.current
    setPreviewState({ kind: 'loading', request })
    try {
      const preview = await apiClient.previewReport(request, page, previewPageSize)
      if (version === previewVersion.current) {
        setPreviewState({ kind: 'ready', request, preview })
      }
    } catch (error) {
      if (version !== previewVersion.current || handleAccessError(error)) {
        return
      }
      setPreviewState({ kind: 'failed', request, error })
    }
  }

  const submitPreview = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!blocked) {
      void runPreview(currentRequest, 0)
    }
  }

  const runStatistics = async (request: StatisticsRequest) => {
    const version = ++statisticsVersion.current
    setStatisticsState({ kind: 'loading', request })
    try {
      const result = await apiClient.reportStatistics(request)
      if (version === statisticsVersion.current) {
        setStatisticsState({ kind: 'ready', request, result })
      }
    } catch (error) {
      if (version !== statisticsVersion.current || handleAccessError(error)) {
        return
      }
      setStatisticsState({ kind: 'failed', request, error })
    }
  }

  const orderJob = async (payload: ReportRequest, target: OrderTarget) => {
    if (ordering) {
      return
    }
    const serialized = JSON.stringify(payload)
    const key = orderKey.current?.payload === serialized ? orderKey.current.key : createIdempotencyKey()
    orderKey.current = { payload: serialized, key }
    setOrdering(true)
    setOrderError(null)
    setPollError(null)
    try {
      const created = await apiClient.createReportJob(payload, key)
      orderKey.current = null
      if (target === 'chart') {
        autoDownloadIds.current.add(created.jobId)
        setChartJobId(created.jobId)
      }
      void refreshJobs([created.jobId])
    } catch (error) {
      if (error instanceof ApiError) {
        orderKey.current = null
      }
      if (!handleAccessError(error)) {
        setOrderError({ target, error })
      }
    } finally {
      setOrdering(false)
    }
  }

  const orderFile = () => {
    if (!blocked) {
      void orderJob({ ...currentRequest, format: selection.format }, 'report')
    }
  }

  const orderChart = (request: StatisticsRequest, format: 'PNG' | 'PDF') => {
    void orderJob({ ...request, format }, 'chart')
  }

  const toggleColumn = (column: ReportColumn, checked: boolean) => {
    setSelection((current) => {
      const chosen = current.columns[current.kind]
      const next = reportColumns[current.kind].filter((item) => item === column ? checked : chosen.includes(item))
      return { ...current, columns: { ...current.columns, [current.kind]: next } }
    })
  }

  const resetSelection = () => {
    setSelection(defaultSelection())
  }

  const previewOutdated = previewState.kind === 'ready'
    && JSON.stringify(previewState.request) !== JSON.stringify(currentRequest)
  const statisticsOutdated = statisticsState.kind === 'ready'
    && JSON.stringify(statisticsState.request) !== JSON.stringify(currentStatisticsRequest)
  const chartBarCount = statisticsState.kind === 'ready' ? chartBars(statisticsState.result).length : 0
  const chartTooLarge = statisticsState.kind === 'ready' && chartBarCount > statisticsState.result.maxChartBars
  const chartJob = chartJobId === null || jobsState.kind !== 'ready'
    ? undefined
    : jobsState.jobs.find((job) => job.id === chartJobId)

  return (
    <section className="reports" aria-labelledby="reports-title">
      <h2 id="reports-title" className="reports__title">Отчёт по взаимодействиям</h2>

      <form className="reports__form" onSubmit={submitPreview}>
        <div className="reports__settings">
          <label>
            Вид отчёта
            <select
              value={selection.kind}
              onChange={(event) => {
                const kind = reportKinds.find((item) => item === event.target.value) ?? 'PORTFOLIO'
                update({ kind, groupBy: groupingsFor(kind).includes(selection.groupBy) ? selection.groupBy : 'PROGRAM' })
              }}
            >
              {reportKinds.map((kind) => <option key={kind} value={kind}>{kindLabels[kind]}</option>)}
            </select>
          </label>
          <label>
            Период с
            <input type="date" value={selection.from} max={selection.to || undefined} onChange={(event) => update({ from: event.target.value })} />
          </label>
          <label>
            по
            <input type="date" value={selection.to} min={selection.from || undefined} onChange={(event) => update({ to: event.target.value })} />
          </label>
          {selection.kind === 'DEMAND' && (
            <label>
              Сортировка
              <select
                value={selection.sortBy}
                onChange={(event) => update({ sortBy: demandSorts.find((sort) => sort === event.target.value) ?? 'APPLICATIONS' })}
              >
                {demandSorts.map((sort) => <option key={sort} value={sort}>{demandSortLabels[sort]}</option>)}
              </select>
            </label>
          )}
          {selection.kind === 'PORTFOLIO' && (
            <label>
              Отбор по периоду
              <select
                value={selection.periodBasis}
                onChange={(event) => update({ periodBasis: event.target.value === 'ACTIVITY' ? 'ACTIVITY' : 'CREATED' })}
              >
                <option value="CREATED">Созданные в периоде</option>
                <option value="ACTIVITY">С событиями в периоде</option>
              </select>
            </label>
          )}
        </div>
        <p className="reports__hint">
          {kindHints[selection.kind]} Даты включительно, время московское; пустая дата снимает ограничение.
        </p>

        {optionsState.kind === 'loading' && <p role="status">Загружаем значения фильтров…</p>}
        {optionsState.kind === 'failed' && (
          <ErrorNotice
            error={optionsState.error}
            message="Не удалось загрузить значения фильтров."
            onRetry={() => void loadOptions()}
          />
        )}
        {optionsState.kind === 'ready' && (
          <div className="reports__filters">
            <MultiSelect
              legend="Вузы"
              options={optionsState.options.organizations}
              selected={selection.organizationIds}
              onChange={(organizationIds) => update({ organizationIds })}
            />
            <MultiSelect
              legend="ИТ-направления"
              options={optionsState.options.directions}
              selected={selection.directionIds}
              onChange={(directionIds) => update({ directionIds })}
              includeNone={selection.includeNoDirection}
              onIncludeNoneChange={(includeNoDirection) => update({ includeNoDirection })}
            />
            <MultiSelect
              legend="ИТ-программы"
              options={optionsState.options.programs}
              selected={selection.programIds}
              onChange={(programIds) => update({ programIds })}
              includeNone={selection.includeNoProgram}
              onIncludeNoneChange={(includeNoProgram) => update({ includeNoProgram })}
            />
            {selection.kind !== 'DEMAND' && (
              <MultiSelect
                legend="ИТ-продукты"
                options={optionsState.options.products}
                selected={selection.productIds}
                onChange={(productIds) => update({ productIds })}
                includeNone={selection.includeNoProduct}
                onIncludeNoneChange={(includeNoProduct) => update({ includeNoProduct })}
              />
            )}
            <MultiSelect
              legend={selection.kind === 'EVENTS' ? 'Ответственный на момент события' : 'Ответственный'}
              options={optionsState.options.managers}
              selected={selection.managerIds}
              onChange={(managerIds) => update({ managerIds })}
              includeNone={selection.includeNoManager}
              onIncludeNoneChange={(includeNoManager) => update({ includeNoManager })}
            />
            {selection.kind !== 'DEMAND' && (
              <MultiSelect
                legend={selection.kind === 'EVENTS' ? 'Этап события' : 'Текущий этап'}
                options={optionsState.options.stages}
                selected={selection.stages}
                onChange={(stages) => update({ stages })}
              />
            )}
          </div>
        )}

        <fieldset className="reports__columns">
          <legend>Колонки</legend>
          <ul>
            {columns.map((column) => (
              <li key={column}>
                <label>
                  <input
                    type="checkbox"
                    checked={selectedColumns.includes(column)}
                    onChange={(event) => toggleColumn(column, event.target.checked)}
                  />
                  <span>{columnTitle(selection.kind, column)}</span>
                </label>
              </li>
            ))}
          </ul>
        </fieldset>

        {problem !== null && <p className="reports__problem" role="alert">{problem}</p>}

        <div className="reports__actions">
          <button type="submit" disabled={blocked || previewState.kind === 'loading'}>
            {previewState.kind === 'loading' ? 'Строим таблицу…' : 'Показать'}
          </button>
          <button type="button" className="reports__secondary" onClick={resetSelection}>Сбросить выбор</button>
        </div>
        <p className="reports__hint">Выбор фильтров и колонок хранится только в этой вкладке и удаляется при выходе из CRM.</p>
      </form>

      <section className="reports__preview" aria-labelledby="reports-preview-title" aria-busy={previewState.kind === 'loading'}>
        <h3 id="reports-preview-title">Предпросмотр</h3>
        {previewState.kind === 'idle' && <p>Нажмите «Показать», чтобы увидеть строки отчёта по выбранным фильтрам.</p>}
        {previewState.kind === 'loading' && <p role="status">Строим таблицу предпросмотра…</p>}
        {previewState.kind === 'failed' && (
          <ErrorNotice
            error={previewState.error}
            message="Не удалось построить предпросмотр."
            onRetry={() => void runPreview(previewState.request, 0)}
          />
        )}
        {previewState.kind === 'ready' && (
          <>
            {previewOutdated && (
              <p className="reports__outdated" role="status">Выбор изменён после построения таблицы. Нажмите «Показать», чтобы обновить её.</p>
            )}
            <p className="reports__total">Строк в отчёте: {previewState.preview.total}</p>
            <ul className="reports__notes">
              {previewState.preview.notes.map((note) => <li key={note}>{note}</li>)}
            </ul>
            {previewState.preview.total === 0 ? (
              <p>По выбранному периоду и фильтрам строк нет. Измените период или снимите часть фильтров.</p>
            ) : (
              <>
                <div className="reports__table-scroll" role="region" aria-label="Таблица предпросмотра" tabIndex={0}>
                  <table>
                    <thead>
                      <tr>
                        {previewState.preview.columns.map((column) => <th key={column.id} scope="col">{column.title}</th>)}
                      </tr>
                    </thead>
                    <tbody>
                      {previewState.preview.items.map((row, index) => (
                        <tr key={index}>
                          {previewState.preview.columns.map((column) => {
                            const value = row[column.id]
                            return (
                              <td key={column.id}>
                                {value === null || value === undefined
                                  ? column.emptyText
                                  : dateColumns.has(column.id) ? formatDateTime(moscowDateTime, String(value)) : value}
                              </td>
                            )
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="reports__pagination">
                  <button
                    type="button"
                    disabled={previewState.preview.page === 0}
                    onClick={() => void runPreview(previewState.request, previewState.preview.page - 1)}
                  >
                    Назад
                  </button>
                  <p>
                    Строки {previewState.preview.page * previewState.preview.size + 1}–
                    {previewState.preview.page * previewState.preview.size + previewState.preview.items.length} из {previewState.preview.total}
                  </p>
                  <button
                    type="button"
                    disabled={(previewState.preview.page + 1) * previewState.preview.size >= previewState.preview.total}
                    onClick={() => void runPreview(previewState.request, previewState.preview.page + 1)}
                  >
                    Вперёд
                  </button>
                </div>
              </>
            )}
          </>
        )}
      </section>

      <section className="reports__statistics" aria-labelledby="reports-statistics-title" aria-busy={statisticsState.kind === 'loading'}>
        <h3 id="reports-statistics-title">Статистика</h3>
        <p className="reports__hint">Диаграмма считается по тем же виду отчёта, периоду и фильтрам, что и таблица; выбор колонок на неё не влияет.</p>
        <div className="reports__order">
          <label>
            Группировка
            <select
              value={selection.groupBy}
              onChange={(event) => update({ groupBy: groupingsFor(selection.kind).find((groupBy) => groupBy === event.target.value) ?? 'STAGE' })}
            >
              {groupingsFor(selection.kind).map((groupBy) => <option key={groupBy} value={groupBy}>{groupingTitles[groupBy]}</option>)}
            </select>
          </label>
          <button
            type="button"
            onClick={() => void runStatistics(currentStatisticsRequest)}
            disabled={statisticsBlocked || statisticsState.kind === 'loading'}
          >
            {statisticsState.kind === 'loading' ? 'Считаем…' : 'Построить диаграмму'}
          </button>
        </div>
        {statisticsProblem !== null && <p className="reports__problem" role="alert">{statisticsProblem}</p>}
        {statisticsState.kind === 'idle' && <p>Выберите группировку и нажмите «Построить диаграмму».</p>}
        {statisticsState.kind === 'loading' && <p role="status">Считаем статистику…</p>}
        {statisticsState.kind === 'failed' && (
          <ErrorNotice
            error={statisticsState.error}
            message="Не удалось посчитать статистику."
            onRetry={() => void runStatistics(statisticsState.request)}
          />
        )}
        {statisticsState.kind === 'ready' && (
          <>
            {statisticsOutdated && (
              <p className="reports__outdated" role="status">Выбор изменён после построения диаграммы. Нажмите «Построить диаграмму», чтобы обновить её.</p>
            )}
            <StatisticsChart result={statisticsState.result} />
            <div className="reports__order">
              <button type="button" onClick={() => orderChart(statisticsState.request, 'PNG')} disabled={ordering || chartTooLarge}>Скачать PNG</button>
              <button type="button" onClick={() => orderChart(statisticsState.request, 'PDF')} disabled={ordering || chartTooLarge}>Скачать PDF</button>
            </div>
            {chartTooLarge ? (
              <p className="reports__problem" role="status">
                В файл PNG или PDF помещается не больше {statisticsState.result.maxChartBars} столбцов с учётом «Не указано», а в этой диаграмме {chartBarCount}. Сузьте период или фильтры либо выберите другую группировку.
              </p>
            ) : (
              <p className="reports__hint">
                Файл строится на сервере по параметрам показанной диаграммы и скачивается, когда готов. Он также остаётся в списке «Заказанные файлы».
              </p>
            )}
          </>
        )}
        {chartJob && (
          <p role="status">
            {jobTitle(chartJob)}: {jobStatusText(chartJob)}
            {chartJob.error ? `. Код: ${chartJob.error.code}. ${chartJob.error.message}` : ''}
          </p>
        )}
        {orderError?.target === 'chart' && <ErrorNotice error={orderError.error} message="Заказ файла диаграммы не принят." />}
      </section>

      <section className="reports__files" aria-labelledby="reports-files-title">
        <h3 id="reports-files-title">Файл отчёта</h3>
        <div className="reports__order">
          <label>
            Формат
            <select
              value={selection.format}
              onChange={(event) => update({ format: reportFormats.find((format) => format === event.target.value) ?? 'XLSX' })}
            >
              {reportFormats.map((format) => <option key={format} value={format}>{format}</option>)}
            </select>
          </label>
          <button type="button" onClick={orderFile} disabled={blocked || ordering}>
            {ordering ? 'Отправляем заказ…' : 'Сформировать файл'}
          </button>
        </div>
        <p className="reports__hint">Файл строится в фоне по тем же фильтрам и колонкам. Пока он строится, можно работать в других разделах.</p>
        {orderError?.target === 'report' && <ErrorNotice error={orderError.error} message="Заказ файла не принят." />}

        <div className="reports__jobs-header">
          <h4>Заказанные файлы</h4>
          <button type="button" className="reports__secondary" onClick={() => void loadJobs()} disabled={jobsState.kind === 'loading'}>
            Обновить список
          </button>
        </div>
        {pollError !== null && (
          <ErrorNotice error={pollError} message="Не удалось получить состояние файла." onRetry={() => void loadJobs()} retryLabel="Обновить список" />
        )}
        {jobsState.kind === 'loading' && <p role="status">Загружаем заказанные файлы…</p>}
        {jobsState.kind === 'failed' && (
          <ErrorNotice error={jobsState.error} message="Не удалось загрузить заказанные файлы." onRetry={() => void loadJobs()} />
        )}
        {jobsState.kind === 'ready' && jobsState.jobs.length === 0 && <p>Файлов пока нет.</p>}
        {jobsState.kind === 'ready' && jobsState.jobs.length > 0 && (
          <ul className="reports__jobs" aria-live="polite">
            {jobsState.jobs.map((job) => (
              <li key={job.id}>
                <div className="reports__job-title">
                  <strong>{jobTitle(job)}</strong>
                  <span className={`reports__job-status reports__job-status--${job.status.toLowerCase()}`}>{jobStatusText(job)}</span>
                </div>
                <p>Заказан {formatDateTime(localDateTime, job.createdAt)}</p>
                {job.status === 'FAILED' && job.error && (
                  <p className="reports__job-error">Код: {job.error.code}. {job.error.message}</p>
                )}
                {job.resultReady && (
                  <button
                    type="button"
                    onClick={() => void downloadJob(job)}
                    disabled={downloadState.kind === 'downloading' && downloadState.id === job.id}
                  >
                    {downloadState.kind === 'downloading' && downloadState.id === job.id ? 'Готовим скачивание…' : 'Скачать'}
                  </button>
                )}
                {downloadState.kind === 'failed' && downloadState.id === job.id && (
                  <ErrorNotice error={downloadState.error} message="Файл не скачан." onRetry={() => void downloadJob(job)} />
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </section>
  )
}
