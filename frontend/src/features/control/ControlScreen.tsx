import { useCallback, useEffect, useRef, useState } from 'react'
import { withCount } from '../../shared/format/plural'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type ManagerWorkChange,
  type Me,
  type ReportFormat,
  type TeamsSummary,
  type WorkControl
} from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime, todayInMoscow } from '../../shared/format/datetime'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { FilterButton, FilterPanel } from '../../shared/ui/FilterPanel'
import { HintButton } from '../../shared/ui/HintButton'
import { Segmented } from '../../shared/ui/Segmented'
import { StatTiles, type StatTile } from '../../shared/ui/StatTiles'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { Toast } from '../../shared/ui/Toast'
import { terms } from '../../shared/ui/terms'
import { saveFile } from '../admin/saveFile'
import { matchingPreset, periodPresets } from '../reports/reportPeriods'
import { WorkList } from '../work/WorkList'
import { AssignDialog, IdleOrganizations } from './ControlIdle'
import { ControlNoContact } from './ControlNoContact'
import { AgreementsInProgress, ControlMatrix, UnassignedOrganizations, type MatrixOpenState } from './ControlMatrix'
import { ControlMore } from './ControlMore'
import { ControlRows, CycleStageMatrix } from './ControlCycleViews'
import { cellFilters, displayOptions, unassignedByWork, unassignedFor } from './cycleModel'
import {
  activeControlFilterCount,
  changeOptions,
  controlFiltersFromQuery,
  invalidQueryDates,
  controlListHref,
  controlReportHref,
  controlQuery,
  controlQueryFromFilters,
  controlStateFromHash,
  controlViewFromQuery,
  type ControlTile,
  type ControlView,
  emptyControlFilters,
  filterControl,
  hasPeriod,
  hottestTeamId,
  isPeriodMode,
  managerUnassigned,
  modeOptions,
  type ControlMode,
  panelFilterCount,
  stuckDayOptions,
  teamControl,
  tileCounts,
  tileShownCount,
  toExportRequest,
  unassignedInRows,
  type ControlFilters
} from './controlModel'
import { loadControlOptions, type ControlOptions } from './controlOptions'
import { CheckList, FilterChips, labelsOf, type ActiveChip } from './FilterFields'
import './control.css'

type ControlScreenProps = {
  role: Me['role']
  profileId?: string
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

type PeriodChoice = 'ALL' | 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR' | 'CUSTOM'

type TileKey = ControlTile | ManagerWorkChange

const pollMilliseconds = 1000

const pause = (milliseconds: number) => new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds))

const known = (ids: string[], options: { id: string }[]) => ids.filter((id) => options.some((option) => option.id === id))

const periodTiles: ReadonlySet<TileKey> = new Set<TileKey>(['MOVED', 'NEW', 'COMPLETED'])

const tileLabels: Record<ControlTile, string> = {
  OVERDUE: 'Просрочено',
  THIS_WEEK: 'Срок на этой неделе',
  NO_NEXT_STEP: terms.noStepOrDue,
  ISSUES: terms.issues,
  AGREEMENT: 'Договор на согласовании',
  UNASSIGNED: 'Вузов без КАМ',
  STUCK: 'На этапе дольше',
  LMS: 'С сигналами LMS',
  IDLE: 'Без работы'
}

const periodOptions: { value: PeriodChoice; label: string }[] = [
  { value: 'ALL', label: 'Без ограничения' },
  { value: 'WEEK', label: 'Неделя' },
  { value: 'MONTH', label: 'Месяц' },
  { value: 'QUARTER', label: 'Квартал' },
  { value: 'YEAR', label: 'Год' },
  { value: 'CUSTOM', label: 'Даты…' }
]

const periodChoice = (from: string, to: string, today: string): PeriodChoice => {
  if (from === '' && to === '') {
    return 'ALL'
  }
  const preset = matchingPreset(from, to, today)
  return preset === null || preset === 'PREVIOUS_MONTH' ? 'CUSTOM' : preset
}

const periodText = (from: string, to: string) => (
  `${from === '' ? 'с начала' : `с ${formatCalendarDate(from)}`} ${to === '' ? 'по сегодня' : `по ${formatCalendarDate(to)}`}`
)

// Подпись правила владельца рядом с итогами: по ней видно, чей КАМ в строках и в выгрузке. Команда, программа и
// продукт истории не имеют, поэтому за прошлый период названы текущими (разбор А05).
const managerBasis = (mode: ControlMode, to: string, today: string) => (
  mode === 'PERIOD' && to !== '' && to < today
    ? `КАМ на ${formatCalendarDate(to)}; команда, программа и продукт — текущие`
    : 'КАМ текущий'
)

const isListQuery = (query: string) => new URLSearchParams(query).get('view') === 'list'

const compactQuery = '(max-width: 640px)'

// Телефон: группы свёрнуты, «горящие» сверху. Ширина отслеживается, чтобы поворот экрана менял вид без перезагрузки.
const useCompact = () => {
  const [compact, setCompact] = useState(() => typeof window !== 'undefined' && window.matchMedia?.(compactQuery).matches === true)
  useEffect(() => {
    const query = window.matchMedia?.(compactQuery)
    if (query === undefined) {
      return
    }
    const onChange = () => setCompact(query.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])
  return compact
}

export const ControlScreen = (props: ControlScreenProps) => (
  isListQuery(props.initialQuery) ? <ControlList {...props} /> : <ControlBoard {...props} />
)

const ControlList = ({ role, initialQuery, refreshSignal, onSessionExpired, onProfileUnavailable }: ControlScreenProps) => {
  return (
    <section className="control" aria-label="Контроль списком">
      <div className="control-toolbar">
        <HintButton label="Что на этом экране">
          Все взаимодействия {role === 'MANAGEMENT' ? 'всех команд, только просмотр' : 'команды'} одним списком: сначала ближайшие и просроченные сроки следующего шага.
        </HintButton>
        <ActionMenu label={terms.moreActions} items={[{ label: 'Показать по менеджерам', href: '#/control' }]} />
      </div>
      <WorkList
        role={role}
        path="control"
        initialQuery={initialQuery}
        refreshSignal={refreshSignal}
        title="Все взаимодействия"
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />
    </section>
  )
}

const ControlBoard = ({ role, profileId, initialQuery, refreshSignal, onSessionExpired, onProfileUnavailable }: ControlScreenProps) => {
  const [filters, setFilters] = useState<ControlFilters>(() => controlFiltersFromQuery(initialQuery))
  const [view, setView] = useState<ControlView>(() => controlViewFromQuery(initialQuery))
  const [badDates] = useState(() => invalidQueryDates(initialQuery))
  const [dataState, setDataState] = useState<DataState>({ kind: 'loading' })
  const [optionsState, setOptionsState] = useState<OptionsState>({ kind: 'loading' })
  const [summary, setSummary] = useState<TeamsSummary | null>(null)
  const [unassignedCount, setUnassignedCount] = useState<number | null>(null)
  const [message, setMessage] = useState<{ text: string; persistent: boolean } | null>(null)
  const [exporting, setExporting] = useState(false)
  const [panelOpen, setPanelOpen] = useState(false)
  const [customPeriod, setCustomPeriod] = useState(false)
  const [overrides, setOverrides] = useState<Record<string, boolean>>({})
  const [bulk, setBulk] = useState<boolean | null>(null)
  const [assigning, setAssigning] = useState<string | null>(null)
  const [assignRevision, setAssignRevision] = useState(0)
  const requestVersion = useRef(0)
  const mounted = useRef(true)
  const management = role === 'MANAGEMENT'
  const compact = useCompact()

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
    // Номер запроса отсекает устаревшие ответы: при быстрой смене фильтра поздно пришедший ответ на прежний
    // запрос не должен перезаписать результат нового.
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
        kindId: known(current.kindId, options.kinds),
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
    const query = controlQuery(filters, view)
    // Адрес обновляется через replaceState: это не создаёт hashchange (экран не перерисуется по кругу) и не
    // засоряет историю «Назад» каждым нажатием фильтра.
    window.history.replaceState(null, '', query === '' ? '#/control' : `#/control?${query}`)
  }, [filters, view])

  useEffect(() => {
    const applyLinkedState = () => {
      const linked = controlStateFromHash(window.location.hash)
      if (linked === null) {
        return
      }
      // Тот же отбор оставляем прежним объектом, чтобы не перезапрашивать данные зря.
      setFilters((current) => controlQueryFromFilters(current) === controlQueryFromFilters(linked.filters) ? current : linked.filters)
      setView((current) => controlQuery(emptyControlFilters, current) === controlQuery(emptyControlFilters, linked.view) ? current : linked.view)
      setCustomPeriod(false)
    }
    window.addEventListener('hashchange', applyLinkedState)
    return () => window.removeEventListener('hashchange', applyLinkedState)
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(filters), 200)
    return () => window.clearTimeout(timer)
  }, [filters, load, refreshSignal])

  useEffect(() => {
    let active = true
    const request = management
      ? apiClient.getTeamsSummary(view.stuckDays).then((value) => {
        if (active) {
          setSummary(value)
          setUnassignedCount(value.total.unassignedOrganizations)
        }
      })
      : apiClient.getTeamIndicators(view.stuckDays).then((value) => {
        if (active) {
          setUnassignedCount(value.unassignedOrganizations)
        }
      })
    request.catch((error: unknown) => {
      if (active && !handleAccessError(error)) {
        setSummary(null)
      }
    })
    return () => {
      active = false
    }
  }, [handleAccessError, management, refreshSignal, view.stuckDays, assignRevision])

  const update = (patch: Partial<ControlFilters>) => setFilters((current) => ({ ...current, ...patch }))

  // Без периода режим «Сейчас»: снятие периода возвращает текущего КАМ.
  const setPeriod = (from: string, to: string) => update({
    from,
    to,
    change: from === '' && to === '' ? '' : filters.change,
    mode: from === '' && to === '' ? 'NOW' : filters.mode
  })

  // «За период» без выбранного периода берёт прошлый месяц: КАМ на конец периода требует законченного периода.
  const chooseMode = (mode: ControlMode) => {
    if (mode === 'PERIOD' && !hasPeriod(filters)) {
      const previous = periodPresets(todayInMoscow()).find((item) => item.id === 'PREVIOUS_MONTH')
      setCustomPeriod(true)
      update({ mode, from: previous?.from ?? '', to: previous?.to ?? '' })
      return
    }
    update({ mode })
  }

  const choosePeriod = (choice: PeriodChoice) => {
    setCustomPeriod(choice === 'CUSTOM')
    if (choice === 'ALL') {
      setPeriod('', '')
      return
    }
    const preset = periodPresets(todayInMoscow()).find((item) => item.id === choice)
    if (preset !== undefined) {
      setPeriod(preset.from, preset.to)
    }
  }

  const selectTile = (key: TileKey | null) => {
    if (key !== null && periodTiles.has(key)) {
      setView((current) => ({ ...current, tile: null }))
      update({ change: key as ManagerWorkChange })
      return
    }
    setView((current) => ({ ...current, tile: key as ControlTile | null }))
    if (filters.change !== '') {
      update({ change: '' })
    }
  }

  const pickManager = (managerId: string | null, tile: ControlTile | null) => {
    update({ managerId: [managerId ?? managerUnassigned], change: '' })
    setView((current) => ({ ...current, tile }))
  }

  const resetAll = () => {
    setFilters(emptyControlFilters)
    setView((current) => ({ ...current, tile: null, team: null }))
    setCustomPeriod(false)
  }

  const exportFile = async (format: ReportFormat) => {
    if (exporting) {
      return
    }
    setExporting(true)
    setMessage({ text: `Готовим файл ${format}…`, persistent: true })
    try {
      const created = await apiClient.createReportJob(toExportRequest(filters, format, management, view), createIdempotencyKey())
      for (;;) {
        await pause(pollMilliseconds)
        if (!mounted.current) {
          return
        }
        const job = await apiClient.getReportJob(created.jobId)
        if (job.status === 'FAILED') {
          setMessage({ text: job.error?.message ?? 'Не удалось подготовить файл.', persistent: true })
          return
        }
        if (job.status === 'SUCCEEDED') {
          saveFile(await apiClient.downloadReportJobResult(job.id), job.fileName ?? `Контроль.${format.toLowerCase()}`)
          setMessage({ text: 'Файл сохранён.', persistent: false })
          return
        }
      }
    } catch (error) {
      if (!handleAccessError(error)) {
        setMessage({
          text: error instanceof ApiError && error.message !== '' ? error.message : 'Не удалось выгрузить файл. Повторите попытку.',
          persistent: true
        })
      }
    } finally {
      setExporting(false)
    }
  }

  const options = optionsState.kind === 'ready' ? optionsState.options : null
  const withPeriod = hasPeriod(filters)
  const loaded = dataState.kind === 'ready' ? dataState.control : null
  const control = loaded === null ? null : teamControl(loaded, view.team)
  const teamName = view.team === undefined || view.team === null ? null
    : loaded?.teams.find((team) => team.teamId === view.team)?.teamName ?? 'команда не найдена'
  const periodMode = isPeriodMode(filters)
  const counts = control === null ? null : tileCounts(control, view.stuckDays)
  const shown = control === null ? null : filterControl(control, view.tile, view.stuckDays)
  const inTile = shown === null ? null : tileShownCount(shown, view.tile)
  const today = todayInMoscow()
  const choice = customPeriod ? 'CUSTOM' : periodChoice(filters.from, filters.to, today)
  // Вузы без КАМ с учётом команды, вуза и (через строки) программы, продукта, направления, вида и этапа.
  const unassignedOrganizations = options === null ? [] : unassignedFor(options.organizationList, control, filters, view.team)
  // Число с сервера — по всем вузам области; при любом отборе берётся длина отобранного списка.
  const unassignedNumber = teamName !== null || unassignedByWork(filters) || filters.organizationId.length > 0
    ? unassignedOrganizations.length
    : unassignedCount ?? unassignedOrganizations.length
  const display = view.display ?? 'managers'
  const periodEnd = filters.to !== '' && filters.to < today ? formatCalendarDate(filters.to) : null
  const kindNames = (ids: string[]) => labelsOf(ids, options?.kinds ?? [], 'вид').join(', ')
  // Список вузов без КАМ — сегодняшний; с периодом его нет: плитка тогда считает по строкам периода.
  const showUnassigned = unassignedOrganizations.length > 0 && (view.tile === null || view.tile === 'UNASSIGNED') && filters.managerId.length === 0
    && !withPeriod
  const assignHref = role === 'LEADER' ? '#/organizations?requiresAssignment=true' : null
  const onAssign = role === 'LEADER' && profileId !== undefined ? setAssigning : undefined
  const idleOrganizations = control?.idleOrganizations ?? null
  const showIdle = idleOrganizations !== null && idleOrganizations.length > 0 && (view.tile === null || view.tile === 'IDLE')
  const selectedTile: TileKey | null = view.tile ?? (filters.change === '' ? null : filters.change)

  const open: MatrixOpenState = {
    isOpen: (key, fallback) => overrides[key] ?? bulk ?? (view.tile !== null ? true : compact ? false : fallback),
    onToggle: (key, value) => setOverrides((current) => (open.isOpen(key, !value) === value ? current : { ...current, [key]: value }))
  }
  const setAll = (value: boolean) => {
    setOverrides({})
    setBulk(value)
  }

  // С периодом «Вузов без КАМ» считается по строкам периода (КАМ — по режиму), без периода — по всем вузам команды.
  const unassignedTile: StatTile<TileKey> | null = counts === null || control === null ? null : {
    key: 'UNASSIGNED',
    label: periodMode ? `${tileLabels.UNASSIGNED} на ${formatCalendarDate(filters.to)}` : tileLabels.UNASSIGNED,
    value: withPeriod ? unassignedInRows(control) : unassignedNumber,
    tone: 'danger'
  }
  const stateTiles: StatTile<TileKey>[] = counts === null ? [] : [
    { key: 'OVERDUE', label: tileLabels.OVERDUE, value: counts.OVERDUE, tone: 'danger' },
    { key: 'THIS_WEEK', label: tileLabels.THIS_WEEK, value: counts.THIS_WEEK, tone: 'warning' },
    { key: 'NO_NEXT_STEP', label: tileLabels.NO_NEXT_STEP, value: counts.NO_NEXT_STEP, tone: 'warning' },
    { key: 'ISSUES', label: tileLabels.ISSUES, value: counts.ISSUES, tone: 'danger' },
    { key: 'AGREEMENT', label: tileLabels.AGREEMENT, value: counts.AGREEMENT, tone: 'warning' },
    ...(unassignedTile !== null && !periodMode ? [unassignedTile] : []),
    { key: 'STUCK', label: `${tileLabels.STUCK} ${view.stuckDays} дн.`, value: counts.STUCK, tone: 'warning' },
    { key: 'LMS', label: tileLabels.LMS, value: counts.LMS, tone: 'warning' },
    ...(control?.idleOrganizations !== null && control?.idleOrganizations !== undefined
      ? [{ key: 'IDLE' as const, label: tileLabels.IDLE, value: counts.IDLE, tone: 'danger' as const }]
      : [])
  ]
  const changeTiles: StatTile<TileKey>[] = withPeriod && control !== null ? [
    { key: 'MOVED', label: 'Перешли на другой этап', value: control.total.moved },
    { key: 'NEW', label: 'Новые взаимодействия', value: control.total.created },
    { key: 'COMPLETED', label: 'Завершённые', value: control.total.completed },
    ...(unassignedTile !== null && periodMode ? [unassignedTile] : [])
  ] : []

  const chips: ActiveChip[] = []
  const listChips = (key: 'productId' | 'kindId' | 'programId' | 'directionId' | 'organizationId' | 'managerId' | 'stage', label: string, optionList: { id: string; label: string }[]) => {
    const values = filters[key]
    labelsOf(values, optionList, 'выбранное ранее').forEach((text, index) => chips.push({
      key: `${key}:${values[index]}`,
      label: `${label}: ${text}`,
      onRemove: () => update({ [key]: values.filter((value) => value !== values[index]) })
    }))
  }
  listChips('productId', 'Продукт', options?.products ?? [])
  listChips('kindId', 'Вид взаимодействия', options?.kinds ?? [])
  listChips('programId', 'ИТ-программа', options?.programs ?? [])
  listChips('directionId', 'ИТ-направление', options?.directions ?? [])
  listChips('organizationId', 'Вуз', options?.organizations ?? [])
  listChips('managerId', 'Менеджер', [{ id: managerUnassigned, label: 'Не указано' }, ...(options?.managers ?? [])])
  listChips('stage', 'Этап', options?.stages ?? [])
  if (withPeriod) {
    chips.push({ key: 'period', label: `Период ${periodText(filters.from, filters.to)}`, onRemove: () => choosePeriod('ALL') })
  }
  if (teamName !== null) {
    chips.push({ key: 'team', label: `Команда: ${teamName}`, onRemove: () => setView((current) => ({ ...current, team: null })) })
  }
  if (view.tile !== null) {
    chips.push({
      key: 'tile',
      label: `Плитка: ${view.tile === 'STUCK' ? `на этапе дольше ${view.stuckDays} дн.` : tileLabels[view.tile].toLowerCase()}`,
      onRemove: () => selectTile(null)
    })
  }
  if (filters.change !== '') {
    chips.push({ key: 'change', label: changeOptions.find((option) => option.id === filters.change)?.label ?? '', onRemove: () => update({ change: '' }) })
  }

  // Отбора по команде у выгрузки нет: файл с другими командами выдавал бы себя за то, что на экране.
  const menu: ActionMenuItem[] = [
    ...(teamName === null ? [
      { label: exporting ? 'Готовим файл…' : `${terms.export} XLSX`, onSelect: () => void exportFile('XLSX') },
      { label: `${terms.export} PDF`, onSelect: () => void exportFile('PDF') },
      { label: 'Все форматы — в отчёте', href: controlReportHref(filters, view) }
    ] : [{ label: 'Выгрузка — без отбора команды', onSelect: () => setView((current) => ({ ...current, team: null })) }]),
    display === 'list'
      ? { label: 'Показать по менеджерам', onSelect: () => setView((current) => ({ ...current, display: 'managers' })), separated: true }
      : { label: 'Показать списком', href: controlListHref(filters, view), separated: true },
    { label: 'Развернуть всех', onSelect: () => setAll(true) },
    { label: 'Свернуть всех', onSelect: () => setAll(false) }
  ]

  return (
    <section className="control" aria-label="Контроль">
      <div className="control-toolbar">
        <HintButton label="Что на этом экране">
          Кто, с каким вузом и на каком этапе. «Сейчас» — текущий КАМ вуза; «За период» — КАМ на конец выбранного
          периода, как в отчёте «Работа менеджеров»; отбор по менеджеру, выгрузка и отчёт идут по тому же правилу.
          Плитка отбирает строки, второе нажатие снимает отбор. Число в строке менеджера отбирает его взаимодействия.
          «Договор на согласовании» считает договоры вузов на согласовании, корректировке и подписании, как реестр
          договоров, даже не привязанные к взаимодействию; плитка показывает эти договоры и взаимодействия их вузов.
          В режиме «За период» плитки изменений и «Вузов без КАМ на дату» считаются по строкам периода, остальные
          плитки — состояние этих строк сейчас.
          «С сигналами LMS» — взаимодействия с сигналами LMS или несверенным этапом. «Без работы» — вузы, за которыми
          закреплён КАМ, но идущего взаимодействия нет; эта группа видна без периода и без отборов по продукту,
          программе, направлению и этапу, её выгрузка — список вузов с датой последнего действия сотрудника. Период
          показывает изменения: кто перешёл на другой этап, какие взаимодействия новые и какие завершены — завершённым
          считается взаимодействие, переведённое в статус «Завершено» в периоде (даты включительно, МСК). Сроки
          считаются только у идущих взаимодействий, у приостановленных — по дате возврата.
        </HintButton>
        <Segmented label="Режим" options={modeOptions} value={filters.mode} onChange={chooseMode} />
        <Segmented label="Период" options={periodOptions} value={choice} onChange={choosePeriod} />
        <FilterButton count={panelFilterCount(filters)} onClick={() => setPanelOpen(true)} />
        <ActionMenu label={terms.moreActions} items={menu} />
      </div>
      {choice === 'CUSTOM' && (
        <div className="control-period" role="group" aria-label="Период, даты включительно, МСК">
          <label>с <input type="date" value={filters.from} max={filters.to || undefined} onChange={(event) => setPeriod(event.target.value, filters.to)} /></label>
          <label>по <input type="date" value={filters.to} min={filters.from || undefined} onChange={(event) => setPeriod(filters.from, event.target.value)} /></label>
        </div>
      )}

      <FilterChips chips={chips} onReset={resetAll} />
      {control !== null && (
        <p className="control-status">
          <span>{`${inTile === null ? '' : 'всего: '}${withCount(control.total.organizations, 'вуз', 'вуза', 'вузов')} · ${withCount(control.total.works, 'взаимодействие', 'взаимодействия', 'взаимодействий')}${
            inTile === null ? '' : ` · по отбору: ${view.tile === 'IDLE' ? withCount(inTile, 'вуз', 'вуза', 'вузов') : withCount(inTile, 'взаимодействие', 'взаимодействия', 'взаимодействий')}`
          } · ${managerBasis(filters.mode, filters.to, today)} · данные на ${formatMoscowDateTime(control.calculatedAt)}`}</span>
          <button type="button" className="button--secondary" onClick={() => void load(filters)}>{terms.refresh}</button>
        </p>
      )}

      {changeTiles.length > 0 && (
        <div className="control-tiles">
          <p className="control-tiles__caption">{periodMode ? 'За период, КАМ на конец периода' : 'Изменения за период'}</p>
          <StatTiles label="Изменения за период" tiles={changeTiles} selected={selectedTile} onSelect={selectTile} />
        </div>
      )}
      {stateTiles.length > 0 && (
        <div className="control-tiles">
          {changeTiles.length > 0 && (
            <p className="control-tiles__caption">{periodMode ? 'Сейчас — состояние взаимодействий периода' : 'Сейчас'}</p>
          )}
          <StatTiles label="Отбор по показателям" tiles={stateTiles} selected={selectedTile} onSelect={selectTile} />
        </div>
      )}

      {badDates.filter((bad) => filters[bad.name] === '').map((bad) => (
        <p key={bad.name} className="notice" role="note">
          {bad.name === 'from' ? 'Дата начала' : 'Дата окончания'} периода в адресе «{bad.value}» не распознана или такой даты нет —
          отбор по ней не применён. Задайте период в фильтрах.
        </p>
      ))}
      <FilterPanel open={panelOpen} onClose={() => setPanelOpen(false)} onReset={resetAll}>
        {optionsState.kind === 'loading' && <p className="control-muted" role="status">Загружаем списки…</p>}
        {optionsState.kind === 'failed' && (
          <div className="notice notice--error" role="alert">
            <p>Не удалось загрузить списки для фильтров.</p>
            <button type="button" onClick={() => void loadOptions()}>{terms.retry}</button>
          </div>
        )}
        {options !== null && (
          <>
            <CheckList label="Продукт" options={options.products} selected={filters.productId} onChange={(ids) => update({ productId: ids })} />
            <CheckList label="Вид взаимодействия" options={options.kinds} selected={filters.kindId} onChange={(ids) => update({ kindId: ids })} />

            <CheckList label="ИТ-программа" options={options.programs} selected={filters.programId} onChange={(ids) => update({ programId: ids })} />
            <CheckList label="ИТ-направление" options={options.directions} selected={filters.directionId} onChange={(ids) => update({ directionId: ids })} />
            <CheckList label="Вуз" options={options.organizations} selected={filters.organizationId} onChange={(ids) => update({ organizationId: ids })} />
            <CheckList
              label="Менеджер"
              options={options.managers}
              selected={filters.managerId}
              extra={{ id: managerUnassigned, label: 'Не указано' }}
              note="«Не указано» — вузы, за которыми нет действующего менеджера."
              onChange={(ids) => update({ managerId: ids })}
            />
            <CheckList label="Этап" options={options.stages} selected={filters.stage} onChange={(ids) => update({ stage: ids })} />
          </>
        )}
        <label>
          На этапе дольше
          <select value={view.stuckDays} onChange={(event) => setView((current) => ({ ...current, stuckDays: Number(event.target.value) }))}>
            {(stuckDayOptions.includes(view.stuckDays) ? stuckDayOptions : [...stuckDayOptions, view.stuckDays].sort((a, b) => a - b))
              .map((days) => <option key={days} value={days}>{days} дней</option>)}
          </select>
        </label>
        {withPeriod && (
          <fieldset className="check-list">
            <legend>Показать за период</legend>
            {changeOptions.map((option) => (
              <label key={option.id || 'all'} className="checkbox-field">
                <input type="radio" name="control-change" checked={filters.change === option.id} onChange={() => selectTile(option.id === '' ? null : option.id)} />
                {option.label}
              </label>
            ))}
          </fieldset>
        )}
      </FilterPanel>

      {dataState.kind === 'loading' && <p className="control-muted" role="status">Собираем данные…</p>}
      {dataState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          {/* 400 — ошибка в отборах (например, конец периода раньше начала): показываем причину, «Повторить» не поможет. */}
          {dataState.error instanceof ApiError && dataState.error.status === 400
            ? <p>{Object.values(dataState.error.fieldErrors ?? {})[0] ?? dataState.error.message}</p>
            : <p>Не удалось собрать данные «Контроля».</p>}
          {dataState.error instanceof ApiError && <SupportDetails requestId={dataState.error.requestId} code={dataState.error.code} />}
          {!(dataState.error instanceof ApiError && dataState.error.status === 400) && (
            <button type="button" onClick={() => void load(filters)}>{terms.retry}</button>
          )}
        </div>
      )}
      {shown !== null && control !== null && (
        <div className="control-display">
          <Segmented
            label="Вид"
            options={displayOptions}
            value={display}
            onChange={(value) => setView((current) => ({ ...current, display: value }))}
          />
        </div>
      )}
      {shown !== null && control !== null && display === 'stages' && (
        <div className="control-board" aria-busy={dataState.kind === 'ready' && dataState.refreshing}>
          <CycleStageMatrix
            control={shown}
            stuckDays={view.stuckDays}
            periodEnd={periodEnd}
            kindNames={kindNames}
            compact={compact}
            onPick={(axis, rowId, stage, route, severalRoutes) => {
              setFilters((current) => cellFilters(current, axis, rowId, stage, route, severalRoutes))
              setView((current) => ({ ...current, display: 'list' }))
            }}
          />
        </div>
      )}
      {shown !== null && control !== null && display !== 'stages' && (
        <div className="control-board" aria-busy={dataState.kind === 'ready' && dataState.refreshing}>
          {showUnassigned && view.tile !== 'IDLE' && (
            <UnassignedOrganizations
              organizations={unassignedOrganizations}
              count={unassignedNumber}
              assignHref={assignHref}
              open={open}
              onAssign={onAssign}
            />
          )}
          {showIdle && <IdleOrganizations organizations={idleOrganizations} byTeams={control.byTeams} open={open} />}
          {view.tile === 'AGREEMENT' && <AgreementsInProgress rows={control.agreements ?? []} byTeams={control.byTeams} />}
          {view.tile === 'IDLE' && !showIdle && <p className="control-empty">Вузов без работы нет.</p>}
          {view.tile === 'IDLE' ? null : view.tile === 'UNASSIGNED' && shown.teams.length === 0
            ? <p className="control-empty">Взаимодействий у вузов без КАМ нет.</p>
            : display === 'list' ? <ControlRows control={shown} withPeriod={withPeriod} byTeams={control.byTeams} /> : (
              <ControlMatrix
                control={shown}
                full={view.tile === null ? undefined : control}
                filtered={view.tile !== null || activeControlFilterCount(filters) > 0}
                withPeriod={withPeriod}
                stuckDays={view.stuckDays}
                summary={summary}
                hotTeamId={hottestTeamId(control)}
                open={open}
                compact={compact}
                onPick={pickManager}
                assignHref={showUnassigned ? null : assignHref}
                onAssign={onAssign}
              />
            )}
        </div>
      )}

      <ControlNoContact refreshKey={refreshSignal} onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} />
      <ControlMore
        management={management}
        organizations={options?.organizationList ?? null}
        refreshKey={refreshSignal}
        managers={role === 'LEADER' ? options?.activeManagers ?? null : null}
        leaderId={profileId}
        onDeputiesChanged={() => void loadOptions()}
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />
      {profileId !== undefined && (
        <AssignDialog
          organization={options?.organizationList.find((item) => item.id === assigning) ?? null}
          profileId={profileId}
          onClose={() => setAssigning(null)}
          onAssigned={() => {
            setAssigning(null)
            setMessage({ text: 'Назначение ответственного сохранено.', persistent: false })
            setAssignRevision((value) => value + 1)
            void load(filters)
            void loadOptions()
          }}
          onReload={loadOptions}
          onSessionExpired={onSessionExpired}
          onProfileUnavailable={onProfileUnavailable}
        />
      )}
      <Toast message={message?.text ?? null} persistent={message?.persistent ?? false} onClose={() => setMessage(null)} />
    </section>
  )
}
