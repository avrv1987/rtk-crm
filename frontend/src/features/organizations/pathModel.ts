import { apiClient } from '../../shared/api/client.ts'
import type { components } from '../../shared/api/openapi'
import { formatCalendarDate, formatMoscowDateTime } from '../../shared/format/datetime.ts'
import { agreementStatusLabels } from '../agreements/agreementModel.ts'

export type OrganizationPath = components['schemas']['OrganizationPath']
export type PathWork = components['schemas']['OrganizationPathWork']

export const organizationPathApi = {
  get: (organizationId: string) => apiClient.request<OrganizationPath>(`/api/organizations/${encodeURIComponent(organizationId)}/path`)
}

export type PathSort = 'PROGRAM' | 'DUE' | 'STAGE'

export const pathSortOptions: { value: PathSort; label: string }[] = [
  { value: 'PROGRAM', label: 'По программам' },
  { value: 'DUE', label: 'По сроку шага' },
  { value: 'STAGE', label: 'По этапу' }
]

export type PathGroup = { key: string; label: string | null; works: PathWork[] }

const statusOrder: Record<PathWork['status'], number> = { ACTIVE: 0, PAUSED: 1, COMPLETED: 2 }
const dueOrder: Record<PathWork['due'], number> = { OVERDUE: 0, NO_NEXT_STEP: 1, THIS_WEEK: 2, IN_ORDER: 3 }

const share = (work: PathWork) => (work.stages.length === 0 ? 0 : work.stagePosition / work.stages.length)

// Идущие выше завершённых, внутри — новые сверху: следующий цикл оказывается над предыдущим.
const byStatusAndDate = (a: PathWork, b: PathWork) => statusOrder[a.status] - statusOrder[b.status] || b.createdAt.localeCompare(a.createdAt)

const byDue = (a: PathWork, b: PathWork) => statusOrder[a.status] - statusOrder[b.status] || dueOrder[a.due] - dueOrder[b.due]
  || (a.nextActionAt ?? '9999').localeCompare(b.nextActionAt ?? '9999')

const byStage = (a: PathWork, b: PathWork) => statusOrder[a.status] - statusOrder[b.status] || share(b) - share(a) || byDue(a, b)

export const pathGroups = (works: PathWork[], sort: PathSort): PathGroup[] => {
  if (sort !== 'PROGRAM') {
    return [{ key: 'all', label: null, works: [...works].sort(sort === 'DUE' ? byDue : byStage) }]
  }
  const groups = new Map<string, PathGroup>()
  works.forEach((work) => {
    const key = work.programId ?? 'none'
    const group = groups.get(key) ?? { key, label: work.programName ?? 'Без программы', works: [] }
    group.works.push(work)
    groups.set(key, group)
  })
  return [...groups.values()]
    .sort((a, b) => Number(a.key === 'none') - Number(b.key === 'none') || (a.label ?? '').localeCompare(b.label ?? '', 'ru'))
    .map((group) => ({ ...group, works: [...group.works].sort(byStatusAndDate) }))
}

export type SegmentState = 'done' | 'current' | 'ahead'

export const pathSegments = (work: PathWork): { name: string; optional: boolean; state: SegmentState }[] => (
  work.stages.map((stage, index) => ({
    name: stage.name,
    optional: stage.optional,
    state: index + 1 < work.stagePosition || work.status === 'COMPLETED' && index + 1 === work.stagePosition
      ? 'done'
      : index + 1 === work.stagePosition ? 'current' : 'ahead'
  }))
)

export const stageLine = (work: PathWork) => [
  work.stages.length > 0 && work.stagePosition > 0 ? `Этап ${work.stagePosition} из ${work.stages.length}: ${work.stageName}` : `Этап: ${work.stageName}`,
  ...(work.daysOnStage === null || work.daysOnStage === undefined ? [] : [`на этапе ${work.daysOnStage} дн.`])
].join(' · ')

export type DueTone = 'overdue' | 'missing' | 'planned' | 'done'

export const dueLine = (work: PathWork): { tone: DueTone; text: string } => {
  if (work.status === 'COMPLETED') {
    return { tone: 'done', text: 'Завершено' }
  }
  const when = work.nextActionAt ? formatMoscowDateTime(work.nextActionAt) : null
  if (work.status === 'PAUSED') {
    return { tone: 'missing', text: when === null ? 'Приостановлено' : `Приостановлено до ${when}` }
  }
  const action = work.nextAction?.trim() ?? ''
  if (work.due === 'OVERDUE') {
    return { tone: 'overdue', text: `Просрочен с ${when ?? ''}${action === '' ? '' : `: ${action}`}` }
  }
  if (work.due === 'NO_NEXT_STEP') {
    return { tone: 'missing', text: action === '' ? 'Следующий шаг не задан' : `Срок не задан: ${action}` }
  }
  return { tone: 'planned', text: `${when ?? ''}${action === '' ? '' : `: ${action}`}` }
}

export const agreementLine = (work: PathWork) => {
  if (work.agreementStatus === null || work.agreementStatus === undefined) {
    return 'Договор не привязан'
  }
  return `Договор${work.agreementNumber ? ` № ${work.agreementNumber}` : ''}: ${agreementStatusLabels[work.agreementStatus].toLowerCase()}`
    + (work.agreementValidUntil ? `, до ${formatCalendarDate(work.agreementValidUntil)}` : '')
}

export const pathSummary = (works: PathWork[]) => {
  const active = works.filter((work) => work.status === 'ACTIVE').length
  const programs = new Set(works.map((work) => work.programId ?? 'none')).size
  const overdue = works.filter((work) => work.due === 'OVERDUE').length
  return { active, programs, overdue, completed: works.filter((work) => work.status === 'COMPLETED').length }
}
