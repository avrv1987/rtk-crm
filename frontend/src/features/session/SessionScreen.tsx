import type { ReactNode } from 'react'
import type { Me } from '../../shared/api/client'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { personInitials, roleName, type NavItem } from '../../app/routing'
import { passwordUrl } from './ActivationRequest'

type SessionScreenProps = {
  title: string
  children?: ReactNode
}

export const SessionScreen = ({ title, children }: SessionScreenProps) => (
  <main className="session-screen">
    <section className="session-card" aria-labelledby="session-title">
      <p className="eyebrow">CRM ИТ Школы РТК</p>
      <h1 id="session-title">{title}</h1>
      {children}
    </section>
  </main>
)

const profileFacts = (profile: Me): [string, string][] => {
  const facts: [string, string][] = profile.displayName
    ? [['Сотрудник', profile.displayName], ['Роль', roleName(profile.role)]]
    : [['Роль', roleName(profile.role)]]
  if (profile.role === 'MANAGEMENT') {
    facts.push(['Область', 'Все команды, только просмотр'])
  } else if (profile.role !== 'PARTNER' && (profile.teamName !== null || profile.role !== 'ADMIN')) {
    facts.push(['Команда', profile.teamName ?? 'Не назначена, вузы недоступны'])
  }
  return facts
}

type ProfileMenuProps = {
  profile: Me
  phoneItems: NavItem[]
  onLogout: () => void
}

export const ProfileMenu = ({ profile, phoneItems, onLogout }: ProfileMenuProps) => {
  const items: ActionMenuItem[] = [
    ...phoneItems.map((item) => ({ label: item.label, href: item.href, phoneOnly: true })),
    { label: 'Сменить пароль', href: passwordUrl, external: true },
    { label: 'Справка', href: '#/help' },
    { label: 'Выйти', onSelect: onLogout, separated: true }
  ]
  return (
    <ActionMenu
      className="profile-menu"
      label={`Профиль: ${profile.displayName ?? roleName(profile.role)}`}
      toggle={(
        <>
          <span className="profile-menu__avatar" aria-hidden="true">{personInitials(profile.displayName, profile.role)}</span>
          <span className="profile-menu__role">{profile.displayName ?? roleName(profile.role)}</span>
        </>
      )}
      header={(
        <dl className="profile-menu__facts">
          {profileFacts(profile).map(([term, value]) => (
            <div key={term}>
              <dt>{term}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      )}
      items={items}
    />
  )
}
