import { apiClient, createIdempotencyKey } from '../../shared/api/client'
import type { components } from '../../shared/api/openapi'

export type HourUnit = components['schemas']['HourUnit']
export type PeopleSettings = components['schemas']['PeopleSettings']
export type PeopleSettingsRequest = components['schemas']['PeopleSettingsRequest']
export type PeopleOverview = components['schemas']['PeopleOverview']
export type TeachingInput = components['schemas']['TeachingInput']
export type TeachingRecord = components['schemas']['TeachingRecord']
export type InternshipInput = components['schemas']['InternshipInput']
export type InternshipRecord = components['schemas']['InternshipRecord']
export type QualificationInput = components['schemas']['QualificationInput']
export type QualificationRecord = components['schemas']['QualificationRecord']
export type PeopleImportResult = components['schemas']['PeopleImportResult']
export type PeopleReport = components['schemas']['PeopleReport']
export type PeopleKind = 'teaching' | 'internships' | 'qualifications'

const id = encodeURIComponent

const itemPath: Record<PeopleKind, string> = {
  teaching: 'university-teaching',
  internships: 'internships',
  qualifications: 'teacher-qualifications'
}

// Ключ повтора уходит заголовком, как у остальных команд клиента; эти пути его не требуют.
const send = <T>(path: string, payload: object | undefined, method: 'POST' | 'PUT' | 'DELETE') => (
  apiClient.command<T>(path, payload, createIdempotencyKey(), method)
)

export const peopleApi = {
  overview: (organizationId: string, interactionId?: string) => apiClient.request<PeopleOverview>(
    `/api/organizations/${id(organizationId)}/people${interactionId === undefined ? '' : `?interactionId=${id(interactionId)}`}`
  ),
  create: <T>(kind: PeopleKind, organizationId: string, payload: object) => (
    send<T>(`/api/organizations/${id(organizationId)}/${itemPath[kind]}`, payload, 'POST')
  ),
  update: <T>(kind: PeopleKind, recordId: string, payload: object) => send<T>(`/api/${itemPath[kind]}/${id(recordId)}`, payload, 'PUT'),
  remove: (kind: PeopleKind, recordId: string, version: number) => (
    send<void>(`/api/${itemPath[kind]}/${id(recordId)}?version=${version}`, undefined, 'DELETE')
  ),
  template: () => apiClient.download('/api/teacher-qualifications/import-template'),
  importQualifications: (organizationId: string, file: File, options: { dryRun: boolean; interactionId?: string; teacherRosterId?: string }) => {
    const query = new URLSearchParams({ dryRun: String(options.dryRun) })
    if (options.interactionId !== undefined) {
      query.set('interactionId', options.interactionId)
    }
    if (options.teacherRosterId !== undefined && options.teacherRosterId !== '') {
      query.set('teacherRosterId', options.teacherRosterId)
    }
    const form = new FormData()
    form.set('file', file)
    return apiClient.upload<PeopleImportResult>(
      `/api/organizations/${id(organizationId)}/teacher-qualifications/import?${query.toString()}`, form, createIdempotencyKey()
    )
  },
  settings: () => apiClient.request<PeopleSettings>('/api/people-settings'),
  saveSettings: (payload: PeopleSettingsRequest) => send<PeopleSettings>('/api/admin/people-settings', payload, 'PUT'),
  resetSettings: () => send<void>('/api/admin/people-settings', undefined, 'DELETE'),
  report: (from: string, to: string) => apiClient.request<PeopleReport>(`/api/reports/people?from=${id(from)}&to=${id(to)}`)
}

export const hourUnitLabels: Record<HourUnit, string> = {
  ACADEMIC: 'академический час (45 минут)',
  ASTRONOMICAL: 'астрономический час (60 минут)'
}

export const confirmationLabels: Record<TeachingRecord['confirmationStatus'], string> = {
  NOT_REQUESTED: 'не запрашивалось',
  REQUESTED: 'запрошено у вуза',
  CONFIRMED: 'подтверждено вузом',
  REJECTED: 'вуз не подтвердил'
}

export const internKindLabels: Record<InternshipRecord['internKind'], string> = {
  STUDENT: 'студент',
  TEACHER: 'преподаватель'
}

export const internshipResultLabels: Record<InternshipRecord['result'], string> = {
  PLANNED: 'запланирована',
  IN_PROGRESS: 'идёт',
  COMPLETED: 'завершена',
  NOT_COMPLETED: 'не завершена'
}

export const qualificationResultLabels: Record<QualificationRecord['result'], string> = {
  IN_PROGRESS: 'обучается',
  CERTIFIED: 'аттестован',
  NOT_CERTIFIED: 'не аттестован'
}
