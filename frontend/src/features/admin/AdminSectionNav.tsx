export type AdminSectionSlug =
  | 'profiles'
  | 'catalogs'
  | 'workflow-templates'
  | 'catalog-import'
  | 'sources'
  | 'journal'
  | 'personal-data'
  | 'retention'
  | 'activity-kinds'
  | 'pending-source-records'

export type AdminNavRole = 'ADMIN' | 'LEADER'

type AdminSectionEntry = { slug: AdminSectionSlug; label: string; roles: AdminNavRole[] }

const adminOnly: AdminNavRole[] = ['ADMIN']
const adminAndLeader: AdminNavRole[] = ['ADMIN', 'LEADER']
const leaderOnly: AdminNavRole[] = ['LEADER']

export const adminSections: AdminSectionEntry[] = [
  { slug: 'profiles', label: 'Профили, команды и вузы', roles: adminOnly },
  { slug: 'catalogs', label: 'Справочники', roles: adminOnly },
  { slug: 'workflow-templates', label: 'Шаблоны этапов', roles: adminAndLeader },
  { slug: 'pending-source-records', label: 'Заявки источников', roles: leaderOnly },
  { slug: 'catalog-import', label: 'Импорт каталогов', roles: adminOnly },
  { slug: 'sources', label: 'Источники данных', roles: adminOnly },
  { slug: 'journal', label: 'Журнал администратора и безопасности', roles: adminOnly },
  { slug: 'personal-data', label: 'Субъект персональных данных', roles: adminOnly },
  { slug: 'retention', label: 'Сроки хранения', roles: adminOnly },
  { slug: 'activity-kinds', label: 'Виды мероприятий', roles: adminOnly }
]

export const adminSectionsFor = (role: AdminNavRole): AdminSectionEntry[] => (
  adminSections.filter((section) => section.roles.includes(role))
)

export const defaultAdminSection: AdminSectionSlug = adminSections[0].slug

export const defaultAdminSectionFor = (role: AdminNavRole): AdminSectionSlug => adminSectionsFor(role)[0].slug

export const resolveAdminSection = (slug: string | undefined, role: AdminNavRole): AdminSectionSlug => {
  const allowed = adminSectionsFor(role)
  const found = allowed.find((section) => section.slug === slug)
  return found?.slug ?? defaultAdminSectionFor(role)
}

export const AdminSectionNav = ({ active, role }: { active: AdminSectionSlug; role: AdminNavRole }) => (
  <nav className="admin-section-nav" aria-label="Разделы администрирования">
    <ul>
      {adminSectionsFor(role).map((section) => (
        <li key={section.slug}>
          <a href={`#/admin/${section.slug}`} aria-current={section.slug === active ? 'page' : undefined}>
            {section.label}
          </a>
        </li>
      ))}
    </ul>
  </nav>
)
