import { ApiError, apiClient, createIdempotencyKey, type Attachment, type Interaction } from '../../shared/api/client'
import type { components } from '../../shared/api/openapi'

export type AttachmentKind = components['schemas']['AttachmentKind']
export type ProductTransfer = components['schemas']['ProductTransfer']
export type ProductTransferKind = components['schemas']['ProductTransferKind']
export type ProductAgreementUpdate = components['schemas']['ProductAgreementUpdate']
export type ReportAgreementFilters = components['schemas']['ReportAgreementFilters']
export type CatalogReference = components['schemas']['CatalogReference']

export const attachmentKinds: readonly AttachmentKind[] = [
  'CONTRACT', 'LICENSE_AGREEMENT', 'APPENDIX', 'ACT', 'SIGNED_SCAN',
  'MATERIALS', 'DOCUMENTATION', 'CURRICULUM', 'QUALIFICATION', 'OTHER'
]

export const attachmentKindLabels: Record<AttachmentKind, string> = {
  CONTRACT: 'Договор',
  LICENSE_AGREEMENT: 'Лицензионное соглашение',
  APPENDIX: 'Приложение',
  ACT: 'Акт',
  SIGNED_SCAN: 'Скан подписанного документа',
  MATERIALS: 'Учебные материалы',
  DOCUMENTATION: 'Документация продукта',
  CURRICULUM: 'Рабочая программа',
  QUALIFICATION: 'Документ о повышении квалификации',
  OTHER: 'Иное'
}

export const transferKinds: readonly ProductTransferKind[] = ['MATERIALS', 'LICENSE', 'DOCUMENTATION']

export const transferKindLabels: Record<ProductTransferKind, string> = {
  MATERIALS: 'Материалы',
  LICENSE: 'Лицензия',
  DOCUMENTATION: 'Документация'
}

const previewMediaTypes = new Set(['application/pdf', 'image/png', 'image/jpeg'])

export const canPreview = (attachment: Attachment) => (
  attachment.status === 'CLEAN' && previewMediaTypes.has(attachment.mediaType)
)

export const previewUrl = (id: Attachment['id']) => `/api/attachments/${encodeURIComponent(id)}/preview`

export const updateAttachmentKind = (id: Attachment['id'], version: number, kind: AttachmentKind) => (
  apiClient.command<Attachment>(`/api/attachments/${encodeURIComponent(id)}`, { version, kind }, createIdempotencyKey(), 'PATCH')
)

export const updateAttachmentPartnerVisible = (id: Attachment['id'], version: number, partnerVisible: boolean) => (
  apiClient.command<Attachment>(`/api/attachments/${encodeURIComponent(id)}`, { version, partnerVisible }, createIdempotencyKey(), 'PATCH')
)

export const deleteAttachment = (
  interactionId: Interaction['id'],
  attachmentId: Attachment['id'],
  payload: { version: number; reason: string | null },
  idempotencyKey: string
) => apiClient.command<Interaction>(
  `/api/interactions/${encodeURIComponent(interactionId)}/attachments/${encodeURIComponent(attachmentId)}/deletion`,
  payload,
  idempotencyKey
)

export const updateProductAgreement = (
  interactionId: Interaction['id'],
  agreementId: string,
  payload: ProductAgreementUpdate,
  idempotencyKey: string
) => apiClient.command<Interaction>(
  `/api/interactions/${encodeURIComponent(interactionId)}/product-agreements/${encodeURIComponent(agreementId)}`,
  payload,
  idempotencyKey,
  'PATCH'
)

export const listReportVendors = () => apiClient.request<CatalogReference[]>('/api/report-filters/vendors')

export const attachmentAccept = '.png,.jpg,.jpeg,.heic,.heif,.pdf,.zip,.gz,.gzip,.rar,.doc,.docx,.xls,.xlsx'

export const attachmentFormats = 'PNG, JPEG, HEIC, PDF, ZIP, GZIP, RAR, DOC, DOCX, XLS или XLSX'

export type AccessHandlers = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

export const handledAccessError = (error: unknown, handlers: AccessHandlers) => {
  if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
    handlers.onSessionExpired()
    return true
  }
  if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
    handlers.onProfileUnavailable(error.requestId)
    return true
  }
  return false
}

export const commandMessage = (error: unknown) => {
  if (error instanceof ApiError && error.status === 409 && error.code === 'VERSION_CONFLICT') {
    return `${error.message}. Обновите карточку и повторите действие.`
  }
  if (error instanceof ApiError) {
    const field = error.fieldErrors === undefined ? undefined : Object.values(error.fieldErrors)[0]
    return field ?? error.message
  }
  return 'Не удалось связаться с сервисом. Повторите попытку позже.'
}

export const saveAttachment = async (attachment: Attachment) => {
  const blob = await apiClient.downloadAttachment(attachment.id)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = attachment.originalName
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}
