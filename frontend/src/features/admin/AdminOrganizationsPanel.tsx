import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import {
  apiClient,
  createIdempotencyKey,
  type AdminOrganization,
  type OrganizationStatusChange,
  type PageAdminOrganization
} from '../../shared/api/client'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { Pagination } from '../../shared/ui/Pagination'
import { Toast } from '../../shared/ui/Toast'
import { CardDialog } from '../interactions/cardUi'
import { actionTexts } from '../organizations/OrganizationCatalogPanel'
import {
  OrganizationForm,
  organizationErrorMessage,
  organizationStatusLabels,
  organizationTypeLabels
} from '../organizations/OrganizationForm'
import { CommandError, LoadFailure } from './AdminErrors'
import { handledSessionError, isVersionConflict, requestIdOf, type SessionHandlers } from './adminShared'
import { organizationMenuActions, type OrganizationListStatus } from './organizationList'
import { useAdminTeams } from './useAdminTeams'
import './adminUi.css'

type StatusAction = OrganizationStatusChange['action']

type OrganizationsState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageAdminOrganization }
  | { kind: 'failed'; requestId?: string }

type Transfer = {
  organization: AdminOrganization
  teamId: string
  confirming: boolean
  saving: boolean
  error?: unknown
  idempotencyKey: string | null
}

const organizationsPageSize = 25
const searchDelayMilliseconds = 300

const listStatuses: Array<{ value: OrganizationListStatus; label: string }> = [
  { value: 'CURRENT', label: 'Действующие' },
  { value: 'PENDING', label: 'Ожидают подтверждения' },
  { value: 'ARCHIVED', label: 'Архив' },
  { value: 'ALL', label: 'Все' }
]

const statusTone = { ACTIVE: 'ok', PENDING: 'warn', ARCHIVED: 'danger' } as const

export const AdminOrganizationsPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const teamsData = useAdminTeams({ onSessionExpired, onProfileUnavailable })
  const [organizationsState, setOrganizationsState] = useState<OrganizationsState>({ kind: 'loading' })
  const [pageIndex, setPageIndex] = useState(0)
  const [searchText, setSearchText] = useState('')
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<OrganizationListStatus>('CURRENT')
  const [expanded, setExpanded] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<AdminOrganization | null>(null)
  const [pending, setPending] = useState<{ organization: AdminOrganization; action: StatusAction } | null>(null)
  const [statusError, setStatusError] = useState<unknown>(undefined)
  const [transfer, setTransfer] = useState<Transfer | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const requestVersion = useRef(0)
  const statusKey = useRef<{ action: StatusAction; key: string } | null>(null)
  const teams = teamsData.teams
  const activeTeams = teams.filter((team) => !team.archived)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const loadOrganizations = useCallback(async () => {
    const version = ++requestVersion.current
    setOrganizationsState({ kind: 'loading' })
    try {
      const page = await apiClient.listAdminOrganizations({ page: pageIndex, size: organizationsPageSize, q: search, status })
      if (version === requestVersion.current) {
        setOrganizationsState({ kind: 'ready', page })
      }
    } catch (error) {
      if (version !== requestVersion.current || handleError(error)) {
        return
      }
      setOrganizationsState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [handleError, pageIndex, search, status])

  useEffect(() => {
    void loadOrganizations()
    return () => {
      requestVersion.current += 1
    }
  }, [loadOrganizations])

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

  const replaceOrganization = (organization: AdminOrganization) => {
    setOrganizationsState((current) => (
      current.kind === 'ready'
        ? { kind: 'ready', page: { ...current.page, items: current.page.items.map((item) => (item.id === organization.id ? organization : item)) } }
        : current
    ))
  }

  const runStatus = async () => {
    if (pending === null) {
      return
    }
    const { organization, action } = pending
    const key = statusKey.current?.action === action ? statusKey.current.key : createIdempotencyKey()
    statusKey.current = { action, key }
    setPending(null)
    setStatusError(undefined)
    try {
      const changed = await apiClient.changeAdminOrganizationStatus(organization.id, { action, version: organization.version }, key)
      statusKey.current = null
      setToast(`«${changed.name}»: ${actionTexts[action].done}`)
      void loadOrganizations()
    } catch (failure) {
      if (!handleError(failure)) {
        setStatusError(failure)
      }
    }
  }

  const confirmTransfer = async () => {
    if (transfer === null || transfer.saving) {
      return
    }
    const idempotencyKey = transfer.idempotencyKey ?? createIdempotencyKey()
    setTransfer({ ...transfer, saving: true, error: undefined, idempotencyKey })
    try {
      await apiClient.transferOrganizationTeam(
        transfer.organization.id,
        { teamId: transfer.teamId, version: transfer.organization.version },
        idempotencyKey
      )
      setToast(`«${transfer.organization.name}» перенесена в команду «${targetTeamName(transfer.teamId)}».`)
      setTransfer(null)
      void loadOrganizations()
    } catch (error) {
      if (!handleError(error)) {
        setTransfer((current) => (current === null ? current : { ...current, saving: false, error }))
      }
    }
  }

  const reloadAfterConflict = () => {
    setTransfer(null)
    void loadOrganizations()
  }

  const targetTeamName = (teamId: string) => teams.find((team) => team.id === teamId)?.name ?? 'выбранную команду'

  const rowMenu = (organization: AdminOrganization): ActionMenuItem[] => organizationMenuActions(organization).map((key) => {
    if (key === 'edit') {
      return { label: 'Изменить название и реквизиты', onSelect: () => setEditing(organization) }
    }
    if (key === 'transfer') {
      return {
        label: 'Перенести в команду…',
        onSelect: () => setTransfer({ organization, teamId: '', confirming: false, saving: false, idempotencyKey: null }),
        separated: true
      }
    }
    return {
      label: actionTexts[key].button,
      onSelect: () => {
        setStatusError(undefined)
        setPending({ organization, action: key })
      },
      separated: key === 'ARCHIVE' || key === 'RESTORE'
    }
  })

  const state = organizationsState
  const filtered = search.length > 0 || status !== 'CURRENT'
  const transferTargets = transfer === null ? [] : activeTeams.filter((team) => team.id !== transfer.organization.teamId)

  return (
    <section className="admin-page" aria-labelledby="admin-organizations-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="admin-organizations-title">Организации</h2>
        <HintButton label="О разделе «Организации»">
          Служебный список без контактов и взаимодействий. Здесь добавляют организации (вуз, колледж, школа), правят название и реквизиты,
          подтверждают заявки КАМ, архивируют и переносят организацию в другую команду; КАМ другой команды снимается с организации.
        </HintButton>
        {state.kind === 'ready' && <span className="page-title__count">Всего: {state.page.total}</span>}
        <span className="page-title__spacer" />
        <button type="button" onClick={() => setCreating(true)}>Добавить организацию</button>
      </div>

      <div className="admin-toolbar" role="search" aria-label="Отбор организаций">
        <div className="admin-toolbar__search">
          <input
            type="search"
            value={searchText}
            maxLength={200}
            placeholder="Поиск по названию"
            aria-label="Поиск по названию"
            onChange={(event) => setSearchText(event.target.value)}
          />
        </div>
        <label className="admin-toolbar__select">
          <span className="admin-visually-hidden">Состояние</span>
          <select
            value={status}
            aria-label="Состояние"
            onChange={(event) => {
              setStatus(event.target.value as OrganizationListStatus)
              setPageIndex(0)
            }}
          >
            {listStatuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
      </div>

      {statusError !== undefined && (
        <div className="interaction-command-error" role="alert">
          <p>{organizationErrorMessage(statusError)}</p>
        </div>
      )}
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем организации…</p>}
      {state.kind === 'failed' && (
        <LoadFailure text="Не удалось загрузить организации. Повторите попытку." requestId={state.requestId} onRetry={() => void loadOrganizations()} />
      )}
      {state.kind === 'ready' && state.page.items.length === 0 && (
        <EmptyState
          text={filtered ? 'По выбранным условиям организаций нет.' : 'Организаций пока нет.'}
          action={filtered ? undefined : <button type="button" className="button--secondary" onClick={() => setCreating(true)}>Добавить организацию</button>}
        />
      )}
      {state.kind === 'ready' && state.page.items.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table" aria-label="Организации и их команды">
            <thead>
              <tr>
                <th scope="col">Организация</th>
                <th scope="col">Тип</th>
                <th scope="col">Команда</th>
                <th scope="col">Ответственный КАМ</th>
                <th scope="col">Состояние</th>
                <th scope="col"><span className="admin-visually-hidden">Действия</span></th>
              </tr>
            </thead>
            <tbody>
              {state.page.items.map((organization) => {
                const open = expanded === organization.id
                return (
                  <Fragment key={organization.id}>
                    <tr>
                      <td className="admin-table__cell-main">
                        <button
                          type="button"
                          className="admin-table__toggle"
                          aria-expanded={open}
                          onClick={() => setExpanded(open ? null : organization.id)}
                        >
                          {organization.name}
                        </button>
                      </td>
                      <td data-label="Тип">{organizationTypeLabels[organization.type]}</td>
                      <td data-label="Команда">{organization.teamName}</td>
                      <td data-label="Ответственный КАМ">{organization.ownerManagerName ?? 'Требует назначения'}</td>
                      <td data-label="Состояние">
                        <span className={`admin-chip admin-chip--${statusTone[organization.status]}`}>{organizationStatusLabels[organization.status]}</span>
                      </td>
                      <td className="admin-table__actions">
                        <ActionMenu label={`Действия: ${organization.name}`} items={rowMenu(organization)} />
                      </td>
                    </tr>
                    {open && (
                      <tr className="admin-table__detail">
                        <td colSpan={6}>
                          <section aria-label="Реквизиты и состояние организации">
                            <dl className="admin-profiles__fields">
                              <div>
                                <dt>Город или регион</dt>
                                <dd>{organization.city ?? 'Не указан'}</dd>
                              </div>
                              <div>
                                <dt>Сайт</dt>
                                <dd>
                                  {organization.website === null
                                    ? 'Не указан'
                                    : <a href={organization.website} target="_blank" rel="noopener noreferrer">{organization.website}</a>}
                                </dd>
                              </div>
                              <div>
                                <dt>ИНН</dt>
                                <dd>{organization.inn ?? 'Не указан'}</dd>
                              </div>
                            </dl>
                            {organization.status === 'PENDING' && (
                              <p className="admin-form__hint">
                                КАМ завёл организацию и ждёт подтверждения. Проверьте название и реквизиты, затем подтвердите или отклоните заявку.
                              </p>
                            )}
                          </section>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      {state.kind === 'ready' && (
        <Pagination
          label="Страницы организаций"
          page={state.page.page}
          size={state.page.size}
          total={state.page.total}
          onChange={setPageIndex}
        />
      )}

      <CardDialog open={creating} title="Новая организация" onClose={() => setCreating(false)}>
        <OrganizationForm
          title="Новая организация"
          submitLabel="Создать организацию"
          hint="Организация появится в выбранной команде со статусом «Требует назначения»; КАМ назначает руководитель команды."
          teams={activeTeams}
          linkDuplicates={false}
          onSubmit={async (payload, idempotencyKey) => {
            const created = await apiClient.createAdminOrganization(payload, idempotencyKey)
            setCreating(false)
            setToast(`«${created.name}» создана в команде «${created.teamName}».`)
            await loadOrganizations()
          }}
          onCancel={() => setCreating(false)}
          onSessionError={handleError}
        />
      </CardDialog>

      <CardDialog open={editing !== null} title={editing === null ? '' : `Реквизиты: ${editing.name}`} onClose={() => setEditing(null)}>
        {editing !== null && (
          <OrganizationForm
            title={`Реквизиты: ${editing.name}`}
            submitLabel="Сохранить реквизиты"
            initial={{
              name: editing.name,
              ...(editing.type === 'OPEN_ENROLLMENT' ? {} : { type: editing.type }),
              city: editing.city ?? '',
              website: editing.website ?? '',
              inn: editing.inn ?? ''
            }}
            exceptId={editing.id}
            linkDuplicates={false}
            onSubmit={async (payload, key) => {
              const changed = await apiClient.updateAdminOrganization(editing.id, { ...payload, version: editing.version }, key)
              setEditing(null)
              setToast('Реквизиты сохранены.')
              replaceOrganization(changed)
            }}
            onCancel={() => setEditing(null)}
            onSessionError={handleError}
          />
        )}
      </CardDialog>

      <CardDialog
        open={transfer !== null}
        title={transfer === null ? '' : `Перенести в команду: ${transfer.organization.name}`}
        onClose={() => setTransfer(null)}
      >
        {transfer !== null && (
          <div className="admin-form">
            {!transfer.confirming && (
              <label>
                Перенести в команду
                <select
                  value={transfer.teamId}
                  disabled={transfer.saving}
                  onChange={(event) => setTransfer({ ...transfer, teamId: event.target.value, idempotencyKey: null })}
                >
                  <option value="">Выберите команду</option>
                  {transferTargets.map((team) => <option key={team.id} value={team.id}>{team.name}</option>)}
                </select>
              </label>
            )}
            {transfer.confirming && (
              <div className="admin-profiles__confirmation" role="alertdialog" aria-labelledby={`transfer-confirmation-${transfer.organization.id}`}>
                <h3 id={`transfer-confirmation-${transfer.organization.id}`}>Подтвердите перенос организации</h3>
                <p>
                  «{transfer.organization.name}» перейдёт из команды «{transfer.organization.teamName}» в команду «{targetTeamName(transfer.teamId)}».
                  {transfer.organization.ownerManagerName !== null
                    ? ` КАМ «${transfer.organization.ownerManagerName}» будет снят, организация получит статус «Требует назначения».`
                    : ''}
                  {' '}Руководитель прежней команды потеряет доступ к карточке со следующего запроса.
                </p>
              </div>
            )}
            {transfer.error !== undefined && <CommandError error={transfer.error} onConflict={reloadAfterConflict} />}
            <div className="admin-form__actions">
              <button type="button" className="button--secondary" disabled={transfer.saving} onClick={() => setTransfer(null)}>Отмена</button>
              {!transfer.confirming && (
                <button type="button" disabled={transfer.teamId === ''} onClick={() => setTransfer({ ...transfer, confirming: true })}>
                  Перенести
                </button>
              )}
              {transfer.confirming && !isVersionConflict(transfer.error) && (
                <button type="button" disabled={transfer.saving} onClick={() => void confirmTransfer()}>
                  {transfer.saving ? 'Переносим…' : 'Подтвердить перенос'}
                </button>
              )}
            </div>
          </div>
        )}
      </CardDialog>

      <ConfirmDialog
        open={pending !== null}
        title={pending === null ? '' : `${actionTexts[pending.action].title} «${pending.organization.name}»?`}
        description={pending === null ? '' : `${organizationTypeLabels[pending.organization.type]}. ${actionTexts[pending.action].description}`}
        confirmLabel={pending === null ? '' : actionTexts[pending.action].button}
        onConfirm={() => void runStatus()}
        onCancel={() => setPending(null)}
      />
      <Toast message={toast} onClose={() => setToast(null)} />
    </section>
  )
}
