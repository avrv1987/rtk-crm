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

export const ProfileSummary = ({ profile }: { profile: Me }) => (
  <dl className="profile-summary">
    <div>
      <dt>Роль</dt>
      <dd>{profile.role === 'ADMIN' ? 'Администратор' : profile.role === 'LEADER' ? 'Руководитель команды' : 'Менеджер'}</dd>
    </div>
    {(profile.teamName !== null || profile.role !== 'ADMIN') && (
      <div>
        <dt>Команда</dt>
        <dd>{profile.teamName ?? 'Не назначена, вузы недоступны'}</dd>
      </div>
    )}
  </dl>
)
