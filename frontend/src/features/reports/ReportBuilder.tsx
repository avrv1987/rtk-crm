import { useEffect, useRef, useState, type Dispatch, type FormEvent, type ReactNode, type SetStateAction } from 'react'
import {
  apiClient,
  type Me,
  type ReportColumn,
  type ReportFormat,
  type ReportJob,
  type ReportKind,
  type ReportPreview,
  type ReportPreviewRequest,
  type ReportRequest,
  type SavedReport,
  type StatisticsRequest,
  type StatisticsResult
} from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { CardMenu, CardTabPanel, CardTabs } from '../interactions/cardUi'
import { organizationTypeLabels } from '../organizations/OrganizationForm'
import { ColumnOrder } from './ColumnOrder'
import { LineChart, chartLines } from './LineChart'
import { agreementValues, ReportAgreementFilter } from './ReportAgreementFilter'
import {
  AsOfPicker,
  ChoiceChip,
  InfoTip,
  MultiChip,
  PeriodPicker,
  SelectedValues,
  type FilterOption,
  type SelectedValue
} from './ReportControls'
import { ErrorNotice } from './ReportErrorNotice'
import { JobProgress, jobTitle } from './ReportJobs'
import {
  chartKinds,
  columnTitle,
  defaultSelection,
  demandSorts,
  eventTypeTitles,
  eventTypes,
  groupingProblem,
  groupingTitles,
  groupingsFor,
  kindLabels,
  lineChartSelected,
  maxDaysOnStage,
  organizationTypeFilters,
  periodProblem,
  reportColumns,
  reportFlagLabels,
  reportFlags,
  reportWorkStatusLabels,
  reportWorkStatuses,
  selectionProblem,
  seriesGroupingsFor,
  toPreviewRequest,
  toStatisticsRequest,
  type ReportSelection
} from './reportSelection'
import { SaveReportDialog, type SavedReportsModel } from './SavedReports'
import { StatisticsChart, chartBars } from './StatisticsChart'

export type FilterOptions = {
  organizations: FilterOption[]
  directions: FilterOption[]
  programs: FilterOption[]
  products: FilterOption[]
  managers: FilterOption[]
  stages: FilterOption[]
  vendors: FilterOption[]
}

export type OptionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; options: FilterOptions }
  | { kind: 'failed'; error: unknown }

export type OrderTarget = 'report' | 'chart'

type PreviewState =
  | { kind: 'idle' }
  | { kind: 'loading'; request: ReportPreviewRequest }
  | { kind: 'ready'; request: ReportPreviewRequest; preview: ReportPreview }
  | { kind: 'failed'; request: ReportPreviewRequest; error: unknown }

type StatisticsState =
  | { kind: 'idle' }
  | { kind: 'loading'; request: StatisticsRequest; line: boolean }
  | { kind: 'ready'; request: StatisticsRequest; line: boolean; result: StatisticsResult }
  | { kind: 'failed'; request: StatisticsRequest; line: boolean; error: unknown }

type ResultTab = 'table' | 'chart'

const previewPageSize = 50

const kindHints: Record<ReportKind, string> = {
  PORTFOLIO: 'Строка — взаимодействие. Этап и ответственный — текущие на момент формирования; «Дней на этапе» — полных суток с последнего входа в текущий этап, отбор «На этапе дольше» оставляет работы, которые стоят на этапе не меньше указанного числа дней. «Активные в периоде» — созданные до конца периода и не завершённые к его началу.',
  EVENTS: 'Строка — событие: создание, переход, комментарий, изменение этапов или плана, а также назначение, смена и снятие КАМ вуза. Ответственный — КАМ на момент события.',
  SNAPSHOT: 'Строка — взаимодействие, созданное до конца выбранного дня. Этап и ответственный восстановлены по истории переходов и назначений КАМ на эту дату; ИТ-программа и ИТ-продукты — текущие.',
  DURATION: 'Строка — команда, ИТ-программа (итог команды — «Все программы») и этап или «Весь цикл». Средняя и максимальная длительность в календарных днях — по прохождениям, завершённым в периоде, по истории переходов; отдельно — сколько работ ещё на этапе на конец периода и самая долгая из них. Диаграмма для этого отчёта не строится: показатели уже сгруппированы в таблице.',
  DEMAND: 'Строка — ИТ-программа. Заявки — заявки на обучение с сайта по предложенному контракту (демонстрационный стенд) в ваших вузах, период — по дате подачи. Обучающиеся и завершившие (участия, не уникальные люди) — потоки занятий студентов в Moodle, которые шли в периоде хотя бы один день: у идущего потока — последнее наблюдение, у закрытого — последнее до окончания; обучение преподавателей не учитывается. Доля завершивших — завершившие к обучающимся тех же потоков, округлена до целого; потоки, где Moodle не отслеживает завершение, в неё не входят, а без таких потоков показано «нет данных». Оплаченные заявки и потоки с оплатами — оплаты физлиц на открытые курсы («Открытый набор (физлица)»), их видят все КАМ и руководители; период к ним не применяется: сайт не передаёт дату оплаты, поэтому они показаны за всё время и с заявками не складываются. Дата снимка Moodle и период заявок указаны в заголовках колонок. Параллельные потоки — потоки, которые идут в последний день периода, а если он не задан — сегодня. «Нет данных» — источник не дал значения, это не ноль. Единого рейтинга нет: выберите показатель для сортировки.',
  AGREEMENTS: 'Строка — мероприятие плана соглашения с вузом; соглашение без мероприятий выводится одной строкой. Период отбирает мероприятия, чьи фактические сроки, а если их нет — плановые, иначе срок соглашения, пересекаются с периодом. Объёмы — числа без ФИО; подтверждения — проверенные документы, привязанные к мероприятию, со ссылками для скачивания после входа в CRM. Фильтр «Ответственный» — ответственный за мероприятие. Диаграмма для этого отчёта не строится.'
}

const managerEventsHint = 'Строка — событие: создание, переход, комментарий, изменение этапов или плана. Ответственный — КАМ на момент события. История назначений КАМ видна руководителю команды.'

const statisticsHint = 'Диаграмма считается по тем же виду данных, периоду и фильтрам, что и таблица; выбор колонок на неё не влияет. Шкала начинается с нуля, «Не указано» показано отдельно и не смешивается с нулём.'

const demandSortLabels: Record<(typeof demandSorts)[number], string> = {
  APPLICATIONS: 'по заявкам',
  PAID_ORDERS: 'по оплаченным заявкам',
  PAID_STREAMS: 'по потокам с оплатами',
  PARTICIPANTS: 'по обучающимся',
  LEARNERS_COMPLETED: 'по завершившим',
  PARALLEL_RUNS: 'по параллельным потокам'
}

const periodBasisOptions: { value: ReportSelection['periodBasis']; label: string }[] = [
  { value: 'CREATED', label: 'Созданные в периоде' },
  { value: 'ACTIVITY', label: 'С событиями в периоде' },
  { value: 'ACTIVE', label: 'Активные в периоде' }
]

const stageLegends: Record<ReportKind, string> = {
  PORTFOLIO: 'Этап',
  EVENTS: 'Этап события',
  SNAPSHOT: 'Этап на дату',
  DURATION: 'Этап',
  DEMAND: 'Этап',
  AGREEMENTS: 'Этап'
}

const managerLegends: Record<ReportKind, string> = {
  PORTFOLIO: 'Ответственный',
  EVENTS: 'Ответственный на момент события',
  SNAPSHOT: 'Ответственный на дату',
  DURATION: 'Ответственный (текущий КАМ вуза)',
  DEMAND: 'Ответственный',
  AGREEMENTS: 'Ответственный за мероприятие'
}

const dateColumns: ReadonlySet<ReportColumn> = new Set<ReportColumn>(['CREATED_AT', 'LAST_EVENT_AT', 'NEXT_ACTION_AT', 'EVENT_AT'])

const cellText = (value: unknown, column: ReportColumn) => {
  if (dateColumns.has(column)) {
    return formatMoscowDateTime(String(value))
  }
  return typeof value === 'number' ? value.toLocaleString('ru-RU') : String(value)
}

const eventTypeOptions = (role: Me['role']): FilterOption[] => eventTypes
  .filter((type) => role === 'LEADER' || role === 'MANAGEMENT' || type !== 'ASSIGNMENT')
  .map((type) => ({ id: type, label: eventTypeTitles[type] }))

const organizationTypeOptions = organizationTypeFilters.map((type) => ({
  id: type,
  label: type === '' ? 'Все типы' : organizationTypeLabels[type]
}))

const focusResult = (id: string) => {
  const target = document.getElementById(id)
  if (target === null) {
    return
  }
  target.scrollIntoView({ block: 'start' })
  target.focus({ preventScroll: true })
}

const labelOf = (options: FilterOption[], id: string) => options.find((option) => option.id === id)?.label ?? id

type ReportBuilderProps = {
  role: Me['role']
  selection: ReportSelection
  setSelection: Dispatch<SetStateAction<ReportSelection>>
  statisticsMode: boolean
  optionsState: OptionsState
  onReloadOptions: () => void
  onAccessError: (error: unknown) => boolean
  onOrder: (payload: ReportRequest, target: OrderTarget) => void
  ordering: boolean
  orderError: { target: OrderTarget; error: unknown } | null
  orderNotice: string
  chartJob: ReportJob | undefined
  saved: SavedReportsModel
  currentReport: SavedReport | undefined
  onSaved: (report: SavedReport) => void
  autoRun: boolean
  onAutoRunDone: () => void
  jobs: ReactNode
}

export const ReportBuilder = ({
  role,
  selection,
  setSelection,
  statisticsMode,
  optionsState,
  onReloadOptions,
  onAccessError,
  onOrder,
  ordering,
  orderError,
  orderNotice,
  chartJob,
  saved,
  currentReport,
  onSaved,
  autoRun,
  onAutoRunDone,
  jobs
}: ReportBuilderProps) => {
  const [previewState, setPreviewState] = useState<PreviewState>({ kind: 'idle' })
  const [statisticsState, setStatisticsState] = useState<StatisticsState>({ kind: 'idle' })
  const [tab, setTab] = useState<ResultTab>(statisticsMode ? 'chart' : 'table')
  const [saving, setSaving] = useState(false)
  const previewVersion = useRef(0)
  const statisticsVersion = useRef(0)
  const moreRef = useRef<HTMLDetailsElement>(null)

  const kind = selection.kind
  const chartable = chartKinds.includes(kind)
  const activeTab: ResultTab = chartable ? tab : 'table'
  const problem = selectionProblem(selection)
  const blocked = problem !== null || optionsState.kind !== 'ready'
  const currentRequest = toPreviewRequest(selection)
  const statisticsProblem = groupingProblem(selection)
  const statisticsBlocked = periodProblem(selection) !== null || statisticsProblem !== null || optionsState.kind !== 'ready'
  const currentStatisticsRequest = toStatisticsRequest(selection)
  const currentLine = lineChartSelected(selection)
  const columns = reportColumns[kind]
  const selectedColumns = selection.columns[kind]

  const update = (patch: Partial<ReportSelection>) => {
    setSelection((current) => ({ ...current, ...patch }))
  }

  useEffect(() => () => {
    previewVersion.current += 1
    statisticsVersion.current += 1
  }, [])

  const runPreview = async (request: ReportPreviewRequest, page: number) => {
    const version = ++previewVersion.current
    setPreviewState({ kind: 'loading', request })
    try {
      const preview = await apiClient.previewReport(request, page, previewPageSize)
      if (version === previewVersion.current) {
        setPreviewState({ kind: 'ready', request, preview })
        if (page === 0) {
          requestAnimationFrame(() => focusResult('reports-preview-total'))
        }
      }
    } catch (error) {
      if (version !== previewVersion.current || onAccessError(error)) {
        return
      }
      setPreviewState({ kind: 'failed', request, error })
    }
  }

  const runStatistics = async (request: StatisticsRequest, line: boolean) => {
    const version = ++statisticsVersion.current
    setStatisticsState({ kind: 'loading', request, line })
    try {
      const result = await apiClient.reportStatistics(request)
      if (version === statisticsVersion.current) {
        setStatisticsState({ kind: 'ready', request, line, result })
        requestAnimationFrame(() => focusResult('reports-statistics-result'))
      }
    } catch (error) {
      if (version !== statisticsVersion.current || onAccessError(error)) {
        return
      }
      setStatisticsState({ kind: 'failed', request, line, error })
    }
  }

  useEffect(() => {
    if (autoRun && optionsState.kind === 'ready') {
      onAutoRunDone()
      if (selectionProblem(selection) === null) {
        void runPreview(toPreviewRequest(selection), 0)
      }
    }
  })

  const show = () => {
    if (activeTab === 'chart') {
      if (!statisticsBlocked) {
        void runStatistics(currentStatisticsRequest, currentLine)
      }
      return
    }
    if (!blocked) {
      void runPreview(currentRequest, 0)
    }
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    show()
  }

  const switchTab = (next: ResultTab) => {
    setTab(next)
    if (next === 'chart' && statisticsState.kind === 'idle' && !statisticsBlocked) {
      void runStatistics(currentStatisticsRequest, currentLine)
    }
  }

  const orderFile = (format: ReportFormat) => {
    if (!blocked) {
      onOrder({ ...currentRequest, format }, 'report')
    }
  }

  const orderChart = (request: StatisticsRequest, line: boolean, format: 'PNG' | 'PDF') => {
    onOrder({ ...request, format, chartType: line ? 'LINE' : 'BAR' }, 'chart')
  }

  const openGrouping = () => {
    if (moreRef.current !== null) {
      moreRef.current.open = true
      requestAnimationFrame(() => document.getElementById('report-grouping')?.focus())
    }
  }

  const setColumns = (next: ReportColumn[]) => {
    setSelection((current) => ({ ...current, columns: { ...current.columns, [current.kind]: next } }))
  }

  const toggleColumn = (column: ReportColumn, checked: boolean) => {
    setSelection((current) => {
      const chosen = current.columns[current.kind].filter((item) => item !== column)
      return { ...current, columns: { ...current.columns, [current.kind]: checked ? [...chosen, column] : chosen } }
    })
  }

  const previewOutdated = previewState.kind === 'ready'
    && JSON.stringify(previewState.request) !== JSON.stringify(currentRequest)
  const statisticsOutdated = statisticsState.kind === 'ready'
    && (JSON.stringify(statisticsState.request) !== JSON.stringify(currentStatisticsRequest) || statisticsState.line !== currentLine)
  const chartLimit = (() => {
    if (statisticsState.kind !== 'ready') {
      return null
    }
    const { result, line } = statisticsState
    if (line && chartLines(result).length > result.maxChartSeries) {
      return `В файл PNG или PDF помещается не больше ${result.maxChartSeries} линий, а на этом графике ${chartLines(result).length}. Оставьте меньше значений в фильтре или выберите другую разбивку на линии.`
    }
    const count = line ? result.items.length : chartBars(result).length
    return count > result.maxChartBars
      ? `В файл PNG или PDF помещается не больше ${result.maxChartBars} ${line ? 'месяцев' : 'столбцов с учётом «Не указано»'}, а здесь ${count}. Сузьте период или фильтры либо выберите другую группировку.`
      : null
  })()

  const options = optionsState.kind === 'ready' ? optionsState.options : null
  const showProgramFilters = kind !== 'AGREEMENTS'
  const showProductFilters = kind !== 'DEMAND' && kind !== 'AGREEMENTS'
  const workStatusOptions = reportWorkStatuses.map((status) => ({ id: status, label: reportWorkStatusLabels[status] }))
  const flagOptions = reportFlags.map((flag) => ({ id: flag, label: reportFlagLabels[flag] }))

  const selectedValues: SelectedValue[] = options === null ? [] : [
    ...(selection.organizationType === '' ? [] : [{
      key: 'type',
      text: `Тип: ${organizationTypeLabels[selection.organizationType]}`,
      onRemove: () => update({ organizationType: '' })
    }]),
    ...selection.organizationIds.map((id) => ({
      key: `org-${id}`,
      text: labelOf(options.organizations, id),
      onRemove: () => update({ organizationIds: selection.organizationIds.filter((item) => item !== id) })
    })),
    ...(showProgramFilters ? [
      ...(selection.includeNoDirection ? [{ key: 'dir-none', text: 'Направление: не указано', onRemove: () => update({ includeNoDirection: false }) }] : []),
      ...selection.directionIds.map((id) => ({
        key: `dir-${id}`,
        text: labelOf(options.directions, id),
        onRemove: () => update({ directionIds: selection.directionIds.filter((item) => item !== id) })
      })),
      ...(selection.includeNoProgram ? [{ key: 'prog-none', text: 'Программа: не указано', onRemove: () => update({ includeNoProgram: false }) }] : []),
      ...selection.programIds.map((id) => ({
        key: `prog-${id}`,
        text: labelOf(options.programs, id),
        onRemove: () => update({ programIds: selection.programIds.filter((item) => item !== id) })
      }))
    ] : []),
    ...(showProductFilters ? [
      ...(selection.includeNoProduct ? [{ key: 'prod-none', text: 'Продукт: не указано', onRemove: () => update({ includeNoProduct: false }) }] : []),
      ...selection.productIds.map((id) => ({
        key: `prod-${id}`,
        text: labelOf(options.products, id),
        onRemove: () => update({ productIds: selection.productIds.filter((item) => item !== id) })
      }))
    ] : []),
    ...(selection.includeNoManager ? [{ key: 'man-none', text: 'Ответственный: не указан', onRemove: () => update({ includeNoManager: false }) }] : []),
    ...selection.managerIds.map((id) => ({
      key: `man-${id}`,
      text: labelOf(options.managers, id),
      onRemove: () => update({ managerIds: selection.managerIds.filter((item) => item !== id) })
    })),
    ...(showProductFilters ? selection.stages.map((stage) => ({
      key: `stage-${stage}`,
      text: `Этап: ${stage}`,
      onRemove: () => update({ stages: selection.stages.filter((item) => item !== stage) })
    })) : []),
    ...(kind === 'PORTFOLIO' ? [
      ...selection.workStatuses.map((status) => ({
        key: `status-${status}`,
        text: reportWorkStatusLabels[status],
        onRemove: () => update({ workStatuses: selection.workStatuses.filter((item) => item !== status) })
      })),
      ...selection.flags.map((flag) => ({
        key: `flag-${flag}`,
        text: reportFlagLabels[flag],
        onRemove: () => update({ flags: selection.flags.filter((item) => item !== flag) })
      }))
    ] : []),
    ...(kind === 'EVENTS' ? selection.eventTypes.map((type) => ({
      key: `event-${type}`,
      text: eventTypeTitles[type],
      onRemove: () => update({ eventTypes: selection.eventTypes.filter((item) => item !== type) })
    })) : []),
    ...(showProductFilters ? agreementValues(selection.agreement, options.vendors, (agreement) => update({ agreement })) : [])
  ]

  const title = statisticsMode ? 'Статистика и диаграммы' : kindLabels[kind]
  const hint = kind === 'EVENTS' && role !== 'LEADER' ? managerEventsHint : kindHints[kind]

  return (
    <section className="report-builder" aria-labelledby="report-builder-title">
      <p className="report-builder__back"><a href="#/reports">← Все отчёты</a></p>
      <div className="report-builder__header">
        <h2 id="report-builder-title" className="reports__section-title">{title}</h2>
        <InfoTip label={statisticsMode ? 'Статистика' : title}>{statisticsMode ? statisticsHint : hint}</InfoTip>
        {currentReport !== undefined && <span className="report-builder__saved">Сохранённый отчёт «{currentReport.name}»</span>}
      </div>

      <form className="reports__form report-builder__panel" onSubmit={submit}>
        <div className="report-builder__main">
          {statisticsMode && (
            <div className="report-field">
              <label htmlFor="report-chart-kind">Данные</label>
              <select
                id="report-chart-kind"
                value={kind}
                onChange={(event) => {
                  const next = chartKinds.find((item) => item === event.target.value) ?? 'PORTFOLIO'
                  update({ kind: next, groupBy: groupingsFor(next).includes(selection.groupBy) ? selection.groupBy : 'PROGRAM' })
                }}
              >
                {chartKinds.map((item) => <option key={item} value={item}>{kindLabels[item]}</option>)}
              </select>
            </div>
          )}
          {kind === 'SNAPSHOT' ? (
            <AsOfPicker value={selection.asOf} onChange={(asOf) => update({ asOf })} />
          ) : (
            <PeriodPicker from={selection.from} to={selection.to} onChange={(from, to) => update({ from, to })} />
          )}
          {kind === 'PORTFOLIO' && (
            <div className="report-field">
              <span className="report-field__label">
                <label htmlFor="report-period-basis">Отбор по периоду</label>
                <InfoTip label="Отбор по периоду">
                  «Созданные в периоде» — по дате создания работы; «С событиями в периоде» — были любые события;
                  «Активные в периоде» — созданные до конца периода и не завершённые к его началу.
                </InfoTip>
              </span>
              <select
                id="report-period-basis"
                value={selection.periodBasis}
                onChange={(event) => update({
                  periodBasis: periodBasisOptions.find((option) => option.value === event.target.value)?.value ?? 'CREATED'
                })}
              >
                {periodBasisOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            </div>
          )}
          {kind === 'DEMAND' && (
            <div className="report-field">
              <label htmlFor="report-demand-sort">Сортировка</label>
              <select
                id="report-demand-sort"
                value={selection.sortBy}
                onChange={(event) => update({ sortBy: demandSorts.find((sort) => sort === event.target.value) ?? 'APPLICATIONS' })}
              >
                {demandSorts.map((sort) => <option key={sort} value={sort}>{demandSortLabels[sort]}</option>)}
              </select>
            </div>
          )}
        </div>

        <div className="report-filters" role="group" aria-label="Фильтры">
          {optionsState.kind === 'loading' && <p className="report-empty report-empty--inline" role="status">Загружаем значения фильтров…</p>}
          {optionsState.kind === 'failed' && (
            <ErrorNotice error={optionsState.error} message="Не удалось загрузить значения фильтров." onRetry={onReloadOptions} />
          )}
          {options !== null && (
            <>
              <div className="report-filters__chips">
                <span className="report-filters__title">Фильтры</span>
                <ChoiceChip
                  label="Тип организации"
                  options={organizationTypeOptions}
                  value={selection.organizationType}
                  onChange={(organizationType) => update({ organizationType })}
                />
                <MultiChip
                  label="Вузы"
                  options={options.organizations}
                  selected={selection.organizationIds}
                  onChange={(organizationIds) => update({ organizationIds })}
                />
                {showProgramFilters && (
                  <>
                    <MultiChip
                      label="ИТ-направления"
                      options={options.directions}
                      selected={selection.directionIds}
                      onChange={(directionIds) => update({ directionIds })}
                      includeNone={selection.includeNoDirection}
                      onIncludeNoneChange={(includeNoDirection) => update({ includeNoDirection })}
                    />
                    <MultiChip
                      label="ИТ-программы"
                      options={options.programs}
                      selected={selection.programIds}
                      onChange={(programIds) => update({ programIds })}
                      includeNone={selection.includeNoProgram}
                      onIncludeNoneChange={(includeNoProgram) => update({ includeNoProgram })}
                    />
                  </>
                )}
                {showProductFilters && (
                  <MultiChip
                    label="ИТ-продукты"
                    options={options.products}
                    selected={selection.productIds}
                    onChange={(productIds) => update({ productIds })}
                    includeNone={selection.includeNoProduct}
                    onIncludeNoneChange={(includeNoProduct) => update({ includeNoProduct })}
                  />
                )}
                <MultiChip
                  label={managerLegends[kind]}
                  options={options.managers}
                  selected={selection.managerIds}
                  onChange={(managerIds) => update({ managerIds })}
                  includeNone={selection.includeNoManager}
                  onIncludeNoneChange={(includeNoManager) => update({ includeNoManager })}
                />
                {showProductFilters && (
                  <MultiChip
                    label={stageLegends[kind]}
                    options={options.stages}
                    selected={selection.stages}
                    onChange={(stages) => update({ stages })}
                  />
                )}
                {kind === 'PORTFOLIO' && (
                  <>
                    <MultiChip
                      label="Статус работы"
                      options={workStatusOptions}
                      selected={selection.workStatuses}
                      onChange={(ids) => update({ workStatuses: reportWorkStatuses.filter((status) => ids.includes(status)) })}
                    />
                    <MultiChip
                      label="Отметки работы"
                      note="Остаются работы, у которых есть все выбранные отметки одновременно."
                      options={flagOptions}
                      selected={selection.flags}
                      onChange={(ids) => update({ flags: reportFlags.filter((flag) => ids.includes(flag)) })}
                    />
                  </>
                )}
                {kind === 'EVENTS' && (
                  <MultiChip
                    label="Вид события"
                    options={eventTypeOptions(role)}
                    selected={selection.eventTypes}
                    onChange={(ids) => update({ eventTypes: eventTypes.filter((type) => ids.includes(type)) })}
                  />
                )}
                {showProductFilters && (
                  <ReportAgreementFilter
                    value={selection.agreement}
                    vendors={options.vendors}
                    onChange={(agreement) => update({ agreement })}
                  />
                )}
              </div>
              <SelectedValues values={selectedValues} />
            </>
          )}
        </div>

        <details ref={moreRef} className="report-more">
          <summary>Ещё настройки <span className="report-more__summary">колонки и порядок{kind === 'PORTFOLIO' ? ', «на этапе дольше»' : ''}{chartable ? ', группировка диаграммы' : ''}</span></summary>
          <div className="report-more__body">
            {(kind === 'PORTFOLIO' || chartable) && (
              <div className="report-more__row">
                {kind === 'PORTFOLIO' && (
                  <div className="report-field">
                    <span className="report-field__label">
                      <label htmlFor="report-min-days">На этапе дольше, дней</label>
                      <InfoTip label="На этапе дольше">
                        Оставляет работы, которые стоят на текущем этапе не меньше указанного числа полных суток. Пустое поле — любой срок.
                      </InfoTip>
                    </span>
                    <input
                      id="report-min-days"
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={maxDaysOnStage}
                      step={1}
                      value={selection.minDaysOnStage}
                      placeholder="любой срок"
                      onChange={(event) => update({ minDaysOnStage: event.target.value })}
                    />
                  </div>
                )}
                {chartable && (
                  <>
                    <div className="report-field">
                      <label htmlFor="report-grouping">Группировка диаграммы</label>
                      <select
                        id="report-grouping"
                        value={selection.groupBy}
                        onChange={(event) => update({ groupBy: groupingsFor(kind).find((groupBy) => groupBy === event.target.value) ?? 'STAGE' })}
                      >
                        {groupingsFor(kind).map((groupBy) => <option key={groupBy} value={groupBy}>{groupingTitles[groupBy]}</option>)}
                      </select>
                    </div>
                    {selection.groupBy === 'MONTH' && (
                      <div className="report-field">
                        <label htmlFor="report-chart-type">Вид отображения</label>
                        <select
                          id="report-chart-type"
                          value={selection.chartType}
                          onChange={(event) => update({ chartType: event.target.value === 'LINE' ? 'LINE' : 'BAR' })}
                        >
                          <option value="BAR">Столбцы</option>
                          <option value="LINE">График (линия)</option>
                        </select>
                      </div>
                    )}
                    {currentLine && (
                      <div className="report-field">
                        <label htmlFor="report-series">Линии</label>
                        <select
                          id="report-series"
                          value={currentStatisticsRequest.seriesBy ?? ''}
                          onChange={(event) => update({
                            seriesBy: seriesGroupingsFor(kind).find((groupBy) => groupBy === event.target.value) ?? null
                          })}
                        >
                          <option value="">Одна линия: всего</option>
                          {seriesGroupingsFor(kind).map((groupBy) => (
                            <option key={groupBy} value={groupBy}>Отдельно {groupingTitles[groupBy]}</option>
                          ))}
                        </select>
                      </div>
                    )}
                  </>
                )}
              </div>
            )}
            {chartable && statisticsProblem !== null && <p className="reports__problem" role="alert">{statisticsProblem}</p>}
            <fieldset className="report-columns">
              <legend>Колонки таблицы и файла</legend>
              <ul>
                {columns.map((column) => (
                  <li key={column}>
                    <label className="report-chip__option">
                      <input
                        type="checkbox"
                        checked={selectedColumns.includes(column)}
                        onChange={(event) => toggleColumn(column, event.target.checked)}
                      />
                      <span>{columnTitle(kind, column)}</span>
                    </label>
                  </li>
                ))}
              </ul>
              <ColumnOrder kind={kind} columns={selectedColumns} onChange={setColumns} />
            </fieldset>
            <div className="report-more__reset">
              <button type="button" className="reports__secondary" onClick={() => setSelection({ ...defaultSelection(), kind })}>
                Сбросить выбор
              </button>
              <span className="reports__hint">Вернёт период «Этот месяц», все фильтры и колонки по умолчанию.</span>
            </div>
          </div>
        </details>

        {problem !== null && <p className="reports__problem" role="alert">{problem}</p>}

        <div className="report-actions reports__actions">
          <button type="submit" disabled={activeTab === 'chart'
            ? statisticsBlocked || statisticsState.kind === 'loading'
            : blocked || previewState.kind === 'loading'}
          >
            {previewState.kind === 'loading' || statisticsState.kind === 'loading' ? 'Строим…' : 'Показать'}
          </button>
          <button type="button" className="reports__secondary" aria-label="Выгрузить XLSX" onClick={() => orderFile('XLSX')} disabled={blocked || ordering}>
            <span className="report-actions__verb">Выгрузить </span>XLSX
          </button>
          <button type="button" className="reports__secondary" aria-label="Выгрузить PDF" onClick={() => orderFile('PDF')} disabled={blocked || ordering}>
            <span className="report-actions__verb">Выгрузить </span>PDF
          </button>
          {!blocked && !ordering && (
            <CardMenu
              label="Другие форматы файла"
              items={[
                { label: 'Выгрузить XLS (старый Excel)', onSelect: () => orderFile('XLS') },
                { label: 'Выгрузить JSON (для обработки программами)', onSelect: () => orderFile('JSON') }
              ]}
            />
          )}
          <button type="button" className="reports__secondary report-actions__save" onClick={() => setSaving(true)}>
            Сохранить отчёт
          </button>
          {ordering && <span className="report-actions__status" role="status">Отправляем заказ…</span>}
          {!ordering && orderNotice !== '' && <span className="report-actions__status" role="status">{orderNotice}</span>}
          {!ordering && orderNotice === '' && saved.notice !== '' && <span className="report-actions__status" role="status">{saved.notice}</span>}
        </div>
      </form>

      {orderError?.target === 'report' && <ErrorNotice error={orderError.error} message="Заказ файла не принят." />}

      <section className="report-result" aria-label="Результат отчёта">
        {chartable && (
          <CardTabs
            label="Вид результата"
            idPrefix="report-result"
            tabs={[{ id: 'table', label: 'Таблица' }, { id: 'chart', label: 'Диаграмма' }]}
            active={activeTab}
            onChange={switchTab}
          />
        )}
        <CardTabPanel idPrefix="report-result" active={activeTab}>
          {activeTab === 'table' ? (
            <div className="reports__preview" aria-busy={previewState.kind === 'loading'}>
              {previewState.kind === 'idle' && (
                <p className="report-empty">Задайте период и фильтры и нажмите «Показать» — строки отчёта появятся здесь.</p>
              )}
              {previewState.kind === 'loading' && <p className="report-empty report-loading" role="status">Строим таблицу…</p>}
              {previewState.kind === 'failed' && (
                <ErrorNotice
                  error={previewState.error}
                  message="Не удалось построить таблицу."
                  onRetry={() => void runPreview(previewState.request, 0)}
                />
              )}
              {previewState.kind === 'ready' && (
                <>
                  {previewOutdated && (
                    <p className="reports__outdated" role="status">Выбор изменён после построения таблицы. Нажмите «Показать», чтобы обновить её.</p>
                  )}
                  <p className="reports__total" id="reports-preview-total" tabIndex={-1}>Строк в отчёте: {previewState.preview.total}</p>
                  {previewState.preview.notes.length > 0 && (
                    <ul className="reports__notes">
                      {previewState.preview.notes.map((note) => <li key={note}>{note}</li>)}
                    </ul>
                  )}
                  {previewState.preview.total === 0 ? (
                    <p className="report-empty">По выбранному периоду и фильтрам строк нет. Измените период или снимите часть фильтров.</p>
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
                                      {value === null || value === undefined ? column.emptyText : cellText(value, column.id)}
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
                          className="reports__secondary"
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
                          className="reports__secondary"
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
            </div>
          ) : (
            <div className="reports__statistics" aria-busy={statisticsState.kind === 'loading'}>
              <div className="report-chart__toolbar">
                <span>
                  Диаграмма {groupingTitles[selection.groupBy]}
                  {currentLine ? ', график' : ''}
                </span>
                <button type="button" className="reports__secondary" onClick={openGrouping}>Изменить группировку</button>
                <InfoTip label="Диаграмма">{statisticsHint}</InfoTip>
              </div>
              {statisticsProblem !== null && <p className="reports__problem" role="alert">{statisticsProblem}</p>}
              {statisticsState.kind === 'idle' && statisticsProblem === null && (
                <p className="report-empty">Нажмите «Показать», чтобы построить диаграмму {groupingTitles[selection.groupBy]}.</p>
              )}
              {statisticsState.kind === 'loading' && <p className="report-empty report-loading" role="status">Считаем статистику…</p>}
              {statisticsState.kind === 'failed' && (
                <ErrorNotice
                  error={statisticsState.error}
                  message="Не удалось посчитать статистику."
                  onRetry={() => void runStatistics(statisticsState.request, statisticsState.line)}
                />
              )}
              {statisticsState.kind === 'ready' && (
                <>
                  {statisticsOutdated && (
                    <p className="reports__outdated" role="status">Выбор изменён после построения. Нажмите «Показать», чтобы обновить диаграмму.</p>
                  )}
                  <div id="reports-statistics-result" tabIndex={-1}>
                    {statisticsState.line ? <LineChart result={statisticsState.result} /> : <StatisticsChart result={statisticsState.result} />}
                  </div>
                  <div className="reports__order">
                    <button
                      type="button"
                      className="reports__secondary"
                      onClick={() => orderChart(statisticsState.request, statisticsState.line, 'PNG')}
                      disabled={ordering || chartLimit !== null}
                    >
                      Скачать PNG
                    </button>
                    <button
                      type="button"
                      className="reports__secondary"
                      onClick={() => orderChart(statisticsState.request, statisticsState.line, 'PDF')}
                      disabled={ordering || chartLimit !== null}
                    >
                      Скачать PDF
                    </button>
                    {chartLimit === null && (
                      <InfoTip label="Файл диаграммы">
                        Файл строится на сервере по параметрам показанной диаграммы и скачивается, когда готов. Он также остаётся в «Моих выгрузках».
                      </InfoTip>
                    )}
                  </div>
                  {chartLimit !== null && <p className="reports__problem" role="status">{chartLimit}</p>}
                </>
              )}
              {chartJob && (
                <div className="report-chart__job" role="status">
                  <strong>{jobTitle(chartJob)}</strong>
                  <JobProgress job={chartJob} />
                </div>
              )}
              {orderError?.target === 'chart' && <ErrorNotice error={orderError.error} message="Заказ файла диаграммы не принят." />}
            </div>
          )}
        </CardTabPanel>
      </section>

      {jobs}

      <SaveReportDialog
        open={saving}
        onClose={() => setSaving(false)}
        saved={saved}
        definition={currentRequest}
        disabled={blocked}
        current={currentReport}
        onSaved={onSaved}
      />
    </section>
  )
}
