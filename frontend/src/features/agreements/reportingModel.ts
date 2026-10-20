import type { components } from '../../shared/api/openapi'

type Schemas = components['schemas']
export type ActivityStatus = Schemas['AgreementActivityStatus']
export type ActivityAttribute = Schemas['AgreementActivityAttribute']
export type ReferenceCategory = Schemas['AgreementReferenceCategory']
export type ReferenceItem = Schemas['AgreementReferenceItem']
export type ActivityListStatus = Schemas['ActivityListStatus']
export type ActivityListAction = Schemas['ActivityListAction']
export type ActivityListChannel = Schemas['ActivityListEvent']['channel']
export type ReportColumn = Schemas['AgreementReportColumn']
export type ReportColumnOption = Schemas['AgreementReportColumnOption']
export type ReportQuantity = Schemas['AgreementReportQuantity']
export type ReportingSettings = Schemas['AgreementReportingSettings']
export type ReportingSettingsInput = Schemas['AgreementReportingSettingsInput']
export type DeadlineInput = Schemas['AgreementReportingDeadlineInput']
export type Deadline = Schemas['AgreementReportingDeadline']

export const activityStatuses: readonly ActivityStatus[] = ['PLANNED', 'IN_PROGRESS', 'DONE', 'CONFIRMED', 'REJECTED', 'CANCELLED']

export const activityStatusLabels: Record<ActivityStatus, string> = {
  PLANNED: 'Запланировано',
  IN_PROGRESS: 'Выполняется',
  DONE: 'Проведено',
  CONFIRMED: 'Подтверждено вузом',
  REJECTED: 'Отклонено',
  CANCELLED: 'Отменено'
}

export const activityListStatusLabels: Record<ActivityListStatus, string> = {
  DRAFT: 'Проект',
  REVIEW: 'На согласовании',
  APPROVED: 'Согласован',
  APPROVED_SILENTLY: 'Согласован без ответа',
  RETURNED: 'Возвращён'
}

export const activityListChannelLabels: Record<ActivityListChannel, string> = {
  KAM: 'КАМ',
  COORDINATOR: 'координатор вуза',
  SYSTEM: 'система'
}

export const referenceCategories: readonly ReferenceCategory[] = ['EXECUTOR', 'EDUCATION_LEVEL', 'UNIT']

export const referenceCategoryLabels: Record<ReferenceCategory, string> = {
  EXECUTOR: 'Исполнители',
  EDUCATION_LEVEL: 'Уровни образования',
  UNIT: 'Единицы'
}

// Правило сервера (AgreementService.requireAttributes): у отменённого и отклонённого проверки нет, документ и факт
// количества нужны только проведённому или подтверждённому мероприятию.
export const isHeld = (status: ActivityStatus) => status === 'DONE' || status === 'CONFIRMED'

export type RequiredFields = {
  educationLevel: boolean
  participants: boolean
  unit: boolean
  plannedVolume: boolean
  actualVolume: boolean
  cost: boolean
  executor: boolean
  document: boolean
}

export const requiredFields = (attributes: readonly ActivityAttribute[], status: ActivityStatus): RequiredFields => {
  const dropped = status === 'CANCELLED' || status === 'REJECTED'
  const has = (attribute: ActivityAttribute) => !dropped && attributes.includes(attribute)
  return {
    educationLevel: has('EDUCATION_LEVEL'),
    participants: has('PARTICIPANTS'),
    unit: has('UNIT_QUANTITY'),
    plannedVolume: has('UNIT_QUANTITY'),
    actualVolume: has('UNIT_QUANTITY') && isHeld(status),
    cost: has('COST'),
    executor: has('EXECUTOR'),
    document: has('DOCUMENT') && isHeld(status)
  }
}

// Архивное значение справочника предлагается, только если оно уже стоит у записи.
export const referenceOptions = (items: readonly ReferenceItem[], category: ReferenceCategory, currentId: string | null | undefined) => (
  items.filter((item) => item.category === category && (!item.archived || item.id === currentId))
)

const moneyCodes = new Set(['COST', 'RATE', 'PLANNED_VALUATION', 'ACTUAL_VALUATION'])

export const isMoneyColumn = (code: string) => moneyCodes.has(code)

// Как сервер (AgreementReportService.text): два знака, пробел между разрядами, запятая; пустое — пусто.
export const formatMoney = (value: number | null | undefined) => {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return ''
  }
  const [whole, fraction] = Math.abs(value).toFixed(2).split('.')
  return `${value < 0 ? '-' : ''}${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ' ')},${fraction}`
}

export const formatReportCell = (code: string, value: string | number | null | undefined) => {
  if (value === null || value === undefined) {
    return ''
  }
  if (typeof value === 'number') {
    return isMoneyColumn(code) ? formatMoney(value) : String(value).replace('.', ',')
  }
  return value
}

export const columnTitle = (column: ReportColumn, options: readonly ReportColumnOption[]) => (
  column.title?.trim() || options.find((option) => option.code === column.code)?.defaultTitle || column.code
)

export const reportTable = (
  report: { columns: ReportColumn[]; rows: (string | number | null)[][] },
  options: readonly ReportColumnOption[]
) => ({
  headers: report.columns.map((column) => ({ code: column.code, title: columnTitle(column, options), money: isMoneyColumn(column.code) })),
  rows: report.rows.map((row) => report.columns.map((column, index) => formatReportCell(column.code, row[index])))
})

// Та же строка, что в выгрузке (AgreementReportService.quantities).
export const formatQuantities = (quantities: readonly ReportQuantity[]) => (
  quantities.map((quantity) => `${quantity.unit}: ${quantity.planned} / ${quantity.actual}`).join('; ')
)

export const thresholdMetLabel = (met: boolean | null | undefined) => (met === null || met === undefined ? 'нет базы' : met ? 'да' : 'нет')

export const moveColumn = <T>(items: readonly T[], index: number, delta: -1 | 1): T[] => {
  const target = index + delta
  if (index < 0 || index >= items.length || target < 0 || target >= items.length) {
    return [...items]
  }
  const next = [...items]
  ;[next[index], next[target]] = [next[target], next[index]]
  return next
}

// Включённая колонка встаёт в конец набора, выключенная убирается; подпись сохраняется, пока колонка в наборе.
export const toggleColumn = (columns: readonly ReportColumn[], code: string, on: boolean): ReportColumn[] => {
  const present = columns.some((column) => column.code === code)
  if (on) {
    return present ? [...columns] : [...columns, { code, title: null }]
  }
  return columns.filter((column) => column.code !== code)
}

export const templateInput = (columns: readonly ReportColumn[]) => ({
  columns: columns.map((column) => ({ code: column.code, title: column.title?.trim() ? column.title.trim() : null }))
})

export const daysLeftLabel = (daysLeft: number) => {
  if (daysLeft === 0) {
    return 'сегодня'
  }
  return daysLeft < 0 ? `просрочено на ${-daysLeft} дн.` : `через ${daysLeft} дн.`
}

export type DeadlineDraft = { name: string; dueOn: string; deliverable: string; annual: boolean }

export const deadlineDrafts = (deadlines: readonly Deadline[]): DeadlineDraft[] => deadlines.map((deadline) => ({
  name: deadline.name,
  dueOn: deadline.dueOn,
  deliverable: deadline.deliverable ?? '',
  annual: deadline.annual
}))

export const deadlinesInput = (drafts: readonly DeadlineDraft[]): { deadlines: DeadlineInput[] } => ({
  deadlines: drafts.map((draft) => ({
    name: draft.name.trim(),
    dueOn: draft.dueOn,
    deliverable: draft.deliverable.trim() === '' ? null : draft.deliverable.trim(),
    annual: draft.annual
  }))
})

export const deadlinesValid = (drafts: readonly DeadlineDraft[]) => drafts.every((draft) => draft.name.trim() !== '' && draft.dueOn !== '')

export type SettingsDraft = {
  periodKind: ReportingSettings['periodKind']
  academicYearStartMonth: string
  silenceDays: string
  repeatSilenceDays: string
  reminderDays: string
  thresholdEnabled: boolean
  thresholdPercent: string
  thresholdBase: string
  thresholdBaseLabel: string
}

const text = (value: number | null | undefined) => (value === null || value === undefined ? '' : String(value))
const numberOrNull = (value: string) => (value.trim() === '' ? null : Number(value.replace(',', '.')))

export const settingsDraft = (settings: ReportingSettings): SettingsDraft => ({
  periodKind: settings.periodKind,
  academicYearStartMonth: String(settings.academicYearStartMonth),
  silenceDays: text(settings.silenceDays),
  repeatSilenceDays: text(settings.repeatSilenceDays),
  reminderDays: String(settings.reminderDays),
  thresholdEnabled: settings.thresholdEnabled,
  thresholdPercent: text(settings.thresholdPercent),
  thresholdBase: text(settings.thresholdBase),
  thresholdBaseLabel: settings.thresholdBaseLabel ?? ''
})

export const settingsInput = (draft: SettingsDraft): ReportingSettingsInput => ({
  periodKind: draft.periodKind,
  academicYearStartMonth: Number(draft.academicYearStartMonth),
  silenceDays: numberOrNull(draft.silenceDays),
  repeatSilenceDays: numberOrNull(draft.repeatSilenceDays),
  reminderDays: Number(draft.reminderDays),
  thresholdEnabled: draft.thresholdEnabled,
  thresholdPercent: numberOrNull(draft.thresholdPercent),
  thresholdBase: numberOrNull(draft.thresholdBase),
  thresholdBaseLabel: draft.thresholdBaseLabel.trim() === '' ? null : draft.thresholdBaseLabel.trim()
})

export const parseMoney = numberOrNull

// Перечень правится, пока он у школы: проект или возвращённый вузом.
export const listEditable = (status: ActivityListStatus) => status === 'DRAFT' || status === 'RETURNED'

export const monthNames = [
  'январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь'
] as const
