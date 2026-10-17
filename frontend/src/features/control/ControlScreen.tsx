import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type ManagerWorkChange,
  type Me,
  type ReportFormat,
  type WorkControl
} from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { saveFile } from '../admin/saveFile'
import { ChoiceChip, MultiChip, PeriodPicker, type FilterOption } from '../reports/ReportControls'
import '../reports/reports.css'
import { ControlMatrix } from './ControlMatrix'
import {
  activeControlFilterCount,
  changeOptions,
  controlFiltersFromQuery,
  controlQueryFromFilters,
  emptyControlFilters,
  hasPeriod,
  managerUnassigned,
  toExportRequest,
  type ControlFilters
} from './controlModel'
import { loadControlOptions, type ControlOptions } from './controlOptions'
import './control.css'

type ControlScreenProps = {
  role: Me['role']
  initialQuery: string
  refreshSignal: number
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type DataState =
  | { kind: 'loading' }
  | { kind: 'ready'; control: WorkControl; refreshing: boolean }
  | { kind: 'failed'; error: unknown }

type OptionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; options: ControlOptions }
  | { kind: 'failed'; error: unknown }

type ExportState =
  | { kind: 'idle' }
  | { kind: 'working'; format: ReportFormat }
  | { kind: 'done' }
  | { kind: 'failed'; message: string }

const pollMilliseconds = 1000

const pause = (milliseconds: number) => new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds))

const Tile = ({ label, value, tone }: { label: string; value: number; tone?: 'danger' | 'warning' }) => (
  <li>
    <span className={`desk-tile${tone !== undefined && value > 0 ? ` desk-tile--${tone}` : ''}`}>
      <span className="desk-tile__value">{value.toLocaleString('ru-RU')}</span>
      <span className="desk-tile__label">{label}</span>
    </span>
  </li>
)

const known = (ids: string[], options: FilterOption[]) => ids.filter((id) => options.some((option) => option.id === id))

export const ControlScreen = ({ role, initialQuery, refreshSignal, onSessionExpired, onProfileUnavailable }: ControlScreenProps) => {
  const [filters, setFilters] = useState<ControlFilters>(() => controlFiltersFromQuery(initialQuery))
  const [dataState, setDataState] = useState<DataState>({ kind: 'loading' })
  const [optionsState, setOptionsState] = useState<OptionsState>({ kind: 'loading' })
  const [exportState, setExportState] = useState<ExportState>({ kind: 'idle' })
  const requestVersion = useRef(0)
  const mounted = useRef(true)
  const withTeams = role === 'MANAGEMENT'

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

  const load = useCallback(async (current: ControlFilters) => {
    const version = ++requestVersion.current
    setDataState((state) => state.kind === 'ready' ? { ...state, refreshing: true } : { kind: 'loading' })
    try {
      const control = await apiClient.getWorkControl(current)
      if (version === requestVersion.current) {
        setDataState({ kind: 'ready', control, refreshing: false })
      }
    } catch (error) {
      if (version === requestVersion.current && !handleAccessError(error)) {
        setDataState({ kind: 'failed', error })
      }
    }
  }, [handleAccessError])

  const loadOptions = useCallback(async () => {
    setOptionsState({ kind: 'loading' })
    try {
      const options = await loadControlOptions()
      setOptionsState({ kind: 'ready', options })
      setFilters((current) => ({
        ...current,
        organizationId: known(current.organizationId, options.organizations),
        directionId: known(current.directionId, options.directions),
        programId: known(current.programId, options.programs),
        productId: known(current.productId, options.products),
        managerId: current.managerId.filter((id) => id === managerUnassigned || options.managers.some((option) => option.id === id)),
        stage: known(current.stage, options.stages)
      }))
    } catch (error) {
      if (!handleAccessError(error)) {
        setOptionsState({ kind: 'failed', error })
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    mounted.current = true
    void loadOptions()
    return () => {
      mounted.current = false
      requestVersion.current += 1
    }
  }, [loadOptions])

  useEffect(() => {
    const query = controlQueryFromFilters(filters)
    window.history.replaceState(null, '', query === '' ? '#/control' : `#/control?${query}`)
    const timer = window.setTimeout(() => void load(filters), 200)
    return () => window.clearTimeout(timer)
  }, [filters, load, refreshSignal])

  const update = (patch: Partial<ControlFilters>) => setFilters((current) => ({ ...current, ...patch }))

  const setPeriod = (from: string, to: string) => update({ from, to, change: from === '' && to === '' ? '' : filters.change })

  const exportFile = async (format: ReportFormat) => {
    setExportState({ kind: 'working', format })
    try {
      const created = await apiClient.createReportJob(toExportRequest(filters, format, withTeams), createIdempotencyKey())
      for (;;) {
        await pause(pollMilliseconds)
        if (!mounted.current) {
          return
        }
        const job = await apiClient.getReportJob(created.jobId)
        if (job.status === 'FAILED') {
          setExportState({ kind: 'failed', message: job.error?.message ?? 'Не удалось подготовить файл.' })
          return
        }
        if (job.status === 'SUCCEEDED') {
          saveFile(await apiClient.downloadReportJobResult(job.id), job.fileName ?? `Контроль.${format.toLowerCase()}`)
          setExportState({ kind: 'done' })
          return
        }
      }
    } catch (error) {
      if (!handleAccessError(error)) {
        setExportState({
          kind: 'failed',
          message: error instanceof ApiError && error.message !== '' ? error.message : 'Не удалось выгрузить файл. Повторите попытку.'
        })
      }
    }
  }

  const options = optionsState.kind === 'ready' ? optionsState.options : null
  const withPeriod = hasPeriod(filters)
  const control = dataState.kind === 'ready' ? dataState.control : null
  const working = exportState.kind === 'working'

  return (
    <section className="control" aria-labelledby="control-intro">
      <p id="control-intro" className="control__intro">
        Менеджеры, их вузы и работы: на каком этапе работа, когда следующий шаг, где есть проблемы и риски.
      </p>
      {control !== null && (
        <ul className="desk-tiles" aria-label="Итоги">
          <Tile label="Вузов" value={control.total.organizations} />
          <Tile label="Работ" value={control.total.works} />
          <Tile label="Просрочено" value={control.total.overdue} tone="danger" />
          <Tile label="Срок на этой неделе" value={control.total.thisWeek} tone="warning" />
          <Tile label="Без шага или срока" value={control.total.noNextStep} tone="warning" />
          <Tile label="С проблемами или рисками" value={control.total.withIssues} tone="danger" />
          {withPeriod && <Tile label="Перешли на другой этап" value={control.total.moved} />}
          {withPeriod && <Tile label="Новые работы" value={control.total.created} />}
          {withPeriod && <Tile label="Завершённые" value={control.total.completed} />}
        </ul>
      )}

      <div className="control-filters">
        <div className="control-filters__chips" role="group" aria-label="Отборы">
          {options !== null && (
            <>
              <MultiChip label="Продукт" options={options.products} selected={filters.productId} onChange={(ids) => update({ productId: ids })} />
              <MultiChip label="ИТ-программа" options={options.programs} selected={filters.programId} onChange={(ids) => update({ programId: ids })} />
              <MultiChip label="ИТ-направление" options={options.directions} selected={filters.directionId} onChange={(ids) => update({ directionId: ids })} />
              <MultiChip label="Вуз" options={options.organizations} selected={filters.organizationId} onChange={(ids) => update({ organizationId: ids })} />
              <MultiChip
                label="Менеджер"
                options={options.managers}
                selected={filters.managerId.filter((id) => id !== managerUnassigned)}
                onChange={(ids) => update({ managerId: filters.managerId.includes(managerUnassigned) ? [...ids, managerUnassigned] : ids })}
                includeNone={filters.managerId.includes(managerUnassigned)}
                onIncludeNoneChange={(value) => update({
                  managerId: value
                    ? [...filters.managerId.filter((id) => id !== managerUnassigned), managerUnassigned]
                    : filters.managerId.filter((id) => id !== managerUnassigned)
                })}
                note="«Не указано» — вузы, за которыми нет действующего менеджера."
              />
              <MultiChip label="Этап" options={options.stages} selected={filters.stage} onChange={(ids) => update({ stage: ids })} />
            </>
          )}
          {withPeriod && (
            <ChoiceChip
              label="Показать"
              options={changeOptions}
              value={filters.change}
              onChange={(value: ManagerWorkChange | '') => update({ change: value })}
            />
          )}
          {optionsState.kind === 'loading' && <span className="control-muted" role="status">Загружаем списки…</span>}
        </div>
        <PeriodPicker from={filters.from} to={filters.to} onChange={setPeriod} hint="Изменения за период: кто перешёл на другой этап, какие работы новые и какие завершены. Даты включительно, по московскому времени." />
        <div className="control-filters__actions">
          {activeControlFilterCount(filters) > 0 && (
            <button type="button" className="button--secondary" onClick={() => setFilters(emptyControlFilters)}>Сбросить отборы</button>
          )}
          <button type="button" disabled={working || dataState.kind !== 'ready'} onClick={() => void exportFile('XLSX')}>
            {working && exportState.format === 'XLSX' ? 'Готовим файл…' : 'Выгрузить XLSX'}
          </button>
          <button type="button" disabled={working || dataState.kind !== 'ready'} onClick={() => void exportFile('PDF')}>
            {working && exportState.format === 'PDF' ? 'Готовим файл…' : 'Выгрузить PDF'}
          </button>
          <a href="#/reports/manager-work">Все форматы — в отчёте</a>
        </div>
        {exportState.kind === 'done' && <p className="control-muted" role="status">Файл сохранён.</p>}
        {exportState.kind === 'failed' && (
          <div className="notice notice--error" role="alert">
            <p>{exportState.message}</p>
          </div>
        )}
      </div>

      <ul className="control-legend" aria-label="Состояние срока следующего шага">
        <li><span className="control-due control-due--overdue">Просрочено</span></li>
        <li><span className="control-due control-due--week">На этой неделе</span></li>
        <li><span className="control-due control-due--missing">Без шага или срока</span></li>
        <li><span className="control-due control-due--ok">В порядке</span></li>
      </ul>

      {optionsState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось загрузить списки для отборов.</p>
          <button type="button" onClick={() => void loadOptions()}>Повторить</button>
        </div>
      )}
      {dataState.kind === 'loading' && <p className="control-muted" role="status">Собираем матрицу…</p>}
      {dataState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось собрать матрицу.</p>
          {dataState.error instanceof ApiError && <SupportDetails requestId={dataState.error.requestId} code={dataState.error.code} />}
          <button type="button" onClick={() => void load(filters)}>Повторить</button>
        </div>
      )}
      {control !== null && (
        <div aria-busy={dataState.kind === 'ready' && dataState.refreshing}>
          <ControlMatrix control={control} withPeriod={withPeriod} />
          <p className="control-footer">
            <span>Данные на {formatMoscowDateTime(control.calculatedAt)}</span>
            <button type="button" className="button--secondary" onClick={() => void load(filters)}>Обновить</button>
          </p>
        </div>
      )}
    </section>
  )
}
