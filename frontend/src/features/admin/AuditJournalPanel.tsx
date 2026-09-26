import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  ApiError,
  apiClient,
  type AuditCategory,
  type AuditEventListParams,
  type PageAuditEntry
} from '../../shared/api/client'
import { Pagination } from '../../shared/ui/Pagination'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import { saveFile, today } from './saveFile'
import { historyDetails } from '../enrolment/enrolmentShared'
import './security.css'

type JournalState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageAuditEntry }
  | { kind: 'failed'; requestId?: string }

type Filters = {
  from: string
  to: string
  actor: string
  object: string
  category: AuditCategory | ''
}

type ExportState =
  | { kind: 'idle' }
  | { kind: 'exporting' }
  | { kind: 'failed'; requestId?: string; message?: string }

const pageSize = 50

const categoryLabels: Record<AuditCategory, string> = {
  PROFILE: 'Профили',
  ASSIGNMENT: 'Назначения КАМ',
  ORGANIZATION: 'Переносы вузов',
  TEAM: 'Команды',
  SYNC: 'Синхронизация источников',
  DOWNLOAD: 'Скачивания',
  PERSONAL_DATA: 'Действия с ПДн',
  RETENTION: 'Сроки хранения',
  ACCOUNT: 'Учётные записи Keycloak',
  LEARNER: 'Слушатели'
}

const emptyFilters: Filters = { from: '', to: '', actor: '', object: '', category: '' }

const paramsOf = (filters: Filters): AuditEventListParams => ({
  from: filters.from || undefined,
  to: filters.to || undefined,
  actor: filters.actor.trim() || undefined,
  object: filters.object.trim() || undefined,
  category: filters.category || undefined
})

export const AuditJournalPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [draft, setDraft] = useState<Filters>(emptyFilters)
  const [filters, setFilters] = useState<Filters>(emptyFilters)
  const [pageIndex, setPageIndex] = useState(0)
  const [state, setState] = useState<JournalState>({ kind: 'loading' })
  const [exportState, setExportState] = useState<ExportState>({ kind: 'idle' })
  const requestVersion = useRef(0)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const load = useCallback(async (requestedFilters: Filters, requestedPage: number) => {
    const version = ++requestVersion.current
    setState({ kind: 'loading' })
    try {
      const page = await apiClient.listAuditEvents({ ...paramsOf(requestedFilters), page: requestedPage, size: pageSize })
      if (version === requestVersion.current) {
        setState({ kind: 'ready', page })
      }
    } catch (error) {
      if (version !== requestVersion.current || handleError(error)) {
        return
      }
      setState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [handleError])

  useEffect(() => {
    void load(filters, pageIndex)
    return () => {
      requestVersion.current += 1
    }
  }, [filters, load, pageIndex])

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setPageIndex(0)
    setFilters({ ...draft })
  }

  const reset = () => {
    setDraft(emptyFilters)
    setPageIndex(0)
    setFilters(emptyFilters)
  }

  const exportJournal = async (format: 'XLSX' | 'CSV') => {
    setExportState({ kind: 'exporting' })
    try {
      const blob = await apiClient.exportAuditEvents({ ...paramsOf(filters), format })
      saveFile(blob, `Журнал администратора ${today()}.${format.toLowerCase()}`)
      setExportState({ kind: 'idle' })
      void load(filters, pageIndex)
    } catch (error) {
      if (!handleError(error)) {
        setExportState({
          kind: 'failed',
          requestId: requestIdOf(error),
          message: error instanceof ApiError && error.code === 'REPORT_ROW_LIMIT' ? error.message : undefined
        })
      }
    }
  }

  return (
    <section className="security-panel" aria-labelledby="audit-journal-title" aria-busy={state.kind === 'loading'}>
      <div className="security-panel__header">
        <div>
          <p className="eyebrow">Администрирование и безопасность</p>
          <h2 id="audit-journal-title">Журнал администратора и безопасности</h2>
        </div>
        {state.kind === 'ready' && <p className="security-panel__total">Записей: {state.page.total}</p>}
      </div>
      <p className="security-panel__intro">
        Изменения профилей и команд, назначения КАМ, переносы вузов, запуски синхронизации, скачивания файлов и отчётов,
        действия с персональными данными и учётными записями Keycloak. Даты — по московскому времени.
      </p>

      <form className="security-filters" onSubmit={submit} aria-label="Фильтры журнала">
        <label>
          С даты
          <input type="date" value={draft.from} onChange={(event) => setDraft({ ...draft, from: event.target.value })} />
        </label>
        <label>
          По дату
          <input type="date" value={draft.to} onChange={(event) => setDraft({ ...draft, to: event.target.value })} />
        </label>
        <label>
          Автор
          <input
            value={draft.actor}
            maxLength={200}
            placeholder="Часть имени"
            onChange={(event) => setDraft({ ...draft, actor: event.target.value })}
          />
        </label>
        <label>
          Объект
          <input
            value={draft.object}
            maxLength={200}
            placeholder="Вуз, команда, файл, профиль"
            onChange={(event) => setDraft({ ...draft, object: event.target.value })}
          />
        </label>
        <label>
          Раздел
          <select
            value={draft.category}
            onChange={(event) => setDraft({ ...draft, category: event.target.value as Filters['category'] })}
          >
            <option value="">Все события</option>
            {(Object.keys(categoryLabels) as AuditCategory[]).map((category) => (
              <option key={category} value={category}>{categoryLabels[category]}</option>
            ))}
          </select>
        </label>
        <div className="security-actions">
          <button type="submit">Показать</button>
          <button type="button" className="button--secondary" onClick={reset}>Сбросить</button>
        </div>
      </form>

      <div className="security-actions">
        <button
          type="button"
          className="button--secondary"
          disabled={exportState.kind === 'exporting'}
          onClick={() => void exportJournal('XLSX')}
        >
          Выгрузить XLSX
        </button>
        <button
          type="button"
          className="button--secondary"
          disabled={exportState.kind === 'exporting'}
          onClick={() => void exportJournal('CSV')}
        >
          Выгрузить CSV
        </button>
        {exportState.kind === 'exporting' && <p role="status">Готовим файл…</p>}
      </div>
      {exportState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>{exportState.message ?? 'Не удалось выгрузить журнал. Повторите попытку.'}</p>
          {exportState.requestId && <p className="request-id">Request ID: {exportState.requestId}</p>}
        </div>
      )}

      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем журнал…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить журнал.</p>
          {state.requestId && <p className="request-id">Request ID: {state.requestId}</p>}
          <button type="button" onClick={() => void load(filters, pageIndex)}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && state.page.items.length === 0 && (
        <p className="organizations-message">Записей по этим условиям нет.</p>
      )}
      {state.kind === 'ready' && state.page.items.length > 0 && (
        <>
          <ol className="security-journal" aria-label="Записи журнала">
            {state.page.items.map((entry) => (
              <li key={`${entry.category}-${entry.id}`} className="security-journal__item">
                <p className="security-journal__meta">
                  <time dateTime={entry.occurredAt}>{formatDateTime(entry.occurredAt)}</time>
                  <span className="security-badge">{categoryLabels[entry.category]}</span>
                </p>
                <p className="security-journal__action">{entry.actionLabel}</p>
                <dl className="security-fields">
                  <div>
                    <dt>Автор</dt>
                    <dd>{entry.actorDisplayName}</dd>
                  </div>
                  {entry.objectName && (
                    <div>
                      <dt>Объект</dt>
                      <dd>{entry.objectName}</dd>
                    </div>
                  )}
                  {entry.details && (
                    <div>
                      <dt>Подробности</dt>
                      <dd>{historyDetails(entry.details)}</dd>
                    </div>
                  )}
                </dl>
                {entry.requestId && <p className="request-id">Request ID: {entry.requestId}</p>}
              </li>
            ))}
          </ol>
          <Pagination
            label="Страницы журнала"
            page={state.page.page}
            size={state.page.size}
            total={state.page.total}
            onChange={setPageIndex}
          />
        </>
      )}
    </section>
  )
}
