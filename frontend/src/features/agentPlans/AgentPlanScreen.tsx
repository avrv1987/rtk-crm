import { useEffect, useState } from 'react'
import { ApiError, apiClient, type AgentPlan } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { handledAccessError } from '../documents/documentsApi'
import { canDecide, statusText } from './agentPlan'
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

// Тексты плана написал ИИ-агент: React выводит их как текст, поэтому команда внутри комментария остаётся данными.
export const AgentPlanScreen = ({ planId, onSessionExpired, onProfileUnavailable }: Props) => {
  const [state, setState] = useState<State>({ kind: 'loading' })

  const failed = (error: unknown) => {
    if (handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
      return
    }
    if (error instanceof ApiError && error.status === 404) {
      setState({ kind: 'missing' })
      return
    }
    setState({ kind: 'failed', requestId: error instanceof ApiError ? error.requestId : undefined })
  }

  useEffect(() => {
    if (planId === undefined) {
      setState({ kind: 'missing' })
      return
    }
    apiClient.getAgentPlan(planId).then((plan) => setState({ kind: 'ready', plan, saving: false }), failed)
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
    return <p>План не найден. Его мог подготовить агент другого сотрудника, или ссылка неполная.</p>
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
      <h2 id="agent-plan-title">Запись от ИИ-агента</h2>
      <p className={plan.status === 'APPLIED' ? 'security-success' : 'agent-plan__status'} role="status">{statusText(plan)}</p>
      <dl className="agent-plan__facts">
        <dt>Вуз</dt>
        <dd>{plan.organizationName}</dd>
        <dt>Взаимодействие</dt>
        <dd><a href={`#/organizations/${plan.organizationId}/${plan.interactionId}`}>{plan.interactionTitle}</a></dd>
        {plan.stageName && (<><dt>Этап</dt><dd>{plan.stageName}</dd></>)}
        <dt>Подготовил</dt>
        <dd>{plan.clientId ?? 'клиент не указан'}, {formatMoscowDateTime(plan.createdAt)}</dd>
      </dl>
      <h3>Комментарий (итог встречи)</h3>
      <p className="agent-plan__note">{plan.note}</p>
      <table className="agent-plan__diff">
        <caption>Следующий шаг: было и станет</caption>
        <thead>
          <tr><th scope="col"></th><th scope="col">Было</th><th scope="col">Станет</th></tr>
        </thead>
        <tbody>
          <tr>
            <th scope="row">Шаг</th>
            <td>{pending ? plan.currentNextAction ?? 'не задан' : '—'}</td>
            <td>{plan.nextAction}</td>
          </tr>
          <tr>
            <th scope="row">Срок</th>
            <td>{pending ? dateTime(plan.currentNextActionAt) : '—'}</td>
            <td>{dateTime(plan.nextActionAt)}</td>
          </tr>
        </tbody>
      </table>
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
    </section>
  )
}
