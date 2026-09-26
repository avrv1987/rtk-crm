import { useState, type FormEvent } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type AdminTeam, type Team } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { commandErrorMessage, handledSessionError, isVersionConflict, type SessionHandlers } from './adminShared'

export type TeamsState =
  | { kind: 'loading' }
  | { kind: 'ready'; teams: AdminTeam[] }
  | { kind: 'failed'; requestId?: string }

type ArchiveCommand = {
  team: AdminTeam
  confirming: boolean
  saving: boolean
  error?: unknown
  idempotencyKey: string | null
}

const namesOrNone = (names: string[], none: string) => (names.length === 0 ? none : names.join(', '))

const archivable = (team: AdminTeam) => (
  team.organizationCount === 0 && team.leaderNames.length + team.managerNames.length + team.otherProfileCount === 0
)

type AdminTeamsPanelProps = SessionHandlers & {
  teamsState: TeamsState
  onReload: () => void
  onTeamsChanged: () => void
}

type TeamCommand = {
  name: string
  saving: boolean
  error?: unknown
  idempotencyKey: string | null
}

type RenameCommand = TeamCommand & {
  team: Team
}

const emptyCommand: TeamCommand = { name: '', saving: false, idempotencyKey: null }

export const AdminTeamsPanel = ({
  teamsState,
  onReload,
  onTeamsChanged,
  onSessionExpired,
  onProfileUnavailable
}: AdminTeamsPanelProps) => {
  const [create, setCreate] = useState<TeamCommand>(emptyCommand)
  const [rename, setRename] = useState<RenameCommand | null>(null)
  const [archive, setArchive] = useState<ArchiveCommand | null>(null)
  const handlers = { onSessionExpired, onProfileUnavailable }

  const submitArchive = async () => {
    if (archive === null || archive.saving) {
      return
    }
    const idempotencyKey = archive.idempotencyKey ?? createIdempotencyKey()
    setArchive({ ...archive, confirming: false, saving: true, error: undefined, idempotencyKey })
    try {
      await apiClient.changeTeamArchived(
        archive.team.id,
        { archived: !archive.team.archived, version: archive.team.version },
        idempotencyKey
      )
      setArchive(null)
      setCreate((current) => (current.error === undefined ? current : { ...current, error: undefined }))
      onTeamsChanged()
    } catch (error) {
      if (handledSessionError(error, handlers)) {
        return
      }
      setArchive((current) => (current === null ? current : { ...current, saving: false, error }))
    }
  }

  const submitCreate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (create.saving || create.name.trim().length === 0) {
      return
    }
    const idempotencyKey = create.idempotencyKey ?? createIdempotencyKey()
    setCreate({ ...create, saving: true, error: undefined, idempotencyKey })
    try {
      await apiClient.createTeam({ name: create.name.trim() }, idempotencyKey)
      setCreate(emptyCommand)
      onTeamsChanged()
    } catch (error) {
      if (handledSessionError(error, handlers)) {
        return
      }
      setCreate((current) => ({ ...current, saving: false, error }))
    }
  }

  const submitRename = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (rename === null || rename.saving || rename.name.trim().length === 0) {
      return
    }
    const idempotencyKey = rename.idempotencyKey ?? createIdempotencyKey()
    setRename({ ...rename, saving: true, error: undefined, idempotencyKey })
    try {
      await apiClient.renameTeam(rename.team.id, { name: rename.name.trim(), version: rename.team.version }, idempotencyKey)
      setRename(null)
      setCreate((current) => (current.error === undefined ? current : { ...current, error: undefined }))
      onTeamsChanged()
    } catch (error) {
      if (handledSessionError(error, handlers)) {
        return
      }
      setRename((current) => (current === null ? current : { ...current, saving: false, error }))
    }
  }

  const renderError = (error: unknown, onConflict: () => void) => (
    <div className="interaction-command-error" role="alert">
      <p>{commandErrorMessage(error)}</p>
      {error instanceof ApiError && <p className="request-id">Request ID: {error.requestId}</p>}
      {isVersionConflict(error) && <button type="button" onClick={onConflict}>Обновить список</button>}
    </div>
  )

  return (
    <section className="admin-profiles" aria-labelledby="admin-teams-title" aria-busy={teamsState.kind === 'loading'}>
      <div className="admin-profiles__header">
        <div>
          <p className="eyebrow">Администрирование</p>
          <h2 id="admin-teams-title">Команды</h2>
        </div>
        {teamsState.kind === 'ready' && <p className="admin-profiles__total">Всего: {teamsState.teams.length}</p>}
      </div>
      <p className="admin-profiles__intro">
        Команда объединяет руководителя, КАМ и организации. Руководитель видит все организации своей команды, КАМ — только закреплённые за ним.
        Архивировать можно команду без организаций и сотрудников, включая заблокированных: она уйдёт из списков выбора, история сохранится.
      </p>

      <form className="admin-team-form" onSubmit={(event) => void submitCreate(event)}>
        <label>
          Название новой команды
          <input
            value={create.name}
            maxLength={160}
            disabled={create.saving}
            onChange={(event) => setCreate({ name: event.target.value, saving: false, idempotencyKey: null })}
          />
        </label>
        <button type="submit" disabled={create.saving || create.name.trim().length === 0}>
          {create.saving ? 'Создаём…' : 'Создать команду'}
        </button>
      </form>
      {create.error !== undefined && renderError(create.error, onReload)}

      {teamsState.kind === 'loading' && <p className="organizations-message" role="status">Загружаем команды…</p>}
      {teamsState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить команды. Повторите попытку.</p>
          {teamsState.requestId && <p className="request-id">Request ID: {teamsState.requestId}</p>}
          <button type="button" onClick={onReload}>Повторить</button>
        </div>
      )}
      {teamsState.kind === 'ready' && teamsState.teams.length === 0 && (
        <p className="organizations-message">Команд пока нет.</p>
      )}
      {teamsState.kind === 'ready' && teamsState.teams.length > 0 && (
        <ul className="admin-profiles__list" aria-label="Список команд">
          {teamsState.teams.map((team) => (
            <li key={team.id} className="admin-profiles__item">
              <div className="admin-profiles__identity">
                {rename?.team.id === team.id ? (
                  <form className="admin-team-form" onSubmit={(event) => void submitRename(event)}>
                    <label>
                      Новое название команды «{team.name}»
                      <input
                        value={rename.name}
                        maxLength={160}
                        disabled={rename.saving}
                        onChange={(event) => setRename({ team, name: event.target.value, saving: false, idempotencyKey: null })}
                      />
                    </label>
                    <div className="admin-profiles__confirmation-actions">
                      <button type="submit" disabled={rename.saving || rename.name.trim().length === 0 || rename.name.trim() === team.name}>
                        {rename.saving ? 'Сохраняем…' : 'Сохранить'}
                      </button>
                      <button type="button" className="admin-profiles__cancel" disabled={rename.saving} onClick={() => setRename(null)}>
                        Отмена
                      </button>
                    </div>
                    {rename.error !== undefined && renderError(rename.error, () => {
                      setRename(null)
                      onReload()
                    })}
                  </form>
                ) : (
                  <h3>
                    {team.name}
                    {team.archived && <span className="admin-profiles__status admin-profiles__status--blocked">В архиве</span>}
                  </h3>
                )}
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
                {archive?.team.id === team.id && archive.error !== undefined && renderError(archive.error, () => {
                  setArchive(null)
                  onReload()
                })}
              </div>
              {rename?.team.id !== team.id && (
                <div className="admin-profiles__actions">
                  {!team.archived && (
                    <button
                      type="button"
                      className="admin-profiles__cancel"
                      onClick={() => setRename({ team, name: team.name, saving: false, idempotencyKey: null })}
                    >
                      Переименовать
                    </button>
                  )}
                  <button
                    type="button"
                    className="admin-profiles__cancel"
                    disabled={archive?.saving === true || (!team.archived && !archivable(team))}
                    title={!team.archived && !archivable(team)
                      ? 'Сначала перенесите организации и сотрудников, включая заблокированных, в другую команду'
                      : undefined}
                    onClick={() => setArchive({ team, confirming: true, saving: false, idempotencyKey: null })}
                  >
                    {archive?.team.id === team.id && archive.saving
                      ? 'Сохраняем…'
                      : team.archived ? 'Восстановить' : 'Архивировать'}
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={archive?.confirming === true}
        title={archive === null ? '' : archive.team.archived ? `Восстановить команду «${archive.team.name}»?` : `Архивировать команду «${archive.team.name}»?`}
        description={archive?.team.archived === true
          ? 'Команда снова появится в списках выбора профилей и организаций.'
          : 'Команда уйдёт из списков выбора профилей и организаций. История и журнал сохранятся; команду можно восстановить.'}
        confirmLabel={archive?.team.archived === true ? 'Восстановить' : 'Архивировать'}
        onConfirm={() => void submitArchive()}
        onCancel={() => setArchive(null)}
      />
    </section>
  )
}
