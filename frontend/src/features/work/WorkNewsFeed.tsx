import { useState } from 'react'
import { apiClient, type WorkNews } from '../../shared/api/client'
import { type AccessHandlers, daysLabel, formatDateTime, handledAccessError } from './workShared'
import { newsKindLabels, withViewed } from './workNews'

const shownLimit = 5

// «Новое по моим вузам»: заявки с сайта и данные LMS из истории взаимодействий. «Просмотрено» — личная отметка
// сотрудника, просмотренные уходят в конец списка и не считаются на плитке.
export const WorkNewsFeed = ({
  news: current,
  onViewed,
  onSessionExpired,
  onProfileUnavailable
}: AccessHandlers & { news: WorkNews; onViewed: (news: WorkNews) => void }) => {
  const [expanded, setExpanded] = useState(false)
  const [failed, setFailed] = useState(false)

  const markViewed = async (eventId: string) => {
    setFailed(false)
    try {
      await apiClient.markWorkNewsViewed(eventId)
      onViewed(withViewed(current, eventId))
    } catch (error) {
      if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setFailed(true)
      }
    }
  }

  if (current.items.length === 0) {
    return null
  }
  const shown = expanded ? current.items : current.items.slice(0, shownLimit)
  return (
    <section className="desk-feed__group" aria-labelledby="desk-feed-news">
      <h3 id="desk-feed-news">
        Новое по моим вузам за {daysLabel(current.days)} <span className="desk-feed__count">{current.unviewed}</span>
      </h3>
      {failed && <p className="work-control__message" role="alert">Не удалось отметить просмотренным. Повторите попытку.</p>}
      <ul className="desk-feed__list">
        {shown.map((item) => (
          <li key={item.eventId} className={`desk-feed__item${item.viewed ? ' desk-feed__item--viewed' : ''}`}>
            <div className="desk-feed__step">
              <strong><a href={`#/organizations/${item.organizationId}/${item.interactionId}`}>{item.summary}</a></strong>
              <span>
                {newsKindLabels[item.kind]} · <a href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a> · {item.interactionTitle}
              </span>
            </div>
            <span className="status status--planned">{formatDateTime(item.occurredAt)}</span>
            {item.viewed
              ? <span className="desk-feed__viewed">Просмотрено</span>
              : <button type="button" className="button--secondary desk-feed__action" onClick={() => void markViewed(item.eventId)}>Просмотрено</button>}
          </li>
        ))}
      </ul>
      {current.items.length > shownLimit && (
        <p className="desk-feed__more">
          <button type="button" className="button--secondary" onClick={() => setExpanded((value) => !value)}>
            {expanded ? 'Свернуть' : `Показать ещё ${current.items.length - shownLimit}`}
          </button>
        </p>
      )}
    </section>
  )
}
