import type { ReactNode } from 'react'
import { formatMoscowDateTime } from '../shared/format/datetime'
import { cardActions, errorText, type MaxApi, type MaxAppCard, type Problem } from './maxApi'
import { dueChip, markChips, moscowShort } from './model'
import type { Bridge } from './startup'
import { Banner, Chips, Icon, OpenCrm } from './ui'
import { useLoad } from './useLoad'

/** Карточка: «Следующий шаг» сверху, контакты с кнопками 44 px, действия закреплены внизу над безопасной зоной. */
export const CardScreen = ({ api, bridge, id, refreshKey, back, onFatal, onForm }: {
  api: MaxApi
  bridge: Bridge | undefined
  id: string
  refreshKey: number
  back: ReactNode
  onFatal: (problem: Problem) => void
  onForm: (screen: 'step' | 'comment', id: string, card: MaxAppCard) => void
}) => {
  const card = useLoad(`${id}:${refreshKey}`, (signal) => api.card(id, signal), onFatal)
  if (card.status !== 'ready') {
    return (
      <section className="mx-screen" aria-busy={card.status === 'loading'}>
        <div className="mx-content">
          {back}
          {card.status === 'loading'
            ? <><span className="mx-sk mx-sk--w50" /><span className="mx-sk mx-sk--title" /><div className="mx-block"><span className="mx-sk" /><span className="mx-sk mx-sk--w60" /></div></>
            : <Banner tone="error" action="Повторить" onAction={card.reload}>Не удалось загрузить карточку. {errorText(card.error)}</Banner>}
        </div>
      </section>
    )
  }
  const { interaction, organization } = card.data
  const actions = cardActions(card.data)
  const due = interaction.nextActionAt === null ? null : dueChip(interaction.nextActionAt, Date.now())
  const overdue = due?.tone === 'danger'
  return (
    <section className="mx-screen" aria-labelledby="mx-card-title">
      <div className="mx-content">
        {back}
        <p className="mx-sub mx-sub--tight">{organization.name}</p>
        <h1 id="mx-card-title" className="mx-title">{interaction.title}</h1>
        <p className="mx-chips mx-chips--card"><Chips chips={[{ tone: 'neutral', label: interaction.currentStageName }, ...markChips(interaction.marks)]} /></p>
        <div className={`mx-block mx-next${overdue ? ' mx-next--overdue' : ''}`}>
          <span className="mx-next__label">Следующий шаг</span>
          <span className="mx-next__text">{interaction.nextAction ?? 'Не задан'}</span>
          <span className="mx-chips">
            <Chips chips={[due === null
              ? { tone: 'warning', label: 'Срок не задан' }
              : { ...due, label: overdue ? `${due.label} · ${moscowShort(interaction.nextActionAt!)}` : due.label }]} />
          </span>
        </div>
        <h2 className="mx-section">Контакты</h2>
        {card.data.contacts.length === 0
          ? <p className="mx-sub">Контактов в карточке нет. Добавить можно в CRM.</p>
          : (
            <ul className="mx-block mx-plain">
              {card.data.contacts.map((contact) => (
                <li key={contact.id} className="mx-contact">
                  <span className="mx-contact__who">
                    <b>{contact.name}</b>
                    {contact.position && <span>{contact.position}</span>}
                  </span>
                  {contact.phone && (
                    <a className="mx-icon-button" href={`tel:${contact.phone.replace(/[^\d+]/g, '')}`} aria-label={`Позвонить: ${contact.name}`}>
                      <Icon name="phone" />
                    </a>
                  )}
                  {contact.email && (
                    <a className="mx-icon-button" href={`mailto:${contact.email}`} aria-label={`Написать письмо: ${contact.name}`}>
                      <Icon name="mail" />
                    </a>
                  )}
                </li>
              ))}
            </ul>
          )}
        {card.data.agreements.length > 0 && (
          <>
            <h2 className="mx-section">Договоры</h2>
            <ul className="mx-block mx-plain">
              {card.data.agreements.map((agreement) => (
                <li key={agreement.id}>
                  № {agreement.number} — <b>{agreement.statusTitle}</b>
                  {agreement.validUntil ? `, до ${agreement.validUntil.split('-').reverse().join('.')}` : ''}
                </li>
              ))}
            </ul>
          </>
        )}
        {card.data.events.length > 0 && (
          <>
            <h2 className="mx-section">Последние события</h2>
            <ul className="mx-block mx-plain">
              {card.data.events.map((event) => (
                <li key={event.id}>
                  <span className="mx-when">{formatMoscowDateTime(event.occurredAt)} · {event.actorDisplayName}</span>
                  {event.comment && <p className="mx-event">{event.comment}</p>}
                </li>
              ))}
            </ul>
          </>
        )}
        {!actions.comment && <p className="mx-sub mx-gap">Работа завершена. Вернуть её в работу можно в CRM.</p>}
        <OpenCrm bridge={bridge} hash={`#/organizations/${organization.id}/${interaction.id}`}>Открыть карточку в CRM ↗</OpenCrm>
      </div>
      {actions.comment && (
        <div className="mx-bottom">
          {actions.step
            ? (
              <>
                <button type="button" className="mx-button mx-button--primary" onClick={() => onForm('step', id, card.data)}>Шаг выполнен</button>
                <button type="button" className="mx-button mx-button--secondary" onClick={() => onForm('comment', id, card.data)}>Комментарий</button>
              </>
            )
            : <button type="button" className="mx-button mx-button--primary" onClick={() => onForm('comment', id, card.data)}>Задать шаг</button>}
        </div>
      )}
    </section>
  )
}
