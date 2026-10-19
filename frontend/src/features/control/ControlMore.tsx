import { useEffect, useState } from 'react'
import { apiClient, type LearningCoverage, type LearningTrend, type LmsSignal, type Organization, type PortfolioChange, type SigningPlan } from '../../shared/api/client'
import { currentPeriod, periodTitle, stateCounts } from '../reports/signingPlan'
import { LearningTrendPanel } from '../work/LearningTrendPanel'
import { LmsSignalList } from '../work/LmsSignals'
import type { FilterOption } from '../reports/ReportControls'
import { ManagerDeputyForm } from './ManagerDeputyForm'
import { controlMoreScope, trendDays, trendLine } from './controlModel'
import { comparisonPeriods, coverageText, portfolioText } from './overviewModel'
import { type AccessHandlers, formatDate, handledAccessError, todayIso } from '../work/workShared'

type Panel = 'signals' | 'deputies' | 'trend' | null

type Loaded<T> = { kind: 'loading' } | { kind: 'ready'; value: T } | { kind: 'failed' }


export type DeputyGroup = { owner: string; deputy: string; endsOn: string | null; names: string[] }

export const deputyGroups = (organizations: Organization[]): DeputyGroup[] => {
  const groups = new Map<string, DeputyGroup>()
  for (const organization of organizations) {
    if (organization.deputyManagerName === null || organization.status === 'ARCHIVED') {
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

const useLoaded = <T,>(load: () => Promise<T>, handlers: AccessHandlers, key: unknown): Loaded<T> => {
  const [state, setState] = useState<Loaded<T>>({ kind: 'loading' })
  useEffect(() => {
    let active = true
    setState({ kind: 'loading' })
    load().then(
      (value) => active && setState({ kind: 'ready', value }),
      (error: unknown) => {
        if (active && !handledAccessError(error, handlers)) {
          setState({ kind: 'failed' })
        }
      }
    )
    return () => {
      active = false
    }
  }, [key])
  return state
}

type ControlMoreProps = AccessHandlers & {
  management: boolean
  organizations: Organization[] | null
  refreshKey: unknown
  managers?: FilterOption[] | null
  leaderId?: string
  onDeputiesChanged?: () => void
}

export const ControlMore = ({
  management,
  organizations,
  refreshKey,
  managers,
  leaderId,
  onDeputiesChanged,
  onSessionExpired,
  onProfileUnavailable
}: ControlMoreProps) => {
  const handlers = { onSessionExpired, onProfileUnavailable }
  const [panel, setPanel] = useState<Panel>(null)
  const signals = useLoaded<LmsSignal[]>(() => apiClient.listLmsSignals().then((result) => result.items), handlers, refreshKey)
  const trend = useLoaded<LearningTrend>(() => apiClient.getLearningTrend(trendDays), handlers, refreshKey)
  const portfolio = useLoaded<PortfolioChange>(() => {
    const window = comparisonPeriods(todayIso(), trendDays).current
    return apiClient.getPortfolioChange(window.from, window.to)
  }, handlers, refreshKey)
  const coverage = useLoaded<LearningCoverage>(() => apiClient.getLearningCoverage(), handlers, refreshKey)
  const plan = useLoaded<SigningPlan | null>(
    () => (management ? apiClient.getSigningPlan(currentPeriod(todayIso())) : Promise.resolve(null)),
    handlers,
    refreshKey
  )
  const deputies = organizations === null ? null : deputyGroups(organizations)
  const pending = organizations?.filter((organization) => organization.status === 'PENDING').length ?? null
  const toggle = (next: Panel) => setPanel((current) => (current === next ? null : next))
  const planCounts = plan.kind === 'ready' && plan.value !== null ? stateCounts(plan.value.rows) : null
  const signing = plan.kind === 'ready' && plan.value !== null ? plan.value.rows.filter((row) => row.kind === 'SIGNING').length : 0

  return (
    <section className="control-more" aria-label={management ? 'Ещё по всем командам' : 'Ещё по команде'}>
      <p className="control-more__scope">{controlMoreScope(management)}</p>
      <ul className="control-more__line">
        <li>
          {signals.kind === 'ready' ? (
            <button type="button" className="control-more__toggle" aria-expanded={panel === 'signals'} onClick={() => toggle('signals')}>
              Все сигналы LMS: {signals.value.length}
            </button>
          ) : <span>{signals.kind === 'loading' ? 'Все сигналы LMS: …' : 'Сигналы LMS недоступны'}</span>}
        </li>
        <li>
          {deputies === null ? <span>Заместители: …</span> : (
            <button type="button" className="control-more__toggle" aria-expanded={panel === 'deputies'} onClick={() => toggle('deputies')}>
              Заместители: {deputies.length === 0 ? 'нет' : deputies.length}
            </button>
          )}
        </li>
        <li>
          {pending === null || pending === 0
            ? <span>Вузы ждут подтверждения: {pending ?? '…'}</span>
            : <a href="#/organizations?status=PENDING">Вузы ждут подтверждения: {pending}</a>}
        </li>
        <li>
          {trend.kind === 'ready' ? (
            <button type="button" className="control-more__toggle" aria-expanded={panel === 'trend'} onClick={() => toggle('trend')}>
              {trendLine(trend.value, trendDays)}
            </button>
          ) : <span>{trend.kind === 'loading' ? 'Тренд обучения: …' : 'Тренд обучения недоступен'}</span>}
          {' '}
          <a href="#/reports/learning-dynamics">Динамика обучения</a>
        </li>
        <li>
          {portfolio.kind === 'ready'
            ? <span>{`Активные взаимодействия за ${trendDays} дней: ${portfolioText(portfolio.value.total)}`}</span>
            : <span>{portfolio.kind === 'loading' ? 'Состав активных: …' : 'Состав активных недоступен'}</span>}
        </li>
        <li>
          {coverage.kind === 'ready'
            ? <span>{coverageText(coverage.value)}</span>
            : <span>{coverage.kind === 'loading' ? 'Данные обучения: …' : 'Покрытие данных обучения недоступно'}</span>}
        </li>
        {management && (
          <li>
            {planCounts !== null && plan.kind === 'ready' && plan.value !== null ? (
              <a href="#/reports/signing-plan">
                {`Подписания, ${periodTitle(plan.value.year, plan.value.quarter ?? undefined)}: подписаний ${signing}, продлений ${plan.value.rows.length - signing}, просрочено ${planCounts.overdue}, впереди ${planCounts.upcoming}, выполнено ${planCounts.done}`}
              </a>
            ) : <a href="#/reports/signing-plan">{plan.kind === 'failed' ? 'План подписаний' : 'Подписания: …'}</a>}
          </li>
        )}
      </ul>

      {panel === 'signals' && signals.kind === 'ready' && (
        <div className="control-more__panel">
          {signals.value.length === 0
            ? <p className="control-muted">Сигналов по данным LMS нет.</p>
            : <LmsSignalList signals={signals.value} limit={10} showOwner />}
          <p className="control-muted">Скрыть сигнал, записать его как риск или перейти к этапу можно в карточке взаимодействия.</p>
        </div>
      )}
      {panel === 'deputies' && deputies !== null && (
        <div className="control-more__panel">
          {deputies.length === 0 ? <p className="control-muted">Действующих замещений нет.</p> : (
            <ul className="leader-aside__list">
              {deputies.map((group) => (
                <li key={`${group.owner}|${group.deputy}|${group.endsOn ?? ''}`}>
                  <strong>{group.owner} → {group.deputy}{group.endsOn === null ? '' : `, до ${formatDate(group.endsOn)}`}</strong>
                  <span>{group.names.join(', ')}</span>
                </li>
              ))}
            </ul>
          )}
          <p className="control-muted">По одному вузу замещение назначают и снимают в карточке вуза.</p>
          {managers && managers.length > 0 && (
            <>
              <h3 className="control-more__subtitle">Заместитель на все вузы КАМ</h3>
              <p className="control-muted">Отпуск или больничный: заместитель получает все действующие вузы КАМ на период, ответственный не меняется.</p>
              <ManagerDeputyForm
                managers={managers}
                leaderId={leaderId}
                onAssigned={() => onDeputiesChanged?.()}
                onSessionExpired={onSessionExpired}
                onProfileUnavailable={onProfileUnavailable}
              />
            </>
          )}
        </div>
      )}
      {panel === 'trend' && (
        <div className="control-more__panel">
          <LearningTrendPanel ownTeam={!management} onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} />
        </div>
      )}
    </section>
  )
}
