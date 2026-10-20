import { apiClient } from '../../shared/api/client'
import type { components } from '../../shared/api/openapi'

type Schemas = components['schemas']
export type ReportingSettingsView = Schemas['AgreementReportingSettingsView']
export type ReferenceCreate = Schemas['AgreementReferenceCreate']
export type ReferenceUpdate = Schemas['AgreementReferenceUpdate']
export type AgreementRate = Schemas['AgreementRate']
export type AgreementRateInput = Schemas['AgreementRateInput']
export type ReportingReminders = Schemas['AgreementReportingReminders']
export type ActivityList = Schemas['ActivityList']
export type ActivityListSummary = Schemas['ActivityListSummary']
export type ActivityListsView = Schemas['ActivityListsView']
export type ActivityListCreate = Schemas['ActivityListCreate']
export type ActivityListUpdate = Schemas['ActivityListUpdate']
export type ActivityListTransition = Schemas['ActivityListTransition']
export type PartnerActivityListDecision = Schemas['PartnerActivityListDecision']
export type AgreementReport = Schemas['AgreementReport']
export type AgreementReportQuery = { from: string; to: string; organizationId: string }
export type AgreementReportFormat = 'XLSX' | 'PDF' | 'JSON'

const path = (id: string) => encodeURIComponent(id)

const reportSearch = (query: AgreementReportQuery, extra: Record<string, string> = {}) => {
  const params = new URLSearchParams({ ...extra, from: query.from, to: query.to })
  if (query.organizationId !== '') {
    params.set('organizationId', query.organizationId)
  }
  return `?${params}`
}

export const reportingApi = {
  settings: () => apiClient.request<ReportingSettingsView>('/api/agreement-reporting/settings'),
  saveSettings: (payload: Schemas['AgreementReportingSettingsInput']) => (
    apiClient.saveAgreementReporting<ReportingSettingsView>('settings', payload)
  ),
  saveTemplate: (payload: Schemas['AgreementReportTemplateInput']) => (
    apiClient.saveAgreementReporting<ReportingSettingsView>('template', payload)
  ),
  resetTemplate: () => apiClient.resetAgreementReportTemplate<ReportingSettingsView>(),
  saveDeadlines: (payload: Schemas['AgreementReportingDeadlinesInput']) => (
    apiClient.saveAgreementReporting<ReportingSettingsView>('deadlines', payload)
  ),
  references: (includeArchived = false) => apiClient.request<Schemas['AgreementReferenceItem'][]>(
    `/api/agreement-reporting/references${includeArchived ? '?includeArchived=true' : ''}`
  ),
  createReference: (payload: ReferenceCreate, key: string) => (
    apiClient.command<Schemas['AgreementReferenceItem']>('/api/admin/agreement-reporting/references', payload, key)
  ),
  updateReference: (id: string, payload: ReferenceUpdate, key: string) => (
    apiClient.command<Schemas['AgreementReferenceItem']>(`/api/admin/agreement-reporting/references/${path(id)}`, payload, key, 'PATCH')
  ),
  rates: () => apiClient.request<AgreementRate[]>('/api/agreement-reporting/rates'),
  createRate: (payload: AgreementRateInput, key: string) => (
    apiClient.command<AgreementRate>('/api/admin/agreement-reporting/rates', payload, key)
  ),
  updateRate: (id: string, payload: AgreementRateInput, key: string) => (
    apiClient.command<AgreementRate>(`/api/admin/agreement-reporting/rates/${path(id)}`, payload, key, 'PATCH')
  ),
  deleteRate: (id: string, version: number, key: string) => apiClient.command<void>(
    `/api/admin/agreement-reporting/rates/${path(id)}?${new URLSearchParams({ version: String(version) })}`, undefined, key, 'DELETE'
  ),
  reminders: () => apiClient.request<ReportingReminders>('/api/agreement-reporting/reminders'),
  lists: (organizationId: string) => apiClient.request<ActivityListsView>(`/api/organizations/${path(organizationId)}/activity-lists`),
  createList: (organizationId: string, payload: ActivityListCreate, key: string) => (
    apiClient.command<ActivityList>(`/api/organizations/${path(organizationId)}/activity-lists`, payload, key)
  ),
  list: (id: string) => apiClient.request<ActivityList>(`/api/activity-lists/${path(id)}`),
  updateList: (id: string, payload: ActivityListUpdate, key: string) => (
    apiClient.command<ActivityList>(`/api/activity-lists/${path(id)}`, payload, key, 'PATCH')
  ),
  transitionList: (id: string, payload: ActivityListTransition, key: string) => (
    apiClient.command<ActivityList>(`/api/activity-lists/${path(id)}/transitions`, payload, key)
  ),
  partnerLists: () => apiClient.request<ActivityList[]>('/api/partner/activity-lists'),
  decidePartnerList: (id: string, payload: PartnerActivityListDecision, key: string) => (
    apiClient.command<ActivityList>(`/api/partner/activity-lists/${path(id)}/decision`, payload, key)
  ),
  report: (query: AgreementReportQuery) => apiClient.request<AgreementReport>(`/api/reports/agreement-reporting${reportSearch(query)}`),
  reportFile: (query: AgreementReportQuery, format: AgreementReportFormat) => (
    apiClient.download(`/api/reports/agreement-reporting/file${reportSearch(query, { format })}`)
  )
}
