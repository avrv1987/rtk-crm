import { useEffect, useState } from 'react'
import { adminGroupsFor, adminSectionsFor, type AdminNavRole, type AdminSectionSlug } from './adminSections'

type AdminSectionNavProps = {
  active: AdminSectionSlug
  role: AdminNavRole
  counts?: Partial<Record<AdminSectionSlug, number>>
}

const Counter = ({ value }: { value: number | undefined }) => (
  value === undefined || value === 0 ? null : <span className="admin-section-nav__count">{value}</span>
)

export const AdminSectionNav = ({ active, role, counts = {} }: AdminSectionNavProps) => {
  const [open, setOpen] = useState(false)
  const activeLabel = adminSectionsFor(role).find((section) => section.slug === active)?.label ?? ''

  useEffect(() => {
    setOpen(false)
  }, [active])

  if (role === 'LEADER') {
    return (
      <nav className="admin-section-nav" aria-label="Разделы настроек команды">
        <ul>
          {adminSectionsFor(role).map((section) => (
            <li key={section.slug}>
              <a href={`#/admin/${section.slug}`} aria-current={section.slug === active ? 'page' : undefined}>
                {section.label}
                <Counter value={counts[section.slug]} />
              </a>
            </li>
          ))}
        </ul>
      </nav>
    )
  }

  return (
    <nav className="admin-section-nav admin-section-nav--side" aria-label="Разделы администрирования">
      <button
        type="button"
        className="button--secondary admin-section-nav__toggle"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span aria-hidden="true">☰</span> Разделы: {activeLabel}
      </button>
      <div className={`admin-section-nav__groups${open ? ' admin-section-nav__groups--open' : ''}`}>
        {adminGroupsFor(role).map(({ group, sections }) => (
          <div key={group} className="admin-section-nav__group">
            <p className="admin-section-nav__group-title">{group}</p>
            <ul>
              {sections.map((section) => (
                <li key={section.slug}>
                  <a href={`#/admin/${section.slug}`} aria-current={section.slug === active ? 'page' : undefined}>
                    {section.label}
                    <Counter value={counts[section.slug]} />
                  </a>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </nav>
  )
}
