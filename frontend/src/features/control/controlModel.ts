import type {
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
  change: ''
}

export const changeOptions: { id: ManagerWorkChange | ''; label: string }[] = [
  { id: '', label: 'Все взаимодействия' },
  { id: 'MOVED', label: 'Перешли на другой этап' },
  { id: 'NEW', label: 'Новые взаимодействия' },
  { id: 'COMPLETED', label: 'Завершённые' }
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
    change: changeFrom(params.get('change') ?? '')
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

export const routeLabel = (item: Pick<WorkControlItem, 'stagePosition' | 'stageTotal'>) => (
  item.stageTotal > 0 ? `${item.stagePosition} из ${item.stageTotal}` : ''
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
    notes.push('Завершена')
  }
  return notes
}

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
  'PROBLEM',
  'RISK',
  'LMS_LEARNING',
  'LMS_CHECK',
  ...(hasPeriod(filters) ? ['STAGE_AT_START' as const, 'TRANSITIONS' as const, 'PERIOD_COMMENTS' as const] : [])
]

export const toExportRequest = (filters: ControlFilters, format: ReportFormat, withTeams: boolean): ReportRequest => ({
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
    change: filters.change === '' ? null : filters.change
  },
  columns: exportColumns(filters, withTeams)
})

export type ControlTile = 'OVERDUE' | 'THIS_WEEK' | 'NO_NEXT_STEP' | 'ISSUES' | 'AGREEMENT' | 'UNASSIGNED' | 'STUCK' | 'LMS'

export type PeriodTile = ManagerWorkChange

export const defaultStuckDays = 30

export const stuckDayOptions = [7, 14, 30, 60, 90]

const controlTileKeys: readonly ControlTile[] = ['OVERDUE', 'THIS_WEEK', 'NO_NEXT_STEP', 'ISSUES', 'AGREEMENT', 'UNASSIGNED', 'STUCK', 'LMS']

const agreementInProgress: ReadonlySet<string> = new Set(['REVIEW', 'CORRECTION', 'SIGNING'])

export type ControlView = {
  tile: ControlTile | null
  stuckDays: number
}

export const controlViewFromQuery = (query: string): ControlView => {
  const params = new URLSearchParams(query)
  const tile = params.get('tile') ?? ''
  const stuck = Number(params.get('stuck') ?? '')
  return {
    tile: controlTileKeys.find((key) => key === tile) ?? null,
    stuckDays: Number.isInteger(stuck) && stuck > 0 && stuck <= 3650 ? stuck : defaultStuckDays
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

export const isStuck = (item: Pick<WorkControlItem, 'status' | 'daysOnStage'>, stuckDays: number) => (
  isActiveWork(item) && (item.daysOnStage ?? 0) >= stuckDays
)

export const tileMatches = (item: WorkControlItem, managerId: string | null, tile: ControlTile, stuckDays: number): boolean => {
  switch (tile) {
    case 'OVERDUE':
    case 'THIS_WEEK':
    case 'NO_NEXT_STEP':
      return item.due === tile
    case 'ISSUES':
      return item.problems + item.risks > 0
    case 'AGREEMENT':
      return item.agreementStatus !== null && item.agreementStatus !== undefined && agreementInProgress.has(item.agreementStatus)
    case 'UNASSIGNED':
      return managerId === null
    case 'STUCK':
      return isStuck(item, stuckDays)
    case 'LMS':
      return (item.learning?.signals ?? 0) > 0 || Boolean(item.learning?.mismatch)
  }
}

const allWorks = (control: WorkControl) => control.teams.flatMap((team) => team.managers.flatMap((manager) => (
  manager.works.map((item) => ({ item, managerId: manager.managerId }))
)))

export const tileCounts = (control: WorkControl, stuckDays: number): Record<Exclude<ControlTile, 'UNASSIGNED'>, number> => {
  const works = allWorks(control)
  const count = (tile: ControlTile) => works.filter(({ item, managerId }) => tileMatches(item, managerId, tile, stuckDays)).length
  return {
    OVERDUE: control.total.overdue,
    THIS_WEEK: control.total.thisWeek,
    NO_NEXT_STEP: control.total.noNextStep,
    ISSUES: control.total.withIssues,
    AGREEMENT: count('AGREEMENT'),
    STUCK: count('STUCK'),
    LMS: count('LMS')
  }
}

export const managerCounts = (manager: WorkControlManager, stuckDays: number) => ({
  stuck: manager.works.filter((item) => isStuck(item, stuckDays)).length,
  lms: manager.works.filter((item) => tileMatches(item, manager.managerId, 'LMS', stuckDays)).length
})

// Плитка показывает только подходящие строки; менеджеры и команды без таких строк скрываются, чтобы отбор
// не терялся среди пустых групп. Итоги групп остаются серверными: они описывают всю группу, а не отбор.
export const filterControl = (control: WorkControl, tile: ControlTile | null, stuckDays: number): WorkControl => {
  if (tile === null) {
    return control
  }
  const teams = control.teams
    .map((team) => ({
      ...team,
      managers: team.managers
        .map((manager) => ({ ...manager, works: manager.works.filter((item) => tileMatches(item, manager.managerId, tile, stuckDays)) }))
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
