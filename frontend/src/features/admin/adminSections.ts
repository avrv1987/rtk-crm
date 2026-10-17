export type AdminSectionSlug =
  | 'overview'
  | 'employees'
  | 'teams'
  | 'organizations'
  | 'catalogs'
  | 'catalog-import'
  | 'workflow-templates'
  | 'pending-source-records'
  | 'sources'
  | 'journal'
  | 'personal-data'
  | 'retention'

export type AdminNavRole = 'ADMIN' | 'LEADER'

export type AdminSectionEntry = { slug: AdminSectionSlug; label: string; group: string; roles: AdminNavRole[] }

const adminOnly: AdminNavRole[] = ['ADMIN']
const adminAndLeader: AdminNavRole[] = ['ADMIN', 'LEADER']
const leaderOnly: AdminNavRole[] = ['LEADER']

export const adminSections: AdminSectionEntry[] = [
  { slug: 'overview', label: 'Обзор', group: 'Начало', roles: adminOnly },
  { slug: 'employees', label: 'Сотрудники', group: 'Люди и доступ', roles: adminOnly },
  { slug: 'teams', label: 'Команды', group: 'Люди и доступ', roles: adminOnly },
  { slug: 'organizations', label: 'Организации', group: 'Люди и доступ', roles: adminOnly },
  { slug: 'catalogs', label: 'Справочники', group: 'Данные', roles: adminOnly },
  { slug: 'catalog-import', label: 'Импорт из Excel', group: 'Данные', roles: adminOnly },
  { slug: 'workflow-templates', label: 'Шаблоны этапов', group: 'Данные', roles: adminAndLeader },
  { slug: 'pending-source-records', label: 'Заявки с сайта', group: 'Данные', roles: leaderOnly },
  { slug: 'sources', label: 'Источники данных', group: 'Интеграции', roles: adminOnly },
  { slug: 'journal', label: 'Журнал', group: 'Безопасность', roles: adminOnly },
  { slug: 'personal-data', label: 'Персональные данные', group: 'Безопасность', roles: adminOnly },
  { slug: 'retention', label: 'Сроки хранения', group: 'Безопасность', roles: adminOnly }
]

export const adminSectionsFor = (role: AdminNavRole): AdminSectionEntry[] => (
  adminSections.filter((section) => section.roles.includes(role))
)

export const adminGroupsFor = (role: AdminNavRole): { group: string; sections: AdminSectionEntry[] }[] => {
  const groups: { group: string; sections: AdminSectionEntry[] }[] = []
  for (const section of adminSectionsFor(role)) {
    const last = groups.at(-1)
    if (last?.group === section.group) {
      last.sections.push(section)
    } else {
      groups.push({ group: section.group, sections: [section] })
    }
  }
  return groups
}

/** Прежние адреса #/admin/<slug>: куда они ведут теперь (и какой параметр добавить). */
const legacySlugs: Record<string, { slug: AdminSectionSlug; params?: string }> = {
  profiles: { slug: 'employees' },
  'activity-kinds': { slug: 'catalogs', params: 'tab=activity-kinds' }
}

export type AdminAddress = { section: AdminSectionSlug; query: string }

const joinQuery = (...parts: (string | undefined)[]) => parts.filter((part) => part !== undefined && part !== '').join('&')

/** Разбор #/admin/<slug>?<query>: старые адреса — в новые, недоступное роли — на первый подраздел роли. */
export const resolveAdminAddress = (slug: string | undefined, query: string, role: AdminNavRole): AdminAddress => {
  const allowed = adminSectionsFor(role)
  const legacy = slug === undefined ? undefined : legacySlugs[slug]
  const requested = legacy?.slug ?? slug
  const section = allowed.find((entry) => entry.slug === requested)
  if (section === undefined) {
    return { section: allowed[0].slug, query: '' }
  }
  return { section: section.slug, query: joinQuery(query, legacy?.params) }
}

export const resolveAdminSection = (slug: string | undefined, role: AdminNavRole): AdminSectionSlug => (
  resolveAdminAddress(slug, '', role).section
)
