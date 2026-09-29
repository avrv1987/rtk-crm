import type { InteractionIssue, InteractionMarks, IssueListParams } from '../../shared/api/client'

export type IssueKind = InteractionIssue['kind']
export type IssueLevel = NonNullable<InteractionIssue['riskLevel']>
export type IssueStatusFilter = 'OPEN' | 'RESOLVED' | 'ALL'

export const issueKinds: IssueKind[] = ['PROBLEM', 'RISK']
export const issueLevels: IssueLevel[] = ['MEDIUM', 'HIGH']

export const issueKindLabels: Record<IssueKind, string> = {
  PROBLEM: 'Проблема',
  RISK: 'Риск'
}

export const issueLevelLabels: Record<IssueLevel, string> = {
  MEDIUM: 'средний',
  HIGH: 'высокий'
}

export const issueStatusLabels: Record<IssueStatusFilter, string> = {
  OPEN: 'Открытые',
  RESOLVED: 'Решённые',
  ALL: 'Все'
}

export type IssueBadge = {
  key: 'problem' | 'risk'
  tone: 'overdue' | 'missing'
  label: string
  title?: string
}

export const issueBadges = (marks: Pick<InteractionMarks, 'problemCount' | 'riskLevel' | 'problems' | 'risks'>): IssueBadge[] => {
  const badges: IssueBadge[] = []
  if (marks.problemCount > 0) {
    badges.push({ key: 'problem', tone: 'overdue', label: `Проблемы: ${marks.problemCount}`, title: marks.problems ?? undefined })
  }
  if (marks.riskLevel !== null) {
    badges.push({
      key: 'risk',
      tone: marks.riskLevel === 'HIGH' ? 'overdue' : 'missing',
      label: `Риск: ${issueLevelLabels[marks.riskLevel]}`,
      title: marks.risks ?? undefined
    })
  }
  return badges
}

export const issueTitle = (issue: Pick<InteractionIssue, 'kind' | 'riskLevel'>) => (
  issue.riskLevel === null ? issueKindLabels[issue.kind] : `${issueKindLabels[issue.kind]}, ${issueLevelLabels[issue.riskLevel]}`
)

export const calendarDaysBetween = (from: string, to: string) => (
  Math.max(0, Math.round((Date.parse(`${to.slice(0, 10)}T00:00:00Z`) - Date.parse(`${from.slice(0, 10)}T00:00:00Z`)) / 86_400_000))
)

export const isIssueOverdue = (issue: Pick<InteractionIssue, 'status' | 'dueOn'>, today: string) => (
  issue.status === 'OPEN' && issue.dueOn !== null && issue.dueOn < today
)

export type IssueFilters = {
  kind: IssueKind | ''
  riskLevel: IssueLevel | ''
  responsible: string
  organizationId: string
  overdue: boolean
  status: IssueStatusFilter
  page: number
}

export const emptyIssueFilters: IssueFilters = {
  kind: '',
  riskLevel: '',
  responsible: '',
  organizationId: '',
  overdue: false,
  status: 'OPEN',
  page: 0
}

const oneOf = <T extends string>(values: readonly T[], value: string | null): T | '' => (
  values.find((item) => item === value) ?? ''
)

export const issueFiltersFromQuery = (query: string): IssueFilters => {
  const params = new URLSearchParams(query)
  const page = Number(params.get('page'))
  return {
    kind: oneOf(issueKinds, params.get('kind')),
    riskLevel: oneOf(issueLevels, params.get('riskLevel')),
    responsible: params.get('responsible') ?? '',
    organizationId: params.get('organizationId') ?? '',
    overdue: params.get('overdue') === 'true',
    status: oneOf(['RESOLVED', 'ALL'] as const, params.get('status')) || 'OPEN',
    page: Number.isInteger(page) && page > 0 ? page : 0
  }
}

export const issueListParams = (filters: IssueFilters): Omit<IssueListParams, 'page' | 'size'> => ({
  kind: filters.kind || undefined,
  riskLevel: filters.riskLevel || undefined,
  responsible: filters.responsible || undefined,
  organizationId: filters.organizationId || undefined,
  overdue: filters.overdue || undefined,
  status: filters.status === 'OPEN' ? undefined : filters.status
})

export const issueQueryFromFilters = (filters: IssueFilters) => {
  const params = new URLSearchParams()
  Object.entries(issueListParams(filters)).forEach(([key, value]) => {
    if (value !== undefined) {
      params.set(key, String(value))
    }
  })
  if (filters.page > 0) {
    params.set('page', String(filters.page))
  }
  return params.toString()
}

export const issuesHref = (organizationId: string, interactionId: string) => (
  `#/organizations/${organizationId}/${interactionId}?issues=1`
)
