import { ApiError } from '../../shared/api/client'

const dateTime = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeStyle: 'short' })
const date = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeZone: 'UTC' })

export const formatDateTime = (value: string | null | undefined) => {
  if (value === null || value === undefined) {
    return 'нет'
  }
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : dateTime.format(parsed)
}

export const formatDate = (value: string | null | undefined) => (
  value === null || value === undefined ? 'нет' : date.format(new Date(`${value}T00:00:00Z`))
)

export const shiftDate = (value: string, days: number) => {
  const shifted = new Date(`${value}T00:00:00Z`)
  shifted.setUTCDate(shifted.getUTCDate() + days)
  return shifted.toISOString().slice(0, 10)
}

export const todayIso = () => {
  const now = new Date()
  return new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())).toISOString().slice(0, 10)
}

export const runPeriod = (startsOn: string | null | undefined, endsOn: string | null | undefined) => (
  !startsOn || !endsOn ? 'даты не подтверждены администратором' : `с ${formatDate(startsOn)} по ${formatDate(shiftDate(endsOn, -1))}`
)

export const errorText = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

export const fieldErrors = (error: unknown) => (
  error instanceof ApiError ? Object.values(error.fieldErrors ?? {}) : []
)

export const requestIdOf = (error: unknown) => (error instanceof ApiError ? error.requestId : undefined)

export const accessHandled = (
  error: unknown,
  onSessionExpired: () => void,
  onProfileUnavailable: (requestId: string) => void
) => {
  if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
    onSessionExpired()
    return true
  }
  if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
    onProfileUnavailable(error.requestId)
    return true
  }
  return false
}
