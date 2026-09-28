import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, type TeamSummary, type TeamsSummary as Summary } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessHandlers, formatDateTime, handledAccessError, requestIdOf } from './workShared'
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
  )
}
