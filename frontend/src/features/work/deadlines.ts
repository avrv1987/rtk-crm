import type { InteractionListItem, ReminderFeed } from '../../shared/api/client'
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

// Названия групп ленты — одни для «Моей работы» CRM и мини-приложения MAX (там на плитке короткая форма).
export const groupTitles: Record<FeedGroup, string> = {
  overdue: 'Просрочено',
  today: 'Сегодня',
  week: 'На этой неделе',
  later: 'Позже',
  noStep: 'Без следующего шага'
}

// Итоги групп из серверной сводки. «На этой неделе» — неделя без сегодняшнего дня: разность итогов, на сервере это
// отбор REST_OF_WEEK.
export const groupTotals = (feed: ReminderFeed): Record<FeedGroup, number> => ({
  overdue: feed.overdue,
  today: feed.today,
  week: Math.max(0, feed.thisWeek - feed.today),
  later: feed.later,
  noStep: feed.noNextStep
})

export type FeedPage = { items: InteractionListItem[] }

export type FeedGroupData = { items: InteractionListItem[]; total: number }

// Каждая группа ленты приходит отдельным запросом с тем же отбором due, что ссылка «Показать все в списке», а итог —
// из серверной сводки (ReminderDigest.feed), поэтому 120 просроченных не вытесняют группу «Сегодня».
// «На этой неделе» в ленте — неделя без сегодняшнего дня: строки берутся из отбора THIS_WEEK без сегодняшних,
// итог — разность серверных итогов.
export const feedGroups = (
  pages: Record<'overdue' | 'today' | 'week' | 'later' | 'noStep', FeedPage>,
  feed: ReminderFeed,
  now: number
): Map<FeedGroup, FeedGroupData> => {
  const restOfWeek = pages.week.items.filter((item) => item.nextActionAt !== null && deadlineGroup(item.nextActionAt, now) === 'week')
  const totals = groupTotals(feed)
  const groups: [FeedGroup, InteractionListItem[], number][] = [
    ['overdue', pages.overdue.items, totals.overdue],
    ['today', pages.today.items, totals.today],
    ['week', restOfWeek, totals.week],
    ['later', pages.later.items, totals.later],
    ['noStep', pages.noStep.items, totals.noStep]
  ]
  return new Map(groups
    .map(([key, items, total]): [FeedGroup, FeedGroupData] => [key, { items, total: Math.max(total, items.length) }])
    .filter(([, group]) => group.total > 0))
}

export const shownOf = (shown: number, total: number) => (total > shown ? `Показано ${shown} из ${total}` : null)

export type PausedGroups = { due: InteractionListItem[]; noDate: InteractionListItem[] }

// Приостановленное взаимодействие возвращается в ленту в день повторного касания (дата хранится сроком шага);
// приостановленные раньше без даты собираются отдельно, чтобы их не забыли.
export const pausedGroups = (paused: InteractionListItem[], now: number): PausedGroups => ({
  due: paused.filter((item) => item.nextActionAt !== null && moscowDay(new Date(item.nextActionAt).getTime()) <= moscowDay(now)),
  noDate: paused.filter((item) => item.nextActionAt === null)
})
