import { apiClient, createIdempotencyKey } from '../../shared/api/client'
import type { components } from '../../shared/api/openapi'

export type PartnerAccess = components['schemas']['PartnerAccess']
export type PartnerAccessGranted = components['schemas']['PartnerAccessGranted']
export type PartnerCabinet = components['schemas']['PartnerCabinet']
export type PartnerWork = components['schemas']['PartnerWork']
export type PartnerDocument = components['schemas']['PartnerDocument']
export type PartnerAgreement = components['schemas']['PartnerAgreement']

const accessPath = (organizationId: string, contactId: string) => (
  `/api/organizations/${encodeURIComponent(organizationId)}/contacts/${encodeURIComponent(contactId)}/partner-access`
)

export const listPartnerAccess = (organizationId: string) => (
  apiClient.request<PartnerAccess[]>(`/api/organizations/${encodeURIComponent(organizationId)}/partner-access`)
)

export const openPartnerAccess = (organizationId: string, contactId: string) => (
  apiClient.command<PartnerAccessGranted>(accessPath(organizationId, contactId), undefined, createIdempotencyKey())
)

export const closePartnerAccess = (organizationId: string, contactId: string) => (
  apiClient.command<PartnerAccess>(accessPath(organizationId, contactId), undefined, createIdempotencyKey(), 'DELETE')
)

export const loadPartnerCabinet = () => apiClient.request<PartnerCabinet>('/api/partner/cabinet')

export const downloadPartnerDocument = (id: string) => (
  apiClient.download(`/api/partner/documents/${encodeURIComponent(id)}/download`)
)

export const organizationTypeLabels: Record<PartnerCabinet['organization']['type'], string> = {
  UNIVERSITY: 'Вуз',
  SCHOOL: 'Школа',
  COLLEGE: 'Колледж',
  OPEN_ENROLLMENT: 'Открытый набор'
}

export const agreementStatusLabels: Record<PartnerAgreement['status'], string> = {
  DRAFT: 'Проект',
  ACTIVE: 'Действует',
  COMPLETED: 'Завершено',
  TERMINATED: 'Расторгнуто'
}
