export type RouteStage = { id: string; name: string; order: number; optional: boolean }
export type RouteTransition = { fromStageId: string; toStageId: string; commentRequired: boolean }
export type Route = { name: string; stages: RouteStage[]; transitions: RouteTransition[] }

export type FileStage = { key?: string; name: string; optional: boolean }
export type FileTransition = { from: number; to: number; commentRequired: boolean }
export type RouteFile = {
  format: typeof routeFileFormat
  formatVersion: 1
  name: string
  sourceVersion?: number
  stages: FileStage[]
  transitions: FileTransition[]
}

export type RouteDiff = {
  added: string[]
  removed: string[]
  renamed: { from: string; to: string }[]
  moved: string[]
  optionalChanged: string[]
  transitionsAdded: string[]
  transitionsRemoved: string[]
  commentChanged: string[]
}

export const routeFileFormat = 'rtk-crm-workflow'

const ordered = (route: Route) => [...route.stages].sort((left, right) => left.order - right.order)

export const routeToFile = (route: Route, sourceVersion?: number): RouteFile => {
  const stages = ordered(route)
  const index = new Map(stages.map((stage, position) => [stage.id, position]))
  return {
    format: routeFileFormat,
    formatVersion: 1,
    name: route.name,
    ...(sourceVersion === undefined ? {} : { sourceVersion }),
    stages: stages.map((stage) => ({ key: stage.id, name: stage.name, optional: stage.optional })),
    transitions: route.transitions.flatMap((transition) => {
      const from = index.get(transition.fromStageId)
      const to = index.get(transition.toStageId)
      return from === undefined || to === undefined ? [] : [{ from, to, commentRequired: transition.commentRequired }]
    })
  }
}

const isRecord = (value: unknown): value is Record<string, unknown> => (
  typeof value === 'object' && value !== null && !Array.isArray(value)
)

export const parseRouteFile = (text: string): { file: RouteFile } | { error: string } => {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    return { error: 'Файл не похож на шаблон этапов: это не JSON.' }
  }
  if (!isRecord(value) || value.format !== routeFileFormat || value.formatVersion !== 1) {
    return { error: 'Файл не похож на шаблон этапов: выгрузите его кнопкой «Скачать файл».' }
  }
  if (typeof value.name !== 'string' || value.name.trim().length === 0 || value.name.length > 200) {
    return { error: 'В файле нет названия шаблона.' }
  }
  if (!Array.isArray(value.stages) || value.stages.length === 0 || value.stages.length > 100) {
    return { error: 'В файле должно быть от 1 до 100 этапов.' }
  }
  const stages: FileStage[] = []
  for (const [position, stage] of value.stages.entries()) {
    if (!isRecord(stage) || typeof stage.name !== 'string' || stage.name.trim().length === 0
      || stage.name.length > 200 || typeof stage.optional !== 'boolean'
      || (stage.key !== undefined && typeof stage.key !== 'string')) {
      return { error: `Этап ${position + 1} в файле заполнен неверно.` }
    }
    stages.push({ ...(typeof stage.key === 'string' ? { key: stage.key } : {}), name: stage.name.trim(), optional: stage.optional })
  }
  if (!Array.isArray(value.transitions) || value.transitions.length > 1000) {
    return { error: 'В файле нет списка переходов.' }
  }
  const transitions: FileTransition[] = []
  for (const [position, transition] of value.transitions.entries()) {
    if (!isRecord(transition) || !Number.isInteger(transition.from) || !Number.isInteger(transition.to)
      || typeof transition.commentRequired !== 'boolean'
      || (transition.from as number) < 0 || (transition.to as number) < 0
      || (transition.from as number) >= stages.length || (transition.to as number) >= stages.length) {
      return { error: `Переход ${position + 1} в файле ведёт в несуществующий этап.` }
    }
    transitions.push({ from: transition.from as number, to: transition.to as number, commentRequired: transition.commentRequired })
  }
  return {
    file: {
      format: routeFileFormat,
      formatVersion: 1,
      name: value.name.trim(),
      ...(Number.isInteger(value.sourceVersion) ? { sourceVersion: value.sourceVersion as number } : {}),
      stages,
      transitions
    }
  }
}

const stableSubsequence = (left: string[], right: string[]): Set<string> => {
  const lengths = left.map(() => new Array<number>(right.length + 1).fill(0))
  lengths.push(new Array<number>(right.length + 1).fill(0))
  for (let i = left.length - 1; i >= 0; i -= 1) {
    for (let j = right.length - 1; j >= 0; j -= 1) {
      lengths[i][j] = left[i] === right[j] ? lengths[i + 1][j + 1] + 1 : Math.max(lengths[i + 1][j], lengths[i][j + 1])
    }
  }
  const kept = new Set<string>()
  let i = 0
  let j = 0
  while (i < left.length && j < right.length) {
    if (left[i] === right[j]) {
      kept.add(left[i])
      i += 1
      j += 1
    } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
      i += 1
    } else {
      j += 1
    }
  }
  return kept
}

export const diffRoutes = (before: Route, after: Route): RouteDiff => {
  const oldStages = ordered(before)
  const newStages = ordered(after)
  const oldById = new Map(oldStages.map((stage) => [stage.id, stage]))
  const newById = new Map(newStages.map((stage) => [stage.id, stage]))
  const common = (stages: RouteStage[], other: Map<string, RouteStage>) => stages.filter((stage) => other.has(stage.id)).map((stage) => stage.id)
  const kept = stableSubsequence(common(oldStages, newById), common(newStages, oldById))
  const edgeName = (transition: RouteTransition, stages: Map<string, RouteStage>) => (
    `«${stages.get(transition.fromStageId)?.name ?? '?'}» → «${stages.get(transition.toStageId)?.name ?? '?'}»`
  )
  const edgeKey = (transition: RouteTransition) => `${transition.fromStageId}>${transition.toStageId}`
  const oldEdges = new Map(before.transitions.map((transition) => [edgeKey(transition), transition]))
  const newEdges = new Map(after.transitions.map((transition) => [edgeKey(transition), transition]))
  return {
    added: newStages.filter((stage) => !oldById.has(stage.id)).map((stage) => stage.name),
    removed: oldStages.filter((stage) => !newById.has(stage.id)).map((stage) => stage.name),
    renamed: newStages.flatMap((stage) => {
      const previous = oldById.get(stage.id)
      return previous !== undefined && previous.name !== stage.name ? [{ from: previous.name, to: stage.name }] : []
    }),
    moved: newStages.filter((stage) => oldById.has(stage.id) && !kept.has(stage.id)).map((stage) => stage.name),
    optionalChanged: newStages.flatMap((stage) => {
      const previous = oldById.get(stage.id)
      return previous !== undefined && previous.optional !== stage.optional
        ? [`«${stage.name}» — ${stage.optional ? 'теперь необязательный' : 'теперь обязательный'}`]
        : []
    }),
    transitionsAdded: after.transitions.filter((transition) => !oldEdges.has(edgeKey(transition))).map((transition) => edgeName(transition, newById)),
    transitionsRemoved: before.transitions.filter((transition) => !newEdges.has(edgeKey(transition))).map((transition) => edgeName(transition, oldById)),
    commentChanged: after.transitions.flatMap((transition) => {
      const previous = oldEdges.get(edgeKey(transition))
      return previous !== undefined && previous.commentRequired !== transition.commentRequired
        ? [`${edgeName(transition, newById)} — ${transition.commentRequired ? 'нужен комментарий' : 'без комментария'}`]
        : []
    })
  }
}

export const isEmptyDiff = (diff: RouteDiff) => Object.values(diff).every((items) => items.length === 0)

export const routeFileName = (name: string, version: number) => (
  `${name.replace(/[\\/:*?"<>|]+/g, ' ').trim() || 'шаблон'} — версия ${version}.json`
)
