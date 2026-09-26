import type {
  ChartType,
  PeriodBasis,
  ReportColumn,
  ReportEventType,
  ReportFormat,
  ReportKind,
  ReportPreviewRequest,
  StatisticsGroupBy,
  StatisticsRequest
} from '../../shared/api/client'
import {
  agreementColumnTitles,
  agreementColumns,
  agreementProblem,
  defaultAgreementSelection,
  normalizeAgreementSelection,
  toAgreementFilters,
  type AgreementSelection
} from './reportAgreement'

export type DemandSort = 'APPLICATIONS' | 'PAID_ORDERS' | 'PAID_STREAMS' | 'PARTICIPANTS' | 'LEARNERS_COMPLETED' | 'PARALLEL_RUNS'

export type OrganizationTypeFilter = '' | 'UNIVERSITY' | 'COLLEGE' | 'SCHOOL'

export const organizationTypeFilters: readonly OrganizationTypeFilter[] = ['', 'UNIVERSITY', 'COLLEGE', 'SCHOOL']

export type ReportWorkStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED'

export const reportWorkStatuses: readonly ReportWorkStatus[] = ['ACTIVE', 'PAUSED', 'COMPLETED']

export const reportWorkStatusLabels: Record<ReportWorkStatus, string> = {
  ACTIVE: 'Активна',
  PAUSED: 'Приостановлена',
  COMPLETED: 'Завершена'
}

export type ReportFlag = 'WAITING_UNIVERSITY' | 'WAITING_RTK' | 'PROBLEM' | 'RISK'

export const reportFlags: readonly ReportFlag[] = ['WAITING_UNIVERSITY', 'WAITING_RTK', 'PROBLEM', 'RISK']

export const reportFlagLabels: Record<ReportFlag, string> = {
  WAITING_UNIVERSITY: 'Ждём вуз',
  WAITING_RTK: 'Ждём РТК',
  PROBLEM: 'Есть проблема',
  RISK: 'Есть риск'
}

export type ReportSelection = {
  kind: ReportKind
  from: string
  to: string
  asOf: string
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
  organizationType: OrganizationTypeFilter
  workStatuses: ReportWorkStatus[]
  flags: ReportFlag[]
  agreement: AgreementSelection
  eventTypes: ReportEventType[]
  minDaysOnStage: string
  columns: Record<ReportKind, ReportColumn[]>
  format: ReportFormat
  groupBy: StatisticsGroupBy
  chartType: ChartType
  seriesBy: StatisticsGroupBy | null
  sortBy: DemandSort
}

export const reportKinds: readonly ReportKind[] = ['PORTFOLIO', 'EVENTS', 'SNAPSHOT', 'DURATION', 'DEMAND', 'AGREEMENTS']

export const demandSorts: readonly DemandSort[] = [
  'APPLICATIONS', 'PAID_ORDERS', 'PAID_STREAMS', 'PARTICIPANTS', 'LEARNERS_COMPLETED', 'PARALLEL_RUNS'
]

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

export const eventTypes: readonly ReportEventType[] = [
  'CREATED', 'TRANSITIONED', 'COMMENTED', 'STAGES_EDITED', 'PLAN_UPDATED', 'DETAILS_UPDATED', 'STATUS_CHANGED',
  'AGREEMENT_UPDATED', 'ATTACHMENT_DELETED', 'STAGE_COMPLETED', 'STAGE_COMPLETION_CLEARED', 'ASSIGNMENT'
]

export const eventTypeTitles: Record<ReportEventType, string> = {
  CREATED: 'Создание',
  TRANSITIONED: 'Переход',
  COMMENTED: 'Комментарий',
  STAGES_EDITED: 'Изменены этапы карточки',
  PLAN_UPDATED: 'Изменён план',
  DETAILS_UPDATED: 'Изменены данные работы',
  STATUS_CHANGED: 'Изменён статус работы',
  AGREEMENT_UPDATED: 'Изменены договор и передача',
  ATTACHMENT_DELETED: 'Удалён документ',
  STAGE_COMPLETED: 'Этап отмечен выполненным',
  STAGE_COMPLETION_CLEARED: 'Снята отметка выполнения этапа',
  ASSIGNMENT: 'Назначение, смена и снятие КАМ'
}

const demandGroupings: readonly StatisticsGroupBy[] = ['ORGANIZATION', 'DIRECTION', 'PROGRAM', 'MANAGER', 'MONTH']

export const groupingsFor = (kind: ReportKind): readonly StatisticsGroupBy[] => (
  kind === 'AGREEMENTS' ? [] : kind === 'DEMAND' ? demandGroupings : statisticsGroupings
)

export const seriesGroupingsFor = (kind: ReportKind): readonly StatisticsGroupBy[] => (
  groupingsFor(kind).filter((groupBy) => groupBy !== 'MONTH')
)

const periodBases: readonly PeriodBasis[] = ['CREATED', 'ACTIVITY', 'ACTIVE']

const chartTypes: readonly ChartType[] = ['BAR', 'LINE']

export const reportColumns: Record<ReportKind, readonly ReportColumn[]> = {
  PORTFOLIO: [
    'ORGANIZATION', 'INTERACTION', 'DIRECTION', 'PROGRAM', 'PRODUCTS', 'STAGE', 'DAYS_ON_STAGE', 'WORK_STATUS', 'WAITING',
    'PROBLEM', 'RISK', 'MANAGER', 'CREATED_AT', 'LAST_EVENT_AT', 'NEXT_ACTION', 'NEXT_ACTION_AT', ...agreementColumns
  ],
  EVENTS: [
    'EVENT_AT', 'ORGANIZATION', 'INTERACTION', 'EVENT_TYPE', 'FROM_STAGE', 'STAGE', 'COMMENT', 'AUTHOR',
    'MANAGER', 'DIRECTION', 'PROGRAM', 'PRODUCTS'
  ],
  DEMAND: ['DIRECTION', 'PROGRAM', 'APPLICATIONS', 'PAID_ORDERS', 'PAID_STREAMS', 'PARTICIPANTS', 'LEARNERS_COMPLETED', 'PARALLEL_RUNS'],
  SNAPSHOT: ['ORGANIZATION', 'INTERACTION', 'DIRECTION', 'PROGRAM', 'PRODUCTS', 'STAGE', 'MANAGER', 'CREATED_AT', 'LAST_EVENT_AT'],
  DURATION: ['TEAM', 'PROGRAM', 'STAGE', 'COMPLETED', 'AVG_DAYS', 'MAX_DAYS', 'CURRENT', 'CURRENT_MAX_DAYS'],
  AGREEMENTS: [
    'ORGANIZATION', 'AGREEMENT', 'AGREEMENT_STATUS', 'AGREEMENT_TERM', 'ACTIVITY_KIND', 'ACTIVITY', 'PLANNED_VOLUME',
    'ACTUAL_VOLUME', 'VOLUME_UNIT', 'PLANNED_DATES', 'ACTUAL_DATES', 'MANAGER', 'ACTIVITY_STATUS', 'PARTICIPANTS', 'WORKS',
    'CONFIRMATIONS', 'CONFIRMATION_LINKS'
  ]
}

const columnTitles: Record<ReportColumn, string> = {
  ORGANIZATION: 'Вуз',
  INTERACTION: 'Взаимодействие',
  DIRECTION: 'ИТ-направление',
  PROGRAM: 'ИТ-программа',
  PRODUCTS: 'ИТ-продукты',
  STAGE: 'Этап',
  DAYS_ON_STAGE: 'Дней на этапе',
  WORK_STATUS: 'Статус работы',
  WAITING: 'Ожидание',
  PROBLEM: 'Проблема',
  RISK: 'Риск',
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
  PAID_ORDERS: 'Оплаченные заявки (сайт)',
  PAID_STREAMS: 'Потоки с оплатами (сайт)',
  PARTICIPANTS: 'Обучающиеся (Moodle)',
  LEARNERS_COMPLETED: 'Завершили (Moodle)',
  PARALLEL_RUNS: 'Параллельные потоки (Moodle)',
  ...agreementColumnTitles,
  TEAM: 'Команда',
  COMPLETED: 'Завершённых прохождений',
  AVG_DAYS: 'Средняя длительность, дн.',
  MAX_DAYS: 'Максимальная длительность, дн.',
  CURRENT: 'На этапе на конец периода',
  CURRENT_MAX_DAYS: 'Дольше всех на этапе, дн.',
  AGREEMENT: 'Соглашение',
  AGREEMENT_STATUS: 'Статус соглашения',
  AGREEMENT_TERM: 'Срок действия',
  ACTIVITY_KIND: 'Вид мероприятия',
  ACTIVITY: 'Мероприятие',
  PLANNED_VOLUME: 'Объём по плану',
  ACTUAL_VOLUME: 'Объём факт',
  VOLUME_UNIT: 'Единица',
  PLANNED_DATES: 'Плановые сроки',
  ACTUAL_DATES: 'Фактические сроки',
  ACTIVITY_STATUS: 'Статус мероприятия',
  WORKS: 'Связанные работы',
  CONFIRMATIONS: 'Подтверждения',
  CONFIRMATION_LINKS: 'Ссылки на подтверждения'
}

const kindColumnTitles: Partial<Record<ReportKind, Partial<Record<ReportColumn, string>>>> = {
  EVENTS: { MANAGER: 'Ответственный на момент события' },
  SNAPSHOT: { STAGE: 'Этап на дату', MANAGER: 'Ответственный на дату', LAST_EVENT_AT: 'Последнее событие до даты' }
}

export const columnTitle = (kind: ReportKind, column: ReportColumn) => (
  kindColumnTitles[kind]?.[column] ?? columnTitles[column]
)

export const isoDate = (date: Date) => [
  date.getFullYear().toString(),
  (date.getMonth() + 1).toString().padStart(2, '0'),
  date.getDate().toString().padStart(2, '0')
].join('-')

const moscowDate = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Europe/Moscow',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit'
})

export const moscowToday = (now: Date = new Date()) => moscowDate.format(now)

export const maxDaysOnStage = 3650

export const defaultSelection = (today: Date = new Date()): ReportSelection => ({
  kind: 'PORTFOLIO',
  from: isoDate(new Date(today.getFullYear(), today.getMonth(), 1)),
  to: isoDate(new Date(today.getFullYear(), today.getMonth() + 1, 0)),
  asOf: moscowToday(today),
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
  organizationType: '',
  workStatuses: [],
  flags: [],
  agreement: defaultAgreementSelection(),
  eventTypes: [],
  minDaysOnStage: '',
  columns: {
    PORTFOLIO: reportColumns.PORTFOLIO.filter((column) => !agreementColumns.includes(column)),
    EVENTS: [...reportColumns.EVENTS],
    DEMAND: [...reportColumns.DEMAND],
    SNAPSHOT: [...reportColumns.SNAPSHOT],
    DURATION: [...reportColumns.DURATION],
    AGREEMENTS: [...reportColumns.AGREEMENTS]
  },
  format: 'XLSX',
  groupBy: 'STAGE',
  chartType: 'BAR',
  seriesBy: null,
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

const daysValue = (value: unknown) => {
  if (typeof value === 'number' && Number.isInteger(value)) {
    return String(value)
  }
  return typeof value === 'string' && /^\d{0,4}$/.test(value) ? value : ''
}

const columnList = (value: unknown, kind: ReportKind): ReportColumn[] => (
  Array.isArray(value)
    ? [...new Set(value)].filter((column): column is ReportColumn => reportColumns[kind].some((item) => item === column))
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
    asOf: dateValue(record.asOf, defaults.asOf),
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
    organizationType: oneOf(record.organizationType, organizationTypeFilters, ''),
    workStatuses: reportWorkStatuses.filter((status) => strings(record.workStatuses).includes(status)),
    flags: reportFlags.filter((flag) => strings(record.flags).includes(flag)),
    agreement: normalizeAgreementSelection(record.agreement),
    eventTypes: strings(record.eventTypes).filter((type): type is ReportEventType => eventTypes.some((item) => item === type)),
    minDaysOnStage: daysValue(record.minDaysOnStage),
    columns: {
      PORTFOLIO: columnList(columns.PORTFOLIO, 'PORTFOLIO'),
      EVENTS: columnList(columns.EVENTS, 'EVENTS'),
      DEMAND: columnList(columns.DEMAND, 'DEMAND'),
      SNAPSHOT: columnList(columns.SNAPSHOT, 'SNAPSHOT'),
      DURATION: columnList(columns.DURATION, 'DURATION'),
      AGREEMENTS: columnList(columns.AGREEMENTS, 'AGREEMENTS')
    },
    format: oneOf(record.format, reportFormats, defaults.format),
    groupBy: oneOf(record.groupBy, statisticsGroupings, defaults.groupBy),
    chartType: oneOf(record.chartType, chartTypes, defaults.chartType),
    seriesBy: statisticsGroupings.find((groupBy) => groupBy === record.seriesBy && groupBy !== 'MONTH') ?? null,
    sortBy: oneOf(record.sortBy, demandSorts, defaults.sortBy)
  }
}

export const moveColumn = (columns: ReportColumn[], column: ReportColumn, offset: -1 | 1): ReportColumn[] => {
  const index = columns.indexOf(column)
  const target = index + offset
  if (index < 0 || target < 0 || target >= columns.length) {
    return columns
  }
  const moved = [...columns]
  moved[index] = columns[target]
  moved[target] = column
  return moved
}

export const applyDefinition = (selection: ReportSelection, definition: ReportPreviewRequest): ReportSelection => {
  const filters = definition.filters ?? {}
  const restored = normalizeSelection({
    ...selection,
    kind: definition.kind,
    from: definition.from ?? '',
    to: definition.to ?? '',
    asOf: definition.asOf ?? moscowToday(),
    periodBasis: definition.periodBasis ?? 'CREATED',
    organizationIds: filters.organizationIds ?? [],
    directionIds: filters.directionIds ?? [],
    includeNoDirection: filters.includeNoDirection ?? false,
    programIds: filters.programIds ?? [],
    includeNoProgram: filters.includeNoProgram ?? false,
    productIds: filters.productIds ?? [],
    includeNoProduct: filters.includeNoProduct ?? false,
    managerIds: filters.managerIds ?? [],
    includeNoManager: filters.includeNoManager ?? false,
    stages: filters.stages ?? [],
    eventTypes: filters.eventTypes ?? [],
    minDaysOnStage: filters.minDaysOnStage ?? '',
    sortBy: definition.sortBy ?? selection.sortBy
  })
  const columns = definition.columns === undefined || definition.columns.length === 0
    ? [...reportColumns[restored.kind]]
    : columnList(definition.columns, restored.kind)
  return {
    ...restored,
    groupBy: groupingsFor(restored.kind).includes(restored.groupBy) ? restored.groupBy : 'PROGRAM',
    columns: { ...restored.columns, [restored.kind]: columns }
  }
}

export const periodProblem = (selection: ReportSelection): string | null => (
  selection.kind !== 'SNAPSHOT' && selection.from !== '' && selection.to !== '' && selection.from > selection.to
    ? 'Дата начала периода позже даты окончания.'
    : null
)

export const lineChartSelected = (selection: ReportSelection) => (
  selection.groupBy === 'MONTH' && selection.chartType === 'LINE'
)

export const groupingProblem = (selection: ReportSelection): string | null => {
  if (selection.kind === 'AGREEMENTS') {
    return 'Для отчёта «Реализация соглашений» диаграмма не строится.'
  }
  if (selection.kind === 'DURATION') {
    return 'Для отчёта «Длительность этапов и цикла» диаграмма не строится: показатели уже сгруппированы по командам, программам и этапам и видны в таблице.'
  }
  if (selection.kind === 'PORTFOLIO' && selection.periodBasis !== 'CREATED' && selection.groupBy === 'MONTH') {
    return `Группировка по месяцам недоступна для отбора «${selection.periodBasis === 'ACTIVE' ? 'Активные в периоде' : 'С событиями в периоде'}»: месяц создания взаимодействия может лежать вне периода. Выберите отбор «Созданные в периоде» или вид «События за период».`
  }
  if (!groupingsFor(selection.kind).includes(selection.groupBy)) {
    return 'Заявки группируются по вузам, ИТ-направлениям, ИТ-программам, ответственным или месяцам.'
  }
  return null
}

const daysProblem = (selection: ReportSelection): string | null => {
  if (selection.kind !== 'PORTFOLIO' || selection.minDaysOnStage === '') {
    return null
  }
  const days = Number(selection.minDaysOnStage)
  return Number.isInteger(days) && days >= 1 && days <= maxDaysOnStage
    ? null
    : `Укажите в отборе «На этапе дольше» целое число дней от 1 до ${maxDaysOnStage} или оставьте поле пустым.`
}

export const selectionProblem = (selection: ReportSelection): string | null => (
  periodProblem(selection)
    ?? daysProblem(selection)
    ?? (selection.columns[selection.kind].length === 0 ? 'Выберите хотя бы одну колонку.' : null)
    ?? (selection.kind === 'DEMAND' ? null : agreementProblem(selection.agreement))
)

const baseRequest = (selection: ReportSelection) => {
  const demand = selection.kind === 'DEMAND'
  const snapshot = selection.kind === 'SNAPSHOT'
  const agreements = selection.kind === 'AGREEMENTS'
  return {
    kind: selection.kind,
    from: snapshot || selection.from === '' ? null : selection.from,
    to: snapshot || selection.to === '' ? null : selection.to,
    asOf: snapshot && selection.asOf !== '' && selection.asOf !== moscowToday() ? selection.asOf : null,
    periodBasis: selection.kind === 'PORTFOLIO' ? selection.periodBasis : null,
    filters: {
      organizationIds: selection.organizationIds,
      directionIds: agreements ? [] : selection.directionIds,
      includeNoDirection: agreements ? false : selection.includeNoDirection,
      programIds: agreements ? [] : selection.programIds,
      includeNoProgram: agreements ? false : selection.includeNoProgram,
      productIds: demand || agreements ? [] : selection.productIds,
      includeNoProduct: demand || agreements ? false : selection.includeNoProduct,
      managerIds: selection.managerIds,
      includeNoManager: selection.includeNoManager,
      stages: demand || agreements ? [] : selection.stages,
      organizationType: selection.organizationType === '' ? null : selection.organizationType,
      workStatuses: selection.kind === 'PORTFOLIO' ? selection.workStatuses : [],
      flags: selection.kind === 'PORTFOLIO' ? selection.flags : [],
      ...(demand || agreements ? {} : { agreement: toAgreementFilters(selection.agreement) }),
      eventTypes: selection.kind === 'EVENTS' ? selection.eventTypes : [],
      minDaysOnStage: selection.kind === 'PORTFOLIO' && selection.minDaysOnStage !== '' ? Number(selection.minDaysOnStage) : null
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
  groupBy: selection.groupBy,
  seriesBy: lineChartSelected(selection) && selection.seriesBy !== null && seriesGroupingsFor(selection.kind).includes(selection.seriesBy)
    ? selection.seriesBy
    : null
})
