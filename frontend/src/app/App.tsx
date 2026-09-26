import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, apiClient, type Me } from '../shared/api/client'
import { CatalogImportPanel } from '../features/admin/CatalogImportPanel'
import { AdminProfilesScreen } from '../features/admin/AdminProfilesScreen'
import { SourcesPanel } from '../features/admin/SourcesPanel'
import { WorkflowTemplatesPanel } from '../features/admin/WorkflowTemplatesPanel'
import {
  clearDraftsForProfile,
  clearRememberedProfileDrafts,
  forgetActiveProfile,
  rememberActiveProfile
} from '../features/interactions/drafts'
import { OrganizationsScreen } from '../features/organizations/OrganizationsScreen'
import { ReportsScreen } from '../features/reports/ReportsScreen'
import { ProfileSummary, SessionScreen } from '../features/session/SessionScreen'
import { WorkScreen } from '../features/work/WorkScreen'
import { SupportDetails } from '../shared/ui/SupportDetails'

type SessionState =
  | { kind: 'loading' }
  | { kind: 'anonymous' }
  | { kind: 'profile'; profile: Me }
  | { kind: 'forbidden'; requestId: string; pending: boolean }
  | { kind: 'failed' }
  | { kind: 'logoutFailed' }

type Section = 'work' | 'organizations' | 'reports' | 'admin'

const sectionTitles: Record<Section, string> = {
  work: 'Моя работа',
  organizations: 'Вузы',
  reports: 'Отчёты и статистика',
  admin: 'Администрирование'
}

const sectionsFor = (role: Me['role']): Section[] => (
  role === 'ADMIN' ? ['admin'] : ['work', 'organizations', 'reports']
)

const parseRoute = (hash: string) => {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  const [section = '', organizationId, interactionId] = path.split('/')
  return { section, organizationId, interactionId, query }
}

export const App = () => {
  const [state, setState] = useState<SessionState>({ kind: 'loading' })
  const [hash, setHash] = useState(() => window.location.hash)
  const activeProfileId = useRef<string | null>(null)

  useEffect(() => {
    void loadSession()
  }, [])

  useEffect(() => {
    const syncHash = () => setHash(window.location.hash)
    window.addEventListener('hashchange', syncHash)
    return () => window.removeEventListener('hashchange', syncHash)
  }, [])

  const loadSession = async () => {
    try {
      const profile = await apiClient.me()
      await apiClient.refreshCsrf()
      rememberActiveProfile(profile.id)
      activeProfileId.current = profile.id
      setState({ kind: 'profile', profile })
    } catch (error) {
      if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
        setState({ kind: 'anonymous' })
        return
      }
      if (error instanceof ApiError && (error.code === 'CRM_PROFILE_REQUIRED' || error.code === 'CRM_PROFILE_PENDING')) {
        clearRememberedProfileDrafts()
        setState({ kind: 'forbidden', requestId: error.requestId, pending: error.code === 'CRM_PROFILE_PENDING' })
        return
      }
      setState({ kind: 'failed' })
    }
  }

  const logout = async () => {
    const profileId = state.kind === 'profile' ? state.profile.id : null
    try {
      const { logoutUrl } = await apiClient.logout()
      window.location.assign(logoutUrl)
    } catch {
      setState({ kind: 'logoutFailed' })
    } finally {
      if (profileId !== null) {
        clearDraftsForProfile(profileId)
      }
      forgetActiveProfile()
    }
  }

  const handleSessionExpired = useCallback(() => {
    activeProfileId.current = null
    setState({ kind: 'anonymous' })
  }, [])

  const handleProfileUnavailable = useCallback((requestId: string) => {
    if (activeProfileId.current !== null) {
      clearDraftsForProfile(activeProfileId.current)
      activeProfileId.current = null
    }
    forgetActiveProfile()
    setState({ kind: 'forbidden', requestId, pending: false })
  }, [])

  if (state.kind === 'loading') {
    return <SessionScreen title="Проверяем сессию" />
  }
  if (state.kind === 'anonymous') {
    return (
      <SessionScreen title="Вход в CRM">
        <p>Используйте корпоративную учётную запись через защищённый вход.</p>
        <button type="button" onClick={() => apiClient.login()}>Войти</button>
      </SessionScreen>
    )
  }
  if (state.kind === 'forbidden') {
    return (
      <SessionScreen title={state.pending ? 'Профиль ожидает активации администратором' : 'Профиль CRM недоступен'}>
        <p>
          {state.pending
            ? 'Учётная запись подтверждена, профиль CRM создан. Администратор назначит роль и команду, после этого откроется доступ к данным.'
            : 'Учётная запись успешно подтверждена, но активный профиль CRM отсутствует или заблокирован. Обратитесь к администратору CRM.'}
        </p>
        <SupportDetails requestId={state.requestId} />
        <button type="button" className="button--secondary" onClick={() => void logout()}>Выйти</button>
      </SessionScreen>
    )
  }
  if (state.kind === 'logoutFailed') {
    return (
      <SessionScreen title="Выход не завершён">
        <p>Сервер не подтвердил выход. Черновики на этом устройстве удалены.</p>
        <button type="button" onClick={() => void logout()}>Повторить выход</button>
      </SessionScreen>
    )
  }
  if (state.kind === 'failed') {
    return (
      <SessionScreen title="Не удалось проверить сессию">
        <p>Сервис не ответил. Повторите попытку позже; несохранённые черновики на этом устройстве остаются.</p>
        <button type="button" onClick={() => void loadSession()}>Повторить</button>
      </SessionScreen>
    )
  }
  const sections = sectionsFor(state.profile.role)
  const route = parseRoute(hash)
  const section = sections.find((item) => item === route.section) ?? sections[0]

  return (
    <div className="app-shell">
      <header className="app-header">
        <p className="app-header__brand">CRM ИТ Школы РТК</p>
        <nav className="app-nav" aria-label="Разделы CRM">
          <ul>
            {sections.map((item) => (
              <li key={item}>
                <a href={`#/${item}`} aria-current={item === section ? 'page' : undefined}>{sectionTitles[item]}</a>
              </li>
            ))}
          </ul>
        </nav>
        <div className="app-header__profile">
          <ProfileSummary profile={state.profile} />
          <button type="button" className="button--secondary" onClick={() => void logout()}>Выйти</button>
        </div>
      </header>
      <main className="app-main">
        <h1>{sectionTitles[section]}</h1>
        <div className="app-content">
          {section === 'admin' && (
            <>
              <AdminProfilesScreen
                currentProfile={state.profile}
                onSessionExpired={handleSessionExpired}
                onProfileUnavailable={handleProfileUnavailable}
              />
              <WorkflowTemplatesPanel
                role={state.profile.role}
                onSessionExpired={handleSessionExpired}
                onProfileUnavailable={handleProfileUnavailable}
              />
              <CatalogImportPanel
                onSessionExpired={handleSessionExpired}
                onProfileUnavailable={handleProfileUnavailable}
              />
              <SourcesPanel
                onSessionExpired={handleSessionExpired}
                onProfileUnavailable={handleProfileUnavailable}
              />
            </>
          )}
          {section === 'work' && (
            <WorkScreen
              role={state.profile.role}
              initialQuery={route.query}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'organizations' && (
            <>
              <OrganizationsScreen
                profileId={state.profile.id}
                role={state.profile.role}
                selectedOrganizationId={route.organizationId}
                selectedInteractionId={route.interactionId}
                query={route.query}
                onSessionExpired={handleSessionExpired}
                onProfileUnavailable={handleProfileUnavailable}
              />
              {state.profile.role === 'LEADER' && (
                <WorkflowTemplatesPanel
                  role={state.profile.role}
                  onSessionExpired={handleSessionExpired}
                  onProfileUnavailable={handleProfileUnavailable}
                />
              )}
            </>
          )}
          {section === 'reports' && (
            <ReportsScreen
              profileId={state.profile.id}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
        </div>
      </main>
    </div>
  )
}
