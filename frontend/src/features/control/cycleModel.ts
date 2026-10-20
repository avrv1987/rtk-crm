import type { Organization, WorkControl, WorkControlItem } from '../../shared/api/client'
import { isStuck, type ControlFilters, type ControlView } from './controlModel.ts'

export type ControlDisplay = NonNullable<ControlView['display']>

export const displayOptions: { value: ControlDisplay; label: string }[] = [
  { value: 'managers', label: 'По менеджерам' },
  { value: 'stages', label: 'Этапы' },
  { value: 'list', label: 'Списком' }
]

export type MatrixAxis = 'PROGRAM' | 'PRODUCT'

export const axisOptions: { value: MatrixAxis; label: string }[] = [
  { value: 'PROGRAM', label: 'По программам' },
  { value: 'PRODUCT', label: 'По продуктам' }
]

export type MatrixCell = { works: number; organizations: number; overdue: number; noStep: number; stuck: number }

export type MatrixRow = { key: string; id: string | null; label: string; cells: MatrixCell[]; total: MatrixCell }

export type MatrixRoute = {
  key: string
  name: string
  kindIds: string[]
  stages: string[]
  // Этап прежней версии шаблона: в текущем маршруте его нет, поэтому без номера и с пометкой.
  former: boolean[]
  // Этап, который КАМ добавил в маршрут своей карточки: стоит на своём месте, без номера шаблона и с пометкой.
  card: boolean[]
  // Номер этапа в шаблоне; у этапов карточек и прежних версий — null.
  numbers: (number | null)[]
  rows: MatrixRow[]
  // Итог по этапу считает каждое взаимодействие один раз, даже если у него несколько продуктов.
  totals: MatrixCell[]
  total: MatrixCell
}

type Bucket = { works: Set<string>; organizations: Set<string>; overdue: number; noStep: number; stuck: number }

const emptyBucket = (): Bucket => ({ works: new Set(), organizations: new Set(), overdue: 0, noStep: 0, stuck: 0 })

const add = (bucket: Bucket, item: WorkControlItem, stuckDays: number) => {
  if (bucket.works.has(item.interactionId)) {
    return
  }
  bucket.works.add(item.interactionId)
  bucket.organizations.add(item.organizationId)
  bucket.overdue += item.due === 'OVERDUE' ? 1 : 0
  bucket.noStep += item.due === 'NO_NEXT_STEP' ? 1 : 0
  bucket.stuck += isStuck(item, stuckDays) ? 1 : 0
}

const cell = (bucket: Bucket | undefined): MatrixCell => (bucket === undefined
  ? { works: 0, organizations: 0, overdue: 0, noStep: 0, stuck: 0 }
  : { works: bucket.works.size, organizations: bucket.organizations.size, overdue: bucket.overdue, noStep: bucket.noStep, stuck: bucket.stuck })

// Этап строки — на конец периода: по нему же работает отбор по этапу, поэтому щелчок по ячейке даёт те же строки.
export const matrixStage = (item: Pick<WorkControlItem, 'stage' | 'stageAtEnd'>) => item.stageAtEnd ?? item.stage

const axisKeys = (item: WorkControlItem, axis: MatrixAxis): { id: string | null; label: string }[] => {
  if (axis === 'PROGRAM') {
    return [item.programId !== null && item.programId !== undefined && item.program
      ? { id: item.programId, label: item.program }
      : { id: null, label: 'Без программы' }]
  }
  const ids = item.productIds ?? []
  return ids.length === 0 ? [{ id: null, label: 'Без продукта' }] : ids.map((id, index) => ({ id, label: item.products[index] ?? id }))
}

const noTemplate = '-'

type RouteLike = NonNullable<WorkControl['routes']>[number]

const isFormer = (route: RouteLike | undefined, stage: string) => route !== undefined
  && !(route.cardStages ?? []).includes(stage)
  && (!route.stages.includes(stage) || (route.formerStages ?? []).includes(stage))

const templateNumbers = (route: RouteLike | undefined, stages: string[]) => {
  let number = 0
  return stages.map((stage) => (isFormer(route, stage) || (route?.cardStages ?? []).includes(stage) ? null : ++number))
}

// Матрица «программа (или продукт) × этап» из уже полученных строк «Контроля»: те же отборы, права и режим, что у плиток.
// Маршруты разные у разных видов взаимодействия, поэтому таблица строится отдельно по каждому маршруту, столбцы —
// его этапы по порядку (пустые тоже, чтобы было видно звено маршрута).
export const stageMatrix = (control: WorkControl, axis: MatrixAxis, stuckDays: number): MatrixRoute[] => {
  const items = control.teams.flatMap((team) => team.managers.flatMap((manager) => manager.works))
  const byRoute = new Map<string, WorkControlItem[]>()
  items.forEach((item) => {
    const key = item.routeId ?? noTemplate
    byRoute.set(key, [...(byRoute.get(key) ?? []), item])
  })
  const known = (control.routes ?? []).map((route) => route.templateId ?? noTemplate)
  const order = [...known.filter((key) => byRoute.has(key)), ...[...byRoute.keys()].filter((key) => !known.includes(key))]
  return order.map((key) => {
    const route = control.routes?.find((item) => (item.templateId ?? noTemplate) === key)
    const routeItems = byRoute.get(key) ?? []
    const stages = [...(route?.stages ?? [])]
    routeItems.forEach((item) => {
      if (!stages.includes(matrixStage(item))) {
        stages.push(matrixStage(item))
      }
    })
    const rows = new Map<string, { id: string | null; label: string; cells: Map<string, Bucket>; total: Bucket }>()
    const totals = new Map<string, Bucket>()
    const total = emptyBucket()
    routeItems.forEach((item) => {
      const stage = matrixStage(item)
      axisKeys(item, axis).forEach(({ id, label }) => {
        const rowKey = id ?? `none:${label}`
        const row = rows.get(rowKey) ?? { id, label, cells: new Map(), total: emptyBucket() }
        rows.set(rowKey, row)
        const bucket = row.cells.get(stage) ?? emptyBucket()
        row.cells.set(stage, bucket)
        add(bucket, item, stuckDays)
        add(row.total, item, stuckDays)
      })
      const stageTotal = totals.get(stage) ?? emptyBucket()
      totals.set(stage, stageTotal)
      add(stageTotal, item, stuckDays)
      add(total, item, stuckDays)
    })
    return {
      key,
      name: route?.templateName ?? 'Без шаблона маршрута',
      kindIds: [...new Set(routeItems.flatMap((item) => (item.kindId ? [item.kindId] : [])))],
      stages,
      former: stages.map((stage) => isFormer(route, stage)),
      card: stages.map((stage) => (route?.cardStages ?? []).includes(stage)),
      numbers: templateNumbers(route, stages),
      rows: [...rows.entries()]
        .sort(([, a], [, b]) => Number(a.id === null) - Number(b.id === null) || a.label.localeCompare(b.label, 'ru'))
        .map(([rowKey, row]) => ({
          key: rowKey,
          id: row.id,
          label: row.label,
          cells: stages.map((stage) => cell(row.cells.get(stage))),
          total: cell(row.total)
        })),
      totals: stages.map((stage) => cell(totals.get(stage))),
      total: cell(total)
    }
  })
}

export type CellTone = 'danger' | 'warning' | 'plain' | 'empty'

export const cellTone = (value: MatrixCell): CellTone => (
  value.works === 0 ? 'empty' : value.overdue > 0 ? 'danger' : value.noStep + value.stuck > 0 ? 'warning' : 'plain'
)

export const cellText = (value: MatrixCell, stuckDays: number) => [
  `взаимодействий ${value.works}, вузов ${value.organizations}`,
  ...(value.overdue > 0 ? [`просрочено ${value.overdue}`] : []),
  ...(value.noStep > 0 ? [`без шага или срока ${value.noStep}`] : []),
  ...(value.stuck > 0 ? [`на этапе дольше ${stuckDays} дн. ${value.stuck}`] : [])
].join(', ')

// Щелчок по ячейке: отбор программы или продукта и этапа, список строк. При нескольких маршрутах одноимённый этап есть
// у разных видов, поэтому добавляется отбор по видам этого маршрута.
export const cellFilters = (
  filters: ControlFilters,
  axis: MatrixAxis,
  rowId: string,
  stage: string,
  route: Pick<MatrixRoute, 'kindIds'>,
  severalRoutes: boolean
): ControlFilters => ({
  ...filters,
  programId: axis === 'PROGRAM' ? [rowId] : filters.programId,
  productId: axis === 'PRODUCT' ? [rowId] : filters.productId,
  stage: [stage],
  kindId: severalRoutes && route.kindIds.length > 0 ? route.kindIds : filters.kindId
})

export type FlatRow = { item: WorkControlItem; managerName: string | null; teamName: string | null }

const dueOrder: Record<WorkControlItem['due'], number> = { OVERDUE: 0, NO_NEXT_STEP: 1, THIS_WEEK: 2, IN_ORDER: 3 }

// «Списком»: те же строки, что по менеджерам, одной таблицей — сначала просроченные и без шага, затем по сроку.
export const flatRows = (control: WorkControl): FlatRow[] => control.teams
  .flatMap((team) => team.managers.flatMap((manager) => manager.works.map((item) => ({
    item,
    managerName: manager.managerName ?? null,
    teamName: team.teamName ?? null
  }))))
  .sort((a, b) => dueOrder[a.item.due] - dueOrder[b.item.due]
    || (a.item.nextActionAt ?? '9999').localeCompare(b.item.nextActionAt ?? '9999')
    || a.item.organizationName.localeCompare(b.item.organizationName, 'ru'))

// «Вузы без КАМ» при отборе по программе, продукту, направлению, виду или этапу: только вузы без КАМ, у которых есть
// подходящие строки. Вуз без взаимодействий к программе не привязан и в такой отбор не входит. Отбор по вузу
// применяется к списку напрямую.
export const unassignedFor = (
  organizations: Organization[],
  control: WorkControl | null,
  filters: ControlFilters,
  teamId: string | null | undefined
): Organization[] => {
  const candidates = organizations.filter((item) => (
    item.requiresAssignment && item.status !== 'ARCHIVED'
    && (teamId === null || teamId === undefined || item.teamId === teamId)
    && (filters.organizationId.length === 0 || filters.organizationId.includes(item.id))
  ))
  if (!unassignedByWork(filters)) {
    return candidates
  }
  const withRows = new Set((control?.teams ?? []).flatMap((team) => team.managers
    .filter((manager) => manager.managerId === null)
    .flatMap((manager) => manager.works.map((item) => item.organizationId))))
  return candidates.filter((item) => withRows.has(item.id))
}

export const unassignedByWork = (filters: ControlFilters) => (
  filters.programId.length + filters.productId.length + filters.directionId.length + filters.kindId.length + filters.stage.length > 0
)
