import type { Me } from '../shared/api/client'
import type { Modules } from '../shared/modules'

export type Role = Me['role']

export type Section =
  | 'control' | 'work' | 'issues' | 'organizations' | 'agreements' | 'reports' | 'admin' | 'enrolment' | 'partner' | 'help'
  | 'agent-plans' | 'summary'

export type Route = {
  section: string
  organizationId?: string
  interactionId?: string
  query: string
}

export type NavItem = {
  label: string
  href: string
  sections: Section[]
  phone: boolean
}

export type WorkTab = {
  key: 'summary' | 'control' | 'work' | 'issues'
  label: string
  href: string
}

export const isSameRouteClick = (clickedHref: string | null, currentHash: string): boolean => (
  clickedHref !== null && clickedHref.startsWith('#') && clickedHref === currentHash
)

// Пункт меню раздела, где пользователь уже находится (в карточке, отчёте или с отборами), возвращает к началу раздела.
export const isSameSectionClick = (clickedHref: string | null, currentHash: string): boolean => (
  clickedHref !== null && clickedHref.startsWith('#') && parseRoute(clickedHref).section === parseRoute(currentHash).section
)

// Раздел, недоступный роли, показывает первый доступный; адрес приводится к нему, чтобы закладка не вводила в заблуждение.
export const canonicalAddress = (route: Route, sections: readonly string[]): string | null => (
  route.section === '' || sections.includes(route.section) ? null : `#/${sections[0]}`
)

export const parseRoute = (hash: string): Route => {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  const [section = '', organizationId, interactionId] = path.split('/')
  return { section, organizationId, interactionId, query }
}

const seesTeams = (role: Role) => role === 'LEADER' || role === 'MANAGEMENT'

const enrolmentVisible = (enrolmentOperator: boolean, modules: Modules) => modules.enrolment && enrolmentOperator

// enrolmentOnly — менеджер учебного процесса служебной команды «Открытый набор»: своих вузов у него нет, разделы КАМ лишние.
export const sectionsFor = (role: Role, enrolmentOperator: boolean, modules: Modules, enrolmentOnly = false): Section[] => {
  if (role === 'ADMIN') {
    return ['admin', 'help']
  }
  if (enrolmentOnly && enrolmentVisible(enrolmentOperator, modules)) {
    return ['enrolment', 'help']
  }
  if (role === 'PARTNER') {
    return modules.partner ? ['partner', 'help'] : ['help']
  }
  // Первый экран «Руководства» — сводка команд с долями и изменениями, операционный «Контроль» — следующей вкладкой.
  const sections: Section[] = role === 'MANAGEMENT' ? ['summary', 'control'] : seesTeams(role) ? ['control'] : []
  sections.push('work', 'issues', 'organizations', 'agreements', 'reports')
  if (enrolmentVisible(enrolmentOperator, modules)) {
    sections.push('enrolment')
  }
  if (role === 'LEADER') {
    sections.push('admin')
  }
  // Планы ИИ-агента открываются по ссылке агента, из колокольчика и по адресу #/agent-plans; в меню их нет. Планы готовят КАМ и руководитель.
  // Без модуля MCP агентов нет вовсе, и ссылка ведёт на первый доступный раздел, а не на «План не найден».
  if (modules.mcp && (role === 'USER' || role === 'LEADER')) {
    sections.push('agent-plans')
  }
  sections.push('help')
  return sections
}

export const navFor = (role: Role, enrolmentOperator: boolean, modules: Modules, enrolmentOnly = false): NavItem[] => {
  if (role === 'ADMIN' || role === 'PARTNER') {
    return []
  }
  if (enrolmentOnly && enrolmentVisible(enrolmentOperator, modules)) {
    return [{ label: 'Зачисление', href: '#/enrolment', sections: ['enrolment'], phone: true }]
  }
  const items: NavItem[] = [
    role === 'MANAGEMENT'
      ? { label: 'Работа', href: '#/summary', sections: ['summary', 'control', 'issues'], phone: true }
      : seesTeams(role)
      ? { label: 'Работа', href: '#/control', sections: ['control', 'issues'], phone: true }
      : { label: 'Работа', href: '#/work', sections: ['work', 'issues'], phone: true },
    { label: 'Вузы', href: '#/organizations', sections: ['organizations'], phone: true },
    { label: 'Договоры', href: '#/agreements', sections: ['agreements'], phone: true },
    { label: 'Отчёты', href: '#/reports', sections: ['reports'], phone: true }
  ]
  if (role === 'LEADER') {
    items.push({ label: 'Настройки команды', href: '#/admin', sections: ['admin'], phone: false })
  }
  if (enrolmentVisible(enrolmentOperator, modules)) {
    items.push({ label: 'Зачисление', href: '#/enrolment', sections: ['enrolment'], phone: true })
  }
  return items
}

export const workTabsFor = (role: Role): WorkTab[] => {
  if (role === 'USER') {
    return [
      { key: 'work', label: 'Моя работа', href: '#/work' },
      { key: 'issues', label: 'Проблемы и риски', href: '#/issues' }
    ]
  }
  if (!seesTeams(role)) {
    return []
  }
  return [
    ...(role === 'MANAGEMENT' ? [{ key: 'summary' as const, label: 'Сводка команд', href: '#/summary' }] : []),
    { key: 'control', label: 'Контроль', href: '#/control' },
    { key: 'issues', label: 'Проблемы и риски', href: '#/issues' }
  ]
}

export const isListView = (query: string) => new URLSearchParams(query).get('view') === 'list'

export const withoutView = (query: string) => {
  const params = new URLSearchParams(query)
  params.delete('view')
  return params.toString()
}

export const activeWorkTab = (section: Section): WorkTab['key'] | null => {
  return section === 'summary' || section === 'control' || section === 'work' || section === 'issues' ? section : null
}

export const workRedirect = (hash: string, role: Role): string | null => {
  const route = parseRoute(hash)
  if (route.section !== 'work' || !seesTeams(role)) {
    return null
  }
  const rest = withoutView(route.query)
  return rest === '' ? '#/control?view=list' : `#/control?view=list&${rest}`
}

const titles: Record<Section, string> = {
  control: 'Контроль',
  work: 'Моя работа',
  issues: 'Проблемы и риски',
  organizations: 'Вузы',
  agreements: 'Договоры',
  reports: 'Отчёты',
  admin: 'Администрирование',
  enrolment: 'Зачисление',
  partner: 'Сотрудничество с ИТ Школой РТК',
  help: 'Справка',
  'agent-plans': 'Планы ИИ-агента',
  summary: 'Сводка команд'
}

export const sectionTitle = (section: Section, role: Role): string => {
  if (section === 'admin' && role === 'LEADER') {
    return 'Настройки команды'
  }
  return titles[section]
}

const roleNames: Record<Role, string> = {
  USER: 'КАМ',
  LEADER: 'Руководитель команды',
  ADMIN: 'Администратор',
  MANAGEMENT: 'Руководство',
  PARTNER: 'Координатор вуза'
}

export const roleName = (role: Role) => roleNames[role]

export const roleInitials = (role: Role) => (
  roleNames[role].split(' ').slice(0, 2).map((word) => word[0].toUpperCase()).join('')
)

export const personInitials = (name: string | null | undefined, role: Role) => {
  const words = (name ?? '').trim().split(/\s+/).filter((word) => /\p{L}/u.test(word[0] ?? ''))
  return words.length === 0 ? roleInitials(role) : words.slice(0, 2).map((word) => word[0].toUpperCase()).join('')
}
