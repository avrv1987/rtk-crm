import { type FormEvent, useCallback, useEffect, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type AdminCatalogEntry,
  type VendorContact,
  type VendorContactList
} from '../../shared/api/client'
import { commandErrorMessage, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import './vendorContacts.css'

type VendorContactsPanelProps = SessionHandlers & {
  vendor: AdminCatalogEntry
  onClose: () => void
}

type Draft = {
  contact: VendorContact | null
  name: string
  phone: string
  email: string
  prefersEmail: boolean
  prefersTelegram: boolean
  productIds: string[]
}

type SaveState = { saving: boolean; error?: unknown; key: string | null }

const emptyDraft = (): Draft => ({
  contact: null, name: '', phone: '', email: '', prefersEmail: false, prefersTelegram: false, productIds: []
})

const draftOf = (contact: VendorContact): Draft => ({
  contact,
  name: contact.name,
  phone: contact.phone ?? '',
  email: contact.email ?? '',
  prefersEmail: contact.prefersEmail,
  prefersTelegram: contact.prefersTelegram,
  productIds: contact.products.map((product) => product.id)
})

const channelLabel = (contact: { prefersEmail: boolean; prefersTelegram: boolean }) => {
  const channels = [contact.prefersEmail ? 'почта' : null, contact.prefersTelegram ? 'чат в Telegram' : null]
    .filter((channel) => channel !== null)
  return channels.length === 0 ? 'не указан' : channels.join(', ')
}

export const VendorContactsPanel = ({ vendor, onClose, onSessionExpired, onProfileUnavailable }: VendorContactsPanelProps) => {
  const [list, setList] = useState<VendorContactList | null>(null)
  const [loadError, setLoadError] = useState<string | undefined | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [save, setSave] = useState<SaveState>({ saving: false, key: null })
  const [message, setMessage] = useState<string | null>(null)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const load = useCallback(async () => {
    setLoadError(null)
    try {
      setList(await apiClient.listVendorContacts(vendor.id))
    } catch (error) {
      if (!handleError(error)) {
        setLoadError(requestIdOf(error))
      }
    }
  }, [handleError, vendor.id])

  useEffect(() => {
    void load()
  }, [load])

  const edit = (next: Draft | null) => {
    setDraft(next)
    setSave({ saving: false, key: null })
    setMessage(null)
  }

  const change = (patch: Partial<Draft>) => {
    if (draft !== null) {
      setDraft({ ...draft, ...patch })
      setSave({ saving: false, key: null })
    }
  }

  const run = async (action: (key: string) => Promise<VendorContact>, done: (contact: VendorContact) => string) => {
    const key = save.key ?? createIdempotencyKey()
    setSave({ saving: true, key })
    try {
      const contact = await action(key)
      setSave({ saving: false, key: null })
      setDraft(null)
      setMessage(done(contact))
      await load()
    } catch (error) {
      if (!handleError(error)) {
        setSave({ saving: false, error, key })
      }
    }
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (draft === null || save.saving || draft.name.trim().length === 0) {
      return
    }
    const payload = {
      name: draft.name.trim(),
      phone: draft.phone.trim(),
      email: draft.email.trim(),
      prefersEmail: draft.prefersEmail,
      prefersTelegram: draft.prefersTelegram,
      productIds: draft.productIds
    }
    const current = draft.contact
    void run(
      (key) => current === null
        ? apiClient.createVendorContact(vendor.id, payload, key)
        : apiClient.updateVendorContact(vendor.id, current.id, { ...payload, version: current.version }, key),
      (contact) => `Контакт «${contact.name}» сохранён; КАМ видит его у продуктов в карточке.`
    )
  }

  const toggleArchived = (contact: VendorContact) => {
    setMessage(null)
    void run(
      (key) => apiClient.updateVendorContact(vendor.id, contact.id, { archived: !contact.archived, version: contact.version }, key),
      (changed) => changed.archived
        ? `Контакт «${changed.name}» в архиве, продукты откреплены.`
        : `Контакт «${changed.name}» восстановлен.`
    )
  }

  const products = list?.products ?? []

  return (
    <section className="vendor-contacts" aria-label={`Контакты вендора ${vendor.name}`}>
      <div className="vendor-contacts__header">
        <h4>Контакты вендора «{vendor.name}»</h4>
        <button type="button" className="button--secondary" onClick={onClose}>Закрыть</button>
      </div>
      <p className="admin-profile-form__hint">
        Контакт отвечает за продукты вендора: КАМ видит его в карточке вуза у продукта и в форме «Договор и лицензия». Телефон хранится как +7XXXXXXXXXX, почта у вендора не повторяется.
      </p>
      {message !== null && <p className="notice" role="status">{message}</p>}
      {loadError !== null && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить контакты. Повторите попытку.</p>
          {loadError !== undefined && <p className="request-id">Request ID: {loadError}</p>}
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {list !== null && list.contacts.length === 0 && <p className="organizations-message">Контактов пока нет.</p>}
      {list !== null && list.contacts.length > 0 && (
        <ul className="admin-profiles__list" aria-label="Контакты вендора">
          {list.contacts.map((contact) => (
            <li key={contact.id} className="admin-profiles__item">
              <div className="admin-profiles__identity">
                <h3>
                  {contact.name}
                  {contact.archived && <span className="admin-profiles__status admin-profiles__status--blocked">В архиве</span>}
                </h3>
                <dl className="admin-profiles__fields">
                  <div>
                    <dt>Телефон</dt>
                    <dd>{contact.phone ?? 'не указан'}</dd>
                  </div>
                  <div>
                    <dt>Почта</dt>
                    <dd>{contact.email ?? 'не указана'}</dd>
                  </div>
                  <div>
                    <dt>Способ связи</dt>
                    <dd>{channelLabel(contact)}</dd>
                  </div>
                  <div>
                    <dt>Продукты</dt>
                    <dd>{contact.products.length === 0 ? 'не выбраны' : contact.products.map((product) => product.name).join(', ')}</dd>
                  </div>
                </dl>
              </div>
              <div className="admin-profiles__actions admin-catalogs__actions">
                {!contact.archived && (
                  <button type="button" className="button--secondary" disabled={save.saving} onClick={() => edit(draftOf(contact))}>
                    Изменить
                  </button>
                )}
                <button type="button" className="button--secondary" disabled={save.saving} onClick={() => toggleArchived(contact)}>
                  {contact.archived ? 'Восстановить' : 'Архивировать'}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {draft === null && (
        <button type="button" className="button--secondary" disabled={vendor.archived || list === null} onClick={() => edit(emptyDraft())}>
          Добавить контакт
        </button>
      )}
      {draft !== null && (
        <form className="admin-profile-form" onSubmit={submit} aria-label={draft.contact === null ? 'Новый контакт вендора' : 'Изменение контакта вендора'}>
          <label>
            ФИО *
            <input value={draft.name} maxLength={200} disabled={save.saving} onChange={(event) => change({ name: event.target.value })} />
          </label>
          <label>
            Телефон
            <input
              type="tel"
              value={draft.phone}
              maxLength={50}
              placeholder="+7 (900) 000-00-00"
              disabled={save.saving}
              onChange={(event) => change({ phone: event.target.value })}
            />
          </label>
          <label>
            Почта
            <input type="email" value={draft.email} maxLength={320} disabled={save.saving} onChange={(event) => change({ email: event.target.value })} />
          </label>
          <fieldset>
            <legend>Способ связи</legend>
            <label>
              <input
                type="checkbox"
                checked={draft.prefersEmail}
                disabled={save.saving}
                onChange={(event) => change({ prefersEmail: event.target.checked })}
              />
              Почта
            </label>
            <label>
              <input
                type="checkbox"
                checked={draft.prefersTelegram}
                disabled={save.saving}
                onChange={(event) => change({ prefersTelegram: event.target.checked })}
              />
              Чат в Telegram
            </label>
          </fieldset>
          <fieldset>
            <legend>Продукты контакта</legend>
            {products.length === 0 && <p className="admin-profile-form__hint">У вендора нет продуктов.</p>}
            {products.map((product) => (
              <label key={product.id}>
                <input
                  type="checkbox"
                  checked={draft.productIds.includes(product.id)}
                  disabled={save.saving}
                  onChange={(event) => change({
                    productIds: event.target.checked
                      ? [...draft.productIds, product.id]
                      : draft.productIds.filter((id) => id !== product.id)
                  })}
                />
                {product.name}{product.archived ? ' (в архиве)' : ''}
              </label>
            ))}
            <p className="admin-profile-form__hint">У продукта один контакт: выбранный продукт открепится от прежнего контакта.</p>
          </fieldset>
          <div className="admin-profiles__confirmation-actions">
            <button type="submit" disabled={save.saving || draft.name.trim().length === 0}>
              {save.saving ? 'Сохраняем…' : 'Сохранить'}
            </button>
            <button type="button" className="button--secondary" disabled={save.saving} onClick={() => edit(null)}>Отмена</button>
          </div>
        </form>
      )}
      {save.error !== undefined && (
        <div className="interaction-command-error" role="alert">
          <p>{commandErrorMessage(save.error)}</p>
          {save.error instanceof ApiError && <p className="request-id">Request ID: {save.error.requestId}</p>}
        </div>
      )}
    </section>
  )
}
