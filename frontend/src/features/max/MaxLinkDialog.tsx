import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { ApiError, apiClient, type MaxLinkStart, type MaxLinkStatus, type MaxPreferences } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { defaultPreferences, pollNeeded, preferenceLabels, stateText } from './maxLink'
import './max.css'

type MaxLinkDialogProps = {
  open: boolean
  onClose: () => void
}

const errorMessage = (error: unknown) => (error instanceof ApiError ? error.message : 'Не удалось выполнить действие. Повторите позже.')

export const MaxLinkDialog = ({ open, onClose }: MaxLinkDialogProps) => {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const [status, setStatus] = useState<MaxLinkStatus | null>(null)
  const [start, setStart] = useState<MaxLinkStart | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setStatus(await apiClient.getMaxLink())
      setError(null)
    } catch (loadError) {
      setError(errorMessage(loadError))
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

  useEffect(() => {
    if (!open || status === null || !pollNeeded(status.state)) {
      return undefined
    }
    const timer = window.setInterval(() => void load(), 3000)
    return () => window.clearInterval(timer)
  }, [open, status, load])

  const run = async (action: () => Promise<MaxLinkStatus>) => {
    setBusy(true)
    try {
      setStatus(await action())
      setError(null)
    } catch (actionError) {
      setError(errorMessage(actionError))
    } finally {
      setBusy(false)
    }
  }

  const requestLink = async () => {
    setBusy(true)
    try {
      setStart(await apiClient.startMaxLink())
      setStatus(await apiClient.getMaxLink())
      setError(null)
    } catch (actionError) {
      setError(errorMessage(actionError))
    } finally {
      setBusy(false)
    }
  }

  const preferences = status?.preferences ?? defaultPreferences
  const togglePreference = (key: keyof MaxPreferences) => run(() => apiClient.saveMaxPreferences({ ...preferences, [key]: !preferences[key] }))

  return (
    <dialog
      ref={dialogRef}
      className="confirm-dialog max-link"
      aria-modal="true"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
    >
      <h2 id={titleId}>Уведомления в MAX</h2>
      {status === null && error === null && <p role="status">Загружаем…</p>}
      {status !== null && <p>{stateText[status.state]}</p>}

      {status?.state === 'WAITING' && start !== null && (
        <div className="max-link__start">
          <a href={start.link} target="_blank" rel="noreferrer">{start.link}</a>
          <p className="max-link__muted">Ссылка одноразовая и действует до {formatMoscowDateTime(start.expiresAt)}. Не пересылайте её.</p>
        </div>
      )}
      {status?.state === 'WAITING' && start === null && (
        <p className="max-link__muted">Ссылка уже выдана и показывается один раз. Если её нет под рукой, получите новую.</p>
      )}

      {status?.state === 'FOUND' && (
        <p className="max-link__candidate">Подключить MAX-аккаунт <strong>{status.candidateName}</strong>?</p>
      )}

      {(status?.state === 'ACTIVE' || status?.state === 'STOPPED') && (
        <fieldset className="max-link__preferences" disabled={busy}>
          <legend>Что присылать</legend>
          {(Object.keys(preferenceLabels) as (keyof MaxPreferences)[]).map((key) => (
            <label key={key}>
              <input type="checkbox" checked={preferences[key]} onChange={() => void togglePreference(key)} />
              {preferenceLabels[key]}
            </label>
          ))}
          {status.maxName && <p className="max-link__muted">Аккаунт: {status.maxName}</p>}
        </fieldset>
      )}

      {error !== null && <p className="max-link__error" role="alert">{error}</p>}

      <div className="confirm-dialog__actions">
        {status?.state === 'FOUND' && (
          <>
            <button type="button" className="button--secondary" disabled={busy} onClick={() => void run(() => apiClient.rejectMaxLink())}>Это не я</button>
            <button type="button" disabled={busy} onClick={() => void run(() => apiClient.confirmMaxLink())}>Подключить</button>
          </>
        )}
        {(status?.state === 'NONE' || status?.state === 'WAITING') && (
          <button type="button" disabled={busy} onClick={() => void requestLink()}>
            {status.state === 'NONE' ? 'Получить ссылку' : 'Новая ссылка'}
          </button>
        )}
        {(status?.state === 'ACTIVE' || status?.state === 'STOPPED') && (
          <button type="button" className="button--danger" disabled={busy} onClick={() => void run(() => apiClient.deleteMaxLink())}>Отключить MAX</button>
        )}
        <button type="button" className="button--secondary" onClick={onClose}>Закрыть</button>
      </div>
    </dialog>
  )
}
