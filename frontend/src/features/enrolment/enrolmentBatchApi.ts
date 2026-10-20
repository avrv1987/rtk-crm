import { apiClient } from '../../shared/api/client'
import type { components } from '../../shared/api/openapi'
import { enrolmentQueueSearch, type EnrolmentQueueQuery } from './enrolmentQueueModel'

export type EnrolmentBatch = components['schemas']['EnrolmentBatch']
export type EnrolmentBatchItem = components['schemas']['EnrolmentBatchItem']
export type BatchResultsApplied = components['schemas']['BatchResultsApplied']
export type BatchItemResult = components['schemas']['BatchItemResult']
export type EnrolmentQueue = components['schemas']['EnrolmentQueue']
export type EnrolmentQueueItem = components['schemas']['EnrolmentQueueItem']
export type EnrolmentQueueKind = components['schemas']['EnrolmentQueueKind']
export type RosterFormat = components['schemas']['RosterFormat']

const batchPath = (id: string) => `/api/enrolment/batches/${encodeURIComponent(id)}`

export const getEnrolmentBatch = (id: string) => apiClient.request<EnrolmentBatch>(batchPath(id))

export const recordBatchResults = (
  id: string,
  items: Array<{ itemId: string; result: BatchItemResult; error: string | null }>,
  idempotencyKey: string
) => apiClient.command<BatchResultsApplied>(`${batchPath(id)}/results`, { items }, idempotencyKey)

export const importBatchProtocol = (id: string, file: File, idempotencyKey: string) => {
  const form = new FormData()
  form.set('file', file)
  return apiClient.upload<BatchResultsApplied>(`${batchPath(id)}/protocol`, form, idempotencyKey)
}

export const getEnrolmentQueue = (query: EnrolmentQueueQuery = {}) => (
  apiClient.request<EnrolmentQueue>(`/api/enrolment/queue${enrolmentQueueSearch(query)}`)
)

export const reconcileEnrolmentLms = (
  enrolmentId: string,
  payload: { kind: 'MOVED_AFTER_TRANSFER' | 'CHANGED_AFTER_TRANSFER'; learnerVersion: number | null },
  idempotencyKey: string
) => apiClient.command<{ enrolmentId: string; kind: EnrolmentQueueKind }>(
  `/api/enrolment/enrolments/${encodeURIComponent(enrolmentId)}/lms-reconciled`, payload, idempotencyKey
)

export type MoodleTransferResult = {
  batch: EnrolmentBatch
  sent: number
  accepted: number
  failed: number
  confirmed: number
  stoppedReason: string | null
}

export const transferBatchToMoodle = (id: string, idempotencyKey: string) => (
  apiClient.command<MoodleTransferResult>(`${batchPath(id)}/moodle-transfer`, undefined, idempotencyKey)
)

export const moveEnrolmentInMoodle = (enrolmentId: string, idempotencyKey: string) => (
  apiClient.command<{ enrolmentId: string; courseFrom: number; courseTo: number }>(
    `/api/enrolment/enrolments/${encodeURIComponent(enrolmentId)}/moodle-move`, undefined, idempotencyKey
  )
)
