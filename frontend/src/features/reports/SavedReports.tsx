import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type ReportPreviewRequest,
  type SavedReport
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { CardDialog } from '../interactions/cardUi'
import { InfoTip } from './ReportControls'
import { ErrorNotice } from './ReportErrorNotice'
import { kindLabels } from './reportSelection'

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; reports: SavedReport[] }
  | { kind: 'failed'; error: unknown }

type Command = 'create' | 'update' | 'rename' | 'delete'

type Period = NonNullable<SavedReport['period']>

const periodTitles: Record<Period, string> = {
  CURRENT_MONTH: 'Текущий месяц',
  PREVIOUS_MONTH: 'Прошлый месяц',
  CURRENT_QUARTER: 'Текущий квартал',
  PREVIOUS_QUARTER: 'Прошлый квартал'
}

const periods = Object.keys(periodTitles) as Period[]

const savedReportsHint = 'Сохраняются вид отчёта, период, фильтры и колонки в выбранном порядке. Отчёт хранится на сервере под вашей учётной записью, открывается после выхода и на другом устройстве, другие пользователи его не видят. Относительный период («Прошлый месяц» и другие) пересчитывается при каждом открытии; состояние на сегодня открывается на день открытия.'

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

export const useSavedReports = (onAccessError: (error: unknown) => boolean) => {
  const [listState, setListState] = useState<ListState>({ kind: 'loading' })
  const [busy, setBusy] = useState<Command | null>(null)
  const [error, setError] = useState<{ command: Command; error: unknown } | null>(null)
  const [notice, setNotice] = useState('')
  const pendingKey = useRef<{ payload: string; key: string } | null>(null)

  const load = useCallback(async () => {
    setListState({ kind: 'loading' })
    try {
      setListState({ kind: 'ready', reports: await apiClient.listSavedReports() })
    } catch (loadError) {
      if (!onAccessError(loadError)) {
        setListState({ kind: 'failed', error: loadError })
      }
    }
  }, [onAccessError])

  useEffect(() => {
    void load()
  }, [load])

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
      setNotice(done)
      await load()
      return result ?? null
    } catch (commandError) {
      if (commandError instanceof ApiError) {
        pendingKey.current = null
      }
      if (!onAccessError(commandError)) {
        setError({ command, error: commandError })
        if (commandError instanceof ApiError && (commandError.code === 'VERSION_CONFLICT' || commandError.status === 404)) {
          await load()
        }
      }
      return undefined
    } finally {
      setBusy(null)
    }
  }

  const create = (name: string, definition: ReportPreviewRequest, period: Period | null) => {
    const payload = { name, definition, period }
    return run('create', { command: 'create', ...payload }, (key) => apiClient.createSavedReport(payload, key), `Отчёт «${name}» сохранён.`)
  }

  const update = (report: SavedReport, definition: ReportPreviewRequest, period: Period | null) => {
    const payload = { name: report.name, definition, version: report.version, period }
    return run('update', { command: 'update', id: report.id, ...payload },
      (key) => apiClient.updateSavedReport(report.id, payload, key), `Отчёт «${report.name}» обновлён текущим выбором.`)
  }

  const rename = (report: SavedReport, name: string) => {
    const payload = { name, definition: report.definition, version: report.version, period: report.period ?? null }
    return run('rename', { command: 'rename', id: report.id, ...payload },
      (key) => apiClient.updateSavedReport(report.id, payload, key), `Отчёт переименован в «${name}».`)
  }

  const remove = (report: SavedReport) => run('delete', { command: 'delete', id: report.id, version: report.version },
    (key) => apiClient.deleteSavedReport(report.id, report.version, key), `Отчёт «${report.name}» удалён.`)

  return {
    listState,
    reports: listState.kind === 'ready' ? listState.reports : [],
    busy,
    error,
    notice,
    load,
    create,
    update,
    rename,
    remove
  }
}

export type SavedReportsModel = ReturnType<typeof useSavedReports>

const SavedError = ({ saved }: { saved: SavedReportsModel }) => (
  saved.error === null ? null : (
    <ErrorNotice
      error={saved.error.error}
      message={saved.error.error instanceof ApiError && saved.error.error.code === 'VERSION_CONFLICT'
        ? 'Отчёт уже изменили или удалили в другой вкладке. Список обновлён, повторите действие.'
        : 'Действие с сохранённым отчётом не выполнено.'}
    />
  )
)

const SavedReportRow = ({ report, saved, onOpen, onDelete }: {
  report: SavedReport
  saved: SavedReportsModel
  onOpen: (report: SavedReport) => void
  onDelete: (report: SavedReport) => void
}) => {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(report.name)
  const inputId = useId()

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const trimmed = name.trim()
    if (trimmed.length === 0 || trimmed === report.name) {
      setEditing(false)
      return
    }
    if (await saved.rename(report, trimmed) !== undefined) {
      setEditing(false)
    }
  }

  return (
    <li className="saved-report">
      {editing ? (
        <form className="saved-report__rename" onSubmit={(event) => void submit(event)}>
          <label htmlFor={inputId}>Новое название</label>
          <input id={inputId} type="text" value={name} maxLength={200} autoFocus onChange={(event) => setName(event.target.value)} />
          <button type="submit" disabled={name.trim().length === 0 || saved.busy !== null}>
            {saved.busy === 'rename' ? 'Сохраняем…' : 'Сохранить'}
          </button>
          <button type="button" className="reports__secondary" onClick={() => {
            setName(report.name)
            setEditing(false)
          }}>
            Отмена
          </button>
        </form>
      ) : (
        <>
          <div className="saved-report__text">
            <strong>{report.name}</strong>
            <span>{kindLabels[report.definition.kind]} · {periodText(report)}</span>
          </div>
          <div className="saved-report__actions">
            <button type="button" onClick={() => onOpen(report)} disabled={saved.busy !== null}>Открыть</button>
            <button type="button" className="reports__secondary" onClick={() => setEditing(true)} disabled={saved.busy !== null}>
              Переименовать
            </button>
            <button type="button" className="reports__secondary saved-report__delete" onClick={() => onDelete(report)} disabled={saved.busy !== null}>
              Удалить
            </button>
          </div>
        </>
      )}
    </li>
  )
}

export const SavedReportsList = ({ saved, onOpen }: { saved: SavedReportsModel; onOpen: (report: SavedReport) => void }) => {
  const [deleting, setDeleting] = useState<SavedReport | null>(null)
  return (
    <section className="saved-reports" aria-labelledby="saved-reports-title">
      <div className="saved-reports__header">
        <h2 id="saved-reports-title" className="reports__section-title">Сохранённые отчёты</h2>
        <InfoTip label="Сохранённые отчёты">{savedReportsHint}</InfoTip>
      </div>
      {saved.listState.kind === 'loading' && <p className="report-empty" role="status">Загружаем сохранённые отчёты…</p>}
      {saved.listState.kind === 'failed' && (
        <ErrorNotice error={saved.listState.error} message="Не удалось загрузить сохранённые отчёты." onRetry={() => void saved.load()} />
      )}
      {saved.listState.kind === 'ready' && saved.reports.length === 0 && (
        <p className="report-empty">Сохранённых отчётов пока нет. Откройте отчёт ниже, настройте его и нажмите «Сохранить отчёт».</p>
      )}
      {saved.reports.length > 0 && (
        <ul className="saved-reports__list">
          {saved.reports.map((report) => (
            <SavedReportRow key={`${report.id}:${report.version}`} report={report} saved={saved} onOpen={onOpen} onDelete={setDeleting} />
          ))}
        </ul>
      )}
      {saved.notice !== '' && <p className="saved-reports__notice" role="status">{saved.notice}</p>}
      <SavedError saved={saved} />
      <ConfirmDialog
        open={deleting !== null}
        title="Удалить сохранённый отчёт?"
        description={deleting === null ? '' : `Отчёт «${deleting.name}» будет удалён без возможности восстановления. Выгрузки, уже построенные по нему, останутся в списке.`}
        confirmLabel="Удалить"
        onCancel={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting !== null) {
            void saved.remove(deleting)
          }
          setDeleting(null)
        }}
      />
    </section>
  )
}

type SaveReportDialogProps = {
  open: boolean
  onClose: () => void
  saved: SavedReportsModel
  definition: ReportPreviewRequest
  disabled: boolean
  current: SavedReport | undefined
  onSaved: (report: SavedReport) => void
}

export const SaveReportDialog = ({ open, onClose, saved, definition, disabled, current, onSaved }: SaveReportDialogProps) => {
  const [name, setName] = useState('')
  const [period, setPeriod] = useState<Period | ''>(current?.period ?? '')
  const nameId = useId()
  const periodId = useId()
  const snapshot = definition.kind === 'SNAPSHOT'
  const chosenPeriod = snapshot || period === '' ? null : period

  useEffect(() => {
    if (open) {
      setPeriod(current?.period ?? '')
    }
  }, [current, open])

  const finish = (result: SavedReport | null | undefined) => {
    if (result !== undefined) {
      if (result !== null) {
        onSaved(result)
      }
      setName('')
      onClose()
    }
  }

  const create = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const trimmed = name.trim()
    if (trimmed.length > 0 && !disabled) {
      finish(await saved.create(trimmed, definition, chosenPeriod))
    }
  }

  return (
    <CardDialog open={open} title="Сохранить отчёт" onClose={onClose}>
      <p className="reports__hint">{savedReportsHint}</p>
      <p className="reports__hint">Без сохранения текущий выбор живёт только в этой вкладке и удаляется при выходе из CRM.</p>
      {disabled && <p className="reports__problem" role="alert">Сохранить можно, когда выбор корректен: проверьте период и колонки.</p>}
      <form className="save-report" onSubmit={(event) => void create(event)}>
        {!snapshot && (
          <div className="save-report__field">
            <label htmlFor={periodId}>Период при открытии</label>
            <select id={periodId} value={period} onChange={(event) => setPeriod(periods.find((item) => item === event.target.value) ?? '')}>
              <option value="">Даты как выбраны</option>
              {periods.map((item) => <option key={item} value={item}>{periodTitles[item]}</option>)}
            </select>
          </div>
        )}
        {current !== undefined && (
          <div className="save-report__update">
            <p>Сейчас открыт отчёт «{current.name}».</p>
            <button
              type="button"
              disabled={disabled || saved.busy !== null}
              onClick={() => void saved.update(current, definition, chosenPeriod).then(finish)}
            >
              {saved.busy === 'update' ? 'Сохраняем…' : `Обновить «${current.name}»`}
            </button>
          </div>
        )}
        <div className="save-report__field">
          <label htmlFor={nameId}>Название нового отчёта</label>
          <input id={nameId} type="text" value={name} maxLength={200} onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="save-report__actions">
          <button type="submit" disabled={name.trim().length === 0 || disabled || saved.busy !== null}>
            {saved.busy === 'create' ? 'Сохраняем…' : current === undefined ? 'Сохранить' : 'Сохранить как новый'}
          </button>
          <button type="button" className="reports__secondary" onClick={onClose}>Отмена</button>
        </div>
      </form>
      <SavedError saved={saved} />
    </CardDialog>
  )
}
