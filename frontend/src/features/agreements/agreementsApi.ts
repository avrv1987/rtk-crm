import { apiClient, type Organization } from '../../shared/api/client'
import type { components } from '../../shared/api/openapi'
import { formatMoscowDateTime } from '../../shared/format/datetime'

export type AgreementStatus = components['schemas']['AgreementStatus']
export type ActivityStatus = components['schemas']['AgreementActivityStatus']
export type ActivityKind = components['schemas']['AgreementActivityKind']
export type AgreementSummary = components['schemas']['AgreementSummary']
export type Agreement = components['schemas']['Agreement']
export type AgreementInput = components['schemas']['AgreementInput']
export type AgreementActivity = components['schemas']['AgreementActivity']
export type AgreementActivityInput = components['schemas']['AgreementActivityInput']
export type AgreementOptions = components['schemas']['AgreementOptions']
export type AgreementConfirmation = components['schemas']['AgreementConfirmation']

export type ConfirmationQuery = {
  organizationId?: string
  agreementId?: string
  kindId?: string
  from?: string
  to?: string
}

export const agreementStatusLabels: Record<AgreementStatus, string> = {
  DRAFT: 'Проект',
  ACTIVE: 'Действует',
  COMPLETED: 'Завершено',
  TERMINATED: 'Расторгнуто'
}

export const activityStatusLabels: Record<ActivityStatus, string> = {
  PLANNED: 'Запланировано',
  IN_PROGRESS: 'Выполняется',
  DONE: 'Выполнено',
  CANCELLED: 'Отменено'
}

const dateFormatter = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short' })

export const formatDate = (value: string | null | undefined) => {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  const [year, month, day] = value.split('-').map(Number)
  return dateFormatter.format(new Date(year, month - 1, day))
}

export const formatInstant = formatMoscowDateTime

export const formatPeriod = (start: string | null | undefined, end: string | null | undefined) => {
  if (!start && !end) {
    return '—'
  }
  if (start && start === end) {
    return formatDate(start)
  }
  return `${start ? formatDate(start) : '…'} – ${end ? formatDate(end) : '…'}`
}

const confirmationSearch = (query: ConfirmationQuery) => {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') {
      params.set(key, value)
    }
  }
  return params.size === 0 ? '' : `?${params.toString()}`
}

const path = (id: string) => encodeURIComponent(id)

export const agreementsApi = {
  listKinds: (includeArchived = false) => apiClient.request<ActivityKind[]>(
    `/api/agreement-activity-kinds${includeArchived ? '?includeArchived=true' : ''}`
  ),
  createKind: (name: string, key: string) => apiClient.command<ActivityKind>('/api/admin/agreement-activity-kinds', { name }, key),
  updateKind: (id: string, payload: { version: number; name: string; archived: boolean }, key: string) => (
    apiClient.command<ActivityKind>(`/api/admin/agreement-activity-kinds/${path(id)}`, payload, key, 'PATCH')
  ),
  list: (organizationId: Organization['id']) => apiClient.request<AgreementSummary[]>(`/api/organizations/${path(organizationId)}/agreements`),
  options: (organizationId: Organization['id']) => apiClient.request<AgreementOptions>(
    `/api/organizations/${path(organizationId)}/agreement-options`
  ),
  create: (organizationId: Organization['id'], payload: AgreementInput, key: string) => (
    apiClient.command<Agreement>(`/api/organizations/${path(organizationId)}/agreements`, payload, key)
  ),
  get: (id: string) => apiClient.request<Agreement>(`/api/agreements/${path(id)}`),
  update: (id: string, payload: AgreementInput, key: string) => (
    apiClient.command<Agreement>(`/api/agreements/${path(id)}`, payload, key, 'PATCH')
  ),
  createActivity: (agreementId: string, payload: AgreementActivityInput, key: string) => (
    apiClient.command<AgreementActivity>(`/api/agreements/${path(agreementId)}/activities`, payload, key)
  ),
  updateActivity: (id: string, payload: AgreementActivityInput, key: string) => (
    apiClient.command<AgreementActivity>(`/api/agreement-activities/${path(id)}`, payload, key, 'PATCH')
  ),
  deleteActivity: (id: string, version: number, key: string) => apiClient.command<void>(
    `/api/agreement-activities/${path(id)}?${new URLSearchParams({ version: version.toString() })}`,
    undefined,
    key,
    'DELETE'
  ),
  confirmations: (query: ConfirmationQuery) => apiClient.request<AgreementConfirmation[]>(
    `/api/agreement-confirmations${confirmationSearch(query)}`
  ),
  archive: (query: ConfirmationQuery) => apiClient.download(`/api/agreement-confirmations/archive${confirmationSearch(query)}`)
}

export const saveBlob = (blob: Blob, fileName: string) => {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}
