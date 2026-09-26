import { useCallback, useEffect, useId, useState, type FormEvent } from 'react'
import { agreementsApi, type ActivityKind } from './agreementsApi'
import { CommandError, useAccessErrorHandler, useCommandKey } from './agreementUi'
import './agreements.css'

type ActivityKindsPanelProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type KindsState =
  | { kind: 'loading' }
  | { kind: 'ready'; kinds: ActivityKind[] }
  | { kind: 'failed'; error: unknown }

export const ActivityKindsPanel = ({ onSessionExpired, onProfileUnavailable }: ActivityKindsPanelProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [state, setState] = useState<KindsState>({ kind: 'loading' })
  const [name, setName] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()
  const titleId = useId()

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', kinds: await agreementsApi.listKinds(true) })
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setState({ kind: 'failed', error: caught })
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const create = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload = { name: name.trim() }
    setSaving(true)
    setError(null)
    try {
      await agreementsApi.createKind(payload.name, commandKey.keyFor(payload))
      commandKey.settle()
      setName('')
      await load()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="activity-kinds" aria-labelledby={titleId}>
      <h2 id={titleId}>Виды мероприятий соглашений</h2>
      <p className="agreements__hint">
        Справочник для плана мероприятий соглашений с вузами. Начальные значения — по видам мероприятий приказа Минцифры № 270; вид в архиве остаётся у существующих мероприятий, но не выбирается для новых.
      </p>
      {state.kind === 'loading' && <p role="status">Загружаем виды мероприятий…</p>}
      {state.kind === 'failed' && <CommandError error={state.error} fallback="Не удалось загрузить виды мероприятий." onRetry={() => void load()} />}
      {state.kind === 'ready' && (
        <ul className="activity-kinds__list">
          {state.kinds.map((kind) => (
            <ActivityKindRow key={`${kind.id}:${kind.version}`} kind={kind} onSaved={() => void load()} handleAccessError={handleAccessError} />
          ))}
        </ul>
      )}
      <form className="activity-kinds__form" onSubmit={(event) => void create(event)}>
        <label>
          Новый вид мероприятия
          <input value={name} required maxLength={200} onChange={(event) => setName(event.target.value)} />
        </label>
        <button type="submit" disabled={saving || name.trim() === ''}>{saving ? 'Добавляем…' : 'Добавить'}</button>
      </form>
      {error !== null && <CommandError error={error} fallback="Вид мероприятия не добавлен." />}
    </section>
  )
}

type ActivityKindRowProps = {
  kind: ActivityKind
  onSaved: () => void
  handleAccessError: (error: unknown) => boolean
}

const ActivityKindRow = ({ kind, onSaved, handleAccessError }: ActivityKindRowProps) => {
  const [name, setName] = useState(kind.name)
  const [archived, setArchived] = useState(kind.archived)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()
  const changed = name.trim() !== kind.name || archived !== kind.archived

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload = { version: kind.version, name: name.trim(), archived }
    setSaving(true)
    setError(null)
    try {
      await agreementsApi.updateKind(kind.id, payload, commandKey.keyFor({ id: kind.id, ...payload }))
      commandKey.settle()
      onSaved()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <li className={`activity-kinds__item${kind.archived ? ' activity-kinds__item--archived' : ''}`}>
      <form onSubmit={(event) => void save(event)} aria-label={`Вид мероприятия «${kind.name}»`}>
        <input
          className="activity-kinds__name"
          aria-label="Название вида мероприятия"
          value={name}
          required
          maxLength={200}
          onChange={(event) => setName(event.target.value)}
        />
        <label className="checkbox-field">
          <input type="checkbox" checked={archived} onChange={(event) => setArchived(event.target.checked)} />
          В архиве
        </label>
        <button type="submit" className="button--secondary" disabled={saving || !changed || name.trim() === ''}>
          {saving ? 'Сохраняем…' : 'Сохранить'}
        </button>
      </form>
      {error !== null && <CommandError error={error} fallback="Изменение не сохранено." onRetry={onSaved} retryLabel="Обновить список" />}
    </li>
  )
}
