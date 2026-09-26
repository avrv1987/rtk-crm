import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Attachment,
  type CatalogLookup,
  type Contact,
  type ContactCreate,
  type Interaction,
  type InteractionComment,
  type InteractionCreate,
  type InteractionEvent,
  type InteractionNextStep,
  type InteractionPlanUpdate,
  type InteractionStage,
  type InteractionStageEdit,
  type InteractionTransition,
  type Me,
  type Organization,
  type PageInteraction,
  type PageWorkflowTemplate
} from '../../shared/api/client'
import {
  loadInteractionCardDraft,
  loadInteractionDraft,
  saveInteractionCardDraft,
  saveInteractionDraft,
  isEmptyPlanDraft,
  type InteractionDraft,
  type InteractionPlanDraft,
  type InteractionStageEditType
} from './drafts'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { LearningSnapshots } from './LearningSnapshots'

type InteractionsPanelProps = {
  organizationId: Organization['id']
  initialInteractionId?: Interaction['id']
  profileId: string
  role: Me['role']
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | {
    kind: 'ready'
    items: PageInteraction['items']
    total: number
    pages: number
    refreshing: boolean
    loadingMore: boolean
  }
  | { kind: 'failed'; requestId?: string }

type DetailState =
  | { kind: 'idle' }
  | { kind: 'loading'; id: Interaction['id'] }
  | { kind: 'ready'; interaction: Interaction }
  | { kind: 'failed'; id: Interaction['id']; requestId?: string }

type EventsState =
  | { kind: 'idle' }
  | { kind: 'loading'; id: Interaction['id'] }
  | { kind: 'ready'; id: Interaction['id']; events: InteractionEvent[] }
  | { kind: 'failed'; id: Interaction['id']; requestId?: string }

type ContactsState =
  | { kind: 'loading' }
  | { kind: 'ready'; contacts: Contact[] }
  | { kind: 'failed'; requestId?: string }

type CatalogState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | {
    kind: 'ready'
    programs: CatalogLookup[]
    programTotal: number
    products: CatalogLookup[]
    productTotal: number
  }
  | { kind: 'failed'; requestId?: string }

type TemplatesState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageWorkflowTemplate }
  | { kind: 'failed'; requestId?: string }

type AttachmentRefreshState =
  | { kind: 'idle' }
  | { kind: 'loading'; id: Attachment['id'] }
  | { kind: 'failed'; id: Attachment['id']; error: unknown }

type AttachmentDownloadState =
  | { kind: 'idle' }
  | { kind: 'downloading'; id: Attachment['id'] }
  | { kind: 'failed'; id: Attachment['id']; error: unknown }

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

const interactionPageSize = 25
const interactionRequestSizeLimit = 100
const interactionSort = 'updatedAt,desc' as const

const catalogQuery = {
  page: 0,
  size: 100
} as const

const templatesPageSize = 25

const dateTimeFormatter = new Intl.DateTimeFormat('ru-RU', {
  dateStyle: 'medium',
  timeStyle: 'short'
})

const eventLabel: Record<InteractionEvent['type'], string> = {
  CREATED: 'Взаимодействие создано',
  TRANSITIONED: 'Этап изменён',
  COMMENTED: 'Добавлен комментарий',
  STAGES_EDITED: 'Изменены этапы карточки',
  PLAN_UPDATED: 'Изменён план'
}

const dayMilliseconds = 24 * 60 * 60 * 1000

const formatDateTime = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTimeFormatter.format(date)
}

const requestIdOf = (error: unknown) => (
  error instanceof ApiError ? error.requestId : undefined
)

const isUnauthenticated = (error: unknown) => (
  error instanceof ApiError && error.code === 'UNAUTHENTICATED'
)

const isProfileUnavailable = (error: unknown): error is ApiError => (
  error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED'
)

const commandMessage = (error: unknown) => {
  if (error instanceof ApiError && error.status === 409) {
    return typeof error.currentVersion !== 'number'
      ? 'Команда не выполнена из-за конфликта. Черновик сохранён; обновите карточку и решите, отправлять ли его снова.'
      : 'Карточку уже изменили. Черновик сохранён; обновите карточку перед новой отправкой.'
  }
  if (error instanceof ApiError) {
    return 'Команда не выполнена. Черновик сохранён; проверьте данные и повторите попытку.'
  }
  return 'Не удалось связаться с сервисом. Черновик сохранён; повторите попытку позже.'
}

const fieldLabels: Record<string, string> = {
  title: 'Название',
  name: 'Название',
  position: 'Должность',
  email: 'Электронная почта',
  phone: 'Телефон',
  nextAction: 'Следующий шаг',
  'nextStep.nextAction': 'Новый следующий шаг',
  nextActionAt: 'Срок',
  lastContactAt: 'Дата последнего контакта',
  programId: 'Программа',
  productIds: 'Продукты',
  contactIds: 'Связанные контакты',
  templateId: 'Шаблон процесса',
  text: 'Текст комментария',
  comment: 'Комментарий к переходу',
  stageId: 'Этап',
  toStageId: 'Новый этап',
  afterId: 'После какого этапа',
  id: 'Этап',
  operations: 'Операция',
  attachmentIds: 'Документы',
  file: 'Файл'
}

const StructuredApiError = ({ error }: { error: unknown }) => {
  if (!(error instanceof ApiError)) {
    return null
  }
  const fieldErrors = Object.entries(error.fieldErrors ?? {})
  if (fieldErrors.length === 0) {
    return error.code === 'VALIDATION_ERROR' ? null : <p className="structured-api-error">{error.message}</p>
  }
  return (
    <ul className="structured-api-error">
      {fieldErrors.map(([field, message]) => <li key={field}>{fieldLabels[field] ?? field}: {message}</li>)}
    </ul>
  )
}

const fieldErrorOf = (state: CommandState, field: string) => (
  state.kind === 'failed' && state.error instanceof ApiError ? state.error.fieldErrors?.[field] : undefined
)

const invalidProps = (message: string | undefined, id: string) => (
  message === undefined ? {} : { 'aria-invalid': true, 'aria-describedby': id }
)

const FieldError = ({ id, message }: { id: string; message: string | undefined }) => (
  message === undefined ? null : <span id={id} className="field-error">{message}</span>
)

const toIsoDateTime = (value: string): string | null => {
  if (value.length === 0) {
    return null
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

const toDateTimeLocal = (value: string | null) => {
  if (value === null) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

const daysLabel = (days: number) => {
  const lastTwo = days % 100
  const last = days % 10
  if (last === 1 && lastTwo !== 11) {
    return `${days} день`
  }
  if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) {
    return `${days} дня`
  }
  return `${days} дней`
}

const nextStepPayload = (
  interaction: Pick<Interaction, 'nextAction' | 'nextActionAt'>,
  nextAction: string,
  nextActionAt: string
): InteractionNextStep | null => {
  const action = nextAction.trim()
  const dueAt = toIsoDateTime(nextActionAt)
  if (action.length === 0 && dueAt === null) {
    return null
  }
  return {
    nextAction: action.length > 0 ? action : interaction.nextAction,
    nextActionAt: dueAt ?? interaction.nextActionAt
  }
}

type PlanField = 'nextAction' | 'nextActionAt' | 'programId'

const planFieldLabels: Record<PlanField, string> = {
  nextAction: 'Следующий шаг',
  nextActionAt: 'Срок',
  programId: 'Программа'
}

const emptyPlanDraft = (): InteractionPlanDraft => ({ addedProductIds: [], removedProductIds: [] })

const planFromInteraction = (interaction: Interaction): Record<PlanField, string> => ({
  nextAction: interaction.nextAction ?? '',
  nextActionAt: toDateTimeLocal(interaction.nextActionAt),
  programId: interaction.programId ?? ''
})

const planValuesOf = (interaction: Interaction, plan: InteractionPlanDraft | null) => {
  const current = planFromInteraction(interaction)
  const removed = new Set(plan?.removedProductIds ?? [])
  return {
    nextAction: plan?.nextAction?.value ?? current.nextAction,
    nextActionAt: plan?.nextActionAt?.value ?? current.nextActionAt,
    programId: plan?.programId?.value ?? current.programId,
    productIds: [
      ...interaction.productIds.filter((id) => !removed.has(id)),
      ...(plan?.addedProductIds ?? []).filter((id) => !interaction.productIds.includes(id))
    ]
  }
}

const planChanges = (interaction: Interaction, plan: InteractionPlanDraft | null): InteractionPlanUpdate => {
  const changes: InteractionPlanUpdate = { version: interaction.version }
  const current = planFromInteraction(interaction)
  const values = planValuesOf(interaction, plan)
  if (values.nextAction.trim() !== current.nextAction) {
    changes.nextAction = values.nextAction.trim() || null
  }
  if (values.nextActionAt !== current.nextActionAt) {
    changes.nextActionAt = toIsoDateTime(values.nextActionAt)
  }
  if (values.programId !== current.programId) {
    changes.programId = values.programId || null
  }
  if (
    values.productIds.length !== interaction.productIds.length
    || values.productIds.some((id) => !interaction.productIds.includes(id))
  ) {
    changes.productIds = values.productIds
  }
  return changes
}

const planConflicts = (interaction: Interaction, plan: InteractionPlanDraft | null) => {
  const current = planFromInteraction(interaction)
  const shown: Record<PlanField, string> = {
    nextAction: interaction.nextAction ?? 'не задан',
    nextActionAt: interaction.nextActionAt === null ? 'не задан' : formatDateTime(interaction.nextActionAt),
    programId: interaction.program?.name ?? 'не указана'
  }
  return (Object.keys(planFieldLabels) as PlanField[])
    .filter((field) => {
      const draft = plan?.[field]
      return draft !== undefined && draft.base !== current[field] && draft.value !== current[field]
    })
    .map((field) => ({ field, label: planFieldLabels[field], current: shown[field] }))
}

type StagePathStatus = 'passed' | 'current' | 'skipped' | 'future'

type StagePathItem = {
  stage: InteractionStage
  status: StagePathStatus
  enteredAt?: string
}

const stagePath = (interaction: Interaction, events: InteractionEvent[] | undefined): StagePathItem[] => {
  const enteredAt = new Map<InteractionStage['id'], string>()
  events?.forEach((event) => {
    if ((event.type === 'CREATED' || event.type === 'TRANSITIONED') && event.toStageId) {
      enteredAt.set(event.toStageId, event.occurredAt)
    }
  })
  const currentOrder = interaction.stages.find((stage) => stage.id === interaction.currentStageId)?.order ?? 0
  return interaction.stages.map((stage) => {
    const entered = enteredAt.get(stage.id)
    if (stage.id === interaction.currentStageId) {
      return { stage, status: 'current', enteredAt: entered }
    }
    if (entered !== undefined) {
      return { stage, status: 'passed', enteredAt: entered }
    }
    return { stage, status: events !== undefined && stage.order < currentOrder ? 'skipped' : 'future' }
  })
}

const stagePathLabel = (item: StagePathItem) => {
  if (item.status === 'current') {
    if (item.enteredAt === undefined) {
      return 'Текущий этап'
    }
    const days = Math.max(0, Math.floor((Date.now() - new Date(item.enteredAt).getTime()) / dayMilliseconds))
    return `Текущий этап: ${daysLabel(days)} на этапе`
  }
  if (item.status === 'passed') {
    return item.enteredAt === undefined ? 'Пройден' : `Пройден; вход ${formatDateTime(item.enteredAt)}`
  }
  return item.status === 'skipped' ? 'Пропущен' : 'Впереди'
}

type NextStepFieldsProps = {
  nextAction: string
  nextActionAt: string
  onNextActionChange: (value: string) => void
  onNextActionAtChange: (value: string) => void
}

const NextStepFields = ({ nextAction, nextActionAt, onNextActionChange, onNextActionAtChange }: NextStepFieldsProps) => (
  <fieldset className="interaction-next-step-fields">
    <legend>Обновить следующий шаг</legend>
    <label>
      Новый следующий шаг
      <textarea
        value={nextAction}
        maxLength={500}
        onChange={(event) => onNextActionChange(event.target.value)}
      />
    </label>
    <label>
      Новый срок
      <input
        type="datetime-local"
        value={nextActionAt}
        onChange={(event) => onNextActionAtChange(event.target.value)}
      />
    </label>
    <span className="interaction-field-hint">Пустое поле оставляет текущее значение.</span>
  </fieldset>
)

const eventStageDescription = (event: InteractionEvent) => {
  if (event.type === 'TRANSITIONED') {
    const from = event.fromStageNameSnapshot ?? 'предыдущий этап'
    const to = event.toStageNameSnapshot ?? 'следующий этап'
    return `${from} → ${to}`
  }
  return `Этап: ${event.stageNameSnapshot}`
}

const nextActionSchedule = (interaction: Pick<Interaction, 'nextAction' | 'nextActionAt'>) => {
  if (interaction.nextAction === null || interaction.nextAction === undefined || interaction.nextAction.trim().length === 0) {
    return {
      className: 'interaction-summary__missing',
      label: 'Следующий шаг не задан'
    }
  }
  if (interaction.nextActionAt === null || interaction.nextActionAt === undefined) {
    return {
      className: 'interaction-summary__missing',
      label: 'Срок следующего шага не задан'
    }
  }
  const scheduledAt = new Date(interaction.nextActionAt)
  if (!Number.isNaN(scheduledAt.getTime()) && scheduledAt.getTime() < Date.now()) {
    return {
      className: 'interaction-summary__overdue',
      label: `Просрочен с ${formatDateTime(interaction.nextActionAt)}`
    }
  }
  return {
    className: 'interaction-summary__planned',
    label: `Запланирован на ${formatDateTime(interaction.nextActionAt)}`
  }
}

const lastContactLabel = (value: string | null) => (
  value === null ? 'Не указан' : formatDateTime(value)
)

const licenseSignedLabel = (value: boolean | null) => {
  if (value === null) {
    return 'Не указано'
  }
  return value ? 'Подписана' : 'Не подписана'
}

const attachmentStatusLabel: Record<Attachment['status'], string> = {
  QUARANTINE: 'В карантине: проверяется',
  CLEAN: 'Проверен: доступен',
  REJECTED: 'Отклонён: недоступен',
  UNVERIFIABLE: 'Не удалось проверить: недоступен'
}

const attachmentMessage = (error: unknown, action: 'upload' | 'download' | 'refresh') => {
  if (error instanceof ApiError && error.status === 409) {
    return action === 'upload'
      ? 'Карточку уже изменили. Выбранный файл остаётся в форме; обновите карточку перед повторной загрузкой.'
      : 'Карточку уже изменили. Обновите карточку и повторите действие.'
  }
  if (error instanceof ApiError && (error.status === 413 || error.code === 'PAYLOAD_TOO_LARGE')) {
    return `Файл больше ${attachmentLimitMegabytes} МБ. Уменьшите файл или разделите его на части.`
  }
  if (action === 'download') {
    return 'Файл недоступен для скачивания. Обновите карточку и проверьте статус документа.'
  }
  if (error instanceof ApiError) {
    if (action === 'upload') {
      const reason = error.fieldErrors?.file ?? error.fieldErrors?.stageId ?? error.message
      return `Файл не принят: ${reason.charAt(0).toLowerCase()}${reason.slice(1)}.`
    }
    return 'Не удалось обновить статус документа. Повторите попытку позже.'
  }
  return action === 'upload'
    ? 'Не удалось загрузить файл. Выбранный файл остаётся в форме; повторите попытку позже.'
    : 'Не удалось связаться с сервисом. Повторите попытку позже.'
}

const attachmentLimitMegabytes = 20
const attachmentLimitBytes = attachmentLimitMegabytes * 1024 * 1024

const withActiveInteraction = (draft: InteractionDraft, interactionId: Interaction['id'] | undefined): InteractionDraft => (
  interactionId === undefined ? draft : { ...draft, activeInteractionId: interactionId }
)

const formatFileSize = (sizeBytes: number) => {
  if (sizeBytes < 1024) {
    return `${sizeBytes} Б`
  }
  if (sizeBytes < 1024 * 1024) {
    return `${Math.round(sizeBytes / 1024)} КиБ`
  }
  return `${(sizeBytes / (1024 * 1024)).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} МиБ`
}

export const InteractionsPanel = ({
  organizationId,
  initialInteractionId,
  profileId,
  role,
  onSessionExpired,
  onProfileUnavailable
}: InteractionsPanelProps) => {
  const restoredDraft = useRef<InteractionDraft>(withActiveInteraction(loadInteractionDraft(profileId, organizationId), initialInteractionId))
  const [restoredCard] = useState(() => loadInteractionCardDraft(profileId, restoredDraft.current.activeInteractionId))
  const [listState, setListState] = useState<ListState>({ kind: 'loading' })
  const [detailState, setDetailState] = useState<DetailState>({ kind: 'idle' })
  const [eventsState, setEventsState] = useState<EventsState>({ kind: 'idle' })
  const [contactsState, setContactsState] = useState<ContactsState>({ kind: 'loading' })
  const [catalogState, setCatalogState] = useState<CatalogState>({ kind: 'idle' })
  const [templatesState, setTemplatesState] = useState<TemplatesState>({ kind: 'idle' })
  const [templatesPageIndex, setTemplatesPageIndex] = useState(0)
  const [uploadStageId, setUploadStageId] = useState('')
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [uploadState, setUploadState] = useState<CommandState>({ kind: 'idle' })
  const [attachmentRefreshState, setAttachmentRefreshState] = useState<AttachmentRefreshState>({ kind: 'idle' })
  const [attachmentDownloadState, setAttachmentDownloadState] = useState<AttachmentDownloadState>({ kind: 'idle' })
  const [selectedContactIds, setSelectedContactIds] = useState<Contact['id'][]>(restoredDraft.current.selectedContactIds)
  const [contactName, setContactName] = useState(restoredDraft.current.contactName)
  const [contactPosition, setContactPosition] = useState(restoredDraft.current.contactPosition)
  const [contactEmail, setContactEmail] = useState(restoredDraft.current.contactEmail)
  const [contactPhone, setContactPhone] = useState(restoredDraft.current.contactPhone)
  const [contactCreateState, setContactCreateState] = useState<CommandState>({ kind: 'idle' })
  const [createTitle, setCreateTitle] = useState(restoredDraft.current.createTitle)
  const [createNextAction, setCreateNextAction] = useState(restoredDraft.current.createNextAction)
  const [createNextActionAt, setCreateNextActionAt] = useState(restoredDraft.current.createNextActionAt)
  const [createProgramId, setCreateProgramId] = useState(restoredDraft.current.createProgramId)
  const [createTemplateId, setCreateTemplateId] = useState(restoredDraft.current.createTemplateId)
  const [confirmedTemplateId, setConfirmedTemplateId] = useState<string | null>(null)
  const [selectedProductIds, setSelectedProductIds] = useState<string[]>(restoredDraft.current.selectedProductIds)
  const [createLastContactAt, setCreateLastContactAt] = useState(restoredDraft.current.createLastContactAt)
  const [createState, setCreateState] = useState<CommandState>({ kind: 'idle' })
  const [activeInteractionId, setActiveInteractionId] = useState<Interaction['id'] | null>(restoredDraft.current.activeInteractionId)
  const [commentStageId, setCommentStageId] = useState(restoredCard.commentStageId)
  const [commentDraft, setCommentDraft] = useState(restoredCard.commentDraft)
  const [commentNextAction, setCommentNextAction] = useState(restoredCard.commentNextAction)
  const [commentNextActionAt, setCommentNextActionAt] = useState(restoredCard.commentNextActionAt)
  const [commentAttachmentIds, setCommentAttachmentIds] = useState<Attachment['id'][]>([])
  const [commentState, setCommentState] = useState<CommandState>({ kind: 'idle' })
  const [transitionStageId, setTransitionStageId] = useState(restoredCard.transitionStageId)
  const [transitionDraft, setTransitionDraft] = useState(restoredCard.transitionDraft)
  const [transitionNextAction, setTransitionNextAction] = useState(restoredCard.transitionNextAction)
  const [transitionNextActionAt, setTransitionNextActionAt] = useState(restoredCard.transitionNextActionAt)
  const [transitionAttachmentIds, setTransitionAttachmentIds] = useState<Attachment['id'][]>([])
  const [transitionState, setTransitionState] = useState<CommandState>({ kind: 'idle' })
  const [stageEditType, setStageEditType] = useState<InteractionStageEditType>(restoredCard.stageEditType)
  const [stageEditId, setStageEditId] = useState(restoredCard.stageEditId)
  const [stageEditAfterId, setStageEditAfterId] = useState(restoredCard.stageEditAfterId)
  const [stageEditName, setStageEditName] = useState(restoredCard.stageEditName)
  const [stageEditOptional, setStageEditOptional] = useState(restoredCard.stageEditOptional)
  const [stageEditState, setStageEditState] = useState<CommandState>({ kind: 'idle' })
  const [planDraft, setPlanDraft] = useState<InteractionPlanDraft | null>(restoredCard.plan)
  const [planState, setPlanState] = useState<CommandState>({ kind: 'idle' })
  const listRequestVersion = useRef(0)
  const detailRequestVersion = useRef(0)
  const eventsRequestVersion = useRef(0)
  const contactsRequestVersion = useRef(0)
  const catalogRequestVersion = useRef(0)
  const templatesRequestVersion = useRef(0)
  const contactCreateKey = useRef<string | null>(null)
  const createKey = useRef<string | null>(null)
  const commentKey = useRef<string | null>(null)
  const transitionKey = useRef<string | null>(null)
  const stageEditKey = useRef<string | null>(null)
  const planKey = useRef<string | null>(null)
  const uploadKey = useRef<string | null>(null)
  const uploadInput = useRef<HTMLInputElement>(null)
  const openedInteractionId = useRef<Interaction['id'] | null>(restoredDraft.current.activeInteractionId)
  const detailHeading = useRef<HTMLHeadingElement>(null)
  const focusInitialInteraction = useRef(initialInteractionId !== undefined)

  const loadInteractions = useCallback(async (pages: number) => {
    const requestVersion = ++listRequestVersion.current
    setListState((current) => current.kind === 'ready'
      ? { ...current, refreshing: true, loadingMore: pages > current.pages }
      : { kind: 'loading' })
    const limit = pages * interactionPageSize
    const size = Math.min(limit, interactionRequestSizeLimit)
    try {
      const results = await Promise.all(Array.from({ length: Math.ceil(limit / size) }, (_, page) => apiClient.listInteractions({
        organizationId,
        page,
        size,
        sort: interactionSort
      })))
      if (requestVersion === listRequestVersion.current) {
        const items = [...new Map(results.flatMap((result) => result.items).map((item) => [item.id, item])).values()]
          .slice(0, limit)
        setListState({
          kind: 'ready',
          items,
          total: results[results.length - 1].total,
          pages,
          refreshing: false,
          loadingMore: false
        })
      }
    } catch (error) {
      if (requestVersion !== listRequestVersion.current) {
        return
      }
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setListState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired, organizationId])

  const loadContacts = useCallback(async () => {
    const requestVersion = ++contactsRequestVersion.current
    setContactsState({ kind: 'loading' })
    try {
      const contacts = await apiClient.listOrganizationContacts(organizationId)
      if (requestVersion === contactsRequestVersion.current) {
        setContactsState({ kind: 'ready', contacts })
      }
    } catch (error) {
      if (requestVersion !== contactsRequestVersion.current) {
        return
      }
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setContactsState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired, organizationId])

  const loadCatalog = useCallback(async () => {
    const requestVersion = ++catalogRequestVersion.current
    setCatalogState({ kind: 'loading' })
    try {
      const [programPage, productPage] = await Promise.all([
        apiClient.listPrograms(catalogQuery),
        apiClient.listProducts(catalogQuery)
      ])
      if (requestVersion === catalogRequestVersion.current) {
        setCatalogState({
          kind: 'ready',
          programs: programPage.items,
          programTotal: programPage.total,
          products: productPage.items,
          productTotal: productPage.total
        })
      }
    } catch (error) {
      if (requestVersion !== catalogRequestVersion.current) {
        return
      }
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setCatalogState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired])

  const loadAvailableTemplates = useCallback(async (requestedPage: number) => {
    const requestVersion = ++templatesRequestVersion.current
    setTemplatesState({ kind: 'loading' })
    try {
      const page = await apiClient.listAvailableWorkflowTemplates({
        page: requestedPage,
        size: templatesPageSize,
        sort: 'name,asc'
      })
      if (requestVersion === templatesRequestVersion.current) {
        setTemplatesState({ kind: 'ready', page })
      }
    } catch (error) {
      if (requestVersion !== templatesRequestVersion.current) {
        return
      }
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setTemplatesState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired])

  const loadEvents = useCallback(async (id: Interaction['id']) => {
    const requestVersion = ++eventsRequestVersion.current
    setEventsState((current) => current.kind === 'ready' && current.id === id ? current : { kind: 'loading', id })
    try {
      const events = await apiClient.listInteractionEvents(id)
      if (requestVersion === eventsRequestVersion.current) {
        setEventsState({ kind: 'ready', id, events })
      }
    } catch (error) {
      if (requestVersion !== eventsRequestVersion.current) {
        return
      }
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setEventsState({ kind: 'failed', id, requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired])

  const loadInteraction = useCallback(async (id: Interaction['id']) => {
    const requestVersion = ++detailRequestVersion.current
    setDetailState((current) => current.kind === 'ready' && current.interaction.id === id ? current : { kind: 'loading', id })
    try {
      const interaction = await apiClient.getInteraction(id)
      if (requestVersion === detailRequestVersion.current) {
        commentKey.current = null
        transitionKey.current = null
        planKey.current = null
        const attachableIds = new Set(
          interaction.attachments
            .filter((attachment) => attachment.status === 'CLEAN' && attachment.eventId === null)
            .map((attachment) => attachment.id)
        )
        const stageIds = new Set(interaction.stages.map((stage) => stage.id))
        const allowedStageIds = new Set(interaction.allowedTransitions.map((option) => option.stageId))
        setCommentStageId((stageId) => stageIds.has(stageId) ? stageId : interaction.currentStageId)
        setTransitionStageId((stageId) => allowedStageIds.has(stageId) ? stageId : '')
        setUploadStageId((stageId) => stageIds.has(stageId) ? stageId : interaction.currentStageId)
        setCommentAttachmentIds((ids) => ids.filter((attachmentId) => attachableIds.has(attachmentId)))
        setTransitionAttachmentIds((ids) => ids.filter((attachmentId) => attachableIds.has(attachmentId)))
        setStageEditId((stageId) => stageIds.has(stageId) ? stageId : '')
        setStageEditAfterId((stageId) => stageIds.has(stageId) ? stageId : '')
        setDetailState({ kind: 'ready', interaction })
      }
    } catch (error) {
      if (requestVersion !== detailRequestVersion.current) {
        return
      }
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setDetailState({ kind: 'failed', id, requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired])

  const openInteraction = useCallback((id: Interaction['id']) => {
    const query = window.location.hash.split('?')[1]
    window.history.replaceState(null, '', `#/organizations/${organizationId}/${id}${query ? `?${query}` : ''}`)
    if (openedInteractionId.current !== id) {
      const card = loadInteractionCardDraft(profileId, id)
      openedInteractionId.current = id
      setActiveInteractionId(id)
      setCommentStageId(card.commentStageId)
      setCommentDraft(card.commentDraft)
      setCommentNextAction(card.commentNextAction)
      setCommentNextActionAt(card.commentNextActionAt)
      setCommentAttachmentIds([])
      setCommentState({ kind: 'idle' })
      setTransitionStageId(card.transitionStageId)
      setTransitionDraft(card.transitionDraft)
      setTransitionNextAction(card.transitionNextAction)
      setTransitionNextActionAt(card.transitionNextActionAt)
      setTransitionAttachmentIds([])
      setTransitionState({ kind: 'idle' })
      setStageEditType(card.stageEditType)
      setStageEditId(card.stageEditId)
      setStageEditAfterId(card.stageEditAfterId)
      setStageEditName(card.stageEditName)
      setStageEditOptional(card.stageEditOptional)
      setStageEditState({ kind: 'idle' })
      setPlanDraft(card.plan)
      setPlanState({ kind: 'idle' })
      setUploadStageId('')
      setUploadFile(null)
      setUploadState({ kind: 'idle' })
      setAttachmentRefreshState({ kind: 'idle' })
      setAttachmentDownloadState({ kind: 'idle' })
      commentKey.current = null
      transitionKey.current = null
      stageEditKey.current = null
      planKey.current = null
      uploadKey.current = null
      if (uploadInput.current !== null) {
        uploadInput.current.value = ''
      }
    }
    void loadInteraction(id)
    void loadEvents(id)
  }, [loadEvents, loadInteraction, organizationId, profileId])

  useEffect(() => {
    void loadInteractions(1)
    void loadContacts()
    return () => {
      listRequestVersion.current += 1
      detailRequestVersion.current += 1
      eventsRequestVersion.current += 1
      contactsRequestVersion.current += 1
    }
  }, [loadContacts, loadInteractions])

  useEffect(() => {
    if (role === 'ADMIN') {
      return
    }
    void loadCatalog()
    setTemplatesPageIndex(0)
    void loadAvailableTemplates(0)
    return () => {
      catalogRequestVersion.current += 1
      templatesRequestVersion.current += 1
    }
  }, [loadAvailableTemplates, loadCatalog, role])

  useEffect(() => {
    if (
      createTemplateId.length > 0
      && templatesState.kind === 'ready'
      && templatesState.page.items.some((template) => template.id === createTemplateId)
    ) {
      setConfirmedTemplateId(createTemplateId)
    }
  }, [createTemplateId, templatesState])

  useEffect(() => {
    const id = openedInteractionId.current
    if (id === null) {
      return
    }
    void loadInteraction(id)
    void loadEvents(id)
  }, [loadEvents, loadInteraction])

  useEffect(() => {
    saveInteractionDraft(profileId, organizationId, {
      createTitle,
      createNextAction,
      createNextActionAt,
      createProgramId,
      createTemplateId,
      selectedProductIds,
      createLastContactAt,
      selectedContactIds,
      contactName,
      contactPosition,
      contactEmail,
      contactPhone,
      activeInteractionId
    })
  }, [
    activeInteractionId,
    contactEmail,
    contactName,
    contactPhone,
    contactPosition,
    createNextAction,
    createNextActionAt,
    createLastContactAt,
    createProgramId,
    createTemplateId,
    createTitle,
    organizationId,
    profileId,
    selectedProductIds,
    selectedContactIds
  ])

  useEffect(() => {
    if (activeInteractionId === null) {
      return
    }
    saveInteractionCardDraft(profileId, activeInteractionId, {
      commentStageId,
      commentDraft,
      commentNextAction,
      commentNextActionAt,
      transitionStageId,
      transitionDraft,
      transitionNextAction,
      transitionNextActionAt,
      stageEditType,
      stageEditId,
      stageEditAfterId,
      stageEditName,
      stageEditOptional,
      plan: planDraft
    })
  }, [
    activeInteractionId,
    commentDraft,
    commentNextAction,
    commentNextActionAt,
    commentStageId,
    planDraft,
    profileId,
    stageEditAfterId,
    stageEditId,
    stageEditName,
    stageEditOptional,
    stageEditType,
    transitionDraft,
    transitionNextAction,
    transitionNextActionAt,
    transitionStageId
  ])

  const currentInteraction = detailState.kind === 'ready' ? detailState.interaction : undefined
  const currentInteractionId = currentInteraction?.id

  useEffect(() => {
    if (focusInitialInteraction.current && currentInteractionId !== undefined && currentInteractionId === initialInteractionId) {
      focusInitialInteraction.current = false
      detailHeading.current?.focus()
    }
  }, [currentInteractionId, initialInteractionId])

  const loadedPages = listState.kind === 'ready' ? listState.pages : 1
  const planValues = currentInteraction === undefined ? undefined : planValuesOf(currentInteraction, planDraft)
  const planPayload = currentInteraction === undefined ? undefined : planChanges(currentInteraction, planDraft)
  const planConflictList = currentInteraction === undefined ? [] : planConflicts(currentInteraction, planDraft)
  const planHasChanges = planPayload !== undefined && Object.keys(planPayload).length > 1
  const currentEvents = eventsState.kind === 'ready' && eventsState.id === currentInteraction?.id
    ? eventsState.events
    : undefined
  const canManageDailyWork = role === 'USER' || role === 'LEADER'
  const availableProgramIds = catalogState.kind === 'ready'
    ? new Set(catalogState.programs.map((program) => program.id))
    : undefined
  const availableProductIds = catalogState.kind === 'ready'
    ? new Set(catalogState.products.map((product) => product.id))
    : undefined
  const unavailableProgram = createProgramId.length > 0
    && availableProgramIds !== undefined
    && !availableProgramIds.has(createProgramId)
  const unavailableProductIds = availableProductIds === undefined
    ? []
    : selectedProductIds.filter((id) => !availableProductIds.has(id))
  const hasCatalogDraft = createProgramId.length > 0 || selectedProductIds.length > 0
  const catalogSelectionRequiresResolution = (
    catalogState.kind !== 'ready' && hasCatalogDraft
  ) || unavailableProgram || unavailableProductIds.length > 0
  const currentTemplatePageHasSelection = templatesState.kind === 'ready'
    && templatesState.page.items.some((template) => template.id === createTemplateId)
  const unavailableTemplate = createTemplateId.length > 0
    && templatesState.kind === 'ready'
    && templatesState.page.total === 0
  const templateSelectionRequiresResolution = createTemplateId.length > 0 && (
    templatesState.kind !== 'ready' || confirmedTemplateId !== createTemplateId
  )
  const stageIds = new Set(currentInteraction?.stages.map((stage) => stage.id) ?? [])
  const stageEditTargetExists = stageEditId.length > 0 && stageIds.has(stageEditId)
  const stageEditAfterExists = stageEditAfterId.length > 0 && stageIds.has(stageEditAfterId)
  const stageEditNeedsTarget = stageEditType !== 'ADD_AFTER'
  const stageEditNeedsAfter = stageEditType === 'ADD_AFTER' || stageEditType === 'MOVE_AFTER'
  const stageEditNeedsName = stageEditType === 'RENAME' || stageEditType === 'ADD_AFTER'
  const stageEditCanSubmit = currentInteraction !== undefined
    && (!stageEditNeedsTarget || stageEditTargetExists)
    && (!stageEditNeedsAfter || stageEditAfterExists)
    && (!stageEditNeedsName || stageEditName.trim().length > 0)
    && (stageEditType !== 'MOVE_AFTER' || stageEditId !== stageEditAfterId)
  const selectedInteractionId = currentInteraction?.id
    ?? (detailState.kind === 'loading' || detailState.kind === 'failed' ? detailState.id : undefined)

  const submitCreate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const title = createTitle.trim()
    if (title.length === 0) {
      return
    }
    const payload: InteractionCreate = {
      organizationId,
      title,
      nextAction: createNextAction.trim() || null,
      nextActionAt: toIsoDateTime(createNextActionAt),
      contactIds: selectedContactIds,
      programId: createProgramId || null,
      productIds: selectedProductIds,
      lastContactAt: toIsoDateTime(createLastContactAt),
      templateId: createTemplateId || null
    }
    setCreateState({ kind: 'saving' })
    try {
      const interaction = await apiClient.createInteraction(
        payload,
        createKey.current ?? (createKey.current = createIdempotencyKey())
      )
      createKey.current = null
      setCreateTitle('')
      setCreateNextAction('')
      setCreateNextActionAt('')
      setCreateProgramId('')
      setCreateTemplateId('')
      setConfirmedTemplateId(null)
      setSelectedProductIds([])
      setCreateLastContactAt('')
      setSelectedContactIds([])
      setCreateState({ kind: 'idle' })
      void loadInteractions(loadedPages)
      openInteraction(interaction.id)
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setCreateState({ kind: 'failed', error })
    }
  }

  const submitContact = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const name = contactName.trim()
    if (name.length === 0) {
      return
    }
    const payload: ContactCreate = {
      name,
      position: contactPosition.trim() || null,
      email: contactEmail.trim() || null,
      phone: contactPhone.trim() || null
    }
    setContactCreateState({ kind: 'saving' })
    try {
      const contact = await apiClient.createOrganizationContact(
        organizationId,
        payload,
        contactCreateKey.current ?? (contactCreateKey.current = createIdempotencyKey())
      )
      contactCreateKey.current = null
      setContactName('')
      setContactPosition('')
      setContactEmail('')
      setContactPhone('')
      setContactCreateState({ kind: 'idle' })
      setSelectedContactIds((ids) => ids.includes(contact.id) ? ids : [...ids, contact.id])
      void loadContacts()
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setContactCreateState({ kind: 'failed', error })
    }
  }

  const replaceAttachment = (attachment: Attachment) => {
    setDetailState((current) => {
      if (current.kind !== 'ready' || current.interaction.id !== attachment.interactionId) {
        return current
      }
      const attachments = current.interaction.attachments.some((item) => item.id === attachment.id)
        ? current.interaction.attachments.map((item) => item.id === attachment.id ? attachment : item)
        : [...current.interaction.attachments, attachment]
      return {
        kind: 'ready',
        interaction: {
          ...current.interaction,
          attachments
        }
      }
    })
  }

  const submitUpload = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (currentInteraction === undefined || uploadFile === null || uploadStageId.length === 0) {
      return
    }
    setUploadState({ kind: 'saving' })
    try {
      const attachment = await apiClient.uploadInteractionAttachment(
        currentInteraction.id,
        { file: uploadFile, stageId: uploadStageId },
        uploadKey.current ?? (uploadKey.current = createIdempotencyKey())
      )
      uploadKey.current = null
      replaceAttachment(attachment)
      setUploadFile(null)
      if (uploadInput.current !== null) {
        uploadInput.current.value = ''
      }
      setUploadState({ kind: 'idle' })
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setUploadState({ kind: 'failed', error })
    }
  }

  const refreshAttachment = async (attachment: Attachment) => {
    setAttachmentRefreshState({ kind: 'loading', id: attachment.id })
    try {
      const updatedAttachment = await apiClient.getAttachment(attachment.id)
      replaceAttachment(updatedAttachment)
      setAttachmentRefreshState({ kind: 'idle' })
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setAttachmentRefreshState({ kind: 'failed', id: attachment.id, error })
    }
  }

  const downloadAttachment = async (attachment: Attachment) => {
    setAttachmentDownloadState({ kind: 'downloading', id: attachment.id })
    try {
      const blob = await apiClient.downloadAttachment(attachment.id)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = attachment.originalName
      document.body.append(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 0)
      setAttachmentDownloadState({ kind: 'idle' })
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setAttachmentDownloadState({ kind: 'failed', id: attachment.id, error })
    }
  }

  const submitComment = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (currentInteraction === undefined || commentStageId.length === 0) {
      return
    }
    const comment = commentDraft.trim()
    if (comment.length === 0) {
      return
    }
    const payload: InteractionComment = {
      version: currentInteraction.version,
      stageId: commentStageId,
      text: comment,
      attachmentIds: commentAttachmentIds,
      nextStep: nextStepPayload(currentInteraction, commentNextAction, commentNextActionAt)
    }
    setCommentState({ kind: 'saving' })
    try {
      const result = await apiClient.commentInteraction(
        currentInteraction.id,
        payload,
        commentKey.current ?? (commentKey.current = createIdempotencyKey())
      )
      const interaction = result.interaction
      commentKey.current = null
      setDetailState({ kind: 'ready', interaction })
      setCommentStageId(interaction.currentStageId)
      setCommentDraft('')
      setCommentNextAction('')
      setCommentNextActionAt('')
      setCommentAttachmentIds([])
      setTransitionAttachmentIds([])
      setCommentState({ kind: 'idle' })
      void loadEvents(interaction.id)
      void loadInteractions(loadedPages)
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setCommentState({ kind: 'failed', error })
    }
  }

  const submitTransition = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (currentInteraction === undefined || transitionStageId.length === 0) {
      return
    }
    const payload: InteractionTransition = {
      version: currentInteraction.version,
      toStageId: transitionStageId,
      comment: transitionDraft.trim() || null,
      attachmentIds: transitionAttachmentIds,
      nextStep: nextStepPayload(currentInteraction, transitionNextAction, transitionNextActionAt)
    }
    setTransitionState({ kind: 'saving' })
    try {
      const interaction = await apiClient.transitionInteraction(
        currentInteraction.id,
        payload,
        transitionKey.current ?? (transitionKey.current = createIdempotencyKey())
      )
      transitionKey.current = null
      setDetailState({ kind: 'ready', interaction })
      setCommentStageId(interaction.currentStageId)
      setTransitionStageId('')
      setTransitionDraft('')
      setTransitionNextAction('')
      setTransitionNextActionAt('')
      setCommentAttachmentIds([])
      setTransitionAttachmentIds([])
      setTransitionState({ kind: 'idle' })
      void loadEvents(interaction.id)
      void loadInteractions(loadedPages)
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setTransitionState({ kind: 'failed', error })
    }
  }

  const submitStageEdit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (currentInteraction === undefined || !stageEditCanSubmit) {
      return
    }
    const name = stageEditName.trim()
    const operation: InteractionStageEdit['operations'][number] = stageEditType === 'RENAME'
      ? { type: 'RENAME', id: stageEditId, name }
      : stageEditType === 'ADD_AFTER'
        ? { type: 'ADD_AFTER', afterId: stageEditAfterId, name, optional: stageEditOptional }
        : stageEditType === 'MOVE_AFTER'
          ? { type: 'MOVE_AFTER', id: stageEditId, afterId: stageEditAfterId }
          : { type: 'DELETE', id: stageEditId }
    const payload: InteractionStageEdit = {
      version: currentInteraction.version,
      operations: [operation]
    }
    setStageEditState({ kind: 'saving' })
    try {
      const interaction = await apiClient.editInteractionStages(
        currentInteraction.id,
        payload,
        stageEditKey.current ?? (stageEditKey.current = createIdempotencyKey())
      )
      stageEditKey.current = null
      setDetailState({ kind: 'ready', interaction })
      setStageEditId('')
      setStageEditAfterId('')
      setStageEditName('')
      setStageEditOptional(false)
      setStageEditState({ kind: 'idle' })
      void loadEvents(interaction.id)
      void loadInteractions(loadedPages)
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setStageEditState({ kind: 'failed', error })
    }
  }

  const submitPlan = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (currentInteraction === undefined || planDraft === null) {
      return
    }
    const payload = planChanges(currentInteraction, planDraft)
    if (Object.keys(payload).length === 1) {
      return
    }
    setPlanState({ kind: 'saving' })
    try {
      const interaction = await apiClient.updateInteractionPlan(
        currentInteraction.id,
        payload,
        planKey.current ?? (planKey.current = createIdempotencyKey())
      )
      planKey.current = null
      setDetailState({ kind: 'ready', interaction })
      setPlanDraft(null)
      setPlanState({ kind: 'idle' })
      void loadEvents(interaction.id)
      void loadInteractions(loadedPages)
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setPlanState({ kind: 'failed', error })
    }
  }

  const changePlan = (field: PlanField, value: string) => {
    if (currentInteraction === undefined) {
      return
    }
    planKey.current = null
    setPlanState({ kind: 'idle' })
    const current = planFromInteraction(currentInteraction)[field]
    setPlanDraft((draft) => {
      const next = {
        ...(draft ?? emptyPlanDraft()),
        [field]: value === current ? undefined : { value, base: draft?.[field]?.base ?? current }
      }
      return isEmptyPlanDraft(next) ? null : next
    })
  }

  const togglePlanProduct = (id: string) => {
    if (currentInteraction === undefined) {
      return
    }
    planKey.current = null
    setPlanState({ kind: 'idle' })
    const linked = currentInteraction.productIds.includes(id)
    setPlanDraft((draft) => {
      const selected = planValuesOf(currentInteraction, draft).productIds.includes(id)
      const addedProductIds = (draft?.addedProductIds ?? []).filter((productId) => productId !== id)
      const removedProductIds = (draft?.removedProductIds ?? []).filter((productId) => productId !== id)
      if (selected && linked) {
        removedProductIds.push(id)
      }
      if (!selected && !linked) {
        addedProductIds.push(id)
      }
      const next = { ...(draft ?? emptyPlanDraft()), addedProductIds, removedProductIds }
      return isEmptyPlanDraft(next) ? null : next
    })
  }

  const resetPlan = () => {
    planKey.current = null
    setPlanDraft(null)
    setPlanState({ kind: 'idle' })
  }

  const changeCommentNextAction = (value: string) => {
    commentKey.current = null
    setCommentNextAction(value)
  }

  const changeCommentNextActionAt = (value: string) => {
    commentKey.current = null
    setCommentNextActionAt(value)
  }

  const changeTransitionNextAction = (value: string) => {
    transitionKey.current = null
    setTransitionNextAction(value)
  }

  const changeTransitionNextActionAt = (value: string) => {
    transitionKey.current = null
    setTransitionNextActionAt(value)
  }

  const changeCreateTitle = (value: string) => {
    createKey.current = null
    setCreateTitle(value)
  }

  const changeCreateNextAction = (value: string) => {
    createKey.current = null
    setCreateNextAction(value)
  }

  const changeCreateNextActionAt = (value: string) => {
    createKey.current = null
    setCreateNextActionAt(value)
  }

  const changeCreateProgram = (value: string) => {
    createKey.current = null
    setCreateProgramId(value)
  }

  const changeCreateTemplate = (value: string) => {
    createKey.current = null
    setCreateTemplateId(value)
    setConfirmedTemplateId(value.length === 0 ? null : value)
  }

  const toggleProduct = (id: string) => {
    createKey.current = null
    setSelectedProductIds((ids) => ids.includes(id)
      ? ids.filter((selectedId) => selectedId !== id)
      : [...ids, id])
  }

  const changeCreateLastContactAt = (value: string) => {
    createKey.current = null
    setCreateLastContactAt(value)
  }

  const clearUnavailableProgram = () => {
    createKey.current = null
    setCreateProgramId('')
  }

  const clearUnavailableProducts = () => {
    createKey.current = null
    setSelectedProductIds((ids) => ids.filter((id) => availableProductIds?.has(id)))
  }

  const clearUnavailableTemplate = () => {
    createKey.current = null
    setCreateTemplateId('')
    setConfirmedTemplateId(null)
  }

  const changeTemplatesPage = (page: number) => {
    setTemplatesPageIndex(page)
    void loadAvailableTemplates(page)
  }

  const changeUploadStage = (value: string) => {
    uploadKey.current = null
    setUploadStageId(value)
  }

  const uploadTooLarge = uploadFile !== null && uploadFile.size > attachmentLimitBytes
  const uploadFileError = uploadTooLarge
    ? `Файл больше ${attachmentLimitMegabytes} МБ. Выберите файл меньшего размера.`
    : fieldErrorOf(uploadState, 'file')

  const changeUploadFile = (file: File | null) => {
    uploadKey.current = null
    setUploadFile(file)
    setUploadState({ kind: 'idle' })
  }

  const toggleContact = (id: Contact['id']) => {
    createKey.current = null
    setSelectedContactIds((ids) => ids.includes(id)
      ? ids.filter((selectedId) => selectedId !== id)
      : [...ids, id])
  }

  const changeContactName = (value: string) => {
    contactCreateKey.current = null
    setContactName(value)
  }

  const changeContactPosition = (value: string) => {
    contactCreateKey.current = null
    setContactPosition(value)
  }

  const changeContactEmail = (value: string) => {
    contactCreateKey.current = null
    setContactEmail(value)
  }

  const changeContactPhone = (value: string) => {
    contactCreateKey.current = null
    setContactPhone(value)
  }

  const changeCommentStage = (value: string) => {
    commentKey.current = null
    setCommentStageId(value)
    setCommentAttachmentIds([])
  }

  const toggleCommentAttachment = (id: Attachment['id']) => {
    commentKey.current = null
    setCommentAttachmentIds((ids) => ids.includes(id)
      ? ids.filter((attachmentId) => attachmentId !== id)
      : [...ids, id])
  }

  const changeCommentDraft = (value: string) => {
    commentKey.current = null
    setCommentDraft(value)
  }

  const changeTransitionStage = (value: string) => {
    transitionKey.current = null
    setTransitionStageId(value)
    setTransitionAttachmentIds([])
  }

  const toggleTransitionAttachment = (id: Attachment['id']) => {
    transitionKey.current = null
    setTransitionAttachmentIds((ids) => ids.includes(id)
      ? ids.filter((attachmentId) => attachmentId !== id)
      : [...ids, id])
  }

  const changeTransitionDraft = (value: string) => {
    transitionKey.current = null
    setTransitionDraft(value)
  }

  const changeStageEditType = (value: InteractionStageEditType) => {
    stageEditKey.current = null
    setStageEditType(value)
    setStageEditState({ kind: 'idle' })
  }

  const changeStageEditId = (value: string) => {
    stageEditKey.current = null
    setStageEditId(value)
    setStageEditState({ kind: 'idle' })
  }

  const changeStageEditAfterId = (value: string) => {
    stageEditKey.current = null
    setStageEditAfterId(value)
    setStageEditState({ kind: 'idle' })
  }

  const changeStageEditName = (value: string) => {
    stageEditKey.current = null
    setStageEditName(value)
    setStageEditState({ kind: 'idle' })
  }

  const changeStageEditOptional = (value: boolean) => {
    stageEditKey.current = null
    setStageEditOptional(value)
    setStageEditState({ kind: 'idle' })
  }

  const planProgramOptions = catalogState.kind === 'ready'
    ? catalogState.programs.map((program) => ({ id: program.id, name: program.name }))
    : []
  if (currentInteraction?.program && !planProgramOptions.some((program) => program.id === currentInteraction.program?.id)) {
    planProgramOptions.push({ id: currentInteraction.program.id, name: `${currentInteraction.program.name} (архивирована)` })
  }
  const lockedProductIds = new Set(currentInteraction?.productAgreements
    .filter((agreement) => agreement.contractNumber !== null
      || agreement.licenseSigned !== null
      || agreement.licenseExpiryYear !== null
      || agreement.transferStatus !== null)
    .map((agreement) => agreement.productId) ?? [])
  const planProductOptions = catalogState.kind === 'ready'
    ? catalogState.products.map((product) => ({ id: product.id, name: product.name }))
    : []
  currentInteraction?.productAgreements.forEach((agreement) => {
    if (!planProductOptions.some((product) => product.id === agreement.productId)) {
      planProductOptions.push({
        id: agreement.productId,
        name: agreement.productArchived ? `${agreement.productName} (архивирован)` : agreement.productName
      })
    }
  })
  const pathItems = currentInteraction === undefined ? [] : stagePath(currentInteraction, currentEvents)
  const selectedTransition = currentInteraction?.allowedTransitions.find((option) => option.stageId === transitionStageId)
  const schedule = currentInteraction === undefined ? undefined : nextActionSchedule(currentInteraction)
  const contactsById = contactsState.kind === 'ready'
    ? new Map(contactsState.contacts.map((contact) => [contact.id, contact]))
    : undefined
  const linkedContacts = currentInteraction?.contactIds.map((id) => ({
    id,
    contact: contactsById?.get(id)
  })) ?? []
  const attachmentStageNames = new Map(currentInteraction?.stages.map((stage) => [stage.id, stage.name]) ?? [])
  const attachableAttachments = currentInteraction?.attachments.filter((attachment) => (
    attachment.status === 'CLEAN' && attachment.eventId === null
  )) ?? []
  const commentAttachments = attachableAttachments.filter((attachment) => attachment.stageId === commentStageId)
  const transitionAttachments = selectedTransition === undefined
    ? []
    : attachableAttachments.filter((attachment) => attachment.stageId === selectedTransition.stageId)
  const attachmentsByEvent = new Map<InteractionEvent['id'], Attachment[]>()
  currentInteraction?.attachments.forEach((attachment) => {
    if (attachment.eventId === null) {
      return
    }
    const attachments = attachmentsByEvent.get(attachment.eventId) ?? []
    attachments.push(attachment)
    attachmentsByEvent.set(attachment.eventId, attachments)
  })

  return (
    <section className="interactions-panel" aria-labelledby="interactions-title">
      <div className="interactions-header">
        <div>
          <p className="eyebrow">Работа с организацией</p>
          <h4 id="interactions-title">Взаимодействия</h4>
        </div>
        {listState.kind === 'ready' && <p className="interactions-total">Всего: {listState.total}</p>}
      </div>
      {!canManageDailyWork && (
        <p className="interactions-read-only" role="status">
          Режим просмотра портфеля: доступны контакты, взаимодействия и история. Добавлять контакты, создавать взаимодействия, комментарии и переходы может ответственный КАМ.
        </p>
      )}

      <section className="interaction-contacts" aria-labelledby="interaction-contacts-title">
        <h5 id="interaction-contacts-title">Контакты организации</h5>
        {contactsState.kind === 'loading' && (
          <p className="organizations-message" role="status">Загружаем контакты…</p>
        )}
        {contactsState.kind === 'failed' && (
          <div className="organizations-message organizations-message--error" role="alert">
            <p>Не удалось загрузить контакты организации.</p>
            <SupportDetails requestId={contactsState.requestId} />
            <button type="button" onClick={() => void loadContacts()}>Повторить</button>
          </div>
        )}
        {contactsState.kind === 'ready' && contactsState.contacts.length === 0 && (
          <p className="organizations-message">
            {canManageDailyWork
              ? 'Контактов пока нет. Добавьте контакт, чтобы связать его с новым взаимодействием.'
              : 'Контактов пока нет.'}
          </p>
        )}
        {canManageDailyWork && (
          <form className="interaction-contact-form" onSubmit={(event) => void submitContact(event)}>
            <h6>Добавить контакт</h6>
            <label>
              ФИО
              <input
                value={contactName}
                maxLength={200}
                required
                onChange={(event) => changeContactName(event.target.value)}
                placeholder="Фамилия Имя Отчество"
                {...invalidProps(fieldErrorOf(contactCreateState, 'name'), 'contact-name-error')}
              />
              <FieldError id="contact-name-error" message={fieldErrorOf(contactCreateState, 'name')} />
            </label>
            <label>
              Должность
              <input
                value={contactPosition}
                maxLength={200}
                onChange={(event) => changeContactPosition(event.target.value)}
                placeholder="Например, декан"
                {...invalidProps(fieldErrorOf(contactCreateState, 'position'), 'contact-position-error')}
              />
              <FieldError id="contact-position-error" message={fieldErrorOf(contactCreateState, 'position')} />
            </label>
            <label>
              Электронная почта
              <input
                type="email"
                value={contactEmail}
                maxLength={254}
                onChange={(event) => changeContactEmail(event.target.value)}
                {...invalidProps(fieldErrorOf(contactCreateState, 'email'), 'contact-email-error')}
              />
              <FieldError id="contact-email-error" message={fieldErrorOf(contactCreateState, 'email')} />
            </label>
            <label>
              Телефон
              <input
                value={contactPhone}
                maxLength={40}
                onChange={(event) => changeContactPhone(event.target.value)}
                {...invalidProps(fieldErrorOf(contactCreateState, 'phone'), 'contact-phone-error')}
              />
              <FieldError id="contact-phone-error" message={fieldErrorOf(contactCreateState, 'phone')} />
            </label>
            <button type="submit" disabled={contactCreateState.kind === 'saving'}>
              {contactCreateState.kind === 'saving' ? 'Добавляем…' : 'Добавить контакт'}
            </button>
            {contactCreateState.kind === 'failed' && (
              <div className="interaction-command-error" role="alert">
                <p>{commandMessage(contactCreateState.error)}</p>
                <StructuredApiError error={contactCreateState.error} />
                {contactCreateState.error instanceof ApiError && <SupportDetails requestId={contactCreateState.error.requestId} code={contactCreateState.error.code} />}
              </div>
            )}
          </form>
        )}
      </section>

      {canManageDailyWork && <form className="interaction-create-form" onSubmit={(event) => void submitCreate(event)}>
        <h5>Новое взаимодействие</h5>
        <label>
          Название
          <input
            value={createTitle}
            maxLength={200}
            required
            onChange={(event) => changeCreateTitle(event.target.value)}
            placeholder="Например, запуск программы по ИТ-направлению"
            {...invalidProps(fieldErrorOf(createState, 'title'), 'interaction-create-title-error')}
          />
          <FieldError id="interaction-create-title-error" message={fieldErrorOf(createState, 'title')} />
        </label>
        <label>
          Шаблон процесса
          <select
            value={createTemplateId}
            disabled={templatesState.kind !== 'ready'}
            onChange={(event) => changeCreateTemplate(event.target.value)}
          >
            <option value="">Шаблон по умолчанию</option>
            {createTemplateId.length > 0 && !currentTemplatePageHasSelection && (
              <option value={createTemplateId}>Сохранённый выбор: найдите шаблон на странице списка</option>
            )}
            {templatesState.kind === 'ready' && templatesState.page.items.map((template) => (
              <option key={template.id} value={template.id}>{template.name}</option>
            ))}
          </select>
        </label>
        {templatesState.kind === 'loading' && (
          <p className="interaction-catalog-message" role="status">Загружаем доступные шаблоны процесса…</p>
        )}
        {templatesState.kind === 'failed' && (
          <div className="interaction-catalog-message interaction-catalog-message--error" role="alert">
            <p>Не удалось загрузить доступные шаблоны процесса.</p>
            <SupportDetails requestId={templatesState.requestId} />
            <button type="button" onClick={() => void loadAvailableTemplates(templatesPageIndex)}>Повторить</button>
          </div>
        )}
        {templatesState.kind === 'ready' && templatesState.page.total > templatesState.page.size && (
          <nav className="interaction-template-pagination" aria-label="Страницы шаблонов процесса">
            <button
              type="button"
              disabled={templatesState.page.page === 0}
              onClick={() => changeTemplatesPage(templatesState.page.page - 1)}
            >
              Предыдущая
            </button>
            <p>Страница {templatesState.page.page + 1} из {Math.ceil(templatesState.page.total / templatesState.page.size)}</p>
            <button
              type="button"
              disabled={(templatesState.page.page + 1) * templatesState.page.size >= templatesState.page.total}
              onClick={() => changeTemplatesPage(templatesState.page.page + 1)}
            >
              Следующая
            </button>
          </nav>
        )}
        {unavailableTemplate && (
          <div className="interaction-catalog-message interaction-catalog-message--error" role="alert">
            <p>Выбранный ранее шаблон больше недоступен в текущей области. Выберите другой шаблон или шаблон по умолчанию.</p>
            <button type="button" onClick={clearUnavailableTemplate}>Использовать шаблон по умолчанию</button>
          </div>
        )}
        {templatesState.kind === 'ready' && templateSelectionRequiresResolution && !currentTemplatePageHasSelection && (
          <div className="interaction-catalog-message interaction-catalog-message--error" role="alert">
            <p>Сохранённый шаблон ещё не подтверждён в доступной области. Перейдите по страницам списка или выберите другой шаблон.</p>
          </div>
        )}
        {templatesState.kind !== 'ready' && templateSelectionRequiresResolution && (
          <div className="interaction-catalog-message interaction-catalog-message--error" role="alert">
            <p>Черновик содержит шаблон процесса. Загрузите доступные шаблоны перед отправкой.</p>
          </div>
        )}
        <fieldset className="interaction-contact-picker">
          <legend>Связанные контакты</legend>
          {contactsState.kind === 'loading' && <p>Загружаем доступные контакты…</p>}
          {contactsState.kind === 'failed' && <p>Контакты пока недоступны. Их можно повторно загрузить выше.</p>}
          {contactsState.kind === 'ready' && (
            contactsState.contacts.length === 0 ? (
              <p>Сначала добавьте контакт организации.</p>
            ) : (
              <ul>
                {contactsState.contacts.map((contact) => (
                  <li key={contact.id}>
                    <label>
                      <input
                        type="checkbox"
                        checked={selectedContactIds.includes(contact.id)}
                        onChange={() => toggleContact(contact.id)}
                      />
                      <span>{contact.name}</span>
                      {contact.position && <small>{contact.position}</small>}
                    </label>
                  </li>
                ))}
              </ul>
            )
          )}
        </fieldset>
        {catalogState.kind === 'loading' && (
          <p className="interaction-catalog-message" role="status">Загружаем доступные программы и продукты…</p>
        )}
        {catalogState.kind === 'failed' && (
          <div className="interaction-catalog-message interaction-catalog-message--error" role="alert">
            <p>Не удалось загрузить программы и продукты.</p>
            <SupportDetails requestId={catalogState.requestId} />
            <button type="button" onClick={() => void loadCatalog()}>Повторить</button>
          </div>
        )}
        {catalogState.kind === 'ready' && (
          <>
            <label>
              Программа
              <select
                value={unavailableProgram ? '' : createProgramId}
                onChange={(event) => changeCreateProgram(event.target.value)}
              >
                <option value="">Не указана</option>
                {catalogState.programs.map((program) => (
                  <option key={program.id} value={program.id}>{program.name}</option>
                ))}
              </select>
            </label>
            <fieldset className="interaction-catalog-picker">
              <legend>Продукты</legend>
              {catalogState.products.length === 0 ? (
                <p>Доступных продуктов нет. Их можно не указывать.</p>
              ) : (
                <ul>
                  {catalogState.products.map((product) => (
                    <li key={product.id}>
                      <label>
                        <input
                          type="checkbox"
                          checked={selectedProductIds.includes(product.id)}
                          onChange={() => toggleProduct(product.id)}
                        />
                        <span>{product.name}</span>
                      </label>
                    </li>
                  ))}
                </ul>
              )}
              {catalogState.productTotal > catalogState.products.length && (
                <p>Показаны первые {catalogState.products.length} из {catalogState.productTotal} доступных продуктов.</p>
              )}
            </fieldset>
            {catalogState.programTotal > catalogState.programs.length && (
              <p className="interaction-catalog-message">Показаны первые {catalogState.programs.length} из {catalogState.programTotal} доступных программ.</p>
            )}
            {unavailableProgram && (
              <div className="interaction-catalog-message interaction-catalog-message--error" role="alert">
                <p>Выбранная ранее программа больше недоступна. Снимите выбор или выберите доступную программу.</p>
                <button type="button" onClick={clearUnavailableProgram}>Снять программу</button>
              </div>
            )}
            {unavailableProductIds.length > 0 && (
              <div className="interaction-catalog-message interaction-catalog-message--error" role="alert">
                <p>Один или несколько ранее выбранных продуктов больше недоступны. Снимите их перед отправкой.</p>
                <button type="button" onClick={clearUnavailableProducts}>Снять недоступные продукты</button>
              </div>
            )}
          </>
        )}
        {catalogState.kind !== 'ready' && hasCatalogDraft && (
          <div className="interaction-catalog-message interaction-catalog-message--error" role="alert">
            <p>Черновик содержит программу или продукты. Загрузите справочник перед отправкой, чтобы проверить выбор.</p>
          </div>
        )}
        <label>
          Следующий шаг
          <textarea
            value={createNextAction}
            maxLength={500}
            onChange={(event) => changeCreateNextAction(event.target.value)}
            placeholder="Например, согласовать дату встречи"
            {...invalidProps(fieldErrorOf(createState, 'nextAction'), 'interaction-create-next-action-error')}
          />
          <FieldError id="interaction-create-next-action-error" message={fieldErrorOf(createState, 'nextAction')} />
        </label>
        <label>
          Срок следующего шага
          <input
            type="datetime-local"
            value={createNextActionAt}
            onChange={(event) => changeCreateNextActionAt(event.target.value)}
          />
        </label>
        <label>
          Дата последнего контакта
          <input
            type="datetime-local"
            value={createLastContactAt}
            onChange={(event) => changeCreateLastContactAt(event.target.value)}
          />
          <span className="interaction-field-hint">Укажите только известный факт контакта. Комментарий и переход эту дату не меняют.</span>
        </label>
        <button type="submit" disabled={createState.kind === 'saving' || catalogSelectionRequiresResolution || templateSelectionRequiresResolution}>
          {createState.kind === 'saving' ? 'Создаём…' : 'Создать взаимодействие'}
        </button>
        {createState.kind === 'failed' && (
          <div className="interaction-command-error" role="alert">
            <p>{commandMessage(createState.error)}</p>
            <StructuredApiError error={createState.error} />
            {createState.error instanceof ApiError && <SupportDetails requestId={createState.error.requestId} code={createState.error.code} />}
          </div>
        )}
      </form>}

      {listState.kind === 'loading' && (
        <p className="organizations-message" role="status">Загружаем взаимодействия…</p>
      )}
      {listState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить взаимодействия. Повторите попытку.</p>
          <SupportDetails requestId={listState.requestId} />
          <button type="button" onClick={() => void loadInteractions(1)}>Повторить</button>
        </div>
      )}
      {listState.kind === 'ready' && (
        listState.items.length === 0 ? (
          <p className="organizations-message">У этой организации пока нет взаимодействий.</p>
        ) : (
          <ul className="interactions-list" aria-label="Взаимодействия организации" aria-busy={listState.refreshing}>
            {listState.items.map((interaction) => {
              const selected = interaction.id === selectedInteractionId
              return (
                <li key={interaction.id}>
                  <button
                    type="button"
                    className={`interaction-list-item${selected ? ' interaction-list-item--selected' : ''}`}
                    aria-current={selected ? 'true' : undefined}
                    onClick={() => openInteraction(interaction.id)}
                  >
                    <span className="interaction-list-item__title">{interaction.title}</span>
                    <span className="interaction-list-item__stage">Этап: {interaction.currentStageName}</span>
                    <span className="interaction-list-item__action">{interaction.nextAction ?? 'Следующий шаг не задан'}</span>
                    <span className="interaction-list-item__catalog">Программа: {interaction.programName ?? 'не указана'}</span>
                    <span className="interaction-list-item__catalog">Продукты: {interaction.productIds.length === 0 ? 'не указаны' : interaction.productIds.length}</span>
                    <span className="interaction-list-item__catalog">Последний контакт: {lastContactLabel(interaction.lastContactAt)}</span>
                  </button>
                </li>
              )
            })}
          </ul>
        )
      )}
      {listState.kind === 'ready' && listState.items.length < listState.total && (
        <button
          type="button"
          className="interactions-more button--secondary"
          disabled={listState.loadingMore}
          onClick={() => void loadInteractions(listState.pages + 1)}
        >
          {listState.loadingMore ? 'Загружаем…' : `Показать ещё (показано ${listState.items.length} из ${listState.total})`}
        </button>
      )}

      <section className="interaction-detail" aria-labelledby="interaction-detail-title">
        <h5 id="interaction-detail-title" ref={detailHeading} tabIndex={-1}>
          {currentInteraction === undefined ? 'Карточка взаимодействия' : `Карточка взаимодействия «${currentInteraction.title}»`}
        </h5>
        {detailState.kind === 'idle' && (
          <p className="organizations-message">Выберите взаимодействие из списка, чтобы открыть карточку.</p>
        )}
        {detailState.kind === 'loading' && (
          <p className="organizations-message" role="status">Загружаем карточку взаимодействия…</p>
        )}
        {detailState.kind === 'failed' && (
          <div className="organizations-message organizations-message--error" role="alert">
            <p>Не удалось загрузить карточку взаимодействия. Повторите попытку.</p>
            <SupportDetails requestId={detailState.requestId} />
            <button type="button" onClick={() => openInteraction(detailState.id)}>Повторить</button>
          </div>
        )}
        {currentInteraction !== undefined && (
          <>
            <dl className="interaction-summary">
              <div>
                <dt>Название</dt>
                <dd>{currentInteraction.title}</dd>
              </div>
              <div>
                <dt>Текущий этап</dt>
                <dd>{currentInteraction.currentStageName}</dd>
              </div>
              <div>
                <dt>Следующий шаг</dt>
                <dd>{currentInteraction.nextAction ?? 'Не задан'}</dd>
              </div>
              <div>
                <dt>Срок</dt>
                <dd className={schedule?.className}>{schedule?.label}</dd>
              </div>
              <div>
                <dt>Программа</dt>
                <dd>
                  {currentInteraction.program === null ? 'Не указана' : currentInteraction.program.name}
                  {currentInteraction.program?.archived && <span className="interaction-archived">Архивирована</span>}
                </dd>
              </div>
              <div>
                <dt>Последний контакт</dt>
                <dd>{lastContactLabel(currentInteraction.lastContactAt)}</dd>
              </div>
            </dl>

            <section className="interaction-linked-contacts" aria-labelledby="interaction-linked-contacts-title">
              <h6 id="interaction-linked-contacts-title">Связанные контакты</h6>
              {linkedContacts.length === 0 && <p>Контакты для этого взаимодействия не выбраны.</p>}
              {linkedContacts.length > 0 && contactsState.kind === 'loading' && (
                <p>Загружаем сведения о связанных контактах…</p>
              )}
              {linkedContacts.length > 0 && contactsState.kind === 'failed' && (
                <p>Не удалось получить текущий список контактов организации.</p>
              )}
              {linkedContacts.length > 0 && contactsState.kind === 'ready' && (
                <ul>
                  {linkedContacts.map(({ id, contact }) => (
                    <li key={id}>
                      {contact === undefined ? (
                        <span>Контакт больше недоступен в текущем списке организации.</span>
                      ) : (
                        <>
                          <strong>{contact.name}</strong>
                          {contact.position && <span>{contact.position}</span>}
                          {contact.email && <span>{contact.email}</span>}
                          {contact.phone && <span>{contact.phone}</span>}
                        </>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section className="interaction-product-agreements" aria-labelledby="interaction-product-agreements-title">
              <h6 id="interaction-product-agreements-title">Продукты и соглашения</h6>
              {currentInteraction.productAgreements.length === 0 && <p>Продукты не указаны.</p>}
              {currentInteraction.productAgreements.length > 0 && (
                <ul>
                  {currentInteraction.productAgreements.map((agreement) => (
                    <li key={agreement.id}>
                      <div className="interaction-product-agreement__header">
                        <strong>{agreement.productName}</strong>
                        {agreement.productArchived && <span className="interaction-archived">Архивирован</span>}
                      </div>
                      <dl>
                        <div>
                          <dt>Номер договора</dt>
                          <dd>{agreement.contractNumber ?? 'Не указан'}</dd>
                        </div>
                        <div>
                          <dt>Подписание лицензии</dt>
                          <dd>{licenseSignedLabel(agreement.licenseSigned)}</dd>
                        </div>
                        <div>
                          <dt>Срок лицензии</dt>
                          <dd>{agreement.licenseExpiryYear ?? 'Не указан'}</dd>
                        </div>
                        <div>
                          <dt>Статус передачи</dt>
                          <dd>{agreement.transferStatus ?? 'Не указан'}</dd>
                        </div>
                      </dl>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <LearningSnapshots
              interactionId={currentInteraction.id}
              version={currentInteraction.version}
              hasProgram={currentInteraction.program !== null}
              canRefresh={canManageDailyWork}
              onRefreshed={() => openInteraction(currentInteraction.id)}
              onSessionExpired={onSessionExpired}
              onProfileUnavailable={onProfileUnavailable}
            />

            {canManageDailyWork && planValues !== undefined && (
              <form className="interaction-plan-form" onSubmit={(event) => void submitPlan(event)}>
                <h6>Следующий шаг</h6>
                <p>Срок и следующий шаг показывают, что должно произойти на текущем этапе. Изменение попадает в историю.</p>
                {planConflictList.length > 0 && (
                  <div className="interaction-catalog-message" role="status">
                    <p>Пока вы редактировали план, в карточке изменились поля, которые вы тоже меняете. При сохранении останутся ваши значения.</p>
                    <ul>
                      {planConflictList.map((conflict) => (
                        <li key={conflict.field}>{conflict.label}: сейчас в карточке {conflict.current}</li>
                      ))}
                    </ul>
                  </div>
                )}
                <label>
                  Следующий шаг
                  <textarea
                    value={planValues.nextAction}
                    maxLength={500}
                    onChange={(event) => changePlan('nextAction', event.target.value)}
                    {...invalidProps(fieldErrorOf(planState, 'nextAction'), 'interaction-plan-next-action-error')}
                  />
                  <FieldError id="interaction-plan-next-action-error" message={fieldErrorOf(planState, 'nextAction')} />
                </label>
                <label>
                  Срок
                  <input
                    type="datetime-local"
                    value={planValues.nextActionAt}
                    onChange={(event) => changePlan('nextActionAt', event.target.value)}
                  />
                </label>
                {catalogState.kind === 'ready' ? (
                  <>
                    <label>
                      Программа
                      <select value={planValues.programId} onChange={(event) => changePlan('programId', event.target.value)}>
                        <option value="">Не указана</option>
                        {planProgramOptions.map((program) => (
                          <option key={program.id} value={program.id}>{program.name}</option>
                        ))}
                      </select>
                    </label>
                    <fieldset className="interaction-catalog-picker">
                      <legend>Продукты</legend>
                      {planProductOptions.length === 0 ? (
                        <p>Доступных продуктов нет.</p>
                      ) : (
                        <ul>
                          {planProductOptions.map((product) => (
                            <li key={product.id}>
                              <label>
                                <input
                                  type="checkbox"
                                  checked={planValues.productIds.includes(product.id)}
                                  disabled={lockedProductIds.has(product.id) && planValues.productIds.includes(product.id)}
                                  onChange={() => togglePlanProduct(product.id)}
                                />
                                <span>{product.name}</span>
                                {lockedProductIds.has(product.id) && <small>Есть данные договора, снять нельзя</small>}
                              </label>
                            </li>
                          ))}
                        </ul>
                      )}
                    </fieldset>
                  </>
                ) : (
                  <p className="interaction-catalog-message">Программу и продукты можно изменить после загрузки справочника.</p>
                )}
                <div className="interaction-plan-form__actions">
                  <button type="submit" disabled={planState.kind === 'saving' || !planHasChanges}>
                    {planState.kind === 'saving' ? 'Сохраняем…' : 'Сохранить план'}
                  </button>
                  {planDraft !== null && (
                    <button type="button" className="interaction-plan-form__reset" disabled={planState.kind === 'saving'} onClick={resetPlan}>
                      Отменить изменения
                    </button>
                  )}
                </div>
                {planState.kind === 'failed' && (
                  <div className="interaction-command-error" role="alert">
                    <p>{commandMessage(planState.error)}</p>
                    <StructuredApiError error={planState.error} />
                    {planState.error instanceof ApiError && <SupportDetails requestId={planState.error.requestId} code={planState.error.code} />}
                    {planState.error instanceof ApiError && planState.error.status === 409 && (
                      <button type="button" onClick={() => openInteraction(currentInteraction.id)}>Обновить карточку</button>
                    )}
                  </div>
                )}
              </form>
            )}

            <div className="interaction-stages">
              <h6 id="interaction-path-title">Карта пути</h6>
              <ol className="interaction-path" aria-labelledby="interaction-path-title">
                {pathItems.map((item, index) => (
                  <li
                    key={item.stage.id}
                    className={`interaction-path__stage interaction-path__stage--${item.status}`}
                    aria-current={item.status === 'current' ? 'step' : undefined}
                  >
                    <span className="interaction-path__number" aria-hidden="true">{index + 1}</span>
                    <span className="interaction-path__name">{item.stage.name}</span>
                    <span className="interaction-path__status">{stagePathLabel(item)}</span>
                    {item.stage.optional && <span className="interaction-path__optional">Необязательный</span>}
                  </li>
                ))}
              </ol>
              <div className="interaction-stage-transitions">
                <p>Разрешённые переходы снимка</p>
                {currentInteraction.transitions.length === 0 ? (
                  <p>В этом снимке переходы не заданы.</p>
                ) : (
                  <ul>
                    {currentInteraction.transitions.map((transition) => (
                      <li key={`${transition.fromStageId}:${transition.toStageId}`}>
                        {attachmentStageNames.get(transition.fromStageId) ?? 'Недоступный этап'} → {attachmentStageNames.get(transition.toStageId) ?? 'Недоступный этап'}
                        {transition.commentRequired && <span>Комментарий обязателен</span>}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>

            {canManageDailyWork && (
              <form className="interaction-stage-editor" onSubmit={(event) => void submitStageEdit(event)}>
                <h6>Изменить локальный граф этапов</h6>
                <p>Одна команда изменяет только снимок этого взаимодействия.</p>
                <label>
                  Операция
                  <select value={stageEditType} onChange={(event) => changeStageEditType(event.target.value as InteractionStageEditType)}>
                    <option value="RENAME">Переименовать этап</option>
                    <option value="ADD_AFTER">Добавить этап после выбранного</option>
                    <option value="MOVE_AFTER">Переместить этап после выбранного</option>
                    <option value="DELETE">Удалить этап</option>
                  </select>
                </label>
                {stageEditNeedsTarget && (
                  <label>
                    Этап
                    <select value={stageEditId} required onChange={(event) => changeStageEditId(event.target.value)}>
                      <option value="">Выберите этап</option>
                      {currentInteraction.stages.map((stage) => (
                        <option key={stage.id} value={stage.id}>{stage.order + 1}. {stage.name}</option>
                      ))}
                    </select>
                  </label>
                )}
                {stageEditNeedsAfter && (
                  <label>
                    После какого этапа
                    <select value={stageEditAfterId} required onChange={(event) => changeStageEditAfterId(event.target.value)}>
                      <option value="">Выберите этап</option>
                      {currentInteraction.stages
                        .filter((stage) => stageEditType !== 'MOVE_AFTER' || stage.id !== stageEditId)
                        .map((stage) => (
                          <option key={stage.id} value={stage.id}>{stage.order + 1}. {stage.name}</option>
                        ))}
                    </select>
                  </label>
                )}
                {stageEditNeedsName && (
                  <label>
                    Название этапа
                    <input
                      value={stageEditName}
                      maxLength={200}
                      required
                      onChange={(event) => changeStageEditName(event.target.value)}
                      {...invalidProps(fieldErrorOf(stageEditState, 'name'), 'interaction-stage-name-error')}
                    />
                    <FieldError id="interaction-stage-name-error" message={fieldErrorOf(stageEditState, 'name')} />
                  </label>
                )}
                {stageEditType === 'ADD_AFTER' && (
                  <label className="interaction-stage-editor__checkbox">
                    <input
                      type="checkbox"
                      checked={stageEditOptional}
                      onChange={(event) => changeStageEditOptional(event.target.checked)}
                    />
                    Необязательный этап
                  </label>
                )}
                <button type="submit" disabled={stageEditState.kind === 'saving' || !stageEditCanSubmit}>
                  {stageEditState.kind === 'saving' ? 'Сохраняем…' : 'Применить одну операцию'}
                </button>
                {stageEditState.kind === 'failed' && (
                  <div className="interaction-command-error" role="alert">
                    <p>{commandMessage(stageEditState.error)}</p>
                    <StructuredApiError error={stageEditState.error} />
                    {stageEditState.error instanceof ApiError && <SupportDetails requestId={stageEditState.error.requestId} code={stageEditState.error.code} />}
                    {stageEditState.error instanceof ApiError && stageEditState.error.status === 409 && (
                      <button type="button" onClick={() => openInteraction(currentInteraction.id)}>Обновить карточку</button>
                    )}
                  </div>
                )}
              </form>
            )}

            <section className="interaction-attachments" aria-labelledby="interaction-attachments-title">
              <h6 id="interaction-attachments-title">Документы</h6>
              {currentInteraction.attachments.length === 0 && (
                <p>Документы к этому взаимодействию пока не загружены.</p>
              )}
              {currentInteraction.attachments.length > 0 && (
                <ul>
                  {currentInteraction.attachments.map((attachment) => {
                    const stageName = attachmentStageNames.get(attachment.stageId) ?? 'Этап недоступен'
                    const refreshing = attachmentRefreshState.kind === 'loading' && attachmentRefreshState.id === attachment.id
                    const downloading = attachmentDownloadState.kind === 'downloading' && attachmentDownloadState.id === attachment.id
                    return (
                      <li key={attachment.id}>
                        <div className="interaction-attachment__header">
                          <strong>{attachment.originalName}</strong>
                          <span className={`interaction-attachment__status interaction-attachment__status--${attachment.status.toLowerCase()}`}>
                            {attachmentStatusLabel[attachment.status]}
                          </span>
                        </div>
                        <dl>
                          <div>
                            <dt>Этап</dt>
                            <dd>{stageName}</dd>
                          </div>
                          <div>
                            <dt>Связь с историей</dt>
                            <dd>{attachment.eventId === null ? 'Ещё не привязан к событию' : 'Связан с событием истории'}</dd>
                          </div>
                          <div>
                            <dt>Тип и размер</dt>
                            <dd>{attachment.mediaType}; {formatFileSize(attachment.sizeBytes)}</dd>
                          </div>
                          <div>
                            <dt>Загружен</dt>
                            <dd>{formatDateTime(attachment.createdAt)}</dd>
                          </div>
                        </dl>
                        {attachment.status === 'CLEAN' && (
                          <button type="button" className="button--secondary" onClick={() => void downloadAttachment(attachment)} disabled={downloading}>
                            {downloading ? 'Готовим скачивание…' : 'Скачать файл'}
                          </button>
                        )}
                        {attachment.status === 'QUARANTINE' && (
                          <button type="button" className="button--secondary" onClick={() => void refreshAttachment(attachment)} disabled={refreshing}>
                            {refreshing ? 'Обновляем статус…' : 'Обновить статус'}
                          </button>
                        )}
                        {attachmentRefreshState.kind === 'failed' && attachmentRefreshState.id === attachment.id && (
                          <div className="interaction-command-error" role="alert">
                            <p>{attachmentMessage(attachmentRefreshState.error, 'refresh')}</p>
                            {attachmentRefreshState.error instanceof ApiError && <SupportDetails requestId={attachmentRefreshState.error.requestId} code={attachmentRefreshState.error.code} />}
                            <button type="button" onClick={() => void refreshAttachment(attachment)}>Повторить</button>
                          </div>
                        )}
                        {attachmentDownloadState.kind === 'failed' && attachmentDownloadState.id === attachment.id && (
                          <div className="interaction-command-error" role="alert">
                            <p>{attachmentMessage(attachmentDownloadState.error, 'download')}</p>
                            {attachmentDownloadState.error instanceof ApiError && <SupportDetails requestId={attachmentDownloadState.error.requestId} code={attachmentDownloadState.error.code} />}
                            <button type="button" onClick={() => void downloadAttachment(attachment)}>Повторить скачивание</button>
                          </div>
                        )}
                      </li>
                    )
                  })}
                </ul>
              )}
              {canManageDailyWork && (
                <form className="interaction-attachment-upload" onSubmit={(event) => void submitUpload(event)}>
                  <h6>Загрузить документ</h6>
                  <label>
                    Этап документа
                    <select value={uploadStageId} required onChange={(event) => changeUploadStage(event.target.value)}>
                      {currentInteraction.stages.map((stage) => (
                        <option key={stage.id} value={stage.id}>
                          {stage.order + 1}. {stage.name}{stage.id === currentInteraction.currentStageId ? ' (текущий)' : ''}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Файл
                    <input
                      ref={uploadInput}
                      type="file"
                      required
                      accept=".png,.jpg,.jpeg,.pdf,.zip,.gz,.gzip,.rar,.doc,.docx,.xls,.xlsx"
                      onChange={(event) => changeUploadFile(event.target.files?.item(0) ?? null)}
                      aria-invalid={uploadFileError === undefined ? undefined : true}
                      aria-describedby={uploadFileError === undefined ? 'interaction-upload-hint' : 'interaction-upload-hint interaction-upload-error'}
                    />
                    <span id="interaction-upload-hint" className="interaction-field-hint">
                      До {attachmentLimitMegabytes} МБ: PNG, JPEG, PDF, ZIP, GZIP, RAR, DOC, DOCX, XLS или XLSX.
                    </span>
                    <FieldError id="interaction-upload-error" message={uploadFileError} />
                  </label>
                  <button type="submit" disabled={uploadState.kind === 'saving' || uploadFile === null || uploadTooLarge || uploadStageId.length === 0}>
                    {uploadState.kind === 'saving' ? 'Загружаем…' : 'Загрузить в карантин'}
                  </button>
                  {uploadState.kind === 'failed' && (
                    <div className="interaction-command-error" role="alert">
                      <p>{attachmentMessage(uploadState.error, 'upload')}</p>
                      {uploadState.error instanceof ApiError && <p className="error-code">Код: {uploadState.error.code}</p>}
                      {uploadState.error instanceof ApiError && <SupportDetails requestId={uploadState.error.requestId} code={uploadState.error.code} />}
                      {uploadState.error instanceof ApiError && uploadState.error.status === 409 && (
                        <button type="button" onClick={() => openInteraction(currentInteraction.id)}>Обновить карточку</button>
                      )}
                    </div>
                  )}
                </form>
              )}
            </section>

            {canManageDailyWork && <div className="interaction-commands">
              <form onSubmit={(event) => void submitComment(event)}>
                <h6>Комментарий</h6>
                <label>
                  Этап комментария
                  <select value={commentStageId} required onChange={(event) => changeCommentStage(event.target.value)}>
                    <option value="">Выберите этап</option>
                    {currentInteraction.stages.map((stage) => (
                      <option key={stage.id} value={stage.id}>
                        {stage.order + 1}. {stage.name}{stage.id === currentInteraction.currentStageId ? ' (текущий)' : ''}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Текст комментария
                  <textarea
                    value={commentDraft}
                    maxLength={4000}
                    required
                    onChange={(event) => changeCommentDraft(event.target.value)}
                    placeholder="Зафиксируйте результат контакта или договорённость"
                    {...invalidProps(fieldErrorOf(commentState, 'text'), 'interaction-comment-text-error')}
                  />
                  <FieldError id="interaction-comment-text-error" message={fieldErrorOf(commentState, 'text')} />
                </label>
                <NextStepFields
                  nextAction={commentNextAction}
                  nextActionAt={commentNextActionAt}
                  onNextActionChange={changeCommentNextAction}
                  onNextActionAtChange={changeCommentNextActionAt}
                />
                <fieldset className="interaction-attachment-picker">
                  <legend>Очищенные документы к комментарию</legend>
                  {commentStageId.length === 0 && <p>Сначала выберите этап комментария.</p>}
                  {commentStageId.length > 0 && commentAttachments.length === 0 && (
                    <p>Для выбранного этапа нет очищенных и ещё не связанных с событием документов.</p>
                  )}
                  {commentAttachments.length > 0 && (
                    <ul>
                      {commentAttachments.map((attachment) => (
                        <li key={attachment.id}>
                          <label>
                            <input
                              type="checkbox"
                              checked={commentAttachmentIds.includes(attachment.id)}
                              onChange={() => toggleCommentAttachment(attachment.id)}
                            />
                            <span>{attachment.originalName}</span>
                            <small>{formatFileSize(attachment.sizeBytes)}</small>
                          </label>
                        </li>
                      ))}
                    </ul>
                  )}
                </fieldset>
                <button type="submit" disabled={commentState.kind === 'saving' || commentStageId.length === 0 || commentDraft.trim().length === 0}>
                  {commentState.kind === 'saving' ? 'Сохраняем…' : 'Добавить комментарий'}
                </button>
                {commentState.kind === 'failed' && (
                  <div className="interaction-command-error" role="alert">
                    <p>{commandMessage(commentState.error)}</p>
                    <StructuredApiError error={commentState.error} />
                    {commentState.error instanceof ApiError && <SupportDetails requestId={commentState.error.requestId} code={commentState.error.code} />}
                    {commentState.error instanceof ApiError && commentState.error.status === 409 && (
                      <button type="button" onClick={() => openInteraction(currentInteraction.id)}>Обновить карточку</button>
                    )}
                  </div>
                )}
              </form>

              <form onSubmit={(event) => void submitTransition(event)}>
                <h6>Переход этапа</h6>
                <label>
                  Новый этап
                  <select value={transitionStageId} required onChange={(event) => changeTransitionStage(event.target.value)}>
                    <option value="">Выберите этап</option>
                    {currentInteraction.allowedTransitions.map((option) => (
                      <option key={option.stageId} value={option.stageId}>
                        {option.stageName}{option.commentRequired ? ' (нужен комментарий)' : ''}
                      </option>
                    ))}
                  </select>
                </label>
                {currentInteraction.allowedTransitions.length === 0 && (
                  <p className="interaction-stage-choice">Сервер не разрешил переходы из текущего этапа.</p>
                )}
                {selectedTransition !== undefined && (
                  <p className="interaction-stage-choice">
                    Выбран этап: {selectedTransition.stageName}{selectedTransition.commentRequired ? '; комментарий обязателен' : ''}
                  </p>
                )}
                <label>
                  Комментарий к переходу
                  <textarea
                    value={transitionDraft}
                    maxLength={4000}
                    required={selectedTransition?.commentRequired ?? false}
                    onChange={(event) => changeTransitionDraft(event.target.value)}
                    placeholder="Укажите причину или итог перехода, если это необходимо"
                    {...invalidProps(fieldErrorOf(transitionState, 'comment'), 'interaction-transition-comment-error')}
                  />
                  <FieldError id="interaction-transition-comment-error" message={fieldErrorOf(transitionState, 'comment')} />
                </label>
                <NextStepFields
                  nextAction={transitionNextAction}
                  nextActionAt={transitionNextActionAt}
                  onNextActionChange={changeTransitionNextAction}
                  onNextActionAtChange={changeTransitionNextActionAt}
                />
                <fieldset className="interaction-attachment-picker">
                  <legend>Очищенные документы к переходу</legend>
                  {transitionStageId.length === 0 && <p>Сначала выберите целевой этап.</p>}
                  {transitionStageId.length > 0 && transitionAttachments.length === 0 && (
                    <p>Для целевого этапа нет очищенных и ещё не связанных с событием документов.</p>
                  )}
                  {transitionAttachments.length > 0 && (
                    <ul>
                      {transitionAttachments.map((attachment) => (
                        <li key={attachment.id}>
                          <label>
                            <input
                              type="checkbox"
                              checked={transitionAttachmentIds.includes(attachment.id)}
                              onChange={() => toggleTransitionAttachment(attachment.id)}
                            />
                            <span>{attachment.originalName}</span>
                            <small>{formatFileSize(attachment.sizeBytes)}</small>
                          </label>
                        </li>
                      ))}
                    </ul>
                  )}
                </fieldset>
                <button type="submit" disabled={transitionState.kind === 'saving' || transitionStageId.length === 0}>
                  {transitionState.kind === 'saving' ? 'Сохраняем…' : 'Перейти на этап'}
                </button>
                {transitionState.kind === 'failed' && (
                  <div className="interaction-command-error" role="alert">
                    <p>{commandMessage(transitionState.error)}</p>
                    <StructuredApiError error={transitionState.error} />
                    {transitionState.error instanceof ApiError && <SupportDetails requestId={transitionState.error.requestId} code={transitionState.error.code} />}
                    {transitionState.error instanceof ApiError && transitionState.error.status === 409 && (
                      <button type="button" onClick={() => openInteraction(currentInteraction.id)}>Обновить карточку</button>
                    )}
                  </div>
                )}
              </form>
            </div>}

            <section className="interaction-events" aria-labelledby="interaction-events-title">
              <div className="interaction-events__header">
                <h6 id="interaction-events-title">История</h6>
                <button type="button" className="button--secondary" onClick={() => void loadEvents(currentInteraction.id)}>Обновить историю</button>
              </div>
              {eventsState.kind === 'loading' && eventsState.id === currentInteraction.id && (
                <p className="organizations-message" role="status">Загружаем историю…</p>
              )}
              {eventsState.kind === 'failed' && eventsState.id === currentInteraction.id && (
                <div className="organizations-message organizations-message--error" role="alert">
                  <p>Не удалось загрузить историю.</p>
                  <SupportDetails requestId={eventsState.requestId} />
                  <button type="button" onClick={() => void loadEvents(currentInteraction.id)}>Повторить</button>
                </div>
              )}
              {currentEvents !== undefined && (
                currentEvents.length === 0 ? (
                  <p className="organizations-message">История пока пуста.</p>
                ) : (
                  <ol className="interaction-events__list">
                    {currentEvents.map((item) => {
                      const stageDescription = eventStageDescription(item)
                      const eventAttachments = attachmentsByEvent.get(item.id) ?? []
                      return (
                        <li key={item.id}>
                          <div>
                            <strong>{eventLabel[item.type]}</strong>
                            <time dateTime={item.occurredAt}>{formatDateTime(item.occurredAt)}</time>
                          </div>
                          <p>{stageDescription}</p>
                          {item.comment && <p className="interaction-events__comment">{item.comment}</p>}
                          {item.nextStep && (
                            <p className="interaction-events__next-step">
                              Следующий шаг: {item.nextStep.nextAction ?? 'не задан'}; срок: {item.nextStep.nextActionAt === null ? 'не задан' : formatDateTime(item.nextStep.nextActionAt)}
                            </p>
                          )}
                          {eventAttachments.length > 0 && (
                            <p className="interaction-events__attachments">
                              Документы: {eventAttachments.map((attachment) => attachment.originalName).join(', ')}
                            </p>
                          )}
                          <p className="interaction-events__meta">Автор: {item.actorDisplayName}</p>
                        </li>
                      )
                    })}
                  </ol>
                )
              )}
            </section>
          </>
        )}
      </section>
    </section>
  )
}
