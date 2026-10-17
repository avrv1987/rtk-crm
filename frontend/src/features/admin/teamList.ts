import type { AdminTeam } from '../../shared/api/client'

export type TeamStateFilter = 'ACTIVE' | 'ARCHIVED' | 'ALL'

/** Архивировать можно только пустую команду: без организаций, руководителей, КАМ и профилей вне состава. */
export const teamArchivable = (team: Pick<AdminTeam, 'organizationCount' | 'leaderNames' | 'managerNames' | 'otherProfileCount'>) => (
  team.organizationCount === 0 && team.leaderNames.length + team.managerNames.length + team.otherProfileCount === 0
)

export const filterTeams = <T extends Pick<AdminTeam, 'name' | 'archived'>>(teams: T[], search: string, state: TeamStateFilter): T[] => {
  const needle = search.trim().toLocaleLowerCase('ru')
  return teams.filter((team) => (
    (state === 'ALL' || (state === 'ARCHIVED') === team.archived)
    && (needle === '' || team.name.toLocaleLowerCase('ru').includes(needle))
  ))
}
