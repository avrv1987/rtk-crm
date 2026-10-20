import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, apiClient } from '../../shared/api/client'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { handledSessionError, type SessionHandlers } from './adminShared'

// Шаг по умолчанию при импорте: его получают карточки, созданные импортом, и массовое действие руководителя.
export const ImportSettingsBlock = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [text, setText] = useState('')
  const [days, setDays] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    apiClient.getImportSettings()
      .then((settings) => {
        setText(settings.defaultStepText)
        setDays(String(settings.defaultStepDays))
        setLoaded(true)
      })
      .catch((value: unknown) => {
        if (!handledSessionError(value, { onSessionExpired, onProfileUnavailable })) {
          setError(value)
        }
      })
  }, [onProfileUnavailable, onSessionExpired])

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setSaving(true)
    setError(null)
    setNotice(null)
    try {
      const saved = await apiClient.updateImportSettings({ defaultStepText: text.trim(), defaultStepDays: Number(days) })
      setText(saved.defaultStepText)
      setDays(String(saved.defaultStepDays))
      setNotice('Шаг по умолчанию сохранён.')
    } catch (value) {
      if (!handledSessionError(value, { onSessionExpired, onProfileUnavailable })) {
        setError(value)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="import-block import-settings" onSubmit={(event) => void save(event)}>
      <div className="import-block__head">
        <h3>Шаг по умолчанию при импорте</h3>
        <HintButton label="О шаге по умолчанию">
          Карточки, созданные импортом, получают этот шаг и срок, чтобы не попасть в «Без шага». Тот же шаг предлагается руководителю в «Контроле» для карточек без шага.
        </HintButton>
      </div>
      <div className="import-grid">
        <label>
          Текст шага
          <input value={text} maxLength={500} required disabled={!loaded || saving} onChange={(event) => setText(event.target.value)} />
        </label>
        <label>
          Срок, дней
          <input type="number" min={1} max={365} value={days} required disabled={!loaded || saving}
            onChange={(event) => setDays(event.target.value)} />
        </label>
      </div>
      <div className="admin-form__actions">
        <button type="submit" className="button--secondary" disabled={!loaded || saving}>
          {saving ? 'Сохраняем…' : 'Сохранить шаг'}
        </button>
      </div>
      {notice !== null && <p role="status">{notice}</p>}
      {error !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{error instanceof ApiError && error.status === 400 ? 'Проверьте текст (до 500 символов) и срок (1–365 дней).' : 'Не удалось загрузить или сохранить настройку.'}</p>
          {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
        </div>
      )}
    </form>
  )
}
