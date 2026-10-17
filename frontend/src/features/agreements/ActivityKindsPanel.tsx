import { Fragment, useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { Segmented } from '../../shared/ui/Segmented'
import { Toast } from '../../shared/ui/Toast'
import '../admin/adminUi.css'
import '../admin/catalogs.css'
import { CardDialog } from '../interactions/cardUi'
import { agreementsApi, type ActivityKind } from './agreementsApi'
import { CommandError, useAccessErrorHandler, useCommandKey } from './agreementUi'

type ActivityKindsPanelProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
  /** Счётчик нажатий главной кнопки страницы «Справочники»: каждое новое значение открывает окно добавления. */
  addRequest?: number
  /** Куда показывать результат действия; без него панель показывает своё сообщение. */
  onNotify?: (message: string) => void
}

type KindsState =
  | { kind: 'loading' }
  | { kind: 'ready'; kinds: ActivityKind[] }
  | { kind: 'failed'; error: unknown }

type KindFilter = 'ACTIVE' | 'ARCHIVED' | 'ALL'

type KindCommand = {
  kind: ActivityKind
  mode: 'rename' | 'archive'
  name: string
  confirming: boolean
  saving: boolean
  error?: unknown
}

const filterOptions: Array<{ value: KindFilter; label: string }> = [
  { value: 'ACTIVE', label: 'Действующие' },
  { value: 'ARCHIVED', label: 'Архив' },
  { value: 'ALL', label: 'Все' }
]

export const ActivityKindsPanel = ({ onSessionExpired, onProfileUnavailable, addRequest, onNotify }: ActivityKindsPanelProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [state, setState] = useState<KindsState>({ kind: 'loading' })
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<KindFilter>('ACTIVE')
  const [addOpen, setAddOpen] = useState(false)
  const [name, setName] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [command, setCommand] = useState<KindCommand | null>(null)
  const [ownMessage, setOwnMessage] = useState<string | null>(null)
  const commandKey = useCommandKey()
  const seenRequest = useRef(addRequest)
  const notify = onNotify ?? setOwnMessage

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', kinds: await agreementsApi.listKinds(true) })
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setState({ kind: 'failed', error: caught })
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const openAdd = useCallback(() => {
    setName('')
    setError(null)
    setAddOpen(true)
  }, [])

  useEffect(() => {
    if (addRequest !== undefined && addRequest !== seenRequest.current) {
      seenRequest.current = addRequest
      openAdd()
    }
  }, [addRequest, openAdd])

  const create = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload = { name: name.trim() }
    setSaving(true)
    setError(null)
    try {
      await agreementsApi.createKind(payload.name, commandKey.keyFor(payload))
      commandKey.settle()
      setName('')
      setAddOpen(false)
      notify(`Вид мероприятия «${payload.name}» добавлен.`)
      await load()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  const submitCommand = async () => {
    if (command === null || command.saving) {
      return
    }
    const target = command.kind
    const payload = command.mode === 'rename'
      ? { version: target.version, name: command.name.trim(), archived: target.archived }
      : { version: target.version, name: target.name, archived: !target.archived }
    setCommand({ ...command, confirming: false, saving: true, error: undefined })
    try {
      await agreementsApi.updateKind(target.id, payload, commandKey.keyFor({ id: target.id, ...payload }))
      commandKey.settle()
      setCommand(null)
      notify(command.mode === 'rename'
        ? `Название «${payload.name}» сохранено.`
        : payload.archived ? `Вид «${target.name}» в архиве.` : `Вид «${target.name}» восстановлен.`)
      await load()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setCommand((current) => (current === null ? current : { ...current, saving: false, error: caught }))
      }
    }
  }

  const needle = search.trim().toLowerCase()
  const shown = state.kind === 'ready'
    ? state.kinds.filter((item) => (
      (filter === 'ALL' || (filter === 'ARCHIVED') === item.archived) && item.name.toLowerCase().includes(needle)
    ))
    : []
  const renaming = command?.mode === 'rename' ? command : null
  const rowError = command !== null && command.mode === 'archive' ? command.error : undefined

  return (
    <Fragment>
      <div className="admin-toolbar" role="search" aria-label="Отбор: виды мероприятий">
        <HintButton label="О видах мероприятий">
          Справочник для плана мероприятий договоров с вузами. Начальные значения — по видам мероприятий приказа Минцифры № 270; вид в архиве остаётся у существующих мероприятий, но не выбирается для новых.
        </HintButton>
        <label className="admin-toolbar__search">
          <span className="catalogs-sr">Поиск по названию</span>
          <input
            type="search"
            value={search}
            maxLength={200}
            placeholder="Поиск по названию"
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        <Segmented label="Состояние" options={filterOptions} value={filter} onChange={setFilter} />
        {addRequest === undefined && <button type="button" onClick={openAdd}>Добавить вид мероприятия</button>}
      </div>
      {state.kind === 'loading' && <p role="status">Загружаем виды мероприятий…</p>}
      {state.kind === 'failed' && <CommandError error={state.error} fallback="Не удалось загрузить виды мероприятий." onRetry={() => void load()} />}
      {rowError !== undefined && (
        <CommandError error={rowError} fallback="Изменение не сохранено." onRetry={() => { setCommand(null); void load() }} retryLabel="Обновить список" />
      )}
      {state.kind === 'ready' && shown.length === 0 && (
        <EmptyState
          text="Видов мероприятий нет."
          action={search !== '' || filter !== 'ACTIVE'
            ? <button type="button" className="button--secondary" onClick={() => { setSearch(''); setFilter('ACTIVE') }}>Сбросить поиск и состояние</button>
            : <button type="button" className="button--secondary" onClick={openAdd}>Добавить вид мероприятия</button>}
        />
      )}
      {shown.length > 0 && (
        <table className="admin-table" aria-label="Виды мероприятий">
          <thead>
            <tr>
              <th scope="col">Название</th>
              <th scope="col" className="admin-table__actions"><span className="catalogs-sr">Действия</span></th>
            </tr>
          </thead>
          <tbody>
            {shown.map((item) => (
              <tr key={item.id}>
                <td className="admin-table__cell-main">
                  <span className="admin-table__main">{item.name}</span>
                  {item.archived && <> <span className="admin-chip admin-chip--warn">В архиве</span></>}
                </td>
                <td className="admin-table__actions">
                  <ActionMenu
                    label={`Действия: «${item.name}»`}
                    items={[
                      ...(item.archived ? [] : [{
                        label: 'Переименовать',
                        onSelect: () => setCommand({ kind: item, mode: 'rename', name: item.name, confirming: false, saving: false })
                      }]),
                      {
                        label: item.archived ? 'Восстановить' : 'Архивировать',
                        onSelect: () => setCommand({ kind: item, mode: 'archive', name: item.name, confirming: true, saving: false })
                      }
                    ]}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <CardDialog open={addOpen} title="Добавить вид мероприятия" onClose={() => setAddOpen(false)}>
        <form className="admin-form" onSubmit={(event) => void create(event)}>
          <label>
            Новый вид мероприятия
            <input value={name} required maxLength={200} disabled={saving} onChange={(event) => setName(event.target.value)} />
          </label>
          {error !== null && <CommandError error={error} fallback="Вид мероприятия не добавлен." />}
          <div className="admin-form__actions">
            <button type="button" className="button--secondary" disabled={saving} onClick={() => setAddOpen(false)}>Отмена</button>
            <button type="submit" disabled={saving || name.trim() === ''}>{saving ? 'Добавляем…' : 'Добавить'}</button>
          </div>
        </form>
      </CardDialog>

      <CardDialog
        open={renaming !== null}
        title={renaming === null ? '' : `Переименовать «${renaming.kind.name}»`}
        onClose={() => (command?.saving === true ? undefined : setCommand(null))}
      >
        {renaming !== null && (
          <form
            className="admin-form"
            aria-label={`Вид мероприятия «${renaming.kind.name}»`}
            onSubmit={(event) => {
              event.preventDefault()
              void submitCommand()
            }}
          >
            <label>
              Название вида мероприятия
              <input
                value={renaming.name}
                required
                maxLength={200}
                disabled={renaming.saving}
                onChange={(event) => setCommand({ ...renaming, name: event.target.value, error: undefined })}
              />
            </label>
            {renaming.error !== undefined && (
              <CommandError
                error={renaming.error}
                fallback="Изменение не сохранено."
                onRetry={() => { setCommand(null); void load() }}
                retryLabel="Обновить список"
              />
            )}
            <div className="admin-form__actions">
              <button type="button" className="button--secondary" disabled={renaming.saving} onClick={() => setCommand(null)}>Отмена</button>
              <button
                type="submit"
                disabled={renaming.saving || renaming.name.trim() === '' || renaming.name.trim() === renaming.kind.name}
              >
                {renaming.saving ? 'Сохраняем…' : 'Сохранить'}
              </button>
            </div>
          </form>
        )}
      </CardDialog>

      <ConfirmDialog
        open={command?.mode === 'archive' && command.confirming}
        title={command === null ? '' : `${command.kind.archived ? 'Восстановить' : 'Архивировать'} «${command.kind.name}»?`}
        description={command?.kind.archived === true
          ? 'Вид снова можно будет выбрать для новых мероприятий.'
          : 'Вид не будет предлагаться для новых мероприятий; у существующих мероприятий он останется.'}
        confirmLabel={command?.kind.archived === true ? 'Восстановить' : 'Архивировать'}
        onConfirm={() => void submitCommand()}
        onCancel={() => setCommand(null)}
      />
      {onNotify === undefined && <Toast message={ownMessage} onClose={() => setOwnMessage(null)} />}
    </Fragment>
  )
}
