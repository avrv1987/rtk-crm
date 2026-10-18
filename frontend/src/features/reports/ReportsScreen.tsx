import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Me,
  type ReportJob,
  type ReportRequest,
  type SavedReport
} from '../../shared/api/client'
import { todayInMoscow } from '../../shared/format/datetime'
import { Toast } from '../../shared/ui/Toast'
import { AgreementConfirmationsPanel } from '../agreements/AgreementConfirmationsPanel'
import { reportSelectionFromControl, skippedNotice } from '../control/controlModel'
import { listReportVendors } from '../documents/documentsApi'
import { loadReportSelection, saveReportSelection } from '../interactions/drafts'
import { organizationTypeLabels } from '../organizations/OrganizationForm'
import { KamReviewPanel } from './KamReviewPanel'
import { LearningDynamicsReport } from './LearningDynamics'
import { SigningPlanReport } from './SigningPlanReport'
import { AgreementPeriodReport } from './AgreementPeriodReport'
import { CardTabPanel, CardTabs } from '../interactions/cardUi'
import { ReportBuilder, type OrderTarget } from './ReportBuilder'
import { kindViews, ReportCatalog, ReportTasks, viewKind, type ReportView } from './ReportCatalog'
import type { FilterOption } from './ReportControls'
import type { FilterOptions, OptionsState } from './ReportFilters'
import { isFinished, ReportJobs, type DownloadState, type JobsState } from './ReportJobs'
import { homeTabFromQuery, homeTabHash, jobsLinkHash } from './reportsRoute'
import { periodOnKindChange, periodPresets } from './reportPeriods'
import { reportTaskFor, taskMethod, taskSelection } from './reportTasks'
import { applyDefinition, chartKinds, groupingsFor, normalizeSelection, type ReportSelection } from './reportSelection'
import { SavedReportsList, useSavedReports } from './SavedReports'
import './reports.css'
import './reportsLayout.css'

type ReportsScreenProps = {
  profileId: string
  role: Me['role']
  view: string | undefined
  query?: string
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

const reportViews: readonly ReportView[] = [
  'manager-work', 'portfolio', 'events', 'snapshot', 'duration', 'demand', 'agreements', 'statistics', 'learning-dynamics', 'kam-review',
  'confirmations', 'signing-plan', 'agreements-period'
]

const pollIntervalMs = 1000

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

const catalogOptions = (items: { id: string; name: string; archived: boolean }[]): FilterOption[] => (
  items.map((item) => ({ id: item.id, label: item.archived ? `${item.name} — в архиве` : item.name }))
)

const known = (ids: string[], options: FilterOption[]) => ids.filter((id) => options.some((option) => option.id === id))

const withKnownFilters = (selection: ReportSelection, options: FilterOptions): ReportSelection => ({
  ...selection,
  organizationIds: known(selection.organizationIds, options.organizations),
  directionIds: known(selection.directionIds, options.directions),
  programIds: known(selection.programIds, options.programs),
  productIds: known(selection.productIds, options.products),
  managerIds: known(selection.managerIds, options.managers),
  stages: known(selection.stages, options.stages),
  agreement: { ...selection.agreement, vendorIds: known(selection.agreement.vendorIds, options.vendors) }
})

export const ReportsScreen = ({ profileId, role, view, query, onSessionExpired, onProfileUnavailable }: ReportsScreenProps) => {
  const found = reportViews.find((item) => item === view) ?? null
  const current = found === 'kam-review' && role === 'USER' ? null : found
  // Переход «Все форматы — в отчёте» из «Контроля» приносит его отборы в адресе и заменяет ими сохранённый выбор.
  const [selection, setSelection] = useState<ReportSelection>(() => {
    const stored = normalizeSelection(loadReportSelection(profileId))
    return (current === 'manager-work' ? reportSelectionFromControl(stored, query ?? '') : null) ?? stored
  })
  const [transferNotice] = useState(() => (current === 'manager-work' ? skippedNotice(query ?? '') : null))
  const task = reportTaskFor(current, query ?? '')
  const [optionsState, setOptionsState] = useState<OptionsState>({ kind: 'loading' })
  const [jobsState, setJobsState] = useState<JobsState>({ kind: 'loading' })
  const [pollError, setPollError] = useState<unknown>(null)
  const [ordering, setOrdering] = useState(false)
  const [orderError, setOrderError] = useState<{ target: OrderTarget; error: unknown } | null>(null)
  const [toast, setToast] = useState<ReactNode | null>(null)
  const [chartJobId, setChartJobId] = useState<string | null>(null)
  const [downloadState, setDownloadState] = useState<DownloadState>({ kind: 'idle' })
  const [currentReportId, setCurrentReportId] = useState<string | null>(null)
  const [autoRun, setAutoRun] = useState(false)
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

  const saved = useSavedReports(handleAccessError)

  const loadOptions = useCallback(async () => {
    setOptionsState({ kind: 'loading' })
    try {
      const [organizations, directions, programs, products, managers, stages, vendors] = await Promise.all([
        loadAllPages((page) => apiClient.listOrganizations({ page, size: 100, sort: 'name,asc', status: 'ALL' })),
        loadAllPages((page) => apiClient.listDirections({ page, size: 100, state: 'ALL' })),
        loadAllPages((page) => apiClient.listPrograms({ page, size: 100, state: 'ALL' })),
        loadAllPages((page) => apiClient.listProducts({ page, size: 100, state: 'ALL' })),
        apiClient.listReportManagers(),
        apiClient.listReportStages(),
        listReportVendors()
      ])
      const options: FilterOptions = {
        organizations: organizations.map((organization) => ({
          id: organization.id,
          label: `${organization.name}${organization.type === 'UNIVERSITY'
            ? ''
            : ` (${organizationTypeLabels[organization.type].toLowerCase()})`}${organization.status === 'ARCHIVED' ? ' — в архиве' : ''}`
        })),
        directions: catalogOptions(directions),
        programs: catalogOptions(programs),
        products: catalogOptions(products),
        managers: managers.map((manager) => ({
          id: manager.id,
          label: manager.active ? manager.displayName : `${manager.displayName} (неактивен)`
        })),
        stages: stages.map((stage) => ({ id: stage, label: stage })),
        vendors: vendors.map((vendor) => ({ id: vendor.id, label: vendor.archived ? `${vendor.name} (архивирован)` : vendor.name }))
      }
      setOptionsState({ kind: 'ready', options })
      setSelection((currentSelection) => withKnownFilters(currentSelection, options))
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
  }, [loadJobs, loadOptions])

  useEffect(() => {
    const pendingDownloads = autoDownloadIds.current
    return () => pendingDownloads.clear()
  }, [])

  useEffect(() => {
    saveReportSelection(profileId, selection)
  }, [profileId, selection])

  // Экран не пересоздаётся при переходе с вкладки «Задачи» в отчёт, поэтому пресет задачи применяется по смене адреса.
  useEffect(() => {
    if (task !== null) {
      setSelection((currentSelection) => taskSelection(currentSelection, task, periodPresets(todayInMoscow())))
      setAutoRun(task.preset !== undefined)
    }
  }, [task])

  useEffect(() => {
    if (current === null) {
      setCurrentReportId(null)
    }
  }, [current])

  const routeKind = current === null ? null : viewKind(current)
  const synced = current === 'statistics' ? chartKinds.includes(selection.kind) : routeKind === null || routeKind === selection.kind

  useEffect(() => {
    if (synced || (routeKind === null && current !== 'statistics')) {
      return
    }
    const kind = routeKind ?? 'PORTFOLIO'
    setSelection((currentSelection) => ({
      ...currentSelection,
      ...periodOnKindChange(currentSelection, currentSelection.kind, kind, todayInMoscow()),
      kind,
      groupBy: groupingsFor(kind).includes(currentSelection.groupBy) ? currentSelection.groupBy : 'PROGRAM'
    }))
  }, [current, routeKind, synced])

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
    setJobsState((currentJobs) => ({
      kind: 'ready',
      jobs: refreshed.reduce(upsertJob, (currentJobs.kind === 'ready' ? currentJobs.jobs : []).filter((job) => !gone.has(job.id)))
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
      } else {
        setToast(<>Файл {payload.format} заказан, он появится в выгрузках, когда будет готов. <a href={jobsLinkHash}>Открыть выгрузки</a></>)
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

  const openSavedReport = (report: SavedReport) => {
    const restored = applyDefinition(selection, report.definition)
    setSelection(optionsState.kind === 'ready' ? withKnownFilters(restored, optionsState.options) : restored)
    setCurrentReportId(report.id)
    setAutoRun(true)
    window.location.hash = `#/reports/${kindViews[restored.kind]}`
  }

  useEffect(() => {
    if (saved.notice !== '') {
      setToast(saved.notice)
    }
  }, [saved.notice])

  // Блока выгрузок под отчётом больше нет, поэтому сбой опроса готовности файла сообщаем всплывающим сообщением.
  useEffect(() => {
    if (pollError !== null) {
      setToast(<>Не удалось узнать, готов ли файл. <a href={jobsLinkHash}>Открыть выгрузки</a></>)
    }
  }, [pollError])

  const accessHandlers = { onSessionExpired, onProfileUnavailable }

  const content = (() => {
    if (current === 'learning-dynamics') {
      return <LearningDynamicsReport role={role} {...accessHandlers} />
    }
    if (current === 'signing-plan') {
      return <SigningPlanReport role={role} {...accessHandlers} />
    }
    if (current === 'agreements-period') {
      return <AgreementPeriodReport role={role} {...accessHandlers} />
    }
    if (current === 'kam-review') {
      return <KamReviewPanel role={role} {...accessHandlers} />
    }
    if (current === 'confirmations') {
      return <AgreementConfirmationsPanel role={role} {...accessHandlers} />
    }
    if (current !== null) {
      const chartJob = chartJobId === null || jobsState.kind !== 'ready'
        ? undefined
        : jobsState.jobs.find((job) => job.id === chartJobId)
      return (
        <div className="reports">
          {current === 'manager-work' && transferNotice !== null && <p className="notice" role="status">{transferNotice}</p>}
          {synced && (
            <ReportBuilder
              key={current}
              role={role}
              selection={selection}
              setSelection={setSelection}
              statisticsMode={current === 'statistics'}
              optionsState={optionsState}
              onReloadOptions={() => void loadOptions()}
              onAccessError={handleAccessError}
              onOrder={(payload, target) => void orderJob(payload, target)}
              ordering={ordering}
              orderError={orderError}
              chartJob={chartJob}
              saved={saved}
              currentReport={saved.reports.find((report) => report.id === currentReportId)}
              onSaved={(report) => setCurrentReportId(report.id)}
              autoRun={autoRun}
              onAutoRunDone={() => setAutoRun(false)}
            />
          )}
        </div>
      )
    }
    const tab = homeTabFromQuery(query)
    return (
      <div className="reports report-home">
        <CardTabs
          label="Разделы отчётов"
          idPrefix="reports-home"
          tabs={[
            { id: 'tasks', label: 'Задачи' },
            { id: 'catalog', label: 'Все отчёты' },
            { id: 'saved', label: 'Сохранённые', count: saved.listState.kind === 'ready' ? saved.reports.length : undefined },
            { id: 'jobs', label: 'Выгрузки', count: jobsState.kind === 'ready' ? jobsState.jobs.length : undefined }
          ]}
          active={tab}
          onChange={(next) => {
            window.location.hash = homeTabHash(next)
          }}
        />
        <CardTabPanel idPrefix="reports-home" active={tab}>
          {tab === 'tasks' && <ReportTasks role={role} />}
          {tab === 'catalog' && <ReportCatalog role={role} />}
          {tab === 'saved' && <SavedReportsList saved={saved} onOpen={openSavedReport} />}
          {tab === 'jobs' && (
            <ReportJobs
              jobsState={jobsState}
              pollError={pollError}
              downloadState={downloadState}
              onReload={() => void loadJobs()}
              onDownload={(job) => void downloadJob(job)}
            />
          )}
        </CardTabPanel>
      </div>
    )
  })()

  return (
    <>
      {task !== null && (
        <p className="notice report-task-notice" role="status">
          <strong>{task.title}.</strong> {taskMethod(task, selection.from, selection.to, todayInMoscow())}
        </p>
      )}
      {content}
      <Toast message={toast} onClose={() => setToast(null)} />
    </>
  )
}
