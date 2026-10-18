import { useEffect, useState } from 'react'
import { apiClient, type Contact } from '../../shared/api/client'
import { type ContactMarkDraft, contactChannelLabels, contactChannels, toggleParticipant } from './contactMark'

type ContactMarkFieldsProps = {
  organizationId: string
  value: ContactMarkDraft
  onChange: (value: ContactMarkDraft) => void
}

type ContactsState = { kind: 'idle' | 'loading' | 'failed' } | { kind: 'ready'; contacts: Contact[] }

// Отметка «был контакт с вузом»: канал обязателен, участники — по желанию из действующих контактов вуза.
// Контакты загружаются, только когда галочку поставили.
export const ContactMarkFields = ({ organizationId, value, onChange }: ContactMarkFieldsProps) => {
  const [contacts, setContacts] = useState<ContactsState>({ kind: 'idle' })
  const checked = value !== null

  useEffect(() => {
    if (!checked || contacts.kind !== 'idle') {
      return
    }
    setContacts({ kind: 'loading' })
    apiClient.listOrganizationContacts(organizationId).then(
      (items) => setContacts({ kind: 'ready', contacts: items.filter((contact) => !contact.inactive && (contact.personalDataStatus ?? 'ACTIVE') === 'ACTIVE') }),
      () => setContacts({ kind: 'failed' })
    )
  }, [checked, contacts.kind, organizationId])

  return (
    <fieldset className="contact-mark">
      <legend className="visually-hidden">Контакт с вузом</legend>
      <label className="checkbox-field">
        <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked ? { channel: '', participantIds: [] } : null)} />
        Был контакт с вузом
      </label>
      {value !== null && (
        <>
          <label>
            Как прошёл контакт
            <select required value={value.channel} onChange={(event) => onChange({ ...value, channel: contactChannels.find((channel) => channel === event.target.value) ?? '' })}>
              <option value="">Выберите</option>
              {contactChannels.map((channel) => <option key={channel} value={channel}>{contactChannelLabels[channel]}</option>)}
            </select>
          </label>
          <div className="contact-mark__participants">
            <span>Участники от вуза</span>
            {contacts.kind === 'loading' && <p className="step-completion__hint">Загружаем контакты…</p>}
            {contacts.kind === 'failed' && <p className="step-completion__hint">Контакты не загрузились; контакт можно отметить без участников.</p>}
            {contacts.kind === 'ready' && contacts.contacts.length === 0 && (
              <p className="step-completion__hint">У вуза нет действующих контактов; добавьте их на вкладке «Контакты».</p>
            )}
            {contacts.kind === 'ready' && contacts.contacts.map((contact) => (
              <label key={contact.id} className="checkbox-field">
                <input
                  type="checkbox"
                  checked={value.participantIds.includes(contact.id)}
                  onChange={() => onChange(toggleParticipant(value, contact.id))}
                />
                {contact.name}{contact.position ? `, ${contact.position}` : ''}
              </label>
            ))}
          </div>
          <p className="step-completion__hint">Дата последнего контакта обновится сама. Записи сайта и LMS контактом не считаются.</p>
        </>
      )}
    </fieldset>
  )
}
