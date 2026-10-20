import type { LearningCoverage, PortfolioChangeRow, ReportPreviewRequest, TeamsSummary, WorkControl, WorkControlItem } from '../../shared/api/client'
import { shiftCalendarDate } from '../../shared/format/datetime.ts'
import type { AgreementRegistryRow } from '../agreements/agreementsApi'
import { registryTileCounts } from '../agreements/agreementModel.ts'
import { defaultStuckDays, isStuck, type ControlTile } from './controlModel.ts'

// Сводка «Руководства» (первый экран): команды рядом — число, знаменатель и доля, без общего балла (паспорта М01–М06,
// М10 в docs/metrics.md). Числа берутся из тех же ответов, что «Контроль» и реестр договоров, поэтому ссылка из
// числа открывает ровно эти строки.

export type Share = { count: number; base: number }

export type TeamOverview = {
  teamId: string
  teamName: string
  organizations: number
  unassigned: Share
  idle: Share
  works: number
  overdue: Share
  noNextStep: Share
  issues: Share
  stuck: Share
  review: { total: number; yellow: number; red: number }
  expiring: number
}

export type Change = { current: number; previous: number }

export type TeamChanges = {
  teamId: string
  teamName: string
  created: Change
  completed: Change
  moved: Change
  signed: Change
}

export type Period = { from: string; to: string }

// Неразрывный пробел: «10 %» не разрывается переносом в узкой колонке.
export const nbsp = String.fromCharCode(160)

const percent = (share: Share) => Math.round(share.count * 100 / share.base)

// При базе меньше 10 процент скачет от одного случая («1 из 3» — 33 %) и читается как вывод, поэтому показываем
// только числа (разбор §5.3, А08).
export const minPercentBase = 10

// «8 из 80 активных (10 %)»: абсолютное число всегда рядом со знаменателем, доля без знаменателя не показывается.
export const shareText = (share: Share, of: string) => {
  if (share.base === 0) {
    return `${share.count} — ${of}: нет`
  }
  const text = `${share.count} из ${share.base} ${of}`
  return share.base < minPercentBase ? text : `${text} (${percent(share)}${nbsp}%)`
}

const signed = (value: number) => (value > 0 ? `+${value}` : value < 0 ? `−${Math.abs(value)}` : '0')

// Рост от нуля процентом не выражается: «новое наблюдение» вместо бесконечного роста (разбор §5.3).
export const changeText = ({ current, previous }: Change) => {
  if (previous === 0) {
    return current === 0 ? 'без изменений' : 'новое наблюдение'
  }
  if (current === previous) {
    return 'без изменений'
  }
  if (previous < minPercentBase) {
    return signed(current - previous)
  }
  return `${signed(current - previous)} (${signed(Math.round((current - previous) * 100 / previous))}${nbsp}%)`
}

// Баланс состава (разбор §7.3): «было → стало» и из чего сложилась разница. Без процентов: это разложение, а не темп.
export const portfolioParts = (row: PortfolioChangeRow) => [
  `остались ${row.stayed}`,
  ...(row.created > 0 ? [`новые +${row.created}`] : []),
  ...(row.resumed > 0 ? [`возобновлены +${row.resumed}`] : []),
  ...(row.completed > 0 ? [`завершены −${row.completed}`] : []),
  ...(row.paused > 0 ? [`приостановлены −${row.paused}`] : [])
]

export const portfolioText = (row: PortfolioChangeRow) => `${row.atStart} → ${row.atEnd}: ${portfolioParts(row).join(', ')}`

// Индикатор доверия к данным обучения (разбор §5.4): знаменатель — идущие сопоставленные потоки, а не пришедшие данные.
export const coverageText = (coverage: LearningCoverage) => {
  if (coverage.expected === 0) {
    return 'Данные обучения: идущих сопоставленных потоков нет'
  }
  const toCheck = coverage.stale + coverage.withoutObservation
  return `Данные обучения доступны для ${coverage.fresh} из ${coverage.expected} идущих потоков`
    + (toCheck > 0 ? `; ${toCheck} требуют проверки` : '')
    + (coverage.sourceConfigured ? '' : '; Moodle не подключён, свежесть не проверяется')
}

// Окно сравнения — последние N дней по сегодня и N дней перед ними: окна одной длины, поэтому числа сопоставимы.
export const comparisonPeriods = (today: string, days: number): { current: Period; previous: Period } => {
  const from = shiftCalendarDate(today, -(days - 1))
  return {
    current: { from, to: today },
    previous: { from: shiftCalendarDate(from, -days), to: shiftCalendarDate(from, -1) }
  }
}

const teamItems = (control: WorkControl, teamId: string | null | undefined) => (
  control.teams.filter((team) => team.teamId === teamId).flatMap((team) => team.managers.flatMap((manager) => manager.works))
)

const count = (items: WorkControlItem[], match: (item: WorkControlItem) => boolean) => items.filter(match).length

const inProgress = new Set(['REVIEW', 'CORRECTION', 'SIGNING'])

// control — «Контроль» без фильтров и периода (идущие взаимодействия, М01), summary — вузы без КАМ по командам (М05),
// agreements — весь реестр договоров области.
export const teamOverviews = (
  control: WorkControl,
  summary: TeamsSummary | null,
  agreements: readonly AgreementRegistryRow[],
  today: string
): TeamOverview[] => control.teams
  .filter((team): team is typeof team & { teamId: string } => team.teamId !== null && team.teamId !== undefined)
  .map((team) => {
    const items = teamItems(control, team.teamId)
    const works = items.length
    const organizations = summary?.teams.find((row) => row.teamId === team.teamId)?.organizations ?? team.totals.organizations
    const own = agreements.filter((row) => row.teamId === team.teamId)
    const review = own.filter((row) => inProgress.has(row.status))
    return {
      teamId: team.teamId,
      teamName: team.teamName ?? 'Команда без названия',
      organizations,
      unassigned: {
        count: summary?.teams.find((row) => row.teamId === team.teamId)?.unassignedOrganizations ?? 0,
        base: organizations
      },
      idle: { count: team.totals.idle, base: organizations },
      works,
      overdue: { count: team.totals.overdue, base: count(items, (item) => item.nextActionAt !== null && item.nextActionAt !== undefined) },
      noNextStep: { count: team.totals.noNextStep, base: works },
      issues: { count: team.totals.withIssues, base: works },
      stuck: { count: count(items, (item) => isStuck(item, summary?.stuckDays ?? defaultStuckDays)), base: works },
      review: {
        total: review.length,
        yellow: review.filter((row) => row.approval?.color === 'YELLOW').length,
        red: review.filter((row) => row.approval?.color === 'RED').length
      },
      expiring: registryTileCounts(own, today).EXPIRING
    }
  })
  .sort((a, b) => a.teamName.localeCompare(b.teamName, 'ru'))

// Подписан в периоде — по дате подписания, как отчёт «Договоры за период» (AgreementRepository.findSignedBetween).
const signedIn = (rows: readonly AgreementRegistryRow[], period: Period) => rows.filter((row) => (
  (row.status === 'ACTIVE' || row.status === 'COMPLETED' || row.status === 'TERMINATED')
  && row.concludedOn !== null && row.concludedOn !== undefined && row.concludedOn >= period.from && row.concludedOn <= period.to
)).length

export const teamChanges = (
  current: WorkControl,
  previous: WorkControl,
  agreements: readonly AgreementRegistryRow[],
  periods: { current: Period; previous: Period }
): TeamChanges[] => {
  const teams = new Map<string, string>()
  for (const team of [...current.teams, ...previous.teams]) {
    if (team.teamId !== null && team.teamId !== undefined) {
      teams.set(team.teamId, team.teamName ?? 'Команда без названия')
    }
  }
  const totals = (control: WorkControl, teamId: string) => control.teams.find((team) => team.teamId === teamId)?.totals
  return [...teams.entries()]
    .map(([teamId, teamName]) => {
      const own = agreements.filter((row) => row.teamId === teamId)
      const now = totals(current, teamId)
      const before = totals(previous, teamId)
      return {
        teamId,
        teamName,
        created: { current: now?.created ?? 0, previous: before?.created ?? 0 },
        completed: { current: now?.completed ?? 0, previous: before?.completed ?? 0 },
        moved: { current: now?.moved ?? 0, previous: before?.moved ?? 0 },
        signed: { current: signedIn(own, periods.current), previous: signedIn(own, periods.previous) }
      }
    })
    .sort((a, b) => a.teamName.localeCompare(b.teamName, 'ru'))
}

// Ссылка из числа сводки: «Контроль» с отбором команды и плиткой — те же строки, что в числе.
export const teamHref = (teamId: string, tile: ControlTile | null, extra: Record<string, string> = {}) => {
  const params = new URLSearchParams(extra)
  if (tile !== null) {
    params.set('tile', tile)
  }
  params.set('team', teamId)
  return `#/control?${params.toString()}`
}

export const changeHref = (teamId: string, period: Period, change: 'NEW' | 'COMPLETED' | 'MOVED') => (
  `#/control?from=${period.from}&to=${period.to}&change=${change}&team=${teamId}`
)

export const registryHref = (teamId: string, tile: 'EXPIRING' | 'DELAYED') => `#/agreements?team=${teamId}&tile=${tile}`

// Блок «Топ-5 программ» в «Сводке команд» (решение владельца 21.10): тот же запрос отчёта «Востребованность программ»
// за последние 90 дней без отборов, строки по месту — расчёт тот же, что в отчёте, файле и MCP.
export const topDemandDays = 90
export const topDemandSize = 5

export const topDemandRequest = (from: string, to: string): ReportPreviewRequest => ({
  kind: 'DEMAND',
  from,
  to,
  asOf: null,
  periodBasis: null,
  filters: {
    organizationIds: [],
    directionIds: [],
    includeNoDirection: false,
    programIds: [],
    includeNoProgram: false,
    productIds: [],
    includeNoProduct: false,
    kindIds: [],
    managerIds: [],
    includeNoManager: false,
    stages: [],
    organizationType: null,
    workStatuses: [],
    flags: [],
    eventTypes: [],
    minDaysOnStage: null
  },
  columns: ['PROGRAM', 'DEMAND_RANK', 'DEMAND_INDEX', 'DEMAND_COVERAGE'],
  sortBy: 'DEMAND_INDEX'
})

export type TopDemandRow = { program: string; place: number; index: string; coverage: string }

// Программы без места («данных недостаточно») в топ не попадают: пустое место — не последнее место.
export const topDemandRows = (items: Record<string, string | number | null>[]): TopDemandRow[] => items
  .filter((item) => typeof item.DEMAND_RANK === 'number')
  .slice(0, topDemandSize)
  .map((item) => ({
    program: item.PROGRAM === null || item.PROGRAM === undefined ? 'Без программы' : String(item.PROGRAM),
    place: Number(item.DEMAND_RANK),
    index: item.DEMAND_INDEX === null || item.DEMAND_INDEX === undefined ? '—' : String(item.DEMAND_INDEX).replace('.', ','),
    coverage: item.DEMAND_COVERAGE === null || item.DEMAND_COVERAGE === undefined ? '' : String(item.DEMAND_COVERAGE)
  }))
