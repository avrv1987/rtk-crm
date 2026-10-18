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
  clearInteractionDraft,
  isEmptyPlanDraft,
  type InteractionDraft,
  type InteractionPlanDraft,
  type InteractionStageEditType
} from './drafts'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { Segmented } from '../../shared/ui/Segmented'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { Toast } from '../../shared/ui/Toast'
import { InteractionDetailsForm } from './InteractionDetailsForm'
import { InteractionIssuesPanel } from '../issues/InteractionIssuesPanel'
import { InteractionFlagsForm } from './InteractionFlagsForm'
import { InteractionMarkBadges } from './InteractionMarkBadges'
import { InteractionStatusPanel } from './InteractionStatusPanel'
import { StepCompletion } from '../work/StepCompletion'
import { LearningSnapshots } from './LearningSnapshots'
import { LmsSignalsCard } from '../work/LmsSignals'
import { OrganizationContacts, type PartnerAccessRights } from './OrganizationContacts'
import { PartnerStepToggle } from '../partner/PartnerStepToggle'
import { getModules } from '../../shared/modules'
import { CameraInput } from '../../shared/ui/CameraInput'
import { ContactLink } from '../../shared/ui/ContactLink'
import { emailHref, phoneHref } from '../../shared/format/links'
import { forgetUnsavedDraft, useUnsavedDraft } from './unsavedDrafts'
import { contactRoleLabels, contactRoles, workStatusLabels } from './workMarks'
import { AttachmentExtras } from '../documents/AttachmentExtras'
import { ProductAgreementsSection } from '../documents/ProductAgreementsSection'
import {
  attachmentAccept,
  attachmentFormats,
  attachmentKindLabels,
  attachmentKinds,
  type AttachmentKind
} from '../documents/documentsApi'
import { InteractionCycles } from '../training/InteractionCycles'
import { LmsStageSuggestionPanel } from '../training/LmsStageSuggestion'
import { TeacherTrainings } from '../training/TeacherTrainings'
import { TeacherRoster } from '../training/TeacherRoster'
import { StageCompletionPanel } from './StageCompletionPanel'
import { WorkAgreementBlock } from '../agreements/WorkAgreementBlock'
import { agreementsApi, type WorkAgreements } from '../agreements/agreementsApi'
import { activeStageRole } from '../agreements/agreementModel'
import { CardDialog, CardSection, CardTabPanel, CardTabs, RequiredMark, type CardTab } from './cardUi'
import { RouteDisclosure, daysLabel, daysSince, stagePath } from './RouteDisclosure'
import { WorkFacts } from './WorkFacts'
import { WorkHistoryTab } from './WorkHistoryTab'
import { agreementFact, isSignedAgreement, primaryAction, primaryShortLabels, signingBlock } from './workCardModel'
import { formatMoscowDateTime, isoFromMoscowInput, moscowInputValue } from './moscowTime'
import './workCard.css'

export type InteractionsPanelView = 'works' | 'contacts' | 'card'

type InteractionsPanelProps = {
  view: InteractionsPanelView
  organizationId: Organization['id']
  initialInteractionId?: Interaction['id']
  openIssues?: boolean
  organization?: Organization
  profileId: string
  role: Me['role']
  partnerAccess?: PartnerAccessRights
  onContactsChanged?: () => void
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
  | { kind: 'failed'; id: Interaction['id']; requestId?: string; code?: string }

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

const formatDateTime = formatMoscowDateTime

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
  file: 'Файл',
  agreement: 'Договор'
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

const toIsoDateTime = isoFromMoscowInput

const toDateTimeLocal = moscowInputValue

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

const attachableIdsOf = (interaction: Interaction) => new Set(
  interaction.attachments
    .filter((attachment) => attachment.status === 'CLEAN' && attachment.eventId === null)
    .map((attachment) => attachment.id)
)

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

type PlanPart = 'step' | 'program'

const planPartFields: Record<PlanPart, PlanField[]> = {
  step: ['nextAction', 'nextActionAt'],
  program: ['programId']
}

// Окна «Изменить шаг и срок» и «Программа и продукты» делят один черновик плана (и прежний ключ хранения),
// но отправляют только свои поля: несохранённая правка программы не должна уйти вместе со сроком.
const planChanges = (interaction: Interaction, plan: InteractionPlanDraft | null, part: PlanPart): InteractionPlanUpdate => {
  const changes: InteractionPlanUpdate = { version: interaction.version }
  const current = planFromInteraction(interaction)
  const values = planValuesOf(interaction, plan)
  if (part === 'step' && values.nextAction.trim() !== current.nextAction) {
    changes.nextAction = values.nextAction.trim() || null
  }
  if (part === 'step' && values.nextActionAt !== current.nextActionAt) {
    changes.nextActionAt = toIsoDateTime(values.nextActionAt)
  }
  if (part === 'program' && values.programId !== current.programId) {
    changes.programId = values.programId || null
  }
  if (
    part === 'program' && (values.productIds.length !== interaction.productIds.length
    || values.productIds.some((id) => !interaction.productIds.includes(id)))
  ) {
    changes.productIds = values.productIds
  }
  return changes
}

const withoutPlanPart = (plan: InteractionPlanDraft | null, part: PlanPart): InteractionPlanDraft | null => {
  if (plan === null) {
    return null
  }
  const next: InteractionPlanDraft = part === 'step'
    ? { ...plan, nextAction: undefined, nextActionAt: undefined }
    : { ...plan, programId: undefined, addedProductIds: [], removedProductIds: [] }
  return isEmptyPlanDraft(next) ? null : next
}

const planConflicts = (interaction: Interaction, plan: InteractionPlanDraft | null, part: PlanPart) => {
  const current = planFromInteraction(interaction)
  const shown: Record<PlanField, string> = {
    nextAction: interaction.nextAction ?? 'не задан',
    nextActionAt: interaction.nextActionAt === null ? 'не задан' : formatDateTime(interaction.nextActionAt),
    programId: interaction.program?.name ?? 'не указана'
  }
  return planPartFields[part]
    .filter((field) => {
      const draft = plan?.[field]
      return draft !== undefined && draft.base !== current[field] && draft.value !== current[field]
    })
    .map((field) => ({ field, label: planFieldLabels[field], current: shown[field] }))
}

const offered = (contact: Contact) => !contact.inactive && (contact.personalDataStatus ?? 'ACTIVE') === 'ACTIVE'

type NextStepFieldsProps = {
  nextAction: string
  nextActionAt: string
  onNextActionChange: (value: string) => void
  onNextActionAtChange: (value: string) => void
}

const NextStepFields = ({ nextAction, nextActionAt, onNextActionChange, onNextActionAtChange }: NextStepFieldsProps) => {
  const [startOpen] = useState(nextAction.length > 0 || nextActionAt.length > 0)
  return (
  <details className="interaction-next-step-fields" open={startOpen}>
    <summary>Заодно изменить следующий шаг</summary>
    <label>
      Следующий шаг
      <textarea
        value={nextAction}
        maxLength={500}
        onChange={(event) => onNextActionChange(event.target.value)}
      />
    </label>
    <label>
      Срок (МСК)
      <input
        type="datetime-local"
        value={nextActionAt}
        onChange={(event) => onNextActionAtChange(event.target.value)}
      />
    </label>
    <span className="interaction-field-hint">Пустое поле оставляет текущее значение.</span>
  </details>
  )
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

type CardDialogKind = 'contact' | 'create' | 'transition' | 'step' | 'program' | 'comment' | 'upload' | 'status' | 'flags' | 'issues' | 'stageEdit' | 'stageCompletion' | 'details'

type CardTabId = 'history' | 'documents' | 'contract' | 'learning' | 'contacts'

type WorksFilter = 'ACTIVE' | 'ALL'

type WorkAgreementsState =
  | { kind: 'loading' }
  | { kind: 'ready'; value: WorkAgreements }
  | { kind: 'failed' }

let carriedNotice: { interactionId: Interaction['id']; text: string } | null = null

const takeCarriedNotice = (interactionId: Interaction['id'] | undefined) => {
  const notice = carriedNotice !== null && carriedNotice.interactionId === interactionId ? carriedNotice.text : null
  carriedNotice = null
  return notice
}

const fileTypeLabel = (attachment: Attachment) => {
  const extension = attachment.originalName.includes('.') ? attachment.originalName.split('.').pop()?.toUpperCase() : undefined
  return extension ?? attachment.mediaType
}

const nextAllowedTransition = (interaction: Interaction) => {
  const orderOf = new Map(interaction.stages.map((stage) => [stage.id, stage.order]))
  const currentOrder = orderOf.get(interaction.currentStageId) ?? -1
  const ahead = interaction.allowedTransitions
    .filter((option) => (orderOf.get(option.stageId) ?? -1) > currentOrder)
    .sort((left, right) => (orderOf.get(left.stageId) ?? 0) - (orderOf.get(right.stageId) ?? 0))
  return ahead[0] ?? interaction.allowedTransitions[0]
}

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
  view,
  organizationId,
  initialInteractionId,
  openIssues = false,
  organization,
  profileId,
  role,
  partnerAccess,
  onContactsChanged,
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
  const [uploadKind, setUploadKind] = useState<AttachmentKind>('OTHER')
  const [attachmentKindFilter, setAttachmentKindFilter] = useState<AttachmentKind | ''>('')
  const [uploadState, setUploadState] = useState<CommandState>({ kind: 'idle' })
  const [attachmentRefreshState, setAttachmentRefreshState] = useState<AttachmentRefreshState>({ kind: 'idle' })
  const [attachmentDownloadState, setAttachmentDownloadState] = useState<AttachmentDownloadState>({ kind: 'idle' })
  const [selectedContactIds, setSelectedContactIds] = useState<Contact['id'][]>(restoredDraft.current.selectedContactIds)
  const [contactName, setContactName] = useState(restoredDraft.current.contactName)
  const [contactPosition, setContactPosition] = useState(restoredDraft.current.contactPosition)
  const [contactEmail, setContactEmail] = useState(restoredDraft.current.contactEmail)
  const [contactPhone, setContactPhone] = useState(restoredDraft.current.contactPhone)
  const [contactRole, setContactRole] = useState(restoredDraft.current.contactRole)
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
  const [dialog, setDialog] = useState<CardDialogKind | null>(null)
  const [cardTab, setCardTab] = useState<CardTabId>('history')
  const [notice, setNotice] = useState<string | null>(() => takeCarriedNotice(initialInteractionId))
  const [worksFilter, setWorksFilter] = useState<WorksFilter>('ACTIVE')
  const [completingStep, setCompletingStep] = useState(false)
  const [workAgreements, setWorkAgreements] = useState<WorkAgreementsState>({ kind: 'loading' })
  const [agreementCreateSignal, setAgreementCreateSignal] = useState(0)
  const [signalCount, setSignalCount] = useState(0)
  const [cyclesFocus, setCyclesFocus] = useState(0)
  const [confirmStageDelete, setConfirmStageDelete] = useState(false)
  const [agreementsRevision, setAgreementsRevision] = useState(0)
  const contactFromDetails = useRef(false)
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
        sort: interactionSort,
        status: worksFilter
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
  }, [onProfileUnavailable, onSessionExpired, organizationId, worksFilter])

  const loadContacts = useCallback(async () => {
    const requestVersion = ++contactsRequestVersion.current
    setContactsState({ kind: 'loading' })
    try {
      const contacts = await apiClient.listOrganizationContacts(organizationId)
      if (requestVersion === contactsRequestVersion.current) {
        setContactsState({ kind: 'ready', contacts })
        setSelectedContactIds((ids) => ids.filter((id) => contacts.some((contact) => contact.id === id && offered(contact))))
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
        const attachableIds = attachableIdsOf(interaction)
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
      setDetailState({ kind: 'failed', id, requestId: requestIdOf(error), code: error instanceof ApiError ? error.code : undefined })
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
      setDialog(null)
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
    if (view === 'works') {
      void loadInteractions(1)
    }
    void loadContacts()
    return () => {
      listRequestVersion.current += 1
      detailRequestVersion.current += 1
      eventsRequestVersion.current += 1
      contactsRequestVersion.current += 1
    }
  }, [loadContacts, loadInteractions, view])

  useEffect(() => {
    if (role === 'ADMIN' || role === 'MANAGEMENT') {
      return
    }
    void loadCatalog()
    if (view === 'works') {
      setTemplatesPageIndex(0)
      void loadAvailableTemplates(0)
    }
    return () => {
      catalogRequestVersion.current += 1
      templatesRequestVersion.current += 1
    }
  }, [loadAvailableTemplates, loadCatalog, role, view])

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
    if (view !== 'card' || id === null) {
      return
    }
    void loadInteraction(id)
    void loadEvents(id)
  }, [loadEvents, loadInteraction, view])

  useEffect(() => {
    if (view === 'card' && initialInteractionId !== undefined && initialInteractionId !== openedInteractionId.current) {
      setNotice(takeCarriedNotice(initialInteractionId))
      openInteraction(initialInteractionId)
    }
  }, [initialInteractionId, openInteraction, view])

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
      contactRole,
      activeInteractionId
    })
  }, [
    activeInteractionId,
    contactEmail,
    contactName,
    contactPhone,
    contactPosition,
    contactRole,
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
  const currentStageId = currentInteraction?.currentStageId
  const currentVersion = currentInteraction?.version

  useEffect(() => {
    if (currentStageId !== undefined) {
      setUploadStageId(currentStageId)
    }
  }, [currentInteractionId, currentStageId, currentVersion])
  const cardLabel = currentInteraction === undefined ? 'карточке взаимодействия' : `карточке «${currentInteraction.title}»`
  const cardDraftKey = `card:${activeInteractionId ?? ''}`
  useUnsavedDraft(`${cardDraftKey}:comment`, `комментарий в ${cardLabel}`,
    activeInteractionId !== null && (commentDraft.trim().length > 0 || commentNextAction.trim().length > 0))
  useUnsavedDraft(`${cardDraftKey}:transition`, `переход этапа в ${cardLabel}`,
    activeInteractionId !== null && (transitionDraft.trim().length > 0 || transitionNextAction.trim().length > 0))
  useUnsavedDraft(`${cardDraftKey}:plan`, `следующий шаг в ${cardLabel}`, activeInteractionId !== null && planDraft !== null)
  useUnsavedDraft(`${cardDraftKey}:stages`, `правка этапов в ${cardLabel}`,
    activeInteractionId !== null && stageEditName.trim().length > 0)
  useUnsavedDraft(`organization:${organizationId}:create`, 'новое взаимодействие',
    createTitle.trim().length > 0 || createNextAction.trim().length > 0)
  useUnsavedDraft(`organization:${organizationId}:contact`, 'новый контакт', contactName.trim().length > 0)

  useEffect(() => {
    if (focusInitialInteraction.current && currentInteractionId !== undefined && currentInteractionId === initialInteractionId) {
      focusInitialInteraction.current = false
      detailHeading.current?.focus()
    }
  }, [currentInteractionId, initialInteractionId])

  const loadedPages = listState.kind === 'ready' ? listState.pages : 1
  const refreshList = () => {
    if (view === 'works') {
      void loadInteractions(loadedPages)
    }
  }
  const finish = (text: string, tab?: CardTabId) => {
    setDialog(null)
    setNotice(text)
    if (tab !== undefined) {
      setCardTab(tab)
    }
  }
  const interactionHref = (id: Interaction['id']) => {
    const query = window.location.hash.split('?')[1]
    return `#/organizations/${organizationId}/${id}${query ? `?${query}` : ''}`
  }
  const goToInteraction = (id: Interaction['id']) => {
    window.location.hash = interactionHref(id)
  }
  const planValues = currentInteraction === undefined ? undefined : planValuesOf(currentInteraction, planDraft)
  const planPart: PlanPart = dialog === 'program' ? 'program' : 'step'
  const planPayload = currentInteraction === undefined ? undefined : planChanges(currentInteraction, planDraft, planPart)
  const planConflictList = currentInteraction === undefined ? [] : planConflicts(currentInteraction, planDraft, planPart)
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

  const issuesOpenedFor = useRef<string | null>(null)
  useEffect(() => {
    if (openIssues && selectedInteractionId !== undefined && selectedInteractionId === initialInteractionId
      && issuesOpenedFor.current !== selectedInteractionId) {
      issuesOpenedFor.current = selectedInteractionId
      setDialog('issues')
    }
  }, [initialInteractionId, openIssues, selectedInteractionId])
    ?? (detailState.kind === 'loading' || detailState.kind === 'failed' ? detailState.id : undefined)

  useEffect(() => {
    if (view !== 'card' || currentInteractionId === undefined) {
      return
    }
    let active = true
    agreementsApi.workAgreements(currentInteractionId)
      .then((value) => {
        if (active) {
          setWorkAgreements({ kind: 'ready', value })
        }
      })
      .catch((error: unknown) => {
        if (!active) {
          return
        }
        if (isUnauthenticated(error)) {
          onSessionExpired()
        } else if (isProfileUnavailable(error)) {
          onProfileUnavailable(error.requestId)
        } else {
          setWorkAgreements({ kind: 'failed' })
        }
      })
    return () => {
      active = false
    }
  }, [agreementsRevision, currentInteractionId, currentVersion, onProfileUnavailable, onSessionExpired, view])

  useEffect(() => {
    if (view !== 'card' || currentInteractionId === undefined) {
      return
    }
    let active = true
    apiClient.listInteractionLmsSignals(currentInteractionId)
      .then((result) => {
        if (active) {
          setSignalCount(result.items.filter((signal) => !signal.dismissed).length)
        }
      })
      .catch(() => {
        if (active) {
          setSignalCount(0)
        }
      })
    return () => {
      active = false
    }
  }, [currentInteractionId, currentVersion, view])

  useEffect(() => {
    if (cyclesFocus > 0 && cardTab === 'learning') {
      window.requestAnimationFrame(() => document.getElementById('work-cycles')?.scrollIntoView({ block: 'start' }))
    }
  }, [cardTab, cyclesFocus])

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
      setDialog(null)
      clearInteractionDraft(profileId, organizationId)
      forgetUnsavedDraft(`organization:${organizationId}:create`)
      carriedNotice = { interactionId: interaction.id, text: `Взаимодействие «${interaction.title}» создано. Этап: ${interaction.currentStageName}.` }
      goToInteraction(interaction.id)
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
      phone: contactPhone.trim() || null,
      role: contactRoles.find((role) => role === contactRole) ?? null
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
      setContactRole('')
      setContactCreateState({ kind: 'idle' })
      setSelectedContactIds((ids) => ids.includes(contact.id) ? ids : [...ids, contact.id])
      setDialog((current) => {
        if (current !== 'contact') {
          return null
        }
        return view === 'works' ? 'create' : contactFromDetails.current ? 'details' : null
      })
      contactFromDetails.current = false
      setNotice(`Контакт «${contact.name}» добавлен${view === 'works' ? ' и выбран для нового взаимодействия' : ''}.`)
      void loadContacts()
      onContactsChanged?.()
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

  const applyUpdatedInteraction = (interaction: Interaction, text?: string) => {
    if (text !== undefined) {
      finish(text)
    }
    setDetailState({ kind: 'ready', interaction })
    void loadEvents(interaction.id)
    refreshList()
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

  const acceptInteraction = (interaction: Interaction) => {
    setDetailState({ kind: 'ready', interaction })
    void loadEvents(interaction.id)
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
        { file: uploadFile, stageId: uploadStageId, kind: uploadKind },
        uploadKey.current ?? (uploadKey.current = createIdempotencyKey())
      )
      uploadKey.current = null
      replaceAttachment(attachment)
      setUploadFile(null)
      if (uploadInput.current !== null) {
        uploadInput.current.value = ''
      }
      setUploadState({ kind: 'idle' })
      finish(`Файл «${attachment.originalName}» загружен в этап «${currentInteraction.stages.find((stage) => stage.id === attachment.stageId)?.name ?? 'этап недоступен'}». Идёт проверка антивирусом.`, 'documents')
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
      finish(`Комментарий добавлен в историю этапа «${interaction.stages.find((stage) => stage.id === payload.stageId)?.name ?? interaction.currentStageName}».`, 'history')
      void loadEvents(interaction.id)
      refreshList()
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
      finish(`Этап изменён: ${interaction.currentStageName}.`, 'history')
      void loadEvents(interaction.id)
      refreshList()
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

  const submitStageEdit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (stageEditType === 'DELETE') {
      setConfirmStageDelete(true)
      return
    }
    void runStageEdit()
  }

  const runStageEdit = async () => {
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
      finish('Этапы этого взаимодействия изменены. Изменение записано в историю.')
      void loadEvents(interaction.id)
      refreshList()
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
    const part = planPart
    const payload = planChanges(currentInteraction, planDraft, part)
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
      setPlanDraft((draft) => withoutPlanPart(draft, part))
      setPlanState({ kind: 'idle' })
      finish(part === 'step'
        ? `Следующий шаг сохранён: ${interaction.nextAction ?? 'не задан'}.`
        : `Программа и продукты сохранены: ${interaction.program?.name ?? 'программа не указана'}.`)
      void loadEvents(interaction.id)
      refreshList()
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
    setPlanDraft((draft) => withoutPlanPart(draft, planPart))
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
      || agreement.transferStatus !== null
      || agreement.scanAttachmentId !== null
      || agreement.transfers.length > 0)
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

  const openTransition = () => {
    if (currentInteraction !== undefined && transitionStageId.length === 0) {
      const next = nextAllowedTransition(currentInteraction)
      if (next !== undefined) {
        changeTransitionStage(next.stageId)
      }
    }
    setDialog('transition')
  }

  const closeDialog = () => setDialog(null)

  const noticeSlot = <Toast message={notice} onClose={() => setNotice(null)} />

  const readOnlyNote = !canManageDailyWork && (
    <p className="interactions-read-only">
      Только просмотр
      <HintButton label="Что доступно в режиме просмотра">
        Доступны контакты, взаимодействия и история. Добавлять контакты, создавать взаимодействия, комментарии и переходы может ответственный КАМ.
      </HintButton>
    </p>
  )

  const contactForm = (
    <form className="interaction-contact-form" onSubmit={(event) => void submitContact(event)}>
      <label>
        <span>ФИО<RequiredMark /></span>
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
      <label>
        Роль во взаимодействии
        <select value={contactRole} onChange={(event) => setContactRole(event.target.value)}>
          <option value="">Не указана</option>
          {contactRoles.map((role) => <option key={role} value={role}>{contactRoleLabels[role]}</option>)}
        </select>
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
  )

  if (view === 'contacts') {
    return (
      <section className="interaction-contacts" aria-labelledby="interaction-contacts-title">
        <div className="interactions-header">
          <h4 id="interaction-contacts-title">Контакты вуза</h4>
          {canManageDailyWork && (
            <button type="button" onClick={() => setDialog('contact')}>Добавить контакт</button>
          )}
        </div>
        {noticeSlot}
        {contactsState.kind === 'loading' && (
          <p className="organizations-message" role="status">Загружаем контакты…</p>
        )}
        {contactsState.kind === 'failed' && (
          <div className="organizations-message organizations-message--error" role="alert">
            <p>Не удалось загрузить контакты вуза.</p>
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
        {contactsState.kind === 'ready' && (
          <OrganizationContacts
            organizationId={organizationId}
            contacts={contactsState.contacts}
            profileId={profileId}
            canEdit={canManageDailyWork}
            partnerAccess={partnerAccess}
            onChanged={() => {
              void loadContacts()
              onContactsChanged?.()
            }}
            onSessionExpired={onSessionExpired}
            onProfileUnavailable={onProfileUnavailable}
          />
        )}
        {canManageDailyWork && (
          <CardDialog open={dialog === 'contact'} title="Новый контакт" onClose={closeDialog}>
            {contactForm}
          </CardDialog>
        )}
      </section>
    )
  }

  if (view === 'works') {
    return (
      <section className="interactions-panel interactions-panel--works" aria-labelledby="interactions-title">
        <h4 id="interactions-title" className="visually-hidden">Взаимодействия</h4>
        <div className="interactions-header">
          <Segmented
            label="Какие взаимодействия показать"
            options={[{ value: 'ACTIVE', label: 'Активные' }, { value: 'ALL', label: 'Все' }]}
            value={worksFilter}
            onChange={setWorksFilter}
          />
          {listState.kind === 'ready' && <p className="interactions-total">Всего: {listState.total}</p>}
          {canManageDailyWork && (
            <button type="button" className="interactions-header__create" onClick={() => setDialog('create')}>Новое взаимодействие</button>
          )}
        </div>
        {readOnlyNote}
        {noticeSlot}
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
            <p className="organizations-message">
              {worksFilter === 'ACTIVE' ? 'Активных взаимодействий нет.' : 'У этого вуза пока нет взаимодействий.'}
            </p>
          ) : (
            <ul className="interactions-list" aria-label="Взаимодействия вуза" aria-busy={listState.refreshing}>
              {listState.items.map((interaction) => {
                const itemSchedule = nextActionSchedule(interaction)
                const days = daysSince(interaction.stageEnteredAt)
                return (
                  <li key={interaction.id}>
                    <a className="interaction-list-item" href={interactionHref(interaction.id)}>
                      <span className="interaction-list-item__head">
                        <span className="interaction-list-item__title">{interaction.title}</span>
                        <InteractionMarkBadges marks={interaction.marks} />
                      </span>
                      <span className="interaction-list-item__stage">
                        Этап: {interaction.currentStageName}
                        {!Number.isNaN(days) && <span className="interaction-list-item__days"> · {daysLabel(days)} на этапе</span>}
                      </span>
                      <span className="interaction-list-item__action">{interaction.nextAction ?? 'Следующий шаг не задан'}</span>
                      {interaction.marks.status === 'ACTIVE' && (
                        <span className={`interaction-list-item__due ${itemSchedule.className}`}>{itemSchedule.label}</span>
                      )}
                      <span className="interaction-list-item__catalog">
                        Программа: {interaction.programName ?? 'не указана'} · Продукты: {interaction.productIds.length === 0 ? 'не указаны' : interaction.productIds.length} · Последний контакт: {lastContactLabel(interaction.lastContactAt)}
                      </span>
                    </a>
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
        {canManageDailyWork && (
          <>
            <CardDialog open={dialog === 'create'} title="Новое взаимодействие" onClose={closeDialog}>
              <form className="interaction-create-form" onSubmit={(event) => void submitCreate(event)}>
                <label>
                  <span>Название<RequiredMark /></span>
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
                    <option value="">
                      {templatesState.kind === 'ready' && templatesState.page.teamDefaultTemplateId !== null
                        ? `Шаблон команды по умолчанию: ${templatesState.page.items.find((template) => (
                          template.id === templatesState.page.teamDefaultTemplateId
                        ))?.name ?? 'выбран руководителем'}`
                        : 'Шаблон по умолчанию'}
                    </option>
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
                  {contactsState.kind === 'failed' && (
                    <p>
                      Контакты пока недоступны.{' '}
                      <button type="button" className="button--secondary" onClick={() => void loadContacts()}>Повторить</button>
                    </p>
                  )}
                  {contactsState.kind === 'ready' && (
                    !contactsState.contacts.some(offered) ? (
                      <p>Сначала добавьте действующий контакт вуза.</p>
                    ) : (
                      <ul>
                        {contactsState.contacts.filter(offered).map((contact) => (
                          <li key={contact.id}>
                            <label>
                              <input
                                type="checkbox"
                                checked={selectedContactIds.includes(contact.id)}
                                onChange={() => toggleContact(contact.id)}
                              />
                              <span>{contact.name}</span>
                              {contact.position && <small>{contact.position}</small>}
                              {contact.role && <small>{contactRoleLabels[contact.role]}</small>}
                            </label>
                          </li>
                        ))}
                      </ul>
                    )
                  )}
                  <button type="button" className="button--secondary interaction-contact-picker__add" onClick={() => setDialog('contact')}>
                    Добавить контакт
                  </button>
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
                  Срок следующего шага (МСК)
                  <input
                    type="datetime-local"
                    value={createNextActionAt}
                    onChange={(event) => changeCreateNextActionAt(event.target.value)}
                  />
                </label>
                <label>
                  Дата последнего контакта (МСК)
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
              </form>
            </CardDialog>
            <CardDialog open={dialog === 'contact'} title="Новый контакт" onClose={() => setDialog('create')}>
              <p className="interaction-field-hint">Новый контакт сразу будет выбран для нового взаимодействия.</p>
              {contactForm}
            </CardDialog>
          </>
        )}
      </section>
    )
  }

  const stageNumber = currentInteraction === undefined
    ? 0
    : currentInteraction.stages.findIndex((stage) => stage.id === currentInteraction.currentStageId) + 1
  const currentPathItem = pathItems.find((item) => item.status === 'current')
  const uploadStage = currentInteraction?.stages.find((stage) => stage.id === uploadStageId)
  const commentStage = currentInteraction?.stages.find((stage) => stage.id === commentStageId)
  const workStatus = currentInteraction?.marks.status
  const work = workAgreements.kind === 'ready' ? workAgreements.value : null
  const stageRole = work === null || currentInteraction === undefined ? null : activeStageRole(work.stages, currentInteraction.currentStageId)
  const nextTransition = currentInteraction === undefined ? undefined : nextAllowedTransition(currentInteraction)
  const signingBlockedNext = currentInteraction === undefined || nextTransition === undefined
    ? null
    : signingBlock(work, currentInteraction.stages, currentInteraction.currentStageId, nextTransition.stageId)
  const transitionSigningBlock = currentInteraction === undefined || selectedTransition === undefined
    ? null
    : signingBlock(work, currentInteraction.stages, currentInteraction.currentStageId, selectedTransition.stageId)
  const hasNextStep = (currentInteraction?.nextAction?.trim() ?? '').length > 0
  const hasStepOrDue = hasNextStep || (currentInteraction?.nextActionAt ?? null) !== null
  const primary = currentInteraction === undefined ? null : primaryAction({
    canEdit: canManageDailyWork,
    status: currentInteraction.marks.status,
    hasNextStep,
    transitionCount: currentInteraction.allowedTransitions.length,
    agreementStageRole: stageRole,
    agreementCount: work?.agreements.length ?? 0,
    signingBlocked: signingBlockedNext
  })
  const startAgreementCreate = () => {
    setCardTab('contract')
    setAgreementCreateSignal((value) => value + 1)
  }
  const startNewCycle = () => {
    setCardTab('learning')
    setCyclesFocus((value) => value + 1)
  }
  const runPrimary = () => {
    if (primary === null) {
      return
    }
    if (primary.kind === 'transition') {
      openTransition()
    } else if (primary.kind === 'resume') {
      setDialog('status')
    } else if (primary.kind === 'createAgreement') {
      startAgreementCreate()
    } else if (primary.kind === 'openAgreement') {
      setCardTab('contract')
    } else {
      setDialog('step')
    }
  }
  const addItems: ActionMenuItem[] = canManageDailyWork ? [
    { label: 'Комментарий…', onSelect: () => setDialog('comment') },
    { label: 'Документ…', onSelect: () => setDialog('upload') },
    { label: 'Проблема или риск…', onSelect: () => setDialog('issues') }
  ] : []
  const moreItems: ActionMenuItem[] = canManageDailyWork ? [
    { label: 'Изменить шаг и срок…', onSelect: () => setDialog('step') },
    ...(primary?.kind === 'transition' ? [] : [{ label: 'Перейти на этап…', onSelect: openTransition }]),
    { label: 'Отметить этап выполненным…', onSelect: () => setDialog('stageCompletion') },
    { label: 'Чего ждём от вуза или РТК…', onSelect: () => setDialog('flags') },
    { label: 'Проблемы и риски…', onSelect: () => setDialog('issues') },
    { label: 'Изменить данные взаимодействия…', onSelect: () => setDialog('details'), separated: true },
    { label: 'Программа и продукты…', onSelect: () => setDialog('program') },
    { label: 'Изменить этапы этого взаимодействия…', onSelect: () => setDialog('stageEdit') },
    { label: 'Начать новый цикл…', onSelect: startNewCycle },
    {
      label: workStatus === 'ACTIVE' ? 'Приостановить или завершить…' : 'Возобновить…',
      onSelect: () => setDialog('status'),
      danger: workStatus === 'ACTIVE',
      separated: true
    }
  ] : [{ label: 'Проблемы и риски…', onSelect: () => setDialog('issues') }]
  const cardTabs: CardTab<CardTabId>[] = currentInteraction === undefined ? [] : [
    { id: 'history', label: 'История', count: currentEvents?.length },
    { id: 'documents', label: 'Документы', count: currentInteraction.attachments.length },
    { id: 'contract', label: 'Договор и лицензии', count: (work?.agreements.length ?? 0) + currentInteraction.productAgreements.length },
    { id: 'learning', label: 'Обучение', count: signalCount > 0 ? signalCount : undefined },
    { id: 'contacts', label: 'Контакты', count: currentInteraction.contactIds.length }
  ]
  const reload = () => {
    if (currentInteraction !== undefined) {
      openInteraction(currentInteraction.id)
    }
  }
  const planForm = (part: PlanPart) => planValues !== undefined && currentInteraction !== undefined && (
    <form className="interaction-plan-form" onSubmit={(event) => void submitPlan(event)}>
      {planConflictList.length > 0 && (
        <div className="interaction-catalog-message" role="status">
          <p>Пока вы редактировали, в карточке изменились поля, которые вы тоже меняете. При сохранении останутся ваши значения.</p>
          <ul>
            {planConflictList.map((conflict) => (
              <li key={conflict.field}>{conflict.label}: сейчас в карточке {conflict.current}</li>
            ))}
          </ul>
        </div>
      )}
      {part === 'step' && (
        <>
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
            Срок (МСК)
            <input
              type="datetime-local"
              value={planValues.nextActionAt}
              onChange={(event) => changePlan('nextActionAt', event.target.value)}
            />
          </label>
        </>
      )}
      {part === 'program' && (catalogState.kind === 'ready' ? (
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
                      {lockedProductIds.has(product.id) && <small>Есть данные лицензии, снять нельзя</small>}
                    </label>
                  </li>
                ))}
              </ul>
            )}
          </fieldset>
        </>
      ) : (
        <p className="interaction-catalog-message">Программу и продукты можно изменить после загрузки справочника.</p>
      ))}
      <div className="interaction-plan-form__actions">
        <button type="submit" disabled={planState.kind === 'saving' || !planHasChanges} title={planHasChanges ? undefined : 'Измените поле, чтобы сохранить'}>
          {planState.kind === 'saving' ? 'Сохраняем…' : 'Сохранить'}
        </button>
        {planHasChanges && (
          <button type="button" className="button--secondary" disabled={planState.kind === 'saving'} onClick={resetPlan}>
            Сбросить изменения
          </button>
        )}
      </div>
      {planState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandMessage(planState.error)}</p>
          <StructuredApiError error={planState.error} />
          {planState.error instanceof ApiError && <SupportDetails requestId={planState.error.requestId} code={planState.error.code} />}
          {planState.error instanceof ApiError && planState.error.status === 409 && (
            <button type="button" onClick={reload}>Обновить карточку</button>
          )}
        </div>
      )}
    </form>
  )

  return (
    <section className="interaction-detail work-card" aria-labelledby="interaction-detail-title">
      {currentInteraction === undefined && (
        <h3 id="interaction-detail-title" ref={detailHeading} tabIndex={-1}>Карточка взаимодействия</h3>
      )}
      {detailState.kind === 'loading' && (
        <p className="organizations-message" role="status">Загружаем карточку взаимодействия…</p>
      )}
      {detailState.kind === 'failed' && detailState.code === 'NOT_FOUND' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Взаимодействие не найдено. Возможно, его удалили или у вас больше нет к нему доступа.</p>
          <SupportDetails requestId={detailState.requestId} code={detailState.code} />
        </div>
      )}
      {detailState.kind === 'failed' && detailState.code !== 'NOT_FOUND' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить карточку взаимодействия. Повторите попытку.</p>
          <SupportDetails requestId={detailState.requestId} code={detailState.code} />
          <button type="button" onClick={() => openInteraction(detailState.id)}>Повторить</button>
        </div>
      )}
      {currentInteraction !== undefined && schedule !== undefined && (
        <>
          <div className="work-card__top">
            <div className="work-card__heading">
              <h3 id="interaction-detail-title" ref={detailHeading} tabIndex={-1}>{currentInteraction.title}</h3>
              <div className="work-card__chips">
                {workStatus === 'ACTIVE' && <span className="status status--planned">{workStatusLabels.ACTIVE}</span>}
                <InteractionMarkBadges marks={currentInteraction.marks} onOpenIssues={() => setDialog('issues')} />
                {signalCount > 0 && (
                  <button type="button" className="status status--missing work-card__chip-button" onClick={() => setCardTab('learning')}>
                    Сигналы LMS: {signalCount}
                  </button>
                )}
              </div>
              {currentInteraction.marks.status !== 'ACTIVE' && currentInteraction.marks.statusReason && (
                <p className="work-card__meta">
                  {currentInteraction.marks.status === 'COMPLETED' ? 'Итог' : 'Причина'}: {currentInteraction.marks.statusReason}
                </p>
              )}
              {readOnlyNote}
            </div>
            <div className="work-card__actions">
              {primary !== null && (
                <button
                  type="button"
                  className="work-card__primary"
                  data-action={primary.kind}
                  onClick={runPrimary}
                  disabled={primary.kind === 'transition' && primary.disabled}
                  title={'hint' in primary ? primary.hint : undefined}
                >
                  <span className="work-card__primary-full">{primary.label}</span>
                  <span className="work-card__primary-short">{primaryShortLabels[primary.kind]}</span>
                </button>
              )}
              <ActionMenu label="Добавить" toggle="Добавить ▾" className="work-card__add" items={addItems} />
              <ActionMenu label="Ещё действия со взаимодействием" className="work-card__more" items={moreItems} />
            </div>
          </div>
          {primary?.kind === 'openAgreement' && <p className="work-card__rule" role="note">{primary.hint}</p>}

          <WorkFacts
            interaction={currentInteraction}
            organization={organization}
            stageNumber={stageNumber}
            daysOnStage={currentPathItem?.enteredAt === undefined ? null : daysSince(currentPathItem.enteredAt)}
            schedule={schedule}
            agreement={workAgreements.kind === 'ready' ? agreementFact(work, currentInteraction.currentStageId) : workAgreements.kind}
            canEdit={canManageDailyWork}
            onCreateAgreement={startAgreementCreate}
          />

          <div className={`work-step ${schedule.className}`}>
            <p className="work-step__text">
              <span>Следующий шаг: <strong>{currentInteraction.nextAction ?? 'не задан'}</strong></span>
              {getModules().partner && currentInteraction.nextStepPartnerVisible && <span className="status status--planned">Виден вузу</span>}
              <span className="work-step__due">{schedule.label}</span>
            </p>
            {canManageDailyWork && !completingStep && (
              <div className="work-step__actions">
                {hasStepOrDue && (
                  <button type="button" className="button--secondary" onClick={() => setCompletingStep(true)}>Шаг выполнен</button>
                )}
                <button type="button" className="button--secondary" onClick={() => setDialog('step')}>Изменить</button>
              </div>
            )}
            {canManageDailyWork && completingStep && (
              <StepCompletion
                key={currentInteraction.id}
                interaction={currentInteraction}
                startOpen
                onCancel={() => setCompletingStep(false)}
                onCompleted={(interaction) => {
                  setCompletingStep(false)
                  setDetailState({ kind: 'ready', interaction })
                  finish('Шаг отмечен выполненным и записан в историю.')
                  void loadEvents(interaction.id)
                  refreshList()
                }}
                onReload={reload}
                onSessionExpired={onSessionExpired}
                onProfileUnavailable={onProfileUnavailable}
              />
            )}
          </div>

          <RouteDisclosure
            interaction={currentInteraction}
            items={pathItems}
            canEdit={canManageDailyWork}
            onCompleteStage={() => setDialog('stageCompletion')}
            onEditStages={() => setDialog('stageEdit')}
          />

          {noticeSlot}

          <CardTabs label="Разделы карточки взаимодействия" idPrefix="work-card" tabs={cardTabs} active={cardTab} onChange={(tab) => {
            setCardTab(tab)
            setNotice(null)
          }} />
          <CardTabPanel idPrefix="work-card" active={cardTab}>
            {cardTab === 'history' && (
              <WorkHistoryTab
                key={currentInteraction.id}
                events={currentEvents}
                loading={eventsState.kind === 'loading' && eventsState.id === currentInteraction.id}
                failedRequestId={eventsState.kind === 'failed' && eventsState.id === currentInteraction.id ? eventsState.requestId ?? null : undefined}
                attachmentsByEvent={attachmentsByEvent}
                onRefresh={() => void loadEvents(currentInteraction.id)}
              />
            )}

            {cardTab === 'documents' && (
              <section className="interaction-attachments" aria-labelledby="interaction-attachments-title">
                <div className="interaction-events__header">
                  <h5 id="interaction-attachments-title" className="visually-hidden">Документы</h5>
                  {currentInteraction.attachments.length > 0 && (
                    <label className="document-filter">
                      Отбор по виду документа
                      <select value={attachmentKindFilter} onChange={(event) => setAttachmentKindFilter(event.target.value as AttachmentKind | '')}>
                        <option value="">Все виды</option>
                        {attachmentKinds.map((kind) => <option key={kind} value={kind}>{attachmentKindLabels[kind]}</option>)}
                      </select>
                    </label>
                  )}
                  {canManageDailyWork && (
                    <button type="button" className="button--secondary" onClick={() => setDialog('upload')}>Загрузить документ</button>
                  )}
                </div>
                {currentInteraction.attachments.length === 0 && (
                  <p>Документов пока нет.</p>
                )}
                {attachmentKindFilter !== '' && currentInteraction.attachments.length > 0
                  && !currentInteraction.attachments.some((attachment) => attachment.kind === attachmentKindFilter) && (
                  <p>Документов вида «{attachmentKindLabels[attachmentKindFilter]}» нет.</p>
                )}
                {currentInteraction.attachments.length > 0 && (
                  <ul className="interaction-attachment-list">
                    {currentInteraction.attachments.filter((attachment) => attachmentKindFilter === '' || attachment.kind === attachmentKindFilter).map((attachment) => {
                      const stageName = attachmentStageNames.get(attachment.stageId) ?? 'Этап недоступен'
                      const refreshing = attachmentRefreshState.kind === 'loading' && attachmentRefreshState.id === attachment.id
                      const downloading = attachmentDownloadState.kind === 'downloading' && attachmentDownloadState.id === attachment.id
                      return (
                        <li key={attachment.id} className="interaction-attachment">
                          <div className="interaction-attachment__header">
                            <strong className="interaction-attachment__name">{attachment.originalName}</strong>
                            <span className={`interaction-attachment__status interaction-attachment__status--${attachment.status.toLowerCase()}`}>
                              {attachmentStatusLabel[attachment.status]}
                            </span>
                          </div>
                          <p className="interaction-attachment__meta">
                            Этап: {stageName} · {fileTypeLabel(attachment)}, {formatFileSize(attachment.sizeBytes)} · загружен {formatDateTime(attachment.createdAt)} · {attachment.eventId === null ? 'ещё не привязан к событию истории' : 'связан с событием истории'}
                          </p>
                          <AttachmentExtras
                            interaction={currentInteraction}
                            attachment={attachment}
                            canEdit={canManageDailyWork}
                            profileId={profileId}
                            role={role}
                            onAttachment={replaceAttachment}
                            onInteraction={(interaction) => {
                              acceptInteraction(interaction)
                              setNotice('Документ удалён. Запись об удалении добавлена в историю.')
                            }}
                            onReload={reload}
                            onSessionExpired={onSessionExpired}
                            onProfileUnavailable={onProfileUnavailable}
                          >
                            {attachment.status === 'CLEAN' && (
                              <button type="button" className="button--secondary" onClick={() => void downloadAttachment(attachment)} disabled={downloading}>
                                {downloading ? 'Готовим скачивание…' : 'Скачать'}
                              </button>
                            )}
                            {attachment.status === 'QUARANTINE' && (
                              <button type="button" className="button--secondary" onClick={() => void refreshAttachment(attachment)} disabled={refreshing}>
                                {refreshing ? 'Обновляем статус…' : 'Обновить статус'}
                              </button>
                            )}
                          </AttachmentExtras>
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
              </section>
            )}

            {cardTab === 'contract' && (
              <div className="work-contract">
                <WorkAgreementBlock
                  key={`agreement:${currentInteraction.id}`}
                  interactionId={currentInteraction.id}
                  organizationId={organizationId}
                  currentStageId={currentInteraction.currentStageId}
                  revision={currentInteraction.version}
                  canEdit={canManageDailyWork}
                  startCreate={agreementCreateSignal > 0}
                  onCreateStarted={() => setAgreementCreateSignal(0)}
                  onChanged={() => setAgreementsRevision((value) => value + 1)}
                  onSessionExpired={onSessionExpired}
                  onProfileUnavailable={onProfileUnavailable}
                />
                {(['contract', 'transfers'] as const).map((part) => (
                  <ProductAgreementsSection
                    key={part}
                    part={part}
                    interaction={currentInteraction}
                    canEdit={canManageDailyWork}
                    onInteraction={(interaction) => {
                      acceptInteraction(interaction)
                      setNotice(part === 'contract' ? 'Лицензия по продукту сохранена.' : 'Отметки передачи сохранены.')
                    }}
                    onReload={reload}
                    onSessionExpired={onSessionExpired}
                    onProfileUnavailable={onProfileUnavailable}
                  />
                ))}
              </div>
            )}

            {cardTab === 'learning' && (
              <div className="work-training">
                <LmsStageSuggestionPanel
                  interaction={currentInteraction}
                  canEdit={canManageDailyWork}
                  onChanged={applyUpdatedInteraction}
                  onOpenTransition={openTransition}
                  onOpenCycles={startNewCycle}
                  onSessionExpired={onSessionExpired}
                  onProfileUnavailable={onProfileUnavailable}
                />
                <CardSection title="Сигналы LMS" count={signalCount} defaultOpen={signalCount > 0}>
                  <LmsSignalsCard
                    interactionId={currentInteraction.id}
                    version={currentInteraction.version}
                    canEdit={canManageDailyWork}
                    onMoveToStage={(stageId) => {
                      changeTransitionStage(stageId)
                      setDialog('transition')
                    }}
                    onRecordedAsRisk={(updated) => applyUpdatedInteraction(updated, 'Сигнал LMS записан в реестр проблем и рисков взаимодействия.')}
                    onSessionExpired={onSessionExpired}
                    onProfileUnavailable={onProfileUnavailable}
                  />
                </CardSection>
                <CardSection title="Данные LMS" defaultOpen>
                  <LearningSnapshots
                    interactionId={currentInteraction.id}
                    version={currentInteraction.version}
                    hasProgram={currentInteraction.program !== null}
                    canRefresh={canManageDailyWork}
                    onRefreshed={() => {
                      setNotice('Данные источников обновлены.')
                      openInteraction(currentInteraction.id)
                    }}
                    onSessionExpired={onSessionExpired}
                    onProfileUnavailable={onProfileUnavailable}
                  />
                </CardSection>
                <CardSection title="Повышение квалификации">
                  <TeacherTrainings
                    interaction={currentInteraction}
                    canEdit={canManageDailyWork}
                    onSaved={() => {
                      setNotice('Запись об обучении преподавателей сохранена.')
                      openInteraction(currentInteraction.id)
                    }}
                    onSessionExpired={onSessionExpired}
                    onProfileUnavailable={onProfileUnavailable}
                  />
                </CardSection>
                <CardSection title="Списки на курс LMS">
                  <TeacherRoster
                    key={`teacher-roster:${currentInteraction.id}`}
                    interaction={currentInteraction}
                    contacts={contactsState.kind === 'ready' ? contactsState.contacts : null}
                    profileId={profileId}
                    canEdit={canManageDailyWork}
                    onAddContact={() => {
                      setContactRole('TEACHER')
                      setDialog('contact')
                    }}
                    onContactsChanged={() => {
                      void loadContacts()
                      onContactsChanged?.()
                    }}
                    onSessionExpired={onSessionExpired}
                    onProfileUnavailable={onProfileUnavailable}
                  />
                </CardSection>
                <CardSection id="work-cycles" title="Циклы взаимодействия" defaultOpen={cyclesFocus > 0}>
                  <InteractionCycles
                    key={`cycles:${currentInteraction.id}`}
                    interaction={currentInteraction}
                    canEdit={canManageDailyWork}
                    onOpen={goToInteraction}
                    onStarted={(id) => {
                      carriedNotice = { interactionId: id, text: 'Новый цикл начат. Это его карточка.' }
                      goToInteraction(id)
                    }}
                    onSessionExpired={onSessionExpired}
                    onProfileUnavailable={onProfileUnavailable}
                  />
                </CardSection>
              </div>
            )}

            {cardTab === 'contacts' && (
              <section className="interaction-linked-contacts" aria-labelledby="interaction-linked-contacts-title">
                <div className="interaction-events__header">
                  <h5 id="interaction-linked-contacts-title">Связанные контакты</h5>
                  {canManageDailyWork && (
                    <button type="button" className="button--secondary" onClick={() => setDialog('details')}>Изменить состав</button>
                  )}
                </div>
                {linkedContacts.length === 0 && <p>Контакты для этого взаимодействия не выбраны.</p>}
                {linkedContacts.length > 0 && contactsState.kind === 'loading' && (
                  <p>Загружаем сведения о связанных контактах…</p>
                )}
                {linkedContacts.length > 0 && contactsState.kind === 'failed' && (
                  <p>Не удалось получить текущий список контактов вуза.</p>
                )}
                {linkedContacts.length > 0 && contactsState.kind === 'ready' && (
                  <ul>
                    {linkedContacts.map(({ id, contact }) => (
                      <li key={id}>
                        {contact === undefined ? (
                          <span>Контакт больше недоступен в текущем списке вуза.</span>
                        ) : (
                          <>
                            <strong>{contact.name}</strong>
                            {contact.primary && <span>Основной контакт</span>}
                            {contact.role && <span>{contactRoleLabels[contact.role]}</span>}
                            {contact.inactive && <span>Не актуален</span>}
                            {contact.position && <span>{contact.position}</span>}
                            {contact.email && <span><ContactLink href={emailHref(contact.email)} text={contact.email} /></span>}
                            {contact.phone && <span><ContactLink href={phoneHref(contact.phone)} text={contact.phone} /></span>}
                          </>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            )}
          </CardTabPanel>

          <CardDialog open={dialog === 'issues'} title="Проблемы и риски" onClose={closeDialog}>
            <InteractionIssuesPanel
              key={`issues:${currentInteraction.id}`}
              interaction={currentInteraction}
              canEdit={canManageDailyWork}
              onChanged={(interaction) => applyUpdatedInteraction(interaction)}
              onRefresh={reload}
              onSessionExpired={onSessionExpired}
              onProfileUnavailable={onProfileUnavailable}
            />
          </CardDialog>

          {canManageDailyWork && (
            <>
              <CardDialog open={dialog === 'transition'} title="Перейти на этап" onClose={closeDialog}>
                <div className="interaction-commands">
                  <form className="interaction-transition-form" onSubmit={(event) => void submitTransition(event)}>
                    <p className="interaction-stage-choice">Текущий этап: {currentInteraction.currentStageName}.</p>
                    <label>
                      <span>Новый этап<RequiredMark /></span>
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
                      <p className="interaction-stage-choice">Из текущего этапа переходы не разрешены.</p>
                    )}
                    {selectedTransition !== undefined && (
                      <p className="interaction-stage-choice">
                        Выбран этап: {selectedTransition.stageName}{selectedTransition.commentRequired ? '; комментарий обязателен (пропуск или возврат этапа)' : ''}
                      </p>
                    )}
                    {transitionSigningBlock !== null && (
                      <p className="interaction-catalog-message interaction-catalog-message--error work-card__signing" role="alert">{transitionSigningBlock}</p>
                    )}
                    <label>
                      <span>Комментарий к переходу{selectedTransition?.commentRequired && <RequiredMark />}</span>
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
                      <legend>Проверенные документы к переходу</legend>
                      {transitionStageId.length === 0 && <p>Сначала выберите новый этап.</p>}
                      {transitionStageId.length > 0 && transitionAttachments.length === 0 && (
                        <p>Для нового этапа нет проверенных документов, ещё не связанных с историей.</p>
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
                          <button type="button" onClick={reload}>Обновить карточку</button>
                        )}
                      </div>
                    )}
                  </form>
                </div>
              </CardDialog>

              <CardDialog open={dialog === 'step'} title="Изменить шаг и срок" onClose={closeDialog}>
                {planForm('step')}
                {getModules().partner && (
                  <PartnerStepToggle
                    key={`partner-step:${currentInteraction.id}:${currentInteraction.version}`}
                    interaction={currentInteraction}
                    onUpdated={(interaction) => applyUpdatedInteraction(interaction, interaction.nextStepPartnerVisible
                      ? 'Следующий шаг показан в кабинете вуза.'
                      : 'Следующий шаг больше не показан в кабинете вуза.')}
                    onReload={reload}
                    onSessionExpired={onSessionExpired}
                    onProfileUnavailable={onProfileUnavailable}
                  />
                )}
              </CardDialog>

              <CardDialog open={dialog === 'program'} title="Программа и продукты" onClose={closeDialog}>
                {planForm('program')}
              </CardDialog>

              <CardDialog open={dialog === 'comment'} title="Комментарий" onClose={closeDialog}>
                <div className="interaction-commands">
                  <form className="interaction-comment-form" onSubmit={(event) => void submitComment(event)}>
                    <label>
                      <span>Этап комментария<RequiredMark /></span>
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
                      <span>Текст комментария<RequiredMark /></span>
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
                      <legend>Проверенные документы к комментарию</legend>
                      {commentStageId.length === 0 && <p>Сначала выберите этап комментария.</p>}
                      {commentStageId.length > 0 && commentAttachments.length === 0 && (
                        <p>Для выбранного этапа нет проверенных документов, ещё не связанных с историей.</p>
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
                      {commentState.kind === 'saving' ? 'Сохраняем…' : `Добавить комментарий${commentStage === undefined ? '' : ` к этапу «${commentStage.name}»`}`}
                    </button>
                    {commentState.kind === 'failed' && (
                      <div className="interaction-command-error" role="alert">
                        <p>{commandMessage(commentState.error)}</p>
                        <StructuredApiError error={commentState.error} />
                        {commentState.error instanceof ApiError && <SupportDetails requestId={commentState.error.requestId} code={commentState.error.code} />}
                        {commentState.error instanceof ApiError && commentState.error.status === 409 && (
                          <button type="button" onClick={reload}>Обновить карточку</button>
                        )}
                      </div>
                    )}
                  </form>
                </div>
              </CardDialog>

              <CardDialog open={dialog === 'upload'} title="Документ" onClose={closeDialog}>
                <form className="interaction-attachment-upload" onSubmit={(event) => void submitUpload(event)}>
                  <label>
                    <span>Этап документа<RequiredMark /></span>
                    <select value={uploadStageId} required onChange={(event) => changeUploadStage(event.target.value)}>
                      {currentInteraction.stages.map((stage) => (
                        <option key={stage.id} value={stage.id}>
                          {stage.order + 1}. {stage.name}{stage.id === currentInteraction.currentStageId ? ' (текущий)' : ''}
                        </option>
                      ))}
                    </select>
                  </label>
                  {uploadStageId.length > 0 && uploadStageId !== currentInteraction.currentStageId && (
                    <p className="interaction-catalog-message interaction-catalog-message--error" role="status">
                      Выбран не текущий этап: файл попадёт в этап «{uploadStage?.name ?? 'этап недоступен'}», а текущий этап — «{currentInteraction.currentStageName}».
                    </p>
                  )}
                  <label>
                    Вид документа
                    <select value={uploadKind} onChange={(event) => setUploadKind(event.target.value as AttachmentKind)}>
                      {attachmentKinds.map((kind) => <option key={kind} value={kind}>{attachmentKindLabels[kind]}</option>)}
                    </select>
                  </label>
                  <label>
                    <span>Файл<RequiredMark /></span>
                    <input
                      ref={uploadInput}
                      type="file"
                      required={uploadFile === null}
                      accept={attachmentAccept}
                      onChange={(event) => changeUploadFile(event.target.files?.item(0) ?? null)}
                      aria-invalid={uploadFileError === undefined ? undefined : true}
                      aria-describedby={uploadFileError === undefined ? 'interaction-upload-hint' : 'interaction-upload-hint interaction-upload-error'}
                    />
                    <span id="interaction-upload-hint" className="interaction-field-hint">
                      До {attachmentLimitMegabytes} МБ: {attachmentFormats}. После загрузки файл проверяется антивирусом.
                    </span>
                    {uploadFile !== null && <span className="interaction-field-hint">Выбран файл: {uploadFile.name}</span>}
                    <FieldError id="interaction-upload-error" message={uploadFileError} />
                  </label>
                  <CameraInput disabled={uploadState.kind === 'saving'} onFile={changeUploadFile} />
                  <button type="submit" disabled={uploadState.kind === 'saving' || uploadFile === null || uploadTooLarge || uploadStageId.length === 0}>
                    {uploadState.kind === 'saving' ? 'Загружаем…' : `Загрузить в этап «${uploadStage?.name ?? 'не выбран'}»`}
                  </button>
                  {uploadState.kind === 'failed' && (
                    <div className="interaction-command-error" role="alert">
                      <p>{attachmentMessage(uploadState.error, 'upload')}</p>
                      {uploadState.error instanceof ApiError && <SupportDetails requestId={uploadState.error.requestId} code={uploadState.error.code} />}
                      {uploadState.error instanceof ApiError && uploadState.error.status === 409 && (
                        <button type="button" onClick={reload}>Обновить карточку</button>
                      )}
                    </div>
                  )}
                </form>
              </CardDialog>

              <CardDialog open={dialog === 'status'} title="Статус взаимодействия" onClose={closeDialog}>
                <InteractionStatusPanel
                  key={`status:${currentInteraction.id}`}
                  interaction={currentInteraction}
                  profileId={profileId}
                  onChanged={(interaction) => applyUpdatedInteraction(interaction, `Статус взаимодействия: ${workStatusLabels[interaction.marks.status]}.`)}
                  onRefresh={reload}
                  onSessionExpired={onSessionExpired}
                  onProfileUnavailable={onProfileUnavailable}
                />
              </CardDialog>

              <CardDialog open={dialog === 'flags'} title="Чего ждём от вуза или РТК" onClose={closeDialog}>
                <InteractionFlagsForm
                  key={`flags:${currentInteraction.id}`}
                  interaction={currentInteraction}
                  profileId={profileId}
                  onChanged={(interaction) => applyUpdatedInteraction(interaction, 'Отметка ожидания сохранена.')}
                  onRefresh={reload}
                  onSessionExpired={onSessionExpired}
                  onProfileUnavailable={onProfileUnavailable}
                />
              </CardDialog>

              <CardDialog open={dialog === 'details'} title="Данные взаимодействия" onClose={closeDialog}>
                <InteractionDetailsForm
                  key={`details:${currentInteraction.id}`}
                  interaction={currentInteraction}
                  contacts={contactsState.kind === 'ready' ? contactsState.contacts : null}
                  profileId={profileId}
                  onAddContact={() => {
                    contactFromDetails.current = true
                    setDialog('contact')
                  }}
                  onChanged={(interaction) => applyUpdatedInteraction(interaction, 'Данные взаимодействия сохранены.')}
                  onRefresh={reload}
                  onSessionExpired={onSessionExpired}
                  onProfileUnavailable={onProfileUnavailable}
                />
              </CardDialog>

              <CardDialog open={dialog === 'contact'} title="Новый контакт" onClose={closeDialog}>
                {contactForm}
              </CardDialog>

              <CardDialog open={dialog === 'stageCompletion'} title="Отметить этап выполненным" onClose={closeDialog}>
                <StageCompletionPanel
                  interaction={currentInteraction}
                  signingWarning={work !== null && work.signingRuleEnabled && work.stages.some((stage) => stage.role === 'SIGNING')
                    && !work.agreements.some(isSignedAgreement)
                    ? 'Этап «Подписание» можно отметить выполненным только при подписанном договоре с вузом.'
                    : undefined}
                  onChanged={(interaction) => {
                    const attachableIds = attachableIdsOf(interaction)
                    setCommentAttachmentIds((ids) => ids.filter((attachmentId) => attachableIds.has(attachmentId)))
                    setTransitionAttachmentIds((ids) => ids.filter((attachmentId) => attachableIds.has(attachmentId)))
                    setDetailState({ kind: 'ready', interaction })
                    finish('Отметки выполнения этапов сохранены.')
                    void loadEvents(interaction.id)
                    refreshList()
                  }}
                  onReload={reload}
                  onSessionExpired={onSessionExpired}
                  onProfileUnavailable={onProfileUnavailable}
                />
              </CardDialog>

              <CardDialog open={dialog === 'stageEdit'} title="Изменить этапы этого взаимодействия" onClose={closeDialog}>
                <form className="interaction-stage-editor" onSubmit={submitStageEdit}>
                  <p>Изменение касается только маршрута этого взаимодействия. Шаблон команды и другие взаимодействия не меняются.</p>
                  <label>
                    Что изменить
                    <select value={stageEditType} onChange={(event) => changeStageEditType(event.target.value as InteractionStageEditType)}>
                      <option value="RENAME">Переименовать этап</option>
                      <option value="ADD_AFTER">Добавить этап после выбранного</option>
                      <option value="MOVE_AFTER">Переместить этап после выбранного</option>
                      <option value="DELETE">Удалить этап</option>
                    </select>
                  </label>
                  {stageEditNeedsTarget && (
                    <label>
                      <span>Этап<RequiredMark /></span>
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
                      <span>После какого этапа<RequiredMark /></span>
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
                      <span>Название этапа<RequiredMark /></span>
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
                  <button
                    type="submit"
                    className={stageEditType === 'DELETE' ? 'button--danger' : undefined}
                    disabled={stageEditState.kind === 'saving' || !stageEditCanSubmit}
                  >
                    {stageEditState.kind === 'saving' ? 'Сохраняем…' : stageEditType === 'DELETE' ? 'Удалить этап…' : 'Применить изменение'}
                  </button>
                  {stageEditState.kind === 'failed' && (
                    <div className="interaction-command-error" role="alert">
                      <p>{commandMessage(stageEditState.error)}</p>
                      <StructuredApiError error={stageEditState.error} />
                      {stageEditState.error instanceof ApiError && <SupportDetails requestId={stageEditState.error.requestId} code={stageEditState.error.code} />}
                      {stageEditState.error instanceof ApiError && stageEditState.error.status === 409 && (
                        <button type="button" onClick={reload}>Обновить карточку</button>
                      )}
                    </div>
                  )}
                </form>
              </CardDialog>
              <ConfirmDialog
                open={confirmStageDelete}
                title="Удалить этап?"
                description={`Этап «${currentInteraction.stages.find((stage) => stage.id === stageEditId)?.name ?? ''}» исчезнет из маршрута этого взаимодействия. Шаблон команды и другие взаимодействия не изменятся, удаление записывается в историю.`}
                confirmLabel="Удалить этап"
                onCancel={() => setConfirmStageDelete(false)}
                onConfirm={() => {
                  setConfirmStageDelete(false)
                  void runStageEdit()
                }}
              />
            </>
          )}
        </>
      )}
    </section>
  )
}
