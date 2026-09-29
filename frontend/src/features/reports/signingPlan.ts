export type PlanKind = 'SIGNING' | 'RENEWAL'
export type PlanState = 'DONE' | 'OVERDUE' | 'UPCOMING'
export type PlanSource = 'PLAN' | 'EXPIRY'

export const planKinds: PlanKind[] = ['SIGNING', 'RENEWAL']

export const planKindLabels: Record<PlanKind, string> = {
  SIGNING: 'Подписание',
  RENEWAL: 'Продление'
}

export const planStateLabels: Record<PlanState, string> = {
  DONE: 'Выполнено',
  OVERDUE: 'Просрочено',
  UPCOMING: 'Впереди'
}

export const planSourceLabels: Record<PlanSource, string> = {
  PLAN: 'задана в плане',
  EXPIRY: 'срок действия соглашения'
}

const romanQuarters = ['I', 'II', 'III', 'IV']

export const quarterLabel = (quarter: number) => `${romanQuarters[quarter - 1]} квартал`

export const periodTitle = (year: number, quarter: number | undefined) => (
  quarter === undefined ? `${year} год` : `${quarterLabel(quarter)} ${year} года`
)

export const currentPeriod = (today: string) => ({
  year: Number(today.slice(0, 4)),
  quarter: Math.floor((Number(today.slice(5, 7)) - 1) / 3) + 1
})

export const yearOptions = (currentYear: number) => (
  [currentYear - 2, currentYear - 1, currentYear, currentYear + 1, currentYear + 2]
)

export const stateCounts = (rows: readonly { state: PlanState }[]) => ({
  done: rows.filter((row) => row.state === 'DONE').length,
  overdue: rows.filter((row) => row.state === 'OVERDUE').length,
  upcoming: rows.filter((row) => row.state === 'UPCOMING').length
})
