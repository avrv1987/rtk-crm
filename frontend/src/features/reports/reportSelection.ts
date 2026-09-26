import type {
  PeriodBasis,
  ReportColumn,
  ReportFormat,
  ReportKind,
  ReportPreviewRequest,
  StatisticsGroupBy,
  StatisticsRequest
} from '../../shared/api/client'

export type DemandSort = 'APPLICATIONS' | 'PARTICIPANTS' | 'PARALLEL_RUNS'

export type ReportSelection = {
  kind: ReportKind
  from: string
  to: string
  periodBasis: PeriodBasis
  organizationIds: string[]
  directionIds: string[]
  includeNoDirection: boolean
  programIds: string[]
  includeNoProgram: boolean
  productIds: string[]
  includeNoProduct: boolean
  managerIds: string[]
  includeNoManager: boolean
  stages: string[]
  columns: Record<ReportKind, ReportColumn[]>
  format: ReportFormat
  groupBy: StatisticsGroupBy
  sortBy: DemandSort
}

export const reportKinds: readonly ReportKind[] = ['PORTFOLIO', 'EVENTS', 'DEMAND']

export const demandSorts: readonly DemandSort[] = ['APPLICATIONS', 'PARTICIPANTS', 'PARALLEL_RUNS']

export const reportFormats: readonly ReportFormat[] = ['XLSX', 'XLS', 'PDF', 'JSON']

export const statisticsGroupings: readonly StatisticsGroupBy[] = [
  'STAGE', 'ORGANIZATION', 'DIRECTION', 'PROGRAM', 'PRODUCT', 'MANAGER', 'MONTH'
]

export const groupingTitles: Record<StatisticsGroupBy, string> = {
  STAGE: 'по этапам',
  ORGANIZATION: 'по вузам',
  DIRECTION: 'по ИТ-направлениям',
  PROGRAM: 'по ИТ-программам',
  PRODUCT: 'по ИТ-продуктам',
  MANAGER: 'по ответственным',
  MONTH: 'по месяцам'
}

const demandGroupings: readonly StatisticsGroupBy[] = ['ORGANIZATION', 'DIRECTION', 'PROGRAM', 'MANAGER', 'MONTH']

export const groupingsFor = (kind: ReportKind): readonly StatisticsGroupBy[] => (
  kind === 'DEMAND' ? demandGroupings : statisticsGroupings
)

const periodBases: readonly PeriodBasis[] = ['CREATED', 'ACTIVITY']

export const reportColumns: Record<ReportKind, readonly ReportColumn[]> = {
  PORTFOLIO: [
    'ORGANIZATION', 'INTERACTION', 'DIRECTION', 'PROGRAM', 'PRODUCTS', 'STAGE', 'MANAGER',
    'CREATED_AT', 'LAST_EVENT_AT', 'NEXT_ACTION', 'NEXT_ACTION_AT'
  ],
  EVENTS: [
    'EVENT_AT', 'ORGANIZATION', 'INTERACTION', 'EVENT_TYPE', 'FROM_STAGE', 'STAGE', 'COMMENT', 'AUTHOR',
    'MANAGER', 'DIRECTION', 'PROGRAM', 'PRODUCTS'
  ],
  DEMAND: ['DIRECTION', 'PROGRAM', 'APPLICATIONS', 'PARTICIPANTS', 'PARALLEL_RUNS']
}

const columnTitles: Record<ReportColumn, string> = {
  ORGANIZATION: 'Вуз',
  INTERACTION: 'Взаимодействие',
  DIRECTION: 'ИТ-направление',
  PROGRAM: 'ИТ-программа',
  PRODUCTS: 'ИТ-продукты',
  STAGE: 'Статус работы',
  MANAGER: 'Ответственный',
  CREATED_AT: 'Создано',
  LAST_EVENT_AT: 'Последнее событие',
  NEXT_ACTION: 'Следующий шаг',
  NEXT_ACTION_AT: 'Срок',
  EVENT_AT: 'Дата события',
  EVENT_TYPE: 'Действие',
  FROM_STAGE: 'Из этапа',
  COMMENT: 'Комментарий',
  AUTHOR: 'Автор',
  APPLICATIONS: 'Заявки (сайт)',
  PARTICIPANTS: 'Обучающиеся (Moodle)',
  PARALLEL_RUNS: 'Параллельные потоки (Moodle)'
}

const eventColumnTitles: Partial<Record<ReportColumn, string>> = {
  STAGE: 'Этап',
  MANAGER: 'Ответственный на момент события'
}

export const columnTitle = (kind: ReportKind, column: ReportColumn) => (
  kind === 'EVENTS' ? eventColumnTitles[column] ?? columnTitles[column] : columnTitles[column]
)

const isoDate = (date: Date) => [
  date.getFullYear().toString(),
  (date.getMonth() + 1).toString().padStart(2, '0'),
  date.getDate().toString().padStart(2, '0')
].join('-')

export const defaultSelection = (today: Date = new Date()): ReportSelection => ({
  kind: 'PORTFOLIO',
  from: isoDate(new Date(today.getFullYear(), today.getMonth(), 1)),
  to: isoDate(new Date(today.getFullYear(), today.getMonth() + 1, 0)),
  periodBasis: 'CREATED',
  organizationIds: [],
  directionIds: [],
  includeNoDirection: false,
  programIds: [],
  includeNoProgram: false,
  productIds: [],
  includeNoProduct: false,
  managerIds: [],
  includeNoManager: false,
  stages: [],
  columns: {
    PORTFOLIO: [...reportColumns.PORTFOLIO],
    EVENTS: [...reportColumns.EVENTS],
    DEMAND: [...reportColumns.DEMAND]
  },
  format: 'XLSX',
  groupBy: 'STAGE',
  sortBy: 'APPLICATIONS'
})

const oneOf = <T extends string>(value: unknown, allowed: readonly T[], fallback: T): T => (
  allowed.find((item) => item === value) ?? fallback
)

const strings = (value: unknown) => Array.isArray(value)
  ? value.filter((item): item is string => typeof item === 'string')
  : []

const dateValue = (value: unknown, fallback: string) => (
  typeof value === 'string' && (value === '' || /^\d{4}-\d{2}-\d{2}$/.test(value)) ? value : fallback
)

const columnList = (value: unknown, kind: ReportKind): ReportColumn[] => (
  Array.isArray(value)
    ? reportColumns[kind].filter((column) => value.includes(column))
    : [...reportColumns[kind]]
)

export const normalizeSelection = (value: unknown, today: Date = new Date()): ReportSelection => {
  const defaults = defaultSelection(today)
  if (typeof value !== 'object' || value === null) {
    return defaults
  }
  const record = value as Record<string, unknown>
  const columns = typeof record.columns === 'object' && record.columns !== null
    ? record.columns as Record<string, unknown>
    : {}
  return {
    kind: oneOf(record.kind, reportKinds, defaults.kind),
    from: dateValue(record.from, defaults.from),
    to: dateValue(record.to, defaults.to),
    periodBasis: oneOf(record.periodBasis, periodBases, defaults.periodBasis),
    organizationIds: strings(record.organizationIds),
    directionIds: strings(record.directionIds),
    includeNoDirection: record.includeNoDirection === true,
    programIds: strings(record.programIds),
    includeNoProgram: record.includeNoProgram === true,
    productIds: strings(record.productIds),
    includeNoProduct: record.includeNoProduct === true,
    managerIds: strings(record.managerIds),
    includeNoManager: record.includeNoManager === true,
    stages: strings(record.stages),
    columns: {
      PORTFOLIO: columnList(columns.PORTFOLIO, 'PORTFOLIO'),
      EVENTS: columnList(columns.EVENTS, 'EVENTS'),
      DEMAND: columnList(columns.DEMAND, 'DEMAND')
    },
    format: oneOf(record.format, reportFormats, defaults.format),
    groupBy: oneOf(record.groupBy, statisticsGroupings, defaults.groupBy),
    sortBy: oneOf(record.sortBy, demandSorts, defaults.sortBy)
  }
}

export const periodProblem = (selection: ReportSelection): string | null => (
  selection.from !== '' && selection.to !== '' && selection.from > selection.to
    ? 'Дата начала периода позже даты окончания.'
    : null
)

export const groupingProblem = (selection: ReportSelection): string | null => {
  if (selection.kind === 'PORTFOLIO' && selection.periodBasis === 'ACTIVITY' && selection.groupBy === 'MONTH') {
    return 'Группировка по месяцам недоступна для отбора «С событиями в периоде»: у взаимодействия бывают события в разных месяцах. Выберите отбор «Созданные в периоде» или вид «События за период».'
  }
  if (!groupingsFor(selection.kind).includes(selection.groupBy)) {
    return 'Заявки группируются по вузам, ИТ-направлениям, ИТ-программам, ответственным или месяцам.'
  }
  return null
}

export const selectionProblem = (selection: ReportSelection): string | null => (
  periodProblem(selection)
    ?? (selection.columns[selection.kind].length === 0 ? 'Выберите хотя бы одну колонку.' : null)
)

const baseRequest = (selection: ReportSelection) => {
  const demand = selection.kind === 'DEMAND'
  return {
    kind: selection.kind,
    from: selection.from === '' ? null : selection.from,
    to: selection.to === '' ? null : selection.to,
    periodBasis: selection.kind === 'PORTFOLIO' ? selection.periodBasis : null,
    filters: {
      organizationIds: selection.organizationIds,
      directionIds: selection.directionIds,
      includeNoDirection: selection.includeNoDirection,
      programIds: selection.programIds,
      includeNoProgram: selection.includeNoProgram,
      productIds: demand ? [] : selection.productIds,
      includeNoProduct: demand ? false : selection.includeNoProduct,
      managerIds: selection.managerIds,
      includeNoManager: selection.includeNoManager,
      stages: demand ? [] : selection.stages
    }
  }
}

export const toPreviewRequest = (selection: ReportSelection): ReportPreviewRequest => ({
  ...baseRequest(selection),
  columns: selection.columns[selection.kind],
  sortBy: selection.kind === 'DEMAND' ? selection.sortBy : null
})

export const toStatisticsRequest = (selection: ReportSelection): StatisticsRequest => ({
  ...baseRequest(selection),
  groupBy: selection.groupBy
})
