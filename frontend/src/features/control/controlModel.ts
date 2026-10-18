import type { ReportSelection } from '../reports/reportSelection'
import type {
  LearningTrend,
  ManagerWorkChange,
  ManagerWorkDue,
  ReportColumn,
  ReportFormat,
  ReportRequest,
  WorkControl,
  WorkControlItem,
  WorkControlManager,
  WorkControlQuery,
  WorkControlTeam,
  WorkControlTotals,
  WorkLearning
} from '../../shared/api/client'

export type ControlFilters = WorkControlQuery

export const managerUnassigned = 'UNASSIGNED'

export const emptyControlFilters: ControlFilters = {
  organizationId: [],
  directionId: [],
  programId: [],
  productId: [],
  managerId: [],
  stage: [],
  from: '',
  to: '',
  change: '',
  mode: 'NOW'
}

export const changeOptions: { id: ManagerWorkChange | ''; label: string }[] = [
  { id: '', label: 'Все взаимодействия' },
  { id: 'MOVED', label: 'Перешли на другой этап' },
  { id: 'NEW', label: 'Новые взаимодействия' },
  { id: 'COMPLETED', label: 'Завершённые' }
]

export type ControlMode = ControlFilters['mode']

export const modeOptions: { value: ControlMode; label: string }[] = [
  { value: 'NOW', label: 'Сейчас' },
  { value: 'PERIOD', label: 'За период' }
]

export const dueLabels: Record<ManagerWorkDue, string> = {
  OVERDUE: 'Просрочено',
  THIS_WEEK: 'На этой неделе',
  NO_NEXT_STEP: 'Без шага или срока',
  IN_ORDER: 'В порядке'
}

export type DueTone = 'overdue' | 'week' | 'missing' | 'ok'

export const dueTones: Record<ManagerWorkDue, DueTone> = {
  OVERDUE: 'overdue',
  THIS_WEEK: 'week',
  NO_NEXT_STEP: 'missing',
  IN_ORDER: 'ok'
}

const datePattern = /^\d{4}-\d{2}-\d{2}$/

// Формат ГГГГ-ММ-ДД проверяется регуляркой, а существование даты — обратным преобразованием: браузер принимает
// «2026-02-31» и сдвигает на март, и такая дата прошла бы как допустимая.
const isRealDate = (value: string) => {
  if (!datePattern.test(value)) {
    return false
  }
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value)
}

const changeFrom = (value: string): ManagerWorkChange | '' => (
  changeOptions.find((option) => option.id !== '' && option.id === value)?.id ?? ''
)

// Адрес «Контроля» (#/control?…) вводит и правит пользователь, поэтому разбор терпим: неверные даты и
// неизвестное значение «change» молча отбрасываются, а не ломают экран; списки читаются повторяющимися
// параметрами. Идентификаторы, которых нет в справочниках, экран отбрасывает после загрузки списков.
export const controlFiltersFromQuery = (query: string): ControlFilters => {
  const params = new URLSearchParams(query)
  const date = (name: string) => {
    const value = params.get(name) ?? ''
    return isRealDate(value) ? value : ''
  }
  return {
    organizationId: params.getAll('organization'),
    directionId: params.getAll('direction'),
    programId: params.getAll('program'),
    productId: params.getAll('product'),
    managerId: params.getAll('manager'),
    stage: params.getAll('stage'),
    from: date('from'),
    to: date('to'),
    change: changeFrom(params.get('change') ?? ''),
    // Без периода режим всегда «Сейчас»: КАМ «на конец периода» без периода не определён.
    mode: params.get('mode') === 'period' && (date('from') !== '' || date('to') !== '') ? 'PERIOD' : 'NOW'
  }
}

// Порядок параметров фиксирован: один и тот же отбор всегда даёт один и тот же адрес. Это обратная
// операция к controlFiltersFromQuery; адрес можно сохранить в закладках или отправить коллеге.
export const controlQueryFromFilters = (filters: ControlFilters): string => {
  const params = new URLSearchParams()
  const lists: [string, string[]][] = [
    ['product', filters.productId],
    ['program', filters.programId],
    ['direction', filters.directionId],
    ['organization', filters.organizationId],
    ['manager', filters.managerId],
    ['stage', filters.stage]
  ]
  lists.forEach(([name, values]) => values.forEach((value) => params.append(name, value)))
  if (filters.from !== '') {
    params.set('from', filters.from)
  }
  if (filters.to !== '') {
    params.set('to', filters.to)
  }
  if (filters.change !== '') {
    params.set('change', filters.change)
  }
  if (filters.mode === 'PERIOD' && hasPeriod(filters)) {
    params.set('mode', 'period')
  }
  return params.toString()
}

export const hasPeriod = (filters: ControlFilters) => filters.from !== '' || filters.to !== ''

export const activeControlFilterCount = (filters: ControlFilters) => [
  filters.productId.length > 0,
  filters.programId.length > 0,
  filters.directionId.length > 0,
  filters.organizationId.length > 0,
  filters.managerId.length > 0,
  filters.stage.length > 0,
  hasPeriod(filters),
  filters.change !== ''
].filter(Boolean).length

// Это номер этапа в шаблоне, а не готовность: ветвления, возвраты и необязательные этапы не дают читать долю как результат.
export const routeLabel = (item: Pick<WorkControlItem, 'stagePosition' | 'stageTotal'>) => (
  item.stageTotal > 0 ? `Положение в маршруте: этап ${item.stagePosition} из ${item.stageTotal}` : ''
)

export const routeShare = (item: Pick<WorkControlItem, 'stagePosition' | 'stageTotal'>) => (
  item.stageTotal > 0 ? Math.min(100, Math.round(item.stagePosition * 100 / item.stageTotal)) : 0
)

const transitionsText = (count: number) => {
  const last = count % 10
  const lastTwo = count % 100
  const word = last === 1 && lastTwo !== 11 ? 'переход' : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'перехода' : 'переходов'
  return `${count} ${word}`
}

export const changeNotes = (item: Pick<WorkControlItem, 'created' | 'completed' | 'transitions' | 'stageAtStart' | 'stage'>): string[] => {
  const notes: string[] = []
  if (item.created) {
    notes.push('Новое взаимодействие')
  }
  if (item.transitions > 0) {
    notes.push(item.stageAtStart !== null && item.stageAtStart !== undefined && item.stageAtStart !== item.stage
      ? `Было: ${item.stageAtStart} · ${transitionsText(item.transitions)}`
      : transitionsText(item.transitions))
  }
  if (item.completed) {
    notes.push('Завершена в периоде')
  }
  return notes
}

const signedNumber = (value: number) => (value > 0 ? `+${value}` : value < 0 ? `−${Math.abs(value)}` : '0')

// Тренд в строке «Ещё»: сопоставимые потоки и потоки без базы сравнения — разные совокупности, в одно «было → стало»
// их не складываем («10 → 32 (+2)» читалось бы как рост на 22).
export const trendLine = (trend: Pick<LearningTrend, 'total'>, days: number) => (
  `Обучающиеся за ${days} дней, сопоставимые потоки: ${trend.total.start} → ${trend.total.end}, ${signedNumber(trend.total.change)}`
  + (trend.total.runsWithoutData > 0 ? `; новые потоки без базы сравнения: ${trend.total.endWithoutBase}` : '')
)

export const learningText = (learning: WorkLearning | null | undefined): string => {
  if (learning === null || learning === undefined) {
    return 'Нет потоков LMS'
  }
  if (learning.runs === 0) {
    return 'Идущих потоков нет'
  }
  const active = learning.active7d === null || learning.active7d === undefined
    ? ''
    : `, за 7 дней заходили ${learning.active7d}`
  return `Обучающихся ${learning.participants}${active}`
}

export const exportColumns = (filters: ControlFilters, withTeams: boolean): ReportColumn[] => [
  'MANAGER',
  ...(withTeams ? ['TEAM' as const] : []),
  'ORGANIZATION',
  'INTERACTION',
  'DIRECTION',
  'PROGRAM',
  'PRODUCTS',
  'STAGE_POSITION',
  'STAGE_AT_END',
  'DUE_STATE',
  'NEXT_ACTION',
  'NEXT_ACTION_AT',
  'AGREEMENT_STATUS',
  'AGREEMENT',
  'PROBLEM',
  'RISK',
  'LMS_LEARNING',
  'LMS_CHECK',
  ...(hasPeriod(filters) ? ['STAGE_AT_START' as const, 'TRANSITIONS' as const, 'PERIOD_COMMENTS' as const] : [])
]

// Плитка уходит в выгрузку отбором tile: файл содержит те же строки, что экран. «Без работы» выгружается списком вузов
// (КАМ, команда, последнее действие сотрудника), а не строками взаимодействий. Режим уходит как managerAt, поэтому
// менеджер и отбор по нему в файле те же, что на экране.
export const toExportRequest = (
  filters: ControlFilters,
  format: ReportFormat,
  withTeams: boolean,
  view: ControlView = { tile: null, stuckDays: defaultStuckDays }
): ReportRequest => ({
  kind: 'MANAGER_WORK',
  from: filters.from === '' ? null : filters.from,
  to: filters.to === '' ? null : filters.to,
  format,
  filters: {
    organizationIds: filters.organizationId,
    directionIds: filters.directionId,
    programIds: filters.programId,
    productIds: filters.productId,
    managerIds: filters.managerId.filter((id) => id !== managerUnassigned),
    includeNoManager: filters.managerId.includes(managerUnassigned),
    stages: filters.stage,
    change: filters.change === '' ? null : filters.change,
    tile: view.tile,
    minDaysOnStage: view.tile === 'STUCK' ? view.stuckDays : null,
    managerAt: managerAt(filters)
  },
  columns: exportColumns(filters, withTeams)
})

export const managerAt = (filters: ControlFilters): 'CURRENT' | 'PERIOD_END' => (
  filters.mode === 'PERIOD' && hasPeriod(filters) ? 'PERIOD_END' : 'CURRENT'
)

export type ControlTile = 'OVERDUE' | 'THIS_WEEK' | 'NO_NEXT_STEP' | 'ISSUES' | 'AGREEMENT' | 'UNASSIGNED' | 'STUCK' | 'LMS' | 'IDLE'

export type PeriodTile = ManagerWorkChange

export const defaultStuckDays = 30

export const trendDays = 90

// Блоки запрашиваются отдельно от матрицы и без её фильтров: подпись называет область и время каждого числа.
export const controlMoreScope = (management: boolean) => (
  `${management ? 'По всем командам' : 'По всей команде'}, фильтры, плитки и период выше не применяются. `
  + `Сигналы LMS — действующие сейчас; обучающиеся — сегодня против ${trendDays} дней назад`
  + `${management ? '; подписания — текущий квартал' : ''}.`
)

export const stuckDayOptions = [7, 14, 30, 60, 90]

const controlTileKeys: readonly ControlTile[] = ['OVERDUE', 'THIS_WEEK', 'NO_NEXT_STEP', 'ISSUES', 'AGREEMENT', 'UNASSIGNED', 'STUCK', 'LMS', 'IDLE']

export type ControlView = {
  tile: ControlTile | null
  stuckDays: number
  // Команда из сводки «Руководства»: число в строке команды открывает её строки (отбор на экране, как плитка).
  team?: string | null
}

export const controlViewFromQuery = (query: string): ControlView => {
  const params = new URLSearchParams(query)
  const tile = params.get('tile') ?? ''
  const stuck = Number(params.get('stuck') ?? '')
  const team = params.get('team') ?? ''
  return {
    tile: controlTileKeys.find((key) => key === tile) ?? null,
    stuckDays: Number.isInteger(stuck) && stuck > 0 && stuck <= 3650 ? stuck : defaultStuckDays,
    ...(team === '' ? {} : { team })
  }
}

// Отбор плиткой и порог «долго на этапе» хранятся в адресе рядом с серверными фильтрами, чтобы закладка и
// «Назад» возвращали тот же вид; на сервер они не уходят — плитка отбирает строки уже полученной матрицы.
export const controlQuery = (filters: ControlFilters, view: ControlView): string => {
  const params = new URLSearchParams(controlQueryFromFilters(filters))
  if (view.tile !== null) {
    params.set('tile', view.tile)
  }
  if (view.stuckDays !== defaultStuckDays) {
    params.set('stuck', String(view.stuckDays))
  }
  if (view.team !== undefined && view.team !== null) {
    params.set('team', view.team)
  }
  return params.toString()
}

// Ссылка, вкладка «Контроль» и «Назад» меняют адрес без перезагрузки: экран берёт отборы из нового адреса.
// null — адрес не относится к виду «по менеджерам» (другой раздел или вид списком).
export const controlStateFromHash = (hash: string): { filters: ControlFilters; view: ControlView } | null => {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  if (path !== 'control' || new URLSearchParams(query).get('view') === 'list') {
    return null
  }
  return { filters: controlFiltersFromQuery(query), view: controlViewFromQuery(query) }
}

const isActiveWork = (item: Pick<WorkControlItem, 'status'>) => item.status !== 'COMPLETED' && item.status !== 'PAUSED'

// Этап договора, пока договор на согласовании, не считается застрявшим: там действуют пороги согласования
// (как на сервере, ManagerWorkReport.matches).
export const isStuck = (item: Pick<WorkControlItem, 'status' | 'daysOnStage' | 'stageUnderApproval'>, stuckDays: number) => (
  isActiveWork(item) && !item.stageUnderApproval && (item.daysOnStage ?? 0) >= stuckDays
)

// agreementOrganizations — вузы с договором на согласовании по правилу реестра (WorkControl.agreements): плитка
// «Договор на согласовании» видит и договор, не привязанный к взаимодействию (как ManagerWorkReport.matches).
export const tileMatches = (
  item: WorkControlItem,
  managerId: string | null,
  tile: ControlTile,
  stuckDays: number,
  agreementOrganizations: ReadonlySet<string> = new Set()
): boolean => {
  switch (tile) {
    case 'OVERDUE':
    case 'THIS_WEEK':
    case 'NO_NEXT_STEP':
      return item.due === tile
    case 'ISSUES':
      return item.problems + item.risks > 0
    case 'AGREEMENT':
      return agreementOrganizations.has(item.organizationId)
    case 'UNASSIGNED':
      return managerId === null
    case 'STUCK':
      return isStuck(item, stuckDays)
    case 'LMS':
      return (item.learning?.signals ?? 0) > 0 || Boolean(item.learning?.mismatch)
    case 'IDLE':
      return false
  }
}

const allWorks = (control: WorkControl) => control.teams.flatMap((team) => team.managers.flatMap((manager) => (
  manager.works.map((item) => ({ item, managerId: manager.managerId }))
)))

const agreementOrganizationIds = (control: WorkControl): ReadonlySet<string> => (
  new Set((control.agreements ?? []).map((row) => row.organizationId))
)

// Вузы без КАМ среди строк: с периодом плитка считает их так (КАМ — по режиму), чтобы число относилось к тем же
// строкам, что таблица, а не к сегодняшнему списку вузов (решение владельца 18.10).
export const unassignedInRows = (control: WorkControl): number => (
  new Set(allWorks(control).filter(({ managerId }) => managerId === null).map(({ item }) => item.organizationId)).size
)

// «Договор на согласовании» считает договоры (единица — договор, как в реестре), остальные плитки — взаимодействия.
export const tileCounts = (control: WorkControl, stuckDays: number): Record<Exclude<ControlTile, 'UNASSIGNED'>, number> => {
  const works = allWorks(control)
  const count = (tile: ControlTile) => works.filter(({ item, managerId }) => tileMatches(item, managerId, tile, stuckDays)).length
  return {
    OVERDUE: control.total.overdue,
    THIS_WEEK: control.total.thisWeek,
    NO_NEXT_STEP: control.total.noNextStep,
    ISSUES: control.total.withIssues,
    AGREEMENT: control.agreements?.length ?? 0,
    STUCK: count('STUCK'),
    LMS: count('LMS'),
    IDLE: control.total.idle
  }
}

// КАМ на конец периода сервер берёт только при заданном конце периода (WorkControlService.control).
export const isPeriodMode = (filters: ControlFilters) => filters.mode === 'PERIOD' && filters.to !== ''

const emptyTotals: WorkControlTotals = {
  organizations: 0, works: 0, overdue: 0, thisWeek: 0, noNextStep: 0, inOrder: 0, withIssues: 0, moved: 0, created: 0, completed: 0, idle: 0
}

// Команда из сводки «Руководства»: остаются её строки, договоры и итоги; неизвестная команда даёт пустую матрицу.
export const teamControl = (control: WorkControl, teamId: string | null | undefined): WorkControl => {
  if (teamId === null || teamId === undefined) {
    return control
  }
  const teams = control.teams.filter((team) => team.teamId === teamId)
  return {
    ...control,
    teams,
    total: teams[0]?.totals ?? emptyTotals,
    idleOrganizations: control.idleOrganizations?.filter((organization) => organization.teamId === teamId) ?? control.idleOrganizations,
    agreements: (control.agreements ?? []).filter((row) => row.teamId === teamId)
  }
}

// На телефоне сначала «горящие» менеджеры (без КАМ, с просрочкой или проблемами), внутри — по числу просрочек.
export const compactManagers = (managers: WorkControlManager[]) => (
  [...managers].sort((a, b) => Number(isHotManager(b)) - Number(isHotManager(a)) || b.totals.overdue - a.totals.overdue)
)

export const compactTeams = (teams: WorkControlTeam[]) => (
  [...teams].sort((a, b) => b.totals.overdue + b.totals.withIssues - a.totals.overdue - a.totals.withIssues)
)

export const managerCounts = (manager: WorkControlManager, stuckDays: number) => ({
  stuck: manager.works.filter((item) => isStuck(item, stuckDays)).length,
  lms: manager.works.filter((item) => tileMatches(item, manager.managerId, 'LMS', stuckDays)).length
})

// Плитка показывает только подходящие строки; менеджеры и команды без таких строк скрываются, чтобы отбор
// не терялся среди пустых групп. Итоги групп остаются серверными: они описывают всю группу, а не отбор, поэтому
// рядом матрица показывает отдельный итог отбора (shownTotals) и подписывает оба числа.
export const filterControl = (control: WorkControl, tile: ControlTile | null, stuckDays: number): WorkControl => {
  if (tile === null) {
    return control
  }
  const agreementOrganizations = agreementOrganizationIds(control)
  const teams = control.teams
    .map((team) => ({
      ...team,
      managers: team.managers
        .map((manager) => ({
          ...manager,
          works: manager.works.filter((item) => tileMatches(item, manager.managerId, tile, stuckDays, agreementOrganizations))
        }))
        .filter((manager) => manager.works.length > 0)
    }))
    .filter((team) => team.managers.length > 0)
  return { ...control, teams }
}

export const isHotManager = (manager: WorkControlManager) => (
  manager.managerId === null || manager.totals.overdue > 0 || manager.totals.withIssues > 0
)

export const managerKey = (teamId: string | null | undefined, managerId: string | null) => `${teamId ?? 'team'}:${managerId ?? 'unassigned'}`

export const teamKey = (teamId: string | null | undefined) => `team:${teamId ?? 'none'}`

export const hottestTeamId = (control: WorkControl): string | null | undefined => (
  control.teams.reduce<WorkControlTeam | null>((best, team) => (
    best === null || team.totals.overdue > best.totals.overdue ? team : best
  ), null)?.teamId
)

export const sortedManagers = (managers: WorkControlManager[]) => (
  [...managers].sort((a, b) => Number(a.managerId !== null) - Number(b.managerId !== null))
)

export const panelFilterCount = (filters: ControlFilters) => [
  filters.productId,
  filters.programId,
  filters.directionId,
  filters.organizationId,
  filters.managerId,
  filters.stage
].filter((values) => values.length > 0).length

// Переходы из «Контроля» в список и в отчёт «Работа менеджеров» переносят отборы. У списка отбор одиночный и нет
// продукта, программы, направления и периода; отчёт не знает плиток. Что не перенеслось, целевой экран называет по
// параметру skipped, чтобы пользователь не принял неполную выборку за ту же самую.
const skippedLabels: Record<string, string> = {
  product: 'продукт',
  program: 'ИТ-программа',
  direction: 'ИТ-направление',
  organizations: 'несколько вузов',
  managers: 'несколько менеджеров',
  stages: 'несколько этапов',
  period: 'период',
  change: 'изменения за период',
  tile: 'плитка'
}

const withSkipped = (params: URLSearchParams, skipped: string[]) => {
  if (skipped.length > 0) {
    params.set('skipped', skipped.join(','))
  }
  return params.toString()
}

export const controlListHref = (filters: ControlFilters, view: ControlView): string => {
  const params = new URLSearchParams({ view: 'list' })
  const skipped: string[] = []
  const single = (values: string[], name: string, many: string) => {
    if (values.length === 1) {
      params.set(name, values[0])
    } else if (values.length > 1) {
      skipped.push(many)
    }
  }
  single(filters.organizationId, 'organization', 'organizations')
  single(filters.managerId, 'responsible', 'managers')
  single(filters.stage, 'stage', 'stages')
  const unsupported: [string, boolean][] = [
    ['product', filters.productId.length > 0],
    ['program', filters.programId.length > 0],
    ['direction', filters.directionId.length > 0],
    ['period', hasPeriod(filters)],
    ['change', filters.change !== '']
  ]
  unsupported.filter(([, active]) => active).forEach(([key]) => skipped.push(key))
  switch (view.tile) {
    case 'OVERDUE':
    case 'THIS_WEEK':
    case 'NO_NEXT_STEP':
      params.set('due', view.tile)
      break
    case 'ISSUES':
      params.set('flag', 'RISK_OR_PROBLEM')
      break
    case 'STUCK':
      params.set('minDaysOnStage', String(view.stuckDays))
      break
    case 'UNASSIGNED':
      if (params.has('responsible') || skipped.includes('managers')) {
        skipped.push('tile')
      } else {
        params.set('responsible', managerUnassigned)
      }
      break
    case 'AGREEMENT':
    case 'LMS':
    case 'IDLE':
      skipped.push('tile')
      break
    case null:
      break
  }
  return `#/control?${withSkipped(params, skipped)}`
}

export const controlReportHref = (filters: ControlFilters, view: ControlView): string => {
  const params = new URLSearchParams(controlQueryFromFilters(filters))
  params.set('source', 'control')
  return `#/reports/manager-work?${withSkipped(params, view.tile === null ? [] : ['tile'])}`
}

export const skippedNotice = (query: string): string | null => {
  const labels = (new URLSearchParams(query).get('skipped') ?? '')
    .split(',')
    .map((key) => skippedLabels[key])
    .filter((label): label is string => label !== undefined)
  return labels.length === 0 ? null : `Не перенесено из «Контроля»: ${labels.join(', ')} — на этом экране такого отбора нет.`
}

export const reportSelectionFromControl = (selection: ReportSelection, query: string): ReportSelection | null => {
  if (new URLSearchParams(query).get('source') !== 'control') {
    return null
  }
  const filters = controlFiltersFromQuery(query)
  return {
    ...selection,
    kind: 'MANAGER_WORK',
    from: filters.from,
    to: filters.to,
    organizationIds: filters.organizationId,
    organizationType: '',
    directionIds: filters.directionId,
    includeNoDirection: false,
    programIds: filters.programId,
    includeNoProgram: false,
    productIds: filters.productId,
    includeNoProduct: false,
    managerIds: filters.managerId.filter((id) => id !== managerUnassigned),
    includeNoManager: filters.managerId.includes(managerUnassigned),
    stages: filters.stage,
    change: filters.change,
    managerAt: managerAt(filters)
  }
}

export type ShownTotals = { organizations: number; works: number }

// Итог отбора считается по показанным строкам: вуз — один раз, сколько бы взаимодействий у него ни было.
export const shownTotals = (works: Pick<WorkControlItem, 'organizationId'>[]): ShownTotals => ({
  organizations: new Set(works.map((item) => item.organizationId)).size,
  works: works.length
})

export const shownWorks = (control: WorkControl): WorkControlItem[] => allWorks(control).map(({ item }) => item)

export const shownTotalsText = (totals: ShownTotals) => `по отбору: вузов ${totals.organizations}, взаимодействий ${totals.works}`

// Сколько строк осталось под выбранной плиткой: общий счётчик описывает всю выборку по фильтрам, а этот — отбор.
// У «Без работы» строк взаимодействий нет, там считаются вузы; у «Договор на согласовании» — договоры.
export const tileShownCount = (shown: WorkControl, tile: ControlTile | null): number | null => {
  if (tile === null) {
    return null
  }
  if (tile === 'AGREEMENT') {
    return shown.agreements?.length ?? 0
  }
  return tile === 'IDLE' ? shown.idleOrganizations?.length ?? 0 : allWorks(shown).length
}
