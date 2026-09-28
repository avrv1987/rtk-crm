import { useCallback, useEffect, useRef, useState } from 'react'
import {
  apiClient,
  type LmsSignal,
  type ManagerIndicators,
  type Organization,
  type TeamIndicators as Indicators
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { LmsSignalList } from './LmsSignals'
import { type AccessHandlers, formatDate, formatDateTime, handledAccessError, requestIdOf } from './workShared'
import './workControl.css'

type IndicatorsState =
  | { kind: 'loading' }
  | { kind: 'ready'; indicators: Indicators; signals: LmsSignal[] | null }
  | { kind: 'failed'; requestId?: string }

type TeamIndicatorsProps = AccessHandlers & {
  organizations: Organization[] | null
  refreshKey: number
}

const stuckDayOptions = [7, 14, 30, 60, 90]
const asideLimit = 10

const workLink = (manager: ManagerIndicators, extra: Record<string, string>) => {
  const params = new URLSearchParams({ responsible: manager.managerId ?? 'UNASSIGNED', ...extra })
  return `#/work?${params.toString()}`
}

const CountLink = ({ value, href, label }: { value: number; href: string; label: string }) => (
  value === 0
    ? <span className="team-indicators__zero">0</span>
    : <a href={href} aria-label={`${label}: ${value}`}>{value}</a>
)

const heat = (value: number, tone: 'danger' | 'warning') => value > 0 ? `work-control__cell--${tone}` : undefined

const sum = (managers: ManagerIndicators[], pick: (manager: ManagerIndicators) => number) => (
  managers.reduce((total, manager) => total + pick(manager), 0)
)

const signalCount = (signals: LmsSignal[], managers: ManagerIndicators[], manager: ManagerIndicators) => {
  const listed = new Set(managers.map((item) => item.managerId).filter((id) => id !== null))
  return signals.filter((signal) => (
    manager.managerId === null
      ? signal.ownerManagerId === null || signal.ownerManagerId === undefined || !listed.has(signal.ownerManagerId)
      : signal.ownerManagerId === manager.managerId
  )).length
}

const deputyGroups = (organizations: Organization[]) => {
  const groups = new Map<string, { owner: string; deputy: string; endsOn: string | null; names: string[] }>()
  for (const organization of organizations) {
    if (organization.deputyManagerName === null) {
      continue
    }
    const owner = organization.ownerManagerName ?? 'Требует назначения'
    const key = `${owner}|${organization.deputyManagerName}|${organization.deputyEndsOn ?? ''}`
    const group = groups.get(key) ?? { owner, deputy: organization.deputyManagerName, endsOn: organization.deputyEndsOn, names: [] }
    group.names.push(organization.name)
    groups.set(key, group)
  }
  return [...groups.values()]
}

export const TeamIndicators = ({ organizations, refreshKey, onSessionExpired, onProfileUnavailable }: TeamIndicatorsProps) => {
  const [stuckDays, setStuckDays] = useState<number | undefined>(undefined)
  const [state, setState] = useState<IndicatorsState>({ kind: 'loading' })
  const requestVersion = useRef(0)

  const load = useCallback(async (days: number | undefined) => {
    const version = ++requestVersion.current
    setState({ kind: 'loading' })
    try {
      const [indicators, signals] = await Promise.all([
        apiClient.getTeamIndicators(days),
        apiClient.listLmsSignals().then((result) => result.items, () => null)
      ])
      if (version === requestVersion.current) {
        setState({ kind: 'ready', indicators, signals })
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
  }, [load, stuckDays, refreshKey])

  const days = state.kind === 'ready' ? state.indicators.stuckDays : stuckDays ?? 30
  const options = stuckDayOptions.includes(days) ? stuckDayOptions : [...stuckDayOptions, days].sort((a, b) => a - b)
  const unassigned = organizations?.filter((organization) => organization.requiresAssignment) ?? []
  const pending = organizations?.filter((organization) => organization.status === 'PENDING').length ?? null
  const deputies = organizations === null ? [] : deputyGroups(organizations)
  const signals = state.kind === 'ready' ? state.signals : null

  return (
    <>
      <section className="team-indicators" aria-labelledby="team-indicators-title">
        <div className="team-indicators__header">
          <h2 id="team-indicators-title">Пульт команды</h2>
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
            <ul className="desk-tiles" aria-label="Показатели команды">
              {[
                { label: 'Просрочено', value: sum(state.indicators.managers, (manager) => manager.overdue), href: '#/work?due=OVERDUE', tone: 'danger' },
                {
                  label: 'Без шага или срока',
                  value: sum(state.indicators.managers, (manager) => manager.withoutNextStep),
                  href: '#/work?due=NO_NEXT_STEP',
                  tone: 'warning'
                },
                {
                  label: `На этапе дольше ${state.indicators.stuckDays} дней`,
                  value: sum(state.indicators.managers, (manager) => manager.stuck),
                  href: `#/work?minDaysOnStage=${state.indicators.stuckDays}`,
                  tone: 'warning'
                },
                { label: 'Вузов без КАМ', value: state.indicators.unassignedOrganizations, href: '#/organizations?requiresAssignment=true', tone: 'danger' },
                { label: 'Вузов ждут подтверждения', value: pending, href: '#/organizations?status=PENDING', tone: 'warning' }
              ].map((tile) => (
                <li key={tile.label}>
                  <a className={`desk-tile${tile.value ? ` desk-tile--${tile.tone}` : ''}`} href={tile.href}>
                    <span className="desk-tile__value">{tile.value ?? '…'}</span>
                    <span className="desk-tile__label">{tile.label}</span>
                  </a>
                </li>
              ))}
            </ul>
            <h3 className="team-indicators__subtitle">Где команде нужна помощь</h3>
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
                    <th scope="col">Сигналы LMS</th>
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
                        <td className={heat(manager.overdue, 'danger')}>
                          <CountLink value={manager.overdue} href={workLink(manager, { due: 'OVERDUE' })} label={`${name}, просрочено`} />
                        </td>
                        <td className={heat(manager.withoutNextStep, 'warning')}>
                          <CountLink
                            value={manager.withoutNextStep}
                            href={workLink(manager, { due: 'NO_NEXT_STEP' })}
                            label={`${name}, без шага или срока`}
                          />
                        </td>
                        <td className={heat(manager.stuck, 'warning')}>
                          <CountLink
                            value={manager.stuck}
                            href={workLink(manager, { minDaysOnStage: state.indicators.stuckDays.toString() })}
                            label={`${name}, на этапе дольше ${state.indicators.stuckDays} дней`}
                          />
                        </td>
                        {signals === null ? (
                          <td>—</td>
                        ) : (
                          <td className={heat(signalCount(signals, state.indicators.managers, manager), 'warning')}>
                            {signalCount(signals, state.indicators.managers, manager)}
                          </td>
                        )}
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

      <div className="leader-aside">
        <section className="team-indicators" aria-labelledby="leader-unassigned-title">
          <h2 id="leader-unassigned-title">Вузы без ответственного</h2>
          {organizations === null && <p className="work-control__message">Список вузов загружается или недоступен.</p>}
          {organizations !== null && unassigned.length === 0 && <p className="work-control__message">Все вузы команды закреплены за КАМ.</p>}
          {unassigned.length > 0 && (
            <ul className="leader-aside__list">
              {unassigned.slice(0, asideLimit).map((organization) => (
                <li key={organization.id}>
                  <a href={`#/organizations/${organization.id}`}>{organization.name}</a>
                  <span>
                    {organization.ownerManagerName === null
                      ? 'Ответственный не назначен'
                      : `Прежний ответственный ${organization.ownerManagerName} не может вести вуз`}
                    {organization.status === 'PENDING' ? ', ожидает подтверждения' : ''}
                  </span>
                </li>
              ))}
            </ul>
          )}
          <a href="#/organizations?requiresAssignment=true">
            {unassigned.length > asideLimit ? `Все ${unassigned.length} и массовая передача` : 'Назначить и передать на странице «Вузы»'}
          </a>
        </section>
        <section className="team-indicators" aria-labelledby="leader-deputies-title">
          <h2 id="leader-deputies-title">Заместители</h2>
          {organizations !== null && deputies.length === 0 && <p className="work-control__message">Действующих замещений нет.</p>}
          {deputies.length > 0 && (
            <ul className="leader-aside__list">
              {deputies.map((group) => (
                <li key={`${group.owner}|${group.deputy}|${group.endsOn ?? ''}`}>
                  <strong>
                    {group.owner} → {group.deputy}{group.endsOn === null ? '' : `, до ${formatDate(group.endsOn)}`}
                  </strong>
                  <span>{group.names.join(', ')}</span>
                </li>
              ))}
            </ul>
          )}
          <p className="work-control__caption">Замещение назначают и снимают в карточке вуза.</p>
        </section>
      </div>

      <section className="team-indicators lms-signals-panel" aria-labelledby="leader-lms-signals-title">
        <h2 id="leader-lms-signals-title" className="lms-signals__heading">Сигналы LMS{signals !== null && signals.length > 0 && <span className="desk-feed__count">{signals.length}</span>}</h2>
        {state.kind === 'ready' && signals === null && <p className="work-control__message">Сигналы LMS сейчас недоступны.</p>}
        {signals !== null && signals.length === 0 && <p className="work-control__message">Сигналов по данным LMS нет.</p>}
        {signals !== null && signals.length > 0 && <LmsSignalList signals={signals} limit={asideLimit} showOwner />}
        <p className="work-control__caption">Скрыть сигнал и перейти к этапу можно в карточке работы.</p>
      </section>
    </>
  )
}
