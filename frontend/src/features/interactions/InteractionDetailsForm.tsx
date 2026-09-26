import { type FormEvent, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Contact,
  type Interaction,
  type InteractionPlanUpdate
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { changedDraft, conflictKeys, keepDraftValues, mergedValues, parseBasedDraft, takeCurrentValues } from './draftBase'
import { DraftConflictNotice } from './DraftConflictNotice'
import { recordOf, textOf, useFormDraft } from './formDraft'
import { useUnsavedDraft } from './unsavedDrafts'
import { commandFailureMessage, contactRoleLabels, formatDateTime, handledAccessError } from './workMarks'
import './workCard.css'

type InteractionDetailsFormProps = {
  interaction: Interaction
  contacts: Contact[] | null
  profileId: string
  onChanged: (interaction: Interaction) => void
  onRefresh: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type DetailsValues = {
  title: string
  lastContactAt: string
  contactIds: string[]
}

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

const toDateTimeLocal = (value: string | null) => {
  if (value === null) {
    return ''
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? ''
    : new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

const toIsoDateTime = (value: string) => {
  const date = new Date(value)
  return value.length === 0 || Number.isNaN(date.getTime()) ? null : date.toISOString()
}

const fieldLabels: Record<keyof DetailsValues, string> = {
  title: 'Название',
  lastContactAt: 'Дата последнего контакта',
  contactIds: 'Связанные контакты'
}

const fromInteraction = (interaction: Interaction): DetailsValues => ({
  title: interaction.title,
  lastContactAt: toDateTimeLocal(interaction.lastContactAt),
  contactIds: [...interaction.contactIds].sort()
})

const parseValues = (value: unknown): DetailsValues | null => {
  const record = recordOf(value)
  if (record === null) {
    return null
  }
  return {
    title: textOf(record.title),
    lastContactAt: textOf(record.lastContactAt),
    contactIds: Array.isArray(record.contactIds)
      ? record.contactIds.filter((id): id is string => typeof id === 'string').sort()
      : []
  }
}

const parseDraft = (value: unknown) => parseBasedDraft(value, parseValues)

const sameIds = (left: string[], right: string[]) => (
  left.length === right.length && left.every((id) => right.includes(id))
)

export const InteractionDetailsForm = ({
  interaction,
  contacts,
  profileId,
  onChanged,
  onRefresh,
  onSessionExpired,
  onProfileUnavailable
}: InteractionDetailsFormProps) => {
  const [draft, setDraft] = useFormDraft(profileId, `card:${interaction.id}:details`, parseDraft)
  const [state, setState] = useState<CommandState>({ kind: 'idle' })
  const commandKey = useRef<string | null>(null)
  const current = fromInteraction(interaction)
  const values = mergedValues(draft, current)
  const conflicts = conflictKeys(draft, current)
  const payload: InteractionPlanUpdate = { version: interaction.version }
  if (values.title.trim() !== interaction.title) {
    payload.title = values.title.trim()
  }
  if (values.lastContactAt !== current.lastContactAt) {
    payload.lastContactAt = toIsoDateTime(values.lastContactAt)
  }
  if (!sameIds(values.contactIds, current.contactIds)) {
    payload.contactIds = values.contactIds
  }
  const changed = Object.keys(payload).length > 1
  const titleMissing = values.title.trim().length === 0
  useUnsavedDraft(`card:${interaction.id}:details`, `данные работы в карточке «${interaction.title}»`, draft !== null && changed)
  const selectable = contacts?.filter((contact) => !contact.inactive || current.contactIds.includes(contact.id)) ?? []

  const shownValue = (field: keyof DetailsValues, source: DetailsValues) => {
    if (field === 'title') {
      return source.title.trim() || 'не указано'
    }
    if (field === 'lastContactAt') {
      return source.lastContactAt === '' ? 'не указана' : formatDateTime(source.lastContactAt)
    }
    return source.contactIds.length === 0
      ? 'нет'
      : source.contactIds.map((id) => contacts?.find((contact) => contact.id === id)?.name ?? 'контакт').join(', ')
  }

  const change = (next: Partial<DetailsValues>) => {
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

  const toggleContact = (id: string) => {
    change({
      contactIds: values.contactIds.includes(id)
        ? values.contactIds.filter((contactId) => contactId !== id)
        : [...values.contactIds, id].sort()
    })
  }

  const reset = () => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setDraft(null)
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!changed || conflicts.length > 0 || titleMissing) {
      return
    }
    setState({ kind: 'saving' })
    try {
      const updated = await apiClient.updateInteractionPlan(
        interaction.id,
        payload,
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      commandKey.current = null
      setDraft(null)
      setState({ kind: 'idle' })
      onChanged(updated)
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }

  return (
    <form className="interaction-plan-form work-details" onSubmit={(event) => void submit(event)}>
      <h6>Данные работы</h6>
      <p>Название, связанные контакты и дату последнего контакта можно исправить; в истории останется, что было и что стало.</p>
      {draft !== null && (
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
      )}
      <label>
        Название
        <input value={values.title} maxLength={200} required onChange={(event) => change({ title: event.target.value })} />
      </label>
      <label>
        Дата последнего контакта
        <input type="datetime-local" value={values.lastContactAt} onChange={(event) => change({ lastContactAt: event.target.value })} />
      </label>
      <fieldset className="interaction-contact-picker">
        <legend>Связанные контакты</legend>
        {contacts === null && <p>Контакты организации ещё загружаются.</p>}
        {contacts !== null && selectable.length === 0 && <p>У организации нет действующих контактов. Добавьте контакт выше.</p>}
        {selectable.length > 0 && (
          <ul>
            {selectable.map((contact) => (
              <li key={contact.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={values.contactIds.includes(contact.id)}
                    disabled={contact.inactive && !values.contactIds.includes(contact.id)}
                    onChange={() => toggleContact(contact.id)}
                  />
                  <span>{contact.name}</span>
                  {contact.role && <small>{contactRoleLabels[contact.role]}</small>}
                  {contact.inactive && <small>Не актуален: можно только убрать</small>}
                </label>
              </li>
            ))}
          </ul>
        )}
      </fieldset>
      <div className="interaction-plan-form__actions">
        <button type="submit" disabled={state.kind === 'saving' || !changed || conflicts.length > 0 || titleMissing}>
          {state.kind === 'saving' ? 'Сохраняем…' : 'Сохранить данные работы'}
        </button>
        {draft !== null && (
          <button type="button" className="button--secondary" disabled={state.kind === 'saving'} onClick={reset}>Отменить изменения</button>
        )}
      </div>
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandFailureMessage(state.error)}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
          {state.error instanceof ApiError && state.error.status === 409 && (
            <button type="button" onClick={onRefresh}>Обновить карточку</button>
          )}
        </div>
      )}
    </form>
  )
}
