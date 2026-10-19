import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, apiClient, type Me } from '../shared/api/client'
import { AdminScreen, adminNoticeText } from '../features/admin/AdminScreen'
import { AgentPlanScreen } from '../features/agentPlans/AgentPlanScreen'
import { AgreementRegistry } from '../features/agreements/AgreementRegistry'
import { ControlScreen } from '../features/control/ControlScreen'
import { TeamsOverview } from '../features/control/TeamsOverview'
import { EnrolmentScreen } from '../features/enrolment/EnrolmentScreen'
import { ActivationRequest, keycloakLogoutUrl } from '../features/session/ActivationRequest'
import { announceSession, watchSessionElsewhere } from '../features/session/sessionSync'
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
import {
  activeWorkTab,
  canonicalAddress,
  isListView,
  isSameRouteClick,
  isSameSectionClick,
  navFor,
  parseRoute,
  sectionTitle,
  sectionsFor,
  workRedirect,
  workTabsFor,
  type NavItem
} from './routing'
import { LandingPage } from '../features/landing/LandingPage'
import { InvitationScreen } from '../features/mail/InvitationScreen'
import { invitationToken } from '../features/mail/mailAdmin'
import { OrganizationsScreen } from '../features/organizations/OrganizationsScreen'
import { PartnerCabinet } from '../features/partner/PartnerCabinet'
import { ReportsScreen } from '../features/reports/ReportsScreen'
import { ProfileMenu, SessionScreen } from '../features/session/SessionScreen'
import { ReminderCenter } from '../features/work/ReminderCenter'
import { WorkScreen } from '../features/work/WorkScreen'
import { getModules, setModules } from '../shared/modules'
import { ActionMenu } from '../shared/ui/ActionMenu'
import { HintButton } from '../shared/ui/HintButton'
import { SupportDetails } from '../shared/ui/SupportDetails'

type SessionState =
  | { kind: 'loading' }
  | { kind: 'anonymous' }
  | { kind: 'profile'; profile: Me }
  | { kind: 'forbidden'; requestId: string; pending: boolean; partnerOff?: boolean }
  | { kind: 'failed' }

const NavIcon = ({ href }: { href: string }) => {
  const paths: Record<string, string> = {
    '#/control': 'M4 5h16M4 12h16M4 19h10',
    '#/work': 'M4 5h16M4 12h16M4 19h10',
    '#/organizations': 'M3 21h18M5 21V9l7-5 7 5v12M10 21v-6h4v6',
    '#/agreements': 'M7 3h7l4 4v14H7zM14 3v4h4M10 12h5M10 16h5',
    '#/reports': 'M5 20V10M12 20V4M19 20v-7',
    more: 'M5 12h.01M12 12h.01M19 12h.01',
    '#/enrolment': 'M17 20v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2M10 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7M21 20v-2a3.5 3.5 0 0 0-3-3.4M16 4.2a3.5 3.5 0 0 1 0 6.6'
  }
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={paths[href]} />
    </svg>
  )
}

const isActive = (item: NavItem, section: string) => item.sections.some((value) => value === section)

export const App = () => {
  const [state, setState] = useState<SessionState>({ kind: 'loading' })
  const [hash, setHash] = useState(() => window.location.hash)
  const [routePulse, setRoutePulse] = useState(0)
  const activeProfileId = useRef<string | null>(null)
  const sectionNavPending = useRef(false)
  const redirect = state.kind === 'profile' ? workRedirect(hash, state.profile.role) : null
  const route = parseRoute(redirect ?? hash)
  const watchedProfileId = state.kind === 'profile' ? state.profile.id : null

  useEffect(() => {
    void loadSession()
  }, [])

  useEffect(() => {
    if (redirect !== null) {
      window.history.replaceState(null, '', redirect)
      setHash(redirect)
    }
  }, [redirect])

  useEffect(() => {
    // Адрес приглашения не подменяется разделом CRM: страницу ссылки из письма открывают и в чужой сессии.
    if (state.kind !== 'profile' || invitationToken(window.location.hash) !== null) {
      return
    }
    const sections = sectionsFor(state.profile.role, state.profile.enrolmentOperator, getModules(), state.profile.enrolmentOnly)
    const target = canonicalAddress(route, sections)
    if (target !== null) {
      window.history.replaceState(null, '', target)
      setHash(target)
    }
  }, [route.section, state])

  useEffect(() => (
    watchedProfileId === null ? undefined : watchSessionElsewhere(watchedProfileId, () => window.location.assign('/'))
  ), [watchedProfileId])

  useEffect(() => {
    if (state.kind === 'anonymous') {
      rememberReturnRoute()
    }
  }, [state.kind, hash])

  useEffect(() => {
    const syncHash = () => {
      setHash(window.location.hash)
      if (sectionNavPending.current) {
        sectionNavPending.current = false
        setRoutePulse((value) => value + 1)
      }
    }
    window.addEventListener('hashchange', syncHash)
    return () => window.removeEventListener('hashchange', syncHash)
  }, [])

  useEffect(() => {
    const handleClick = (event: MouseEvent) => {
      const anchor = event.target instanceof Element ? event.target.closest('a[href^="#"]') : null
      const href = anchor instanceof HTMLAnchorElement ? anchor.getAttribute('href') : null
      if (isSameRouteClick(href, window.location.hash)) {
        setRoutePulse((value) => value + 1)
      } else if (anchor?.closest('.app-nav, .app-tabbar') && isSameSectionClick(href, window.location.hash)) {
        // Экран пересоздаётся вместе со сменой адреса (в hashchange), чтобы начать с нового адреса, а не с прежних отборов.
        sectionNavPending.current = true
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

  // Истёкший сеанс объясняется словами (Н-14, 2.5): черновики остаются во вкладке и вернутся после входа.
  const [sessionExpired, setSessionExpired] = useState(false)
  const handleSessionExpired = useCallback(() => {
    activeProfileId.current = null
    setSessionExpired(true)
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

  // Ссылка из письма-приглашения открывается без входа. Если в браузере уже есть сессия сотрудника, экран просит выйти и
  // ссылку не гасит.
  const inviteToken = invitationToken(hash)
  if (inviteToken !== null) {
    return (
      <InvitationScreen
        token={inviteToken}
        sessionChecking={state.kind === 'loading'}
        signedInAs={state.kind === 'profile' ? state.profile.displayName : null}
        onSignOut={() => void logout()}
      />
    )
  }
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
      <LandingPage
        onLogin={() => apiClient.login()}
        notice={sessionExpired ? 'Сеанс истёк. Войдите снова — вы вернётесь на тот же экран, несохранённый текст восстановится.' : null}
      />
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
  const { profile } = state
  const modules = getModules()
  const sections = sectionsFor(profile.role, profile.enrolmentOperator, modules, profile.enrolmentOnly)
  const section = sections.find((item) => item === route.section) ?? sections[0]
  const nav = navFor(profile.role, profile.enrolmentOperator, modules, profile.enrolmentOnly)
  const workTab = activeWorkTab(section)
  const workTabs = workTab === null ? [] : workTabsFor(profile.role)
  const title = sectionTitle(section, profile.role)

  return (
    <div className={nav.some((item) => item.phone) ? 'app-shell app-shell--tabbar' : 'app-shell'}>
      <header className="app-header">
        <p className="app-header__brand">
          <span className="app-header__brand-full">CRM ИТ Школы РТК</span>
          <span className="app-header__brand-short" aria-hidden="true">CRM РТК</span>
        </p>
        {nav.length > 0 && (
          <nav className="app-nav" aria-label="Разделы CRM">
            <ul>
              {nav.map((item) => (
                <li key={item.href}>
                  <a href={item.href} aria-current={isActive(item, section) ? 'page' : undefined}>{item.label}</a>
                </li>
              ))}
            </ul>
          </nav>
        )}
        <div className="app-header__profile">
          {(profile.role === 'USER' || profile.role === 'LEADER') && (
            <ReminderCenter
              profileId={profile.id}
              refreshKey={`${section}:${routePulse}`}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          <ProfileMenu profile={profile} phoneItems={nav.filter((item) => !item.phone)} onLogout={() => void logout()} />
        </div>
      </header>
      {workTabs.length > 0 && (
        <nav className="app-subnav" aria-label="Работа">
          <ul>
            {workTabs.map((tab) => (
              <li key={tab.key}>
                <a href={tab.href} aria-current={tab.key === workTab ? 'page' : undefined}>{tab.label}</a>
              </li>
            ))}
          </ul>
        </nav>
      )}
      <main className="app-main">
        <div className="page-title">
          <h1>{title}</h1>
          {section === 'admin' && profile.role === 'ADMIN' && (
            <HintButton label="Что видит администратор">{adminNoticeText}</HintButton>
          )}
        </div>
        <div className="app-content">
          {section === 'admin' && (
            <AdminScreen
              profile={profile}
              slug={route.organizationId}
              query={route.query}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'summary' && (
            <TeamsOverview key={routePulse} onSessionExpired={handleSessionExpired} onProfileUnavailable={handleProfileUnavailable} />
          )}
          {section === 'control' && (
            <ControlScreen
              key={isListView(route.query) ? 'list' : 'board'}
              role={profile.role}
              profileId={profile.id}
              initialQuery={route.query}
              refreshSignal={routePulse}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'work' && (
            <WorkScreen
              role={profile.role}
              profileId={profile.id}
              initialQuery={route.query}
              refreshSignal={routePulse}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'issues' && (
            <IssuesScreen
              key={routePulse}
              profile={profile}
              initialQuery={route.query}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'organizations' && (
            <OrganizationsScreen
              key={routePulse}
              profileId={profile.id}
              role={profile.role}
              selectedOrganizationId={route.organizationId}
              selectedInteractionId={route.interactionId}
              query={route.query}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'agreements' && (
            <AgreementRegistry
              key={routePulse}
              role={profile.role}
              initialQuery={route.query}
              unknownAddress={route.organizationId !== undefined && route.organizationId.length > 0}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
          {section === 'reports' && (
            <ReportsScreen
              key={routePulse}
              profileId={profile.id}
              role={profile.role}
              view={route.organizationId}
              query={route.query}
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
          {section === 'agent-plans' && (
            <AgentPlanScreen
              key={route.organizationId}
              planId={route.organizationId}
              onSessionExpired={handleSessionExpired}
              onProfileUnavailable={handleProfileUnavailable}
            />
          )}
        </div>
        <UnsavedDraftNotice />
      </main>
      {nav.some((item) => item.phone) && (
        <nav className="app-tabbar" aria-label="Разделы CRM на телефоне">
          <ul>
            {nav.filter((item) => item.phone).map((item) => (
              <li key={item.href}>
                <a href={item.href} aria-current={isActive(item, section) ? 'page' : undefined}>
                  <NavIcon href={item.href} />
                  <span>{item.label}</span>
                </a>
              </li>
            ))}
            {/* Разделы без места в нижнем меню (у руководителя — «Настройки команды») доступны через «Ещё»: в меню
                профиля их на телефоне не искали (4.12). */}
            {nav.some((item) => !item.phone) && (
              <li className="app-tabbar__more">
                <ActionMenu
                  label="Ещё разделы"
                  toggle={<><NavIcon href="more" /><span>Ещё</span></>}
                  items={nav.filter((item) => !item.phone).map((item) => ({ label: item.label, href: item.href }))}
                />
              </li>
            )}
          </ul>
        </nav>
      )}
    </div>
  )
}
