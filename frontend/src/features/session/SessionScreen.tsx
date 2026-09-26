import type { ReactNode } from 'react'
import type { Me } from '../../shared/api/client'

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

const roleNames: Record<Me['role'], string> = {
  USER: 'Менеджер',
  LEADER: 'Руководитель команды',
  ADMIN: 'Администратор',
  MANAGEMENT: 'Руководство'
}

export const ProfileSummary = ({ profile }: { profile: Me }) => (
  <dl className="profile-summary">
    <div>
      <dt>Роль</dt>
      <dd>{roleNames[profile.role]}</dd>
    </div>
    {profile.role === 'MANAGEMENT' && (
      <div>
        <dt>Область</dt>
        <dd>Все команды, только просмотр</dd>
      </div>
    )}
    {profile.role !== 'MANAGEMENT' && (profile.teamName !== null || profile.role !== 'ADMIN') && (
      <div>
        <dt>Команда</dt>
        <dd>{profile.teamName ?? 'Не назначена, вузы недоступны'}</dd>
      </div>
    )}
  </dl>
)
