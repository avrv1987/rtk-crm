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

// Свой файл ответа на запрос школы: сервер отдаёт только файл этого координатора, проверенный антивирусом.
export const downloadPartnerResponseFile = (responseId: string) => (
  apiClient.download(`/api/partner/responses/${encodeURIComponent(responseId)}/file`)
)

export const organizationTypeLabels: Record<PartnerCabinet['organization']['type'], string> = {
  UNIVERSITY: 'Вуз',
  SCHOOL: 'Школа',
  COLLEGE: 'Колледж',
  OPEN_ENROLLMENT: 'Открытый набор'
}

export { agreementStatusLabels } from '../agreements/agreementModel'

export type PartnerRequest = components['schemas']['PartnerRequest']
export type PartnerRequestForPartner = components['schemas']['PartnerRequestForPartner']
export type PartnerReceipt = components['schemas']['PartnerReceipt']
export type PartnerReviewItem = components['schemas']['PartnerReviewItem']
export type PartnerRequestStatus = components['schemas']['PartnerRequestStatus']

export const listPartnerRequests = (interactionId: string) => (
  apiClient.request<PartnerRequest[]>(`/api/interactions/${encodeURIComponent(interactionId)}/partner-requests`)
)

export const createPartnerRequest = (
  interactionId: string,
  payload: { contactId: string; action: string; expectedResult: string; dueOn: string | null; materialIds: string[] },
  idempotencyKey: string
) => apiClient.command<PartnerRequest>(`/api/interactions/${encodeURIComponent(interactionId)}/partner-requests`, payload, idempotencyKey)

const requestCommand = (id: string, action: 'review' | 'clarify' | 'cancel', payload: object, idempotencyKey: string) => (
  apiClient.command<PartnerRequest>(`/api/partner-requests/${encodeURIComponent(id)}/${action}`, payload, idempotencyKey)
)

export const reviewPartnerRequest = (
  id: string,
  payload: { version: number; decision: 'ACCEPTED' | 'RETURNED'; reason: string | null },
  idempotencyKey: string
) => requestCommand(id, 'review', payload, idempotencyKey)

export const clarifyPartnerRequest = (id: string, payload: { version: number; text: string }, idempotencyKey: string) => (
  requestCommand(id, 'clarify', payload, idempotencyKey)
)

export const cancelPartnerRequest = (id: string, payload: { version: number; reason: string | null }, idempotencyKey: string) => (
  requestCommand(id, 'cancel', payload, idempotencyKey)
)

export const listPartnerReviewQueue = () => apiClient.request<PartnerReviewItem[]>('/api/partner-requests/review')

export const respondPartnerRequest = (
  id: string,
  payload: { kind: 'ANSWER' | 'QUESTION'; version: number; message: string; file: File | null },
  idempotencyKey: string
) => {
  const form = new FormData()
  form.set('kind', payload.kind)
  form.set('version', String(payload.version))
  if (payload.message.trim().length > 0) {
    form.set('message', payload.message)
  }
  if (payload.file !== null) {
    form.set('file', payload.file)
  }
  return apiClient.upload<PartnerReceipt>(`/api/partner/requests/${encodeURIComponent(id)}/responses`, form, idempotencyKey)
}

export const confirmPartnerDocument = (id: string) => (
  apiClient.command<{ documentId: string; receivedAt: string }>(
    `/api/partner/documents/${encodeURIComponent(id)}/receipt`, undefined, createIdempotencyKey(), 'PUT'
  )
)
