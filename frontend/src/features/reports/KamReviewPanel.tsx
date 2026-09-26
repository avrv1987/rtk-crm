import { type FormEvent, useCallback, useEffect, useState } from 'react'
import {
  ApiError,
  apiClient,
  type ReportColumn,
  type ReportManagerOption,
  type ReportPreview
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { handledAccessError } from '../interactions/workMarks'
import './kamReview.css'

type KamReviewPanelProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ManagersState =
  | { kind: 'loading' }
  | { kind: 'ready'; managers: ReportManagerOption[] }
  | { kind: 'failed'; error: unknown }

type Review = {
  managerName: string
  from: string
  to: string
  events: ReportPreview
  works: ReportPreview
}

type ReviewState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; review: Review }
  | { kind: 'failed'; error: unknown }

type Row = ReportPreview['items'][number]

const previewSize = 200
const eventColumns: ReportColumn[] = ['EVENT_AT', 'ORGANIZATION', 'INTERACTION', 'EVENT_TYPE', 'STAGE', 'COMMENT', 'AUTHOR']
const workColumns: ReportColumn[] = ['ORGANIZATION', 'INTERACTION', 'STAGE', 'NEXT_ACTION', 'NEXT_ACTION_AT', 'WAITING', 'PROBLEM', 'RISK']
const stepColumns: ReportColumn[] = ['ORGANIZATION', 'INTERACTION', 'STAGE', 'NEXT_ACTION', 'NEXT_ACTION_AT']
const markColumns: ReportColumn[] = ['ORGANIZATION', 'INTERACTION', 'WAITING', 'PROBLEM', 'RISK']
const dateColumns: ReadonlySet<ReportColumn> = new Set<ReportColumn>(['EVENT_AT', 'NEXT_ACTION_AT'])

const moscowDateTime = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Europe/Moscow' })
const shortDate = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'long' })

const isoDate = (date: Date) => [
  date.getFullYear(),
  String(date.getMonth() + 1).padStart(2, '0'),
  String(date.getDate()).padStart(2, '0')
].join('-')

const today = new Date()
const defaultFrom = isoDate(new Date(today.getFullYear(), today.getMonth(), 1))
const defaultTo = isoDate(today)

const cellText = (preview: ReportPreview, row: Row, column: ReportColumn) => {
  const value = row[column]
  if (value === null || value === undefined) {
    return preview.columns.find((item) => item.id === column)?.emptyText ?? ''
  }
  if (dateColumns.has(column)) {
    const date = new Date(String(value))
    return Number.isNaN(date.getTime()) ? String(value) : moscowDateTime.format(date)
  }
  return String(value)
}

const hasMark = (row: Row) => markColumns.slice(2).some((column) => row[column] !== null && row[column] !== undefined)

const ReviewTable = ({ preview, rows, columns, label }: { preview: ReportPreview; rows: Row[]; columns: ReportColumn[]; label: string }) => (
  <div className="reports__table-scroll" role="region" aria-label={label} tabIndex={0}>
    <table>
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column} scope="col">{preview.columns.find((item) => item.id === column)?.title ?? column}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={index}>
            {columns.map((column) => <td key={column}>{cellText(preview, row, column)}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
  </div>
)

const Truncated = ({ preview, what }: { preview: ReportPreview; what: string }) => (
  preview.total > preview.items.length
    ? <p className="reports__hint">Показаны первые {preview.items.length} из {preview.total} {what}; полный список — в отчёте выше.</p>
    : null
)

export const KamReviewPanel = ({ onSessionExpired, onProfileUnavailable }: KamReviewPanelProps) => {
  const [managersState, setManagersState] = useState<ManagersState>({ kind: 'loading' })
  const [managerId, setManagerId] = useState('')
  const [from, setFrom] = useState(defaultFrom)
  const [to, setTo] = useState(defaultTo)
  const [reviewState, setReviewState] = useState<ReviewState>({ kind: 'idle' })

  const loadManagers = useCallback(async () => {
    setManagersState({ kind: 'loading' })
    try {
      const managers = await apiClient.listReportManagers()
      setManagersState({ kind: 'ready', managers })
      setManagerId((current) => current || (managers.find((manager) => manager.active) ?? managers[0])?.id || '')
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setManagersState({ kind: 'failed', error })
      }
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void loadManagers()
  }, [loadManagers])

  const managers = managersState.kind === 'ready' ? managersState.managers : []

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const manager = managers.find((item) => item.id === managerId)
    if (manager === undefined || from === '' || to === '' || from > to) {
      return
    }
    setReviewState({ kind: 'loading' })
    try {
      const filters = { managerIds: [manager.id] }
      const [events, works] = await Promise.all([
        apiClient.previewReport({ kind: 'EVENTS', from, to, filters, columns: eventColumns }, 0, previewSize),
        apiClient.previewReport({ kind: 'PORTFOLIO', filters: { ...filters, workStatuses: ['ACTIVE'] }, columns: workColumns }, 0, previewSize)
      ])
      setReviewState({ kind: 'ready', review: { managerName: manager.displayName, from, to, events, works } })
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setReviewState({ kind: 'failed', error })
      }
    }
  }

  const review = reviewState.kind === 'ready' ? reviewState.review : null
  const markedWorks = review?.works.items.filter(hasMark) ?? []

  return (
    <section className="reports__preview kam-review" aria-labelledby="kam-review-title">
      <h3 id="kam-review-title">Разбор КАМ</h3>
      <p>События за период, открытые шаги и отмеченные риски одного КАМ на одной странице — для регулярного разбора с руководителем.</p>
      {managersState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>Не удалось загрузить список КАМ.</p>
          {managersState.error instanceof ApiError && <SupportDetails requestId={managersState.error.requestId} code={managersState.error.code} />}
          <button type="button" onClick={() => void loadManagers()}>Повторить</button>
        </div>
      )}
      <form className="reports__settings" onSubmit={(event) => void submit(event)}>
        <label>
          КАМ
          <select value={managerId} disabled={managers.length === 0} onChange={(event) => setManagerId(event.target.value)}>
            {managers.length === 0 && <option value="">{managersState.kind === 'loading' ? 'Загружаем…' : 'Нет доступных КАМ'}</option>}
            {managers.map((manager) => (
              <option key={manager.id} value={manager.id}>{manager.displayName}{manager.active ? '' : ' (не активен)'}</option>
            ))}
          </select>
        </label>
        <label>
          Период с
          <input type="date" required value={from} max={to || undefined} onChange={(event) => setFrom(event.target.value)} />
        </label>
        <label>
          по
          <input type="date" required value={to} min={from || undefined} onChange={(event) => setTo(event.target.value)} />
        </label>
        <div className="reports__actions">
          <button type="submit" disabled={managerId === '' || reviewState.kind === 'loading'}>
            {reviewState.kind === 'loading' ? 'Собираем разбор…' : 'Показать разбор'}
          </button>
        </div>
      </form>
      {reviewState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>Не удалось собрать разбор. Повторите попытку.</p>
          {reviewState.error instanceof ApiError && <SupportDetails requestId={reviewState.error.requestId} code={reviewState.error.code} />}
        </div>
      )}
      {review !== null && (
        <div className="kam-review__result" aria-live="polite">
          <p className="kam-review__summary">
            <strong>{review.managerName}</strong>, {shortDate.format(new Date(`${review.from}T00:00:00`))} —{' '}
            {shortDate.format(new Date(`${review.to}T00:00:00`))}: событий {review.events.total}, активных работ {review.works.total},
            {' '}с отметками ожидания, проблемы или риска {markedWorks.length}.
          </p>
          <section aria-labelledby="kam-review-events">
            <h4 id="kam-review-events">События за период</h4>
            {review.events.total === 0
              ? <p>За период событий нет.</p>
              : <ReviewTable preview={review.events} rows={review.events.items} columns={eventColumns} label="События КАМ за период" />}
            <Truncated preview={review.events} what="событий" />
          </section>
          <section aria-labelledby="kam-review-steps">
            <h4 id="kam-review-steps">Открытые шаги</h4>
            {review.works.total === 0
              ? <p>Активных работ нет.</p>
              : <ReviewTable preview={review.works} rows={review.works.items} columns={stepColumns} label="Открытые шаги КАМ" />}
            <Truncated preview={review.works} what="работ" />
          </section>
          <section aria-labelledby="kam-review-marks">
            <h4 id="kam-review-marks">Риски, проблемы и ожидания</h4>
            {markedWorks.length === 0
              ? <p>Отмеченных рисков, проблем и ожиданий нет.</p>
              : <ReviewTable preview={review.works} rows={markedWorks} columns={markColumns} label="Отмеченные риски КАМ" />}
          </section>
        </div>
      )}
    </section>
  )
}
