import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, apiClient, type Me } from '../shared/api/client'
import { AdminCatalogsPanel } from '../features/admin/AdminCatalogsPanel'
import { AdminSectionNav } from '../features/admin/AdminSectionNav'
import { ActivityKindsPanel } from '../features/agreements/ActivityKindsPanel'
import { AgreementConfirmationsPanel } from '../features/agreements/AgreementConfirmationsPanel'
import { CatalogImportPanel } from '../features/admin/CatalogImportPanel'
import { AdminProfilesScreen } from '../features/admin/AdminProfilesScreen'
import { SourcesPanel } from '../features/admin/SourcesPanel'
import { AuditJournalPanel } from '../features/admin/AuditJournalPanel'
import { EnrolmentScreen } from '../features/enrolment/EnrolmentScreen'
import { PersonalDataPanel } from '../features/admin/PersonalDataPanel'
import { RetentionPanel } from '../features/admin/RetentionPanel'
import { ActivationRequest, PasswordLink } from '../features/session/ActivationRequest'
import { PendingSourceRecords } from '../features/sources/PendingSourceRecords'
import { SourceAlerts } from '../features/sources/SourceAlerts'
import { WorkflowTemplatesPanel } from '../features/admin/WorkflowTemplatesPanel'
import {
  clearDraftsForProfile,
  clearRememberedProfileDrafts,
  forgetActiveProfile,
  rememberActiveProfile,
  rememberReturnRoute,
  takeReturnRoute
} from '../features/interactions/drafts'
import { UnsavedDraftNotice } from '../features/interactions/UnsavedDraftNotice'
import { HelpScreen } from '../features/help/HelpScreen'
import { LandingPage } from '../features/landing/LandingPage'
import { OrganizationsScreen } from '../features/organizations/OrganizationsScreen'
import { KamReviewPanel } from '../features/reports/KamReviewPanel'
import { ReportsScreen } from '../features/reports/ReportsScreen'
import { ProfileSummary, SessionScreen } from '../features/session/SessionScreen'
import { ReminderCenter } from '../features/work/ReminderCenter'
import { WorkScreen } from '../features/work/WorkScreen'
import { SupportDetails } from '../shared/ui/SupportDetails'

type SessionState =
  | { kind: 'loading' }
  | { kind: 'anonymous' }
  | { kind: 'profile'; profile: Me }
  | { kind: 'forbidden'; requestId: string; pending: boolean }
  | { kind: 'failed' }
  | { kind: 'logoutFailed' }

type Section = 'work' | 'organizations' | 'reports' | 'admin' | 'enrolment' | 'help'

const sectionTitles: Record<Section, string> = {
  work: 'Моя работа',
  organizations: 'Вузы',
  reports: 'Отчёты и статистика',
  admin: 'Администрирование',
  enrolment: 'Зачисление',
  help: 'Справка'
}

const sectionsFor = (profile: Me): Section[] => {
  if (profile.role === 'ADMIN') {
    return ['admin', 'help']
  }
  if (profile.enrolmentOperator) {
    return ['work', 'organizations', 'reports', 'enrolment', 'help']
  }
  return ['work', 'organizations', 'reports', 'help']
}

const parseRoute = (hash: string) => {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  const [section = '', organizationId, interactionId] = path.split('/')
  return { section, organizationId, interactionId, query }
}

export const App = () => {
  const [state, setState] = useState<SessionState>({ kind: 'loading' })
  const [hash, setHash] = useState(() => window.location.hash)
  const [sourcesRevision, setSourcesRevision] = useState(0)
  const activeProfileId = useRef<string | null>(null)
  const route = parseRoute(hash)

  useEffect(() => {
    void loadSession()
  }, [])

  useEffect(() => {
    if (state.kind === 'anonymous') {
      rememberReturnRoute()
    }
  }, [state.kind, hash])

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
      const returnRoute = takeReturnRoute()
      if (returnRoute !== null && returnRoute !== window.location.hash) {
        window.location.hash = returnRoute
      }
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
  if ((state.kind === 'anonymous' || state.kind === 'forbidden') && route.section === 'help') {
    return (
      <div className="app-shell">
        <header className="app-header">
          <p className="app-header__brand">CRM ИТ Школы РТК</p>
          <nav className="app-nav" aria-label="Разделы CRM">
            <ul>
              <li><a href="#/" aria-current="page">Ко входу</a></li>
            </ul>
          </nav>
        </header>
        <main className="app-main">
          <h1>Справка</h1>
          <div className="app-content">
            <HelpScreen />
          </div>
        </main>
      </div>
    )
  }
  if (state.kind === 'anonymous') {
    return (
      <LandingPage onLogin={() => apiClient.login()} />
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
        {state.pending && <ActivationRequest onSessionExpired={handleSessionExpired} />}
        <SupportDetails requestId={state.requestId} />
        <p><a href="#/help">Открыть справку</a></p>
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
  const sections = sectionsFor(state.profile)
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
          {(state.profile.role === 'USER' || state.profile.role === 'LEADER') && (
            <ReminderCenter
              profileId={state.profile.id}
              refreshKey={section}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          <ProfileSummary profile={state.profile} />
          <PasswordLink />
          <button type="button" className="button--secondary" onClick={() => void logout()}>Выйти</button>
        </div>
      </header>
      <main className="app-main">
        <h1>{sectionTitles[section]}</h1>
        <div className="app-content">
          {section === 'admin' && (
            <>
              <AdminSectionNav />
              <SourceAlerts
                onSessionExpired={handleSessionExpired}
                onProfileUnavailable={handleProfileUnavailable}
                refreshSignal={sourcesRevision}
              />
              <AdminProfilesScreen
                currentProfile={state.profile}
                onSessionExpired={handleSessionExpired}
                onProfileUnavailable={handleProfileUnavailable}
              />
              <AdminCatalogsPanel onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
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
                onSynced={() => setSourcesRevision((value) => value + 1)}
              />
              <AuditJournalPanel onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
              <PersonalDataPanel onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
              <RetentionPanel onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
              <ActivityKindsPanel
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
              <PendingSourceRecords
                role={state.profile.role}
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
            <>
              <ReportsScreen
                profileId={state.profile.id}
                role={state.profile.role}
                onSessionExpired={handleSessionExpired}
                onProfileUnavailable={handleProfileUnavailable}
              />
              <AgreementConfirmationsPanel
                onSessionExpired={handleSessionExpired}
                onProfileUnavailable={handleProfileUnavailable}
              />
            </>
          )}
          {section === 'reports' && (
            <KamReviewPanel onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
          )}
          {section === 'enrolment' && (
            <EnrolmentScreen onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
          )}
          {section === 'help' && <HelpScreen />}
        </div>
        <UnsavedDraftNotice />
      </main>
    </div>
  )
}
