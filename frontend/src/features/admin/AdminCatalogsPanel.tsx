import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type AdminCatalogEntry,
  type CatalogChangeEvent,
  type CatalogEntityType,
  type CatalogKind,
  type PageAdminCatalogEntry,
  type PageCatalogChangeEvent
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { Pagination } from '../../shared/ui/Pagination'
import { commandErrorMessage, formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'

type Tab = CatalogKind | 'journal'

type EntryState = 'ACTIVE' | 'ARCHIVED' | 'ALL'

type ListState<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; page: T }
  | { kind: 'failed'; requestId?: string }

type EntryCommand = {
  entry: AdminCatalogEntry
  mode: 'rename' | 'archive'
  name: string
  confirming: boolean
  saving: boolean
  error?: unknown
  idempotencyKey: string | null
}

const kinds: Array<{ kind: CatalogKind; label: string; single: string; parent?: CatalogKind; parentLabel?: string }> = [
  { kind: 'directions', label: 'ИТ-направления', single: 'направление' },
  { kind: 'programs', label: 'ИТ-программы', single: 'программу', parent: 'directions', parentLabel: 'ИТ-направление' },
  { kind: 'vendors', label: 'Вендоры', single: 'вендора' },
  { kind: 'products', label: 'ИТ-продукты', single: 'продукт', parent: 'vendors', parentLabel: 'Вендор' }
]

const entityLabels: Record<CatalogEntityType, string> = {
  ORGANIZATION: 'Организация',
  TEAM: 'Команда',
  DIRECTION: 'ИТ-направление',
  PROGRAM: 'ИТ-программа',
  VENDOR: 'Вендор',
  PRODUCT: 'ИТ-продукт',
  AGREEMENT: 'Договор'
}

const actionLabels: Record<CatalogChangeEvent['action'], string> = {
  CREATE: 'Создание',
  REQUEST: 'Заявка КАМ',
  APPROVE: 'Подтверждение',
  REJECT: 'Отклонение',
  UPDATE: 'Изменение',
  ARCHIVE: 'Архивирование',
  RESTORE: 'Восстановление'
}

const pageSize = 25
const searchDelayMilliseconds = 300

const errorBlock = (error: unknown) => (
  <div className="interaction-command-error" role="alert">
    <p>{commandErrorMessage(error)}</p>
    {error instanceof ApiError && <p className="request-id">Request ID: {error.requestId}</p>}
  </div>
)

export const AdminCatalogsPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [tab, setTab] = useState<Tab>('directions')
  const [searchText, setSearchText] = useState('')
  const [search, setSearch] = useState('')
  const [entryState, setEntryState] = useState<EntryState>('ACTIVE')
  const [pageIndex, setPageIndex] = useState(0)
  const [entries, setEntries] = useState<ListState<PageAdminCatalogEntry>>({ kind: 'loading' })
  const [events, setEvents] = useState<ListState<PageCatalogChangeEvent>>({ kind: 'loading' })
  const [entityFilter, setEntityFilter] = useState<CatalogEntityType | ''>('')
  const [parents, setParents] = useState<AdminCatalogEntry[]>([])
  const [newName, setNewName] = useState('')
  const [newParentId, setNewParentId] = useState('')
  const [creating, setCreating] = useState<{ saving: boolean; error?: unknown; key: string | null }>({ saving: false, key: null })
  const [command, setCommand] = useState<EntryCommand | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const requestVersion = useRef(0)
  const kind = kinds.find((item) => item.kind === tab)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const loadEntries = useCallback(async () => {
    if (tab === 'journal') {
      return
    }
    const version = ++requestVersion.current
    setEntries({ kind: 'loading' })
    try {
      const page = await apiClient.listAdminCatalogEntries(tab, { page: pageIndex, size: pageSize, q: search, state: entryState })
      if (version === requestVersion.current) {
        setEntries({ kind: 'ready', page })
      }
    } catch (error) {
      if (version === requestVersion.current && !handleError(error)) {
        setEntries({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }, [entryState, handleError, pageIndex, search, tab])

  const loadEvents = useCallback(async () => {
    if (tab !== 'journal') {
      return
    }
    const version = ++requestVersion.current
    setEvents({ kind: 'loading' })
    try {
      const page = await apiClient.listCatalogChangeEvents({
        page: pageIndex,
        size: pageSize,
        entityType: entityFilter === '' ? undefined : entityFilter
      })
      if (version === requestVersion.current) {
        setEvents({ kind: 'ready', page })
      }
    } catch (error) {
      if (version === requestVersion.current && !handleError(error)) {
        setEvents({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }, [entityFilter, handleError, pageIndex, tab])

  useEffect(() => {
    void loadEntries()
    void loadEvents()
  }, [loadEntries, loadEvents])

  useEffect(() => {
    const parentKind = kind?.parent
    if (parentKind === undefined) {
      setParents([])
      return
    }
    apiClient.listAdminCatalogEntries(parentKind, { page: 0, size: 100, state: 'ACTIVE' })
      .then((page) => setParents(page.items))
      .catch((error: unknown) => {
        if (!handleError(error)) {
          setParents([])
        }
      })
  }, [handleError, kind?.parent])

  useEffect(() => {
    if (searchText.trim() === search) {
      return
    }
    const timer = window.setTimeout(() => {
      setSearch(searchText.trim())
      setPageIndex(0)
    }, searchDelayMilliseconds)
    return () => window.clearTimeout(timer)
  }, [search, searchText])

  const selectTab = (next: Tab) => {
    setTab(next)
    setPageIndex(0)
    setSearchText('')
    setSearch('')
    setNewName('')
    setNewParentId('')
    setCreating({ saving: false, key: null })
    setCommand(null)
    setMessage(null)
  }

  const submitCreate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (kind === undefined || creating.saving || newName.trim().length === 0 || (kind.parent !== undefined && newParentId === '')) {
      return
    }
    const key = creating.key ?? createIdempotencyKey()
    setCreating({ saving: true, key })
    setMessage(null)
    try {
      const created = await apiClient.createAdminCatalogEntry(kind.kind, {
        name: newName.trim(),
        parentId: kind.parent === undefined ? null : newParentId
      }, key)
      setCreating({ saving: false, key: null })
      setNewName('')
      setMessage(`«${created.name}» добавлено и сразу доступно для выбора.`)
      await loadEntries()
    } catch (error) {
      if (!handleError(error)) {
        setCreating({ saving: false, error, key })
      }
    }
  }

  const submitCommand = async () => {
    if (kind === undefined || command === null || command.saving) {
      return
    }
    const key = command.idempotencyKey ?? createIdempotencyKey()
    setCommand({ ...command, confirming: false, saving: true, error: undefined, idempotencyKey: key })
    try {
      const changed = await apiClient.updateAdminCatalogEntry(kind.kind, command.entry.id, command.mode === 'rename'
        ? { name: command.name.trim(), version: command.entry.version }
        : { archived: !command.entry.archived, version: command.entry.version }, key)
      setCommand(null)
      setMessage(command.mode === 'rename'
        ? `Новое название «${changed.name}» сохранено и уже видно пользователям.`
        : changed.archived
          ? `«${changed.name}» в архиве: не предлагается для новых взаимодействий, в истории и отчётах остаётся.`
          : `«${changed.name}» восстановлено.`)
      await loadEntries()
    } catch (error) {
      if (!handleError(error)) {
        setCommand((current) => (current === null ? current : { ...current, saving: false, error }))
      }
    }
  }

  return (
    <section className="admin-profiles admin-catalogs" aria-labelledby="admin-catalogs-title">
      <div className="admin-profiles__header">
        <div>
          <p className="eyebrow">Администрирование</p>
          <h2 id="admin-catalogs-title">Справочники</h2>
        </div>
      </div>
      <p className="admin-profiles__intro">
        Добавление, переименование и архивирование направлений, программ, вендоров и продуктов без Excel. Дубль по названию без учёта регистра не создаётся; архивная запись не предлагается для новых взаимодействий, но остаётся в карточках и отчётах. Каждое изменение — в журнале. Организации ведутся в блоке «Организации и команды».
      </p>
      <div className="admin-catalogs__tabs" role="group" aria-label="Справочник">
        {kinds.map((item) => (
          <button
            key={item.kind}
            type="button"
            className={tab === item.kind ? undefined : 'button--secondary'}
            aria-pressed={tab === item.kind}
            onClick={() => selectTab(item.kind)}
          >
            {item.label}
          </button>
        ))}
        <button
          type="button"
          className={tab === 'journal' ? undefined : 'button--secondary'}
          aria-pressed={tab === 'journal'}
          onClick={() => selectTab('journal')}
        >
          Журнал изменений
        </button>
      </div>
      {message !== null && <p className="notice" role="status">{message}</p>}

      {kind !== undefined && (
        <>
          <form className="admin-profile-form" onSubmit={(event) => void submitCreate(event)} aria-label={`Добавить: ${kind.label}`}>
            <label>
              Название *
              <input
                value={newName}
                maxLength={200}
                disabled={creating.saving}
                onChange={(event) => {
                  setNewName(event.target.value)
                  setCreating({ saving: false, key: null })
                }}
              />
            </label>
            {kind.parent !== undefined && (
              <label>
                {kind.parentLabel} *
                <select
                  value={newParentId}
                  disabled={creating.saving}
                  onChange={(event) => {
                    setNewParentId(event.target.value)
                    setCreating({ saving: false, key: null })
                  }}
                >
                  <option value="">Выберите</option>
                  {parents.map((parent) => <option key={parent.id} value={parent.id}>{parent.name}</option>)}
                </select>
              </label>
            )}
            <div className="admin-profiles__confirmation-actions">
              <button
                type="submit"
                disabled={creating.saving || newName.trim().length === 0 || (kind.parent !== undefined && newParentId === '')}
              >
                {creating.saving ? 'Добавляем…' : `Добавить ${kind.single}`}
              </button>
            </div>
            {creating.error !== undefined && errorBlock(creating.error)}
          </form>

          <form className="organizations-filters" role="search" aria-label={`Отбор: ${kind.label}`} onSubmit={(event) => event.preventDefault()}>
            <label>
              Поиск по названию
              <input type="search" value={searchText} maxLength={200} onChange={(event) => setSearchText(event.target.value)} />
            </label>
            <label>
              Состояние
              <select
                value={entryState}
                onChange={(event) => {
                  setEntryState(event.target.value as EntryState)
                  setPageIndex(0)
                }}
              >
                <option value="ACTIVE">Действующие</option>
                <option value="ARCHIVED">Архив</option>
                <option value="ALL">Все</option>
              </select>
            </label>
          </form>

          {entries.kind === 'loading' && <p className="organizations-message" role="status">Загружаем справочник…</p>}
          {entries.kind === 'failed' && (
            <div className="organizations-message organizations-message--error" role="alert">
              <p>Не удалось загрузить справочник. Повторите попытку.</p>
              {entries.requestId && <p className="request-id">Request ID: {entries.requestId}</p>}
              <button type="button" onClick={() => void loadEntries()}>Повторить</button>
            </div>
          )}
          {entries.kind === 'ready' && entries.page.items.length === 0 && (
            <p className="organizations-message">Записей нет.</p>
          )}
          {entries.kind === 'ready' && entries.page.items.length > 0 && (
            <ul className="admin-profiles__list" aria-label={kind.label}>
              {entries.page.items.map((entry) => (
                <li key={entry.id} className="admin-profiles__item">
                  <div className="admin-profiles__identity">
                    {command?.entry.id === entry.id && command.mode === 'rename' ? (
                      <form
                        className="admin-team-form"
                        onSubmit={(event) => {
                          event.preventDefault()
                          void submitCommand()
                        }}
                      >
                        <label>
                          Новое название «{entry.name}»
                          <input
                            value={command.name}
                            maxLength={200}
                            disabled={command.saving}
                            onChange={(event) => setCommand({ ...command, name: event.target.value, idempotencyKey: null, error: undefined })}
                          />
                        </label>
                        <div className="admin-profiles__confirmation-actions">
                          <button
                            type="submit"
                            disabled={command.saving || command.name.trim().length === 0 || command.name.trim() === entry.name}
                          >
                            {command.saving ? 'Сохраняем…' : 'Сохранить'}
                          </button>
                          <button type="button" className="button--secondary" disabled={command.saving} onClick={() => setCommand(null)}>
                            Отмена
                          </button>
                        </div>
                      </form>
                    ) : (
                      <h3>
                        {entry.name}
                        {entry.archived && <span className="admin-profiles__status admin-profiles__status--blocked">Архивирована</span>}
                      </h3>
                    )}
                    {entry.parentName !== null && (
                      <dl className="admin-profiles__fields">
                        <div>
                          <dt>{kind.parentLabel}</dt>
                          <dd>{entry.parentName}</dd>
                        </div>
                      </dl>
                    )}
                    {command?.entry.id === entry.id && command.error !== undefined && errorBlock(command.error)}
                  </div>
                  {!(command?.entry.id === entry.id && command.mode === 'rename') && (
                    <div className="admin-profiles__actions admin-catalogs__actions">
                      {!entry.archived && (
                        <button
                          type="button"
                          className="button--secondary"
                          onClick={() => setCommand({ entry, mode: 'rename', name: entry.name, confirming: false, saving: false, idempotencyKey: null })}
                        >
                          Переименовать
                        </button>
                      )}
                      <button
                        type="button"
                        className="button--secondary"
                        onClick={() => setCommand({ entry, mode: 'archive', name: entry.name, confirming: true, saving: false, idempotencyKey: null })}
                      >
                        {entry.archived ? 'Восстановить' : 'Архивировать'}
                      </button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
          {entries.kind === 'ready' && (
            <Pagination
              label={`Страницы: ${kind.label}`}
              page={entries.page.page}
              size={pageSize}
              total={entries.page.total}
              onChange={setPageIndex}
            />
          )}
        </>
      )}

      {tab === 'journal' && (
        <>
          <form className="organizations-filters" aria-label="Отбор журнала" onSubmit={(event) => event.preventDefault()}>
            <label>
              Что менялось
              <select
                value={entityFilter}
                onChange={(event) => {
                  setEntityFilter(event.target.value as CatalogEntityType | '')
                  setPageIndex(0)
                }}
              >
                <option value="">Все записи</option>
                {(Object.keys(entityLabels) as CatalogEntityType[]).map((type) => (
                  <option key={type} value={type}>{entityLabels[type]}</option>
                ))}
              </select>
            </label>
          </form>
          {events.kind === 'loading' && <p className="organizations-message" role="status">Загружаем журнал…</p>}
          {events.kind === 'failed' && (
            <div className="organizations-message organizations-message--error" role="alert">
              <p>Не удалось загрузить журнал. Повторите попытку.</p>
              {events.requestId && <p className="request-id">Request ID: {events.requestId}</p>}
              <button type="button" onClick={() => void loadEvents()}>Повторить</button>
            </div>
          )}
          {events.kind === 'ready' && events.page.items.length === 0 && (
            <p className="organizations-message">Изменений пока нет.</p>
          )}
          {events.kind === 'ready' && events.page.items.length > 0 && (
            <ol className="admin-catalogs__journal" aria-label="Журнал изменений справочников">
              {events.page.items.map((event) => (
                <li key={event.id}>
                  <p>
                    <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
                    {' · '}{event.actorDisplayName}
                  </p>
                  <p>
                    <strong>{actionLabels[event.action]}</strong> · {entityLabels[event.entityType]} «{event.entityName}»
                  </p>
                  {event.changes !== null && <p className="admin-profile-form__hint">{event.changes}</p>}
                </li>
              ))}
            </ol>
          )}
          {events.kind === 'ready' && (
            <Pagination label="Страницы журнала" page={events.page.page} size={pageSize} total={events.page.total} onChange={setPageIndex} />
          )}
        </>
      )}

      <ConfirmDialog
        open={command?.mode === 'archive' && command.confirming}
        title={command === null ? '' : `${command.entry.archived ? 'Восстановить' : 'Архивировать'} «${command.entry.name}»?`}
        description={command?.entry.archived === true
          ? 'Запись снова станет доступна для выбора в новых взаимодействиях.'
          : 'Запись не будет предлагаться для новых взаимодействий; в существующих карточках и отчётах она останется с меткой «Архивирована».'}
        confirmLabel={command?.entry.archived === true ? 'Восстановить' : 'Архивировать'}
        onConfirm={() => void submitCommand()}
        onCancel={() => setCommand(null)}
      />
    </section>
  )
}
