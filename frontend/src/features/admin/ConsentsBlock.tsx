import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError, apiClient, type Consent, type ConsentInput, type ConsentSettings, type Consents } from '../../shared/api/client'
import { formatCalendarDate, todayInMoscow } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'

// Где ведутся согласия: у контакта в карточке вуза (КАМ, руководитель), у слушателя в анкете (МУП); администратор
// только смотрит на экране «Персональные данные».
export type ConsentScope =
  | { kind: 'contact'; organizationId: string; contactId: string }
  | { kind: 'learner'; learnerId: string }
  | { kind: 'admin'; subjectType: 'CONTACT' | 'LEARNER'; subjectId: string }

type Props = Partial<SessionHandlers> & {
  scope: ConsentScope
  canEdit: boolean
  /** Обработчик ошибок доступа экрана, где нет обработчиков сеанса (анкета слушателя). */
  onAccessError?: (error: unknown) => boolean
}

type Draft = { givenOn: string; basis: string; document: string }

let settingsRequest: Promise<ConsentSettings> | null = null

/** Включён ли учёт согласий; запрашивается один раз за сеанс страницы, сбой — как «выключен». */
export const consentSettings = (): Promise<ConsentSettings> => {
  settingsRequest ??= apiClient.getConsentSettings().catch(() => {
    settingsRequest = null
    return { contacts: false, learners: false }
  })
  return settingsRequest
}

const api = (scope: ConsentScope) => ({
  list: (): Promise<Consents> => scope.kind === 'contact'
    ? apiClient.listContactConsents(scope.organizationId, scope.contactId)
    : scope.kind === 'learner' ? apiClient.listLearnerConsents(scope.learnerId) : apiClient.listConsents(scope.subjectType, scope.subjectId),
  create: (payload: ConsentInput) => scope.kind === 'contact'
    ? apiClient.createContactConsent(scope.organizationId, scope.contactId, payload)
    : scope.kind === 'learner' ? apiClient.createLearnerConsent(scope.learnerId, payload) : Promise.reject(new Error('read only')),
  update: (id: string, payload: ConsentInput) => scope.kind === 'contact'
    ? apiClient.updateContactConsent(scope.organizationId, scope.contactId, id, payload)
    : scope.kind === 'learner' ? apiClient.updateLearnerConsent(scope.learnerId, id, payload) : Promise.reject(new Error('read only'))
})

export const ConsentsBlock = ({ scope, canEdit, onSessionExpired, onProfileUnavailable, onAccessError }: Props) => {
  const [consents, setConsents] = useState<Consents | null>(null)
  const [draft, setDraft] = useState<Draft>({ givenOn: todayInMoscow(), basis: '', document: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const editable = canEdit && scope.kind !== 'admin'
  const key = JSON.stringify(scope)

  const handleError = useCallback((value: unknown) => (
    onAccessError !== undefined
      ? onAccessError(value)
      : handledSessionError(value, { onSessionExpired: onSessionExpired ?? (() => {}), onProfileUnavailable: onProfileUnavailable ?? (() => {}) })
  ), [onAccessError, onProfileUnavailable, onSessionExpired])

  const load = useCallback(async () => {
    try {
      setConsents(await api(JSON.parse(key) as ConsentScope).list())
    } catch (value) {
      if (!handleError(value)) {
        setError(value)
      }
    }
  }, [handleError, key])

  useEffect(() => {
    void load()
  }, [load])

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true)
    setError(null)
    try {
      await action()
      await load()
    } catch (value) {
      if (!handleError(value)) {
        setError(value)
      }
    } finally {
      setBusy(false)
    }
  }

  const add = (event: FormEvent) => {
    event.preventDefault()
    void run(async () => {
      await api(scope).create({
        givenOn: draft.givenOn, basis: draft.basis.trim(), document: draft.document.trim() === '' ? null : draft.document.trim()
      })
      setDraft({ givenOn: todayInMoscow(), basis: '', document: '' })
    })
  }

  const withdraw = (consent: Consent) => void run(() => api(scope).update(consent.id, {
    givenOn: consent.givenOn, basis: consent.basis, document: consent.document, withdrawnOn: todayInMoscow()
  }))

  return (
    <section className="consents-block" aria-label="Согласия на обработку ПДн">
      {error !== null && (
        <div className="notice notice--error" role="alert">
          <p>{error instanceof ApiError ? error.message : 'Не удалось выполнить действие. Повторите попытку позже.'}</p>
          <SupportDetails requestId={requestIdOf(error)} />
        </div>
      )}
      {consents === null && error === null && <p role="status">Загружаем согласия…</p>}
      {consents !== null && !consents.enabled && (
        <p className="admin-form__hint">
          Учёт согласий выключен. Нужен ли он, решает оператор ПДн; включает администратор в разделе «Настройки».
        </p>
      )}
      {consents !== null && consents.enabled && (
        <>
          {consents.consents.length === 0 ? <p>Согласий не записано.</p> : (
            <ul>
              {consents.consents.map((consent) => (
                <li key={consent.id}>
                  <span>
                    {formatCalendarDate(consent.givenOn)}: {consent.basis}
                    {consent.document === null ? '' : `, документ ${consent.document}`}; записал {consent.authorName}
                  </span>
                  {' '}
                  {consent.withdrawnOn !== null
                    ? <span className="status status--missing">Отозвано {formatCalendarDate(consent.withdrawnOn)}</span>
                    : editable && (
                      <button type="button" className="button--secondary" disabled={busy} onClick={() => withdraw(consent)}>
                        Отметить отзыв
                      </button>
                    )}
                </li>
              ))}
            </ul>
          )}
          {editable && (
            <form className="admin-form" onSubmit={add} aria-label="Добавить согласие">
              <label>
                Дата согласия *
                <input type="date" required max={todayInMoscow()} value={draft.givenOn}
                  onChange={(event) => setDraft({ ...draft, givenOn: event.target.value })} />
              </label>
              <label>
                Основание *
                <input required maxLength={300} placeholder="согласие на обработку ПДн" value={draft.basis}
                  onChange={(event) => setDraft({ ...draft, basis: event.target.value })} />
              </label>
              <label>
                Документ
                <input maxLength={300} placeholder="номер и дата" value={draft.document}
                  onChange={(event) => setDraft({ ...draft, document: event.target.value })} />
              </label>
              <div className="admin-form__actions">
                <button type="submit" disabled={busy}>Добавить согласие</button>
              </div>
            </form>
          )}
        </>
      )}
    </section>
  )
}
