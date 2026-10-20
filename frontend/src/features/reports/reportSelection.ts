import type {
  ChartType,
  ManagerWorkChange,
  PeriodBasis,
  ReportColumn,
  ReportEventType,
  ReportKind,
  ReportPreviewRequest,
  StatisticsGroupBy,
  StatisticsRequest
} from '../../shared/api/client'
import { todayInMoscow } from '../../shared/format/datetime'
import { demandLinkPeriod } from './reportsRoute'
import { getModules, withoutPaymentColumns } from '../../shared/modules'
import {
  agreementColumnTitles,
  agreementColumns,
  agreementProblem,
  defaultAgreementSelection,
  normalizeAgreementSelection,
  toAgreementFilters,
  type AgreementSelection
} from './reportAgreement'
import { defaultPeriod } from './reportPeriods'

export type DemandSort = 'DEMAND_INDEX' | 'APPLICATIONS' | 'PAID_ORDERS' | 'PAID_STREAMS' | 'PARTICIPANTS' | 'LEARNERS_COMPLETED' | 'PARALLEL_RUNS'

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
  kindIds: string[]
  managerIds: string[]
  includeNoManager: boolean
  stages: string[]
  organizationType: OrganizationTypeFilter
  workStatuses: ReportWorkStatus[]
  flags: ReportFlag[]
  agreement: AgreementSelection
  eventTypes: ReportEventType[]
  minDaysOnStage: string
  change: ManagerWorkChange | ''
  managerAt: ManagerAt
  columns: Record<ReportKind, ReportColumn[]>
  groupBy: StatisticsGroupBy
  chartType: ChartType
  seriesBy: StatisticsGroupBy | null
  sortBy: DemandSort
}

export const reportKinds: readonly ReportKind[] = ['PORTFOLIO', 'EVENTS', 'SNAPSHOT', 'DURATION', 'DEMAND', 'AGREEMENTS', 'MANAGER_WORK']

export const demandSorts: readonly DemandSort[] = [
  'DEMAND_INDEX', 'APPLICATIONS', 'PAID_ORDERS', 'PAID_STREAMS', 'PARTICIPANTS', 'LEARNERS_COMPLETED', 'PARALLEL_RUNS'
]

export const availableDemandSorts = (): DemandSort[] => withoutPaymentColumns(demandSorts, getModules())

export const kindLabels: Record<ReportKind, string> = {
  PORTFOLIO: 'Портфель взаимодействий',
  EVENTS: 'События за период',
  SNAPSHOT: 'Состояние портфеля на дату',
  DURATION: 'Длительность этапов и время до входа в последний этап',
  DEMAND: 'Востребованность программ',
  AGREEMENTS: 'Реализация договоров',
  MANAGER_WORK: 'Работа менеджеров за период'
}

export const chartKinds: readonly ReportKind[] = ['PORTFOLIO', 'EVENTS', 'SNAPSHOT', 'DEMAND']

export const statisticsGroupings: readonly StatisticsGroupBy[] = [
  'STAGE', 'ORGANIZATION', 'DIRECTION', 'PROGRAM', 'PRODUCT', 'KIND', 'MILESTONE', 'MANAGER', 'MONTH'
]

export const groupingTitles: Record<StatisticsGroupBy, string> = {
  STAGE: 'по этапам',
  ORGANIZATION: 'по вузам',
  DIRECTION: 'по ИТ-направлениям',
  PROGRAM: 'по ИТ-программам',
  PRODUCT: 'по ИТ-продуктам',
  KIND: 'по видам взаимодействия',
  MILESTONE: 'по вехам (роль этапа для LMS)',
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
  DETAILS_UPDATED: 'Изменены данные взаимодействия',
  STATUS_CHANGED: 'Изменён статус взаимодействия',
  AGREEMENT_UPDATED: 'Изменены договор и передача',
  ATTACHMENT_DELETED: 'Удалён документ',
  STAGE_COMPLETED: 'Этап отмечен выполненным',
  STAGE_COMPLETION_CLEARED: 'Снята отметка выполнения этапа',
  ASSIGNMENT: 'Назначение, смена и снятие КАМ'
}

const demandGroupings: readonly StatisticsGroupBy[] = ['ORGANIZATION', 'DIRECTION', 'PROGRAM', 'MANAGER', 'MONTH']

export const groupingsFor = (kind: ReportKind): readonly StatisticsGroupBy[] => (
  kind === 'AGREEMENTS' || kind === 'MANAGER_WORK' ? [] : kind === 'DEMAND' ? demandGroupings
    // Вехи считаются по взаимодействиям: для событий за период они не строятся (сервер отвечает 400).
    : kind === 'PORTFOLIO' || kind === 'SNAPSHOT' ? statisticsGroupings : statisticsGroupings.filter((groupBy) => groupBy !== 'MILESTONE')
)

export const seriesGroupingsFor = (kind: ReportKind): readonly StatisticsGroupBy[] => (
  groupingsFor(kind).filter((groupBy) => groupBy !== 'MONTH' && groupBy !== 'MILESTONE')
)

const periodBases: readonly PeriodBasis[] = ['CREATED', 'ACTIVITY', 'ACTIVE']

const chartTypes: readonly ChartType[] = ['BAR', 'LINE']

export const managerWorkChanges: readonly (ManagerWorkChange | '')[] = ['', 'MOVED', 'NEW', 'COMPLETED']

// Чей КАМ в «Работе менеджеров»: на конец периода (как режим «Контроля» «За период») или текущий («Сейчас»).
export type ManagerAt = 'PERIOD_END' | 'CURRENT'

export const managerAtOptions: { id: ManagerAt; label: string }[] = [
  { id: 'PERIOD_END', label: 'На конец периода («За период»)' },
  { id: 'CURRENT', label: 'Текущий («Сейчас»)' }
]

export const managerWorkChangeLabels: Record<ManagerWorkChange | '', string> = {
  '': 'Все взаимодействия',
  MOVED: 'Перешли на другой этап',
  NEW: 'Новые взаимодействия',
  COMPLETED: 'Завершённые'
}

export const reportColumns: Record<ReportKind, readonly ReportColumn[]> = {
  PORTFOLIO: [
    'ORGANIZATION', 'INTERACTION', 'DIRECTION', 'PROGRAM', 'PRODUCTS', 'STAGE', 'DAYS_ON_STAGE', 'WORK_STATUS', 'WAITING',
    'PROBLEM', 'RISK', 'MANAGER', 'CREATED_AT', 'LAST_EVENT_AT', 'NEXT_ACTION', 'NEXT_ACTION_AT', ...agreementColumns
  ],
  EVENTS: [
    'EVENT_AT', 'ORGANIZATION', 'INTERACTION', 'EVENT_TYPE', 'FROM_STAGE', 'STAGE', 'COMMENT', 'AUTHOR',
    'MANAGER', 'DIRECTION', 'PROGRAM', 'PRODUCTS'
  ],
  DEMAND: [
    'DIRECTION', 'PROGRAM', 'DEMAND_RANK', 'DEMAND_INDEX', 'DEMAND_CONTRIBUTIONS', 'DEMAND_COVERAGE', 'DEMAND_TREND',
    'APPLICATIONS', 'PAID_ORDERS', 'PAID_STREAMS', 'PARTICIPANTS', 'ENROLLED_GROWTH', 'LEARNERS_COMPLETED', 'COMPLETION_SHARE',
    'COMPLETION_BASIS', 'ACTIVITY_SHARE', 'UNIVERSITY_DEMAND', 'PARALLEL_RUNS', 'MARKET_VACANCIES', 'MARKET_TREND'
  ],
  SNAPSHOT: ['ORGANIZATION', 'INTERACTION', 'DIRECTION', 'PROGRAM', 'PRODUCTS', 'STAGE', 'MANAGER', 'CREATED_AT', 'LAST_EVENT_AT'],
  DURATION: ['TEAM', 'PROGRAM', 'STAGE', 'COMPLETED', 'MEDIAN_DAYS', 'P90_DAYS', 'AVG_DAYS', 'MAX_DAYS', 'CURRENT', 'CURRENT_MAX_DAYS'],
  AGREEMENTS: [
    'ORGANIZATION', 'AGREEMENT', 'AGREEMENT_STATUS', 'AGREEMENT_TERM', 'ACTIVITY_KIND', 'ACTIVITY', 'PLANNED_VOLUME',
    'ACTUAL_VOLUME', 'VOLUME_UNIT', 'PLANNED_DATES', 'ACTUAL_DATES', 'MANAGER', 'ACTIVITY_STATUS', 'PARTICIPANTS', 'WORKS',
    'CONFIRMATIONS', 'CONFIRMATION_LINKS'
  ],
  MANAGER_WORK: [
    'MANAGER', 'ORGANIZATION', 'INTERACTION', 'PRODUCTS', 'PROGRAM', 'STAGE_AT_START', 'STAGE_AT_END', 'TRANSITIONS',
    'PERIOD_COMMENTS', 'NEXT_ACTION', 'NEXT_ACTION_AT', 'OVERDUE_DAYS', 'LMS_LEARNING', 'LMS_CHECK', 'TEAM', 'DIRECTION',
    'STAGE_POSITION', 'DUE_STATE',
    'DAYS_ON_STAGE', 'WORK_STATUS', 'PROBLEM', 'RISK', 'AGREEMENT_STATUS', 'AGREEMENT'
  ]
}

export const availableColumns = (kind: ReportKind): ReportColumn[] => (
  kind === 'DEMAND' ? withoutPaymentColumns(reportColumns.DEMAND, getModules()) : [...reportColumns[kind]]
)
export const managerWorkDefaultColumns: readonly ReportColumn[] = reportColumns.MANAGER_WORK.slice(0, 14)

const columnTitles: Record<ReportColumn, string> = {
  ORGANIZATION: 'Вуз',
  INTERACTION: 'Взаимодействие',
  DIRECTION: 'ИТ-направление',
  PROGRAM: 'ИТ-программа',
  PRODUCTS: 'ИТ-продукты',
  STAGE: 'Этап',
  DAYS_ON_STAGE: 'Дней на этапе',
  WORK_STATUS: 'Статус взаимодействия',
  WAITING: 'Ожидание',
  PROBLEM: 'Проблема',
  RISK: 'Риск',
  MANAGER: 'Ответственный',
  CREATED_AT: 'Создано',
  LAST_EVENT_AT: 'Последнее действие сотрудника',
  NEXT_ACTION: 'Следующий шаг',
  NEXT_ACTION_AT: 'Срок',
  EVENT_AT: 'Дата события',
  EVENT_TYPE: 'Действие',
  FROM_STAGE: 'Из этапа',
  COMMENT: 'Комментарий',
  AUTHOR: 'Автор',
  APPLICATIONS: 'Заявки (сайт, по дате подачи)',
  PAID_ORDERS: 'Оплаченные заявки (сайт, без ограничения периода)',
  PAID_STREAMS: 'Потоки с оплатами (сайт, без ограничения периода)',
  PARTICIPANTS: 'Обучающиеся (Moodle, участия — не уникальные люди)',
  LEARNERS_COMPLETED: 'Завершили (Moodle, участия)',
  COMPLETION_SHARE: 'Доля завершивших среди известных, % (Moodle, участия)',
  COMPLETION_BASIS: 'Основание доли (Moodle, участия)',
  PARALLEL_RUNS: 'Параллельные потоки (Moodle)',
  MARKET_VACANCIES: 'Спрос рынка: вакансии',
  MARKET_TREND: 'Тренд спроса, %',
  DEMAND_RANK: 'Место',
  DEMAND_INDEX: 'Индекс востребованности (0–100)',
  DEMAND_CONTRIBUTIONS: 'Вклад компонентов (почему такое место)',
  DEMAND_COVERAGE: 'Покрытие индекса',
  DEMAND_TREND: 'Тренд к прошлому периоду',
  ENROLLED_GROWTH: 'Прирост записанных к прошлому периоду (Moodle, участия)',
  ACTIVITY_SHARE: 'Активность за 7 дней, % (Moodle, свежие наблюдения)',
  UNIVERSITY_DEMAND: 'Спрос вузов: взаимодействия по программе (CRM)',
  ...agreementColumnTitles,
  TEAM: 'Команда',
  COMPLETED: 'Завершённых прохождений',
  MEDIAN_DAYS: 'Медиана длительности, дн.',
  P90_DAYS: '90-й процентиль длительности, дн.',
  AVG_DAYS: 'Средняя длительность, дн.',
  MAX_DAYS: 'Максимальная длительность, дн.',
  CURRENT: 'На этапе на конец периода',
  CURRENT_MAX_DAYS: 'Дольше всех на этапе, дн.',
  AGREEMENT: 'Договор',
  AGREEMENT_STATUS: 'Статус договора',
  AGREEMENT_TERM: 'Срок действия',
  ACTIVITY_KIND: 'Вид мероприятия',
  ACTIVITY: 'Мероприятие',
  PLANNED_VOLUME: 'Объём по плану',
  ACTUAL_VOLUME: 'Объём факт',
  VOLUME_UNIT: 'Единица',
  PLANNED_DATES: 'Плановые сроки',
  ACTUAL_DATES: 'Фактические сроки',
  ACTIVITY_STATUS: 'Статус мероприятия',
  WORKS: 'Связанные взаимодействия',
  CONFIRMATIONS: 'Подтверждения',
  CONFIRMATION_LINKS: 'Ссылки на подтверждения',
  STAGE_AT_START: 'Этап на начало периода',
  STAGE_AT_END: 'Этап на конец периода',
  STAGE_POSITION: 'Положение в маршруте',
  TRANSITIONS: 'Переходов за период',
  PERIOD_COMMENTS: 'Комментарии за период',
  DUE_STATE: 'Состояние срока',
  OVERDUE_DAYS: 'Просрочено, дн.',
  LMS_LEARNING: 'Обучение (LMS) сейчас',
  LMS_CHECK: 'Сверка этапа с LMS',
  LAST_STAFF_ACTION: 'Последнее действие сотрудника по вузу'
}

const kindColumnTitles: Partial<Record<ReportKind, Partial<Record<ReportColumn, string>>>> = {
  EVENTS: { MANAGER: 'Ответственный на момент события' },
  SNAPSHOT: { STAGE: 'Этап на дату', MANAGER: 'Ответственный на дату', LAST_EVENT_AT: 'Последнее действие сотрудника до даты' },
  DEMAND: { PARTICIPANTS: 'Обучающиеся в потоках периода (Moodle, участия — не уникальные люди)' },
  MANAGER_WORK: { MANAGER: 'Менеджер' }
}

export const columnTitle = (kind: ReportKind, column: ReportColumn) => (
  kindColumnTitles[kind]?.[column] ?? columnTitles[column]
)

export const maxDaysOnStage = 3650

export const defaultSelection = (today: Date = new Date(), kind: ReportKind = 'PORTFOLIO'): ReportSelection => ({
  kind,
  from: defaultPeriod(kind, todayInMoscow(today)).from,
  to: defaultPeriod(kind, todayInMoscow(today)).to,
  asOf: todayInMoscow(today),
  periodBasis: 'CREATED',
  organizationIds: [],
  directionIds: [],
  includeNoDirection: false,
  programIds: [],
  includeNoProgram: false,
  productIds: [],
  includeNoProduct: false,
  kindIds: [],
  managerIds: [],
  includeNoManager: false,
  stages: [],
  organizationType: '',
  workStatuses: [],
  flags: [],
  agreement: defaultAgreementSelection(),
  eventTypes: [],
  minDaysOnStage: '',
  change: '',
  managerAt: 'PERIOD_END',
  columns: {
    PORTFOLIO: reportColumns.PORTFOLIO.filter((column) => !agreementColumns.includes(column)),
    EVENTS: [...reportColumns.EVENTS],
    DEMAND: availableColumns('DEMAND'),
    SNAPSHOT: [...reportColumns.SNAPSHOT],
    DURATION: [...reportColumns.DURATION],
    AGREEMENTS: [...reportColumns.AGREEMENTS],
    MANAGER_WORK: [...managerWorkDefaultColumns]
  },
  groupBy: 'STAGE',
  chartType: 'BAR',
  seriesBy: null,
  sortBy: 'DEMAND_INDEX'
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
    ? [...new Set(value)].filter((column): column is ReportColumn => availableColumns(kind).some((item) => item === column))
    : kind === 'MANAGER_WORK' ? [...managerWorkDefaultColumns] : availableColumns(kind)
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
  const kind = oneOf(record.kind, reportKinds, defaults.kind)
  const period = defaultPeriod(kind, todayInMoscow(today))
  return {
    kind,
    from: dateValue(record.from, period.from),
    to: dateValue(record.to, period.to),
    asOf: dateValue(record.asOf, defaults.asOf),
    periodBasis: oneOf(record.periodBasis, periodBases, defaults.periodBasis),
    organizationIds: strings(record.organizationIds),
    directionIds: strings(record.directionIds),
    includeNoDirection: record.includeNoDirection === true,
    programIds: strings(record.programIds),
    includeNoProgram: record.includeNoProgram === true,
    productIds: strings(record.productIds),
    includeNoProduct: record.includeNoProduct === true,
    kindIds: strings(record.kindIds),
    managerIds: strings(record.managerIds),
    includeNoManager: record.includeNoManager === true,
    stages: strings(record.stages),
    organizationType: oneOf(record.organizationType, organizationTypeFilters, ''),
    workStatuses: reportWorkStatuses.filter((status) => strings(record.workStatuses).includes(status)),
    flags: reportFlags.filter((flag) => strings(record.flags).includes(flag)),
    agreement: normalizeAgreementSelection(record.agreement),
    eventTypes: strings(record.eventTypes).filter((type): type is ReportEventType => eventTypes.some((item) => item === type)),
    minDaysOnStage: daysValue(record.minDaysOnStage),
    change: oneOf(record.change, managerWorkChanges, ''),
    managerAt: oneOf(record.managerAt, managerAtOptions.map((option) => option.id), 'PERIOD_END'),
    columns: {
      PORTFOLIO: columnList(columns.PORTFOLIO, 'PORTFOLIO'),
      EVENTS: columnList(columns.EVENTS, 'EVENTS'),
      DEMAND: columnList(columns.DEMAND, 'DEMAND'),
      SNAPSHOT: columnList(columns.SNAPSHOT, 'SNAPSHOT'),
      DURATION: columnList(columns.DURATION, 'DURATION'),
      AGREEMENTS: columnList(columns.AGREEMENTS, 'AGREEMENTS'),
      MANAGER_WORK: columnList(columns.MANAGER_WORK, 'MANAGER_WORK')
    },
    groupBy: oneOf(record.groupBy, statisticsGroupings, defaults.groupBy),
    chartType: oneOf(record.chartType, chartTypes, defaults.chartType),
    seriesBy: statisticsGroupings.find((groupBy) => groupBy === record.seriesBy && groupBy !== 'MONTH' && groupBy !== 'MILESTONE') ?? null,
    sortBy: oneOf(record.sortBy, availableDemandSorts(), defaults.sortBy)
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
    asOf: definition.asOf ?? todayInMoscow(),
    periodBasis: definition.periodBasis ?? 'CREATED',
    organizationIds: filters.organizationIds ?? [],
    directionIds: filters.directionIds ?? [],
    includeNoDirection: filters.includeNoDirection ?? false,
    programIds: filters.programIds ?? [],
    includeNoProgram: filters.includeNoProgram ?? false,
    productIds: filters.productIds ?? [],
    includeNoProduct: filters.includeNoProduct ?? false,
    kindIds: filters.kindIds ?? [],
    managerIds: filters.managerIds ?? [],
    includeNoManager: filters.includeNoManager ?? false,
    stages: filters.stages ?? [],
    eventTypes: filters.eventTypes ?? [],
    minDaysOnStage: filters.minDaysOnStage ?? '',
    change: filters.change ?? '',
    managerAt: filters.managerAt ?? 'PERIOD_END',
    sortBy: definition.sortBy ?? selection.sortBy
  })
  const columns = definition.columns === undefined || definition.columns.length === 0
    ? [...(restored.kind === 'MANAGER_WORK' ? managerWorkDefaultColumns : reportColumns[restored.kind])]
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
    return 'Для отчёта «Реализация договоров» диаграмма не строится.'
  }
  if (selection.kind === 'MANAGER_WORK') {
    return 'Для отчёта «Работа менеджеров за период» диаграмма не строится.'
  }
  if (selection.kind === 'DURATION') {
    return 'Для отчёта «Длительность этапов и время до входа в последний этап» диаграмма не строится: показатели уже сгруппированы по командам, программам и этапам и видны в таблице.'
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
    ?? (selection.kind === 'DEMAND' || selection.kind === 'MANAGER_WORK' ? null : agreementProblem(selection.agreement))
)

const baseRequest = (selection: ReportSelection) => {
  const demand = selection.kind === 'DEMAND'
  const snapshot = selection.kind === 'SNAPSHOT'
  const agreements = selection.kind === 'AGREEMENTS'
  const managerWork = selection.kind === 'MANAGER_WORK'
  return {
    kind: selection.kind,
    from: snapshot || selection.from === '' ? null : selection.from,
    to: snapshot || selection.to === '' ? null : selection.to,
    asOf: snapshot && selection.asOf !== '' && selection.asOf !== todayInMoscow() ? selection.asOf : null,
    periodBasis: selection.kind === 'PORTFOLIO' ? selection.periodBasis : null,
    filters: {
      organizationIds: selection.organizationIds,
      directionIds: agreements ? [] : selection.directionIds,
      includeNoDirection: agreements ? false : selection.includeNoDirection,
      programIds: agreements ? [] : selection.programIds,
      includeNoProgram: agreements ? false : selection.includeNoProgram,
      productIds: demand || agreements ? [] : selection.productIds,
      includeNoProduct: demand || agreements ? false : selection.includeNoProduct,
      kindIds: agreements ? [] : selection.kindIds,

      managerIds: selection.managerIds,
      includeNoManager: selection.includeNoManager,
      stages: demand || agreements ? [] : selection.stages,
      organizationType: selection.organizationType === '' ? null : selection.organizationType,
      workStatuses: selection.kind === 'PORTFOLIO' ? selection.workStatuses : [],
      flags: selection.kind === 'PORTFOLIO' ? selection.flags : [],
      ...(demand || agreements || managerWork ? {} : { agreement: toAgreementFilters(selection.agreement) }),
      ...(managerWork ? { change: selection.change === '' ? null : selection.change, managerAt: selection.managerAt } : {}),
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

// Переход «Полный отчёт» из «Сводки команд»: отчёт открывается за тот же период и без отборов, как посчитан блок.
export const demandSelectionFromLink = (selection: ReportSelection, query: string, today: Date = new Date()): ReportSelection | null => {
  const period = demandLinkPeriod(query)
  return period === null ? null : { ...defaultSelection(today, 'DEMAND'), columns: selection.columns, ...period }
}
