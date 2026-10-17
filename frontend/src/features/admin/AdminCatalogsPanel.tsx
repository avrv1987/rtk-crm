import { Fragment, type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  apiClient,
  createIdempotencyKey,
  type AdminCatalogEntry,
  type CatalogChangeEvent,
  type CatalogEntityType,
  type CatalogKind,
  type PageAdminCatalogEntry,
  type PageCatalogChangeEvent
} from '../../shared/api/client'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { Pagination } from '../../shared/ui/Pagination'
import { Segmented } from '../../shared/ui/Segmented'
import { Toast } from '../../shared/ui/Toast'
import { ActivityKindsPanel } from '../agreements/ActivityKindsPanel'
import { CardDialog } from '../interactions/cardUi'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import { catalogAddLabels, catalogTabLabels, catalogTabSlugs, type CatalogsTabSlug } from './catalogTabs'
import { CatalogCommandError, CatalogLoadFailure } from './catalogUi'
import { VendorContactsPanel } from './VendorContactsPanel'
import './adminUi.css'
import './catalogs.css'

type CatalogsPanelProps = SessionHandlers & {
  tab?: CatalogsTabSlug
  onTabChange?: (slug: CatalogsTabSlug) => void
}

type EntryState = 'ACTIVE' | 'ARCHIVED' | 'ALL'

type ListState<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; page: T }
  | { kind: 'failed'; requestId?: string }

type EntryCommand = {
  entry: AdminCatalogEntry
  mode: 'rename' | 'archive' | 'market'
  name: string
  confirming: boolean
  saving: boolean
  error?: unknown
  idempotencyKey: string | null
}

const kinds: Array<{ kind: CatalogKind; label: string; parent?: CatalogKind; parentLabel?: string }> = [
  { kind: 'directions', label: catalogTabLabels.directions },
  { kind: 'programs', label: catalogTabLabels.programs, parent: 'directions', parentLabel: 'ИТ-направление' },
  { kind: 'vendors', label: catalogTabLabels.vendors },
  { kind: 'products', label: catalogTabLabels.products, parent: 'vendors', parentLabel: 'Вендор' }
]

const stateOptions: Array<{ value: EntryState; label: string }> = [
  { value: 'ACTIVE', label: 'Действующие' },
  { value: 'ARCHIVED', label: 'Архив' },
  { value: 'ALL', label: 'Все' }
]

const entityLabels: Record<CatalogEntityType, string> = {
  ORGANIZATION: 'Организация',
  TEAM: 'Команда',
  DIRECTION: 'ИТ-направление',
  PROGRAM: 'ИТ-программа',
  VENDOR: 'Вендор',
  PRODUCT: 'ИТ-продукт',
  AGREEMENT: 'Договор',
  VENDOR_CONTACT: 'Контакт вендора'
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

export const AdminCatalogsPanel = ({ tab: tabProp, onTabChange, onSessionExpired, onProfileUnavailable }: CatalogsPanelProps) => {
  const [innerTab, setInnerTab] = useState<CatalogsTabSlug>('directions')
  const tab = tabProp ?? innerTab
  const [shownTab, setShownTab] = useState(tab)
  const [searchText, setSearchText] = useState('')
  const [search, setSearch] = useState('')
  const [entryState, setEntryState] = useState<EntryState>('ACTIVE')
  const [pageIndex, setPageIndex] = useState(0)
  const [entries, setEntries] = useState<ListState<PageAdminCatalogEntry>>({ kind: 'loading' })
  const [events, setEvents] = useState<ListState<PageCatalogChangeEvent>>({ kind: 'loading' })
  const [entityFilter, setEntityFilter] = useState<CatalogEntityType | ''>('')
  const [parents, setParents] = useState<AdminCatalogEntry[]>([])
  const [addOpen, setAddOpen] = useState(false)
  const [newName, setNewName] = useState('')
  const [newParentId, setNewParentId] = useState('')
  const [creating, setCreating] = useState<{ saving: boolean; error?: unknown; key: string | null }>({ saving: false, key: null })
  const [command, setCommand] = useState<EntryCommand | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [contactsVendorId, setContactsVendorId] = useState<string | null>(null)
  const [kindsAddRequest, setKindsAddRequest] = useState(0)
  const requestVersion = useRef(0)
  const kind = kinds.find((item) => item.kind === tab)
  const addLabel = catalogAddLabels[tab]

  // Смена вкладки (кнопкой или адресом) сбрасывает поиск, окна и сообщение; делаем это при отрисовке, чтобы не грузить список дважды.
  if (shownTab !== tab) {
    setShownTab(tab)
    setPageIndex(0)
    setSearchText('')
    setSearch('')
    setNewName('')
    setNewParentId('')
    setAddOpen(false)
    setCreating({ saving: false, key: null })
    setCommand(null)
    setMessage(null)
    setContactsVendorId(null)
  }

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const catalogKind = kind?.kind
  const loadEntries = useCallback(async () => {
    if (catalogKind === undefined) {
      return
    }
    const version = ++requestVersion.current
    setEntries({ kind: 'loading' })
    try {
      const page = await apiClient.listAdminCatalogEntries(catalogKind, { page: pageIndex, size: pageSize, q: search, state: entryState })
      if (version === requestVersion.current) {
        setEntries({ kind: 'ready', page })
      }
    } catch (error) {
      if (version === requestVersion.current && !handleError(error)) {
        setEntries({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }, [catalogKind, entryState, handleError, pageIndex, search])

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

  const selectTab = (next: CatalogsTabSlug) => {
    setInnerTab(next)
    onTabChange?.(next)
  }

  const openAdd = () => {
    if (tab === 'activity-kinds') {
      setKindsAddRequest((value) => value + 1)
      return
    }
    setNewName('')
    setNewParentId('')
    setCreating({ saving: false, key: null })
    setAddOpen(true)
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
      setAddOpen(false)
      setMessage(`«${created.name}» добавлено и сразу доступно для выбора.`)
      await loadEntries()
    } catch (error) {
      if (!handleError(error)) {
        setCreating({ saving: false, error, key })
      }
    }
  }

  const submitCommand = async (override?: EntryCommand) => {
    const active = override ?? command
    if (kind === undefined || active === null || active.saving) {
      return
    }
    const key = active.idempotencyKey ?? createIdempotencyKey()
    setCommand({ ...active, confirming: false, saving: true, error: undefined, idempotencyKey: key })
    try {
      const payload = active.mode === 'rename'
        ? { name: active.name.trim(), version: active.entry.version }
        : active.mode === 'market'
          ? { laborMarket: active.entry.laborMarket !== true, version: active.entry.version }
          : { archived: !active.entry.archived, version: active.entry.version }
      const changed = await apiClient.updateAdminCatalogEntry(kind.kind, active.entry.id, payload, key)
      setCommand(null)
      setMessage(active.mode === 'rename'
        ? `Новое название «${changed.name}» сохранено и уже видно пользователям.`
        : active.mode === 'market'
          ? changed.laborMarket === true
            ? `Направление «${changed.name}»: спрос рынка труда учитывается в отчёте «Востребованность программ». Сопоставление с источником — в разделе «Источники данных».`
            : `Направление «${changed.name}»: спрос рынка труда в отчёте больше не учитывается.`
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

  const rowMenu = (entry: AdminCatalogEntry): ActionMenuItem[] => [
    ...(kind?.kind === 'vendors' && contactsVendorId !== entry.id
      ? [{ label: 'Контакты', onSelect: () => setContactsVendorId(entry.id) }]
      : []),
    ...(entry.archived
      ? []
      : [{
        label: 'Переименовать',
        onSelect: () => setCommand({ entry, mode: 'rename', name: entry.name, confirming: false, saving: false, idempotencyKey: null })
      }]),
    {
      label: entry.archived ? 'Восстановить' : 'Архивировать',
      onSelect: () => setCommand({ entry, mode: 'archive', name: entry.name, confirming: true, saving: false, idempotencyKey: null })
    }
  ]

  const filtered = search !== '' || entryState !== 'ACTIVE'
  const showMarket = kind?.kind === 'directions'
  const renaming = command?.mode === 'rename' ? command : null
  const rowError = command !== null && command.mode !== 'rename' ? command.error : undefined

  return (
    <section className="admin-page" aria-labelledby="admin-catalogs-title">
      <div className="page-title">
        <h2 id="admin-catalogs-title">Справочники</h2>
        <HintButton label="О разделе">
          Добавление, переименование и архивирование направлений, программ, вендоров и продуктов без Excel. Дубль по названию без учёта регистра не создаётся; архивная запись не предлагается для новых взаимодействий, но остаётся в карточках и отчётах. Каждое изменение — в журнале. Организации ведутся в разделе «Организации».
        </HintButton>
        <span className="page-title__spacer" />
        {addLabel !== undefined && <button type="button" onClick={openAdd}>{addLabel}</button>}
      </div>
      <div className="admin-tabs catalogs-tabs" role="group" aria-label="Справочник">
        {catalogTabSlugs.map((slug) => (
          <button key={slug} type="button" aria-pressed={tab === slug} onClick={() => selectTab(slug)}>
            {catalogTabLabels[slug]}
          </button>
        ))}
      </div>

      {tab === 'activity-kinds' && (
        <ActivityKindsPanel
          addRequest={kindsAddRequest}
          onNotify={setMessage}
          onSessionExpired={onSessionExpired}
          onProfileUnavailable={onProfileUnavailable}
        />
      )}

      {kind !== undefined && (
        <>
          <div className="admin-toolbar" role="search" aria-label={`Отбор: ${kind.label}`}>
            <label className="admin-toolbar__search">
              <span className="catalogs-sr">Поиск по названию</span>
              <input
                type="search"
                value={searchText}
                maxLength={200}
                placeholder="Поиск по названию"
                onChange={(event) => setSearchText(event.target.value)}
              />
            </label>
            <Segmented
              label="Состояние"
              options={stateOptions}
              value={entryState}
              onChange={(value) => {
                setEntryState(value)
                setPageIndex(0)
              }}
            />
          </div>

          {rowError !== undefined && <CatalogCommandError error={rowError} onClose={() => setCommand(null)} />}
          {entries.kind === 'loading' && <p className="organizations-message" role="status">Загружаем справочник…</p>}
          {entries.kind === 'failed' && (
            <CatalogLoadFailure text="Не удалось загрузить справочник. Повторите попытку." requestId={entries.requestId} onRetry={() => void loadEntries()} />
          )}
          {entries.kind === 'ready' && entries.page.items.length === 0 && (
            <EmptyState
              text="Записей нет."
              action={filtered
                ? (
                  <button
                    type="button"
                    className="button--secondary"
                    onClick={() => {
                      setSearchText('')
                      setSearch('')
                      setEntryState('ACTIVE')
                      setPageIndex(0)
                    }}
                  >
                    Сбросить поиск и состояние
                  </button>
                )
                : <button type="button" className="button--secondary" onClick={openAdd}>{addLabel}</button>}
            />
          )}
          {entries.kind === 'ready' && entries.page.items.length > 0 && (
            <div className="admin-table-wrap">
              <table className="admin-table" aria-label={kind.label}>
                <thead>
                  <tr>
                    <th scope="col">Название</th>
                    {showMarket && <th scope="col">Рынок труда</th>}
                    <th scope="col" className="admin-table__actions"><span className="catalogs-sr">Действия</span></th>
                  </tr>
                </thead>
                <tbody>
                  {entries.page.items.map((entry) => (
                    <Fragment key={entry.id}>
                      <tr>
                        <td className="admin-table__cell-main">
                          <span className="admin-table__main">{entry.name}</span>
                          {entry.archived && <> <span className="admin-chip admin-chip--warn">Архивирована</span></>}
                          {entry.parentName !== null && (
                            <span className="admin-table__sub">{kind.parentLabel}: {entry.parentName}</span>
                          )}
                        </td>
                        {showMarket && (
                          <td data-label="Рынок труда">
                            {kind.kind === 'directions' && !entry.archived && (
                              <label className="catalogs-check">
                                <input
                                  type="checkbox"
                                  checked={entry.laborMarket === true}
                                  disabled={command?.saving === true}
                                  onChange={() => void submitCommand({
                                    entry, mode: 'market', name: entry.name, confirming: false, saving: false, idempotencyKey: null
                                  })}
                                />
                                Учитывать спрос рынка труда
                              </label>
                            )}
                          </td>
                        )}
                        <td className="admin-table__actions">
                          <ActionMenu label={`Действия: «${entry.name}»`} items={rowMenu(entry)} />
                        </td>
                      </tr>
                      {contactsVendorId === entry.id && (
                        <tr className="admin-table__detail">
                          <td colSpan={showMarket ? 3 : 2}>
                            <VendorContactsPanel
                              vendor={entry}
                              onClose={() => setContactsVendorId(null)}
                              onSessionExpired={onSessionExpired}
                              onProfileUnavailable={onProfileUnavailable}
                            />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
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

          <CardDialog open={addOpen} title={addLabel ?? ''} onClose={() => setAddOpen(false)}>
            <form className="admin-form" onSubmit={(event) => void submitCreate(event)} aria-label={`Добавить: ${kind.label}`}>
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
              {creating.error !== undefined && <CatalogCommandError error={creating.error} />}
              <div className="admin-form__actions">
                <button type="button" className="button--secondary" disabled={creating.saving} onClick={() => setAddOpen(false)}>Отмена</button>
                <button
                  type="submit"
                  disabled={creating.saving || newName.trim().length === 0 || (kind.parent !== undefined && newParentId === '')}
                >
                  {creating.saving ? 'Добавляем…' : 'Добавить'}
                </button>
              </div>
            </form>
          </CardDialog>

          <CardDialog
            open={renaming !== null}
            title={renaming === null ? '' : `Переименовать «${renaming.entry.name}»`}
            onClose={() => (command?.saving === true ? undefined : setCommand(null))}
          >
            {renaming !== null && (
              <form
                className="admin-form"
                onSubmit={(event) => {
                  event.preventDefault()
                  void submitCommand()
                }}
              >
                <label>
                  Новое название «{renaming.entry.name}»
                  <input
                    value={renaming.name}
                    maxLength={200}
                    disabled={renaming.saving}
                    onChange={(event) => setCommand({ ...renaming, name: event.target.value, idempotencyKey: null, error: undefined })}
                  />
                </label>
                {renaming.error !== undefined && <CatalogCommandError error={renaming.error} />}
                <div className="admin-form__actions">
                  <button type="button" className="button--secondary" disabled={renaming.saving} onClick={() => setCommand(null)}>
                    Отмена
                  </button>
                  <button
                    type="submit"
                    disabled={renaming.saving || renaming.name.trim().length === 0 || renaming.name.trim() === renaming.entry.name}
                  >
                    {renaming.saving ? 'Сохраняем…' : 'Сохранить'}
                  </button>
                </div>
              </form>
            )}
          </CardDialog>
        </>
      )}

      {tab === 'journal' && (
        <>
          <div className="admin-toolbar" role="search" aria-label="Отбор журнала">
            <label>
              Что менялось{' '}
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
          </div>
          {events.kind === 'loading' && <p className="organizations-message" role="status">Загружаем журнал…</p>}
          {events.kind === 'failed' && (
            <CatalogLoadFailure text="Не удалось загрузить журнал. Повторите попытку." requestId={events.requestId} onRetry={() => void loadEvents()} />
          )}
          {events.kind === 'ready' && events.page.items.length === 0 && <EmptyState text="Изменений пока нет." />}
          {events.kind === 'ready' && events.page.items.length > 0 && (
            <ol className="catalogs-journal" aria-label="Журнал изменений справочников">
              {events.page.items.map((event) => (
                <li key={event.id}>
                  <span>
                    <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
                    {' · '}{event.actorDisplayName}
                    {' · '}<strong>{actionLabels[event.action]}</strong> · {entityLabels[event.entityType]} «{event.entityName}»
                  </span>
                  {event.changes !== null && <span className="catalogs-journal__sub">{event.changes}</span>}
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
      <Toast message={message} onClose={() => setMessage(null)} />
    </section>
  )
}
