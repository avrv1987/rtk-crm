import { useCallback, useEffect, useState, type FormEvent } from 'react'
import {
  ApiError,
  apiClient,
  type PersonalDataRequest,
  type PersonalDataRequestInput,
  type PersonalDataRequestKind,
  type PersonalDataRequestStatus,
  type PersonalDataRequests
} from '../../shared/api/client'
import { formatCalendarDate, todayInMoscow } from '../../shared/format/datetime'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { closedStatus, dueText, requestKindLabels, requestStatusLabels } from './adminSettings'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import { saveFile } from './saveFile'
import './adminUi.css'

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; data: PersonalDataRequests }
  | { kind: 'failed'; requestId?: string }

type Draft = {
  id: string | null
  receivedOn: string
  subjectName: string
  kind: PersonalDataRequestKind
  channel: string
  dueOn: string
  status: PersonalDataRequestStatus
  completedOn: string
  auditEventId: string
  assigneeId: string
  note: string
}

const kinds = Object.keys(requestKindLabels) as PersonalDataRequestKind[]
const statuses = Object.keys(requestStatusLabels) as PersonalDataRequestStatus[]

const newDraft = (): Draft => ({
  id: null, receivedOn: todayInMoscow(), subjectName: '', kind: 'INFORMATION', channel: '', dueOn: '', status: 'NEW', completedOn: '',
  auditEventId: '', assigneeId: '', note: ''
})

const draftOf = (request: PersonalDataRequest): Draft => ({
  id: request.id,
  receivedOn: request.receivedOn,
  subjectName: request.subjectName,
  kind: request.kind,
  channel: request.channel,
  dueOn: request.dueOn,
  status: request.status,
  completedOn: request.completedOn ?? '',
  auditEventId: request.execution?.auditEventId ?? '',
  assigneeId: request.assigneeId ?? '',
  note: request.note ?? ''
})

const payloadOf = (draft: Draft): PersonalDataRequestInput => ({
  receivedOn: draft.receivedOn,
  subjectName: draft.subjectName.trim(),
  kind: draft.kind,
  channel: draft.channel.trim(),
  dueOn: draft.dueOn === '' ? null : draft.dueOn,
  status: draft.status,
  completedOn: closedStatus(draft.status) && draft.completedOn !== '' ? draft.completedOn : null,
  auditEventId: draft.auditEventId === '' ? null : draft.auditEventId,
  assigneeId: draft.assigneeId === '' ? null : draft.assigneeId,
  note: draft.note.trim() === '' ? null : draft.note.trim()
})

const dueClass = (request: PersonalDataRequest) => (
  request.dueState === 'OVERDUE' ? 'status status--overdue' : request.dueState === 'SOON' ? 'status status--missing' : undefined
)

export const PersonalDataRequestsPanel = ({ profileId, onSessionExpired, onProfileUnavailable }: SessionHandlers & { profileId: string }) => {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const handleError = useCallback((value: unknown) => (
    handledSessionError(value, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', data: await apiClient.listPersonalDataRequests() })
    } catch (value) {
      if (!handleError(value)) {
        setState({ kind: 'failed', requestId: requestIdOf(value) })
      }
    }
  }, [handleError])

  useEffect(() => {
    void load()
  }, [load])

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (draft === null) {
      return
    }
    setSaving(true)
    setError(null)
    try {
      const payload = payloadOf(draft)
      if (draft.id === null) {
        await apiClient.createPersonalDataRequest(payload)
      } else {
        await apiClient.updatePersonalDataRequest(draft.id, payload)
      }
      setDraft(null)
      setNotice(draft.id === null ? 'Обращение зарегистрировано.' : 'Обращение сохранено.')
      await load()
    } catch (value) {
      if (!handleError(value)) {
        setError(value)
      }
    } finally {
      setSaving(false)
    }
  }

  const exportJournal = async () => {
    setError(null)
    try {
      saveFile(await apiClient.exportPersonalDataRequests(), `Журнал обращений субъектов ПДн ${todayInMoscow()}.csv`)
    } catch (value) {
      if (!handleError(value)) {
        setError(value)
      }
    }
  }

  const data = state.kind === 'ready' ? state.data : null

  return (
    <section className="admin-page" aria-labelledby="pd-requests-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="pd-requests-title">Обращения субъектов ПДн</h2>
        <HintButton label="О разделе">
          Учёт обращений: когда и как поступило, срок ответа, исполнитель и чем исполнено. Само исполнение — выгрузка
          сведений, уточнение, ограничение или обезличивание — делается в разделе «Персональные данные», а здесь обращение
          связывается с записью журнала об этом действии. Срок ответа по умолчанию задаётся в «Настройках»; о близком
          сроке напоминает колокольчик.
        </HintButton>
        <span className="page-title__spacer" />
        <button type="button" className="button--secondary" onClick={() => void exportJournal()}>Выгрузить журнал</button>
        <button type="button" onClick={() => { setNotice(null); setDraft(newDraft()) }}>Зарегистрировать обращение</button>
      </div>
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем журнал обращений…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить журнал обращений.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {error !== null && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>{error instanceof ApiError ? error.message : 'Не удалось выполнить действие. Повторите попытку позже.'}</p>
          <SupportDetails requestId={requestIdOf(error)} />
        </div>
      )}
      {notice !== null && <p className="organizations-message" role="status">{notice}</p>}
      {draft !== null && data !== null && (
        <form className="admin-form" onSubmit={(event) => void save(event)}
          aria-label={draft.id === null ? 'Новое обращение' : 'Обращение'}>
          <label>
            Дата поступления *
            <input type="date" required max={todayInMoscow()} value={draft.receivedOn}
              onChange={(event) => setDraft({ ...draft, receivedOn: event.target.value })} />
          </label>
          <label>
            Субъект: ФИО или описание *
            <input required maxLength={300} value={draft.subjectName}
              onChange={(event) => setDraft({ ...draft, subjectName: event.target.value })} />
          </label>
          <label>
            Вид *
            <select value={draft.kind} onChange={(event) => setDraft({ ...draft, kind: event.target.value as PersonalDataRequestKind })}>
              {kinds.map((kind) => <option key={kind} value={kind}>{requestKindLabels[kind]}</option>)}
            </select>
          </label>
          <label>
            Канал поступления *
            <input required maxLength={200} placeholder="письмо, лично, через вуз" value={draft.channel}
              onChange={(event) => setDraft({ ...draft, channel: event.target.value })} />
          </label>
          <label>
            Срок ответа
            <input type="date" value={draft.dueOn} onChange={(event) => setDraft({ ...draft, dueOn: event.target.value })} />
            <span className="admin-form__hint">
              Пусто — дата поступления плюс {data.responseDays} {data.workingDays ? 'рабочих дн. (пн–пт, праздники не учитываются)' : 'календарных дн.'} (настройка)
            </span>
          </label>
          <label>
            Исполнитель
            <select value={draft.assigneeId} onChange={(event) => setDraft({ ...draft, assigneeId: event.target.value })}>
              <option value="">{draft.id === null ? 'Я' : 'Не менять'}</option>
              {/* В новом обращении пустой пункт «Я» уже означает текущего администратора — сам он в списке не повторяется. */}
              {data.assignees.filter((assignee) => draft.id !== null || assignee.id !== profileId).map((assignee) => <option key={assignee.id} value={assignee.id}>{assignee.name}</option>)}
            </select>
          </label>
          <label>
            Статус
            <select value={draft.status}
              onChange={(event) => setDraft({ ...draft, status: event.target.value as PersonalDataRequestStatus })}>
              {statuses.map((status) => <option key={status} value={status}>{requestStatusLabels[status]}</option>)}
            </select>
          </label>
          {closedStatus(draft.status) && (
            <label>
              Дата исполнения
              <input type="date" max={todayInMoscow()} value={draft.completedOn}
                onChange={(event) => setDraft({ ...draft, completedOn: event.target.value })} />
              <span className="admin-form__hint">Пусто — сегодня</span>
            </label>
          )}
          <label>
            Исполнение в журнале
            <select value={draft.auditEventId} onChange={(event) => setDraft({ ...draft, auditEventId: event.target.value })}>
              <option value="">Не указано</option>
              {data.executions.map((execution) => (
                <option key={execution.auditEventId ?? ''} value={execution.auditEventId ?? ''}>
                  {formatDateTime(execution.occurredAt)} — {execution.label}{execution.details === null ? '' : `: ${execution.details}`}
                </option>
              ))}
            </select>
          </label>
          <label>
            Примечание
            <textarea maxLength={1000} value={draft.note} onChange={(event) => setDraft({ ...draft, note: event.target.value })} />
          </label>
          <div className="admin-form__actions">
            <button type="button" className="button--secondary" disabled={saving} onClick={() => setDraft(null)}>Отмена</button>
            <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
          </div>
        </form>
      )}
      {data !== null && data.requests.length === 0 && <p className="organizations-message">Обращений пока нет.</p>}
      {data !== null && data.requests.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table" aria-label="Журнал обращений">
            <thead>
              <tr>
                <th scope="col">Субъект</th>
                <th scope="col">Поступило</th>
                <th scope="col">Вид и канал</th>
                <th scope="col">Срок ответа</th>
                <th scope="col">Исполнитель</th>
                <th scope="col">Статус</th>
                <th scope="col">Исполнение</th>
                <th scope="col"><span className="admin-visually-hidden">Действия</span></th>
              </tr>
            </thead>
            <tbody>
              {data.requests.map((request) => (
                <tr key={request.id}>
                  <td className="admin-table__cell-main"><span className="admin-table__main">{request.subjectName}</span></td>
                  <td data-label="Поступило">{formatCalendarDate(request.receivedOn)}</td>
                  <td data-label="Вид и канал">{requestKindLabels[request.kind]}; {request.channel}</td>
                  <td data-label="Срок ответа"><span className={dueClass(request)}>{dueText(request, formatCalendarDate)}</span></td>
                  <td data-label="Исполнитель">{request.assigneeName ?? '—'}</td>
                  <td data-label="Статус">
                    {requestStatusLabels[request.status]}
                    {request.completedOn === null ? '' : ` ${formatCalendarDate(request.completedOn)}`}
                  </td>
                  <td data-label="Исполнение">
                    {request.execution === null ? '—' : `${request.execution.label}, ${formatDateTime(request.execution.occurredAt)}`}
                  </td>
                  <td>
                    <button type="button" className="button--secondary" onClick={() => { setNotice(null); setDraft(draftOf(request)) }}>
                      Изменить
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
