import type { InteractionListItem } from '../../shared/api/client'
import type { FeedGroup } from './workView'

export type DeadlineGroup = 'overdue' | 'today' | 'week' | 'later'

const dayMilliseconds = 24 * 60 * 60 * 1000
const moscowOffsetMilliseconds = 3 * 60 * 60 * 1000

const moscowDay = (time: number) => Math.floor((time + moscowOffsetMilliseconds) / dayMilliseconds)

export const deadlineGroup = (dueAt: string, now: number): DeadlineGroup => {
  const time = new Date(dueAt).getTime()
  if (time < now) {
    return 'overdue'
  }
  const today = moscowDay(now)
  const day = moscowDay(time)
  if (day === today) {
    return 'today'
  }
  const weekday = (new Date(today * dayMilliseconds).getUTCDay() + 6) % 7
  return day < today - weekday + 7 ? 'week' : 'later'
}

const hasStep = (item: Pick<InteractionListItem, 'nextAction' | 'nextActionAt'>) => (
  item.nextActionAt !== null && (item.nextAction?.trim() ?? '') !== ''
)

// Взаимодействие без текста шага или без срока попадает только в группу «Без следующего шага», чтобы одна строка
// не стояла в ленте дважды; число в группе совпадает с плиткой.
export const groupFeed = (
  dated: InteractionListItem[],
  noStep: InteractionListItem[],
  now: number
): Map<FeedGroup, InteractionListItem[]> => {
  const grouped = new Map<FeedGroup, InteractionListItem[]>()
  for (const item of dated.filter(hasStep)) {
    const key = deadlineGroup(item.nextActionAt ?? '', now)
    grouped.set(key, [...(grouped.get(key) ?? []), item])
  }
  if (noStep.length > 0) {
    grouped.set('noStep', noStep)
  }
  return grouped
}
