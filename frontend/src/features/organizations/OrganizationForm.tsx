import { type FormEvent, useEffect, useId, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Organization,
  type OrganizationDetails,
  type OrganizationDuplicate,
  type OrganizationStatus
} from '../../shared/api/client'

export type OrganizationType = Organization['type']

export const organizationTypeLabels: Record<OrganizationType, string> = {
  UNIVERSITY: 'Университет',
  COLLEGE: 'Колледж (СПО)',
  SCHOOL: 'Школа'
}

export const organizationStatusLabels: Record<OrganizationStatus, string> = {
  ACTIVE: 'Действует',
  PENDING: 'Ожидает подтверждения',
  ARCHIVED: 'В архиве'
}

export type OrganizationFormValues = {
  name: string
  type: OrganizationType
  city: string
  website: string
  inn: string
  teamId: string
}

type TeamOption = {
  id: string
  name: string
}

type OrganizationFormProps = {
  title: string
  submitLabel: string
  initial?: Partial<OrganizationFormValues>
  teams?: TeamOption[]
  exceptId?: string
  linkDuplicates: boolean
  hint?: string
  onSubmit: (payload: OrganizationDetails, idempotencyKey: string) => Promise<void>
  onCancel: () => void
  onSessionError: (error: unknown) => boolean
}

const duplicateDelayMilliseconds = 400

const emptyValues: OrganizationFormValues = { name: '', type: 'UNIVERSITY', city: '', website: '', inn: '', teamId: '' }

const nullable = (value: string) => (value.trim().length === 0 ? null : value.trim())

export const organizationErrorMessage = (error: unknown) => {
  if (!(error instanceof ApiError)) {
    return 'Не удалось связаться с сервисом. Введённые данные сохранены; повторите попытку.'
  }
  if (error.code === 'VERSION_CONFLICT') {
    return 'Организацию уже изменили. Обновите карточку и повторите.'
  }
  if (error.code === 'IDEMPOTENCY_CONFLICT') {
    return 'Команда уже выполнялась с другими данными. Повторите действие.'
  }
  if (error.code === 'NOT_FOUND') {
    return 'Организация не найдена или недоступна.'
  }
  const messages = Object.values(error.fieldErrors ?? {})
  if (messages.length > 0) {
    return messages.join(' ')
  }
  if (error.status === 403) {
    return 'Недостаточно прав для этого действия.'
  }
  return 'Не удалось сохранить изменения. Проверьте данные и повторите попытку.'
}

export const OrganizationForm = ({
  title,
  submitLabel,
  initial,
  teams,
  exceptId,
  linkDuplicates,
  hint,
  onSubmit,
  onCancel,
  onSessionError
}: OrganizationFormProps) => {
  const [values, setValues] = useState<OrganizationFormValues>({ ...emptyValues, ...initial })
  const [duplicates, setDuplicates] = useState<OrganizationDuplicate[]>([])
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(undefined)
  const idempotencyKey = useRef<string | null>(null)
  const duplicateRequest = useRef(0)
  const titleId = useId()
  const initialName = initial?.name ?? ''

  useEffect(() => {
    const name = values.name.trim()
    const version = ++duplicateRequest.current
    if (name.length < 2 || name === initialName.trim()) {
      setDuplicates([])
      return
    }
    const timer = window.setTimeout(() => {
      apiClient.findOrganizationDuplicates(name, exceptId)
        .then((found) => {
          if (version === duplicateRequest.current) {
            setDuplicates(found)
          }
        })
        .catch((failure: unknown) => {
          if (version === duplicateRequest.current && !onSessionError(failure)) {
            setDuplicates([])
          }
        })
    }, duplicateDelayMilliseconds)
    return () => window.clearTimeout(timer)
  }, [exceptId, initialName, onSessionError, values.name])

  const change = (field: keyof OrganizationFormValues, value: string) => {
    idempotencyKey.current = null
    setError(undefined)
    setValues((current) => ({ ...current, [field]: value }))
  }

  const exact = duplicates.find((duplicate) => duplicate.exact)
  const similar = duplicates.filter((duplicate) => !duplicate.exact)
  const teamMissing = teams !== undefined && values.teamId.length === 0
  const canSubmit = !saving && values.name.trim().length > 0 && exact === undefined && !teamMissing

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!canSubmit) {
      return
    }
    const key = idempotencyKey.current ?? (idempotencyKey.current = createIdempotencyKey())
    setSaving(true)
    setError(undefined)
    try {
      await onSubmit({
        name: values.name.trim(),
        type: values.type,
        city: nullable(values.city),
        website: nullable(values.website),
        inn: nullable(values.inn),
        teamId: teams === undefined ? null : values.teamId
      }, key)
    } catch (failure) {
      if (!onSessionError(failure)) {
        setError(failure)
        setSaving(false)
      }
    }
  }

  const duplicateName = (duplicate: OrganizationDuplicate) => (
    linkDuplicates && duplicate.id !== null
      ? <a href={`#/organizations/${duplicate.id}`}>{duplicate.name}</a>
      : <strong>{duplicate.name}</strong>
  )

  return (
    <form className="admin-profile-form organization-form" aria-labelledby={titleId} onSubmit={(event) => void submit(event)}>
      <h3 id={titleId} className="organization-form__title">{title}</h3>
      {hint !== undefined && <p className="admin-profile-form__hint">{hint}</p>}
      <label>
        Название *
        <input
          value={values.name}
          maxLength={300}
          required
          disabled={saving}
          aria-invalid={exact !== undefined}
          onChange={(event) => change('name', event.target.value)}
        />
      </label>
      <label>
        Тип организации *
        <select value={values.type} disabled={saving} onChange={(event) => change('type', event.target.value)}>
          {(Object.keys(organizationTypeLabels) as OrganizationType[]).map((type) => (
            <option key={type} value={type}>{organizationTypeLabels[type]}</option>
          ))}
        </select>
      </label>
      {teams !== undefined && (
        <label>
          Команда *
          <select value={values.teamId} required disabled={saving} onChange={(event) => change('teamId', event.target.value)}>
            <option value="">Выберите команду</option>
            {teams.map((team) => <option key={team.id} value={team.id}>{team.name}</option>)}
          </select>
        </label>
      )}
      <label>
        Город или регион
        <input value={values.city} maxLength={200} disabled={saving} onChange={(event) => change('city', event.target.value)} />
      </label>
      <label>
        Сайт
        <input
          value={values.website}
          maxLength={300}
          inputMode="url"
          placeholder="https://school1.ru"
          disabled={saving}
          onChange={(event) => change('website', event.target.value)}
        />
      </label>
      <label>
        ИНН
        <input
          value={values.inn}
          maxLength={12}
          inputMode="numeric"
          pattern="\d{10}|\d{12}"
          title="10 или 12 цифр"
          disabled={saving}
          onChange={(event) => change('inn', event.target.value)}
        />
      </label>
      {exact !== undefined && (
        <div className="interaction-command-error" role="alert">
          <p>
            Организация с таким названием уже есть: {duplicateName(exact)}
            {exact.status === 'ARCHIVED' ? ' (в архиве)' : ''}, команда «{exact.teamName}». Дубль не создаётся.
          </p>
        </div>
      )}
      {exact === undefined && similar.length > 0 && (
        <div className="notice organization-form__similar" role="status">
          <p>Похожие организации — проверьте, нет ли среди них нужной:</p>
          <ul>
            {similar.map((duplicate) => (
              <li key={`${duplicate.name}:${duplicate.teamName}`}>
                {duplicateName(duplicate)} — {organizationTypeLabels[duplicate.type]}, команда «{duplicate.teamName}»
              </li>
            ))}
          </ul>
        </div>
      )}
      {error !== undefined && (
        <div className="interaction-command-error" role="alert">
          <p>{organizationErrorMessage(error)}</p>
          {error instanceof ApiError && <p className="request-id">Request ID: {error.requestId}</p>}
        </div>
      )}
      <div className="admin-profiles__confirmation-actions">
        <button type="submit" disabled={!canSubmit}>{saving ? 'Сохраняем…' : submitLabel}</button>
        <button type="button" className="button--secondary" disabled={saving} onClick={onCancel}>Отмена</button>
      </div>
    </form>
  )
}
