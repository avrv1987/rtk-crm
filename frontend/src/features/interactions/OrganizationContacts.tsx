import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Contact,
  type ContactEvent,
  type ContactRole,
  type ContactUpdate
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { PartnerAccessControl } from '../partner/PartnerAccessControl'
import { listPartnerAccess, type PartnerAccess } from '../partner/partnerApi'
import { changedDraft, conflictKeys, keepDraftValues, mergedValues, parseBasedDraft, takeCurrentValues } from './draftBase'
import { DraftConflictNotice } from './DraftConflictNotice'
import { recordOf, textOf, useFormDraft } from './formDraft'
import { useUnsavedDraft } from './unsavedDrafts'
import { commandFailureMessage, contactRoleLabels, contactRoles, formatDateTime, handledAccessError } from './workMarks'
import './workCard.css'

type SessionHandlers = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

export type PartnerAccessRights = {
  canManage: boolean
  canOpen: boolean
}

type PartnerAccessBinding = PartnerAccessRights & {
  access: PartnerAccess | undefined
  onChanged: () => void
}

type OrganizationContactsProps = SessionHandlers & {
  organizationId: string
  contacts: Contact[]
  profileId: string
  canEdit: boolean
  partnerAccess?: PartnerAccessRights
  onChanged: () => void
}

type ContactValues = {
  name: string
  position: string
  email: string
  phone: string
  role: ContactRole | ''
  primary: boolean
  inactive: boolean
}

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

type HistoryState =
  | { kind: 'hidden' }
  | { kind: 'loading' }
  | { kind: 'ready'; events: ContactEvent[] }
  | { kind: 'failed'; error: unknown }

const fieldLabels: Record<ContactEvent['changes'][number]['field'], string> = {
  name: 'ФИО',
  position: 'Должность',
  email: 'Электронная почта',
  phone: 'Телефон',
  role: 'Роль во взаимодействии',
  primary: 'Основной контакт',
  inactive: 'Не актуален',
  confirmed: 'Контакт подтверждён'
}

const changeValue = (field: ContactEvent['changes'][number]['field'], value: string | null) => {
  if (value === null) {
    return 'не указано'
  }
  if (field === 'role') {
    return contactRoles.includes(value as ContactRole) ? contactRoleLabels[value as ContactRole] : value
  }
  if (field === 'primary' || field === 'inactive') {
    return value === 'true' ? 'да' : 'нет'
  }
  if (field === 'confirmed') {
    return formatDateTime(value)
  }
  return value
}

export const changeText = (change: ContactEvent['changes'][number]) => (
  change.field === 'confirmed'
    ? `${fieldLabels.confirmed}${change.previousValue === null ? '' : ` повторно (прежняя отметка ${changeValue('confirmed', change.previousValue)})`}`
    : `${fieldLabels[change.field]}: ${changeValue(change.field, change.previousValue)} → ${changeValue(change.field, change.value)}`
)

const fromContact = (contact: Contact): ContactValues => ({
  name: contact.name,
  position: contact.position ?? '',
  email: contact.email ?? '',
  phone: contact.phone ?? '',
  role: contact.role ?? '',
  primary: contact.primary,
  inactive: contact.inactive
})

const parseValues = (value: unknown): ContactValues | null => {
  const record = recordOf(value)
  if (record === null) {
    return null
  }
  return {
    name: textOf(record.name),
    position: textOf(record.position),
    email: textOf(record.email),
    phone: textOf(record.phone),
    role: contactRoles.find((role) => role === record.role) ?? '',
    primary: record.primary === true,
    inactive: record.inactive === true
  }
}

const parseDraft = (value: unknown) => parseBasedDraft(value, parseValues)

const shownValue = (field: keyof ContactValues, values: ContactValues) => {
  const value = values[field]
  if (typeof value === 'boolean') {
    return value ? 'да' : 'нет'
  }
  if (field === 'role') {
    return value === '' ? 'не указана' : contactRoleLabels[value as ContactRole]
  }
  return value.trim() || 'не указано'
}

const updatePayload = (contact: Contact, values: ContactValues, confirm: boolean): ContactUpdate => ({
  version: contact.version,
  name: values.name.trim(),
  position: values.position.trim() || null,
  email: values.email.trim() || null,
  phone: values.phone.trim() || null,
  role: values.role === '' ? null : values.role,
  primary: values.primary && !values.inactive,
  inactive: values.inactive,
  confirm
})

const ContactItem = ({
  organizationId,
  contact,
  profileId,
  canEdit,
  partner,
  onChanged,
  onSessionExpired,
  onProfileUnavailable
}: SessionHandlers & {
  organizationId: string
  contact: Contact
  profileId: string
  canEdit: boolean
  partner?: PartnerAccessBinding
  onChanged: () => void
}) => {
  const [draft, setDraft] = useFormDraft(profileId, `contact:${contact.id}`, parseDraft)
  const [state, setState] = useState<CommandState>({ kind: 'idle' })
  const [history, setHistory] = useState<HistoryState>({ kind: 'hidden' })
  const commandKey = useRef<string | null>(null)
  const current = fromContact(contact)
  const values = mergedValues(draft, current)
  const conflicts = conflictKeys(draft, current)
  const changed = draft !== null && JSON.stringify(values) !== JSON.stringify(current)
  useUnsavedDraft(`contact:${contact.id}`, `правка контакта «${contact.name}»`, changed)

  const loadHistory = async () => {
    setHistory({ kind: 'loading' })
    try {
      setHistory({ kind: 'ready', events: await apiClient.listOrganizationContactEvents(organizationId, contact.id) })
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setHistory({ kind: 'failed', error })
      }
    }
  }

  const send = async (payload: ContactUpdate) => {
    setState({ kind: 'saving' })
    try {
      await apiClient.updateOrganizationContact(
        organizationId,
        contact.id,
        payload,
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      commandKey.current = null
      setDraft(null)
      setState({ kind: 'idle' })
      if (history.kind !== 'hidden') {
        void loadHistory()
      }
      onChanged()
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }

  const change = (next: Partial<ContactValues>) => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setDraft(changedDraft(draft, current, next))
  }

  const resolveConflicts = (keep: boolean) => {
    if (draft !== null) {
      commandKey.current = null
      setState({ kind: 'idle' })
      setDraft(keep ? keepDraftValues(draft, current) : takeCurrentValues(draft, current))
    }
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (draft !== null && conflicts.length === 0 && values.name.trim().length > 0) {
      void send(updatePayload(contact, values, false))
    }
  }

  const confirm = () => {
    commandKey.current = null
    void send(updatePayload(contact, fromContact(contact), true))
  }

  const cancel = () => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setDraft(null)
  }

  return (
    <li className={`contact-card${contact.inactive ? ' contact-card--inactive' : ''}`}>
      <div className="contact-card__header">
        <strong>{contact.name}</strong>
        {contact.primary && <span className="status status--planned">Основной контакт</span>}
        {contact.role && <span className="status status--planned">{contactRoleLabels[contact.role]}</span>}
        {contact.inactive && <span className="status status--missing">Не актуален</span>}
        {contact.personalDataStatus === 'RESTRICTED' && (
          <span className="status status--missing" title="Обработка данных контакта ограничена по запросу субъекта">Обработка ограничена</span>
        )}
      </div>
      <dl className="contact-card__facts">
        {contact.position && <div><dt>Должность</dt><dd>{contact.position}</dd></div>}
        {contact.email && <div><dt>Почта</dt><dd>{contact.email}</dd></div>}
        {contact.phone && <div><dt>Телефон</dt><dd>{contact.phone}</dd></div>}
        <div>
          <dt>Подтверждение</dt>
          <dd>
            {contact.confirmedAt === null
              ? 'Не подтверждён'
              : `Подтверждён: ${contact.confirmedByName ?? 'сотрудник'}, ${formatDateTime(contact.confirmedAt)}`}
          </dd>
        </div>
      </dl>
      {partner !== undefined && (
        <PartnerAccessControl
          organizationId={organizationId}
          contact={contact}
          access={partner.access}
          canManage={partner.canManage}
          canOpen={partner.canOpen}
          onChanged={partner.onChanged}
          onSessionExpired={onSessionExpired}
          onProfileUnavailable={onProfileUnavailable}
        />
      )}
      <div className="interaction-plan-form__actions">
        {canEdit && contact.personalDataStatus === 'RESTRICTED' && draft === null && (
          <p className="interaction-field-hint">Правка недоступна: обработка данных контакта ограничена.</p>
        )}
        {canEdit && contact.personalDataStatus !== 'RESTRICTED' && draft === null && (
          <button type="button" className="button--secondary" onClick={() => change({})}>Изменить</button>
        )}
        {canEdit && !contact.inactive && contact.personalDataStatus !== 'RESTRICTED' && draft === null && (
          <button type="button" className="button--secondary" disabled={state.kind === 'saving'} onClick={confirm}>
            Отметить «Контакт подтверждён»
          </button>
        )}
        <button
          type="button"
          className="button--secondary"
          aria-expanded={history.kind !== 'hidden'}
          onClick={() => (history.kind === 'hidden' ? void loadHistory() : setHistory({ kind: 'hidden' }))}
        >
          {history.kind === 'hidden' ? 'История изменений' : 'Скрыть историю'}
        </button>
      </div>
      {draft !== null && (
        <form className="contact-card__form" onSubmit={submit}>
          <DraftConflictNotice
            conflicts={conflicts.map((field) => ({
              key: field,
              label: fieldLabels[field],
              current: shownValue(field, current),
              draft: shownValue(field, draft.values)
            }))}
            onKeepDraft={() => resolveConflicts(true)}
            onTakeCurrent={() => resolveConflicts(false)}
          />
          <label>
            <span>ФИО<span className="required-mark" aria-hidden="true"> *</span></span>
            <input value={values.name} maxLength={200} required onChange={(event) => change({ name: event.target.value })} />
          </label>
          <label>
            Должность
            <input value={values.position} maxLength={200} onChange={(event) => change({ position: event.target.value })} />
          </label>
          <label>
            Электронная почта
            <input type="email" value={values.email} maxLength={320} onChange={(event) => change({ email: event.target.value })} />
          </label>
          <label>
            Телефон
            <input value={values.phone} maxLength={50} onChange={(event) => change({ phone: event.target.value })} />
          </label>
          <label>
            Роль во взаимодействии
            <select
              value={values.role}
              onChange={(event) => change({ role: contactRoles.find((role) => role === event.target.value) ?? '' })}
            >
              <option value="">Не указана</option>
              {contactRoles.map((role) => <option key={role} value={role}>{contactRoleLabels[role]}</option>)}
            </select>
          </label>
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={values.primary && !values.inactive}
              disabled={values.inactive}
              onChange={(event) => change({ primary: event.target.checked })}
            />
            Основной контакт
          </label>
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={values.inactive}
              onChange={(event) => change({ inactive: event.target.checked, primary: event.target.checked ? false : values.primary })}
            />
            Не актуален (человек больше не отвечает за направление)
          </label>
          <span className="interaction-field-hint">
            Неактуальный контакт не предлагается для новых работ; в прежних работах и истории его имя сохраняется.
          </span>
          <div className="interaction-plan-form__actions">
            <button type="submit" disabled={state.kind === 'saving' || !changed || conflicts.length > 0 || values.name.trim().length === 0}>
              {state.kind === 'saving' ? 'Сохраняем…' : 'Сохранить контакт'}
            </button>
            <button type="button" className="button--secondary" disabled={state.kind === 'saving'} onClick={cancel}>Отменить</button>
          </div>
        </form>
      )}
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandFailureMessage(state.error)}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
          {state.error instanceof ApiError && state.error.status === 409 && (
            <button type="button" onClick={onChanged}>Обновить контакты</button>
          )}
        </div>
      )}
      {history.kind === 'loading' && <p role="status">Загружаем историю контакта…</p>}
      {history.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>Не удалось загрузить историю контакта.</p>
          {history.error instanceof ApiError && <SupportDetails requestId={history.error.requestId} code={history.error.code} />}
          <button type="button" onClick={() => void loadHistory()}>Повторить</button>
        </div>
      )}
      {history.kind === 'ready' && (
        history.events.length === 0 ? (
          <p className="contact-card__history-empty">Изменений пока не было. Добавлен {formatDateTime(contact.createdAt)}.</p>
        ) : (
          <ol className="contact-card__history">
            {history.events.map((event) => (
              <li key={event.id}>
                <span>{formatDateTime(event.occurredAt)}, {event.actorDisplayName}</span>
                <ul>
                  {event.changes.map((item) => <li key={item.field}>{changeText(item)}</li>)}
                </ul>
              </li>
            ))}
          </ol>
        )
      )}
    </li>
  )
}

export const OrganizationContacts = ({ contacts, partnerAccess, ...props }: OrganizationContactsProps) => {
  const [accesses, setAccesses] = useState<PartnerAccess[] | null>(null)
  const { organizationId, onSessionExpired, onProfileUnavailable } = props

  const loadAccesses = useCallback(async () => {
    try {
      setAccesses(await listPartnerAccess(organizationId))
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setAccesses(null)
      }
    }
  }, [organizationId, onSessionExpired, onProfileUnavailable])

  useEffect(() => {
    if (partnerAccess !== undefined) {
      void loadAccesses()
    }
  }, [loadAccesses, partnerAccess !== undefined])

  return contacts.length === 0 ? null : (
    <ul className="contact-list" aria-label="Контакты организации">
      {contacts.map((contact) => (
        <ContactItem
          key={contact.id}
          contact={contact}
          partner={partnerAccess === undefined || accesses === null ? undefined : {
            ...partnerAccess,
            access: accesses.find((access) => access.contactId === contact.id),
            onChanged: () => void loadAccesses()
          }}
          {...props}
        />
      ))}
    </ul>
  )
}
