import { useEffect, useState } from 'react'
import { ApiError, apiClient, type AgentPlan, type AgentPlanKind, type AgentPlanListItem } from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { handledAccessError } from '../documents/documentsApi'
import { canDecide, kindLabels, planStatusLabels, statusText, warningTexts } from './agentPlan'
import './agentPlans.css'

type State =
  | { kind: 'loading' }
  | { kind: 'missing' }
  | { kind: 'failed'; requestId?: string }
  | { kind: 'ready'; plan: AgentPlan; saving: boolean; requestId?: string }

type Props = {
  planId: string | undefined
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

const dateTime = (value: string | null | undefined) => (value ? `${formatMoscowDateTime(value)} (Москва)` : 'не задан')
const date = (value: string | null | undefined) => (value ? formatCalendarDate(value) : 'не задан')
const workStatusLabels: Record<string, string> = { ACTIVE: 'Активно', PAUSED: 'Приостановлено', COMPLETED: 'Завершено' }
const stageText = (name: string | null | undefined, position: number | null | undefined, total: number) => (
  name ? `${name}${position ? ` (этап ${position} из ${total})` : ''}` : 'не найден'
)

const noteTitles: Record<AgentPlanKind, string> = {
  NOTE_UPDATE: 'Комментарий (итог встречи)',
  STAGE_TRANSITION: 'Обоснование перехода',
  ISSUE_CREATE: 'Описание',
  ISSUE_RESOLVE: 'Решение',
  STATUS_CHANGE: 'Причина и комментарий'
}

// «Было / станет» по виду плана. Текущие значения карточки показываются только у ожидающего плана: после решения
// карточка уже другая, и сравнение потеряло бы смысл.
const PlanDiff = ({ plan, pending }: { plan: AgentPlan; pending: boolean }) => {
  const rows: [string, string, string][] = []
  const was = (value: string) => (pending ? value : '—')
  if (plan.kind === 'STAGE_TRANSITION') {
    rows.push(['Этап', was(stageText(plan.stageName, plan.stagePosition, plan.stageTotal)),
      stageText(plan.toStageName, plan.toStagePosition, plan.stageTotal)])
  }
  if (plan.kind === 'STATUS_CHANGE') {
    rows.push(['Статус', was(workStatusLabels[plan.currentStatus ?? 'ACTIVE']),
      plan.workStatus === 'PAUSED' ? `Приостановлено до ${date(plan.resumeOn)}` : workStatusLabels[plan.workStatus ?? 'ACTIVE']])
    if (plan.resumeAction) {
      rows.push(['Шаг', was(plan.currentNextAction ?? 'не задан'), plan.resumeAction])
      rows.push(['Срок', was(dateTime(plan.currentNextActionAt)), dateTime(plan.resumeActionAt)])
    }
  }
  if (plan.kind === 'ISSUE_CREATE') {
    rows.push(['Вид', '—', plan.issueKind === 'RISK' ? `Риск (${plan.riskLevel === 'HIGH' ? 'высокий' : 'средний'})` : 'Проблема'])
    rows.push(['Ответственный', '—', plan.responsibleName ?? 'недоступен: план не запишется'])
    rows.push(['Срок', '—', date(plan.dueOn)])
  }
  if (plan.kind === 'ISSUE_RESOLVE') {
    rows.push(['Запись', plan.issueDescription ?? 'не найдена', plan.issueDescription ?? 'не найдена'])
    rows.push(['Состояние', was(plan.issueStatus === 'RESOLVED' ? 'решена' : 'открыта'), 'решена'])
  }
  if (plan.nextAction) {
    rows.push(['Шаг', was(plan.currentNextAction ?? 'не задан'), plan.nextAction])
    rows.push(['Срок', was(dateTime(plan.currentNextActionAt)), dateTime(plan.nextActionAt)])
  }
  return (
    <table className="agent-plan__diff">
      <caption>{kindLabels[plan.kind]}: было и станет</caption>
      <thead>
        <tr><th scope="col"></th><th scope="col">Было</th><th scope="col">Станет</th></tr>
      </thead>
      <tbody>
        {rows.map(([label, before, after], index) => (
          <tr key={`${label}-${index}`}><th scope="row">{label}</th><td>{before}</td><td>{after}</td></tr>
        ))}
      </tbody>
    </table>
  )
}

type ListState = { kind: 'loading' } | { kind: 'ready'; items: AgentPlanListItem[] } | { kind: 'failed'; requestId?: string }

// Список своих планов: открывается из колокольчика и по адресу #/agent-plans без идентификатора.
const AgentPlanList = ({ onSessionExpired, onProfileUnavailable }: Omit<Props, 'planId'>) => {
  const [all, setAll] = useState(false)
  const [kind, setKind] = useState<AgentPlanKind | ''>('')
  const [state, setState] = useState<ListState>({ kind: 'loading' })

  useEffect(() => {
    setState({ kind: 'loading' })
    apiClient.listAgentPlans(all ? 'ALL' : 'PENDING', kind === '' ? undefined : kind).then(
      (items) => setState({ kind: 'ready', items }),
      (error: unknown) => {
        if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
          setState({ kind: 'failed', requestId: error instanceof ApiError ? error.requestId : undefined })
        }
      })
  }, [all, kind])

  return (
    <section className="agent-plan" aria-labelledby="agent-plans-title">
      <h2 id="agent-plans-title">Планы ИИ-агента</h2>
      <p>Планы готовит ИИ-агент по вашей просьбе. Данные CRM меняются, только когда вы откроете план и нажмёте «Записать».</p>
      <div className="agent-plan__filters">
        <label>
          Вид{' '}
          <select value={kind} onChange={(event) => setKind(event.target.value as AgentPlanKind | '')}>
            <option value="">Все</option>
            {(Object.keys(kindLabels) as AgentPlanKind[]).map((key) => <option key={key} value={key}>{kindLabels[key]}</option>)}
          </select>
        </label>
        <label className="checkbox-field">
          <input type="checkbox" checked={all} onChange={(event) => setAll(event.target.checked)} />
          Показать и решённые
        </label>
      </div>
      {state.kind === 'loading' && <p role="status">Загружаем планы…</p>}
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>Не удалось загрузить планы.</p>
          <SupportDetails requestId={state.requestId ?? ''} />
        </div>
      )}
      {state.kind === 'ready' && (state.items.length === 0 ? <p>Планов нет.</p> : (
        <ul className="agent-plan__list">
          {state.items.map((item) => (
            <li key={item.id}>
              <a href={`#/agent-plans/${item.id}`}>{kindLabels[item.kind]}</a>
              <span>{item.organizationName} · {item.interactionTitle}</span>
              <span>{planStatusLabels[item.status]}; подготовлен {formatMoscowDateTime(item.createdAt)}
                {item.status === 'PENDING' ? `, действует до ${formatMoscowDateTime(item.expiresAt)}` : ''}</span>
            </li>
          ))}
        </ul>
      ))}
    </section>
  )
}

const PlanView = ({ planId, onSessionExpired, onProfileUnavailable }: Props & { planId: string }) => {
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    apiClient.getAgentPlan(planId).then((plan) => setState({ kind: 'ready', plan, saving: false }), (error: unknown) => {
      if (handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      if (error instanceof ApiError && error.status === 404) {
        setState({ kind: 'missing' })
        return
      }
      setState({ kind: 'failed', requestId: error instanceof ApiError ? error.requestId : undefined })
    })
  }, [planId])

  const decide = async (plan: AgentPlan, action: 'apply' | 'reject') => {
    setState({ kind: 'ready', plan, saving: true })
    try {
      const updated = action === 'apply' ? await apiClient.applyAgentPlan(plan.id) : await apiClient.rejectAgentPlan(plan.id)
      setState({ kind: 'ready', plan: updated, saving: false })
    } catch (error) {
      if (handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'ready', plan, saving: false, requestId: error instanceof ApiError ? error.requestId : '' })
    }
  }

  if (state.kind === 'loading') {
    return <p role="status">Загружаем план…</p>
  }
  if (state.kind === 'missing') {
    return <p>План не найден. Его мог подготовить агент другого сотрудника, или ссылка неполная. <a href="#/agent-plans">Мои планы</a></p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="interaction-command-error" role="alert">
        <p>Не удалось открыть план. Повторите попытку позже.</p>
        <SupportDetails requestId={state.requestId ?? ''} />
      </div>
    )
  }
  const { plan, saving } = state
  const pending = canDecide(plan)
  return (
    <section className="agent-plan" aria-labelledby="agent-plan-title">
      <h2 id="agent-plan-title">План ИИ-агента: {kindLabels[plan.kind].toLowerCase()}</h2>
      <p className={plan.status === 'APPLIED' ? 'security-success' : 'agent-plan__status'} role="status">{statusText(plan)}</p>
      <dl className="agent-plan__facts">
        <dt>Вуз</dt>
        <dd>{plan.organizationName}</dd>
        <dt>Взаимодействие</dt>
        <dd><a href={`#/organizations/${plan.organizationId}/${plan.interactionId}`}>{plan.interactionTitle}</a></dd>
        {plan.stageName && (<><dt>Этап</dt><dd>{stageText(plan.stageName, plan.stagePosition, plan.stageTotal)}</dd></>)}
        <dt>Подготовил</dt>
        <dd>ИИ-агент, клиент {plan.clientId ?? 'не указан'}, {formatMoscowDateTime(plan.createdAt)}</dd>
      </dl>
      {pending && plan.warnings.length > 0 && (
        <ul className="agent-plan__warnings">
          {plan.warnings.map((warning) => <li key={warning}>{warningTexts[warning] ?? warning}</li>)}
        </ul>
      )}
      {plan.note && (
        <>
          <h3>{noteTitles[plan.kind]} — текст агента</h3>
          <p className="agent-plan__note">{plan.note}</p>
        </>
      )}
      <PlanDiff plan={plan} pending={pending} />
      {plan.evidence.length > 0 && (
        <section aria-labelledby="agent-plan-evidence">
          <h3 id="agent-plan-evidence">Факты из истории карточки</h3>
          <p>Даты, авторы и тексты — из CRM, а не со слов агента.</p>
          <ul className="agent-plan__list">
            {plan.evidence.map((event) => (
              <li key={event.id}>
                <span>{formatMoscowDateTime(event.occurredAt)}, {event.actorName}</span>
                {event.comment && <span className="agent-plan__note">{event.comment}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}
      {state.requestId !== undefined && (
        <div className="interaction-command-error" role="alert">
          <p>Не удалось выполнить действие. Обновите страницу и повторите.</p>
          <SupportDetails requestId={state.requestId} />
        </div>
      )}
      {pending && (
        <>
          <p className="agent-plan__expires">План действует до {formatMoscowDateTime(plan.expiresAt)} (Москва).</p>
          <div className="agent-plan__actions">
            <button type="button" disabled={saving || plan.cardChanged} onClick={() => void decide(plan, 'apply')}>
              {saving ? 'Записываем…' : 'Записать'}
            </button>
            <button type="button" className="button--secondary" disabled={saving} onClick={() => void decide(plan, 'reject')}>
              Отклонить
            </button>
          </div>
        </>
      )}
      <a href="#/agent-plans">Все мои планы</a>
    </section>
  )
}

// Тексты плана написал ИИ-агент, а тексты фактов — люди и внешние системы: React выводит их как текст, поэтому
// команда внутри текста остаётся данными, а ссылки не становятся активными.
export const AgentPlanScreen = ({ planId, onSessionExpired, onProfileUnavailable }: Props) => (
  planId === undefined
    ? <AgentPlanList onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} />
    : <PlanView planId={planId} onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} />
)
