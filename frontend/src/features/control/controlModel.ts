import type {
  ManagerWorkChange,
  ManagerWorkDue,
  ReportColumn,
  ReportFormat,
  ReportRequest,
  WorkControlItem,
  WorkControlQuery
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
  { id: '', label: 'Все работы' },
  { id: 'MOVED', label: 'Перешли на другой этап' },
  { id: 'NEW', label: 'Новые работы' },
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
    notes.push('Новая работа')
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
