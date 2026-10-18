import { useState, type ReactNode } from 'react'
import type { Me } from '../../shared/api/client'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { personInitials, roleName, type NavItem } from '../../app/routing'
import { passwordUrl } from './ActivationRequest'
import { getModules } from '../../shared/modules'
import { MaxLinkDialog } from '../max/MaxLinkDialog'
import { maxLinkAvailable } from '../max/maxLink'
import { MailPreferencesDialog } from '../mail/MailPreferencesDialog'
import { mailPreferencesAvailable } from '../mail/mailAdmin'

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
  const [maxOpen, setMaxOpen] = useState(false)
  const maxItems: ActionMenuItem[] = maxLinkAvailable(profile, getModules())
    ? [{ label: 'Подключить MAX', onSelect: () => setMaxOpen(true) }]
    : []
  // Ссылка «Настроить уведомления» из письма ведёт на #/mail-settings: окно открывается сразу после входа.
  const mailAvailable = mailPreferencesAvailable(profile, getModules())
  const [mailOpen, setMailOpen] = useState(() => mailAvailable && window.location.hash.startsWith('#/mail-settings'))
  const mailItems: ActionMenuItem[] = mailAvailable
    ? [{ label: 'Уведомления на почту', onSelect: () => setMailOpen(true) }]
    : []
  const items: ActionMenuItem[] = [
    ...phoneItems.map((item) => ({ label: item.label, href: item.href, phoneOnly: true })),
    ...maxItems,
    ...mailItems,
    { label: 'Сменить пароль', href: passwordUrl, external: true },
    { label: 'Справка', href: '#/help' },
    { label: 'Выйти', onSelect: onLogout, separated: true }
  ]
  return (
    <>
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
      {maxItems.length > 0 && <MaxLinkDialog open={maxOpen} onClose={() => setMaxOpen(false)} />}
      {mailAvailable && <MailPreferencesDialog open={mailOpen} onClose={() => setMailOpen(false)} />}
    </>
  )
}
