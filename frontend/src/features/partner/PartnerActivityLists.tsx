import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { ActivityListContent, ActivityListStatusMark } from '../agreements/ActivityListsPanel'
import { formatDate } from '../agreements/agreementsApi'
import { useCommandKey } from '../agreements/agreementUi'
import { reportingApi, type ActivityList } from '../agreements/reportingApi'
import { handledAccessError } from '../interactions/workMarks'
import { useRecordDraft } from '../interactions/formDraft'

type PartnerActivityListsProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
  // Н-А3: кабинет считает перечни «На согласовании» в «Требуется от вуза» и показывает счётчик в навигации.
  onLoaded?: (lists: ActivityList[]) => void
}

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; lists: ActivityList[] }
  | { kind: 'failed'; error: unknown }

const errorText = (error: unknown, fallback: string) => (
  error instanceof ApiError && (error.status === 403 || error.status === 409 || error.code === 'VALIDATION_ERROR')
    ? Object.values(error.fieldErrors ?? {})[0] ?? error.message
    : fallback
)

// Перечни своего вуза: координатор согласует перечень или возвращает его на доработку с комментарием.
export const PartnerActivityLists = ({ onSessionExpired, onProfileUnavailable, onLoaded }: PartnerActivityListsProps) => {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const lists = state.kind === 'ready' ? state.lists : null
  useEffect(() => {
    if (lists !== null) {
      onLoaded?.(lists)
    }
  }, [lists, onLoaded])

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', lists: await reportingApi.partnerLists() })
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <section className="partner-cabinet__section" aria-labelledby="partner-activity-lists">
      <h3 id="partner-activity-lists" tabIndex={-1}>Перечни мероприятий</h3>
      <p className="interaction-field-hint">
        Перечень мероприятий по договорам на период готовит ваш менеджер. Перечень «На согласовании» ждёт вашего решения:
        согласуйте его или верните на доработку с комментарием.
      </p>
      {state.kind === 'loading' && <p role="status">Загружаем перечни…</p>}
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>Не удалось загрузить перечни мероприятий.</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
          <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && state.lists.length === 0 && <p>Перечней пока нет.</p>}
      {state.kind === 'ready' && state.lists.length > 0 && (
        <ul className="partner-cabinet__list">
          {state.lists.map((list) => (
            <PartnerListCard
              key={`${list.summary.id}:${list.summary.version}`}
              list={list}
              onChanged={(updated) => setState((current) => (current.kind === 'ready'
                ? { kind: 'ready', lists: current.lists.map((item) => (item.summary.id === updated.summary.id ? updated : item)) }
                : current))}
              onSessionExpired={onSessionExpired}
              onProfileUnavailable={onProfileUnavailable}
            />
          ))}
        </ul>
      )}
    </section>
  )
}

type PartnerListCardProps = PartnerActivityListsProps & {
  list: ActivityList
  onChanged: (list: ActivityList) => void
}

const PartnerListCard = ({ list, onChanged, onSessionExpired, onProfileUnavailable }: PartnerListCardProps) => {
  const draft = useRecordDraft(`partner-list:${list.summary.id}:return`, `возврат перечня «${list.summary.title}»`, { comment: '' })
  const { comment } = draft.values
  // Начатый возврат после F5 или повторного входа открывается сразу с введённым текстом.
  const [returning, setReturning] = useState(draft.edited)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()
  const { summary } = list

  const decide = async (decision: 'APPROVE' | 'RETURN') => {
    const payload = { version: summary.version, decision, comment: comment.trim() === '' ? null : comment.trim() }
    setSaving(true)
    setError(null)
    try {
      const updated = await reportingApi.decidePartnerList(summary.id, payload, commandKey.keyFor({ id: summary.id, ...payload }))
      commandKey.settle()
      draft.clear()
      onChanged(updated)
    } catch (caught) {
      commandKey.settle(caught)
      if (!handledAccessError(caught, onSessionExpired, onProfileUnavailable)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  const submitReturn = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void decide('RETURN')
  }

  return (
    <li className="partner-card">
      <div className="partner-card__header">
        <strong>{summary.title}</strong>
        <ActivityListStatusMark status={summary.status} />
      </div>
      {summary.status === 'REVIEW' && summary.reviewDueOn && (
        <p className="partner-card__meta">
          Ответьте до {formatDate(summary.reviewDueOn)} включительно. Если ответа не будет до конца срока, перечень будет
          считаться согласованным.
        </p>
      )}
      <ActivityListContent list={list} />
      {summary.status === 'REVIEW' && !returning && (
        <div className="partner-request__actions">
          <button type="button" disabled={saving} onClick={() => void decide('APPROVE')}>{saving ? 'Сохраняем…' : 'Согласовать'}</button>
          <button type="button" className="button--secondary" disabled={saving} onClick={() => setReturning(true)}>Вернуть на доработку</button>
        </div>
      )}
      {summary.status === 'REVIEW' && returning && (
        <form className="partner-request__form" onSubmit={submitReturn} aria-label={`Возврат перечня «${summary.title}»`}>
          <label>
            Что нужно изменить<span className="required-mark" aria-hidden="true"> *</span>
            <textarea value={comment} rows={3} maxLength={2000} required disabled={saving} onChange={(event) => draft.set({ comment: event.target.value })} />
          </label>
          <div className="partner-request__actions">
            <button type="submit" disabled={saving || comment.trim() === ''}>{saving ? 'Отправляем…' : 'Вернуть на доработку'}</button>
            <button type="button" className="button--secondary" disabled={saving} onClick={() => { draft.clear(); setReturning(false) }}>Отмена</button>
          </div>
        </form>
      )}
      {error !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{errorText(error, 'Решение не сохранено. Повторите попытку; если не получится, напишите своему менеджеру.')}</p>
          {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
        </div>
      )}
    </li>
  )
}
