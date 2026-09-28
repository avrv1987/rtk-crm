const MOSCOW_TZ = 'Europe/Moscow'
const moscowOffsetMilliseconds = 3 * 60 * 60 * 1000

const dateTimeFormatter = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short', timeZone: MOSCOW_TZ })
const calendarDateFormatter = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeZone: 'UTC' })
const isoDateInMoscow = new Intl.DateTimeFormat('en-CA', { timeZone: MOSCOW_TZ })
const isoDatePattern = /^\d{4}-\d{2}-\d{2}/

/**
 * Единое форматирование момента времени (ISO-строка с датой и временем) по московскому часовому поясу
 * с явной подписью «МСК», например «28 сент. 2026 г., 14:05 МСК». Значение не проверяет на null —
 * вызывающий код сам решает, каким текстом заменить отсутствующую дату (например, «нет», «не указана»).
 */
export const formatMoscowDateTime = (value: string): string => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : `${dateTimeFormatter.format(date)} МСК`
}

/**
 * Форматирование календарной даты (значения вида YYYY-MM-DD или первых 10 символов ISO-строки) без времени
 * и часового пояса — так хранятся даты окончания потоков, рождения и т. п.
 */
export const formatCalendarDate = (value: string): string => {
  const datePart = value.slice(0, 10)
  if (!isoDatePattern.test(datePart)) {
    return value
  }
  const date = new Date(`${datePart}T00:00:00Z`)
  return Number.isNaN(date.getTime()) ? value : calendarDateFormatter.format(date)
}

/**
 * Сегодняшняя календарная дата (YYYY-MM-DD) в московском часовом поясе — для value/max/min у `<input type="date">`,
 * чтобы «сегодня» не зависело от часового пояса браузера.
 */
export const todayInMoscow = (now: Date = new Date()): string => isoDateInMoscow.format(now)

/** Сдвиг календарной даты YYYY-MM-DD на заданное число дней (без часового пояса). */
export const shiftCalendarDate = (value: string, days: number): string => {
  const shifted = new Date(`${value}T00:00:00Z`)
  shifted.setUTCDate(shifted.getUTCDate() + days)
  return shifted.toISOString().slice(0, 10)
}

/**
 * Значение для `<input type="datetime-local">`, показывающее момент времени в московском часовом поясе
 * (не в поясе браузера), формат `YYYY-MM-DDTHH:mm`.
 */
export const moscowInputValue = (value: string | null): string => {
  if (value === null) {
    return ''
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Date(date.getTime() + moscowOffsetMilliseconds).toISOString().slice(0, 16)
}

/**
 * Обратное преобразование: значение `<input type="datetime-local">` трактуется как московское время
 * (а не время браузера) и переводится в ISO-momент (UTC).
 */
export const isoFromMoscowInput = (value: string): string | null => {
  if (value.length === 0) {
    return null
  }
  const date = new Date(`${value}:00+03:00`)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}
