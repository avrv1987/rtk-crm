import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, type LearningTrend, type LearningTrendItem, type TeamSummary, type TeamsSummary as Summary } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessHandlers, formatDate, formatDateTime, handledAccessError, requestIdOf } from './workShared'
import './workControl.css'

type SummaryState =
  | { kind: 'loading' }
  | { kind: 'ready'; summary: Summary }
  | { kind: 'failed'; requestId?: string }

const SummaryRow = ({ row, name }: { row: TeamSummary; name: string }) => (
  <tr className={row.teamId === null ? 'work-control__row--total' : undefined}>
    <th scope="row">{name}</th>
    <td>{row.organizations}</td>
    <td>{row.unassignedOrganizations}</td>
    <td>{row.interactions}</td>
    <td>{row.overdue}</td>
    <td>{row.withoutNextStep}</td>
    <td>{row.stuck}</td>
    <td>{row.organizationsWithLearning}</td>
    <td>{row.participants}</td>
    <td>{row.teachers}</td>
  </tr>
)

type TeamsSummaryProps = AccessHandlers & {
  refreshKey: number
}

type TrendState =
  | { kind: 'loading' }
  | { kind: 'ready'; trend: LearningTrend }
  | { kind: 'failed'; requestId?: string }

const trendPeriods = [30, 90, 180, 365]

const signed = (value: number) => (value > 0 ? `+${value.toLocaleString('ru-RU')}` : value < 0 ? `−${Math.abs(value).toLocaleString('ru-RU')}` : '0')

const TrendTable = ({ title, rows, total }: { title: string; rows: LearningTrendItem[]; total?: LearningTrendItem }) => (
  <div className="work-control__scroll">
    <table className="work-control__table">
      <thead>
        <tr>
          <th scope="col">{title}</th>
          <th scope="col">На начало</th>
          <th scope="col">На конец</th>
          <th scope="col">Изменение</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.id ?? row.name ?? 'row'}>
            <th scope="row">{row.name ?? 'Без названия'}</th>
            <td>{row.start}</td>
            <td>{row.end}</td>
            <td className={row.change < 0 ? 'work-control__cell--danger' : undefined}>{signed(row.change)}</td>
          </tr>
        ))}
        {total && (
          <tr className="work-control__row--total">
            <th scope="row">Все команды</th>
            <td>{total.start}</td>
            <td>{total.end}</td>
            <td>{signed(total.change)}</td>
          </tr>
        )}
      </tbody>
    </table>
  </div>
)

const TrendList = ({ title, items, empty }: { title: string; items: LearningTrendItem[]; empty: string }) => (
  <div>
    <h3 className="team-indicators__subtitle">{title}</h3>
    {items.length === 0 ? <p className="team-indicators__zero">{empty}</p> : (
      <ol className="leader-aside__list">
        {items.map((item) => (
          <li key={item.id ?? item.name}>
            <strong>{item.name}</strong>
            <span>{`${signed(item.change)}: было ${item.start}, стало ${item.end}`}</span>
          </li>
        ))}
      </ol>
    )}
  </div>
)

const LearningTrendPanel = ({ onSessionExpired, onProfileUnavailable }: AccessHandlers) => {
  const [days, setDays] = useState(90)
  const [state, setState] = useState<TrendState>({ kind: 'loading' })
  const requestVersion = useRef(0)

  const load = useCallback(async (period: number) => {
    const version = ++requestVersion.current
    setState({ kind: 'loading' })
    try {
      const trend = await apiClient.getLearningTrend(period)
      if (version === requestVersion.current) {
        setState({ kind: 'ready', trend })
      }
    } catch (error) {
      if (version !== requestVersion.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load(days)
  }, [days, load])

  return (
    <section className="team-indicators" aria-labelledby="teams-trend-title" aria-busy={state.kind === 'loading'}>
      <div className="team-indicators__header">
        <h2 id="teams-trend-title">Тренд обучения</h2>
        <label className="team-indicators__threshold">
          Период
          <select value={days} onChange={(event) => setDays(Number(event.target.value))}>
            {trendPeriods.map((period) => <option key={period} value={period}>{`последние ${period} дней`}</option>)}
          </select>
        </label>
      </div>
      {state.kind === 'loading' && <p className="work-control__message" role="status">Считаем тренд обучения…</p>}
      {state.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось посчитать тренд обучения.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" onClick={() => void load(days)}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && (
        <>
          <ul className="desk-tiles" aria-label="Обучающиеся на начало и конец периода">
            {[
              { label: `Обучающихся на ${formatDate(state.trend.from)}`, value: state.trend.total.start.toLocaleString('ru-RU'), tone: '' },
              { label: `Обучающихся на ${formatDate(state.trend.to)}`, value: state.trend.total.end.toLocaleString('ru-RU'), tone: '' },
              { label: 'Изменение за период', value: signed(state.trend.total.change), tone: state.trend.total.change < 0 ? ' desk-tile--danger' : ' desk-tile--plain' }
            ].map((tile) => (
              <li key={tile.label}>
                <span className={`desk-tile${tile.tone}`}>
                  <span className="desk-tile__value">{tile.value}</span>
                  <span className="desk-tile__label">{tile.label}</span>
                </span>
              </li>
            ))}
          </ul>
          <p className="work-control__caption">
            {`Обучающиеся в потоках занятий студентов Moodle на конец дня ${formatDate(state.trend.from)} и ${formatDate(state.trend.to)} по истории наблюдений; закончившийся к концу периода поток даёт ноль.`}
            {state.trend.runsWithoutData > 0 && ` Потоков без наблюдения на одну из дат: ${state.trend.runsWithoutData}, в сравнение они не входят.`}
          </p>
          <TrendTable title="Команда" rows={state.trend.teams} total={state.trend.total} />
          <TrendTable title="ИТ-программа" rows={state.trend.programs} />
          <div className="leader-aside">
            <TrendList title="Растёт число обучающихся" items={state.trend.growing} empty="Роста за период нет." />
            <TrendList title="Падает число обучающихся" items={state.trend.falling} empty="Падения за период нет." />
          </div>
          <p className="team-indicators__footer">
            <span>Рассчитано: {formatDateTime(state.trend.calculatedAt)}</span>
            <a href="#/reports">Динамика по месяцам — в отчётах</a>
          </p>
        </>
      )}
    </section>
  )
}

export const TeamsSummary = ({ refreshKey, onSessionExpired, onProfileUnavailable }: TeamsSummaryProps) => {
  const [state, setState] = useState<SummaryState>({ kind: 'loading' })
  const requestVersion = useRef(0)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    setState({ kind: 'loading' })
    try {
      const summary = await apiClient.getTeamsSummary()
      if (version === requestVersion.current) {
        setState({ kind: 'ready', summary })
      }
    } catch (error) {
      if (version !== requestVersion.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load()
  }, [load, refreshKey])

  return (
    <>
      <section className="team-indicators" aria-labelledby="teams-summary-title">
        <div className="team-indicators__header">
          <h2 id="teams-summary-title">Сводка по всем командам</h2>
          <span className="desk-badge">Только просмотр</span>
        </div>
        {state.kind === 'loading' && <p className="work-control__message" role="status">Собираем сводку по командам…</p>}
        {state.kind === 'failed' && (
          <div className="notice notice--error" role="alert">
            <p>Не удалось собрать сводку по командам.</p>
            <SupportDetails requestId={state.requestId} />
            <button type="button" onClick={() => void load()}>Повторить</button>
          </div>
        )}
        {state.kind === 'ready' && (
          <>
            <ul className="desk-tiles" aria-label="Итоги по всем командам">
              {[
                { label: 'Вузов и школ', value: state.summary.total.organizations },
                { label: 'Вузов без КАМ', value: state.summary.total.unassignedOrganizations, tone: 'danger' },
                { label: 'Незавершённых работ', value: state.summary.total.interactions },
                { label: 'Просрочено', value: state.summary.total.overdue, tone: 'danger' },
                { label: 'Без шага или срока', value: state.summary.total.withoutNextStep, tone: 'warning' },
                { label: 'Вузов, где идёт обучение', value: state.summary.total.organizationsWithLearning },
                { label: 'Обучающихся', value: state.summary.total.participants },
                { label: 'Преподавателей', value: state.summary.total.teachers }
              ].map((tile) => (
                <li key={tile.label}>
                  <span className={`desk-tile${tile.tone !== undefined && tile.value > 0 ? ` desk-tile--${tile.tone}` : ''}`}>
                    <span className="desk-tile__value">{tile.value.toLocaleString('ru-RU')}</span>
                    <span className="desk-tile__label">{tile.label}</span>
                  </span>
                </li>
              ))}
            </ul>
            <p className="work-control__caption" id="teams-summary-caption">Вузы и школы всех команд, незавершённые работы и обучение по последним данным Moodle. Режим только для чтения.</p>
            <div className="work-control__scroll">
              <table className="work-control__table" aria-describedby="teams-summary-caption">
                <thead>
                  <tr>
                    <th scope="col">Команда</th>
                    <th scope="col">Вузов</th>
                    <th scope="col">Без КАМ</th>
                    <th scope="col">Работ</th>
                    <th scope="col">Просрочено</th>
                    <th scope="col">Без шага или срока</th>
                    <th scope="col">На этапе дольше {state.summary.stuckDays} дней</th>
                    <th scope="col">Вузов, где идёт обучение</th>
                    <th scope="col">Обучающихся</th>
                    <th scope="col">Преподавателей</th>
                  </tr>
                </thead>
                <tbody>
                  {state.summary.teams.map((team) => (
                    <SummaryRow key={team.teamId ?? 'total'} row={team} name={team.teamName ?? 'Команда без названия'} />
                  ))}
                  <SummaryRow row={state.summary.total} name="Все команды" />
                </tbody>
              </table>
            </div>
            <p className="team-indicators__footer">
              <span>Рассчитано: {formatDateTime(state.summary.calculatedAt)}</span>
              <a href="#/reports">Отчёты и выгрузки по всем командам</a>
              <button type="button" className="button--secondary" onClick={() => void load()}>Обновить</button>
            </p>
          </>
        )}
      </section>
      <LearningTrendPanel onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} />
    </>
  )
}
