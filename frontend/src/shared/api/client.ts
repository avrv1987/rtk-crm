import type { components, operations } from './openapi'

export type Me = components['schemas']['Me']
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
export type TeamIndicators = components['schemas']['TeamIndicators']
export type ManagerIndicators = components['schemas']['ManagerIndicators']
export type TeamsSummary = components['schemas']['TeamsSummary']
export type TeamSummary = components['schemas']['TeamSummary']
export type ReminderDigest = components['schemas']['ReminderDigest']
export type ReminderSettings = components['schemas']['ReminderSettings']
export type WorkflowStageInput = components['schemas']['WorkflowStageInput']
export type WorkflowTransitionInput = components['schemas']['WorkflowTransitionInput']
export type WorkflowTemplate = components['schemas']['WorkflowTemplate']
export type WorkflowTemplateCreate = components['schemas']['WorkflowTemplateCreate']
export type WorkflowTemplateUpdate = components['schemas']['WorkflowTemplateUpdate']
export type PageWorkflowTemplate = components['schemas']['PageWorkflowTemplate']
export type WorkflowTemplateListParams = NonNullable<operations['listAvailableWorkflowTemplates']['parameters']['query']>
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
export type CrmProfileListParams = NonNullable<operations['listCrmProfiles']['parameters']['query']>
export type CrmProfileUpdate = components['schemas']['CrmProfileUpdate']
export type CrmProfileEvent = components['schemas']['CrmProfileEvent']
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
export type RetentionPolicy = components['schemas']['RetentionPolicy']
export type RetentionRun = components['schemas']['RetentionRun']
export type VendorContact = components['schemas']['VendorContact']
export type VendorContactList = components['schemas']['VendorContactList']
export type VendorContactChange = components['schemas']['VendorContactChange']
export type VendorContactCard = components['schemas']['VendorContactCard']
export type PaidOrderUpload = components['schemas']['PaidOrderUpload']
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

export class ApiClient {
  private csrf: CsrfToken | null = null

  async me(): Promise<Me> {
    return this.request<Me>('/api/me')
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

  async uploadPaidOrders(file: File, idempotencyKey: string): Promise<PaidOrderUpload> {
    if (this.csrf === null) {
      await this.refreshCsrf()
    }
    const formData = new FormData()
    formData.set('file', file)
    return this.request<PaidOrderUpload>('/api/enrolment/paid-orders', {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token,
        'Idempotency-Key': idempotencyKey
      },
      body: formData
    })
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
    const response = await fetch(`/api/enrolment/streams/${encodeURIComponent(id)}/lms-roster`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, application/json',
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
    if (query.pending !== undefined) {
      searchParams.set('pending', query.pending.toString())
    }
    if (query.q !== undefined && query.q !== '') {
      searchParams.set('q', query.q)
    }
    const suffix = searchParams.size === 0 ? '' : `?${searchParams.toString()}`
    return this.request<PageCrmProfile>(`/api/admin/crm-profiles${suffix}`)
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
    const response = await fetch(`/api/admin/personal-data/export?format=${format}`, {
      method: 'POST',
      credentials: 'include',
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

  async findOrganizationDuplicates(name: string, exceptId?: string): Promise<OrganizationDuplicate[]> {
    const searchParams = new URLSearchParams({ name })
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

  async getTeamIndicators(stuckDays?: number): Promise<TeamIndicators> {
    const suffix = stuckDays === undefined ? '' : `?${new URLSearchParams({ stuckDays: stuckDays.toString() })}`
    return this.request<TeamIndicators>(`/api/work/team-indicators${suffix}`)
  }

  async getTeamsSummary(stuckDays?: number): Promise<TeamsSummary> {
    const suffix = stuckDays === undefined ? '' : `?${new URLSearchParams({ stuckDays: stuckDays.toString() })}`
    return this.request<TeamsSummary>(`/api/work/teams-summary${suffix}`)
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
    method: 'POST' | 'PATCH' | 'DELETE' = 'POST'
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

  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await fetch(path, {
      ...init,
      credentials: 'include',
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
