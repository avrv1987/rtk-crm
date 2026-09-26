import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, type ManagerIndicators, type TeamIndicators as Indicators } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessHandlers, formatDateTime, handledAccessError, requestIdOf } from './workShared'
import './workControl.css'

type IndicatorsState =
  | { kind: 'loading' }
  | { kind: 'ready'; indicators: Indicators }
  | { kind: 'failed'; requestId?: string }

const stuckDayOptions = [7, 14, 30, 60, 90]

const workLink = (manager: ManagerIndicators, extra: Record<string, string>) => {
  const params = new URLSearchParams({ responsible: manager.managerId ?? 'UNASSIGNED', ...extra })
  return `#/work?${params.toString()}`
}

const CountLink = ({ value, href, label }: { value: number; href: string; label: string }) => (
  value === 0
    ? <span className="team-indicators__zero">0</span>
    : <a href={href} aria-label={`${label}: ${value}`}>{value}</a>
)

export const TeamIndicators = ({ onSessionExpired, onProfileUnavailable }: AccessHandlers) => {
  const [stuckDays, setStuckDays] = useState<number | undefined>(undefined)
  const [state, setState] = useState<IndicatorsState>({ kind: 'loading' })
  const requestVersion = useRef(0)

  const load = useCallback(async (days: number | undefined) => {
    const version = ++requestVersion.current
    setState({ kind: 'loading' })
    try {
      const indicators = await apiClient.getTeamIndicators(days)
      if (version === requestVersion.current) {
        setState({ kind: 'ready', indicators })
      }
    } catch (error) {
      if (version !== requestVersion.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load(stuckDays)
  }, [load, stuckDays])

  const days = state.kind === 'ready' ? state.indicators.stuckDays : stuckDays ?? 30
  const options = stuckDayOptions.includes(days) ? stuckDayOptions : [...stuckDayOptions, days].sort((a, b) => a - b)

  return (
    <section className="team-indicators" aria-labelledby="team-indicators-title">
      <div className="team-indicators__header">
        <h2 id="team-indicators-title">Где команде нужна помощь</h2>
        <label className="team-indicators__threshold">
          Порог «долго на этапе»
          <select value={days} onChange={(event) => setStuckDays(Number(event.target.value))}>
            {options.map((option) => <option key={option} value={option}>{option} дней</option>)}
          </select>
        </label>
      </div>
      {state.kind === 'loading' && <p className="work-control__message" role="status">Считаем показатели команды…</p>}
      {state.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось посчитать показатели команды.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" onClick={() => void load(stuckDays)}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && (
        <>
          <p className="work-control__caption" id="team-indicators-caption">Незавершённые работы по ответственным КАМ. Число открывает список «Моей работы» с тем же отбором.</p>
          <div className="work-control__scroll">
            <table className="work-control__table" aria-describedby="team-indicators-caption">
              <thead>
                <tr>
                  <th scope="col">Ответственный</th>
                  <th scope="col">Вузов</th>
                  <th scope="col">Работ</th>
                  <th scope="col">Просрочено</th>
                  <th scope="col">Без шага или срока</th>
                  <th scope="col">На этапе дольше {state.indicators.stuckDays} дней</th>
                </tr>
              </thead>
              <tbody>
                {state.indicators.managers.map((manager) => {
                  const name = manager.managerName ?? 'Требует назначения'
                  return (
                    <tr key={manager.managerId ?? 'unassigned'} className={manager.managerId === null ? 'work-control__row--attention' : undefined}>
                      <th scope="row">{name}</th>
                      <td>{manager.organizations}</td>
                      <td><CountLink value={manager.interactions} href={workLink(manager, {})} label={`${name}, работ`} /></td>
                      <td>
                        <CountLink value={manager.overdue} href={workLink(manager, { due: 'OVERDUE' })} label={`${name}, просрочено`} />
                      </td>
                      <td>
                        <CountLink
                          value={manager.withoutNextStep}
                          href={workLink(manager, { due: 'NO_NEXT_STEP' })}
                          label={`${name}, без шага или срока`}
                        />
                      </td>
                      <td>
                        <CountLink
                          value={manager.stuck}
                          href={workLink(manager, { minDaysOnStage: state.indicators.stuckDays.toString() })}
                          label={`${name}, на этапе дольше ${state.indicators.stuckDays} дней`}
                        />
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <p className="team-indicators__footer">
            <span>
              Вузов без КАМ:{' '}
              {state.indicators.unassignedOrganizations === 0
                ? '0'
                : <a href="#/organizations?requiresAssignment=true">{state.indicators.unassignedOrganizations}</a>}
            </span>
            <span>Рассчитано: {formatDateTime(state.indicators.calculatedAt)}</span>
            <button type="button" className="button--secondary" onClick={() => void load(stuckDays)}>Обновить</button>
          </p>
        </>
      )}
    </section>
  )
}
