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
  validUntil?: string | null
  statusChangedAt: string
}

export type WorkAgreementsLike<A extends WorkAgreementLike = WorkAgreementLike> = {
  signingRuleEnabled: boolean
  stages: readonly { stageId: string; role: AgreementStageRole; name?: string }[]
  agreements: readonly A[]
  /** Действующий договор школы с вузом, в том числе не связанный с работой (решение владельца 19.10). */
  activeAgreementNumber?: string | null
}

// Адрес карточки строится из вуза самого взаимодействия, а не из вуза открытой панели: при переходе из колокольчика
// во взаимодействие другого вуза панель прежнего вуза ещё смонтирована, и её вуз попал бы в адрес и хлебные крошки.
export const cardHash = (organizationId: string, interactionId: string, currentHash: string) => {
  const query = currentHash.split('?')[1]
  return `#/organizations/${organizationId}/${interactionId}${query ? `?${query}` : ''}`
}

export type TransitionDirection = 'forward' | 'back'

// Переход вперёд и возврат — разные действия: вперёд — дальше по маршруту (ближайший этап первым), назад — на
// прежний этап (тоже ближайший первым). Один список «Перейти на этап» с возвратом внутри путал КАМ.
export const transitionsToward = <T extends { stageId: string }>(
  options: readonly T[],
  stages: readonly { id: string; order: number }[],
  currentStageId: string,
  direction: TransitionDirection
): T[] => {
  const orderOf = new Map(stages.map((stage) => [stage.id, stage.order]))
  const current = orderOf.get(currentStageId) ?? -1
  const orderedOf = (option: T) => orderOf.get(option.stageId) ?? -1
  return options
    .filter((option) => direction === 'forward' ? orderedOf(option) > current : orderedOf(option) < current)
    .sort((left, right) => direction === 'forward' ? orderedOf(left) - orderedOf(right) : orderedOf(right) - orderedOf(left))
}

// Почему переход вперёд требует комментария. Сервер ставит commentRequired и за пропуск этапа, и за нехватку
// документов, а окно раньше любое называло пропуском — даже ближайший этап (Н-Р3).
export type ForwardCommentReason = 'skip' | 'documents' | 'route'

export const forwardCommentReason = (
  stages: readonly { id: string; order: number }[],
  currentStageId: string,
  targetStageId: string,
  commentRequired: boolean,
  documentsMissing: boolean
): ForwardCommentReason | null => {
  if (!commentRequired) {
    return null
  }
  const orderOf = new Map(stages.map((stage) => [stage.id, stage.order]))
  const current = orderOf.get(currentStageId) ?? -1
  const target = orderOf.get(targetStageId) ?? -1
  if (stages.some((stage) => stage.order > current && stage.order < target)) {
    return 'skip'
  }
  return documentsMissing ? 'documents' : 'route'
}

export const forwardCommentOptionNote: Record<ForwardCommentReason, string> = {
  skip: ' (через этап, нужен комментарий)',
  documents: ' (нужен комментарий: не хватает документов)',
  route: ' (нужен комментарий)'
}

export const forwardCommentSelectedNote: Record<ForwardCommentReason, string> = {
  skip: 'комментарий обязателен (пропуск этапа)',
  documents: 'комментарий обязателен (не хватает документов этапа)',
  route: 'комментарий обязателен по маршруту'
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
    return { kind: 'transition', label: 'Перейти на следующий этап', disabled: true, hint: 'Дальше по маршруту переходов нет' }
  }
  return { kind: 'transition', label: 'Перейти на следующий этап', disabled: false }
}

// На этапах документов главная кнопка — «Создать договор», но переход по маршруту остаётся рядом на виду: КАМ только что
// им пользовался, и прятать его в «⋯» значит терять знакомое действие.
export const transitionBesidePrimary = (primary: PrimaryAction | null, forwardCount: number) => (
  primary?.kind === 'createAgreement' && forwardCount > 0
)

// Засчитывается только «Действует» с датой подписания и неистёкшим сроком — как на сервере (AgreementStages).
// Модуль без зависимостей времени выполнения (его проверяют node-тесты), поэтому «сегодня по МСК» и признак
// «срок истёк» записаны здесь, а не взяты из shared/format и agreementModel.
const moscowToday = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow' }).format(new Date())

const expiredOn = (agreement: WorkAgreementLike, today: string) => (
  agreement.status === 'ACTIVE' && Boolean(agreement.validUntil) && (agreement.validUntil ?? '') < today
)

const signedOn = (agreement: WorkAgreementLike, today: string) => (
  agreement.status === 'ACTIVE' && Boolean(agreement.concludedOn) && !expiredOn(agreement, today)
)

const hasActiveAgreement = (work: Pick<WorkAgreementsLike, 'agreements' | 'activeAgreementNumber'>, today: string) => (
  Boolean(work.activeAgreementNumber) || work.agreements.some((agreement) => signedOn(agreement, today))
)

export const isSignedAgreement = (agreement: WorkAgreementLike) => signedOn(agreement, moscowToday())

// Подсказка правила договора во вкладке договора: при действующем договоре текст меняется, а вне отмеченного этапа
// подсказка исчезает.
export const signingRuleHint = (
  role: AgreementStageRole | null,
  work: Pick<WorkAgreementsLike, 'agreements' | 'activeAgreementNumber'>,
  today: string = moscowToday()
): string | null => {
  if (hasActiveAgreement(work, today)) {
    return role === 'SIGNING'
      ? `Договор с вузом действует${work.activeAgreementNumber ? ` (№ ${work.activeAgreementNumber})` : ''}: дальше этапа можно пройти.`
      : null
  }
  return role === 'SIGNING'
    ? 'Дальше этапа можно пройти, когда у вуза будет действующий договор.'
    : 'Этап с отметкой «требует действующего договора» проходится только при действующем договоре с вузом.'
}

const byStatusChangeDesc = <A extends WorkAgreementLike>(left: A, right: A) => (
  new Date(right.statusChangedAt).getTime() - new Date(left.statusChangedAt).getTime()
)

// Повторяет серверную проверку: переход вперёд, который уходит с этапа «требует действующего договора» или
// перешагивает его, требует любого действующего договора школы с вузом.
export const signingBlock = (
  work: WorkAgreementsLike | null,
  stages: readonly { id: string; order: number }[],
  fromStageId: string,
  toStageId: string,
  today: string = moscowToday()
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
  const gate = work.stages.find((stage) => {
    const order = orderOf.get(stage.stageId)
    return stage.role === 'SIGNING' && order !== undefined && order >= from && order < to
  })
  if (gate === undefined || hasActiveAgreement(work, today)) {
    return null
  }
  const rule = `Дальше этапа «${gate.name ?? 'с отметкой «требует действующего договора»'}» можно пройти только при действующем договоре с вузом.`
  const expired = [...work.agreements].sort(byStatusChangeDesc).find((agreement) => expiredOn(agreement, today))
  if (expired?.validUntil) {
    return `${rule} Срок договора № ${expired.number} истёк `
      + `${expired.validUntil.split('-').reverse().join('.')}: продлите срок в реквизитах договора или оформите новый договор.`
  }
  if (work.agreements.length === 0) {
    return `${rule} Создайте договор с вузом.`
  }
  const latest = [...work.agreements].sort(byStatusChangeDesc)[0]
  return `${rule} Переведите договор № ${latest.number} в статус «Действует» с датой подписания.`
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

// З-1: сервер не удаляет текущий этап и этап, на который ссылаются события истории или файлы. Такие этапы в списке
// «Удалить этап» помечаются заранее, а не отказом после подтверждения. Пока история не загружена, известен только текущий.
export const undeletableStageReason = (
  stageId: string,
  currentStageId: string | null | undefined,
  events: readonly { stageId: string; fromStageId?: string | null; toStageId?: string | null }[] | undefined,
  attachments: readonly { stageId: string }[]
): string | null => {
  if (stageId === currentStageId) {
    return 'текущий этап'
  }
  const inHistory = (events ?? []).some((event) => event.stageId === stageId || event.fromStageId === stageId || event.toStageId === stageId)
  return inHistory || attachments.some((attachment) => attachment.stageId === stageId) ? 'есть события или файлы' : null
}
