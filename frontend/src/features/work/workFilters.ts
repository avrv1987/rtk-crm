import type { InteractionDue, InteractionFlagFilter, InteractionWorkStatusFilter } from '../../shared/api/client'
import { reportFlagLabels, reportFlags } from '../reports/reportSelection'

export type WorkFilters = {
  q: string
  due: InteractionDue | ''
  stage: string
  organizationId: string
  flag: InteractionFlagFilter | ''
  licenseExpiresBy: string
  responsible: string
  status: InteractionWorkStatusFilter
  minDaysOnStage: number | null
  page: number
}

export const unassigned = 'UNASSIGNED'

export const dueOptions: { value: InteractionDue | ''; label: string }[] = [
  { value: '', label: 'Все' },
  { value: 'OVERDUE', label: 'Просрочено' },
  { value: 'TODAY', label: 'Срок сегодня' },
  { value: 'THIS_WEEK', label: 'Срок на этой неделе' },
  { value: 'LATER', label: 'Срок после этой недели' },
  { value: 'NO_NEXT_STEP', label: 'Без шага или срока' }
]

const currentYear = new Date().getFullYear()

export const licenseYears = Array.from({ length: 7 }, (_, index) => (currentYear - 1 + index).toString())

export const statusOptions: { value: InteractionWorkStatusFilter; label: string }[] = [
  { value: 'ACTIVE', label: 'Активные' },
  { value: 'PAUSED', label: 'Приостановленные' },
  { value: 'COMPLETED', label: 'Завершённые' },
  { value: 'ALL', label: 'Все, включая завершённые' }
]

export const stageDayOptions = [7, 14, 30, 60, 90]

export const emptyWorkFilters: WorkFilters = {
  q: '',
  due: '',
  stage: '',
  organizationId: '',
  flag: '',
  licenseExpiresBy: '',
  responsible: '',
  status: 'ACTIVE',
  minDaysOnStage: null,
  page: 0
}

const isDue = (value: string): value is InteractionDue => dueOptions.some((option) => option.value !== '' && option.value === value)

export const workFlags: readonly InteractionFlagFilter[] = [...reportFlags, 'RISK_OR_PROBLEM']

export const workFlagLabels: Record<InteractionFlagFilter, string> = { ...reportFlagLabels, RISK_OR_PROBLEM: 'Проблемы и риски' }

export const isFlag = (value: string): value is InteractionFlagFilter => workFlags.some((flag) => flag === value)

const isStatus = (value: string): value is InteractionWorkStatusFilter => statusOptions.some((option) => option.value === value)

export const positiveInteger = (value: string | null) => {
  const number = Number(value ?? '')
  return Number.isInteger(number) && number > 0 ? number : null
}

export const filtersFromQuery = (query: string): WorkFilters => {
  const params = new URLSearchParams(query)
  const due = params.get('due') ?? ''
  const flag = params.get('flag') ?? ''
  const status = params.get('status') ?? 'ACTIVE'
  return {
    q: params.get('q') ?? '',
    due: isDue(due) ? due : '',
    stage: params.get('stage') ?? '',
    organizationId: params.get('organization') ?? '',
    flag: isFlag(flag) ? flag : '',
    licenseExpiresBy: /^\d{4}$/.test(params.get('license') ?? '') ? params.get('license') ?? '' : '',
    responsible: params.get('responsible') ?? '',
    status: isStatus(status) ? status : 'ACTIVE',
    minDaysOnStage: positiveInteger(params.get('minDaysOnStage')),
    page: positiveInteger(params.get('page')) ?? 0
  }
}

export const queryFromFilters = (filters: WorkFilters) => {
  const params = new URLSearchParams()
  const set = (name: string, value: string, skip = '') => {
    if (value !== skip) {
      params.set(name, value)
    }
  }
  set('q', filters.q.trim())
  set('due', filters.due)
  set('stage', filters.stage)
  set('organization', filters.organizationId)
  set('flag', filters.flag)
  set('license', filters.licenseExpiresBy)
  set('responsible', filters.responsible)
  set('status', filters.status, 'ACTIVE')
  set('minDaysOnStage', filters.minDaysOnStage === null ? '' : filters.minDaysOnStage.toString())
  set('page', filters.page > 0 ? filters.page.toString() : '')
  return params.toString()
}

export const activeFilterCount = (filters: WorkFilters) => [
  filters.due !== '',
  filters.stage.length > 0,
  filters.organizationId.length > 0,
  filters.responsible.length > 0,
  filters.status !== 'ACTIVE',
  filters.minDaysOnStage !== null,
  filters.flag !== '',
  filters.licenseExpiresBy !== ''
].filter(Boolean).length

export const hasFilters = (filters: WorkFilters) => filters.q.trim().length > 0 || activeFilterCount(filters) > 0
