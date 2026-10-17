import { getModules } from '../../shared/modules'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  ApiError,
  apiClient,
  type AuditEventListParams,
  type PageAuditEntry
} from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { Pagination } from '../../shared/ui/Pagination'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { terms } from '../../shared/ui/terms'
import { historyDetails } from '../enrolment/enrolmentShared'
import { handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import { actionText, appliedFilterCount, categoryLabels, categoryOptions, emptyAuditFilters, type AuditFilters } from './auditJournal'
import { saveFile, today } from './saveFile'
import './adminUi.css'
import './adminSecurity.css'

type JournalState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageAuditEntry }
  | { kind: 'failed'; requestId?: string }

type ExportState =
  | { kind: 'idle' }
  | { kind: 'exporting' }
  | { kind: 'failed'; requestId?: string; message?: string }

const pageSize = 50

const paramsOf = (filters: AuditFilters): AuditEventListParams => ({
  from: filters.from || undefined,
  to: filters.to || undefined,
  actor: filters.actor.trim() || undefined,
  object: filters.object.trim() || undefined,
  category: filters.category || undefined
})

export const AuditJournalPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [draft, setDraft] = useState<AuditFilters>(emptyAuditFilters)
  const [filters, setFilters] = useState<AuditFilters>(emptyAuditFilters)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [pageIndex, setPageIndex] = useState(0)
  const [state, setState] = useState<JournalState>({ kind: 'loading' })
  const [exportState, setExportState] = useState<ExportState>({ kind: 'idle' })
  const requestVersion = useRef(0)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const load = useCallback(async (requestedFilters: AuditFilters, requestedPage: number) => {
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
    setFiltersOpen(false)
  }

  const reset = () => {
    setDraft(emptyAuditFilters)
    setPageIndex(0)
    setFilters(emptyAuditFilters)
  }

  const exportJournal = async (format: 'XLSX' | 'CSV') => {
    if (exportState.kind === 'exporting') {
      return
    }
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

  const applied = appliedFilterCount(filters)

  return (
    <section className="admin-page" aria-labelledby="audit-journal-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="audit-journal-title">Журнал</h2>
        <HintButton label="О разделе">
          Изменения профилей и команд, назначения КАМ, переносы вузов, запуски синхронизации, скачивания файлов и отчётов,
          действия с персональными данными и учётными записями Keycloak, входы и выходы. Даты — по московскому времени.
        </HintButton>
        {state.kind === 'ready' && <span className="page-title__count">Записей: {state.page.total}</span>}
        <span className="page-title__spacer" />
        <ActionMenu
          label="Выгрузить журнал"
          toggle={`${terms.export} ▾`}
          items={[
            { label: 'Выгрузить XLSX', onSelect: () => void exportJournal('XLSX') },
            { label: 'Выгрузить CSV', onSelect: () => void exportJournal('CSV') }
          ]}
        />
      </div>
      {exportState.kind === 'exporting' && <p className="admin-form__hint" role="status">Готовим файл…</p>}
      {exportState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>{exportState.message ?? 'Не удалось выгрузить журнал. Повторите попытку.'}</p>
          <SupportDetails requestId={exportState.requestId} />
        </div>
      )}

      <form className="audit-filters" onSubmit={submit} aria-label="Фильтры журнала">
        <button
          type="button"
          className="button--secondary audit-filters__toggle"
          aria-expanded={filtersOpen}
          onClick={() => setFiltersOpen((value) => !value)}
        >
          {applied > 0 ? `${terms.filters} · ${applied}` : terms.filters}
        </button>
        <div className="audit-filters__fields" data-open={filtersOpen}>
          <label>
            С даты
            <input type="date" value={draft.from} onChange={(event) => setDraft({ ...draft, from: event.target.value })} />
          </label>
          <label>
            По дату
            <input type="date" value={draft.to} onChange={(event) => setDraft({ ...draft, to: event.target.value })} />
          </label>
          <label>
            Раздел
            <select
              value={draft.category}
              onChange={(event) => setDraft({ ...draft, category: event.target.value as AuditFilters['category'] })}
            >
              <option value="">Все события</option>
              {categoryOptions(getModules().enrolment).map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
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
          <div className="audit-filters__actions">
            <button type="submit">Показать</button>
            <button type="button" className="button--secondary" onClick={reset}>Сбросить</button>
          </div>
        </div>
      </form>

      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем журнал…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить журнал.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" className="button--secondary" onClick={() => void load(filters, pageIndex)}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && state.page.items.length === 0 && (
        <EmptyState
          text="Записей по этим условиям нет."
          action={applied > 0 ? <button type="button" className="button--secondary" onClick={reset}>{terms.resetFilters}</button> : undefined}
        />
      )}
      {state.kind === 'ready' && state.page.items.length > 0 && (
        <>
          <div className="admin-table-wrap">
            <table className="admin-table audit-table" aria-label="Записи журнала">
              <thead>
                <tr>
                  <th scope="col">Время (МСК)</th>
                  <th scope="col">Раздел</th>
                  <th scope="col">Действие</th>
                  <th scope="col">Автор</th>
                  <th scope="col">Объект</th>
                </tr>
              </thead>
              <tbody>
                {state.page.items.map((entry) => {
                  const details = historyDetails(entry.details)
                  return (
                    <tr key={`${entry.category}-${entry.id}`}>
                      <td className="admin-table__cell-main audit-table__time">
                        <time dateTime={entry.occurredAt}>{formatMoscowDateTime(entry.occurredAt)}</time>
                      </td>
                      <td data-label="Раздел"><span className="admin-chip">{categoryLabels[entry.category]}</span></td>
                      <td className="admin-table__cell-main">
                        <span className="admin-table__main">{actionText(entry)}</span>
                        {details && <span className="admin-table__sub">{details}</span>}
                        <SupportDetails requestId={entry.requestId ?? undefined} />
                      </td>
                      <td data-label="Автор">{entry.actorDisplayName}</td>
                      <td data-label="Объект">{entry.objectName ?? '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
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
