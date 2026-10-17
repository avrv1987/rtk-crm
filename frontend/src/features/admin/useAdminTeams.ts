import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, type AdminTeam } from '../../shared/api/client'
import { handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'

export type TeamsState =
  | { kind: 'loading' }
  | { kind: 'ready'; teams: AdminTeam[] }
  | { kind: 'failed'; requestId?: string }

/** Команды нужны сразу трём страницам: «Сотрудники» (фильтр и редактор), «Команды» и «Организации» (перенос). */
export const useAdminTeams = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [state, setState] = useState<TeamsState>({ kind: 'loading' })
  const requestVersion = useRef(0)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    setState({ kind: 'loading' })
    try {
      const teams = await apiClient.listTeams()
      if (version === requestVersion.current) {
        setState({ kind: 'ready', teams })
      }
    } catch (error) {
      if (version !== requestVersion.current || handledSessionError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load()
    return () => {
      requestVersion.current += 1
    }
  }, [load])

  return { state, teams: state.kind === 'ready' ? state.teams : [], reload: load }
}
