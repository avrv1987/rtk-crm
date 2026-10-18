import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { ApiError, apiClient, type MailPreferences, type MailTopic } from '../../shared/api/client'
import './mail.css'

type Props = {
  open: boolean
  onClose: () => void
}

const errorMessage = (error: unknown) => (error instanceof ApiError ? error.message : 'Не удалось выполнить действие. Повторите позже.')

// «Уведомления на почту» в меню профиля: какие письма присылать. Адрес — почта учётной записи; меняет его администратор.
export const MailPreferencesDialog = ({ open, onClose }: Props) => {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const [preferences, setPreferences] = useState<MailPreferences | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setPreferences(await apiClient.getMailPreferences())
      setError(null)
    } catch (failure) {
      setError(errorMessage(failure))
    }
  }, [])

  useEffect(() => {
    const dialog = dialogRef.current
    if (dialog === null) {
      return
    }
    if (open && !dialog.open) {
      dialog.showModal()
      void load()
    }
    if (!open && dialog.open) {
      dialog.close()
    }
  }, [open, load])

  const toggle = async (topic: MailTopic) => {
    if (preferences === null) {
      return
    }
    const disabled = preferences.topics
      .filter((item) => (item.topic === topic ? item.enabled : !item.enabled))
      .map((item) => item.topic)
    setBusy(true)
    try {
      setPreferences(await apiClient.saveMailPreferences(disabled))
      setError(null)
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <dialog
      ref={dialogRef}
      className="confirm-dialog mail-preferences"
      aria-modal="true"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
    >
      <h2 id={titleId}>Уведомления на почту</h2>
      {preferences === null && error === null && <p role="status">Загружаем…</p>}
      {preferences !== null && (
        <>
          <p>
            {preferences.email
              ? <>Письма приходят на <strong>{preferences.email}</strong>. Сменить адрес может администратор.</>
              : 'Почта учётной записи не указана или сейчас недоступна: письма не приходят. Обратитесь к администратору.'}
          </p>
          {preferences.topics.length === 0
            ? <p>Для вашей роли писем нет.</p>
            : (
              <fieldset disabled={busy}>
                <legend>Что присылать</legend>
                {preferences.topics.map((item) => (
                  <label key={item.topic}>
                    <input type="checkbox" checked={item.enabled} onChange={() => void toggle(item.topic)} />
                    {item.label}
                  </label>
                ))}
              </fieldset>
            )}
          <p className="mail-admin__muted">В письмах — только названия вузов, номера договоров и сроки; подробности открываются по ссылке в CRM.</p>
        </>
      )}
      {error !== null && <p role="alert">{error}</p>}
      <div className="confirm-dialog__actions">
        <button type="button" className="button--secondary" onClick={onClose}>Закрыть</button>
      </div>
    </dialog>
  )
}
