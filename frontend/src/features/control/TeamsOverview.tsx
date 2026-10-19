import { useCallback, useEffect, useState } from 'react'
import { ApiError, apiClient, type LearningCoverage, type PortfolioChange, type TeamsSummary, type WorkControl } from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime, todayInMoscow } from '../../shared/format/datetime'
import { HintButton } from '../../shared/ui/HintButton'
import { Segmented } from '../../shared/ui/Segmented'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { terms } from '../../shared/ui/terms'
import { agreementsApi, type AgreementRegistryRow } from '../agreements/agreementsApi'
import { emptyRegistryFilters } from '../agreements/agreementModel'
import { defaultStuckDays, emptyControlFilters } from './controlModel'
import {
  changeHref,
  changeText,
  comparisonPeriods,
  coverageText,
  portfolioParts,
  registryHref,
  shareText,
  teamChanges,
  teamHref,
  teamOverviews,
  type Change,
  type Period,
  type Share
} from './overviewModel'
import './control.css'

type OverviewProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Loaded = {
  control: WorkControl
  summary: TeamsSummary
  agreements: AgreementRegistryRow[]
  current: WorkControl
  previous: WorkControl
  periods: { current: Period; previous: Period }
  portfolio: PortfolioChange | null
  coverage: LearningCoverage | null
}

type State = { kind: 'loading' } | { kind: 'ready'; data: Loaded } | { kind: 'failed'; error: unknown }

type Window = '30' | '90'

const windowOptions: { value: Window; label: string }[] = [
  { value: '30', label: '30 дней' },
  { value: '90', label: '90 дней' }
]

const ShareLink = ({ share, of, href, tone }: { share: Share; of: string; href: string; tone?: boolean }) => (
  share.count === 0
    ? <span className="control-muted">{shareText(share, of)}</span>
    : <a className={tone ? 'control-totals__overdue' : undefined} href={href}>{shareText(share, of)}</a>
)

const ChangeCell = ({ change, href }: { change: Change; href?: string }) => (
  <>
    {href !== undefined && change.current > 0 ? <a href={href}>{change.current}</a> : <span>{change.current}</span>}
    <span className="control-muted">{`было ${change.previous} · ${changeText(change)}`}</span>
  </>
)

const periodText = (period: Period) => `${formatCalendarDate(period.from)} – ${formatCalendarDate(period.to)}`

export const TeamsOverview = ({ onSessionExpired, onProfileUnavailable }: OverviewProps) => {
  const [days, setDays] = useState<Window>('30')
  const [state, setState] = useState<State>({ kind: 'loading' })

  const load = useCallback(async (window: Window) => {
    setState({ kind: 'loading' })
    const periods = comparisonPeriods(todayInMoscow(), Number(window))
    try {
      // Состав и покрытие — дополнительные блоки: их сбой не скрывает основную сводку.
      const [control, summary, agreements, current, previous, portfolio, coverage] = await Promise.all([
        apiClient.getWorkControl(emptyControlFilters),
        apiClient.getTeamsSummary(defaultStuckDays),
        agreementsApi.registry(emptyRegistryFilters),
        apiClient.getWorkControl({ ...emptyControlFilters, ...periods.current }),
        apiClient.getWorkControl({ ...emptyControlFilters, ...periods.previous }),
        apiClient.getPortfolioChange(periods.current.from, periods.current.to).catch(() => null),
        apiClient.getLearningCoverage().catch(() => null)
      ])
      setState({ kind: 'ready', data: { control, summary, agreements, current, previous, periods, portfolio, coverage } })
    } catch (error) {
      if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
        onSessionExpired()
        return
      }
      if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
        onProfileUnavailable(error.requestId)
        return
      }
      setState({ kind: 'failed', error })
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load(days)
  }, [days, load])

  if (state.kind === 'loading') {
    return <p className="control-muted" role="status">Собираем сводку команд…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="notice notice--error" role="alert">
        <p>Не удалось собрать сводку команд.</p>
        {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
        <button type="button" onClick={() => void load(days)}>{terms.retry}</button>
      </div>
    )
  }
  const { data } = state
  const today = todayInMoscow()
  const teams = teamOverviews(data.control, data.summary, data.agreements, today)
  const changes = teamChanges(data.current, data.previous, data.agreements, data.periods)

  return (
    <section className="control overview" aria-label="Сводка команд">
      <div className="control-toolbar">
        <HintButton label="Что на этом экране">
          Команды рядом: у каждого числа — из скольких и доля, общего балла нет. Активные — идущие взаимодействия;
          просрочено — из взаимодействий со сроком следующего шага; вузы без КАМ и без работы — из вузов команды.
          Договоры — по реестру, вместе с не привязанными к взаимодействию. Изменения — последние 30 или 90 дней против
          стольких же дней перед ними; если раньше было 0, пишется «новое наблюдение», при базе меньше 10 процент не
          считается. Состав активных — сколько взаимодействий было активно на начало окна и сегодня и из чего сложилась
          разница. Число открывает те же строки в «Контроле» или в реестре договоров.
        </HintButton>
        <Segmented label="Окно сравнения" options={windowOptions} value={days} onChange={setDays} />
        <a className="button-link" href="#/control">Контроль по всем командам</a>
      </div>
      <p className="control-status">{`данные на ${formatMoscowDateTime(data.control.calculatedAt)}`}</p>
      {data.coverage !== null && <p className="control-muted">{coverageText(data.coverage)}</p>}

      <h2 className="overview__title">Команды сейчас</h2>
      {teams.length === 0 ? <p className="control-empty">Команд с вузами нет.</p> : (
        <table className="control-table overview__table">
          <thead>
            <tr>
              <th scope="col">Команда</th>
              <th scope="col">Активные взаимодействия</th>
              <th scope="col">Просрочено</th>
              <th scope="col">Без шага или срока</th>
              <th scope="col">{`На этапе дольше ${defaultStuckDays} дн.`}</th>
              <th scope="col">Проблемы и риски</th>
              <th scope="col">Вузы без КАМ</th>
              <th scope="col">Вузы без работы</th>
            </tr>
          </thead>
          <tbody>
            {teams.map((team) => (
              <tr key={team.teamId}>
                <th scope="row" data-label="Команда">{team.teamName}</th>
                <td data-label="Активные взаимодействия">
                  {team.works === 0 ? <span className="control-muted">0</span> : <a href={teamHref(team.teamId, null)}>{team.works}</a>}
                </td>
                <td data-label="Просрочено"><ShareLink share={team.overdue} of="со сроком" href={teamHref(team.teamId, 'OVERDUE')} tone /></td>
                <td data-label="Без шага или срока"><ShareLink share={team.noNextStep} of="активных" href={teamHref(team.teamId, 'NO_NEXT_STEP')} /></td>
                <td data-label={`На этапе дольше ${defaultStuckDays} дн.`}><ShareLink share={team.stuck} of="активных" href={teamHref(team.teamId, 'STUCK')} /></td>
                <td data-label="Проблемы и риски"><ShareLink share={team.issues} of="активных" href={teamHref(team.teamId, 'ISSUES')} tone /></td>
                <td data-label="Вузы без КАМ"><ShareLink share={team.unassigned} of="вузов" href={teamHref(team.teamId, 'UNASSIGNED')} tone /></td>
                <td data-label="Вузы без работы"><ShareLink share={team.idle} of="вузов" href={teamHref(team.teamId, 'IDLE')} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2 className="overview__title">Договоры по командам</h2>
      <table className="control-table overview__table">
        <thead>
          <tr>
            <th scope="col">Команда</th>
            <th scope="col">На согласовании, корректировке и подписании</th>
            <th scope="col">Истёк или истекает за 90 дней</th>
          </tr>
        </thead>
        <tbody>
          {teams.map((team) => (
            <tr key={team.teamId}>
              <th scope="row" data-label="Команда">{team.teamName}</th>
              <td data-label="На согласовании">
                {team.review.total === 0 ? <span className="control-muted">0</span> : <a href={teamHref(team.teamId, 'AGREEMENT')}>{team.review.total}</a>}
                {team.review.red > 0 && <span className="status status--overdue">{` красных ${team.review.red}`}</span>}
                {team.review.yellow > 0 && <span className="status status--missing">{` жёлтых ${team.review.yellow}`}</span>}
              </td>
              <td data-label="Истекает за 90 дней">
                {team.expiring === 0 ? <span className="control-muted">0</span> : <a href={registryHref(team.teamId, 'EXPIRING')}>{team.expiring}</a>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2 className="overview__title">Изменение к прошлому периоду</h2>
      <p className="control-muted">{`${periodText(data.periods.current)} против ${periodText(data.periods.previous)}`}</p>
      <table className="control-table overview__table">
        <thead>
          <tr>
            <th scope="col">Команда</th>
            <th scope="col">Новые взаимодействия</th>
            <th scope="col">Завершённые</th>
            <th scope="col">Перешли на другой этап</th>
            <th scope="col">Подписано договоров</th>
          </tr>
        </thead>
        <tbody>
          {changes.map((team) => (
            <tr key={team.teamId}>
              <th scope="row" data-label="Команда">{team.teamName}</th>
              <td data-label="Новые взаимодействия"><ChangeCell change={team.created} href={changeHref(team.teamId, data.periods.current, 'NEW')} /></td>
              <td data-label="Завершённые"><ChangeCell change={team.completed} href={changeHref(team.teamId, data.periods.current, 'COMPLETED')} /></td>
              <td data-label="Перешли на другой этап"><ChangeCell change={team.moved} href={changeHref(team.teamId, data.periods.current, 'MOVED')} /></td>
              <td data-label="Подписано договоров"><ChangeCell change={team.signed} /></td>
            </tr>
          ))}
        </tbody>
      </table>

      {data.portfolio !== null && (
        <>
          <h2 className="overview__title">Состав активных взаимодействий</h2>
          <p className="control-muted">
            {`Активные на начало ${formatCalendarDate(data.portfolio.from)} и на конец ${formatCalendarDate(data.portfolio.to)}; было = остались + завершены + приостановлены, стало = остались + новые + возобновлены. Команда — текущая команда вуза.`}
          </p>
          <table className="control-table overview__table">
            <thead>
              <tr>
                <th scope="col">Команда</th>
                <th scope="col">Было → стало</th>
                <th scope="col">Из чего сложилось</th>
              </tr>
            </thead>
            <tbody>
              {[...data.portfolio.teams, data.portfolio.total].map((row) => (
                <tr key={row.teamId ?? 'total'}>
                  <th scope="row" data-label="Команда">{row.teamId === null || row.teamId === undefined ? 'Все команды' : row.teamName}</th>
                  <td data-label="Было → стало">{`${row.atStart} → ${row.atEnd}`}</td>
                  <td data-label="Из чего сложилось">{portfolioParts(row).join(', ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}
