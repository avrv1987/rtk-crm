import { useState } from 'react'
import type { Attachment, InteractionEvent } from '../../shared/api/client'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { Segmented } from '../../shared/ui/Segmented'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { formatMoscowDateTime } from './moscowTime'
import { historyFilters, historyMatches, historyPageSize, newestFirst, type HistoryFilter } from './workCardModel'

const eventLabel: Record<InteractionEvent['type'], string> = {
  CREATED: 'Взаимодействие создано',
  TRANSITIONED: 'Этап изменён',
  COMMENTED: 'Добавлен комментарий',
  STAGES_EDITED: 'Изменены этапы карточки',
  PLAN_UPDATED: 'Изменён план',
  DETAILS_UPDATED: 'Изменены данные взаимодействия',
  STATUS_CHANGED: 'Изменён статус взаимодействия',
  AGREEMENT_UPDATED: 'Изменены лицензия и передача',
  ATTACHMENT_DELETED: 'Удалён документ',
  STAGE_COMPLETED: 'Этап отмечен выполненным',
  STAGE_COMPLETION_CLEARED: 'Снята отметка выполнения этапа'
}

const eventStageDescription = (event: InteractionEvent) => {
  if (event.type === 'TRANSITIONED') {
    return `${event.fromStageNameSnapshot ?? 'предыдущий этап'} → ${event.toStageNameSnapshot ?? 'следующий этап'}`
  }
  return `Этап: ${event.stageNameSnapshot}`
}

type WorkHistoryTabProps = {
  events: InteractionEvent[] | undefined
  loading: boolean
  failedRequestId: string | null | undefined
  attachmentsByEvent: Map<InteractionEvent['id'], Attachment[]>
  onRefresh: () => void
}

export const WorkHistoryTab = ({ events, loading, failedRequestId, attachmentsByEvent, onRefresh }: WorkHistoryTabProps) => {
  const [filter, setFilter] = useState<HistoryFilter>('ALL')
  const [shown, setShown] = useState(historyPageSize)
  const matching = events === undefined ? [] : newestFirst(events)
    .filter((event) => historyMatches(event.type, (attachmentsByEvent.get(event.id)?.length ?? 0) > 0, filter))

  return (
    <section className="interaction-events" aria-labelledby="interaction-events-title">
      <div className="interaction-events__header">
        <h5 id="interaction-events-title" className="visually-hidden">История</h5>
        <Segmented
          label="Отбор событий истории"
          options={historyFilters}
          value={filter}
          onChange={(value) => {
            setFilter(value)
            setShown(historyPageSize)
          }}
        />
        <ActionMenu label="Действия с историей" items={[{ label: 'Обновить', onSelect: onRefresh }]} />
      </div>
      {loading && <p className="organizations-message" role="status">Загружаем историю…</p>}
      {failedRequestId !== undefined && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить историю.</p>
          <SupportDetails requestId={failedRequestId ?? undefined} />
          <button type="button" onClick={onRefresh}>Повторить</button>
        </div>
      )}
      {events !== undefined && (
        matching.length === 0 ? (
          <p className="organizations-message">{events.length === 0 ? 'История пока пуста.' : 'Событий этого вида нет.'}</p>
        ) : (
          <ol className="interaction-events__list">
            {matching.slice(0, shown).map((item) => {
              const eventAttachments = attachmentsByEvent.get(item.id) ?? []
              return (
                <li key={item.id}>
                  <div>
                    <strong>{eventLabel[item.type]}</strong>
                    <time dateTime={item.occurredAt}>{formatMoscowDateTime(item.occurredAt)}</time>
                  </div>
                  <p>{eventStageDescription(item)}</p>
                  {item.comment && <p className="interaction-events__comment">{item.comment}</p>}
                  {item.nextStep && (
                    <p className="interaction-events__next-step">
                      Следующий шаг: {item.nextStep.nextAction ?? 'не задан'}; срок: {item.nextStep.nextActionAt === null ? 'не задан' : formatMoscowDateTime(item.nextStep.nextActionAt)}
                    </p>
                  )}
                  {eventAttachments.length > 0 && (
                    <p className="interaction-events__attachments">
                      Документы: {eventAttachments.map((attachment) => attachment.originalName).join(', ')}
                    </p>
                  )}
                  <p className="interaction-events__meta">Автор: {item.actorDisplayName}</p>
                </li>
              )
            })}
          </ol>
        )
      )}
      {matching.length > shown && (
        <button type="button" className="button--secondary interactions-more" onClick={() => setShown((value) => value + historyPageSize)}>
          Показать ещё (показано {shown} из {matching.length})
        </button>
      )}
    </section>
  )
}
