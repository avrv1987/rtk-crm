import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { CommandError, useAccessErrorHandler } from '../agreements/agreementUi'
import { reportingApi, type ReportingSettingsView } from '../agreements/reportingApi'
import {
  columnTitle,
  deadlineDrafts,
  deadlinesInput,
  deadlinesValid,
  monthNames,
  moveColumn,
  settingsDraft,
  settingsInput,
  templateInput,
  toggleColumn,
  type DeadlineDraft,
  type ReportColumn,
  type SettingsDraft
} from '../agreements/reportingModel'
import { formatDateTime, type SessionHandlers } from './adminShared'
import { ReportingRatesTab, ReportingReferencesTab } from './AgreementReportingCatalogs'
import '../agreements/agreements.css'
import '../agreements/agreementReporting.css'
import './adminUi.css'

export const reportingTabs = ['settings', 'deadlines', 'references', 'rates', 'template'] as const
export type ReportingTab = typeof reportingTabs[number]

const tabLabels: Record<ReportingTab, string> = {
  settings: 'Настройки',
  deadlines: 'Сроки отчётного цикла',
  references: 'Справочники',
  rates: 'Ставки',
  template: 'Шаблон выгрузки'
}

export const reportingTabFrom = (query: string): ReportingTab => (
  reportingTabs.find((tab) => tab === new URLSearchParams(query).get('tab')) ?? 'settings'
)

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; view: ReportingSettingsView }
  | { kind: 'failed'; error: unknown }

type Save = (action: () => Promise<ReportingSettingsView>, message: string) => Promise<boolean>

export const AgreementReportingPanel = ({ query, onSessionExpired, onProfileUnavailable }: SessionHandlers & { query: string }) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const tab = reportingTabFrom(query)
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', view: await reportingApi.settings() })
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setState({ kind: 'failed', error: caught })
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const save: Save = async (action, message) => {
    setSaving(true)
    setError(null)
    setNotice(null)
    try {
      setState({ kind: 'ready', view: await action() })
      setNotice(message)
      return true
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setError(caught)
      }
      return false
    } finally {
      setSaving(false)
    }
  }

  const selectTab = (next: ReportingTab) => {
    setError(null)
    setNotice(null)
    window.location.hash = `#/admin/agreement-reporting?tab=${next}`
  }

  const handlers = { onSessionExpired, onProfileUnavailable }

  return (
    <section className="admin-page" aria-labelledby="agreement-reporting-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="agreement-reporting-title">Отчётность по соглашениям</h2>
        <HintButton label="О разделе">
          Здесь настраивается «Отчёт по соглашениям» с вузами и согласование перечней мероприятий под требования эксплуатанта:
          отчётный период, срок ответа вуза, сроки отчётного цикла, справочники исполнителей, уровней образования и единиц,
          ставки оценки, порог свода и колонки выгрузки. Нормативы в систему не зашиты: всё задаёт администратор, каждое
          изменение пишется в журнал.
        </HintButton>
      </div>
      <div className="admin-tabs" role="group" aria-label="Раздел настроек">
        {reportingTabs.map((item) => (
          <button key={item} type="button" aria-pressed={tab === item} onClick={() => selectTab(item)}>{tabLabels[item]}</button>
        ))}
      </div>
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем настройки…</p>}
      {state.kind === 'failed' && <CommandError error={state.error} fallback="Не удалось загрузить настройки." onRetry={() => void load()} />}
      {state.kind === 'ready' && (
        <>
          {state.view.updatedAt && (
            <p className="admin-form__hint">Изменено: {formatDateTime(state.view.updatedAt)}{state.view.updatedByName ? `, ${state.view.updatedByName}` : ''}.</p>
          )}
          {tab === 'settings' && <SettingsTab key={state.view.updatedAt ?? ''} view={state.view} saving={saving} save={save} />}
          {tab === 'deadlines' && <DeadlinesTab key={state.view.updatedAt ?? ''} view={state.view} saving={saving} save={save} />}
          {tab === 'template' && <TemplateTab key={state.view.updatedAt ?? ''} view={state.view} saving={saving} save={save} />}
          {tab === 'references' && <ReportingReferencesTab {...handlers} />}
          {tab === 'rates' && <ReportingRatesTab {...handlers} />}
          {error !== null && <CommandError error={error} fallback="Изменения не сохранены." />}
          {notice !== null && <p className="organizations-message" role="status">{notice}</p>}
        </>
      )}
    </section>
  )
}

type TabProps = { view: ReportingSettingsView; saving: boolean; save: Save }

const SettingsTab = ({ view, saving, save }: TabProps) => {
  const [draft, setDraft] = useState<SettingsDraft>(() => settingsDraft(view.settings))
  const set = (patch: Partial<SettingsDraft>) => setDraft((current) => ({ ...current, ...patch }))

  const submit = (event: FormEvent) => {
    event.preventDefault()
    void save(() => reportingApi.saveSettings(settingsInput(draft)), 'Настройки сохранены и записаны в журнал.')
  }

  return (
    <form className="admin-form admin-form--cols" onSubmit={submit} aria-label="Настройки отчётности по соглашениям">
      <label>
        Отчётный период
        <select value={draft.periodKind} disabled={saving} onChange={(event) => set({ periodKind: event.target.value as SettingsDraft['periodKind'] })}>
          <option value="CALENDAR">Календарный год</option>
          <option value="ACADEMIC">Учебный год</option>
        </select>
      </label>
      <label>
        Месяц начала учебного года
        <select value={draft.academicYearStartMonth} disabled={saving || draft.periodKind !== 'ACADEMIC'}
          onChange={(event) => set({ academicYearStartMonth: event.target.value })}>
          {monthNames.map((name, index) => <option key={name} value={String(index + 1)}>{name}</option>)}
        </select>
      </label>
      <p className="admin-form__hint admin-form__wide">Текущий период по настройке: {view.currentPeriod.label}.</p>
      <label>
        Срок ответа вуза на перечень, дней
        <input type="number" min={1} max={365} step={1} inputMode="numeric" value={draft.silenceDays} disabled={saving}
          onChange={(event) => set({ silenceDays: event.target.value })} />
      </label>
      <label>
        Срок ответа на повторную отправку, дней
        <input type="number" min={1} max={365} step={1} inputMode="numeric" value={draft.repeatSilenceDays} disabled={saving}
          onChange={(event) => set({ repeatSilenceDays: event.target.value })} />
      </label>
      <p className="admin-form__hint admin-form__wide">
        Пусто в сроке ответа — согласования «молчанием» нет: перечень ждёт ответа вуза без срока. Если срок задан и вуз не
        ответил до его конца, перечень считается согласованным без ответа. Пустой срок повторного ответа — как первый.
      </p>
      <label>
        Напоминать о сроках цикла за, дней
        <input type="number" min={0} max={365} step={1} inputMode="numeric" required value={draft.reminderDays} disabled={saving}
          onChange={(event) => set({ reminderDays: event.target.value })} />
      </label>
      <fieldset className="admin-form__wide agreement-form__choices">
        <legend>Свод по исполнителям против порога</legend>
        <label className="admin-form__check">
          <input type="checkbox" checked={draft.thresholdEnabled} disabled={saving} onChange={(event) => set({ thresholdEnabled: event.target.checked })} />
          Показывать свод в отчёте
        </label>
        <div className="admin-form admin-form--cols">
          <label>
            Порог, % от базы
            <input type="number" min={0} max={100} step={0.01} inputMode="decimal" value={draft.thresholdPercent}
              required={draft.thresholdEnabled} disabled={saving || !draft.thresholdEnabled}
              onChange={(event) => set({ thresholdPercent: event.target.value })} />
          </label>
          <label>
            Общая база, ₽
            <input type="number" min={0} step={0.01} inputMode="decimal" value={draft.thresholdBase} disabled={saving || !draft.thresholdEnabled}
              onChange={(event) => set({ thresholdBase: event.target.value })} />
          </label>
          <label className="admin-form__wide">
            Название базы
            <input value={draft.thresholdBaseLabel} maxLength={200} placeholder="Например, выручка за прошлый год" disabled={saving || !draft.thresholdEnabled}
              onChange={(event) => set({ thresholdBaseLabel: event.target.value })} />
          </label>
        </div>
        <p className="admin-form__hint">
          Свод сравнивает оценку мероприятий каждого исполнителя с долей его базы (база задаётся у исполнителя в
          справочниках). Пустая общая база — сумма баз исполнителей.
        </p>
      </fieldset>
      <div className="admin-form__actions admin-form__wide">
        <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
      </div>
    </form>
  )
}

const emptyDeadline: DeadlineDraft = { name: '', dueOn: '', deliverable: '', annual: true }

const DeadlinesTab = ({ view, saving, save }: TabProps) => {
  const [drafts, setDrafts] = useState<DeadlineDraft[]>(() => deadlineDrafts(view.deadlines))
  const update = (index: number, patch: Partial<DeadlineDraft>) => (
    setDrafts((current) => current.map((item, position) => (position === index ? { ...item, ...patch } : item)))
  )

  const submit = (event: FormEvent) => {
    event.preventDefault()
    void save(() => reportingApi.saveDeadlines(deadlinesInput(drafts)), 'Сроки отчётного цикла сохранены.')
  }

  return (
    <form className="admin-form" onSubmit={submit} aria-label="Сроки отчётного цикла">
      <p className="admin-form__hint">
        Даты, к которым школа сдаёт материалы по соглашениям. За указанное в настройках число дней срок появляется в
        колокольчике КАМ и руководителей и в «Контроле». Ежегодный срок повторяется в тот же день и месяц.
      </p>
      <p className="admin-form__hint">
        Пример на основе приказа Минцифры № 270 (уточните у ответственного): 10.11 — перечень мероприятий вузу на рассмотрение;
        10.12 — предварительный отчёт; 01.03 — итоговый перечень.
      </p>
      {drafts.length === 0 && <p>Сроков нет.</p>}
      <ul className="reporting-deadlines">
        {drafts.map((draft, index) => (
          <li key={index}>
            <label>
              Название
              <input value={draft.name} required maxLength={200} disabled={saving} onChange={(event) => update(index, { name: event.target.value })} />
            </label>
            <label>
              Дата
              <input type="date" value={draft.dueOn} required disabled={saving} onChange={(event) => update(index, { dueOn: event.target.value })} />
            </label>
            <label>
              Что сдаётся
              <input value={draft.deliverable} maxLength={500} disabled={saving} onChange={(event) => update(index, { deliverable: event.target.value })} />
            </label>
            <label className="admin-form__check">
              <input type="checkbox" checked={draft.annual} disabled={saving} onChange={(event) => update(index, { annual: event.target.checked })} />
              Ежегодно
            </label>
            <button type="button" className="button--secondary" disabled={saving}
              onClick={() => setDrafts((current) => current.filter((_, position) => position !== index))}>
              Убрать
            </button>
          </li>
        ))}
      </ul>
      <div className="admin-form__actions">
        <button type="button" className="button--secondary" disabled={saving} onClick={() => setDrafts((current) => [...current, emptyDeadline])}>
          Добавить срок
        </button>
        <button type="submit" disabled={saving || !deadlinesValid(drafts)}>{saving ? 'Сохраняем…' : 'Сохранить сроки'}</button>
      </div>
    </form>
  )
}

const TemplateTab = ({ view, saving, save }: TabProps) => {
  const [columns, setColumns] = useState<ReportColumn[]>(() => view.columns.map((column) => ({ ...column })))
  const [confirmReset, setConfirmReset] = useState(false)
  const unused = view.availableColumns.filter((option) => !columns.some((column) => column.code === option.code))

  const submit = (event: FormEvent) => {
    event.preventDefault()
    void save(() => reportingApi.saveTemplate(templateInput(columns)), 'Шаблон выгрузки сохранён.')
  }

  const reset = () => {
    setConfirmReset(false)
    void save(() => reportingApi.resetTemplate(), 'Восстановлены колонки по умолчанию.')
  }

  return (
    <>
      <form className="admin-form" onSubmit={submit} aria-label="Шаблон выгрузки отчёта по соглашениям">
        <p className="admin-form__hint">
          Колонки строк «Отчёта по соглашениям» — на экране и в XLSX, PDF и JSON: набор, порядок и подписи. Пустая подпись —
          подпись по умолчанию. {view.defaultColumns ? 'Сейчас действуют колонки по умолчанию.' : ''}
        </p>
        <ol className="reporting-columns" aria-label="Колонки отчёта">
          {columns.map((column, index) => {
            const fallback = columnTitle({ code: column.code, title: null }, view.availableColumns)
            return (
              <li key={column.code}>
                <input
                  type="checkbox"
                  checked
                  aria-label={`Колонка «${fallback}» в отчёте`}
                  disabled={saving || columns.length === 1}
                  onChange={() => setColumns((current) => toggleColumn(current, column.code, false))}
                />
                <input
                  type="text"
                  value={column.title ?? ''}
                  placeholder={fallback}
                  aria-label={`Подпись колонки «${fallback}»`}
                  maxLength={200}
                  disabled={saving}
                  onChange={(event) => setColumns((current) => current.map((item) => (item.code === column.code ? { ...item, title: event.target.value } : item)))}
                />
                <button type="button" className="button--secondary" aria-label={`Выше: «${fallback}»`} disabled={saving || index === 0}
                  onClick={() => setColumns((current) => moveColumn(current, index, -1))}>↑</button>
                <button type="button" className="button--secondary" aria-label={`Ниже: «${fallback}»`} disabled={saving || index === columns.length - 1}
                  onClick={() => setColumns((current) => moveColumn(current, index, 1))}>↓</button>
              </li>
            )
          })}
        </ol>
        {unused.length > 0 && (
          <fieldset className="agreement-form__choices">
            <legend>Добавить колонку</legend>
            <ul>
              {unused.map((option) => (
                <li key={option.code}>
                  <label className="admin-form__check">
                    <input type="checkbox" checked={false} disabled={saving}
                      onChange={() => setColumns((current) => toggleColumn(current, option.code, true))} />
                    {option.defaultTitle}
                  </label>
                </li>
              ))}
            </ul>
          </fieldset>
        )}
        <div className="admin-form__actions">
          <button type="button" className="button--secondary" disabled={saving || view.defaultColumns} onClick={() => setConfirmReset(true)}>
            Вернуть по умолчанию
          </button>
          <button type="submit" disabled={saving || columns.length === 0}>{saving ? 'Сохраняем…' : 'Сохранить шаблон'}</button>
        </div>
      </form>
      <ConfirmDialog
        open={confirmReset}
        title="Вернуть колонки по умолчанию?"
        description="Набор, порядок и подписи колонок отчёта станут такими, как при установке системы."
        confirmLabel="Вернуть"
        onConfirm={reset}
        onCancel={() => setConfirmReset(false)}
      />
    </>
  )
}
