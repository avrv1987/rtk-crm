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
