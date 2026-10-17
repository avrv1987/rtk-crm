export type PeriodPresetId = 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR' | 'PREVIOUS_MONTH'

export type PeriodPreset = {
  id: PeriodPresetId
  label: string
  from: string
  to: string
}

const utcDate = (value: string) => new Date(`${value}T00:00:00Z`)

const iso = (date: Date) => date.toISOString().slice(0, 10)

const monthRange = (year: number, month: number) => ({
  from: iso(new Date(Date.UTC(year, month, 1))),
  to: iso(new Date(Date.UTC(year, month + 1, 0)))
})

export const periodPresets = (today: string): PeriodPreset[] => {
  const date = utcDate(today)
  const year = date.getUTCFullYear()
  const month = date.getUTCMonth()
  const weekday = (date.getUTCDay() + 6) % 7
  const quarterStart = month - (month % 3)
  const monday = new Date(Date.UTC(year, month, date.getUTCDate() - weekday))
  const sunday = new Date(Date.UTC(year, month, date.getUTCDate() - weekday + 6))
  return [
    { id: 'WEEK', label: 'Эта неделя', from: iso(monday), to: iso(sunday) },
    { id: 'MONTH', label: 'Этот месяц', ...monthRange(year, month) },
    {
      id: 'QUARTER',
      label: 'Этот квартал',
      from: monthRange(year, quarterStart).from,
      to: monthRange(year, quarterStart + 2).to
    },
    { id: 'YEAR', label: 'Этот год', from: `${year}-01-01`, to: `${year}-12-31` },
    { id: 'PREVIOUS_MONTH', label: 'Прошлый месяц', ...monthRange(year, month - 1) }
  ]
}

export const matchingPreset = (from: string, to: string, today: string): PeriodPresetId | null => (
  periodPresets(today).find((preset) => preset.from === from && preset.to === to)?.id ?? null
)

type Period = { from: string; to: string }

const noPeriod: Period = { from: '', to: '' }

// «Портфель взаимодействий» обещает все работы сейчас, поэтому открывается без периода; остальные отчёты — с текущим месяцем.
export const defaultPeriod = (kind: string, today: string): Period => {
  const month = periodPresets(today)[1]
  return kind === 'PORTFOLIO' ? noPeriod : { from: month.from, to: month.to }
}

// Выбор периода общий для всех отчётов: при смене вида подменяем только нетронутое умолчание предыдущего вида.
export const periodOnKindChange = (period: Period, previousKind: string, nextKind: string, today: string): Period => {
  if (previousKind === nextKind) {
    return { from: period.from, to: period.to }
  }
  const month = defaultPeriod('EVENTS', today)
  const isMonth = period.from === month.from && period.to === month.to
  const isEmpty = period.from === '' && period.to === ''
  if (nextKind === 'PORTFOLIO' && isMonth) {
    return noPeriod
  }
  if (previousKind === 'PORTFOLIO' && isEmpty) {
    return month
  }
  return { from: period.from, to: period.to }
}

export const previousMonthEnd = (today: string) => {
  const date = utcDate(today)
  return iso(new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 0)))
}
