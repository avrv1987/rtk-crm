import type { components } from '../../shared/api/openapi'

type Schemas = components['schemas']
type AgreementStageRole = Schemas['AgreementStageRole']
type AgreementStatus = Schemas['AgreementStatus']
type WorkStatus = Schemas['InteractionMarks']['status']
type EventType = Schemas['InteractionEvent']['type']

export type WorkAgreementLike = {
  number: string
  status: AgreementStatus
  concludedOn?: string | null
  statusChangedAt: string
}

export type WorkAgreementsLike<A extends WorkAgreementLike = WorkAgreementLike> = {
  signingRuleEnabled: boolean
  stages: readonly { stageId: string; role: AgreementStageRole }[]
  agreements: readonly A[]
}

export const primaryShortLabels: Record<PrimaryAction['kind'], string> = {
  transition: 'Следующий этап',
  resume: 'Возобновить',
  createAgreement: 'Создать договор',
  openAgreement: 'К договору',
  setStep: 'Задать шаг'
}

export type PrimaryAction =
  | { kind: 'transition'; label: string; disabled: boolean; hint?: string }
  | { kind: 'resume'; label: string }
  | { kind: 'createAgreement'; label: string }
  | { kind: 'openAgreement'; label: string; hint: string }
  | { kind: 'setStep'; label: string }

export type PrimaryContext = {
  canEdit: boolean
  status: WorkStatus
  hasNextStep: boolean
  transitionCount: number
  agreementStageRole: AgreementStageRole | null
  agreementCount: number
  signingBlocked: string | null
}

// Порядок важен: сначала то, без чего следующий шаг невозможен (статус, договор на этапах документов,
// правило «Подписание»), потом дисциплина следующего шага, и только затем обычный переход по маршруту.
export const primaryAction = (context: PrimaryContext): PrimaryAction | null => {
  if (!context.canEdit) {
    return null
  }
  if (context.status !== 'ACTIVE') {
    return { kind: 'resume', label: 'Возобновить взаимодействие' }
  }
  if (context.agreementStageRole !== null && context.agreementCount === 0) {
    return { kind: 'createAgreement', label: 'Создать договор' }
  }
  if (context.signingBlocked !== null) {
    return { kind: 'openAgreement', label: 'Перейти к договору', hint: context.signingBlocked }
  }
  if (!context.hasNextStep) {
    return { kind: 'setStep', label: 'Задать следующий шаг' }
  }
  if (context.transitionCount === 0) {
    return { kind: 'transition', label: 'Перейти на следующий этап', disabled: true, hint: 'Из текущего этапа переходы не разрешены' }
  }
  return { kind: 'transition', label: 'Перейти на следующий этап', disabled: false }
}

export const isSignedAgreement = (agreement: WorkAgreementLike) => (
  (agreement.status === 'ACTIVE' || agreement.status === 'COMPLETED') && Boolean(agreement.concludedOn)
)

const byStatusChangeDesc = <A extends WorkAgreementLike>(left: A, right: A) => (
  new Date(right.statusChangedAt).getTime() - new Date(left.statusChangedAt).getTime()
)

// Повторяет серверную проверку правила «Подписание»: переход вперёд, который уходит с этапа подписания
// или перешагивает его, требует связанного договора «Действует» или «Завершён» с датой подписания.
export const signingBlock = (
  work: WorkAgreementsLike | null,
  stages: readonly { id: string; order: number }[],
  fromStageId: string,
  toStageId: string
): string | null => {
  if (work === null || !work.signingRuleEnabled) {
    return null
  }
  const orderOf = new Map(stages.map((stage) => [stage.id, stage.order]))
  const from = orderOf.get(fromStageId)
  const to = orderOf.get(toStageId)
  if (from === undefined || to === undefined || to <= from) {
    return null
  }
  const passesSigning = work.stages.some((stage) => {
    const order = orderOf.get(stage.stageId)
    return stage.role === 'SIGNING' && order !== undefined && order >= from && order < to
  })
  if (!passesSigning || work.agreements.some(isSignedAgreement)) {
    return null
  }
  if (work.agreements.length === 0) {
    return 'Этап «Подписание» завершится только с подписанным договором. Создайте договор с вузом.'
  }
  const latest = [...work.agreements].sort(byStatusChangeDesc)[0]
  return `Этап «Подписание» завершится только с подписанным договором. Переведите договор № ${latest.number} в статус «Действует» с датой подписания.`
}

export type AgreementFact<A extends WorkAgreementLike> =
  | { kind: 'agreement'; agreement: A }
  | { kind: 'create' }
  | { kind: 'none' }

export const agreementFact = <A extends WorkAgreementLike>(work: WorkAgreementsLike<A> | null, currentStageId: string): AgreementFact<A> => {
  if (work === null) {
    return { kind: 'none' }
  }
  const sorted = [...work.agreements].sort(byStatusChangeDesc)
  const current = sorted.find((agreement) => agreement.status !== 'TERMINATED' && agreement.status !== 'COMPLETED') ?? sorted[0]
  if (current !== undefined) {
    return { kind: 'agreement', agreement: current }
  }
  return work.stages.some((stage) => stage.stageId === currentStageId) ? { kind: 'create' } : { kind: 'none' }
}

export type RouteWindow = { from: number; to: number; hiddenBefore: number; hiddenAfter: number }

export const routeWindow = (count: number, currentIndex: number, radius = 1): RouteWindow => {
  if (count === 0) {
    return { from: 0, to: -1, hiddenBefore: 0, hiddenAfter: 0 }
  }
  const current = Math.min(Math.max(currentIndex, 0), count - 1)
  const from = Math.max(0, current - radius)
  const to = Math.min(count - 1, current + radius)
  return { from, to, hiddenBefore: from, hiddenAfter: count - 1 - to }
}

export type HistoryFilter = 'ALL' | 'COMMENTS' | 'STAGES' | 'DOCUMENTS'

export const historyFilters: { value: HistoryFilter; label: string }[] = [
  { value: 'ALL', label: 'Все' },
  { value: 'COMMENTS', label: 'Комментарии' },
  { value: 'STAGES', label: 'Этапы и шаги' },
  { value: 'DOCUMENTS', label: 'Документы' }
]

const stageEvents: readonly EventType[] = ['CREATED', 'TRANSITIONED', 'STAGES_EDITED', 'PLAN_UPDATED', 'STAGE_COMPLETED', 'STAGE_COMPLETION_CLEARED']
const documentEvents: readonly EventType[] = ['ATTACHMENT_DELETED', 'AGREEMENT_UPDATED']

export const historyMatches = (type: EventType, hasAttachments: boolean, filter: HistoryFilter) => {
  switch (filter) {
    case 'COMMENTS':
      return type === 'COMMENTED'
    case 'STAGES':
      return stageEvents.includes(type)
    case 'DOCUMENTS':
      return hasAttachments || documentEvents.includes(type)
    default:
      return true
  }
}

export const historyPageSize = 10

export const newestFirst = <E extends { occurredAt: string }>(events: readonly E[]) => (
  [...events].sort((left, right) => new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime())
)
