import type { ReportColumn, ReportKind } from '../../shared/api/client'
import type { AgreementSelection } from './reportAgreement'
import type { ReportSelection } from './reportSelection'

export type FilterGroupId =
  | 'organizationType' | 'organizations' | 'directions' | 'programs' | 'products' | 'managers' | 'stages'
  | 'workStatuses' | 'flags' | 'eventTypes' | 'change' | 'agreement' | 'periodBasis' | 'minDays'

// Какие отборы вид отчёта применяет на самом деле (то же, что отправляет baseRequest в reportSelection.ts).
export const filterVisibility = (kind: ReportKind) => ({
  programs: kind !== 'AGREEMENTS',
  products: kind !== 'DEMAND' && kind !== 'AGREEMENTS',
  stages: kind !== 'DEMAND' && kind !== 'AGREEMENTS',
  agreement: kind !== 'DEMAND' && kind !== 'AGREEMENTS' && kind !== 'MANAGER_WORK',
  workStatuses: kind === 'PORTFOLIO',
  flags: kind === 'PORTFOLIO',
  eventTypes: kind === 'EVENTS',
  change: kind === 'MANAGER_WORK',
  periodBasis: kind === 'PORTFOLIO',
  minDays: kind === 'PORTFOLIO',
  sort: kind === 'DEMAND'
})

const agreementActive = (value: AgreementSelection) => (
  value.vendorIds.length > 0 || value.notTransferred.length > 0 || value.licenseSigned !== '' || value.licenseExpiresBy !== ''
)

// Отбор «считается», если задан хоть один отбор в группе; в счётчике кнопки «Фильтры · N» — число таких групп.
export const activeFilterGroups = (selection: ReportSelection): FilterGroupId[] => {
  const visible = filterVisibility(selection.kind)
  const groups: [FilterGroupId, boolean][] = [
    ['organizationType', selection.organizationType !== ''],
    ['organizations', selection.organizationIds.length > 0],
    ['directions', visible.programs && (selection.directionIds.length > 0 || selection.includeNoDirection)],
    ['programs', visible.programs && (selection.programIds.length > 0 || selection.includeNoProgram)],
    ['products', visible.products && (selection.productIds.length > 0 || selection.includeNoProduct)],
    ['managers', selection.managerIds.length > 0 || selection.includeNoManager],
    ['stages', visible.stages && selection.stages.length > 0],
    ['workStatuses', visible.workStatuses && selection.workStatuses.length > 0],
    ['flags', visible.flags && selection.flags.length > 0],
    ['eventTypes', visible.eventTypes && selection.eventTypes.length > 0],
    ['change', visible.change && selection.change !== ''],
    ['agreement', visible.agreement && agreementActive(selection.agreement)],
    ['periodBasis', visible.periodBasis && selection.periodBasis !== 'CREATED'],
    ['minDays', visible.minDays && selection.minDaysOnStage !== '']
  ]
  return groups.filter(([, active]) => active).map(([id]) => id)
}

export const activeFilterCount = (selection: ReportSelection) => activeFilterGroups(selection).length

// Период, колонки, группировка диаграммы и сортировка — не фильтры: «Сбросить фильтры» их не трогает.
// Значения по умолчанию записаны здесь, а не берутся из reportSelection.ts, чтобы модель проверялась тестом без сборщика.
export const resetFilters = (selection: ReportSelection): ReportSelection => ({
  ...selection,
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
  agreement: { vendorIds: [], licenseSigned: '', licenseExpiresBy: '', notTransferred: [] },
  eventTypes: [],
  minDaysOnStage: '',
  change: ''
})

export type ColumnRow = { column: ReportColumn; checked: boolean }

// Один список: выбранные колонки в порядке вывода, ниже — не выбранные в порядке по умолчанию.
export const columnRows = (available: readonly ReportColumn[], selected: readonly ReportColumn[]): ColumnRow[] => {
  const chosen = selected.filter((column) => available.includes(column))
  return [
    ...chosen.map((column) => ({ column, checked: true })),
    ...available.filter((column) => !chosen.includes(column)).map((column) => ({ column, checked: false }))
  ]
}

export const setColumnChecked = (selected: readonly ReportColumn[], column: ReportColumn, checked: boolean): ReportColumn[] => {
  const rest = selected.filter((item) => item !== column)
  return checked ? [...rest, column] : rest
}

// Перетаскивание: колонка встаёт на место целевой, остальные сдвигаются.
export const moveColumnTo = (columns: readonly ReportColumn[], column: ReportColumn, target: ReportColumn): ReportColumn[] => {
  const from = columns.indexOf(column)
  const to = columns.indexOf(target)
  if (from < 0 || to < 0 || from === to) {
    return [...columns]
  }
  const moved = columns.filter((item) => item !== column)
  moved.splice(to, 0, column)
  return moved
}
