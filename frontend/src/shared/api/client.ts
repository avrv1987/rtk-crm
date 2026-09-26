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
  occurredAt: string
}
export type OrganizationAssignment = {
  version: number
  ownerManagerId: string | null
}
export type OrganizationAssignmentResult = {
  organization: Organization
  event: OrganizationAssignmentEvent
}
export type Contact = components['schemas']['Contact']
export type ContactCreate = components['schemas']['ContactCreate']
export type CatalogLookup = components['schemas']['CatalogLookup']
export type PageCatalogLookup = components['schemas']['PageCatalogLookup']
export type CatalogListParams = NonNullable<operations['listPrograms']['parameters']['query']>
export type ProductAgreement = components['schemas']['ProductAgreement']
export type Attachment = components['schemas']['Attachment']
export type AttachmentUpload = {
  file: File
  stageId: Attachment['stageId']
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
export type InteractionPlanUpdate = components['schemas']['InteractionPlanUpdate']
export type InteractionNextStep = components['schemas']['InteractionNextStep']
export type InteractionListParams = NonNullable<operations['listInteractions']['parameters']['query']>
export type InteractionDue = NonNullable<InteractionListParams['due']>
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
export type SourceCode = components['schemas']['SourceCode']
export type DataSource = components['schemas']['DataSource']
export type SyncRun = components['schemas']['SyncRun']
export type SyncRunCreated = components['schemas']['SyncRunCreated']
export type SourceRecord = components['schemas']['SourceRecord']
export type SourceRecordApply = components['schemas']['SourceRecordApply']
export type SourceRecordApplyResult = components['schemas']['SourceRecordApplyResult']
export type SourceMappingOptions = components['schemas']['SourceMappingOptions']
export type CatalogImportProfile = 'AGREEMENT' | 'DIRECTION_PROGRAM'
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
}
export type CatalogImport = {
  id: string
  profile: CatalogImportProfile
  status: 'PREVIEWED' | 'APPLIED'
  version: number
  rows: CatalogImportRow[]
  createdAt: string
  updatedAt: string
}
export type CatalogImportApply = {
  version: number
  confirmedRowIds: string[]
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
    return this.request<Attachment>(`/api/interactions/${encodeURIComponent(id)}/attachments`, {
      method: 'POST',
      headers: {
        [this.csrf!.headerName]: this.csrf!.token,
        'Idempotency-Key': idempotencyKey
      },
      body: formData
    })
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

  async editInteractionStages(
    id: Interaction['id'],
    payload: InteractionStageEdit,
    idempotencyKey: string
  ): Promise<Interaction> {
    return this.command<Interaction>(`/api/interactions/${encodeURIComponent(id)}/stage-edits`, payload, idempotencyKey)
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
    const suffix = searchParams.size === 0 ? '' : `?${searchParams.toString()}`
    return this.request<PageCrmProfile>(`/api/admin/crm-profiles${suffix}`)
  }

  async listCrmProfileEvents(id: CrmProfile['id']): Promise<CrmProfileEvent[]> {
    return this.request<CrmProfileEvent[]>(`/api/admin/crm-profiles/${encodeURIComponent(id)}/events`)
  }

  async listTeams(): Promise<Team[]> {
    return this.request<Team[]>('/api/admin/teams')
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

  private async command<T>(
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

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
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

  private async download(path: string): Promise<Blob> {
    const response = await fetch(path, {
      credentials: 'include',
      headers: {
        Accept: 'application/octet-stream'
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
