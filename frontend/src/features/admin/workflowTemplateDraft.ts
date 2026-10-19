import type { WorkflowTemplate, WorkflowTemplateCreate, WorkflowTransitionInput } from '../../shared/api/client'
import { stagePropertiesOf, type StageLmsRole } from '../documents/stageProperties.ts'

export type StageDraft = {
  id?: string
  name: string
  optional: boolean
  requiresAgreement: boolean
  lmsRole: StageLmsRole | null
  documentKinds: string[]
}

export type TransitionDraft = WorkflowTransitionInput

export type TemplateDraft = {
  name: string
  stages: StageDraft[]
  transitions: TransitionDraft[]
  comment: string
}

export const emptyStage = (): StageDraft => ({ name: '', optional: false, requiresAgreement: false, lmsRole: null, documentKinds: [] })

export const emptyDraft = (): TemplateDraft => ({
  name: '',
  stages: [emptyStage()],
  transitions: [],
  comment: ''
})

type TemplateLike = Pick<WorkflowTemplate, 'name' | 'stages' | 'transitions'>

export const draftFromTemplate = (template: TemplateLike): TemplateDraft => {
  const stages = [...template.stages]
    .sort((left, right) => left.order - right.order)
    .map((stage) => ({ id: stage.id, name: stage.name, optional: stage.optional, ...stagePropertiesOf(stage) }))
  const orderById = new Map(template.stages.map((stage) => [stage.id, stage.order]))
  const transitions = template.transitions.flatMap((transition) => {
    const fromOrder = orderById.get(transition.fromStageId)
    const toOrder = orderById.get(transition.toStageId)
    return fromOrder === undefined || toOrder === undefined
      ? []
      : [{ fromOrder, toOrder, commentRequired: transition.commentRequired }]
  })
  return { name: template.name, stages, transitions, comment: '' }
}

export const payloadFromDraft = (draft: TemplateDraft): WorkflowTemplateCreate => ({
  name: draft.name.trim(),
  stages: draft.stages.map((stage, order) => ({
    name: stage.name.trim(),
    order,
    optional: stage.optional,
    requiresAgreement: stage.requiresAgreement,
    lmsRole: stage.lmsRole,
    documentKinds: stage.documentKinds,
    ...(stage.id === undefined ? {} : { id: stage.id })
  })),
  transitions: draft.transitions,
  ...(draft.comment.trim().length === 0 ? {} : { comment: draft.comment.trim() })
})

/** Черновик изменён относительно открытого: закрыть такой без вопроса — потерять введённое. */
export const draftChanged = (initial: TemplateDraft, current: TemplateDraft): boolean => (
  JSON.stringify(initial) !== JSON.stringify(current)
)

const stageTitle = (draft: TemplateDraft, order: number) => `«${draft.stages[order]?.name.trim() || `этап ${order + 1}`}»`

// Те же правила, что у сервера (WorkflowGraph.validate и WorkflowTemplateService): кнопка сохранения не даёт отправить
// маршрут, который сервер отклонит. Ошибки собираются все сразу: по одной их приходилось исправлять вслепую, круг за кругом.
export const draftProblems = (draft: TemplateDraft): string[] => {
  const problems: string[] = []
  if (draft.name.trim().length === 0) {
    problems.push('Укажите название шаблона.')
  }
  if (draft.stages.length === 0) {
    return [...problems, 'Добавьте хотя бы один этап.']
  }
  draft.stages.forEach((stage, index) => {
    if (stage.name.trim().length === 0) {
      problems.push(`Укажите название этапа ${index + 1}.`)
    }
  })
  const names = new Set<string>()
  for (const stage of draft.stages) {
    const key = stage.name.trim().toLowerCase()
    if (key.length > 0 && names.has(key)) {
      problems.push(`Названия этапов не должны повторяться: «${stage.name.trim()}» уже есть. Отчёты и «Контроль» различают этапы по названию.`)
    }
    names.add(key)
  }
  const edgeKeys = new Set<string>()
  const edges = new Map<number, number[]>()
  const incoming = new Map<number, number[]>()
  for (const transition of draft.transitions) {
    const route = `${stageTitle(draft, transition.fromOrder)} → ${stageTitle(draft, transition.toOrder)}`
    if (
      transition.fromOrder < 0
      || transition.toOrder < 0
      || transition.fromOrder >= draft.stages.length
      || transition.toOrder >= draft.stages.length
      || transition.fromOrder === transition.toOrder
    ) {
      problems.push(`Переход ${route} должен вести в другой существующий этап.`)
      continue
    }
    if (Math.abs(transition.fromOrder - transition.toOrder) !== 1 && !transition.commentRequired) {
      problems.push(`Переход ${route} пропускает этапы или ведёт назад: отметьте «Нужен комментарий» или удалите переход.`)
    }
    if (!edgeKeys.add(`${transition.fromOrder}:${transition.toOrder}`)) {
      problems.push(`Переход ${route} указан дважды.`)
    }
    edges.set(transition.fromOrder, [...(edges.get(transition.fromOrder) ?? []), transition.toOrder])
    incoming.set(transition.toOrder, [...(incoming.get(transition.toOrder) ?? []), transition.fromOrder])
  }
  draft.stages.forEach((_, order) => {
    if (order < draft.stages.length - 1 && !edges.has(order)) {
      problems.push(`У этапа ${stageTitle(draft, order)} нет выхода: добавьте переход из него в следующий этап.`)
    }
  })
  const walk = (start: number, next: Map<number, number[]>) => {
    const seen = new Set<number>()
    const pending = [start]
    while (pending.length > 0) {
      const order = pending.pop()
      if (order !== undefined && !seen.has(order)) {
        seen.add(order)
        pending.push(...(next.get(order) ?? []))
      }
    }
    return seen
  }
  const reachable = walk(0, edges)
  draft.stages.forEach((_, order) => {
    if (!reachable.has(order)) {
      problems.push(`Этап ${stageTitle(draft, order)} недостижим из первого этапа ${stageTitle(draft, 0)}: добавьте переход в него из предыдущего этапа.`)
    }
  })
  // Цикл без выхода («Б» ↔ «В») проходит проверки выше, но КАМ из него уже не дойдёт до конца маршрута. Этапы без
  // выхода и недостижимые уже названы выше, поэтому здесь только достижимые этапы с выходом.
  const last = draft.stages.length - 1
  const leadsToFinal = walk(last, incoming)
  const stuck = draft.stages.map((_, order) => order)
    .filter((order) => !leadsToFinal.has(order) && reachable.has(order) && edges.has(order))
  if (stuck.length > 0) {
    problems.push(`С ${stuck.length === 1 ? 'этапа' : 'этапов'} ${stuck.map((order) => stageTitle(draft, order)).join(', ')} нельзя дойти до финального этапа ${stageTitle(draft, last)}: добавьте переход вперёд.`)
  }
  return problems
}

export const draftProblem = (draft: TemplateDraft): string | null => draftProblems(draft)[0] ?? null

// «Выше» и «Ниже» пересобирают переходы так же, как правка этапов карточки (WorkflowGraph.rebuild): этап вынимается с
// перемычкой между соседями и вставляется на новое место, рёбра «над» ним перенаправляются в него. Раньше номера в
// переходах просто переставлялись, и маршрут после перемещения приходилось чинить руками.
export const movedStage = (draft: TemplateDraft, from: number, to: number): TemplateDraft => {
  if (from === to || to < 0 || to >= draft.stages.length || from < 0 || from >= draft.stages.length) {
    return draft
  }
  const edges = new Map<string, boolean>()
  const merge = (fromId: number, toId: number, comment: boolean) => {
    const key = `${fromId}:${toId}`
    const current = edges.get(key)
    // Логическое «И», как на сервере: ребро свободно от комментария, если свободен хотя бы один из слитых путей.
    edges.set(key, current === undefined ? comment : current && comment)
  }
  const into: { id: number; comment: boolean }[] = []
  const out: { id: number; comment: boolean }[] = []
  for (const transition of draft.transitions) {
    if (transition.toOrder === from) {
      into.push({ id: transition.fromOrder, comment: transition.commentRequired })
    } else if (transition.fromOrder === from) {
      out.push({ id: transition.toOrder, comment: transition.commentRequired })
    } else {
      merge(transition.fromOrder, transition.toOrder, transition.commentRequired)
    }
  }
  for (const left of into) {
    for (const right of out) {
      if (left.id !== right.id) {
        merge(left.id, right.id, left.comment || right.comment)
      }
    }
  }
  // Идентификатор этапа — его прежний номер; ordered — новый порядок.
  const ordered = draft.stages.map((_, index) => index).filter((index) => index !== from)
  ordered.splice(to, 0, from)
  const position = new Map(ordered.map((id, index) => [id, index]))
  const pos = (id: number) => position.get(id) ?? -1
  const ends = (key: string) => key.split(':').map(Number)
  const crossing = [...edges.entries()].filter(([key]) => pos(ends(key)[0]) < to && pos(ends(key)[1]) > to)
  for (const [key, comment] of crossing) {
    if (draft.stages[from].optional) {
      edges.set(key, true)
    } else {
      edges.delete(key)
      merge(ends(key)[0], from, comment)
    }
  }
  if (to === 0) {
    merge(from, ordered[1], false)
  } else {
    const anchor = ordered[to - 1]
    const next = to + 1 < ordered.length ? ordered[to + 1] : null
    const left = next !== null ? anchor : to > 1 ? ordered[to - 2] : null
    const right = next ?? anchor
    const forward = left === null ? undefined : edges.get(`${left}:${right}`)
    const backward = left === null ? undefined : edges.get(`${right}:${left}`)
    merge(anchor, from, forward === true)
    if (next !== null) {
      merge(from, next, forward === true)
    }
    if (backward !== undefined) {
      merge(from, anchor, backward)
      if (next !== null) {
        merge(next, from, backward)
      }
    }
  }
  const transitions: TransitionDraft[] = []
  edges.forEach((commentRequired, key) => {
    const [fromOrder, toOrder] = ends(key).map(pos)
    // Как итоговый фильтр сервера: рёбра, ставшие пропуском без комментария, отбрасываются.
    if (fromOrder >= 0 && toOrder >= 0 && fromOrder !== toOrder && (commentRequired || Math.abs(fromOrder - toOrder) === 1)) {
      transitions.push({ fromOrder, toOrder, commentRequired })
    }
  })
  return { ...draft, stages: ordered.map((id) => draft.stages[id]), transitions }
}

export const withoutStage = (draft: TemplateDraft, index: number) => {
  const reorder = (order: number) => (order > index ? order - 1 : order)
  const kept = draft.transitions.filter((transition) => transition.fromOrder !== index && transition.toOrder !== index)
  const keys = new Set(kept.map((transition) => `${transition.fromOrder}:${transition.toOrder}`))
  const bridges: TransitionDraft[] = []
  for (const incoming of draft.transitions.filter((transition) => transition.toOrder === index)) {
    for (const outgoing of draft.transitions.filter((transition) => transition.fromOrder === index)) {
      const key = `${incoming.fromOrder}:${outgoing.toOrder}`
      if (incoming.fromOrder !== outgoing.toOrder && !keys.has(key)) {
        keys.add(key)
        const adjacent = Math.abs(reorder(incoming.fromOrder) - reorder(outgoing.toOrder)) === 1
        bridges.push({
          fromOrder: incoming.fromOrder,
          toOrder: outgoing.toOrder,
          commentRequired: !adjacent || (incoming.commentRequired && outgoing.commentRequired)
        })
      }
    }
  }
  const names = bridges.map((bridge) => `${stageTitle(draft, bridge.fromOrder)} → ${stageTitle(draft, bridge.toOrder)}`)
  return {
    draft: {
      ...draft,
      stages: draft.stages.filter((_, stageIndex) => stageIndex !== index),
      transitions: [...kept, ...bridges].map((transition) => ({
        ...transition,
        fromOrder: reorder(transition.fromOrder),
        toOrder: reorder(transition.toOrder)
      }))
    },
    notice: names.length === 0
      ? `Этап ${stageTitle(draft, index)} удалён.`
      : `Этап ${stageTitle(draft, index)} удалён; соседние этапы соединены: ${names.join(', ')}.`
  }
}

const asTemplateDraft = (value: unknown): TemplateDraft | null => {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const record = value as Record<string, unknown>
  const stages = Array.isArray(record.stages) ? record.stages : null
  const transitions = Array.isArray(record.transitions) ? record.transitions : null
  if (typeof record.name !== 'string' || stages === null || transitions === null) {
    return null
  }
  return {
    name: record.name,
    comment: typeof record.comment === 'string' ? record.comment : '',
    stages: stages.filter((stage): stage is StageDraft => typeof stage === 'object' && stage !== null
      && typeof (stage as StageDraft).name === 'string')
      .map((stage) => ({ ...emptyStage(), ...stage, documentKinds: Array.isArray(stage.documentKinds) ? stage.documentKinds : [] })),
    transitions: transitions.filter((transition): transition is TransitionDraft => typeof transition === 'object' && transition !== null
      && Number.isInteger((transition as TransitionDraft).fromOrder) && Number.isInteger((transition as TransitionDraft).toOrder))
      .map((transition) => ({ fromOrder: transition.fromOrder, toOrder: transition.toOrder, commentRequired: transition.commentRequired === true }))
  }
}

export type StoredEditorDraft = {
  template: { id: string; version: number } | null
  source: { stages: { name: string }[] } | null
  draft: TemplateDraft
  initialDraft: TemplateDraft
}

// Черновик редактора из sessionStorage мог записать прежний интерфейс: всё приводится к ожидаемому виду, иначе — null.
export const storedEditorDraft = (value: unknown): StoredEditorDraft | null => {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const record = value as Record<string, unknown>
  const draft = asTemplateDraft(record.draft)
  const initialDraft = asTemplateDraft(record.initialDraft)
  const template = record.template as { id?: unknown; version?: unknown } | null | undefined
  const templateValid = template === null || (typeof template === 'object' && template !== undefined
    && typeof template.id === 'string' && typeof template.version === 'number')
  const source = record.source as { stages?: unknown } | null | undefined
  const sourceValid = source === null || (typeof source === 'object' && source !== undefined && Array.isArray(source.stages))
  if (draft === null || initialDraft === null || !templateValid || !sourceValid) {
    return null
  }
  return {
    template: template as StoredEditorDraft['template'],
    source: source as StoredEditorDraft['source'],
    draft,
    initialDraft
  }
}
