import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, apiClient, type Me } from '../shared/api/client'
import { AdminCatalogsPanel } from '../features/admin/AdminCatalogsPanel'
import { AdminSectionNav, resolveAdminSection } from '../features/admin/AdminSectionNav'
import { ActivityKindsPanel } from '../features/agreements/ActivityKindsPanel'
import { ControlScreen } from '../features/control/ControlScreen'
import { CatalogImportPanel } from '../features/admin/CatalogImportPanel'
import { AdminProfilesScreen } from '../features/admin/AdminProfilesScreen'
import { SourcesPanel } from '../features/admin/SourcesPanel'
import { AuditJournalPanel } from '../features/admin/AuditJournalPanel'
import { EnrolmentScreen } from '../features/enrolment/EnrolmentScreen'
import { PersonalDataPanel } from '../features/admin/PersonalDataPanel'
import { RetentionPanel } from '../features/admin/RetentionPanel'
import { ActivationRequest, PasswordLink, keycloakLogoutUrl } from '../features/session/ActivationRequest'
import { announceSession, watchSessionElsewhere } from '../features/session/sessionSync'
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
import { IssuesScreen } from '../features/issues/IssuesScreen'
import { isSameRouteClick } from './routing'
import { LandingPage } from '../features/landing/LandingPage'
import { OrganizationsScreen } from '../features/organizations/OrganizationsScreen'
import { PartnerCabinet } from '../features/partner/PartnerCabinet'
import { ReportsScreen } from '../features/reports/ReportsScreen'
import { ProfileSummary, SessionScreen } from '../features/session/SessionScreen'
import { ReminderCenter } from '../features/work/ReminderCenter'
import { WorkScreen } from '../features/work/WorkScreen'
import { getModules, sectionAvailable, setModules, type Modules } from '../shared/modules'
import { SupportDetails } from '../shared/ui/SupportDetails'

type SessionState =
  | { kind: 'loading' }
  | { kind: 'anonymous' }
  | { kind: 'profile'; profile: Me }
  | { kind: 'forbidden'; requestId: string; pending: boolean; partnerOff?: boolean }
  | { kind: 'failed' }

type Section = 'control' | 'work' | 'issues' | 'organizations' | 'reports' | 'admin' | 'enrolment' | 'partner' | 'help'

const sectionTitles: Record<Section, string> = {
  control: 'Контроль',
  work: 'Моя работа',
  issues: 'Проблемы и риски',
  organizations: 'Вузы',
  reports: 'Отчёты и статистика',
  admin: 'Администрирование',
  enrolment: 'Зачисление',
  partner: 'Кабинет вуза',
  help: 'Справка'
}

const sectionTitle = (section: Section, profile: Me): string => (
  section === 'work' && profile.role === 'MANAGEMENT' ? 'Сводка по всем командам' : sectionTitles[section]
)

const sectionsFor = (profile: Me, modules: Modules): Section[] => {
  if (profile.role === 'ADMIN') {
    return ['admin', 'help']
  }
  if (profile.role === 'PARTNER') {
    return ['partner', 'help'].filter((item) => sectionAvailable(item, modules)) as Section[]
  }
  const sections: Section[] = ['work', 'issues', 'organizations', 'reports']
  if (profile.role === 'LEADER' || profile.role === 'MANAGEMENT') {
    sections.unshift('control')
  }
  if (sectionAvailable('enrolment', modules) && profile.enrolmentOperator) {
    sections.push('enrolment')
  }
  if (profile.role === 'LEADER') {
    sections.push('admin')
  }
  sections.push('help')
  return sections
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
  const [routePulse, setRoutePulse] = useState(0)
  const activeProfileId = useRef<string | null>(null)
  const route = parseRoute(hash)
  const watchedProfileId = state.kind === 'profile' ? state.profile.id : null

  useEffect(() => {
    void loadSession()
  }, [])

  useEffect(() => (
    watchedProfileId === null ? undefined : watchSessionElsewhere(watchedProfileId, () => window.location.assign('/'))
  ), [watchedProfileId])

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

  useEffect(() => {
    const handleClick = (event: MouseEvent) => {
      const anchor = event.target instanceof Element ? event.target.closest('a[href^="#"]') : null
      if (anchor instanceof HTMLAnchorElement && isSameRouteClick(anchor.getAttribute('href'), window.location.hash)) {
        setRoutePulse((value) => value + 1)
      }
    }
    document.addEventListener('click', handleClick)
    return () => document.removeEventListener('click', handleClick)
  }, [])

  const loadSession = async () => {
    try {
      setModules((await apiClient.config()).modules)
      const profile = await apiClient.me()
      await apiClient.refreshCsrf()
      rememberActiveProfile(profile.id)
      announceSession(profile.id)
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
      if (error instanceof ApiError && error.code === 'PARTNER_MODULE_DISABLED') {
        clearRememberedProfileDrafts()
        setState({ kind: 'forbidden', requestId: error.requestId, pending: false, partnerOff: true })
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
      window.location.assign(keycloakLogoutUrl())
    } finally {
      if (profileId !== null) {
        clearDraftsForProfile(profileId)
      }
      forgetActiveProfile()
      announceSession(null)
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
            <HelpScreen initialQuery={route.query} />
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
      <SessionScreen title={state.partnerOff ? 'Кабинет вуза не подключён' : state.pending ? 'Профиль ожидает активации администратором' : 'Профиль CRM недоступен'}>
        <p>
          {state.partnerOff
            ? 'В этой установке CRM кабинет вуза выключен. Обратитесь к вашему менеджеру в ИТ Школе РТК.'
            : state.pending
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
  if (state.kind === 'failed') {
    return (
      <SessionScreen title="Не удалось проверить сессию">
        <p>CRM сейчас не отвечает или вход устарел. Войдите заново. Если не получится, повторите через несколько минут.</p>
        <p>Несохранённые черновики на этом устройстве останутся.</p>
        <div className="session-help">
          <button type="button" onClick={() => apiClient.login()}>Войти заново</button>
          <button type="button" className="button--secondary" onClick={() => void loadSession()}>Повторить</button>
        </div>
      </SessionScreen>
    )
  }
  const sections = sectionsFor(state.profile, getModules())
  const section = sections.find((item) => item === route.section) ?? sections[0]
  const adminNavRole = state.profile.role === 'LEADER' ? 'LEADER' : 'ADMIN'
  const adminSection = resolveAdminSection(route.organizationId, adminNavRole)

  return (
    <div className="app-shell">
      <header className="app-header">
        <p className="app-header__brand">CRM ИТ Школы РТК</p>
        <nav className="app-nav" aria-label="Разделы CRM">
          <ul>
            {sections.map((item) => (
              <li key={item}>
                <a href={`#/${item}`} aria-current={item === section ? 'page' : undefined}>{sectionTitle(item, state.profile)}</a>
              </li>
            ))}
          </ul>
        </nav>
        <div className="app-header__profile">
          {(state.profile.role === 'USER' || state.profile.role === 'LEADER') && (
            <ReminderCenter
              profileId={state.profile.id}
              refreshKey={`${section}:${routePulse}`}
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
        <h1>{sectionTitle(section, state.profile)}</h1>
        <div className="app-content">
          {section === 'admin' && (
            <>
              <AdminSectionNav active={adminSection} role={adminNavRole} />
              {state.profile.role === 'ADMIN' && adminSection === 'profiles' && (
                <div className="notice admin-role-notice" role="note">
                  <p><strong>Администратор управляет доступом и настройками и не видит работу с вузами.</strong></p>
                  <p>Чтобы посмотреть работу с вузами, войдите руководителем команды или менеджером.</p>
                  <button type="button" className="button--secondary" onClick={() => void logout()}>Выйти</button>
                </div>
              )}
              {state.profile.role === 'ADMIN' && (
                <SourceAlerts
                  onSessionExpired={handleSessionExpired}
                  onProfileUnavailable={handleProfileUnavailable}
                  refreshSignal={sourcesRevision}
                />
              )}
              {adminSection === 'pending-source-records' && (
                <PendingSourceRecords
                  role={state.profile.role}
                  onSessionExpired={handleSessionExpired}
                  onProfileUnavailable={handleProfileUnavailable}
                />
              )}
              {adminSection === 'profiles' && (
                <AdminProfilesScreen
                  currentProfile={state.profile}
                  onSessionExpired={handleSessionExpired}
                  onProfileUnavailable={handleProfileUnavailable}
                />
              )}
              {adminSection === 'catalogs' && (
                <AdminCatalogsPanel onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
              )}
              {adminSection === 'workflow-templates' && (
                <WorkflowTemplatesPanel
                  role={state.profile.role}
                  onSessionExpired={handleSessionExpired}
                  onProfileUnavailable={handleProfileUnavailable}
                />
              )}
              {adminSection === 'catalog-import' && (
                <CatalogImportPanel
                  onSessionExpired={handleSessionExpired}
                  onProfileUnavailable={handleProfileUnavailable}
                />
              )}
              {adminSection === 'sources' && (
                <SourcesPanel
                  onSessionExpired={handleSessionExpired}
                  onProfileUnavailable={handleProfileUnavailable}
                  onSynced={() => setSourcesRevision((value) => value + 1)}
                />
              )}
              {adminSection === 'journal' && (
                <AuditJournalPanel onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
              )}
              {adminSection === 'personal-data' && (
                <PersonalDataPanel onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
              )}
              {adminSection === 'retention' && (
                <RetentionPanel onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
              )}
              {adminSection === 'activity-kinds' && (
                <ActivityKindsPanel
                  onSessionExpired={handleSessionExpired}
                  onProfileUnavailable={handleProfileUnavailable}
                />
              )}
            </>
          )}
          {section === 'control' && (
            <ControlScreen
              role={state.profile.role}
              initialQuery={route.query}
              refreshSignal={routePulse}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'work' && (
            <WorkScreen
              role={state.profile.role}
              initialQuery={route.query}
              refreshSignal={routePulse}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'issues' && (
            <IssuesScreen
              key={routePulse}
              profile={state.profile}
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
              {state.profile.role === 'USER' && (
                <PendingSourceRecords
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
              role={state.profile.role}
              view={route.organizationId}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'enrolment' && (
            <EnrolmentScreen onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
          )}
          {section === 'partner' && (
            <PartnerCabinet onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
          )}
          {section === 'help' && <HelpScreen initialQuery={route.query} />}
        </div>
        <UnsavedDraftNotice />
      </main>
    </div>
  )
}
