const sections = [
  { id: 'admin-profiles-title', label: 'Профили CRM' },
  { id: 'admin-teams-title', label: 'Команды' },
  { id: 'admin-organizations-title', label: 'Организации и команды' },
  { id: 'admin-catalogs-title', label: 'Справочники' },
  { id: 'workflow-templates-title', label: 'Шаблоны этапов' },
  { id: 'catalog-import-title', label: 'Импорт каталогов' },
  { id: 'data-sources-title', label: 'Источники данных' }
]

const goTo = (id: string) => {
  const heading = document.getElementById(id)
  if (heading === null) {
    return
  }
  heading.setAttribute('tabindex', '-1')
  heading.scrollIntoView({ block: 'start' })
  heading.focus({ preventScroll: true })
}

export const AdminSectionNav = () => (
  <nav className="admin-section-nav" aria-label="Разделы администрирования">
    <ul>
      {sections.map((section) => (
        <li key={section.id}>
          <button type="button" className="button--secondary" onClick={() => goTo(section.id)}>{section.label}</button>
        </li>
      ))}
    </ul>
  </nav>
)
