import { clearUnsavedDrafts } from './unsavedDrafts.ts'

export type InteractionStageEditType = 'RENAME' | 'ADD_AFTER' | 'MOVE_AFTER' | 'DELETE'

export type InteractionDraft = {
  createTitle: string
  createNextAction: string
  createNextActionAt: string
  createProgramId: string
  createTemplateId: string
  selectedProductIds: string[]
  createLastContactAt: string
  selectedContactIds: string[]
  contactName: string
  contactPosition: string
  contactEmail: string
  contactPhone: string
  contactRole: string
  activeInteractionId: string | null
}

export type InteractionPlanField = {
  value: string
  base: string
}

export type InteractionPlanDraft = {
  nextAction?: InteractionPlanField
  nextActionAt?: InteractionPlanField
  programId?: InteractionPlanField
  addedProductIds: string[]
  removedProductIds: string[]
}

export type InteractionCardDraft = {
  commentStageId: string
  commentDraft: string
  commentNextAction: string
  commentNextActionAt: string
  transitionStageId: string
  transitionDraft: string
  transitionNextAction: string
  transitionNextActionAt: string
  stageEditType: InteractionStageEditType
  stageEditId: string
  stageEditAfterId: string
  stageEditName: string
  stageEditOptional: boolean
  plan: InteractionPlanDraft | null
}

// Кэш действий пользователя (ФТ 13): черновики и выбор фильтров лежат в sessionStorage, то есть живут только в
// пределах вкладки и пропадают при её закрытии. Ключи включают профиль и карточку, чтобы чужие черновики не
// подставлялись другому сотруднику и другой карточке. Токены, ответы сервера и файлы здесь не хранятся.
const storagePrefix = 'rtk-crm:interaction-draft:'
const activeProfileKey = 'rtk-crm:active-profile'
const returnRouteKey = 'rtk-crm:return-route'

const emptyDraft = (): InteractionDraft => ({
  createTitle: '',
  createNextAction: '',
  createNextActionAt: '',
  createProgramId: '',
  createTemplateId: '',
  selectedProductIds: [],
  createLastContactAt: '',
  selectedContactIds: [],
  contactName: '',
  contactPosition: '',
  contactEmail: '',
  contactPhone: '',
  contactRole: '',
  activeInteractionId: null
})

export const emptyCardDraft = (): InteractionCardDraft => ({
  commentStageId: '',
  commentDraft: '',
  commentNextAction: '',
  commentNextActionAt: '',
  transitionStageId: '',
  transitionDraft: '',
  transitionNextAction: '',
  transitionNextActionAt: '',
  stageEditType: 'RENAME',
  stageEditId: '',
  stageEditAfterId: '',
  stageEditName: '',
  stageEditOptional: false,
  plan: null
})

const keyFor = (profileId: string, organizationId: string) => `${storagePrefix}${profileId}:${organizationId}`

const cardKeyFor = (profileId: string, interactionId: string) => `${storagePrefix}${profileId}:card:${interactionId}`
const reportSelectionKey = (profileId: string) => `rtk-crm:report-selection:${profileId}`

const stringValue = (value: unknown) => typeof value === 'string' ? value : ''

const stringArrayValue = (value: unknown) => Array.isArray(value)
  ? value.filter((item): item is string => typeof item === 'string')
  : []

const stageEditTypeValue = (value: unknown): InteractionStageEditType => (
  value === 'ADD_AFTER' || value === 'MOVE_AFTER' || value === 'DELETE' ? value : 'RENAME'
)

// Прочитанное из хранилища не доверяется: черновик мог быть записан прежней версией интерфейса или повреждён,
// поэтому каждое поле приводится к ожидаемому типу, а неизвестные значения заменяются пустыми.
const normalize = (value: unknown): InteractionDraft | null => {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const record = value as Record<string, unknown>
  return {
    createTitle: stringValue(record.createTitle),
    createNextAction: stringValue(record.createNextAction),
    createNextActionAt: stringValue(record.createNextActionAt),
    createProgramId: stringValue(record.createProgramId),
    createTemplateId: stringValue(record.createTemplateId),
    selectedProductIds: stringArrayValue(record.selectedProductIds),
    createLastContactAt: stringValue(record.createLastContactAt),
    selectedContactIds: stringArrayValue(record.selectedContactIds),
    contactName: stringValue(record.contactName),
    contactPosition: stringValue(record.contactPosition),
    contactEmail: stringValue(record.contactEmail),
    contactPhone: stringValue(record.contactPhone),
    contactRole: stringValue(record.contactRole),
    activeInteractionId: typeof record.activeInteractionId === 'string' ? record.activeInteractionId : null
  }
}

const planFieldValue = (value: unknown): InteractionPlanField | undefined => {
  if (typeof value !== 'object' || value === null) {
    return undefined
  }
  const record = value as Record<string, unknown>
  return typeof record.value === 'string' && typeof record.base === 'string'
    ? { value: record.value, base: record.base }
    : undefined
}

// Черновик плана хранит вместе со значением исходное (base): по нему видно, что поле действительно
// изменено пользователем, а не просто открыто. Пустой план в хранилище не сохраняется.
export const isEmptyPlanDraft = (plan: InteractionPlanDraft) => plan.nextAction === undefined
  && plan.nextActionAt === undefined
  && plan.programId === undefined
  && plan.addedProductIds.length === 0
  && plan.removedProductIds.length === 0

const normalizePlan = (value: unknown): InteractionPlanDraft | null => {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const record = value as Record<string, unknown>
  const plan = {
    nextAction: planFieldValue(record.nextAction),
    nextActionAt: planFieldValue(record.nextActionAt),
    programId: planFieldValue(record.programId),
    addedProductIds: stringArrayValue(record.addedProductIds),
    removedProductIds: stringArrayValue(record.removedProductIds)
  }
  return isEmptyPlanDraft(plan) ? null : plan
}

const normalizeCard = (value: unknown): InteractionCardDraft | null => {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const record = value as Record<string, unknown>
  return {
    commentStageId: stringValue(record.commentStageId),
    commentDraft: stringValue(record.commentDraft),
    commentNextAction: stringValue(record.commentNextAction),
    commentNextActionAt: stringValue(record.commentNextActionAt),
    transitionStageId: stringValue(record.transitionStageId),
    transitionDraft: stringValue(record.transitionDraft),
    transitionNextAction: stringValue(record.transitionNextAction),
    transitionNextActionAt: stringValue(record.transitionNextActionAt),
    stageEditType: stageEditTypeValue(record.stageEditType),
    stageEditId: stringValue(record.stageEditId),
    stageEditAfterId: stringValue(record.stageEditAfterId),
    stageEditName: stringValue(record.stageEditName),
    stageEditOptional: record.stageEditOptional === true,
    plan: normalizePlan(record.plan)
  }
}

// Доступ к sessionStorage может бросать исключение (закрытый режим, запрет хранения): тогда кэш просто
// отключается, а интерфейс работает без него. По той же причине остальные функции не пробрасывают ошибки.
const storage = () => {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

export const loadInteractionDraft = (profileId: string, organizationId: string): InteractionDraft => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return emptyDraft()
  }
  try {
    const raw = sessionStorage.getItem(keyFor(profileId, organizationId))
    return raw === null ? emptyDraft() : normalize(JSON.parse(raw)) ?? emptyDraft()
  } catch {
    return emptyDraft()
  }
}

export const saveInteractionDraft = (profileId: string, organizationId: string, draft: InteractionDraft) => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return
  }
  try {
    sessionStorage.setItem(keyFor(profileId, organizationId), JSON.stringify(draft))
  } catch {
    return
  }
}

// После создания взаимодействия черновик формы удаляется сразу: эффект сохранения пустого состояния может не
// успеть, если экран уже сменился, и повторное открытие формы подставило бы данные созданной записи.
export const clearInteractionDraft = (profileId: string, organizationId: string) => {
  try {
    storage()?.removeItem(keyFor(profileId, organizationId))
  } catch {
    return
  }
}

export const loadInteractionCardDraft = (profileId: string, interactionId: string | null): InteractionCardDraft => {
  const sessionStorage = storage()
  if (sessionStorage === null || interactionId === null) {
    return emptyCardDraft()
  }
  try {
    const raw = sessionStorage.getItem(cardKeyFor(profileId, interactionId))
    return raw === null ? emptyCardDraft() : normalizeCard(JSON.parse(raw)) ?? emptyCardDraft()
  } catch {
    return emptyCardDraft()
  }
}

export const saveInteractionCardDraft = (profileId: string, interactionId: string, draft: InteractionCardDraft) => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return
  }
  const serialized = JSON.stringify(draft)
  try {
    // Пустой черновик карточки не хранится: ключ удаляется, чтобы хранилище не копило пустые записи.
    if (serialized === JSON.stringify(emptyCardDraft())) {
      sessionStorage.removeItem(cardKeyFor(profileId, interactionId))
    } else {
      sessionStorage.setItem(cardKeyFor(profileId, interactionId), serialized)
    }
  } catch {
    return
  }
}

export const loadReportSelection = (profileId: string): unknown => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return null
  }
  try {
    const raw = sessionStorage.getItem(reportSelectionKey(profileId))
    return raw === null ? null : JSON.parse(raw)
  } catch {
    return null
  }
}

export const saveReportSelection = (profileId: string, selection: object) => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return
  }
  try {
    sessionStorage.setItem(reportSelectionKey(profileId), JSON.stringify(selection))
  } catch {
    return
  }
}

const formKeyFor = (profileId: string, key: string) => `${storagePrefix}${profileId}:form:${key}`

export const loadFormDraft = (profileId: string, key: string): unknown => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return null
  }
  try {
    const raw = sessionStorage.getItem(formKeyFor(profileId, key))
    return raw === null ? null : JSON.parse(raw)
  } catch {
    return null
  }
}

export const saveFormDraft = (profileId: string, key: string, draft: object | null) => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return
  }
  try {
    if (draft === null) {
      sessionStorage.removeItem(formKeyFor(profileId, key))
    } else {
      sessionStorage.setItem(formKeyFor(profileId, key), JSON.stringify(draft))
    }
  } catch {
    return
  }
}

// Адрес запоминается перед уходом на вход в Keycloak и восстанавливается после него. Принимаются только
// внутренние адреса вида «#/…»: произвольный адрес из хранилища не станет переходом на чужой сайт.
export const rememberReturnRoute = () => {
  const sessionStorage = storage()
  if (sessionStorage === null || !window.location.hash.startsWith('#/')) {
    return
  }
  try {
    sessionStorage.setItem(returnRouteKey, window.location.hash)
  } catch {
    return
  }
}

export const takeReturnRoute = (): string | null => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return null
  }
  try {
    const route = sessionStorage.getItem(returnRouteKey)
    sessionStorage.removeItem(returnRouteKey)
    return route !== null && route.startsWith('#/') ? route : null
  } catch {
    return null
  }
}

// При выходе и смене профиля стираются черновики этого профиля и несохранённые отметки: следующий
// пользователь того же браузера не увидит чужих текстов.
export const clearDraftsForProfile = (profileId: string) => {
  clearUnsavedDrafts()
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return
  }
  const profilePrefix = `${storagePrefix}${profileId}:`
  try {
    const keys: string[] = []
    for (let index = 0; index < sessionStorage.length; index += 1) {
      const key = sessionStorage.key(index)
      if (key?.startsWith(profilePrefix) || key === reportSelectionKey(profileId)) {
        keys.push(key)
      }
    }
    keys.forEach((key) => sessionStorage.removeItem(key))
  } catch {
    return
  }
}

export const rememberActiveProfile = (profileId: string) => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return
  }
  try {
    const previousProfileId = sessionStorage.getItem(activeProfileKey)
    // Вкладка осталась открытой, а вошёл другой сотрудник: черновики прежнего профиля удаляются сразу.
    if (previousProfileId !== null && previousProfileId.length > 0 && previousProfileId !== profileId) {
      clearDraftsForProfile(previousProfileId)
      sessionStorage.removeItem(returnRouteKey)
    }
    sessionStorage.setItem(activeProfileKey, profileId)
  } catch {
    return
  }
}

export const clearRememberedProfileDrafts = () => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return
  }
  try {
    const profileId = sessionStorage.getItem(activeProfileKey)
    sessionStorage.removeItem(activeProfileKey)
    if (profileId !== null && profileId.length > 0) {
      clearDraftsForProfile(profileId)
    }
  } catch {
    return
  }
}

export const forgetActiveProfile = () => {
  const sessionStorage = storage()
  if (sessionStorage === null) {
    return
  }
  try {
    sessionStorage.removeItem(activeProfileKey)
  } catch {
    return
  }
}
