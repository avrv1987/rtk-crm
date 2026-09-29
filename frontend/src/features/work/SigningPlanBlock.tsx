import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, type SigningPlan } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { currentPeriod, periodTitle, stateCounts } from '../reports/signingPlan'
import { type AccessHandlers, handledAccessError, requestIdOf, todayIso } from './workShared'
import './workControl.css'

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; plan: SigningPlan }
  | { kind: 'failed'; requestId?: string }

export const SigningPlanBlock = ({ refreshKey, onSessionExpired, onProfileUnavailable }: AccessHandlers & { refreshKey: number }) => {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const requestVersion = useRef(0)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    setState({ kind: 'loading' })
    try {
      const plan = await apiClient.getSigningPlan(currentPeriod(todayIso()))
      if (version === requestVersion.current) {
        setState({ kind: 'ready', plan })
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

  const counts = state.kind === 'ready' ? stateCounts(state.plan.rows) : null
  const signing = state.kind === 'ready' ? state.plan.rows.filter((row) => row.kind === 'SIGNING').length : 0

  return (
    <section className="team-indicators" aria-labelledby="signing-plan-block-title" aria-busy={state.kind === 'loading'}>
      <div className="team-indicators__header">
        <h2 id="signing-plan-block-title">Подписания в этом квартале</h2>
        <span className="desk-badge">Только просмотр</span>
      </div>
      {state.kind === 'loading' && <p className="work-control__message" role="status">Собираем план подписаний…</p>}
      {state.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось собрать план подписаний.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && counts !== null && (
        <>
          <ul className="desk-tiles" aria-label={`План подписаний и продлений, ${periodTitle(state.plan.year, state.plan.quarter ?? undefined)}`}>
            {[
              { label: 'Подписаний', value: signing, tone: '' },
              { label: 'Продлений', value: state.plan.rows.length - signing, tone: '' },
              { label: 'Просрочено', value: counts.overdue, tone: 'danger' },
              { label: 'Впереди', value: counts.upcoming, tone: '' }
            ].map((tile) => (
              <li key={tile.label}>
                <span className={`desk-tile${tile.tone !== '' && tile.value > 0 ? ` desk-tile--${tile.tone}` : ''}`}>
                  <span className="desk-tile__value">{tile.value.toLocaleString('ru-RU')}</span>
                  <span className="desk-tile__label">{tile.label}</span>
                </span>
              </li>
            ))}
          </ul>
          <p className="team-indicators__footer">
            <span>{`${periodTitle(state.plan.year, state.plan.quarter ?? undefined)}, выполнено: ${counts.done}`}</span>
            <a href="#/reports/signing-plan">План подписаний и продлений по вузам и КАМ</a>
          </p>
        </>
      )}
    </section>
  )
}
