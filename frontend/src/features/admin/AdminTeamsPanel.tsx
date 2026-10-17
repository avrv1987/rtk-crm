import { Fragment, useState, type FormEvent } from 'react'
import { apiClient, createIdempotencyKey, type AdminTeam } from '../../shared/api/client'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { Segmented } from '../../shared/ui/Segmented'
import { Toast } from '../../shared/ui/Toast'
import { CardDialog } from '../interactions/cardUi'
import { CommandError, LoadFailure } from './AdminErrors'
import { handledSessionError, type SessionHandlers } from './adminShared'
import { filterTeams, teamArchivable, type TeamStateFilter } from './teamList'
import { useAdminTeams } from './useAdminTeams'
import './adminUi.css'

type NameDialog = {
  team: AdminTeam | null
  name: string
  saving: boolean
  error?: unknown
  idempotencyKey: string | null
}

type ArchiveCommand = {
  team: AdminTeam
  saving: boolean
  error?: unknown
  idempotencyKey: string | null
}

const namesOrNone = (names: string[], none: string) => (names.length === 0 ? none : names.join(', '))

const stateOptions: { value: TeamStateFilter; label: string }[] = [
  { value: 'ACTIVE', label: 'Действующие' },
  { value: 'ARCHIVED', label: 'Архив' },
  { value: 'ALL', label: 'Все' }
]

const archiveBlockedHint = 'Сначала перенесите организации и сотрудников, включая заблокированных, в другую команду'

export const AdminTeamsPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const handlers = { onSessionExpired, onProfileUnavailable }
  const teamsData = useAdminTeams(handlers)
  const [search, setSearch] = useState('')
  const [stateFilter, setStateFilter] = useState<TeamStateFilter>('ACTIVE')
  const [expanded, setExpanded] = useState<string | null>(null)
  const [nameDialog, setNameDialog] = useState<NameDialog | null>(null)
  const [archive, setArchive] = useState<ArchiveCommand | null>(null)
  const [archiveError, setArchiveError] = useState<{ teamId: string; error: unknown } | null>(null)
  const [toast, setToast] = useState<string | null>(null)

  const closeNameDialog = () => setNameDialog(null)

  const submitName = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (nameDialog === null || nameDialog.saving || nameDialog.name.trim().length === 0) {
      return
    }
    const name = nameDialog.name.trim()
    const idempotencyKey = nameDialog.idempotencyKey ?? createIdempotencyKey()
    setNameDialog({ ...nameDialog, saving: true, error: undefined, idempotencyKey })
    try {
      if (nameDialog.team === null) {
        await apiClient.createTeam({ name }, idempotencyKey)
        setToast(`Команда «${name}» создана.`)
      } else {
        await apiClient.renameTeam(nameDialog.team.id, { name, version: nameDialog.team.version }, idempotencyKey)
        setToast(`Команда переименована: «${name}».`)
      }
      setNameDialog(null)
      void teamsData.reload()
    } catch (error) {
      if (!handledSessionError(error, handlers)) {
        setNameDialog((current) => (current === null ? current : { ...current, saving: false, error }))
      }
    }
  }

  const submitArchive = async () => {
    if (archive === null || archive.saving) {
      return
    }
    const idempotencyKey = archive.idempotencyKey ?? createIdempotencyKey()
    const command = { ...archive, saving: true, idempotencyKey }
    setArchive(command)
    try {
      await apiClient.changeTeamArchived(
        command.team.id,
        { archived: !command.team.archived, version: command.team.version },
        idempotencyKey
      )
      setArchive(null)
      setArchiveError(null)
      setToast(command.team.archived ? `Команда «${command.team.name}» восстановлена.` : `Команда «${command.team.name}» в архиве.`)
      void teamsData.reload()
    } catch (error) {
      if (!handledSessionError(error, handlers)) {
        setArchive(null)
        setArchiveError({ teamId: command.team.id, error })
      }
    }
  }

  const rowMenu = (team: AdminTeam): ActionMenuItem[] => [
    ...(team.archived ? [] : [{
      label: 'Переименовать',
      onSelect: () => setNameDialog({ team, name: team.name, saving: false, idempotencyKey: null })
    }]),
    {
      label: team.archived ? 'Восстановить' : 'Архивировать',
      onSelect: () => {
        if (!team.archived && !teamArchivable(team)) {
          setToast(archiveBlockedHint)
          return
        }
        setArchiveError(null)
        setArchive({ team, saving: false, idempotencyKey: null })
      }
    }
  ]

  const state = teamsData.state
  const visible = state.kind === 'ready' ? filterTeams(state.teams, search, stateFilter) : []
  const dialogTitle = nameDialog?.team === null ? 'Новая команда' : `Переименовать команду «${nameDialog?.team.name ?? ''}»`

  return (
    <section className="admin-page" aria-labelledby="admin-teams-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="admin-teams-title">Команды</h2>
        <HintButton label="О разделе «Команды»">
          Команда объединяет руководителя, КАМ и организации. Руководитель видит все организации своей команды, КАМ — только закреплённые за ним.
          Архивировать можно команду без организаций и сотрудников, включая заблокированных: она уйдёт из списков выбора, история сохранится.
        </HintButton>
        {state.kind === 'ready' && <span className="page-title__count">Всего: {state.teams.length}</span>}
        <span className="page-title__spacer" />
        <button type="button" onClick={() => setNameDialog({ team: null, name: '', saving: false, idempotencyKey: null })}>
          Создать команду
        </button>
      </div>

      <div className="admin-toolbar">
        <Segmented label="Состояние команд" value={stateFilter} options={stateOptions} onChange={setStateFilter} />
        <div className="admin-toolbar__search">
          <input
            type="search"
            value={search}
            maxLength={160}
            placeholder="Поиск по названию"
            aria-label="Поиск по названию"
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
      </div>

      {archiveError !== null && <CommandError error={archiveError.error} onConflict={() => {
        setArchiveError(null)
        void teamsData.reload()
      }} />}
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем команды…</p>}
      {state.kind === 'failed' && (
        <LoadFailure text="Не удалось загрузить команды. Повторите попытку." requestId={state.requestId} onRetry={() => void teamsData.reload()} />
      )}
      {state.kind === 'ready' && visible.length === 0 && (
        <EmptyState
          text={state.teams.length === 0 ? 'Команд пока нет.' : 'По выбранным условиям команд нет.'}
          action={state.teams.length === 0
            ? <button type="button" className="button--secondary" onClick={() => setNameDialog({ team: null, name: '', saving: false, idempotencyKey: null })}>Создать команду</button>
            : undefined}
        />
      )}
      {state.kind === 'ready' && visible.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table" aria-label="Список команд">
            <thead>
              <tr>
                <th scope="col">Команда</th>
                <th scope="col">Руководитель</th>
                <th scope="col">КАМ</th>
                <th scope="col">Организаций</th>
                <th scope="col"><span className="admin-visually-hidden">Действия</span></th>
              </tr>
            </thead>
            <tbody>
              {visible.map((team) => {
                const open = expanded === team.id
                return (
                  <Fragment key={team.id}>
                    <tr>
                      <td className="admin-table__cell-main">
                        <button
                          type="button"
                          className="admin-table__toggle"
                          aria-expanded={open}
                          onClick={() => setExpanded(open ? null : team.id)}
                        >
                          {team.name}
                        </button>
                        {team.archived && <span className="admin-chip admin-chip--danger"> В архиве</span>}
                      </td>
                      <td data-label="Руководитель">{namesOrNone(team.leaderNames, 'Не назначен')}</td>
                      <td data-label="КАМ">{team.managerNames.length}</td>
                      <td data-label="Организаций">{team.organizationCount}</td>
                      <td className="admin-table__actions">
                        <ActionMenu label={`Действия: команда ${team.name}`} items={rowMenu(team)} />
                      </td>
                    </tr>
                    {open && (
                      <tr className="admin-table__detail">
                        <td colSpan={5}>
                          <dl className="admin-profiles__fields" aria-label={`Состав команды ${team.name}`}>
                            <div>
                              <dt>Руководитель</dt>
                              <dd>{namesOrNone(team.leaderNames, 'Не назначен')}</dd>
                            </div>
                            <div>
                              <dt>КАМ</dt>
                              <dd>{namesOrNone(team.managerNames, 'Нет')}</dd>
                            </div>
                            <div>
                              <dt>Организаций</dt>
                              <dd>{team.organizationCount}</dd>
                            </div>
                            {team.otherProfileCount > 0 && (
                              <div>
                                <dt>Вне состава</dt>
                                <dd>{team.otherProfileCount} (заблокированные или администраторы)</dd>
                              </div>
                            )}
                          </dl>
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

      <CardDialog open={nameDialog !== null} title={dialogTitle} onClose={closeNameDialog}>
        {nameDialog !== null && (
          <form className="admin-form" onSubmit={(event) => void submitName(event)}>
            <label>
              {nameDialog.team === null ? 'Название новой команды' : `Новое название команды «${nameDialog.team.name}»`}
              <input
                value={nameDialog.name}
                maxLength={160}
                autoFocus
                disabled={nameDialog.saving}
                onChange={(event) => setNameDialog({ ...nameDialog, name: event.target.value, saving: false, error: undefined, idempotencyKey: null })}
              />
            </label>
            {nameDialog.error !== undefined && (
              <CommandError error={nameDialog.error} onConflict={() => {
                closeNameDialog()
                void teamsData.reload()
              }} />
            )}
            <div className="admin-form__actions">
              <button type="button" className="button--secondary" disabled={nameDialog.saving} onClick={closeNameDialog}>Отмена</button>
              <button
                type="submit"
                disabled={
                  nameDialog.saving
                  || nameDialog.name.trim().length === 0
                  || nameDialog.name.trim() === nameDialog.team?.name
                }
              >
                {nameDialog.saving
                  ? (nameDialog.team === null ? 'Создаём…' : 'Сохраняем…')
                  : (nameDialog.team === null ? 'Создать команду' : 'Сохранить')}
              </button>
            </div>
          </form>
        )}
      </CardDialog>

      <ConfirmDialog
        open={archive !== null}
        title={archive === null ? '' : archive.team.archived ? `Восстановить команду «${archive.team.name}»?` : `Архивировать команду «${archive.team.name}»?`}
        description={archive?.team.archived === true
          ? 'Команда снова появится в списках выбора профилей и организаций.'
          : 'Команда уйдёт из списков выбора профилей и организаций. История и журнал сохранятся; команду можно восстановить.'}
        confirmLabel={archive?.team.archived === true ? 'Восстановить' : 'Архивировать'}
        onConfirm={() => void submitArchive()}
        onCancel={() => setArchive(null)}
      />
      <Toast message={toast} onClose={() => setToast(null)} />
    </section>
  )
}
