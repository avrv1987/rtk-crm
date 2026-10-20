import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError, apiClient, type DemandIndexSettings, type DemandIndexWeights } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import './adminUi.css'

type Field = keyof DemandIndexWeights

const weightFields: { key: Field; label: string }[] = [
  { key: 'growthWeight', label: 'Прирост записанных' },
  { key: 'completionWeight', label: 'Доля завершивших' },
  { key: 'activityWeight', label: 'Активность за 7 дней' },
  { key: 'universityWeight', label: 'Спрос вузов' },
  { key: 'paymentsWeight', label: 'Оплаты открытого набора' }
]

type Draft = Record<Field, string>

const draftOf = (weights: DemandIndexWeights): Draft => (
  Object.fromEntries(Object.entries(weights).map(([key, value]) => [key, String(value)])) as Draft
)

export const weightSum = (draft: Draft) => weightFields.reduce((sum, field) => sum + (Number(draft[field.key]) || 0), 0)

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; settings: DemandIndexSettings }
  | { kind: 'failed'; requestId?: string }

export const DemandIndexPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [confirmReset, setConfirmReset] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const handleError = useCallback((value: unknown) => (
    handledSessionError(value, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const show = (settings: DemandIndexSettings) => {
    setState({ kind: 'ready', settings })
    setDraft(draftOf(settings.current))
  }

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      const settings = await apiClient.getDemandIndexSettings()
      setState({ kind: 'ready', settings })
      setDraft(draftOf(settings.current))
    } catch (value) {
      if (!handleError(value)) {
        setState({ kind: 'failed', requestId: requestIdOf(value) })
      }
    }
  }, [handleError])

  useEffect(() => {
    void load()
  }, [load])

  const run = async (action: () => Promise<void>, message: string) => {
    setSaving(true)
    setError(null)
    setNotice(null)
    try {
      await action()
      setNotice(message)
    } catch (value) {
      if (!handleError(value)) {
        setError(value)
      }
    } finally {
      setSaving(false)
    }
  }

  const save = (event: FormEvent) => {
    event.preventDefault()
    if (draft === null) {
      return
    }
    const payload = Object.fromEntries(Object.entries(draft).map(([key, value]) => [key, Number(value)])) as DemandIndexWeights
    void run(async () => show(await apiClient.updateDemandIndexSettings(payload)), 'Веса сохранены и записаны в журнал.')
  }

  const reset = () => {
    setConfirmReset(false)
    void run(async () => {
      await apiClient.resetDemandIndexSettings()
      await load()
    }, 'Восстановлены значения по умолчанию.')
  }

  const sum = draft === null ? 0 : weightSum(draft)

  return (
    <section className="admin-page" aria-labelledby="demand-index-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="demand-index-title">Индекс востребованности</h2>
        <HintButton label="О разделе">
          Веса компонентов, по которым отчёт «Востребованность программ» ставит программам место (методика М17 в
          «Паспортах показателей»). Каждый компонент — процентиль среди программ выборки, умноженный на вес. Компонент без
          данных не считается нулём: его вес делится между остальными. Каждое изменение записывается в журнал.
        </HintButton>
      </div>
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем веса…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить веса индекса.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && draft !== null && (
        <form className="admin-form" onSubmit={save} aria-label="Веса индекса востребованности">
          <p className="admin-form__hint">
            {state.settings.saved
              ? `Изменено: ${formatDateTime(state.settings.updatedAt ?? '')}, ${state.settings.updatedByName ?? ''}.`
              : 'Действуют значения по умолчанию.'}
          </p>
          {weightFields.map((field) => (
            <label key={field.key}>
              {field.label}, вес (по умолчанию {state.settings.defaults[field.key]})
              <input
                type="number"
                inputMode="numeric"
                min={0}
                max={100}
                required
                value={draft[field.key]}
                disabled={saving}
                onChange={(event) => setDraft({ ...draft, [field.key]: event.target.value })}
              />
            </label>
          ))}
          <p className="admin-form__hint" role="status">
            Сумма весов: {sum} из 100{sum === 100 ? '' : ' — должна быть ровно 100'}
          </p>
          <label>
            Порог покрытия, % веса с данными (по умолчанию {state.settings.defaults.minCoveragePercent})
            <input
              type="number"
              inputMode="numeric"
              min={1}
              max={100}
              required
              value={draft.minCoveragePercent}
              disabled={saving}
              onChange={(event) => setDraft({ ...draft, minCoveragePercent: event.target.value })}
            />
          </label>
          <label>
            Малая база, участий меньше (по умолчанию {state.settings.defaults.smallBase})
            <input
              type="number"
              inputMode="numeric"
              min={0}
              max={10000}
              required
              value={draft.smallBase}
              disabled={saving}
              onChange={(event) => setDraft({ ...draft, smallBase: event.target.value })}
            />
          </label>
          {error !== null && (
            <div className="organizations-message organizations-message--error" role="alert">
              <p>{error instanceof ApiError ? error.message : 'Не удалось сохранить веса. Повторите попытку позже.'}</p>
              <SupportDetails requestId={requestIdOf(error)} />
            </div>
          )}
          {notice !== null && <p className="organizations-message" role="status">{notice}</p>}
          <div className="admin-form__actions">
            <button type="button" className="button--secondary" disabled={saving || !state.settings.saved}
              onClick={() => setConfirmReset(true)}>
              Вернуть по умолчанию
            </button>
            <button type="submit" disabled={saving || sum !== 100}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
          </div>
        </form>
      )}
      <ConfirmDialog
        open={confirmReset}
        title="Вернуть веса по умолчанию?"
        description="Отчёт «Востребованность программ» снова будет считать индекс по весам из паспорта М17."
        confirmLabel="Вернуть"
        onConfirm={reset}
        onCancel={() => setConfirmReset(false)}
      />
    </section>
  )
}
