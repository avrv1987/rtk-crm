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
  USER: 'КАМ',
  LEADER: 'Руководитель команды',
  ADMIN: 'Администратор',
  MANAGEMENT: 'Руководство',
  PARTNER: 'Представитель вуза'
}

export const ProfileSummary = ({ profile }: { profile: Me }) => (
  <dl className="profile-summary">
    <div className="profile-summary__chip" title="Роль в CRM">
      <dt>Роль</dt>
      <dd>{roleNames[profile.role]}</dd>
    </div>
    {profile.role === 'MANAGEMENT' && (
      <div className="profile-summary__chip" title="Область видимости данных">
        <dt>Область</dt>
        <dd>Все команды, только просмотр</dd>
      </div>
    )}
    {profile.role !== 'MANAGEMENT' && profile.role !== 'PARTNER' && (profile.teamName !== null || profile.role !== 'ADMIN') && (
      <div className="profile-summary__chip" title="Команда">
        <dt>Команда</dt>
        <dd>{profile.teamName ?? 'Не назначена, вузы недоступны'}</dd>
      </div>
    )}
  </dl>
)
