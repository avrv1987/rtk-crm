import type { components, operations } from './openapi'

export type Me = components['schemas']['Me']
export type Config = components['schemas']['Config']
export type CsrfToken = components['schemas']['CsrfToken']
export type LogoutResult = components['schemas']['LogoutResult']
export type ApiErrorPayload = components['schemas']['ApiError']
export type Organization = components['schemas']['Organization']
export type PageOrganization = components['schemas']['PageOrganization']
export type OrganizationListParams = NonNullable<operations['listOrganizations']['parameters']['query']>
export type OrganizationAssignmentCandidate = {
  id: string
  displayName: string
}
export type OrganizationAssignmentEvent = {
  id: string
  previousOwnerManagerId: string | null
  previousOwnerManagerDisplayName?: string | null
  ownerManagerId: string | null
  newOwnerManagerDisplayName?: string | null
  actorProfileId: string
  actorDisplayName?: string | null
  reason?: components['schemas']['OrganizationAssignmentEvent']['reason']
  handoverNote?: string | null
  occurredAt: string
}
export type OrganizationAssignment = {
  version: number
  ownerManagerId: string | null
  handoverNote?: string | null
}
export type OrganizationAssignmentResult = {
  organization: Organization
  event: OrganizationAssignmentEvent
}
export type Contact = components['schemas']['Contact']
export type ContactCreate = components['schemas']['ContactCreate']
export type ContactUpdate = components['schemas']['ContactUpdate']
export type ContactRole = components['schemas']['ContactRole']
export type ContactEvent = components['schemas']['ContactEvent']
export type OrganizationHistoryKind = components['schemas']['OrganizationHistoryKind']
export type OrganizationHistoryItem = components['schemas']['OrganizationHistoryItem']
export type PageOrganizationHistory = components['schemas']['PageOrganizationHistory']
export type OrganizationHistoryQuery = {
  kinds?: OrganizationHistoryKind[]
  from?: string
  to?: string
  page?: number
  size?: number
}
export type CatalogLookup = components['schemas']['CatalogLookup']
export type PageCatalogLookup = components['schemas']['PageCatalogLookup']
export type CatalogListParams = NonNullable<operations['listPrograms']['parameters']['query']>
export type ProductAgreement = components['schemas']['ProductAgreement']
export type Attachment = components['schemas']['Attachment']
export type AttachmentUpload = {
  file: File
  stageId: Attachment['stageId']
  kind?: Attachment['kind']
  replacesId?: Attachment['id']
}
export type Interaction = components['schemas']['Interaction']
export type InteractionStage = components['schemas']['InteractionStage']
export type InteractionEvent = components['schemas']['InteractionEvent']
export type LearningSnapshot = components['schemas']['LearningSnapshot']
export type LearningSnapshotsRefresh = components['schemas']['LearningSnapshotsRefresh']
export type PageInteraction = components['schemas']['PageInteraction']
export type InteractionListItem = components['schemas']['InteractionListItem']
export type InteractionCreate = components['schemas']['InteractionCreate']
export type InteractionTransition = components['schemas']['InteractionTransition']
export type InteractionComment = components['schemas']['InteractionComment']
export type InteractionCommentResult = operations['commentInteraction']['responses'][200]['content']['application/json']
export type InteractionStageEdit = components['schemas']['InteractionStageEdit']
export type InteractionStageCompletion = components['schemas']['InteractionStageCompletion']
export type InteractionStageCompletionRequest = components['schemas']['InteractionStageCompletionRequest']
export type InteractionPlanUpdate = components['schemas']['InteractionPlanUpdate']
export type InteractionNextStep = components['schemas']['InteractionNextStep']
export type InteractionMarks = components['schemas']['InteractionMarks']
export type InteractionStatusChange = components['schemas']['InteractionStatusChange']
export type InteractionFlagsUpdate = components['schemas']['InteractionFlagsUpdate']
export type AgentPlan = components['schemas']['AgentPlan']
export type AgentPlanKind = components['schemas']['AgentPlanKind']
export type AgentPlanListItem = components['schemas']['AgentPlanListItem']
export type InteractionIssue = components['schemas']['InteractionIssue']
export type InteractionIssueList = components['schemas']['InteractionIssueList']
export type InteractionIssuePage = components['schemas']['InteractionIssuePage']
export type InteractionIssueRequest = components['schemas']['InteractionIssueRequest']
export type InteractionIssueResolution = components['schemas']['InteractionIssueResolution']
export type InteractionIssueNote = components['schemas']['InteractionIssueNote']
export type InteractionContactMark = components['schemas']['InteractionContactMark']
export type WorkNews = components['schemas']['WorkNews']
export type WorkNewsItem = components['schemas']['WorkNewsItem']
export type IssueListParams = NonNullable<operations['listIssues']['parameters']['query']>
export type InteractionListParams = NonNullable<operations['listInteractions']['parameters']['query']>
export type InteractionDue = NonNullable<InteractionListParams['due']>
export type InteractionStatusFilter = NonNullable<InteractionListParams['status']>
export type InteractionFlagFilter = NonNullable<InteractionListParams['flag']>
export type InteractionWorkStatusFilter = NonNullable<InteractionListParams['status']>
export type InteractionStepCompletion = components['schemas']['InteractionStepCompletion']
export type OrganizationBulkAssignment = components['schemas']['OrganizationBulkAssignment']
export type OrganizationBulkAssignmentResult = components['schemas']['OrganizationBulkAssignmentResult']
export type OrganizationDeputy = components['schemas']['OrganizationDeputy']
export type OrganizationDeputyCreate = components['schemas']['OrganizationDeputyCreate']
export type ManagerDeputyResult = components['schemas']['ManagerDeputyResult']
export type TeamIndicators = components['schemas']['TeamIndicators']
export type ManagerIndicators = components['schemas']['ManagerIndicators']
export type TeamsSummary = components['schemas']['TeamsSummary']
export type TeamSummary = components['schemas']['TeamSummary']
export type LearningTrend = components['schemas']['LearningTrend']
export type WorkControl = components['schemas']['WorkControl']
export type WorkControlTeam = components['schemas']['WorkControlTeam']
export type WorkControlManager = components['schemas']['WorkControlManager']
export type WorkControlItem = components['schemas']['WorkControlItem']
export type WorkControlTotals = components['schemas']['WorkControlTotals']
export type WorkControlIdleOrganization = components['schemas']['WorkControlIdleOrganization']
export type ManagerWorkDue = components['schemas']['ManagerWorkDue']
export type ManagerWorkChange = components['schemas']['ManagerWorkChange']

export type WorkControlQuery = {
  organizationId: string[]
  directionId: string[]
  programId: string[]
  productId: string[]
  kindId: string[]
  managerId: string[]
  stage: string[]
  from: string
  to: string
  change: ManagerWorkChange | ''
  mode: 'NOW' | 'PERIOD'
}
export type LearningTrendItem = components['schemas']['LearningTrendItem']
export type PortfolioChange = components['schemas']['PortfolioChange']
export type PortfolioChangeRow = components['schemas']['PortfolioChangeRow']
export type LearningCoverage = components['schemas']['LearningCoverage']
export type LearningCoverageRun = components['schemas']['LearningCoverageRun']
export type LearningDynamics = components['schemas']['LearningDynamics']
export type LearningDynamicsQuery = {
  from?: string
  to?: string
  seriesBy?: LearningDynamics['seriesBy']
  organizationIds?: string[]
  programIds?: string[]
}
const learningDynamicsQuery = (query: LearningDynamicsQuery, extra: Record<string, string> = {}) => {
  const searchParams = new URLSearchParams(extra)
  if (query.from) searchParams.set('from', query.from)
  if (query.to) searchParams.set('to', query.to)
  if (query.seriesBy) searchParams.set('seriesBy', query.seriesBy)
  query.organizationIds?.forEach((id) => searchParams.append('organizationIds', id))
  query.programIds?.forEach((id) => searchParams.append('programIds', id))
  return searchParams.size === 0 ? '' : `?${searchParams.toString()}`
}
export type SigningPlan = components['schemas']['SigningPlan']
export type SigningPlanRow = components['schemas']['SigningPlanRow']
export type SigningPlanTotals = components['schemas']['SigningPlanTotals']
export type SigningPlanQuery = { year: number; quarter?: number }
const signingPlanQuery = (query: SigningPlanQuery, extra: Record<string, string> = {}) => {
  const searchParams = new URLSearchParams(extra)
  searchParams.set('year', query.year.toString())
  if (query.quarter !== undefined) searchParams.set('quarter', query.quarter.toString())
  return `?${searchParams.toString()}`
}
export type ReminderDigest = components['schemas']['ReminderDigest']
export type ReminderFeed = components['schemas']['ReminderFeed']
export type ReminderSettings = components['schemas']['ReminderSettings']
export type LmsSignals = components['schemas']['LmsSignals']
export type LmsSignal = components['schemas']['LmsSignal']
export type WorkLearning = components['schemas']['WorkLearning']
export type LearningActivity = components['schemas']['LearningActivity']
export type ReminderQualification = components['schemas']['ReminderQualification']
export type WorkflowStageInput = components['schemas']['WorkflowStageInput']
export type WorkflowTransitionInput = components['schemas']['WorkflowTransitionInput']
export type WorkflowTemplate = components['schemas']['WorkflowTemplate']
export type WorkflowTemplateCreate = components['schemas']['WorkflowTemplateCreate']
export type WorkflowTemplateUpdate = components['schemas']['WorkflowTemplateUpdate']
export type PageWorkflowTemplate = components['schemas']['PageWorkflowTemplate']
export type WorkflowTemplateListParams = NonNullable<operations['listAvailableWorkflowTemplates']['parameters']['query']>
export type WorkflowTemplateVersion = components['schemas']['WorkflowTemplateVersion']
export type WorkflowRolloutRequest = components['schemas']['WorkflowRolloutRequest']
export type WorkflowRolloutPreview = components['schemas']['WorkflowRolloutPreview']
export type WorkflowRolloutResult = components['schemas']['WorkflowRolloutResult']
export type WorkflowStageMatch = components['schemas']['WorkflowStageMatch']
export type ReportKind = components['schemas']['ReportKind']
export type ReportFormat = components['schemas']['ReportFormat']
export type ReportColumn = components['schemas']['ReportColumn']
export type PeriodBasis = components['schemas']['PeriodBasis']
export type ReportPreviewRequest = components['schemas']['ReportPreviewRequest']
export type ReportRequest = components['schemas']['ReportRequest']
export type ReportPreview = components['schemas']['ReportPreview']
export type ReportJob = components['schemas']['ReportJob']
export type ReportJobCreated = components['schemas']['ReportJobCreated']
export type ReportManagerOption = components['schemas']['ReportManagerOption']
export type StatisticsGroupBy = components['schemas']['StatisticsGroupBy']
export type StatisticsRequest = components['schemas']['StatisticsRequest']
export type StatisticsResult = components['schemas']['StatisticsResult']
export type ChartType = components['schemas']['ChartType']
export type ReportEventType = components['schemas']['ReportEventType']
export type SavedReport = components['schemas']['SavedReport']
export type SavedReportRequest = components['schemas']['SavedReportRequest']
export type CrmProfile = components['schemas']['CrmProfile']
export type PageCrmProfile = components['schemas']['PageCrmProfile']
export type KeycloakRoleMigration = components['schemas']['KeycloakRoleMigration']
export type CrmProfileListParams = NonNullable<operations['listCrmProfiles']['parameters']['query']>
export type CrmProfileExportParams = NonNullable<operations['exportCrmProfiles']['parameters']['query']>
export type CrmProfileUpdate = components['schemas']['CrmProfileUpdate']
export type NewEmployee = components['schemas']['NewEmployee']
export type AccountCredentials = components['schemas']['AccountCredentials']
export type CrmProfileEvent = components['schemas']['CrmProfileEvent']
export type CrmProfileOrganizations = components['schemas']['CrmProfileOrganizations']
export type LoginLockoutStatus = components['schemas']['LoginLockoutStatus']
export type BackupStatus = components['schemas']['BackupStatus']
export type Team = components['schemas']['Team']
export type TeamCreate = components['schemas']['TeamCreate']
export type TeamUpdate = components['schemas']['TeamUpdate']
export type AdminOrganization = components['schemas']['AdminOrganization']
export type PageAdminOrganization = components['schemas']['PageAdminOrganization']
export type AdminOrganizationListParams = NonNullable<operations['listAdminOrganizations']['parameters']['query']>
export type OrganizationTeamTransfer = components['schemas']['OrganizationTeamTransfer']
export type OrganizationDetails = components['schemas']['OrganizationDetails']
export type OrganizationStatus = components['schemas']['OrganizationStatus']
export type OrganizationStatusChange = components['schemas']['OrganizationStatusChange']
export type OrganizationDuplicate = components['schemas']['OrganizationDuplicate']
export type AdminTeam = components['schemas']['AdminTeam']
export type TeamArchive = components['schemas']['TeamArchive']
export type CatalogKind = components['schemas']['CatalogKind']
export type CatalogEntityType = components['schemas']['CatalogEntityType']
export type AdminCatalogEntry = components['schemas']['AdminCatalogEntry']
export type PageAdminCatalogEntry = components['schemas']['PageAdminCatalogEntry']
export type AdminCatalogListParams = NonNullable<operations['listAdminCatalogEntries']['parameters']['query']>
export type CatalogEntryChange = components['schemas']['CatalogEntryChange']
export type PageCatalogChangeEvent = components['schemas']['PageCatalogChangeEvent']
export type CatalogChangeEvent = components['schemas']['CatalogChangeEvent']
export type CatalogChangeEventListParams = NonNullable<operations['listCatalogChangeEvents']['parameters']['query']>
export type CatalogImportManagerCandidate = components['schemas']['CatalogImportManagerCandidate']
export type CatalogImportMissingRecord = components['schemas']['CatalogImportMissingRecord']
export type SourceCode = components['schemas']['SourceCode']
export type DataSource = components['schemas']['DataSource']
export type SyncRun = components['schemas']['SyncRun']
export type SyncRunCreated = components['schemas']['SyncRunCreated']
export type SourceRecord = components['schemas']['SourceRecord']
export type SourceRecordApply = components['schemas']['SourceRecordApply']
export type SourceRecordApplyResult = components['schemas']['SourceRecordApplyResult']
export type SourceMappingOptions = components['schemas']['SourceMappingOptions']
export type RunKind = components['schemas']['RunKind']
export type SourceMapping = components['schemas']['SourceMapping']
export type SourceMappingUpdate = components['schemas']['SourceMappingUpdate']
export type PendingSourceRecord = components['schemas']['PendingSourceRecord']
export type SourceSettings = components['schemas']['SourceSettings']
export type SourceSettingsUpdate = components['schemas']['SourceSettingsUpdate']
export type SourceConnectionCheck = components['schemas']['SourceConnectionCheck']
export type LaborMarketSettings = components['schemas']['LaborMarketSettings']
export type LaborMarketSettingsUpdate = components['schemas']['LaborMarketSettingsUpdate']
export type LaborMarketMappings = components['schemas']['LaborMarketMappings']
export type LaborMarketDirection = components['schemas']['LaborMarketDirection']
export type LaborMarketMappingUpdate = components['schemas']['LaborMarketMappingUpdate']
export type CrmEventList = components['schemas']['CrmEventList']
export type CrmEventView = components['schemas']['CrmEventView']
export type CrmEventStatus = components['schemas']['CrmEventStatus']
export type CrmEventKind = components['schemas']['CrmEventKind']
export type CrmEventCounts = components['schemas']['CrmEventCounts']
export type CrmPreview = components['schemas']['CrmPreview']
export type CrmPreviewItem = components['schemas']['CrmPreviewItem']
export type CrmPreviewOutcome = components['schemas']['CrmPreviewOutcome']
export type CrmEventsRequeued = components['schemas']['CrmEventsRequeued']
export type SourceOrganizationCreate = components['schemas']['SourceOrganizationCreate']
export type SourceOrganizationCreated = components['schemas']['SourceOrganizationCreated']
export type InteractionSourceStatus = components['schemas']['InteractionSourceStatus']
export type SourcesRefresh = components['schemas']['SourcesRefresh']
export type TeacherTraining = components['schemas']['TeacherTraining']
export type TeacherTrainingCreate = components['schemas']['TeacherTrainingCreate']
export type TeacherTrainingCreated = components['schemas']['TeacherTrainingCreated']
export type InteractionCycle = components['schemas']['InteractionCycle']
export type CycleStart = components['schemas']['CycleStart']
export type CatalogImportProfile = 'AGREEMENT' | 'DIRECTION_PROGRAM' | 'VENDOR_CONTACTS'
export type CatalogImportRowTarget = {
  organizationId?: string | null
  managerProfileId?: string | null
  interactionId?: string | null
  productAgreementId?: string | null
}
export type CatalogImportMapping = {
  columns: Record<string, string>
  rowTargets?: Record<string, CatalogImportRowTarget>
  transferStatuses?: Record<string, string>
  unassignedTeamId?: string | null
}
export type CatalogImportSheet = {
  name: string
  headers: string[]
  hidden?: boolean
}
export type CatalogImportInspect = {
  sheets: CatalogImportSheet[]
}
export type CatalogImportPreview = {
  jobId: string
  importId: string
}
export type CatalogImportRow = {
  id: string
  sheetName: string
  rowNumber: number
  status: 'CREATE' | 'UPDATE' | 'UNCHANGED' | 'CONFLICT' | 'INVALID'
  fieldErrors: Record<string, string>
  oldValues: Record<string, string>
  newValues: Record<string, string>
  applied: boolean
  managerCandidates: CatalogImportManagerCandidate[]
}
export type CatalogImport = {
  id: string
  profile: CatalogImportProfile
  status: 'PREVIEWED' | 'APPLIED'
  version: number
  rows: CatalogImportRow[]
  createdAt: string
  updatedAt: string
  missingRecords: CatalogImportMissingRecord[]
}
export type CatalogImportApply = {
  version: number
  confirmedRowIds: string[]
  archiveAgreementIds?: string[]
}
export type CatalogImportApplyResult = {
  jobId: string
  importId: string
}
export type CatalogImportJob = {
  id: string
  importId: string
  action: 'PREVIEW' | 'APPLY'
  status: 'SUCCEEDED' | 'FAILED'
  result: string
  createdAt: string
  completedAt: string
}

export type ActivationRequest = components['schemas']['ActivationRequest']
export type AuditCategory = components['schemas']['AuditCategory']
export type AuditEntry = components['schemas']['AuditEntry']
export type PageAuditEntry = components['schemas']['PageAuditEntry']
export type AuditEventListParams = NonNullable<operations['listAuditEvents']['parameters']['query']>
export type AuditEventExportParams = NonNullable<operations['exportAuditEvents']['parameters']['query']>
export type SubjectQuery = components['schemas']['SubjectQuery']
export type SubjectExportFormat = components['schemas']['SubjectExportFormat']
export type SubjectSearchResult = components['schemas']['SubjectSearchResult']
export type SubjectContact = components['schemas']['SubjectContact']
export type SubjectProfile = components['schemas']['SubjectProfile']
export type SubjectMention = components['schemas']['SubjectMention']
export type SubjectAttachment = components['schemas']['SubjectAttachment']
export type SubjectLearner = components['schemas']['SubjectLearner']
export type ContactRectification = components['schemas']['ContactRectification']
export type ContactRestriction = components['schemas']['ContactRestriction']
export type AnonymizationRequest = components['schemas']['AnonymizationRequest']
export type AnonymizationResult = components['schemas']['AnonymizationResult']
export type PersonalDataReason = components['schemas']['PersonalDataReason']
export type RetentionPolicy = components['schemas']['RetentionPolicy']
export type RetentionRun = components['schemas']['RetentionRun']
export type VendorContact = components['schemas']['VendorContact']
export type VendorContactList = components['schemas']['VendorContactList']
export type VendorContactChange = components['schemas']['VendorContactChange']
export type VendorContactCard = components['schemas']['VendorContactCard']
export type PaidOrderUpload = components['schemas']['PaidOrderUpload']
export type LastPaidOrderUpload = components['schemas']['LastPaidOrderUpload']
export type LearnerIntake = components['schemas']['LearnerIntake']
export type LearnerFieldCode = components['schemas']['LearnerFieldCode']
export type LearnerFieldGroup = components['schemas']['LearnerFieldGroup']
export type LmsStatus = components['schemas']['LmsStatus']
export type EnrolmentStreams = components['schemas']['EnrolmentStreams']
export type EnrolmentStream = components['schemas']['EnrolmentStream']
export type EnrolmentCounters = components['schemas']['EnrolmentCounters']
export type EnrolmentStreamUpdate = components['schemas']['EnrolmentStreamUpdate']
export type EnrolmentStreamLearners = components['schemas']['EnrolmentStreamLearners']
export type StreamLearner = components['schemas']['StreamLearner']
export type RosterExport = components['schemas']['RosterExport']
export type RosterSummary = components['schemas']['RosterSummary']
export type RosterScope = components['schemas']['RosterScope']
export type LmsRosterRequest = components['schemas']['LmsRosterRequest']
export type RosterExportMarked = components['schemas']['RosterExportMarked']
export type LearnerSearch = components['schemas']['LearnerSearch']
export type LearnerSummary = components['schemas']['LearnerSummary']
export type LearnerEnrolment = components['schemas']['LearnerEnrolment']
export type LearnerCard = components['schemas']['LearnerCard']
export type LearnerReveal = components['schemas']['LearnerReveal']
export type LearnerRevealed = components['schemas']['LearnerRevealed']
export type LearnerUpdate = components['schemas']['LearnerUpdate']
export type LearnerMove = components['schemas']['LearnerMove']
export type LearnerMoveResult = components['schemas']['LearnerMoveResult']
export type LearnerHistoryEntry = components['schemas']['LearnerHistoryEntry']
export type QuestionnaireImport = components['schemas']['QuestionnaireImport']
export type QuestionnaireRow = components['schemas']['QuestionnaireRow']
export type QuestionnaireIssue = components['schemas']['QuestionnaireIssue']
export type RosterFileDownload = {
  blob: Blob
  fileName: string
  exportId: string
}
export type TeacherRoster = components['schemas']['TeacherRoster']
export type TeacherRosterMember = components['schemas']['TeacherRosterMember']
export type TeacherRosterCreate = components['schemas']['TeacherRosterCreate']
export type TeacherRosterFileRequest = components['schemas']['TeacherRosterFileRequest']
export type TeacherRosterMarked = components['schemas']['TeacherRosterMarked']
export type TeacherRosterFileDownload = RosterFileDownload & {
  rows: number
  skipped: number
}

const querySuffix = (query: Record<string, string | number | boolean | null | undefined>) => {
  const searchParams = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      searchParams.set(key, String(value))
    }
  })
  return searchParams.size === 0 ? '' : `?${searchParams.toString()}`
}

export class ApiError extends Error {
  readonly code: string
  readonly requestId: string
  readonly currentVersion: number | undefined
  readonly fieldErrors: Record<string, string> | undefined
  readonly status: number

  constructor(payload: ApiErrorPayload, status: number) {
    super(payload.message)
    this.code = payload.code
    this.requestId = payload.requestId
    this.currentVersion = payload.currentVersion
    this.fieldErrors = payload.fieldErrors
    this.status = status
  }
}

export const isAccessDenied = (error: unknown): boolean => (
  error instanceof ApiError && (error.status === 401 || error.status === 403)
)

const parseJson = (text: string): unknown => {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

const isApiErrorPayload = (value: unknown): value is ApiErrorPayload =>
  typeof value === 'object' && value !== null && 'code' in value && 'message' in value && 'requestId' in value

const fileNameFromContentDisposition = (value: string | null): string => {
  const match = value?.match(/filename\*=UTF-8''([^;]+)/)
  return match ? decodeURIComponent(match[1]) : 'roster.xlsx'
}

// Модуль MAX: привязка аккаунта в сессии CRM и экран администратора.
export type MaxLinkStatus = components['schemas']['MaxLinkStatus']
export type MaxLinkStart = components['schemas']['MaxLinkStart']
export type MaxPreferences = components['schemas']['MaxPreferences']
export type MaxSettings = components['schemas']['MaxSettings']
export type MaxSettingsUpdate = components['schemas']['MaxSettingsUpdate']
export type MaxCheck = components['schemas']['MaxCheck']
export type MaxAdminLink = components['schemas']['MaxAdminLink']
export type MailPreferences = components['schemas']['MailPreferences']
export type MailTopic = components['schemas']['MailTopic']
export type MailSettings = components['schemas']['MailSettings']
export type MailSettingsUpdate = components['schemas']['MailSettingsUpdate']
export type MailCheck = components['schemas']['MailCheck']
export type MailJournalRow = components['schemas']['MailJournalRow']
export type MailJournalPage = components['schemas']['MailJournalPage']
export type MailLetter = components['schemas']['MailLetter']
export type MailInvitationRedeemed = components['schemas']['MailInvitationRedeemed']

export class ApiClient {
  private csrf: CsrfToken | null = null

  async me(): Promise<Me> {
    return this.request<Me>('/api/me')
  }

  async config(): Promise<Config> {
    return this.request<Config>('/api/config')
  }

  async refreshCsrf(): Promise<CsrfToken> {
    const csrf = await this.request<CsrfToken>('/api/csrf')
    this.csrf = csrf
    return csrf
  }

  async listOrganizations(query: OrganizationListParams = {}): Promise<PageOrganization> {
    const searchParams = new URLSearchParams()
    if (query.page !== undefined) {
      searchParams.set('page', query.page.toString())
    }
    if (query.size !== undefined) {
      searchParams.set('size', query.size.toString())
    }
    if (query.sort !== undefined) {
      searchParams.set('sort', query.sort)
    }
    if (query.q !== undefined && query.q.trim().length > 0) {
      searchParams.set('q', query.q.trim())
    }
    if (query.requiresAssignment === true) {
      searchParams.set('requiresAssignment', 'true')
    }
    if (query.status !== undefined) {
      searchParams.set('status', query.status)
    }
    if (query.excludeService === true) {
      searchParams.set('excludeService', 'true')
    }
    const suffix = searchParams.size === 0 ? '' : `?${searchParams.toString()}`
    return this.request<PageOrganization>(`/api/organizations${suffix}`)
  }

  async getOrganization(id: Organization['id']): Promise<Organization> {
    return this.request<Organization>(`/api/organizations/${encodeURIComponent(id)}`)
  }

  async listOrganizationAssignmentOptions(id: Organization['id']): Promise<OrganizationAssignmentCandidate[]> {
    return this.request<OrganizationAssignmentCandidate[]>(`/api/organizations/${encodeURIComponent(id)}/assignment-options`)
  }

  async listOrganizationAssignmentEvents(id: Organization['id']): Promise<OrganizationAssignmentEvent[]> {
    return this.request<OrganizationAssignmentEvent[]>(`/api/organizations/${encodeURIComponent(id)}/assignment-events`)
  }

  async listOrganizationHistory(id: Organization['id'], query: OrganizationHistoryQuery = {}): Promise<PageOrganizationHistory> {
    const searchParams = new URLSearchParams()
    query.kinds?.forEach((kind) => searchParams.append('kinds', kind))
    if (query.from) searchParams.set('from', query.from)
    if (query.to) searchParams.set('to', query.to)
    if (query.page !== undefined) searchParams.set('page', query.page.toString())
    if (query.size !== undefined) searchParams.set('size', query.size.toString())
    const suffix = searchParams.size === 0 ? '' : `?${searchParams.toString()}`
    return this.request<PageOrganizationHistory>(`/api/organizations/${encodeURIComponent(id)}/history${suffix}`)
  }

  async assignOrganization(
    id: Organization['id'],
    payload: OrganizationAssignment,
    idempotencyKey: string
  ): Promise<OrganizationAssignmentResult> {
    return this.command<OrganizationAssignmentResult>(
      `/api/organizations/${encodeURIComponent(id)}/assignment`,
      payload,
      idempotencyKey
    )
  }

  async listOrganizationContacts(id: Organization['id']): Promise<Contact[]> {
    return this.request<Contact[]>(`/api/organizations/${encodeURIComponent(id)}/contacts`)
  }

  async createOrganizationContact(
    organizationId: Organization['id'],
    payload: ContactCreate,
    idempotencyKey: string
  ): Promise<Contact> {
    return this.command<Contact>(`/api/organizations/${encodeURIComponent(organizationId)}/contacts`, payload, idempotencyKey)
  }

  async updateOrganizationContact(
    organizationId: Organization['id'],
    contactId: Contact['id'],
    payload: ContactUpdate,
    idempotencyKey: string
  ): Promise<Contact> {
    return this.command<Contact>(
      `/api/organizations/${encodeURIComponent(organizationId)}/contacts/${encodeURIComponent(contactId)}`,
      payload,
      idempotencyKey,
      'PATCH'
    )
  }

  async listOrganizationContactEvents(organizationId: Organization['id'], contactId: Contact['id']): Promise<ContactEvent[]> {
    return this.request<ContactEvent[]>(
      `/api/organizations/${encodeURIComponent(organizationId)}/contacts/${encodeURIComponent(contactId)}/events`
    )
  }

  async listPrograms(query: CatalogListParams = {}): Promise<PageCatalogLookup> {
    return this.listCatalog('/api/programs', query)
  }

  async listProducts(query: CatalogListParams = {}): Promise<PageCatalogLookup> {
    return this.listCatalog('/api/products', query)
  }

  async listDirections(query: CatalogListParams = {}): Promise<PageCatalogLookup> {
    return this.listCatalog('/api/directions', query)
  }

  async listInteractions(query: InteractionListParams): Promise<PageInteraction> {
    const searchParams = new URLSearchParams()
    if (query.organizationId !== undefined) {
      searchParams.set('organizationId', query.organizationId)
    }
    if (query.q !== undefined && query.q.trim().length > 0) {
      searchParams.set('q', query.q.trim())
    }
    if (query.due !== undefined) {
      searchParams.set('due', query.due)
    }
    if (query.stage !== undefined && query.stage.length > 0) {
      searchParams.set('stage', query.stage)
    }
    if (query.status !== undefined) {
      searchParams.set('status', query.status)
    }
    if (query.flag !== undefined) {
      searchParams.set('flag', query.flag)
    }
    if (query.licenseExpiresBy !== undefined) {
      searchParams.set('licenseExpiresBy', query.licenseExpiresBy.toString())
    }
    if (query.responsible !== undefined && query.responsible.length > 0) {
      searchParams.set('responsible', query.responsible)
    }
    if (query.minDaysOnStage !== undefined) {
      searchParams.set('minDaysOnStage', query.minDaysOnStage.toString())
    }
    if (query.noContactDays !== undefined) {
      searchParams.set('noContactDays', query.noContactDays.toString())
    }
    for (const name of ['kindId', 'programId', 'productId'] as const) {
      const value = query[name]
      if (value !== undefined && value.length > 0) {
        searchParams.set(name, value)
      }
    }
    if (query.page !== undefined) {
      searchParams.set('page', query.page.toString())
    }
    if (query.size !== undefined) {
      searchParams.set('size', query.size.toString())
    }
    if (query.sort !== undefined) {
      searchParams.set('sort', query.sort)
    }
    return this.request<PageInteraction>(`/api/interactions?${searchParams.toString()}`)
  }

  async createInteraction(payload: InteractionCreate, idempotencyKey: string): Promise<Interaction> {
    return this.command<Interaction>('/api/interactions', payload, idempotencyKey)
  }

  async getInteraction(id: Interaction['id']): Promise<Interaction> {
    return this.request<Interaction>(`/api/interactions/${encodeURIComponent(id)}`)
  }

  async updateInteractionPlan(
    id: Interaction['id'],
    payload: InteractionPlanUpdate,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}`, payload, idempotencyKey, 'PATCH')
  }

  async listInteractionEvents(id: Interaction['id']): Promise<InteractionEvent[]> {
    return this.request<InteractionEvent[]>(`/api/interactions/${encodeURIComponent(id)}/events`)
  }

  async listInteractionLearningSnapshots(id: Interaction['id']): Promise<LearningSnapshot[]> {
    return this.request<LearningSnapshot[]>(`/api/interactions/${encodeURIComponent(id)}/learning-snapshots`)
  }

  async refreshInteractionLearningSnapshots(id: Interaction['id']): Promise<LearningSnapshotsRefresh> {
    return this.post<LearningSnapshotsRefresh>(`/api/interactions/${encodeURIComponent(id)}/learning-snapshots/sync`)
  }

  async uploadInteractionAttachment(
    id: Interaction['id'],
    payload: AttachmentUpload,
    idempotencyKey: string
  ): Promise<Attachment> {
    if (idempotencyKey.length === 0) {
      throw new Error('An idempotency key is required for a command.')
    }
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const formData = new FormData()
    formData.set('file', payload.file)
    formData.set('stageId', payload.stageId)
    if (payload.kind !== undefined) {
      formData.set('kind', payload.kind)
    }
    if (payload.replacesId !== undefined) {
      formData.set('replacesId', payload.replacesId)
    }
    return this.request<Attachment>(`/api/interactions/${encodeURIComponent(id)}/attachments`, {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token,
        'Idempotency-Key': idempotencyKey
      },
      body: formData
    })
  }

  async uploadPaidOrders(file: File, idempotencyKey: string, confirmNewStreams = false): Promise<PaidOrderUpload> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const formData = new FormData()
    formData.set('file', file)
    if (confirmNewStreams) {
      formData.set('confirmNewStreams', 'true')
    }
    return this.request<PaidOrderUpload>('/api/enrolment/paid-orders', {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token,
        'Idempotency-Key': idempotencyKey
      },
      body: formData
    })
  }

  async getLastPaidOrderUpload(): Promise<LastPaidOrderUpload | null> {
    return (await this.request<LastPaidOrderUpload | undefined>('/api/enrolment/paid-orders/last')) ?? null
  }

  async listEnrolmentStreams(): Promise<EnrolmentStreams> {
    return this.request<EnrolmentStreams>('/api/enrolment/streams')
  }

  async updateEnrolmentStream(
    id: EnrolmentStream['id'],
    payload: EnrolmentStreamUpdate,
    idempotencyKey: string
  ): Promise<EnrolmentStream> {
    return this.command<EnrolmentStream>(`/api/enrolment/streams/${encodeURIComponent(id)}`, payload, idempotencyKey, 'PATCH')
  }

  async listEnrolmentStreamLearners(id: EnrolmentStream['id']): Promise<EnrolmentStreamLearners> {
    return this.request<EnrolmentStreamLearners>(`/api/enrolment/streams/${encodeURIComponent(id)}/learners`)
  }

  async previewQuestionnaireImport(id: EnrolmentStream['id'], file: File): Promise<QuestionnaireImport> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const formData = new FormData()
    formData.set('file', file)
    return this.request<QuestionnaireImport>(`/api/enrolment/streams/${encodeURIComponent(id)}/questionnaire-imports/preview`, {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: formData
    })
  }

  async applyQuestionnaireImport(
    id: EnrolmentStream['id'],
    file: File,
    fingerprint: string,
    idempotencyKey: string
  ): Promise<QuestionnaireImport> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const formData = new FormData()
    formData.set('file', file)
    formData.set('fingerprint', fingerprint)
    return this.request<QuestionnaireImport>(`/api/enrolment/streams/${encodeURIComponent(id)}/questionnaire-imports/apply`, {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token,
        'Idempotency-Key': idempotencyKey
      },
      body: formData
    })
  }

  async exportLmsRoster(id: EnrolmentStream['id'], payload: LmsRosterRequest): Promise<RosterFileDownload> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const response = await this.fetchApi(`/api/enrolment/streams/${encodeURIComponent(id)}/lms-roster`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, text/csv, application/json',
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: JSON.stringify(payload)
    })
    if (!response.ok) {
      return this.throwResponseError(response)
    }
    return {
      blob: await response.blob(),
      fileName: fileNameFromContentDisposition(response.headers.get('Content-Disposition')),
      exportId: response.headers.get('X-Roster-Export-Id') ?? ''
    }
  }

  async markRosterExportTransferred(exportId: RosterExport['exportId'], idempotencyKey: string): Promise<RosterExportMarked> {
    return this.command<RosterExportMarked>(
      `/api/enrolment/roster-exports/${encodeURIComponent(exportId)}/transferred`,
      undefined,
      idempotencyKey
    )
  }

  async searchLearners(payload: LearnerSearch): Promise<LearnerSummary[]> {
    return this.post<LearnerSummary[]>('/api/enrolment/learners/search', payload)
  }

  async getLearner(id: LearnerCard['id']): Promise<LearnerCard> {
    return this.request<LearnerCard>(`/api/enrolment/learners/${encodeURIComponent(id)}`)
  }

  async updateLearner(id: LearnerCard['id'], payload: LearnerUpdate, idempotencyKey: string): Promise<LearnerCard> {
    return this.command<LearnerCard>(`/api/enrolment/learners/${encodeURIComponent(id)}`, payload, idempotencyKey, 'PATCH')
  }

  async revealLearnerFields(id: LearnerCard['id'], payload: LearnerReveal): Promise<LearnerRevealed> {
    return this.post<LearnerRevealed>(`/api/enrolment/learners/${encodeURIComponent(id)}/reveal`, payload)
  }

  async moveLearnerEnrolments(id: LearnerCard['id'], payload: LearnerMove, idempotencyKey: string): Promise<LearnerMoveResult> {
    return this.command<LearnerMoveResult>(`/api/enrolment/learners/${encodeURIComponent(id)}/move-enrolments`, payload, idempotencyKey)
  }

  async getLearnerHistory(id: LearnerCard['id']): Promise<LearnerHistoryEntry[]> {
    return this.request<LearnerHistoryEntry[]>(`/api/enrolment/learners/${encodeURIComponent(id)}/history`)
  }

  async getAttachment(id: Attachment['id']): Promise<Attachment> {
    return this.request<Attachment>(`/api/attachments/${encodeURIComponent(id)}`)
  }

  async downloadAttachment(id: Attachment['id']): Promise<Blob> {
    return this.download(`/api/attachments/${encodeURIComponent(id)}/download`)
  }

  async transitionInteraction(
    id: Interaction['id'],
    payload: InteractionTransition,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/transitions`, payload, idempotencyKey)
  }

  async commentInteraction(
    id: Interaction['id'],
    payload: InteractionComment,
    idempotencyKey: string
  ): Promise<InteractionCommentResult> {
    return this.command<InteractionCommentResult>(`/api/interactions/${encodeURIComponent(id)}/comments`, payload, idempotencyKey)
  }

  async changeInteractionStatus(
    id: Interaction['id'],
    payload: InteractionStatusChange,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/status`, payload, idempotencyKey)
  }

  async updateInteractionFlags(
    id: Interaction['id'],
    payload: InteractionFlagsUpdate,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/flags`, payload, idempotencyKey)
  }

  async listInteractionIssues(id: Interaction['id']): Promise<InteractionIssueList> {
    return this.request<InteractionIssueList>(`/api/interactions/${encodeURIComponent(id)}/issues`)
  }

  async createInteractionIssue(id: Interaction['id'], payload: InteractionIssueRequest, idempotencyKey: string): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/issues`, payload, idempotencyKey)
  }

  async updateInteractionIssue(
    id: Interaction['id'],
    issueId: InteractionIssue['id'],
    payload: InteractionIssueRequest,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(
      `/api/interactions/${encodeURIComponent(id)}/issues/${encodeURIComponent(issueId)}`,
      payload,
      idempotencyKey,
      'PATCH'
    )
  }

  async resolveInteractionIssue(
    id: Interaction['id'],
    issueId: InteractionIssue['id'],
    payload: InteractionIssueResolution,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(
      `/api/interactions/${encodeURIComponent(id)}/issues/${encodeURIComponent(issueId)}/resolution`,
      payload,
      idempotencyKey
    )
  }

  async commentIssueAsResponsible(issueId: InteractionIssue['id'], payload: InteractionIssueNote, idempotencyKey: string): Promise<InteractionIssue> {
    return this.command<InteractionIssue>(`/api/issues/${encodeURIComponent(issueId)}/comments`, payload, idempotencyKey)
  }

  async resolveIssueAsResponsible(issueId: InteractionIssue['id'], payload: InteractionIssueNote, idempotencyKey: string): Promise<InteractionIssue> {
    return this.command<InteractionIssue>(`/api/issues/${encodeURIComponent(issueId)}/resolution`, payload, idempotencyKey)
  }

  async getWorkNews(days?: number): Promise<WorkNews> {
    return this.request<WorkNews>(`/api/work/news${days === undefined ? '' : `?days=${days}`}`)
  }

  async markWorkNewsViewed(eventId: WorkNewsItem['eventId']): Promise<void> {
    return this.send<void>(`/api/work/news/${encodeURIComponent(eventId)}/viewed`, 'PUT')
  }

  async listIssues(query: IssueListParams): Promise<InteractionIssuePage> {
    return this.request<InteractionIssuePage>(`/api/issues${querySuffix(query)}`)
  }

  async downloadIssues(query: Omit<IssueListParams, 'page' | 'size'>): Promise<Blob> {
    return this.download(`/api/issues/file${querySuffix(query)}`)
  }

  async editInteractionStages(
    id: Interaction['id'],
    payload: InteractionStageEdit,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/stage-edits`, payload, idempotencyKey)
  }

  async completeInteractionStage(
    id: Interaction['id'],
    payload: InteractionStageCompletionRequest,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/stage-completions`, payload, idempotencyKey)
  }

  async clearInteractionStageCompletion(
    id: Interaction['id'],
    stageId: InteractionStage['id'],
    version: number,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(
      `/api/interactions/${encodeURIComponent(id)}/stage-completions/${encodeURIComponent(stageId)}?${new URLSearchParams({ version: version.toString() })}`,
      undefined,
      idempotencyKey,
      'DELETE'
    )
  }

  async listAvailableWorkflowTemplates(query: WorkflowTemplateListParams = {}): Promise<PageWorkflowTemplate> {
    return this.listWorkflowTemplatesAt('/api/workflow-templates', query)
  }

  async listWorkflowTemplates(query: WorkflowTemplateListParams = {}): Promise<PageWorkflowTemplate> {
    return this.listWorkflowTemplatesAt('/api/admin/workflow-templates', query)
  }

  async createWorkflowTemplate(payload: WorkflowTemplateCreate, idempotencyKey: string): Promise<WorkflowTemplate> {
    return this.command<WorkflowTemplate>('/api/admin/workflow-templates', payload, idempotencyKey)
  }

  async getWorkflowTemplate(id: WorkflowTemplate['id']): Promise<WorkflowTemplate> {
    return this.request<WorkflowTemplate>(`/api/admin/workflow-templates/${encodeURIComponent(id)}`)
  }

  async updateWorkflowTemplate(
    id: WorkflowTemplate['id'],
    payload: WorkflowTemplateUpdate,
    idempotencyKey: string
  ): Promise<WorkflowTemplate> {
    return this.command<WorkflowTemplate>(
      `/api/admin/workflow-templates/${encodeURIComponent(id)}`,
      payload,
      idempotencyKey,
      'PATCH'
    )
  }

  async deleteWorkflowTemplate(
    id: WorkflowTemplate['id'],
    version: number,
    idempotencyKey: string
  ): Promise<void> {
    return this.command<void>(
      `/api/admin/workflow-templates/${encodeURIComponent(id)}?${new URLSearchParams({ version: version.toString() })}`,
      undefined,
      idempotencyKey,
      'DELETE'
    )
  }

  async listCrmProfiles(query: CrmProfileListParams = {}): Promise<PageCrmProfile> {
    return this.request<PageCrmProfile>(`/api/admin/crm-profiles${querySuffix(query)}`)
  }

  async exportCrmProfiles(query: CrmProfileExportParams = {}): Promise<Blob> {
    return this.download(`/api/admin/crm-profiles/export${querySuffix(query)}`)
  }

  async listCrmProfileEvents(id: CrmProfile['id']): Promise<CrmProfileEvent[]> {
    return this.request<CrmProfileEvent[]>(`/api/admin/crm-profiles/${encodeURIComponent(id)}/events`)
  }

  async listTeams(): Promise<AdminTeam[]> {
    return this.request<AdminTeam[]>('/api/admin/teams')
  }

  async createTeam(payload: TeamCreate, idempotencyKey: string): Promise<Team> {
    return this.command<Team>('/api/admin/teams', payload, idempotencyKey)
  }

  async renameTeam(id: Team['id'], payload: TeamUpdate, idempotencyKey: string): Promise<Team> {
    return this.command<Team>(`/api/admin/teams/${encodeURIComponent(id)}`, payload, idempotencyKey, 'PATCH')
  }

  async listAdminOrganizations(query: AdminOrganizationListParams = {}): Promise<PageAdminOrganization> {
    const searchParams = new URLSearchParams()
    if (query.page !== undefined) {
      searchParams.set('page', query.page.toString())
    }
    if (query.size !== undefined) {
      searchParams.set('size', query.size.toString())
    }
    if (query.q !== undefined && query.q.trim().length > 0) {
      searchParams.set('q', query.q.trim())
    }
    if (query.status !== undefined) {
      searchParams.set('status', query.status)
    }
    const suffix = searchParams.size === 0 ? '' : `?${searchParams.toString()}`
    return this.request<PageAdminOrganization>(`/api/admin/organizations${suffix}`)
  }

  async transferOrganizationTeam(
    id: AdminOrganization['id'],
    payload: OrganizationTeamTransfer,
    idempotencyKey: string
  ): Promise<AdminOrganization> {
    return this.command<AdminOrganization>(
      `/api/admin/organizations/${encodeURIComponent(id)}/team`,
      payload,
      idempotencyKey,
      'PATCH'
    )
  }

  async updateCrmProfile(
    id: CrmProfile['id'],
    payload: CrmProfileUpdate,
    idempotencyKey: string
  ): Promise<CrmProfile> {
    return this.command<CrmProfile>(
      `/api/admin/crm-profiles/${encodeURIComponent(id)}`,
      payload,
      idempotencyKey,
      'PATCH'
    )
  }

  async syncCrmProfileAccount(id: CrmProfile['id']): Promise<CrmProfile> {
    return this.post<CrmProfile>(`/api/admin/crm-profiles/${encodeURIComponent(id)}/account-sync`)
  }

  async migrateCrmRolesToKeycloak(): Promise<KeycloakRoleMigration> {
    return this.post<KeycloakRoleMigration>('/api/admin/crm-profiles/keycloak-roles')
  }

  async createEmployeeAccount(payload: NewEmployee, idempotencyKey: string): Promise<AccountCredentials> {
    return this.command<AccountCredentials>('/api/admin/crm-profiles', payload, idempotencyKey)
  }

  async resetCrmProfilePassword(id: CrmProfile['id'], idempotencyKey: string): Promise<AccountCredentials> {
    return this.command<AccountCredentials>(
      `/api/admin/crm-profiles/${encodeURIComponent(id)}/account-password-reset`, undefined, idempotencyKey
    )
  }

  async endCrmProfileSessions(id: CrmProfile['id'], idempotencyKey: string): Promise<CrmProfile> {
    return this.command<CrmProfile>(`/api/admin/crm-profiles/${encodeURIComponent(id)}/account-logout`, undefined, idempotencyKey)
  }

  async resetCrmProfileSecondFactor(id: CrmProfile['id'], idempotencyKey: string): Promise<CrmProfile> {
    return this.command<CrmProfile>(`/api/admin/crm-profiles/${encodeURIComponent(id)}/account-second-factor-reset`, undefined, idempotencyKey)
  }

  async changeCrmProfileEmail(id: CrmProfile['id'], email: string, idempotencyKey: string): Promise<CrmProfile> {
    return this.command<CrmProfile>(`/api/admin/crm-profiles/${encodeURIComponent(id)}/account-email`, { email }, idempotencyKey, 'PUT')
  }

  async listCrmProfileOrganizations(id: CrmProfile['id']): Promise<CrmProfileOrganizations> {
    return this.request<CrmProfileOrganizations>(`/api/admin/crm-profiles/${encodeURIComponent(id)}/organizations`)
  }

  async getCrmProfileLoginLockout(id: CrmProfile['id']): Promise<LoginLockoutStatus> {
    return this.request<LoginLockoutStatus>(`/api/admin/crm-profiles/${encodeURIComponent(id)}/login-lockout`)
  }

  async releaseCrmProfileLoginLockout(id: CrmProfile['id']): Promise<LoginLockoutStatus> {
    return this.post<LoginLockoutStatus>(`/api/admin/crm-profiles/${encodeURIComponent(id)}/login-lockout-release`)
  }

  async getBackupStatus(): Promise<BackupStatus> {
    return this.request<BackupStatus>('/api/admin/backup-status')
  }

  async listAgentPlans(status: 'PENDING' | 'ALL' = 'PENDING', kind?: AgentPlanKind): Promise<AgentPlanListItem[]> {
    const searchParams = new URLSearchParams({ status })
    if (kind !== undefined) {
      searchParams.set('kind', kind)
    }
    return this.request<AgentPlanListItem[]>(`/api/agent-plans?${searchParams.toString()}`)
  }

  async getAgentPlan(id: AgentPlan['id']): Promise<AgentPlan> {
    return this.request<AgentPlan>(`/api/agent-plans/${encodeURIComponent(id)}`)
  }

  async applyAgentPlan(id: AgentPlan['id']): Promise<AgentPlan> {
    return this.post<AgentPlan>(`/api/agent-plans/${encodeURIComponent(id)}/apply`)
  }

  async rejectAgentPlan(id: AgentPlan['id']): Promise<AgentPlan> {
    return this.post<AgentPlan>(`/api/agent-plans/${encodeURIComponent(id)}/reject`)
  }

  async requestProfileActivation(): Promise<ActivationRequest> {
    return this.post<ActivationRequest>('/api/me/activation-request')
  }

  async listAuditEvents(query: AuditEventListParams = {}): Promise<PageAuditEntry> {
    return this.request<PageAuditEntry>(`/api/admin/audit-events${querySuffix(query)}`)
  }

  async exportAuditEvents(query: AuditEventExportParams): Promise<Blob> {
    return this.download(`/api/admin/audit-events/export${querySuffix(query)}`)
  }

  async searchPersonalData(payload: SubjectQuery): Promise<SubjectSearchResult> {
    return this.post<SubjectSearchResult>('/api/admin/personal-data/search', payload)
  }

  async exportPersonalData(payload: SubjectQuery, format: SubjectExportFormat): Promise<Blob> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const response = await this.fetchApi(`/api/admin/personal-data/export?format=${format}`, {
      method: 'POST',
      headers: {
        Accept: 'application/octet-stream',
        'Content-Type': 'application/json',
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: JSON.stringify(payload)
    })
    if (!response.ok) {
      return this.throwResponseError(response)
    }
    return response.blob()
  }

  async rectifyPersonalDataContact(
    id: SubjectContact['id'],
    payload: ContactRectification,
    idempotencyKey: string
  ): Promise<SubjectContact> {
    return this.command<SubjectContact>(
      `/api/admin/personal-data/contacts/${encodeURIComponent(id)}`,
      payload,
      idempotencyKey,
      'PATCH'
    )
  }

  async restrictPersonalDataContact(
    id: SubjectContact['id'],
    payload: ContactRestriction,
    idempotencyKey: string
  ): Promise<SubjectContact> {
    return this.command<SubjectContact>(
      `/api/admin/personal-data/contacts/${encodeURIComponent(id)}/restriction`,
      payload,
      idempotencyKey
    )
  }

  async restrictPersonalDataLearner(
    id: SubjectLearner['id'],
    payload: ContactRestriction,
    idempotencyKey: string
  ): Promise<SubjectLearner> {
    return this.command<SubjectLearner>(
      `/api/admin/personal-data/learners/${encodeURIComponent(id)}/restriction`,
      payload,
      idempotencyKey
    )
  }

  async anonymizePersonalData(payload: AnonymizationRequest, idempotencyKey: string): Promise<AnonymizationResult> {
    return this.command<AnonymizationResult>('/api/admin/personal-data/anonymization', payload, idempotencyKey)
  }

  async previewPersonalDataAnonymization(payload: AnonymizationRequest): Promise<AnonymizationResult> {
    return this.post<AnonymizationResult>('/api/admin/personal-data/anonymization/preview', payload)
  }

  async getRetentionPolicy(): Promise<RetentionPolicy> {
    return this.request<RetentionPolicy>('/api/admin/retention')
  }

  async runRetention(): Promise<RetentionRun> {
    return this.post<RetentionRun>('/api/admin/retention/run')
  }

  async inspectCatalogImport(file: File): Promise<CatalogImportInspect> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const formData = new FormData()
    formData.set('file', file)
    return this.request<CatalogImportInspect>('/api/imports/inspect', {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: formData
    })
  }

  async previewCatalogImport(
    file: File,
    profile: CatalogImportProfile,
    sheet: string,
    mapping: CatalogImportMapping
  ): Promise<CatalogImportPreview> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const formData = new FormData()
    formData.set('file', file)
    formData.set('profile', profile)
    formData.set('sheet', sheet)
    formData.set('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }))
    return this.request<CatalogImportPreview>('/api/imports/preview', {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: formData
    })
  }

  async getCatalogImport(id: string): Promise<CatalogImport> {
    return this.request<CatalogImport>(`/api/imports/${encodeURIComponent(id)}`)
  }

  async applyCatalogImport(
    id: string,
    payload: CatalogImportApply,
    idempotencyKey: string
  ): Promise<CatalogImportApplyResult> {
    return this.command<CatalogImportApplyResult>(
      `/api/imports/${encodeURIComponent(id)}/apply`,
      payload,
      idempotencyKey
    )
  }

  async getCatalogImportJob(id: string): Promise<CatalogImportJob> {
    return this.request<CatalogImportJob>(`/api/jobs/${encodeURIComponent(id)}`)
  }

  async listReportManagers(): Promise<ReportManagerOption[]> {
    return this.request<ReportManagerOption[]>('/api/report-filters/managers')
  }

  async listReportStages(): Promise<string[]> {
    return this.request<string[]>('/api/report-filters/stages')
  }

  async listSavedReports(): Promise<SavedReport[]> {
    return this.request<SavedReport[]>('/api/saved-reports')
  }

  async createSavedReport(payload: SavedReportRequest, idempotencyKey: string): Promise<SavedReport> {
    return this.command<SavedReport>('/api/saved-reports', payload, idempotencyKey)
  }

  async updateSavedReport(id: SavedReport['id'], payload: SavedReportRequest, idempotencyKey: string): Promise<SavedReport> {
    return this.command<SavedReport>(`/api/saved-reports/${encodeURIComponent(id)}`, payload, idempotencyKey, 'PATCH')
  }

  async deleteSavedReport(id: SavedReport['id'], version: number, idempotencyKey: string): Promise<void> {
    return this.command<void>(
      `/api/saved-reports/${encodeURIComponent(id)}?${new URLSearchParams({ version: version.toString() })}`,
      undefined,
      idempotencyKey,
      'DELETE'
    )
  }

  async previewReport(payload: ReportPreviewRequest, page: number, size: number): Promise<ReportPreview> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const searchParams = new URLSearchParams({ page: page.toString(), size: size.toString() })
    return this.request<ReportPreview>(`/api/reports/preview?${searchParams.toString()}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: JSON.stringify(payload)
    })
  }

  async reportStatistics(payload: StatisticsRequest): Promise<StatisticsResult> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    return this.request<StatisticsResult>('/api/statistics', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: JSON.stringify(payload)
    })
  }

  async createReportJob(payload: ReportRequest, idempotencyKey: string): Promise<ReportJobCreated> {
    return this.command<ReportJobCreated>('/api/reports', payload, idempotencyKey)
  }

  async getReportJob(id: ReportJob['id']): Promise<ReportJob> {
    return this.request<ReportJob>(`/api/report-jobs/${encodeURIComponent(id)}`)
  }

  async listRecentReportJobs(): Promise<ReportJob[]> {
    return this.request<ReportJob[]>('/api/report-jobs')
  }

  async downloadReportJobResult(id: ReportJob['id']): Promise<Blob> {
    return this.download(`/api/report-jobs/${encodeURIComponent(id)}/result`)
  }

  async listDataSources(): Promise<DataSource[]> {
    return this.request<DataSource[]>('/api/admin/sources')
  }

  async startSourceSync(source: SourceCode): Promise<SyncRunCreated> {
    return this.post<SyncRunCreated>(`/api/admin/sources/${encodeURIComponent(source)}/sync`)
  }

  async listSourceProblemRecords(): Promise<SourceRecord[]> {
    return this.request<SourceRecord[]>('/api/admin/source-records')
  }

  async listSourceMappingOptions(): Promise<SourceMappingOptions> {
    return this.request<SourceMappingOptions>('/api/admin/source-mapping-options')
  }

  async applySourceRecord(id: SourceRecord['id'], payload: SourceRecordApply): Promise<SourceRecordApplyResult> {
    return this.post<SourceRecordApplyResult>(`/api/admin/source-records/${encodeURIComponent(id)}/apply`, payload)
  }

  async createOrganization(payload: OrganizationDetails, idempotencyKey: string): Promise<Organization> {
    return this.command<Organization>('/api/organizations', payload, idempotencyKey)
  }

  async updateOrganization(id: Organization['id'], payload: OrganizationDetails, idempotencyKey: string): Promise<Organization> {
    return this.command<Organization>(`/api/organizations/${encodeURIComponent(id)}`, payload, idempotencyKey, 'PATCH')
  }

  async changeOrganizationStatus(
    id: Organization['id'],
    payload: OrganizationStatusChange,
    idempotencyKey: string
  ): Promise<Organization> {
    return this.command<Organization>(`/api/organizations/${encodeURIComponent(id)}/status`, payload, idempotencyKey)
  }

  async findOrganizationDuplicates(name: string, exceptId?: string, inn?: string): Promise<OrganizationDuplicate[]> {
    const searchParams = new URLSearchParams({ name })
    if (inn !== undefined && inn !== '') {
      searchParams.set('inn', inn)
    }
    if (exceptId !== undefined) {
      searchParams.set('exceptId', exceptId)
    }
    return this.request<OrganizationDuplicate[]>(`/api/organizations/duplicates?${searchParams.toString()}`)
  }

  async createAdminOrganization(payload: OrganizationDetails, idempotencyKey: string): Promise<AdminOrganization> {
    return this.command<AdminOrganization>('/api/admin/organizations', payload, idempotencyKey)
  }

  async updateAdminOrganization(
    id: AdminOrganization['id'],
    payload: OrganizationDetails,
    idempotencyKey: string
  ): Promise<AdminOrganization> {
    return this.command<AdminOrganization>(`/api/admin/organizations/${encodeURIComponent(id)}`, payload, idempotencyKey, 'PATCH')
  }

  async changeAdminOrganizationStatus(
    id: AdminOrganization['id'],
    payload: OrganizationStatusChange,
    idempotencyKey: string
  ): Promise<AdminOrganization> {
    return this.command<AdminOrganization>(`/api/admin/organizations/${encodeURIComponent(id)}/status`, payload, idempotencyKey)
  }

  async changeTeamArchived(id: Team['id'], payload: TeamArchive, idempotencyKey: string): Promise<Team> {
    return this.command<Team>(`/api/admin/teams/${encodeURIComponent(id)}/archive`, payload, idempotencyKey, 'PATCH')
  }

  async listAdminCatalogEntries(kind: CatalogKind, query: AdminCatalogListParams = {}): Promise<PageAdminCatalogEntry> {
    const searchParams = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value.toString().trim().length > 0) {
        searchParams.set(key, value.toString().trim())
      }
    }
    const suffix = searchParams.size === 0 ? '' : `?${searchParams.toString()}`
    return this.request<PageAdminCatalogEntry>(`/api/admin/catalogs/${kind}${suffix}`)
  }

  async createAdminCatalogEntry(kind: CatalogKind, payload: CatalogEntryChange, idempotencyKey: string): Promise<AdminCatalogEntry> {
    return this.command<AdminCatalogEntry>(`/api/admin/catalogs/${kind}`, payload, idempotencyKey)
  }

  async updateAdminCatalogEntry(
    kind: CatalogKind,
    id: AdminCatalogEntry['id'],
    payload: CatalogEntryChange,
    idempotencyKey: string
  ): Promise<AdminCatalogEntry> {
    return this.command<AdminCatalogEntry>(`/api/admin/catalogs/${kind}/${encodeURIComponent(id)}`, payload, idempotencyKey, 'PATCH')
  }

  async listVendorContacts(vendorId: AdminCatalogEntry['id']): Promise<VendorContactList> {
    return this.request<VendorContactList>(`/api/admin/catalogs/vendors/${encodeURIComponent(vendorId)}/contacts`)
  }

  async createVendorContact(vendorId: AdminCatalogEntry['id'], payload: VendorContactChange, idempotencyKey: string): Promise<VendorContact> {
    return this.command<VendorContact>(`/api/admin/catalogs/vendors/${encodeURIComponent(vendorId)}/contacts`, payload, idempotencyKey)
  }

  async updateVendorContact(
    vendorId: AdminCatalogEntry['id'],
    id: VendorContact['id'],
    payload: VendorContactChange,
    idempotencyKey: string
  ): Promise<VendorContact> {
    return this.command<VendorContact>(
      `/api/admin/catalogs/vendors/${encodeURIComponent(vendorId)}/contacts/${encodeURIComponent(id)}`,
      payload,
      idempotencyKey,
      'PATCH'
    )
  }

  async listCatalogChangeEvents(query: CatalogChangeEventListParams = {}): Promise<PageCatalogChangeEvent> {
    const searchParams = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) {
        searchParams.set(key, value.toString())
      }
    }
    const suffix = searchParams.size === 0 ? '' : `?${searchParams.toString()}`
    return this.request<PageCatalogChangeEvent>(`/api/admin/catalog-events${suffix}`)
  }

  async listWorkflowTemplateVersions(id: WorkflowTemplate['id']): Promise<WorkflowTemplateVersion[]> {
    return this.request<WorkflowTemplateVersion[]>(`/api/workflow-templates/${encodeURIComponent(id)}/versions`)
  }

  async previewWorkflowRollout(
    id: WorkflowTemplate['id'],
    version: number,
    payload: WorkflowRolloutRequest
  ): Promise<WorkflowRolloutPreview> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    return this.request<WorkflowRolloutPreview>(
      `/api/admin/workflow-templates/${encodeURIComponent(id)}/versions/${version}/rollout-preview`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', [this.csrf!.headerName]: this.csrf!.token },
        body: JSON.stringify(payload)
      }
    )
  }

  async rollOutWorkflowVersion(
    id: WorkflowTemplate['id'],
    version: number,
    payload: WorkflowRolloutRequest,
    idempotencyKey: string
  ): Promise<WorkflowRolloutResult> {
    return this.command<WorkflowRolloutResult>(
      `/api/admin/workflow-templates/${encodeURIComponent(id)}/versions/${version}/rollout`,
      payload,
      idempotencyKey
    )
  }

  async makeWorkflowTemplateDefault(id: WorkflowTemplate['id'], version: number, idempotencyKey: string): Promise<WorkflowTemplate> {
    return this.command<WorkflowTemplate>(
      `/api/admin/workflow-templates/${encodeURIComponent(id)}/default`,
      { version },
      idempotencyKey
    )
  }

  async completeInteractionStep(
    id: Interaction['id'],
    payload: InteractionStepCompletion,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/step-completions`, payload, idempotencyKey)
  }

  async bulkAssignOrganizations(
    payload: OrganizationBulkAssignment,
    idempotencyKey: string
  ): Promise<OrganizationBulkAssignmentResult> {
    return this.command<OrganizationBulkAssignmentResult>('/api/organization-assignments', payload, idempotencyKey)
  }

  async listOrganizationDeputies(id: Organization['id']): Promise<OrganizationDeputy[]> {
    return this.request<OrganizationDeputy[]>(`/api/organizations/${encodeURIComponent(id)}/deputies`)
  }

  async assignOrganizationDeputy(
    id: Organization['id'],
    payload: OrganizationDeputyCreate,
    idempotencyKey: string
  ): Promise<OrganizationDeputy> {
    return this.command<OrganizationDeputy>(`/api/organizations/${encodeURIComponent(id)}/deputies`, payload, idempotencyKey)
  }

  async endOrganizationDeputy(
    id: Organization['id'],
    deputyId: OrganizationDeputy['id'],
    idempotencyKey: string
  ): Promise<OrganizationDeputy> {
    return this.command<OrganizationDeputy>(
      `/api/organizations/${encodeURIComponent(id)}/deputies/${encodeURIComponent(deputyId)}/end`,
      undefined,
      idempotencyKey
    )
  }

  async assignManagerDeputy(
    managerId: string,
    payload: OrganizationDeputyCreate,
    idempotencyKey: string
  ): Promise<ManagerDeputyResult> {
    return this.command<ManagerDeputyResult>(`/api/managers/${encodeURIComponent(managerId)}/deputies`, payload, idempotencyKey)
  }

  async getTeamIndicators(stuckDays?: number): Promise<TeamIndicators> {
    const suffix = stuckDays === undefined ? '' : `?${new URLSearchParams({ stuckDays: stuckDays.toString() })}`
    return this.request<TeamIndicators>(`/api/work/team-indicators${suffix}`)
  }

  async getTeamsSummary(stuckDays?: number): Promise<TeamsSummary> {
    const suffix = stuckDays === undefined ? '' : `?${new URLSearchParams({ stuckDays: stuckDays.toString() })}`
    return this.request<TeamsSummary>(`/api/work/teams-summary${suffix}`)
  }

  async getWorkControl(query: WorkControlQuery): Promise<WorkControl> {
    const params = new URLSearchParams()
    const lists = {
      organizationId: query.organizationId,
      directionId: query.directionId,
      programId: query.programId,
      productId: query.productId,
      kindId: query.kindId,
      managerId: query.managerId,

      stage: query.stage
    }
    Object.entries(lists).forEach(([key, values]) => values.forEach((value) => params.append(key, value)))
    Object.entries({ from: query.from, to: query.to, change: query.change, mode: query.mode === 'PERIOD' ? 'PERIOD' : '' }).forEach(([key, value]) => {
      if (value !== '') {
        params.set(key, value)
      }
    })
    return this.request<WorkControl>(`/api/work/control${params.size === 0 ? '' : `?${params.toString()}`}`)
  }

  async getLearningTrend(days?: number): Promise<LearningTrend> {
    return this.request<LearningTrend>(`/api/work/learning-trend${querySuffix({ days })}`)
  }

  async getPortfolioChange(from: string, to: string): Promise<PortfolioChange> {
    return this.request<PortfolioChange>(`/api/work/portfolio-change?${new URLSearchParams({ from, to })}`)
  }

  async getLearningCoverage(): Promise<LearningCoverage> {
    return this.request<LearningCoverage>('/api/learning-coverage')
  }

  async getLearningDynamics(query: LearningDynamicsQuery): Promise<LearningDynamics> {
    return this.request<LearningDynamics>(`/api/reports/learning-dynamics${learningDynamicsQuery(query)}`)
  }

  async downloadLearningDynamics(query: LearningDynamicsQuery, format: 'XLSX' | 'PDF'): Promise<Blob> {
    return this.download(`/api/reports/learning-dynamics/file${learningDynamicsQuery({ ...query, seriesBy: undefined }, { format })}`)
  }

  async getSigningPlan(query: SigningPlanQuery): Promise<SigningPlan> {
    return this.request<SigningPlan>(`/api/reports/signing-plan${signingPlanQuery(query)}`)
  }

  async downloadSigningPlan(query: SigningPlanQuery, format: 'XLSX' | 'PDF'): Promise<Blob> {
    return this.download(`/api/reports/signing-plan/file${signingPlanQuery(query, { format })}`)
  }

  async getReminders(): Promise<ReminderDigest> {
    return this.request<ReminderDigest>('/api/reminders')
  }

  async saveReminderSettings(payload: ReminderSettings): Promise<ReminderSettings> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    return this.request<ReminderSettings>('/api/reminders/settings', {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: JSON.stringify(payload)
    })
  }

  async listLmsSignals(): Promise<LmsSignals> {
    return this.request<LmsSignals>('/api/lms-signals')
  }

  async listInteractionLmsSignals(id: Interaction['id']): Promise<LmsSignals> {
    return this.request<LmsSignals>(`/api/interactions/${encodeURIComponent(id)}/lms-signals`)
  }

  async dismissInteractionLmsSignal(
    id: Interaction['id'],
    payload: components['schemas']['LmsSignalDismissal']
  ): Promise<LmsSignals> {
    return this.post<LmsSignals>(`/api/interactions/${encodeURIComponent(id)}/lms-signals/dismissals`, payload)
  }

  async recordInteractionLmsSignalRisk(
    id: Interaction['id'],
    payload: components['schemas']['LmsSignalRisk'],
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/lms-signals/risks`, payload, idempotencyKey)
  }

  async createOrganizationFromAdminSourceRecord(
    id: SourceRecord['id'],
    payload: SourceOrganizationCreate
  ): Promise<SourceOrganizationCreated> {
    return this.post<SourceOrganizationCreated>(`/api/admin/source-records/${encodeURIComponent(id)}/organization`, payload)
  }

  async listSourceSyncRuns(source: SourceCode): Promise<SyncRun[]> {
    return this.request<SyncRun[]>(`/api/admin/sources/${encodeURIComponent(source)}/runs`)
  }

  async listSourceMappings(): Promise<SourceMapping[]> {
    return this.request<SourceMapping[]>('/api/admin/source-mappings')
  }

  async updateSourceMapping(id: SourceMapping['id'], payload: SourceMappingUpdate): Promise<SourceMapping> {
    return this.send<SourceMapping>(`/api/admin/source-mappings/${encodeURIComponent(id)}`, 'PUT', payload)
  }

  async removeSourceMapping(id: SourceMapping['id'], version: number): Promise<void> {
    return this.send<void>(`/api/admin/source-mappings/${encodeURIComponent(id)}?version=${version}`, 'DELETE')
  }

  async getSourceSettings(): Promise<SourceSettings> {
    return this.request<SourceSettings>('/api/admin/source-settings')
  }

  async updateSourceSettings(payload: SourceSettingsUpdate): Promise<SourceSettings> {
    return this.send<SourceSettings>('/api/admin/source-settings', 'PUT', payload)
  }

  async resetSourceSettings(): Promise<void> {
    return this.send<void>('/api/admin/source-settings', 'DELETE')
  }

  async checkSourceConnection(source: SourceCode, payload: SourceSettingsUpdate): Promise<SourceConnectionCheck> {
    return this.post<SourceConnectionCheck>(`/api/admin/sources/${encodeURIComponent(source)}/check`, payload)
  }

  async getLaborMarketSettings(): Promise<LaborMarketSettings> {
    return this.request<LaborMarketSettings>('/api/admin/labor-market/settings')
  }

  async updateLaborMarketSettings(payload: LaborMarketSettingsUpdate): Promise<LaborMarketSettings> {
    return this.send<LaborMarketSettings>('/api/admin/labor-market/settings', 'PUT', payload)
  }

  async resetLaborMarketSettings(): Promise<void> {
    return this.send<void>('/api/admin/labor-market/settings', 'DELETE')
  }

  async checkLaborMarketConnection(payload: LaborMarketSettingsUpdate): Promise<SourceConnectionCheck> {
    return this.post<SourceConnectionCheck>('/api/admin/labor-market/check', payload)
  }

  async listLaborMarketMappings(): Promise<LaborMarketMappings> {
    return this.request<LaborMarketMappings>('/api/admin/labor-market/mappings')
  }

  async updateLaborMarketMapping(directionId: LaborMarketDirection['directionId'], payload: LaborMarketMappingUpdate): Promise<LaborMarketDirection> {
    return this.send<LaborMarketDirection>(`/api/admin/labor-market/mappings/${encodeURIComponent(directionId)}`, 'PUT', payload)
  }

  async listCrmExchangeEvents(status?: CrmEventStatus): Promise<CrmEventList> {
    return this.request<CrmEventList>(`/api/admin/crm-exchange/events${status ? `?status=${encodeURIComponent(status)}` : ''}`)
  }

  async retryCrmExchangeEvents(): Promise<CrmEventsRequeued> {
    return this.post<CrmEventsRequeued>('/api/admin/crm-exchange/events/retry')
  }

  async previewCrmExchange(): Promise<CrmPreview> {
    return this.post<CrmPreview>('/api/admin/crm-exchange/preview')
  }

  async addSourceMappingRun(id: SourceMapping['id'], payload: SourceMappingUpdate): Promise<SourceMapping> {
    return this.post<SourceMapping>(`/api/admin/source-mappings/${encodeURIComponent(id)}/runs`, payload)
  }

  async deleteSourceMappingSnapshot(id: SourceMapping['id']): Promise<void> {
    return this.send<void>(`/api/admin/source-mappings/${encodeURIComponent(id)}/snapshot`, 'DELETE')
  }

  async listPendingSourceRecords(): Promise<PendingSourceRecord[]> {
    return this.request<PendingSourceRecord[]>('/api/source-records')
  }

  async resolvePendingSourceRecord(id: PendingSourceRecord['id'], organizationId: string): Promise<SourceRecordApplyResult> {
    return this.post<SourceRecordApplyResult>(`/api/source-records/${encodeURIComponent(id)}/resolve`, { organizationId })
  }

  async createOrganizationFromSourceRecord(
    id: PendingSourceRecord['id'],
    payload: SourceOrganizationCreate
  ): Promise<SourceOrganizationCreated> {
    return this.post<SourceOrganizationCreated>(`/api/source-records/${encodeURIComponent(id)}/organization`, payload)
  }

  async getInteractionSourceStatus(id: Interaction['id']): Promise<InteractionSourceStatus> {
    return this.request<InteractionSourceStatus>(`/api/interactions/${encodeURIComponent(id)}/source-status`)
  }

  async refreshInteractionSources(id: Interaction['id']): Promise<SourcesRefresh> {
    return this.post<SourcesRefresh>(`/api/interactions/${encodeURIComponent(id)}/sources/refresh`)
  }

  async listTeacherTrainings(id: Interaction['id']): Promise<TeacherTraining[]> {
    return this.request<TeacherTraining[]>(`/api/interactions/${encodeURIComponent(id)}/teacher-trainings`)
  }

  async createTeacherTraining(
    id: Interaction['id'],
    payload: TeacherTrainingCreate,
    idempotencyKey: string
  ): Promise<TeacherTrainingCreated> {
    return this.command<TeacherTrainingCreated>(`/api/interactions/${encodeURIComponent(id)}/teacher-trainings`, payload, idempotencyKey)
  }

  async listTeacherRosters(id: Interaction['id']): Promise<TeacherRoster[]> {
    return this.request<TeacherRoster[]>(`/api/interactions/${encodeURIComponent(id)}/teacher-rosters`)
  }

  async createTeacherRoster(id: Interaction['id'], payload: TeacherRosterCreate): Promise<TeacherRoster> {
    return this.post<TeacherRoster>(`/api/interactions/${encodeURIComponent(id)}/teacher-rosters`, payload)
  }

  async deleteTeacherRoster(rosterId: TeacherRoster['id']): Promise<void> {
    return this.send<void>(`/api/teacher-rosters/${encodeURIComponent(rosterId)}`, 'DELETE')
  }

  async addTeacherRosterMember(rosterId: TeacherRoster['id'], contactId: string): Promise<TeacherRoster> {
    return this.send<TeacherRoster>(
      `/api/teacher-rosters/${encodeURIComponent(rosterId)}/members/${encodeURIComponent(contactId)}`,
      'PUT'
    )
  }

  async removeTeacherRosterMember(rosterId: TeacherRoster['id'], contactId: string): Promise<TeacherRoster> {
    return this.send<TeacherRoster>(
      `/api/teacher-rosters/${encodeURIComponent(rosterId)}/members/${encodeURIComponent(contactId)}`,
      'DELETE'
    )
  }

  async exportTeacherRoster(rosterId: TeacherRoster['id'], payload: TeacherRosterFileRequest): Promise<TeacherRosterFileDownload> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const response = await this.fetchApi(`/api/teacher-rosters/${encodeURIComponent(rosterId)}/lms-file`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, text/csv, application/json',
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: JSON.stringify(payload)
    })
    if (!response.ok) {
      return this.throwResponseError(response)
    }
    return {
      blob: await response.blob(),
      fileName: fileNameFromContentDisposition(response.headers.get('Content-Disposition')),
      exportId: response.headers.get('X-Roster-Export-Id') ?? '',
      rows: Number(response.headers.get('X-Roster-Rows') ?? 0),
      skipped: Number(response.headers.get('X-Roster-Skipped') ?? 0)
    }
  }

  async markTeacherRosterExportTransferred(exportId: string): Promise<TeacherRosterMarked> {
    return this.post<TeacherRosterMarked>(`/api/teacher-roster-exports/${encodeURIComponent(exportId)}/transferred`)
  }

  async getInteractionCycle(id: Interaction['id']): Promise<InteractionCycle> {
    return this.request<InteractionCycle>(`/api/interactions/${encodeURIComponent(id)}/cycle`)
  }

  async startInteractionCycle(id: Interaction['id'], payload: CycleStart, idempotencyKey: string): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/cycles`, payload, idempotencyKey)
  }

  async logout(): Promise<LogoutResult> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const result = await this.request<LogoutResult>('/api/auth/logout', {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token
      }
    })
    this.csrf = null
    return result
  }

  login(): void {
    window.location.assign('/api/auth/login')
  }

  private async listCatalog(
    path: '/api/programs' | '/api/products' | '/api/directions',
    query: CatalogListParams
  ): Promise<PageCatalogLookup> {
    const searchParams = new URLSearchParams()
    if (query.page !== undefined) {
      searchParams.set('page', query.page.toString())
    }
    if (query.size !== undefined) {
      searchParams.set('size', query.size.toString())
    }
    if (query.state !== undefined) {
      searchParams.set('state', query.state)
    }
    const suffix = searchParams.size === 0 ? '' : `?${searchParams.toString()}`
    return this.request<PageCatalogLookup>(`${path}${suffix}`)
  }

  private async listWorkflowTemplatesAt(
    path: '/api/workflow-templates' | '/api/admin/workflow-templates',
    query: WorkflowTemplateListParams
  ): Promise<PageWorkflowTemplate> {
    const searchParams = new URLSearchParams()
    if (query.page !== undefined) {
      searchParams.set('page', query.page.toString())
    }
    if (query.size !== undefined) {
      searchParams.set('size', query.size.toString())
    }
    if (query.sort !== undefined) {
      searchParams.set('sort', query.sort)
    }
    const suffix = searchParams.size === 0 ? '' : `?${searchParams.toString()}`
    return this.request<PageWorkflowTemplate>(`${path}${suffix}`)
  }

  // Модуль MAX
  async getMaxLink(): Promise<MaxLinkStatus> {
    return this.request<MaxLinkStatus>('/api/max/link')
  }

  async startMaxLink(): Promise<MaxLinkStart> {
    return this.post<MaxLinkStart>('/api/max/link')
  }

  async confirmMaxLink(): Promise<MaxLinkStatus> {
    return this.post<MaxLinkStatus>('/api/max/link/confirm')
  }

  async rejectMaxLink(): Promise<MaxLinkStatus> {
    return this.post<MaxLinkStatus>('/api/max/link/reject')
  }

  async deleteMaxLink(): Promise<MaxLinkStatus> {
    return this.send<MaxLinkStatus>('/api/max/link', 'DELETE')
  }

  async saveMaxPreferences(preferences: MaxPreferences): Promise<MaxLinkStatus> {
    return this.send<MaxLinkStatus>('/api/max/link/preferences', 'PUT', preferences)
  }

  async getMaxSettings(): Promise<MaxSettings> {
    return this.request<MaxSettings>('/api/max/admin/settings')
  }

  async updateMaxSettings(payload: MaxSettingsUpdate): Promise<MaxSettings> {
    return this.send<MaxSettings>('/api/max/admin/settings', 'PUT', payload)
  }

  async checkMax(): Promise<MaxCheck> {
    return this.post<MaxCheck>('/api/max/admin/check')
  }

  async subscribeMax(): Promise<MaxSettings> {
    return this.post<MaxSettings>('/api/max/admin/subscription')
  }

  async requeueMax(): Promise<{ requeued: number }> {
    return this.post<{ requeued: number }>('/api/max/admin/outbox/requeue')
  }

  async listMaxLinks(): Promise<MaxAdminLink[]> {
    return this.request<MaxAdminLink[]>('/api/max/admin/links')
  }

  async deleteMaxLinkByAdmin(profileId: string): Promise<void> {
    return this.send<void>(`/api/max/admin/links/${encodeURIComponent(profileId)}`, 'DELETE')
  }

  // Модуль почты
  async getMailPreferences(): Promise<MailPreferences> {
    return this.request<MailPreferences>('/api/mail/preferences')
  }

  async saveMailPreferences(disabledTopics: MailTopic[]): Promise<MailPreferences> {
    return this.send<MailPreferences>('/api/mail/preferences', 'PUT', { disabledTopics })
  }

  async getMailSettings(): Promise<MailSettings> {
    return this.request<MailSettings>('/api/mail/admin/settings')
  }

  async updateMailSettings(payload: MailSettingsUpdate): Promise<MailSettings> {
    return this.send<MailSettings>('/api/mail/admin/settings', 'PUT', payload)
  }

  async checkMail(): Promise<MailCheck> {
    return this.post<MailCheck>('/api/mail/admin/check')
  }

  async sendMailTest(): Promise<MailJournalRow> {
    return this.post<MailJournalRow>('/api/mail/admin/test')
  }

  async listMailOutbox(status: MailJournalRow['status'] | '', page: number, size = 20): Promise<MailJournalPage> {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (status !== '') {
      params.set('status', status)
    }
    return this.request<MailJournalPage>(`/api/mail/admin/outbox?${params.toString()}`)
  }

  async getMailLetter(id: string): Promise<MailLetter> {
    return this.request<MailLetter>(`/api/mail/admin/outbox/${encodeURIComponent(id)}`)
  }

  async retryMailLetter(id: string): Promise<MailJournalRow> {
    return this.post<MailJournalRow>(`/api/mail/admin/outbox/${encodeURIComponent(id)}/retry`)
  }

  async cancelMailLetter(id: string): Promise<MailJournalRow> {
    return this.post<MailJournalRow>(`/api/mail/admin/outbox/${encodeURIComponent(id)}/cancel`)
  }

  async requeueMail(): Promise<{ requeued: number }> {
    return this.post<{ requeued: number }>('/api/mail/admin/outbox/requeue')
  }

  async sendSubjectReportByMail(email: string, query: SubjectQuery): Promise<MailJournalRow> {
    return this.post<MailJournalRow>('/api/mail/admin/subject-report', { email, query })
  }

  /** Открытие приглашения из письма: без входа и CSRF, токен — в теле запроса. */
  async redeemMailInvitation(token: string): Promise<MailInvitationRedeemed> {
    return this.request<MailInvitationRedeemed>('/api/mail/invitations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token })
    })
  }

  private async post<T>(path: string, payload?: object): Promise<T> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    return this.request<T>(path, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: payload === undefined ? undefined : JSON.stringify(payload)
    })
  }

  private async send<T>(path: string, method: 'PUT' | 'DELETE', payload?: object): Promise<T> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    return this.request<T>(path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        [this.csrf!.headerName]: this.csrf!.token
      },
      body: payload === undefined ? undefined : JSON.stringify(payload)
    })
  }

  async command<T>(
    path: string,
    payload: object | undefined,
    idempotencyKey: string,
    method: 'POST' | 'PUT' | 'PATCH' | 'DELETE' = 'POST'
  ): Promise<T> {
    if (idempotencyKey.length === 0) {
      throw new Error('An idempotency key is required for a command.')
    }
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    return this.request<T>(path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        [this.csrf!.headerName]: this.csrf!.token,
        'Idempotency-Key': idempotencyKey
      },
      body: payload === undefined ? undefined : JSON.stringify(payload)
    })
  }

  async upload<T>(path: string, formData: FormData, idempotencyKey: string): Promise<T> {
    if (idempotencyKey.length === 0) {
      throw new Error('An idempotency key is required for a command.')
    }
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    return this.request<T>(path, {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token,
        'Idempotency-Key': idempotencyKey
      },
      body: formData
    })
  }

  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await this.fetchApi(path, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...init.headers
      }
    })
    if (response.status === 204) {
      return undefined as T
    }
    if (!response.ok) {
      return this.throwResponseError(response)
    }
    const contentType = response.headers.get('content-type') ?? ''
    const body: unknown = contentType.includes('application/json') ? await response.json() : null
    return body as T
  }

  // Единственный автоматический повтор в клиенте: если сервер ответил CSRF_INVALID (токен устарел, например после
  // повторного входа), токен обновляется и тот же запрос уходит ещё раз. Повтор безопасен: заголовок
  // Idempotency-Key остаётся прежним, и сервер не выполнит команду дважды. Любой другой 403 повтором не
  // обрабатывается, а повтор при потере сети отдан пользователю.
  private async fetchApi(path: string, init: RequestInit): Promise<Response> {
    const response = await fetch(path, { ...init, credentials: 'include' })
    // Повтор возможен, только если запрос вообще нёс CSRF-заголовок: запросы чтения (GET) токен не используют.
    const headerName = this.csrf?.headerName
    if (response.status !== 403 || headerName === undefined || !new Headers(init.headers).has(headerName)) {
      return response
    }
    const body = parseJson(await response.clone().text())
    if (!isApiErrorPayload(body) || body.code !== 'CSRF_INVALID') {
      return response
    }
    // Старый заголовок удаляется до установки нового: имя заголовка берётся из свежего ответа сервера и может
    // отличаться от прежнего.
    const csrf = await this.refreshCsrf()
    const headers = new Headers(init.headers)
    headers.delete(headerName)
    headers.set(csrf.headerName, csrf.token)
    return fetch(path, { ...init, headers, credentials: 'include' })
  }

  async download(path: string): Promise<Blob> {
    const response = await fetch(path, {
      credentials: 'include',
      headers: {
        Accept: 'application/octet-stream, application/json'
      }
    })
    if (!response.ok) {
      return this.throwResponseError(response)
    }
    return response.blob()
  }

  private async throwResponseError(response: Response): Promise<never> {
    const body = parseJson(await response.text())
    // Ограничение частоты: экраны показывают message ошибки, поэтому срок повтора из Retry-After вписывается прямо в
    // него — ни один экран не нужно учить коду RATE_LIMITED.
    if (response.status === 429) {
      const seconds = Number.parseInt(response.headers.get('Retry-After') ?? '', 10)
      throw new ApiError({
        code: 'RATE_LIMITED',
        message: Number.isFinite(seconds) && seconds > 0
          ? `Слишком много запросов, повторите через ${seconds} сек.`
          : 'Слишком много запросов, повторите чуть позже',
        requestId: isApiErrorPayload(body) ? body.requestId : response.headers.get('X-Request-Id') ?? ''
      }, response.status)
    }
    if (isApiErrorPayload(body)) {
      throw new ApiError(body, response.status)
    }
    if (response.status === 413) {
      throw new ApiError({
        code: 'PAYLOAD_TOO_LARGE',
        message: 'Файл больше допустимого размера',
        requestId: response.headers.get('X-Request-Id') ?? ''
      }, response.status)
    }
    throw new Error(`Unexpected API response: ${response.status}`)
  }
}

export const apiClient = new ApiClient()

export const createIdempotencyKey = (): string => crypto.randomUUID()
