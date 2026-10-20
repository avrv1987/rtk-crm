import { useCallback, useEffect, useId, useState, type FormEvent } from 'react'
import type { Me } from '../../shared/api/client'
import { todayInMoscow } from '../../shared/format/datetime'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { CardDialog, RequiredMark } from '../interactions/cardUi'
import { formatDate, formatInstant, formatPeriod } from './agreementsApi'
import { CommandError, useAccessErrorHandler, useCommandKey } from './agreementUi'
import { discardRecordDraft, useRecordDraft } from '../interactions/formDraft'
import { reportingApi, type ActivityList, type ActivityListsView, type ActivityListSummary } from './reportingApi'
import {
  activityListChannelLabels,
  activityListStatusLabels,
  formatMoney,
  listEditable,
  type ActivityListAction,
  type ActivityListStatus
} from './reportingModel'
import './agreements.css'
import './agreementReporting.css'

type Load<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T }
  | { kind: 'failed'; error: unknown }

const statusClass: Record<ActivityListStatus, string> = {
  DRAFT: 'status status--planned',
  REVIEW: 'status status--missing',
  APPROVED: 'status status--planned',
  APPROVED_SILENTLY: 'status status--planned',
  RETURNED: 'status status--overdue'
}

export const ActivityListStatusMark = ({ status }: { status: ActivityListStatus }) => (
  <span className={statusClass[status]}>{activityListStatusLabels[status]}</span>
)

const summaryLine = (summary: ActivityListSummary) => [
  summary.periodLabel,
  `позиций: ${summary.itemCount}`,
  summary.status === 'REVIEW' && summary.reviewDueOn ? `ответ вуза до ${formatDate(summary.reviewDueOn)}` : null
].filter((part) => part !== null).join(' · ')

// Позиции и история перечня — одинаково в карточке вуза и в кабинете вуза.
export const ActivityListContent = ({ list }: { list: ActivityList }) => {
  const { summary } = list
  return (
    <>
      <dl className="agreement-activity__facts">
        <div><dt>Период</dt><dd>{summary.periodLabel}</dd></div>
        <div><dt>Статус</dt><dd><ActivityListStatusMark status={summary.status} /></dd></div>
        {summary.sentOn && <div><dt>Отправлен вузу</dt><dd>{formatDate(summary.sentOn)}{summary.reviewRound > 1 ? `, круг ${summary.reviewRound}` : ''}</dd></div>}
        {summary.reviewDueOn && <div><dt>Срок ответа вуза</dt><dd>до {formatDate(summary.reviewDueOn)} включительно</dd></div>}
        {summary.decidedOn && <div><dt>Ответ вуза</dt><dd>{formatDate(summary.decidedOn)}</dd></div>}
      </dl>
      {list.items.length === 0 ? <p>Позиций нет.</p> : (
        <div className="reporting-table" role="region" aria-label="Позиции перечня" tabIndex={0}>
          <table>
            <caption>Позиции ({list.items.length})</caption>
            <thead>
              <tr>
                <th scope="col">Договор</th>
                <th scope="col">Вид</th>
                <th scope="col">Мероприятие</th>
                <th scope="col">Уровень</th>
                <th scope="col">Исполнитель</th>
                <th scope="col">Единица</th>
                <th scope="col">Количество</th>
                <th scope="col">Охват</th>
                <th scope="col">Стоимость, ₽</th>
                <th scope="col">Сроки</th>
              </tr>
            </thead>
            <tbody>
              {list.items.map((item, index) => (
                <tr key={`${item.activityId ?? 'removed'}:${index}`}>
                  <td>№ {item.agreementNumber}</td>
                  <td>{item.kindName}</td>
                  <th scope="row">{item.title}{item.activityId === null && <span className="admin-table__sub">удалено из плана</span>}</th>
                  <td>{item.educationLevel ?? ''}</td>
                  <td>{item.executor ?? ''}</td>
                  <td>{item.unit ?? ''}</td>
                  <td className="reporting-table__number">{item.plannedVolume ?? ''}</td>
                  <td className="reporting-table__number">{item.participants ?? ''}</td>
                  <td className="reporting-table__number">{formatMoney(item.cost)}</td>
                  <td>{item.periodStart || item.periodEnd ? formatPeriod(item.periodStart, item.periodEnd) : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <details className="card-section">
        <summary className="card-section__summary">История{list.history.length > 0 && <span className="card-tabs__count">{list.history.length}</span>}</summary>
        <div className="card-section__body">
          {list.history.length === 0 ? <p>Событий пока нет.</p> : (
            <ol className="reporting-history">
              {list.history.map((event) => (
                <li key={event.id}>
                  <span>
                    {event.fromStatus ? `${activityListStatusLabels[event.fromStatus]} → ` : ''}{activityListStatusLabels[event.toStatus]}
                  </span>
                  <span className="agreement-activity__document-meta">
                    {[formatInstant(event.occurredAt), activityListChannelLabels[event.channel], event.actorName].filter(Boolean).join(' · ')}
                  </span>
                  {event.comment && <span className="agreement-files__comment">{event.comment}</span>}
                </li>
              ))}
            </ol>
          )}
        </div>
      </details>
    </>
  )
}

type ActivityListsPanelProps = {
  organizationId: string
  role: Me['role']
  initialListId?: string
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

export const ActivityListsPanel = ({ organizationId, role, initialListId, onSessionExpired, onProfileUnavailable }: ActivityListsPanelProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [view, setView] = useState<Load<ActivityListsView>>({ kind: 'loading' })
  const [selectedId, setSelectedId] = useState<string | null>(initialListId ?? null)
  const [creating, setCreating] = useState(false)
  const canEdit = role === 'USER' || role === 'LEADER'
  const titleId = useId()

  const load = useCallback(async () => {
    try {
      setView({ kind: 'ready', value: await reportingApi.lists(organizationId) })
    } catch (error) {
      if (!handleAccessError(error)) {
        setView({ kind: 'failed', error })
      }
    }
  }, [handleAccessError, organizationId])

  useEffect(() => {
    void load()
  }, [load])

  // Ссылка из напоминаний (?activityList=…) открывает перечень и прокручивает к нему.
  useEffect(() => {
    if (initialListId !== undefined && view.kind === 'ready') {
      window.requestAnimationFrame(() => document.getElementById(`activity-list-${initialListId}`)?.scrollIntoView({ block: 'start' }))
    }
  }, [initialListId, view.kind])

  return (
    <section className="activity-lists" aria-labelledby={titleId}>
      <div className="activity-lists__header">
        <h4 id={titleId}>Перечни мероприятий</h4>
        <HintButton label="Как согласуется перечень">
          Перечень собирается из планов мероприятий договоров вуза за период (календарный или учебный год — по настройке
          администратора) и отправляется вузу на согласование. Если у вуза есть координатор с доступом к кабинету, он сам
          согласует перечень или вернёт его на доработку; если координатора нет, ответ вуза отмечает КАМ. Если срок ответа
          задан в настройках и вуз не ответил до его конца, перечень считается согласованным без ответа («молчанием»).
        </HintButton>
        {canEdit && view.kind === 'ready' && (
          <button type="button" className="button--secondary" onClick={() => setCreating(true)}>Сформировать перечень</button>
        )}
      </div>
      {view.kind === 'loading' && <p role="status">Загружаем перечни…</p>}
      {view.kind === 'failed' && <CommandError error={view.error} fallback="Не удалось загрузить перечни." onRetry={() => void load()} />}
      {view.kind === 'ready' && (
        <>
          <p className="agreements__hint">
            {view.value.coordinatorAvailable
              ? 'У вуза есть координатор: перечень он согласует в кабинете вуза.'
              : 'У вуза нет координатора с доступом к кабинету: ответ вуза отмечает КАМ.'}
          </p>
          {view.value.lists.length === 0 ? <p>Перечней пока нет.</p> : (
            <ul className="activity-lists__list">
              {view.value.lists.map((summary) => (
                <li key={summary.id} id={`activity-list-${summary.id}`}>
                  <button
                    type="button"
                    className={`agreements__item${summary.id === selectedId ? ' agreements__item--selected' : ''}`}
                    aria-expanded={summary.id === selectedId}
                    onClick={() => setSelectedId(summary.id === selectedId ? null : summary.id)}
                  >
                    <span className="agreements__item-title">{summary.title}</span>
                    <ActivityListStatusMark status={summary.status} />
                    <span className="agreements__item-meta">{summaryLine(summary)}</span>
                  </button>
                  {summary.id === selectedId && (
                    <ActivityListCard
                      key={`${summary.id}:${summary.version}`}
                      listId={summary.id}
                      canEdit={canEdit}
                      handleAccessError={handleAccessError}
                      onChanged={() => void load()}
                    />
                  )}
                </li>
              ))}
            </ul>
          )}
          <CardDialog open={creating} title="Сформировать перечень мероприятий" onClose={() => setCreating(false)}>
            {creating && (
              <CreateListForm
                organizationId={organizationId}
                defaultYear={view.value.currentPeriod.year}
                handleAccessError={handleAccessError}
                onCancel={() => setCreating(false)}
                onCreated={(list) => {
                  setCreating(false)
                  setSelectedId(list.summary.id)
                  void load()
                }}
              />
            )}
          </CardDialog>
        </>
      )}
    </section>
  )
}

type CreateListFormProps = {
  organizationId: string
  defaultYear: number
  handleAccessError: (error: unknown) => boolean
  onCancel: () => void
  onCreated: (list: ActivityList) => void
}

const CreateListForm = ({ organizationId, defaultYear, handleAccessError, onCancel, onCreated }: CreateListFormProps) => {
  const draft = useRecordDraft(`activity-list:new:${organizationId}`, 'новый перечень мероприятий', { year: String(defaultYear), title: '' })
  const { year, title } = draft.values
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload = { year: Number(year), title: title.trim() === '' ? null : title.trim(), activityIds: null }
    setSaving(true)
    setError(null)
    try {
      const list = await reportingApi.createList(organizationId, payload, commandKey.keyFor(payload))
      commandKey.settle()
      draft.clear()
      onCreated(list)
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="admin-form" onSubmit={(event) => void submit(event)} aria-label="Новый перечень мероприятий">
      <label>
        <span>Год периода<RequiredMark /></span>
        <input type="number" min={2000} max={2100} step={1} inputMode="numeric" required value={year} disabled={saving}
          onChange={(event) => draft.set({ year: event.target.value })} />
      </label>
      <p className="admin-form__hint">Для учебного года — год, в котором он начинается.</p>
      <label>
        Название (необязательно)
        <input value={title} maxLength={300} disabled={saving} onChange={(event) => draft.set({ title: event.target.value })} />
      </label>
      <p className="admin-form__hint">
        В перечень войдут все мероприятия договоров вуза за период, кроме отменённых и отклонённых и договоров «Расторгнут».
        Лишние позиции можно снять, пока перечень — проект.
      </p>
      {error !== null && <CommandError error={error} fallback="Перечень не сформирован." />}
      <div className="admin-form__actions">
        <button type="button" className="button--secondary" disabled={saving} onClick={() => { draft.clear(); onCancel() }}>Отмена</button>
        <button type="submit" disabled={saving || year === ''}>{saving ? 'Формируем…' : 'Сформировать'}</button>
      </div>
    </form>
  )
}

type ActivityListCardProps = {
  listId: string
  canEdit: boolean
  handleAccessError: (error: unknown) => boolean
  onChanged: () => void
}

const ActivityListCard = ({ listId, canEdit, handleAccessError, onChanged }: ActivityListCardProps) => {
  const [state, setState] = useState<Load<ActivityList>>({ kind: 'loading' })
  const [editing, setEditing] = useState(false)
  const [answer, setAnswer] = useState<'APPROVE' | 'RETURN' | null>(null)
  const [sending, setSending] = useState(false)
  const emptyHintId = useId()
  const [error, setError] = useState<unknown>(null)
  const sendKey = useCommandKey()

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', value: await reportingApi.list(listId) })
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setState({ kind: 'failed', error: caught })
      }
    }
  }, [handleAccessError, listId])

  useEffect(() => {
    void load()
  }, [load])

  // Н-А4: перечень ушёл вузу — черновик правки названия и состава больше не применить.
  const editable = state.kind === 'ready' && listEditable(state.value.summary.status)
  useEffect(() => {
    if (state.kind === 'ready' && !editable) {
      discardRecordDraft(`activity-list:${listId}`)
    }
  }, [editable, listId, state.kind])

  if (state.kind === 'loading') {
    return <p role="status">Загружаем перечень…</p>
  }
  if (state.kind === 'failed') {
    return <CommandError error={state.error} fallback="Не удалось открыть перечень." onRetry={() => void load()} />
  }
  const list = state.value
  const changed = (updated: ActivityList) => {
    setState({ kind: 'ready', value: updated })
    onChanged()
  }
  const has = (action: ActivityListAction) => list.actions.includes(action)
  // После конфликта окно закрывается, а перечень перечитывается: виден его новый статус и история.
  const reloadAfterConflict = () => {
    setEditing(false)
    setAnswer(null)
    void load()
    onChanged()
  }

  const send = async () => {
    setSending(false)
    setError(null)
    const payload = { version: list.summary.version, action: 'SEND' as const }
    try {
      const updated = await reportingApi.transitionList(list.summary.id, payload, sendKey.keyFor({ id: list.summary.id, ...payload }))
      sendKey.settle()
      changed(updated)
    } catch (caught) {
      sendKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    }
  }

  return (
    <article className="activity-list" aria-label={`Перечень «${list.summary.title}»`}>
      <ActivityListContent list={list} />
      {(has('SEND') || has('APPROVE') || has('RETURN') || (canEdit && listEditable(list.summary.status))) && (
        <div className="agreement-activity__actions">
          {has('SEND') && (
            <button type="button" disabled={list.items.length === 0} aria-describedby={list.items.length === 0 ? emptyHintId : undefined} onClick={() => setSending(true)}>Отправить вузу</button>
          )}
          {(has('APPROVE') || has('RETURN')) && (
            <>
              {has('APPROVE') && <button type="button" className="button--secondary" onClick={() => setAnswer('APPROVE')}>Отметить ответ вуза: согласован</button>}
              {has('RETURN') && <button type="button" className="button--secondary" onClick={() => setAnswer('RETURN')}>Отметить ответ вуза: возвращён</button>}
            </>
          )}
          {canEdit && listEditable(list.summary.status) && (
            <button type="button" className="button--secondary" onClick={() => setEditing(true)}>Изменить название и состав</button>
          )}
        </div>
      )}
      {has('SEND') && list.items.length === 0 && (
        <p id={emptyHintId} className="agreement-form__hint">Отправить пустой перечень нельзя: добавьте мероприятия плана договоров через «Изменить название и состав».</p>
      )}
      {(has('APPROVE') || has('RETURN')) && (
        <p className="agreement-form__hint">У вуза нет координатора в кабинете, поэтому ответ вуза отмечает КАМ: дата ответа и комментарий попадут в историю.</p>
      )}
      {error !== null && <CommandError error={error} fallback="Действие не выполнено." onRetry={() => { setError(null); void load() }} retryLabel="Обновить перечень" />}
      <ConfirmDialog
        open={sending}
        title="Отправить перечень вузу?"
        description={list.coordinatorAvailable
          ? 'Перечень появится в кабинете вуза; координатор согласует его или вернёт на доработку.'
          : 'У вуза нет координатора: отправьте перечень вузу привычным способом, а его ответ отметьте здесь.'}
        confirmLabel="Отправить"
        onConfirm={() => void send()}
        onCancel={() => setSending(false)}
      />
      <CardDialog open={answer !== null} title={answer === 'RETURN' ? 'Вуз вернул перечень на доработку' : 'Вуз согласовал перечень'} onClose={() => setAnswer(null)}>
        {answer !== null && (
          <AnswerForm
            list={list}
            action={answer}
            handleAccessError={handleAccessError}
            onCancel={() => setAnswer(null)}
            onReload={reloadAfterConflict}
            onDone={(updated) => { setAnswer(null); changed(updated) }}
          />
        )}
      </CardDialog>
      <CardDialog open={editing} title="Название и состав перечня" onClose={() => setEditing(false)}>
        {editing && (
          <EditListForm
            list={list}
            handleAccessError={handleAccessError}
            onCancel={() => setEditing(false)}
            onReload={reloadAfterConflict}
            onDone={(updated) => { setEditing(false); changed(updated) }}
          />
        )}
      </CardDialog>
    </article>
  )
}

type AnswerFormProps = {
  list: ActivityList
  action: 'APPROVE' | 'RETURN'
  handleAccessError: (error: unknown) => boolean
  onCancel: () => void
  onReload: () => void
  onDone: (list: ActivityList) => void
}

const AnswerForm = ({ list, action, handleAccessError, onCancel, onReload, onDone }: AnswerFormProps) => {
  const today = todayInMoscow()
  const draft = useRecordDraft(
    `activity-list:${list.summary.id}:answer:${action}`,
    `ответ вуза на перечень «${list.summary.title}»`,
    { respondedOn: today, comment: '' }
  )
  const { respondedOn, comment } = draft.values
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()
  const commentRequired = action === 'RETURN'

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload = {
      version: list.summary.version,
      action,
      respondedOn: respondedOn === '' ? null : respondedOn,
      comment: comment.trim() === '' ? null : comment.trim()
    }
    setSaving(true)
    setError(null)
    try {
      const updated = await reportingApi.transitionList(list.summary.id, payload, commandKey.keyFor({ id: list.summary.id, ...payload }))
      commandKey.settle()
      draft.clear()
      onDone(updated)
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="admin-form" onSubmit={(event) => void submit(event)} aria-label="Ответ вуза на перечень">
      <label>
        Дата ответа вуза
        <input type="date" value={respondedOn} min={list.summary.sentOn ?? undefined} max={today} disabled={saving}
          onChange={(event) => draft.set({ respondedOn: event.target.value })} />
      </label>
      <label>
        <span>Комментарий{commentRequired && <RequiredMark />}</span>
        <textarea value={comment} rows={3} maxLength={2000} required={commentRequired} disabled={saving}
          placeholder={commentRequired ? 'Что вуз просит изменить' : 'Например, письмо вуза: номер и дата'}
          onChange={(event) => draft.set({ comment: event.target.value })} />
      </label>
      {error !== null && <CommandError error={error} fallback="Ответ вуза не отмечен." onReload={onReload} />}
      <div className="admin-form__actions">
        <button type="button" className="button--secondary" disabled={saving} onClick={() => { draft.clear(); onCancel() }}>Отмена</button>
        <button type="submit" disabled={saving || (commentRequired && comment.trim() === '')}>{saving ? 'Сохраняем…' : 'Отметить'}</button>
      </div>
    </form>
  )
}

type EditListFormProps = {
  list: ActivityList
  handleAccessError: (error: unknown) => boolean
  onCancel: () => void
  onReload: () => void
  onDone: (list: ActivityList) => void
}

const EditListForm = ({ list, handleAccessError, onCancel, onReload, onDone }: EditListFormProps) => {
  const kept = list.items.flatMap((item) => (item.activityId === null ? [] : [item.activityId]))
  const draft = useRecordDraft(
    `activity-list:${list.summary.id}`,
    `название и состав перечня «${list.summary.title}»`,
    { title: list.summary.title, activityIds: kept }
  )
  const { title, activityIds } = draft.values
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()
  const removed = list.items.some((item) => item.activityId === null)

  // activityIds: null — сервер заново собирает перечень из плана договоров на тот же период.
  const save = async (rebuild: boolean) => {
    const payload = { version: list.summary.version, title: title.trim(), activityIds: rebuild ? null : activityIds }
    setSaving(true)
    setError(null)
    try {
      const updated = await reportingApi.updateList(list.summary.id, payload, commandKey.keyFor({ id: list.summary.id, ...payload }))
      commandKey.settle()
      draft.clear()
      onDone(updated)
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="agreement-form" aria-label="Изменение перечня" onSubmit={(event) => { event.preventDefault(); void save(false) }}>
      <label>
        <span>Название<RequiredMark /></span>
        <input value={title} required maxLength={300} disabled={saving} onChange={(event) => draft.set({ title: event.target.value })} />
      </label>
      <fieldset className="agreement-form__choices">
        <legend>Позиции перечня</legend>
        <p>Снимите флажок, чтобы убрать позицию. Новые мероприятия плана добавляет «Собрать заново из плана».</p>
        {removed && <p>Позиции, чьи мероприятия удалены из плана, при сохранении уйдут из перечня.</p>}
        <ul>
          {list.items.flatMap((item) => (item.activityId === null ? [] : [{ ...item, activityId: item.activityId }])).map((item) => (
            <li key={item.activityId}>
              <label>
                <input
                  type="checkbox"
                  checked={activityIds.includes(item.activityId)}
                  disabled={saving}
                  onChange={(event) => draft.set({
                    activityIds: event.target.checked ? [...activityIds, item.activityId] : activityIds.filter((id) => id !== item.activityId)
                  })}
                />
                <span>{item.title}<span className="agreement-activity__document-meta"> — договор № {item.agreementNumber}, {item.kindName}</span></span>
              </label>
            </li>
          ))}
        </ul>
      </fieldset>
      {error !== null && <CommandError error={error} fallback="Перечень не сохранён." onReload={onReload} />}
      <div className="agreement-form__actions">
        <button type="submit" disabled={saving || title.trim() === ''}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
        <button type="button" className="button--secondary" disabled={saving || title.trim() === ''} onClick={() => void save(true)}>
          Собрать заново из плана
        </button>
        <button type="button" className="button--secondary" disabled={saving} onClick={() => { draft.clear(); onCancel() }}>Отмена</button>
      </div>
    </form>
  )
}
