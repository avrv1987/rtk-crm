// Чистые правила мини-приложения MAX: группы, подписи сроков, быстрые сроки, порядок команды. Без React и без API,
// поэтому проверяются node --test.
import { deadlineGroup, groupTitles, groupTotals } from '../features/work/deadlines.ts'
import type { FeedGroup } from '../features/work/workView'
import { markBadges } from '../features/interactions/markLabels.ts'
import type { InteractionMarks, ManagerIndicators, ReminderFeed } from '../shared/api/client'

import type { Due } from './maxApi'

export type Group = { key: FeedGroup; due: Due; tile: string; title: string; empty: [string, string] }

// Порядок плиток и выбора первой непустой группы утверждён владельцем: «Без шага» раньше «Позже». Тот же порядок —
// у сервера, когда due не передан (MaxApiController.firstGroup).
export const groups: Group[] = [
  { key: 'overdue', due: 'OVERDUE', tile: 'Просрочено', title: groupTitles.overdue, empty: ['Просроченных шагов нет', 'Все сроки соблюдены.'] },
  { key: 'today', due: 'TODAY', tile: 'Сегодня', title: groupTitles.today, empty: ['На сегодня шагов нет', 'Сроков на сегодня в ваших взаимодействиях нет.'] },
  { key: 'week', due: 'REST_OF_WEEK', tile: 'На неделе', title: groupTitles.week, empty: ['До конца недели шагов нет', 'Сроков на эту неделю нет.'] },
  { key: 'noStep', due: 'NO_NEXT_STEP', tile: 'Без шага', title: groupTitles.noStep, empty: ['У всех взаимодействий есть шаг', 'Следующий шаг задан везде.'] },
  { key: 'later', due: 'LATER', tile: 'Позже', title: groupTitles.later, empty: ['Дальних сроков нет', 'Шагов со сроком позже этой недели нет.'] }
]

export const groupOf = (key: FeedGroup) => groups.find((group) => group.key === key)!

/** Группа по due из ответа сервера; THIS_WEEK старых клиентов — та же плитка «На неделе». */
export const groupOfDue = (due: string): FeedGroup => groups.find((group) => group.due === due)?.key ?? (due === 'THIS_WEEK' ? 'week' : 'today')

export const totalsOf = (feed: ReminderFeed) => groupTotals(feed)

/** Первая непустая группа в утверждённом порядке; null — пусто везде («Всё сделано»). */
export const firstNonEmpty = (totals: Record<FeedGroup, number>): FeedGroup | null => groups.find((group) => totals[group.key] > 0)?.key ?? null

export type Tone = 'danger' | 'warning' | 'info' | 'success' | 'neutral'
export type Chip = { tone: Tone; label: string }

const minute = 60_000
const day = 24 * 60 * minute
const moscowShift = 3 * 60 * minute
const moscowDay = (time: number) => Math.floor((time + moscowShift) / day)
const moscow = (time: number) => new Date(time + moscowShift)
const two = (value: number) => String(value).padStart(2, '0')
const weekdays = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб']
const months = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']

const clock = (date: Date) => `${two(date.getUTCHours())}:${two(date.getUTCMinutes())}`

/** Метка срока шага по Москве: насколько просрочено или когда, коротко. */
export const dueChip = (nextActionAt: string | null, now: number): Chip => {
  if (nextActionAt === null) {
    return { tone: 'warning', label: 'Срок не задан' }
  }
  const time = Date.parse(nextActionAt)
  if (time < now) {
    const minutes = Math.max(1, Math.floor((now - time) / minute))
    const label = minutes < 60 ? `${minutes} мин` : minutes < 24 * 60 ? `${Math.floor(minutes / 60)} ч` : `${Math.floor(minutes / (24 * 60))} дн.`
    return { tone: 'danger', label: `Просрочено на ${label}` }
  }
  const date = moscow(time)
  const days = moscowDay(time) - moscowDay(now)
  const dayMonth = `${two(date.getUTCDate())}.${two(date.getUTCMonth() + 1)}`
  if (days === 0) {
    return { tone: 'info', label: `Сегодня, ${clock(date)}` }
  }
  if (days === 1) {
    return { tone: 'info', label: `Завтра, ${clock(date)}` }
  }
  if (deadlineGroup(nextActionAt, now) === 'week') {
    return { tone: 'info', label: `${weekdays[date.getUTCDay()]} ${dayMonth}, ${clock(date)}` }
  }
  return date.getUTCFullYear() === moscow(now).getUTCFullYear()
    ? { tone: 'info', label: `${dayMonth}, ${clock(date)}` }
    : { tone: 'info', label: `${dayMonth}.${date.getUTCFullYear()}` }
}

const markTone = { overdue: 'danger', missing: 'warning', planned: 'neutral' } as const

/** Отметки работы метками; в строке списка — две самые острые (сначала риск и проблемы). */
export const markChips = (marks: InteractionMarks, limit = Infinity): Chip[] => {
  const order: Tone[] = ['danger', 'warning', 'neutral']
  return markBadges(marks)
    .map((badge): Chip => ({ tone: markTone[badge.tone], label: badge.label }))
    .sort((left, right) => order.indexOf(left.tone) - order.indexOf(right.tone))
    .slice(0, limit)
}

export type QuickDue = 'tomorrow' | 'd3' | 'week'

export const quickDues: [QuickDue, string, number][] = [['tomorrow', 'Завтра', 1], ['d3', 'Через 3 дня', 3], ['week', 'Через неделю', 7]]

/** Быстрый срок: календарные дни, 10:00 МСК, выходной переносится на понедельник. Значение — как у полей даты и времени. */
export const quickDue = (kind: QuickDue, now: number): { local: string; moved: boolean } => {
  const offset = quickDues.find(([key]) => key === kind)![2]
  let target = moscowDay(now) + offset
  const weekday = new Date(target * day).getUTCDay()
  const moved = weekday === 0 || weekday === 6
  target += weekday === 6 ? 2 : weekday === 0 ? 1 : 0
  return { local: `${new Date(target * day).toISOString().slice(0, 10)}T10:00`, moved }
}

/** «Ср, 21 октября, 10:00 МСК» — расшифровка срока из полей даты и времени. */
export const dueText = (local: string): string | null => {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}:\d{2})$/.exec(local)
  if (match === null) {
    return null
  }
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])))
  return `${weekdays[date.getUTCDay()]}, ${date.getUTCDate()} ${months[date.getUTCMonth()]}, ${match[4]} МСК`
}

/** «Вторник, 20 октября» для подзаголовка «Моей работы». */
export const todayText = (now: number) => {
  const date = moscow(now)
  const names = ['Воскресенье', 'Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница', 'Суббота']
  return `${names[date.getUTCDay()]}, ${date.getUTCDate()} ${months[date.getUTCMonth()]}`
}

/** «пн 18.10, 14:00» — когда именно был срок просроченного шага. */
export const moscowShort = (iso: string) => {
  const date = moscow(Date.parse(iso))
  return `${weekdays[date.getUTCDay()].toLowerCase()} ${two(date.getUTCDate())}.${two(date.getUTCMonth() + 1)}, ${clock(date)}`
}

export const moscowClock = (iso: string) => clock(moscow(Date.parse(iso)))

export const plural = (count: number, forms: [string, string, string]) => {
  const tens = count % 100
  const ones = count % 10
  const form = tens >= 11 && tens <= 14 ? forms[2] : ones === 1 ? forms[0] : ones >= 2 && ones <= 4 ? forms[1] : forms[2]
  return `${count} ${form}`
}

const sharpness = (manager: ManagerIndicators) => manager.overdue * 3 + manager.atRisk * 2 + manager.stuck + manager.withoutNextStep

/** «Сначала — у кого горит»: по остроте, затем по имени; «Без ответственного» всегда последней строкой. */
export const teamOrder = (managers: ManagerIndicators[]) => [...managers].sort((left, right) => (
  Number(left.managerId === null) - Number(right.managerId === null)
  || sharpness(right) - sharpness(left)
  || (left.managerName ?? '').localeCompare(right.managerName ?? '', 'ru')
))

export const managerChips = (manager: ManagerIndicators): Chip[] => {
  const chips: Chip[] = []
  if (manager.overdue > 0) chips.push({ tone: 'danger', label: `просрочено ${manager.overdue}` })
  if (manager.atRisk > 0) chips.push({ tone: 'danger', label: `в риске ${manager.atRisk}` })
  if (manager.stuck > 0) chips.push({ tone: 'warning', label: `застряли ${manager.stuck}` })
  if (manager.withoutNextStep > 0) chips.push({ tone: 'warning', label: `без шага ${manager.withoutNextStep}` })
  return chips.length > 0 ? chips : [{ tone: 'success', label: 'всё в порядке' }]
}
