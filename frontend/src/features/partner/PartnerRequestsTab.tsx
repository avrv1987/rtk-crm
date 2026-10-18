import { useCallback, useEffect, useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type Interaction } from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime, todayInMoscow } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { saveFile } from '../admin/saveFile'
import { commandFailureMessage, handledAccessError } from '../interactions/workMarks'
import {
  cancelPartnerRequest,
  clarifyPartnerRequest,
  createPartnerRequest,
  listPartnerAccess,
  listPartnerRequests,
  reviewPartnerRequest,
  type PartnerAccess,
  type PartnerRequest
} from './partnerApi'
import { fileStateLabels, partnerRequestActions, partnerRequestStatusLabels } from './partnerRequestModel'

type PartnerRequestsTabProps = {
  interaction: Interaction
  organizationId: string
  canManage: boolean
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; requests: PartnerRequest[]; coordinators: PartnerAccess[] }
  | { kind: 'failed'; error: unknown }

type Draft = { contactId: string; action: string; expectedResult: string; dueOn: string; materialIds: string[] }

const emptyDraft: Draft = { contactId: '', action: '', expectedResult: '', dueOn: '', materialIds: [] }

export const PartnerRequestsTab = ({ interaction, organizationId, canManage, onSessionExpired, onProfileUnavailable }: PartnerRequestsTabProps) => {
  const [state, setState] = useState<LoadState>({ kind: 'loading' })
  const [creating, setCreating] = useState(false)
  const [draft, setDraft] = useState<Draft>(emptyDraft)
  const [createKey, setCreateKey] = useState(createIdempotencyKey)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [notes, setNotes] = useState<Record<string, string>>({})

  const load = useCallback(async () => {
    try {
      const [requests, accesses] = await Promise.all([
        listPartnerRequests(interaction.id),
        canManage ? listPartnerAccess(organizationId) : Promise.resolve([] as PartnerAccess[])
      ])
      setState({ kind: 'ready', requests, coordinators: accesses.filter((access) => access.active) })
    } catch (exception) {
      if (!handledAccessError(exception, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error: exception })
      }
    }
  }, [canManage, interaction.id, onProfileUnavailable, onSessionExpired, organizationId])

  useEffect(() => {
    void load()
  }, [load])

  const run = async (id: string, action: () => Promise<unknown>) => {
    setBusy(id)
    setError(null)
    try {
      await action()
      await load()
      return true
    } catch (exception) {
      if (!handledAccessError(exception, onSessionExpired, onProfileUnavailable)) {
        setError(exception)
      }
      return false
    } finally {
      setBusy(null)
    }
  }

  const create = async () => {
    const done = await run('create', () => createPartnerRequest(interaction.id, {
      contactId: draft.contactId,
      action: draft.action,
      expectedResult: draft.expectedResult,
      dueOn: draft.dueOn === '' ? null : draft.dueOn,
      materialIds: draft.materialIds
    }, createKey))
    if (done) {
      setDraft(emptyDraft)
      setCreateKey(createIdempotencyKey())
      setCreating(false)
    }
  }

  const note = (id: string) => notes[id] ?? ''
  const published = interaction.attachments.filter((attachment) => attachment.partnerVisible && attachment.status !== 'REJECTED')

  if (state.kind === 'loading') {
    return <p role="status">Загружаем запросы вузу…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="interaction-command-error" role="alert">
        <p>Не удалось загрузить запросы вузу.</p>
        {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }

  return (
    <section className="partner-requests" aria-labelledby="partner-requests-title">
      <div className="interaction-events__header">
        <h5 id="partner-requests-title" className="visually-hidden">Запросы вузу</h5>
        <p className="interaction-field-hint">
          Запрос появляется у координатора вуза в кабинете «Сотрудничество с ИТ Школой РТК». Его ответ вы принимаете или
          возвращаете с причиной; этап взаимодействия при этом не меняется.
        </p>
        {canManage && !creating && (
          <button type="button" className="button--secondary" onClick={() => setCreating(true)}>Запросить у вуза</button>
        )}
      </div>

      {creating && (
        <form className="partner-request__form" onSubmit={(event) => { event.preventDefault(); void create() }}>
          {state.coordinators.length === 0 ? (
            <p className="notice" role="status">У контактов вуза нет доступа в кабинет. Откройте доступ на вкладке «Контакты».</p>
          ) : (
            <label>
              Координатор вуза
              <select value={draft.contactId} required onChange={(event) => setDraft({ ...draft, contactId: event.target.value })}>
                <option value="">Выберите</option>
                {state.coordinators.map((access) => <option key={access.contactId} value={access.contactId}>{access.contactName}</option>)}
              </select>
            </label>
          )}
          <label>
            Что нужно сделать вузу
            <input value={draft.action} maxLength={300} required onChange={(event) => setDraft({ ...draft, action: event.target.value })} />
          </label>
          <label>
            Ожидаемый результат
            <textarea value={draft.expectedResult} maxLength={1000} rows={2} required
              onChange={(event) => setDraft({ ...draft, expectedResult: event.target.value })} />
          </label>
          <label>
            Срок ответа
            <input type="date" value={draft.dueOn} min={todayInMoscow()} onChange={(event) => setDraft({ ...draft, dueOn: event.target.value })} />
          </label>
          {published.length > 0 && (
            <fieldset>
              <legend>Материалы (открытые вузу документы)</legend>
              {published.map((attachment) => (
                <label key={attachment.id} className="checkbox-field">
                  <input
                    type="checkbox"
                    checked={draft.materialIds.includes(attachment.id)}
                    onChange={(event) => setDraft({
                      ...draft,
                      materialIds: event.target.checked
                        ? [...draft.materialIds, attachment.id]
                        : draft.materialIds.filter((id) => id !== attachment.id)
                    })}
                  />
                  {attachment.originalName}, версия {attachment.revision}
                </label>
              ))}
            </fieldset>
          )}
          <div className="partner-request__actions">
            <button type="submit" disabled={busy === 'create' || state.coordinators.length === 0}>
              {busy === 'create' ? 'Отправляем…' : 'Отправить запрос'}
            </button>
            <button type="button" className="button--secondary" onClick={() => setCreating(false)}>Отмена</button>
          </div>
        </form>
      )}

      {error !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{commandFailureMessage(error)}</p>
          {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
        </div>
      )}

      {state.requests.length === 0 && !creating && <p>Запросов вузу пока нет.</p>}
      <ul className="partner-cabinet__list">
        {state.requests.map((request) => {
          const actions = partnerRequestActions(request.status)
          return (
            <li key={request.id} className="partner-card partner-request">
              <div className="partner-card__header">
                <strong>{request.action}</strong>
                <span className="status status--planned">{partnerRequestStatusLabels[request.status]}</span>
              </div>
              <p className="partner-card__meta">
                Кому: {request.contactName}{request.dueOn !== null && <> · срок до {formatCalendarDate(request.dueOn)}</>}
                {' '}· создан {formatMoscowDateTime(request.createdAt)}{request.createdByName !== null && <>, {request.createdByName}</>}
              </p>
              <p>Ожидаемый результат: {request.expectedResult}</p>
              {request.returnReason !== null && request.status === 'RETURNED' && <p>Причина возврата: {request.returnReason}</p>}
              {request.cancelReason !== null && request.status === 'CANCELLED' && <p>Причина отмены: {request.cancelReason}</p>}
              {request.materials.length > 0 && (
                <ul>
                  {request.materials.map((material) => (
                    <li key={material.id}>
                      {material.name}, версия {material.revision}
                      {' — '}{material.receivedAt === null ? 'получение не подтверждено' : `получено ${formatMoscowDateTime(material.receivedAt)}`}
                    </li>
                  ))}
                </ul>
              )}
              {request.responses.map((response) => (
                <div key={response.id} className="partner-request__response">
                  <p className="partner-card__meta">
                    {response.kind === 'QUESTION' ? 'Вопрос вуза' : 'Ответ вуза'} · квитанция № {response.receiptNumber} ·{' '}
                    {formatMoscowDateTime(response.submittedAt)}{response.submittedByName !== null && <>, {response.submittedByName}</>}
                  </p>
                  {response.message !== null && <p className="partner-request__message">{response.message}</p>}
                  {response.fileName !== null && response.attachmentId !== null && (
                    <p>
                      Файл «{response.fileName}»{response.fileState !== null && <>, {fileStateLabels[response.fileState]}</>}
                      {response.fileState === 'CLEAN' && (
                        <>
                          {' '}
                          <button type="button" className="button--secondary" disabled={busy === response.id}
                            onClick={() => void run(response.id, async () => saveFile(await apiClient.downloadAttachment(response.attachmentId!), response.fileName!))}>
                            Скачать
                          </button>
                        </>
                      )}
                    </p>
                  )}
                  {response.decision !== null && (
                    <p>{response.decision === 'ACCEPTED' ? 'Принято' : `Возвращено: ${response.decisionReason ?? ''}`}</p>
                  )}
                </div>
              ))}
              {canManage && (actions.review || actions.clarify || actions.cancel) && (
                <div className="partner-request__form">
                  {(actions.review || actions.clarify) && (
                    <label>
                      {actions.clarify ? 'Уточнение для вуза' : 'Причина возврата (для «Вернуть»)'}
                      <textarea rows={2} maxLength={actions.clarify ? 2000 : 1000} value={note(request.id)}
                        onChange={(event) => setNotes({ ...notes, [request.id]: event.target.value })} />
                    </label>
                  )}
                  <div className="partner-request__actions">
                    {actions.review && (
                      <>
                        <button type="button" disabled={busy === request.id}
                          onClick={() => void run(request.id, () => reviewPartnerRequest(request.id,
                            { version: request.version, decision: 'ACCEPTED', reason: null }, createIdempotencyKey()))}>
                          Принять ответ
                        </button>
                        <button type="button" className="button--secondary" disabled={busy === request.id || note(request.id).trim() === ''}
                          onClick={() => void run(request.id, () => reviewPartnerRequest(request.id,
                            { version: request.version, decision: 'RETURNED', reason: note(request.id) }, createIdempotencyKey()))}>
                          Вернуть на доработку
                        </button>
                      </>
                    )}
                    {actions.clarify && (
                      <button type="button" disabled={busy === request.id || note(request.id).trim() === ''}
                        onClick={() => void run(request.id, () => clarifyPartnerRequest(request.id,
                          { version: request.version, text: note(request.id) }, createIdempotencyKey()))}>
                        Отправить уточнение
                      </button>
                    )}
                    {actions.cancel && (
                      <button type="button" className="button--secondary" disabled={busy === request.id}
                        onClick={() => void run(request.id, () => cancelPartnerRequest(request.id,
                          { version: request.version, reason: null }, createIdempotencyKey()))}>
                        Отменить запрос
                      </button>
                    )}
                  </div>
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
