import { useState, type FormEvent } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type Team } from '../../shared/api/client'
import { commandErrorMessage, handledSessionError, isVersionConflict, type SessionHandlers } from './adminShared'

export type TeamsState =
  | { kind: 'loading' }
  | { kind: 'ready'; teams: Team[] }
  | { kind: 'failed'; requestId?: string }

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
  const handlers = { onSessionExpired, onProfileUnavailable }

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
        Команда объединяет руководителя, КАМ и вузы. Руководитель видит все вузы своей команды, КАМ — только закреплённые за ним.
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
                  <h3>{team.name}</h3>
                )}
              </div>
              {rename?.team.id !== team.id && (
                <div className="admin-profiles__actions">
                  <button
                    type="button"
                    className="admin-profiles__cancel"
                    onClick={() => setRename({ team, name: team.name, saving: false, idempotencyKey: null })}
                  >
                    Переименовать
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
