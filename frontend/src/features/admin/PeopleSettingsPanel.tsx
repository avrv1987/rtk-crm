import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { hourUnitLabels, peopleApi, type HourUnit, type PeopleSettings } from '../people/peopleApi'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import './adminUi.css'

type Draft = { teachingUnit: HourUnit; internshipUnit: HourUnit; qualificationUnit: HourUnit; frdoDeadlineDays: string; frdoNoticeDays: string }

const draftOf = (settings: PeopleSettings): Draft => ({
  teachingUnit: settings.teachingUnit,
  internshipUnit: settings.internshipUnit,
  qualificationUnit: settings.qualificationUnit,
  frdoDeadlineDays: settings.frdoDeadlineDays === null ? '' : String(settings.frdoDeadlineDays),
  frdoNoticeDays: String(settings.frdoNoticeDays)
})

const unitFields: { key: 'teachingUnit' | 'internshipUnit' | 'qualificationUnit'; label: string }[] = [
  { key: 'teachingUnit', label: 'Часы преподавания сотрудниками в вузах' },
  { key: 'internshipUnit', label: 'Часы стажировок (стажёра и наставника)' },
  { key: 'qualificationUnit', label: 'Часы повышения квалификации преподавателей' }
]

type State = { kind: 'loading' } | { kind: 'ready'; settings: PeopleSettings } | { kind: 'failed'; requestId?: string }

// Политику учёта (единицы часов, срок передачи в ФРДО) задаёт эксплуатант: в коде только значения по умолчанию.
export const PeopleSettingsPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirmReset, setConfirmReset] = useState(false)

  const handleError = useCallback((value: unknown) => handledSessionError(value, { onSessionExpired, onProfileUnavailable }),
    [onProfileUnavailable, onSessionExpired])

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      const settings = await peopleApi.settings()
      setState({ kind: 'ready', settings })
      setDraft(draftOf(settings))
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
    void run(async () => {
      const settings = await peopleApi.saveSettings({
        teachingUnit: draft.teachingUnit,
        internshipUnit: draft.internshipUnit,
        qualificationUnit: draft.qualificationUnit,
        frdoDeadlineDays: draft.frdoDeadlineDays.trim() === '' ? null : Number(draft.frdoDeadlineDays),
        frdoNoticeDays: Number(draft.frdoNoticeDays)
      })
      setState({ kind: 'ready', settings })
      setDraft(draftOf(settings))
    }, 'Настройки сохранены и записаны в журнал.')
  }

  return (
    <section className="admin-page" aria-labelledby="people-settings-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="people-settings-title">Учёт людей и часов</h2>
        <HintButton label="О разделе">
          Единицы часа, которые подставляются в новые записи вкладки «Люди и часы» и к которым отчёт «Люди и часы» приводит
          часы. Уже внесённые записи сохраняют свою единицу. Срок передачи сведений в ФРДО считается от даты удостоверения;
          пока срок не задан, просрочка не считается и напоминаний нет. Каждое изменение записывается в журнал.
        </HintButton>
      </div>
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем настройки…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить настройки.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && draft !== null && (
        <form className="admin-form" onSubmit={save} aria-label="Настройки учёта людей и часов">
          <p className="admin-form__hint">
            {state.settings.saved
              ? `Изменено: ${formatDateTime(state.settings.updatedAt ?? '')}, ${state.settings.updatedByName ?? ''}.`
              : 'Действуют значения по умолчанию.'}
          </p>
          {unitFields.map((field) => (
            <label key={field.key}>
              {field.label}
              <select value={draft[field.key]} disabled={saving}
                onChange={(event) => setDraft({ ...draft, [field.key]: event.target.value as HourUnit })}>
                {(Object.keys(hourUnitLabels) as HourUnit[]).map((unit) => <option key={unit} value={unit}>{hourUnitLabels[unit]}</option>)}
              </select>
            </label>
          ))}
          <label>
            Срок передачи сведений в ФРДО, дней от даты удостоверения (пусто — не задан)
            <input type="number" inputMode="numeric" min={1} max={365} value={draft.frdoDeadlineDays} disabled={saving}
              onChange={(event) => setDraft({ ...draft, frdoDeadlineDays: event.target.value })} />
          </label>
          <label>
            Напоминать о передаче в ФРДО за, дней
            <input type="number" inputMode="numeric" min={0} max={90} required value={draft.frdoNoticeDays} disabled={saving}
              onChange={(event) => setDraft({ ...draft, frdoNoticeDays: event.target.value })} />
          </label>
          {error !== null && (
            <div className="organizations-message organizations-message--error" role="alert">
              <p>{error instanceof ApiError ? error.message : 'Не удалось сохранить настройки.'}</p>
              <SupportDetails requestId={requestIdOf(error)} />
            </div>
          )}
          {notice !== null && <p className="organizations-message" role="status">{notice}</p>}
          <div className="admin-form__actions">
            <button type="button" className="button--secondary" disabled={saving || !state.settings.saved} onClick={() => setConfirmReset(true)}>
              Вернуть по умолчанию
            </button>
            <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
          </div>
        </form>
      )}
      <ConfirmDialog
        open={confirmReset}
        title="Вернуть настройки по умолчанию?"
        description="Преподавание и повышение квалификации — академические часы, стажировки — астрономические, срок передачи в ФРДО не задан."
        confirmLabel="Вернуть"
        onConfirm={() => {
          setConfirmReset(false)
          void run(async () => {
            await peopleApi.resetSettings()
            await load()
          }, 'Восстановлены значения по умолчанию.')
        }}
        onCancel={() => setConfirmReset(false)}
      />
    </section>
  )
}
