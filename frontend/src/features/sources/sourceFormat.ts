import { ApiError } from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime, shiftCalendarDate, todayInMoscow } from '../../shared/format/datetime'

export const formatDateTime = (value: string | null | undefined) => (
  value === null || value === undefined ? 'нет' : formatMoscowDateTime(value)
)

export const formatDate = (value: string | null | undefined) => (
  value === null || value === undefined ? 'нет' : formatCalendarDate(value)
)

export const shiftDate = shiftCalendarDate

export const todayIso = todayInMoscow

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

export const scheduleLabel = (cron: string) => {
  const [second, minute, hour, day, month, weekday] = cron.split(/\s+/)
  if (second === '0' && day === '*' && month === '*' && weekday === '*') {
    if (hour === '*' && /^\d+$/.test(minute)) {
      return `каждый час в ${minute.padStart(2, '0')} мин.`
    }
    if (/^\d+$/.test(hour) && /^\d+$/.test(minute)) {
      return `ежедневно в ${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`
    }
  }
  return `по расписанию «${cron}»`
}
