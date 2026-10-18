import { useEffect, useRef, useState, type Dispatch, type FormEvent, type SetStateAction } from 'react'
import {
  apiClient,
  type Me,
  type ReportColumn,
  type ReportFormat,
  type ReportJob,
  type ReportPreview,
  type ReportPreviewRequest,
  type ReportRequest,
  type SavedReport,
  type StatisticsRequest,
  type StatisticsResult
} from '../../shared/api/client'
import { formatMoscowDateTime, todayInMoscow } from '../../shared/format/datetime'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { FilterButton, FilterPanel } from '../../shared/ui/FilterPanel'
import { terms } from '../../shared/ui/terms'
import { CardTabPanel, CardTabs } from '../interactions/cardUi'
import { ReportColumnsDialog } from './ReportColumnsDialog'
import { AsOfPicker, InfoTip, PeriodPicker, SelectedValues } from './ReportControls'
import { ErrorNotice } from './ReportErrorNotice'
import { ExportMenu } from './ReportExportMenu'
import { activeFilterChips, ReportFilterFields, type FilterOptions, type OptionsState } from './ReportFilters'
import { ReportGroupingDialog } from './ReportGroupingDialog'
import { kindViews, reportMeta } from './ReportCatalog'
import { ReportHeader } from './ReportHeader'
import { LineChart, chartLines } from './LineChart'
import { JobProgress, jobTitle } from './ReportJobs'
import { activeFilterCount, resetFilters } from './reportFilterModel'
import { kindHint, statisticsHint } from './reportHints'
import { periodOnKindChange } from './reportPeriods'
import {
  chartKinds,
  defaultSelection,
  groupingProblem,
  groupingTitles,
  groupingsFor,
  kindLabels,
  lineChartSelected,
  periodProblem,
  selectionProblem,
  toPreviewRequest,
  toStatisticsRequest,
  type ReportSelection
} from './reportSelection'
import { SaveReportDialog, type SavedReportsModel } from './SavedReports'
import { StatisticsChart, chartBars } from './StatisticsChart'
import './reportsLayout.css'

export type { FilterOptions, OptionsState }

export type OrderTarget = 'report' | 'chart'

const totalTitles = ['Команда', 'Менеджер', 'Вузов', 'Взаимодействий', 'Просрочено', 'На этой неделе', 'Без шага или срока', 'Переходов за период']

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

const dateColumns: ReadonlySet<ReportColumn> = new Set<ReportColumn>(['CREATED_AT', 'LAST_EVENT_AT', 'NEXT_ACTION_AT', 'EVENT_AT'])

const cellText = (value: unknown, column: ReportColumn) => {
  if (dateColumns.has(column)) {
    return formatMoscowDateTime(String(value))
  }
  return typeof value === 'number' ? value.toLocaleString('ru-RU') : String(value)
}

const focusResult = (id: string) => {
  const target = document.getElementById(id)
  if (target === null) {
    return
  }
  target.scrollIntoView({ block: 'start' })
  target.focus({ preventScroll: true })
}

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
  chartJob: ReportJob | undefined
  saved: SavedReportsModel
  currentReport: SavedReport | undefined
  onSaved: (report: SavedReport) => void
  autoRun: boolean
  onAutoRunDone: () => void
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
  chartJob,
  saved,
  currentReport,
  onSaved,
  autoRun,
  onAutoRunDone
}: ReportBuilderProps) => {
  const [previewState, setPreviewState] = useState<PreviewState>({ kind: 'idle' })
  const [statisticsState, setStatisticsState] = useState<StatisticsState>({ kind: 'idle' })
  const [tab, setTab] = useState<ResultTab>(statisticsMode ? 'chart' : 'table')
  const [saving, setSaving] = useState(false)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [columnsOpen, setColumnsOpen] = useState(false)
  const [groupingOpen, setGroupingOpen] = useState(false)
  const previewVersion = useRef(0)
  const statisticsVersion = useRef(0)

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
  const filterCount = activeFilterCount(selection)

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

  const setColumns = (next: ReportColumn[]) => {
    setSelection((current) => ({ ...current, columns: { ...current.columns, [current.kind]: next } }))
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
  const chips = options === null ? [] : activeFilterChips(selection, options, update)
  const meta = reportMeta(role, statisticsMode ? 'statistics' : kindViews[kind])
  const title = statisticsMode ? 'Статистика и диаграммы' : kindLabels[kind]
  const hint = (
    <>
      {statisticsMode ? statisticsHint : kindHint(kind, role)}
      {meta.data !== '' && <span className="report-hint__data">Данные: {meta.data}.</span>}
    </>
  )

  const exportItems = [
    { label: 'XLSX (Excel)', onSelect: () => orderFile('XLSX') },
    { label: 'PDF', onSelect: () => orderFile('PDF') },
    { label: 'XLS (старый Excel)', onSelect: () => orderFile('XLS') },
    { label: 'JSON (для обработки программами)', onSelect: () => orderFile('JSON') }
  ]

  return (
    <section className="report-builder" aria-labelledby="report-builder-title">
      <ReportHeader
        id="report-builder-title"
        group={meta.group}
        title={title}
        hint={hint}
        saved={currentReport?.name}
        actions={(
          <>
            <ExportMenu items={exportItems} disabled={blocked || ordering} />
            <ActionMenu
              label={terms.moreActions}
              items={[
                { label: 'Сохранить отчёт…', onSelect: () => setSaving(true) },
                { label: 'Колонки и порядок…', onSelect: () => setColumnsOpen(true) },
                ...(chartable ? [{ label: 'Группировка диаграммы…', onSelect: () => setGroupingOpen(true) }] : []),
                { label: 'Сбросить выбор', separated: true, onSelect: () => setSelection(defaultSelection(new Date(), kind)) }
              ]}
            />
          </>
        )}
      />

      <form className="reports__form report-toolbar" onSubmit={submit}>
        <div className="report-toolbar__row">
          {statisticsMode && (
            <div className="report-field">
              <label htmlFor="report-chart-kind">Данные</label>
              <select
                id="report-chart-kind"
                value={kind}
                onChange={(event) => {
                  const next = chartKinds.find((item) => item === event.target.value) ?? 'PORTFOLIO'
                  update({
                    kind: next,
                    ...periodOnKindChange(selection, selection.kind, next, todayInMoscow()),
                    groupBy: groupingsFor(next).includes(selection.groupBy) ? selection.groupBy : 'PROGRAM'
                  })
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
          <div className="report-toolbar__actions reports__actions">
            <FilterButton count={filterCount} onClick={() => setFiltersOpen(true)} />
            <button
              type="submit"
              disabled={activeTab === 'chart'
                ? statisticsBlocked || statisticsState.kind === 'loading'
                : blocked || previewState.kind === 'loading'}
            >
              {previewState.kind === 'loading' || statisticsState.kind === 'loading' ? 'Строим…' : 'Показать'}
            </button>
            {ordering && <span className="report-actions__status" role="status">Отправляем заказ…</span>}
          </div>
        </div>
        {optionsState.kind === 'loading' && <p className="report-empty report-empty--inline" role="status">Загружаем значения фильтров…</p>}
        {optionsState.kind === 'failed' && (
          <ErrorNotice error={optionsState.error} message="Не удалось загрузить значения фильтров." onRetry={onReloadOptions} />
        )}
        {chips.length > 0 && (
          <div className="report-toolbar__chips">
            <SelectedValues values={chips} />
            <button type="button" className="button--secondary" onClick={() => setSelection(resetFilters)}>{terms.resetFilters}</button>
          </div>
        )}
        {problem !== null && <p className="reports__problem" role="alert">{problem}</p>}
      </form>

      <FilterPanel open={filtersOpen} onClose={() => setFiltersOpen(false)} onReset={() => setSelection(resetFilters)}>
        {filtersOpen && options !== null && <ReportFilterFields role={role} selection={selection} options={options} update={update} />}
        {filtersOpen && options === null && <p className="report-empty report-empty--inline" role="status">Значения фильтров ещё загружаются.</p>}
      </FilterPanel>

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
                      {previewState.preview.groupTotals !== undefined && previewState.preview.groupTotals.length > 0 && (
                        <div className="reports__table-scroll" role="region" aria-label="Итоги" tabIndex={0}>
                          <table>
                            <caption>Итоги по менеджерам и командам</caption>
                            <thead>
                              <tr>
                                {totalTitles.map((title) => <th key={title} scope="col">{title}</th>)}
                              </tr>
                            </thead>
                            <tbody>
                              {previewState.preview.groupTotals.map((row, index) => (
                                <tr key={index}>
                                  <td>{row.team ?? ''}</td>
                                  <td>{row.manager}</td>
                                  <td>{row.organizations}</td>
                                  <td>{row.works}</td>
                                  <td>{row.overdue}</td>
                                  <td>{row.thisWeek}</td>
                                  <td>{row.noNextStep}</td>
                                  <td>{row.transitions}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
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
                <button type="button" className="reports__secondary" onClick={() => setGroupingOpen(true)}>Изменить группировку</button>
                <InfoTip label="Диаграмма">{statisticsHint}</InfoTip>
                {statisticsState.kind === 'ready' && (
                  <div className="report-chart__download">
                    <ExportMenu
                      label={terms.download}
                      disabled={ordering || chartLimit !== null}
                      items={[
                        { label: 'PNG (картинка)', onSelect: () => orderChart(statisticsState.request, statisticsState.line, 'PNG') },
                        { label: 'PDF', onSelect: () => orderChart(statisticsState.request, statisticsState.line, 'PDF') }
                      ]}
                    />
                    {chartLimit === null && (
                      <InfoTip label="Файл диаграммы">
                        Файл строится на сервере по параметрам показанной диаграммы и скачивается, когда готов. Он также остаётся в «Выгрузках».
                      </InfoTip>
                    )}
                  </div>
                )}
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

      <ReportColumnsDialog open={columnsOpen} onClose={() => setColumnsOpen(false)} kind={kind} selected={selection.columns[kind]} onChange={setColumns} />
      <ReportGroupingDialog
        open={groupingOpen}
        onClose={() => setGroupingOpen(false)}
        kind={kind}
        selection={selection}
        line={currentLine}
        seriesBy={currentStatisticsRequest.seriesBy}
        problem={statisticsProblem}
        update={update}
      />
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
