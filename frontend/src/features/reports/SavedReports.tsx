import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type ReportPreviewRequest,
  type SavedReport
} from '../../shared/api/client'
import { ErrorNotice } from './ReportErrorNotice'
import './reportExtras.css'

type SavedReportsProps = {
  definition: ReportPreviewRequest
  disabled: boolean
  onOpen: (report: SavedReport) => void
  onAccessError: (error: unknown) => boolean
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; reports: SavedReport[] }
  | { kind: 'failed'; error: unknown }

type Command = 'create' | 'update' | 'delete'

type Period = NonNullable<SavedReport['period']>

const periodTitles: Record<Period, string> = {
  CURRENT_MONTH: 'Текущий месяц',
  PREVIOUS_MONTH: 'Прошлый месяц',
  CURRENT_QUARTER: 'Текущий квартал',
  PREVIOUS_QUARTER: 'Прошлый квартал'
}

const periods = Object.keys(periodTitles) as Period[]

const dateText = (value: string | null | undefined) => (
  value === null || value === undefined ? '…' : value.split('-').reverse().join('.')
)

const periodText = (report: SavedReport) => {
  const { definition } = report
  if (definition.kind === 'SNAPSHOT') {
    return definition.asOf ? `на ${dateText(definition.asOf)}` : 'на сегодня'
  }
  const dates = !definition.from && !definition.to
    ? 'период не ограничен'
    : `${dateText(definition.from)} – ${dateText(definition.to)}`
  return report.period ? `${periodTitles[report.period].toLowerCase()}, сейчас ${dates}` : dates
}

export const SavedReports = ({ definition, disabled, onOpen, onAccessError }: SavedReportsProps) => {
  const [listState, setListState] = useState<ListState>({ kind: 'loading' })
  const [selectedId, setSelectedId] = useState('')
  const [name, setName] = useState('')
  const [period, setPeriod] = useState<Period | ''>('')
  const [busy, setBusy] = useState<Command | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [error, setError] = useState<{ command: Command; error: unknown } | null>(null)
  const [notice, setNotice] = useState('')
  const pendingKey = useRef<{ payload: string; key: string } | null>(null)

  const load = useCallback(async (keepSelection: string) => {
    setListState({ kind: 'loading' })
    try {
      const reports = await apiClient.listSavedReports()
      setListState({ kind: 'ready', reports })
      setSelectedId(reports.some((report) => report.id === keepSelection) ? keepSelection : '')
    } catch (loadError) {
      if (!onAccessError(loadError)) {
        setListState({ kind: 'failed', error: loadError })
      }
    }
  }, [onAccessError])

  useEffect(() => {
    void load('')
  }, [load])

  const reports = listState.kind === 'ready' ? listState.reports : []
  const selected = reports.find((report) => report.id === selectedId)
  const snapshot = definition.kind === 'SNAPSHOT'
  const chosenPeriod = snapshot || period === '' ? null : period

  const keyFor = (payload: object) => {
    const serialized = JSON.stringify(payload)
    const key = pendingKey.current?.payload === serialized ? pendingKey.current.key : createIdempotencyKey()
    pendingKey.current = { payload: serialized, key }
    return key
  }

  const run = async (command: Command, payload: object, action: (key: string) => Promise<SavedReport | void>, done: string) => {
    setBusy(command)
    setError(null)
    setNotice('')
    try {
      const result = await action(keyFor(payload))
      pendingKey.current = null
      setConfirmDelete(false)
      setNotice(done)
      await load(result ? result.id : '')
      if (command === 'create') {
        setName('')
      }
    } catch (commandError) {
      if (commandError instanceof ApiError) {
        pendingKey.current = null
      }
      if (!onAccessError(commandError)) {
        setError({ command, error: commandError })
        if (commandError instanceof ApiError && (commandError.code === 'VERSION_CONFLICT' || commandError.status === 404)) {
          await load(selectedId)
        }
      }
    } finally {
      setBusy(null)
    }
  }

  const create = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const trimmed = name.trim()
    if (trimmed.length === 0 || disabled) {
      return
    }
    const payload = { name: trimmed, definition, period: chosenPeriod }
    void run('create', { command: 'create', ...payload }, (key) => apiClient.createSavedReport(payload, key),
      `Отчёт «${trimmed}» сохранён.`)
  }

  const update = () => {
    if (selected === undefined || disabled) {
      return
    }
    const payload = { name: selected.name, definition, version: selected.version, period: chosenPeriod }
    void run('update', { command: 'update', id: selected.id, ...payload },
      (key) => apiClient.updateSavedReport(selected.id, payload, key), `Отчёт «${selected.name}» обновлён текущим выбором.`)
  }

  const remove = () => {
    if (selected === undefined) {
      return
    }
    void run('delete', { command: 'delete', id: selected.id, version: selected.version },
      (key) => apiClient.deleteSavedReport(selected.id, selected.version, key), `Отчёт «${selected.name}» удалён.`)
  }

  const open = () => {
    if (selected !== undefined) {
      setNotice(`Открыт отчёт «${selected.name}».`)
      onOpen(selected)
    }
  }

  return (
    <section className="saved-reports" aria-labelledby="saved-reports-title">
      <h3 id="saved-reports-title">Сохранённые отчёты</h3>
      <p className="reports__hint">
        Набор вида отчёта, периода, фильтров и колонок в выбранном порядке хранится на сервере под вашей учётной записью и доступен после выхода и на другом устройстве. Другие пользователи его не видят. Относительный период («Прошлый месяц» и другие) пересчитывается при каждом открытии; состояние на сегодняшнюю дату открывается на день открытия.
      </p>
      {listState.kind === 'loading' && <p role="status">Загружаем сохранённые отчёты…</p>}
      {listState.kind === 'failed' && (
        <ErrorNotice error={listState.error} message="Не удалось загрузить сохранённые отчёты." onRetry={() => void load(selectedId)} />
      )}
      {listState.kind === 'ready' && (
        <div className="saved-reports__row">
          <label>
            Отчёт
            <select
              value={selectedId}
              onChange={(event) => {
                setSelectedId(event.target.value)
                setPeriod(reports.find((report) => report.id === event.target.value)?.period ?? '')
                setConfirmDelete(false)
              }}
            >
              <option value="">{reports.length === 0 ? 'Пока нет сохранённых отчётов' : 'Выберите отчёт'}</option>
              {reports.map((report) => <option key={report.id} value={report.id}>{report.name} — {periodText(report)}</option>)}
            </select>
          </label>
          <button type="button" onClick={open} disabled={selected === undefined || busy !== null}>Открыть и показать</button>
          <button type="button" className="reports__secondary" onClick={update} disabled={selected === undefined || disabled || busy !== null}>
            {busy === 'update' ? 'Сохраняем…' : 'Записать текущий выбор'}
          </button>
          {confirmDelete ? (
            <button type="button" className="reports__secondary saved-reports__danger" onClick={remove} disabled={busy !== null}>
              {busy === 'delete' ? 'Удаляем…' : 'Подтвердить удаление'}
            </button>
          ) : (
            <button type="button" className="reports__secondary" onClick={() => setConfirmDelete(true)} disabled={selected === undefined || busy !== null}>
              Удалить
            </button>
          )}
        </div>
      )}
      <form className="saved-reports__row" onSubmit={create}>
        <label>
          Название нового отчёта
          <input type="text" value={name} maxLength={200} onChange={(event) => setName(event.target.value)} />
        </label>
        {!snapshot && (
          <label>
            Период при открытии
            <select
              value={period}
              onChange={(event) => setPeriod(periods.find((item) => item === event.target.value) ?? '')}
            >
              <option value="">Даты как выбраны</option>
              {periods.map((item) => <option key={item} value={item}>{periodTitles[item]}</option>)}
            </select>
          </label>
        )}
        <button type="submit" disabled={name.trim().length === 0 || disabled || busy !== null}>
          {busy === 'create' ? 'Сохраняем…' : 'Сохранить текущий выбор'}
        </button>
      </form>
      {disabled && <p className="reports__hint">Сохранить можно, когда выбор корректен: проверьте период и колонки.</p>}
      {notice !== '' && <p role="status">{notice}</p>}
      {error !== null && (
        <ErrorNotice
          error={error.error}
          message={error.error instanceof ApiError && error.error.code === 'VERSION_CONFLICT'
            ? 'Отчёт уже изменили или удалили в другой вкладке. Список обновлён, повторите действие.'
            : 'Действие с сохранённым отчётом не выполнено.'}
        />
      )}
    </section>
  )
}
