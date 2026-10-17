import { useCallback, useEffect, useState } from 'react'
import {
  apiClient,
  type CrmEventList,
  type CrmEventStatus,
  type CrmPreview
} from '../../shared/api/client'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import {
  crmEventKindLabels,
  crmEventStatusLabels,
  crmPreviewOutcomeLabels,
  crmRecordTypeLabels,
  previewNeedsAttention,
  queueSummary,
  retryAvailable,
  retryHint
} from './crmExchange'
import { accessHandled, errorText, formatDateTime, requestIdOf } from './sourceFormat'
import './crmExchange.css'
import './sourcePage.css'

type CrmExchangePanelProps = {
  configured: boolean
  reloadToken: number
  onNotice: (message: string) => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type QueueState =
  | { kind: 'loading' }
  | { kind: 'ready'; list: CrmEventList }
  | { kind: 'failed'; error: unknown }

type PreviewState =
  | { kind: 'idle' }
  | { kind: 'running' }
  | { kind: 'ready'; preview: CrmPreview }
  | { kind: 'failed'; error: unknown }

type FilterValue = CrmEventStatus | ''

const pageSize = 20

export const CrmExchangePanel = ({ configured, reloadToken, onNotice, onSessionExpired, onProfileUnavailable }: CrmExchangePanelProps) => {
  const [queue, setQueue] = useState<QueueState>({ kind: 'loading' })
  const [preview, setPreview] = useState<PreviewState>({ kind: 'idle' })
  const [filter, setFilter] = useState<FilterValue>('')
  const [retrying, setRetrying] = useState(false)
  const [shown, setShown] = useState(pageSize)

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  const loadQueue = useCallback(async () => {
    try {
      setQueue({ kind: 'ready', list: await apiClient.listCrmExchangeEvents(filter === '' ? undefined : filter) })
    } catch (error) {
      if (!handled(error)) {
        setQueue({ kind: 'failed', error })
      }
    }
  }, [filter, handled])

  useEffect(() => {
    void loadQueue()
  }, [loadQueue, reloadToken])

  const runPreview = async () => {
    setPreview({ kind: 'running' })
    try {
      setPreview({ kind: 'ready', preview: await apiClient.previewCrmExchange() })
    } catch (error) {
      if (!handled(error)) {
        setPreview({ kind: 'failed', error })
      }
    }
  }

  const retry = async () => {
    setRetrying(true)
    try {
      const result = await apiClient.retryCrmExchangeEvents()
      onNotice(`Возвращено в очередь событий: ${result.requeued}. Они уйдут при ближайшем запуске обмена.`)
      await loadQueue()
    } catch (error) {
      if (!handled(error)) {
        setQueue({ kind: 'failed', error })
      }
    }
    setRetrying(false)
  }

  return (
    <div className="src-section" aria-label="Обмен с CRM заказчика" role="group">
      <details>
        <summary>Предпросмотр загрузки</summary>
        <div className="src-section">
          <div className="src-actions">
            <button type="button" className="button--secondary" onClick={() => void runPreview()} disabled={!configured || preview.kind === 'running'}>
              {preview.kind === 'running' ? 'Запрашиваем…' : 'Показать, что придёт из CRM'}
            </button>
            <HintButton label="Что делает предпросмотр">
              Предпросмотр запрашивает организации и контакты у CRM заказчика и показывает, что с ними произойдёт при запуске. Данные не меняются.
            </HintButton>
          </div>
          {!configured && <p className="src-muted">Сначала задайте адрес CRM заказчика в «Настройках подключения».</p>}
          {preview.kind === 'failed' && (
            <div role="alert">
              <p className="src-error">{errorText(preview.error, 'Не удалось получить данные из CRM заказчика.')}</p>
              <SupportDetails requestId={requestIdOf(preview.error)} />
            </div>
          )}
          {preview.kind === 'ready' && (
            <div className="src-section" role="status">
              <p>
                Получено организаций: {preview.preview.organizations}, контактов: {preview.preview.contacts}
                {preview.preview.updatedSince ? `; изменения с ${formatDateTime(preview.preview.updatedSince)}` : '; первая загрузка'}.
              </p>
              {preview.preview.items.length === 0 ? (
                <p>Новых изменений в CRM заказчика нет.</p>
              ) : (
                <>
                  <ul className="crm-exchange__counts">
                    {preview.preview.counts.map((item) => (
                      <li key={item.outcome} className={previewNeedsAttention(item.outcome) ? 'crm-exchange__count--attention' : undefined}>
                        {crmPreviewOutcomeLabels[item.outcome]}: {item.count}
                      </li>
                    ))}
                  </ul>
                  <table className="src-table" aria-label="Предпросмотр записей CRM заказчика">
                    <thead>
                      <tr>
                        <th scope="col">Запись</th>
                        <th scope="col">Что будет</th>
                      </tr>
                    </thead>
                    <tbody>
                      {preview.preview.items.map((item, index) => (
                        <tr key={`${item.recordType ?? ''}:${item.externalId ?? index}`}>
                          <td className="src-cell__main">
                            {item.name ?? 'без названия'}
                            <span className="src-cell__sub">
                              {item.recordType ? crmRecordTypeLabels[item.recordType] ?? item.recordType : 'Запись'}
                              {item.externalId ? `, ID ${item.externalId}` : ''}
                            </span>
                          </td>
                          <td data-label="Что будет">
                            <span className={previewNeedsAttention(item.outcome) ? 'src-error' : undefined}>
                              {crmPreviewOutcomeLabels[item.outcome]}
                            </span>
                            {item.note && <span className="src-cell__sub">{item.note}</span>}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {preview.preview.truncated && <p className="src-muted">Показаны первые 100 записей, сначала требующие внимания.</p>}
                </>
              )}
            </div>
          )}
        </div>
      </details>

      <div className="src-section">
        <div className="src-section__head">
          <h4>События для CRM заказчика</h4>
          <HintButton label="Что за события">
            Смена этапа, следующий шаг и срок, ответственный менеджер и смена статуса договора с вузом. Данных обучающихся в событиях нет.
            При ошибке отправка повторяется с паузой, после пяти неудач событие останавливается с ошибкой.
          </HintButton>
        </div>
        {queue.kind === 'loading' && <p role="status">Загружаем очередь событий…</p>}
        {queue.kind === 'failed' && (
          <div role="alert">
            <p className="src-error">{errorText(queue.error, 'Не удалось загрузить очередь событий.')}</p>
            <SupportDetails requestId={requestIdOf(queue.error)} />
          </div>
        )}
        {queue.kind === 'ready' && (
          <>
            <p className={queue.list.counts.error > 0 ? 'src-error' : undefined}>{queueSummary(queue.list.counts)}</p>
            <details>
              <summary>Показать события</summary>
              <div className="src-section">
                <div className="src-toolbar">
                  <label>
                    Показать{' '}
                    <select
                      value={filter}
                      onChange={(event) => {
                        setFilter(event.target.value as FilterValue)
                        setShown(pageSize)
                      }}
                    >
                      <option value="">Все события</option>
                      <option value="QUEUED">В очереди</option>
                      <option value="SENT">Отправленные</option>
                      <option value="ERROR">С ошибкой</option>
                    </select>
                  </label>
                  <button type="button" className="button--secondary" onClick={() => void loadQueue()}>Обновить</button>
                  {retryAvailable(queue.list.counts) && (
                    <>
                      <button type="button" className="button--secondary" onClick={() => void retry()} disabled={retrying}>
                        {retrying ? 'Возвращаем…' : 'Вернуть ошибки в очередь'}
                      </button>
                      <HintButton label="Что делает «Вернуть ошибки в очередь»">{retryHint(queue.list.counts)}</HintButton>
                    </>
                  )}
                </div>
                {queue.list.items.length === 0 ? (
                  <p>Событий нет. Они появятся после первого запуска обмена.</p>
                ) : (
                  <>
                    <table className="src-table" aria-label="Очередь событий для CRM заказчика">
                      <thead>
                        <tr>
                          <th scope="col">Когда (МСК)</th>
                          <th scope="col">Что произошло</th>
                          <th scope="col">Вуз и взаимодействие</th>
                          <th scope="col">Состояние</th>
                        </tr>
                      </thead>
                      <tbody>
                        {queue.list.items.slice(0, shown).map((event) => (
                          <tr key={event.id}>
                            <td data-label="Когда (МСК)">{formatDateTime(event.occurredAt)}</td>
                            <td data-label="Что произошло">
                              {crmEventKindLabels[event.kind]}
                              <span className="src-cell__sub">{event.description}</span>
                            </td>
                            <td data-label="Вуз и взаимодействие">
                              {event.organizationName ?? 'вуз не указан'}
                              {event.workTitle && <span className="src-cell__sub">{event.workTitle}</span>}
                            </td>
                            <td data-label="Состояние">
                              <span className={event.status === 'ERROR' ? 'src-error' : undefined}>
                                {crmEventStatusLabels[event.status]}
                                {event.status === 'SENT' && event.sentAt ? `, ${formatDateTime(event.sentAt)}` : ''}
                              </span>
                              {event.attempts > 0 && event.status !== 'SENT' && (
                                <span className="src-cell__sub">
                                  Попыток: {event.attempts}
                                  {event.status === 'QUEUED' && event.nextAttemptAt ? `; следующая — ${formatDateTime(event.nextAttemptAt)}` : ''}
                                </span>
                              )}
                              {event.lastError && <span className="src-cell__sub src-error">{event.lastError}</span>}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {queue.list.items.length > shown && (
                      <button type="button" className="button--secondary src-more" onClick={() => setShown(shown + pageSize)}>
                        Показать ещё
                      </button>
                    )}
                  </>
                )}
              </div>
            </details>
          </>
        )}
      </div>
    </div>
  )
}
