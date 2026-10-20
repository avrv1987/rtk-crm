import { useCallback, useEffect, useState } from 'react'
import { apiClient, type Me } from '../../shared/api/client'
import { PendingSourceRecords } from '../sources/PendingSourceRecords'
import { SourceAlerts } from '../sources/SourceAlerts'
import { AdminCatalogsPanel } from './AdminCatalogsPanel'
import { AdminOverview, problemRecordsOf, type AdminSummary } from './AdminOverview'
import { AdminEmployeesScreen } from './AdminEmployeesScreen'
import { AdminOrganizationsPanel } from './AdminOrganizationsPanel'
import { AdminTeamsPanel } from './AdminTeamsPanel'
import { AdminSectionNav } from './AdminSectionNav'
import { AuditJournalPanel } from './AuditJournalPanel'
import { CatalogImportPanel } from './CatalogImportPanel'
import { PersonalDataPanel } from './PersonalDataPanel'
import { RetentionPanel } from './RetentionPanel'
import { DemandIndexPanel } from './DemandIndexPanel'
import { PeopleSettingsPanel } from './PeopleSettingsPanel'
import { AgreementReportingPanel } from './AgreementReportingPanel'
import { ModulesPanel } from './ModulesPanel'
import { PersonalDataRequestsPanel } from './PersonalDataRequestsPanel'
import { RouteVersionsPanel } from './RouteVersionsPanel'
import { SettingsPanel } from './SettingsPanel'
import { SourcesPanel } from './SourcesPanel'
import { InteractionKindTeamTemplates } from './InteractionKindTeamTemplates'
import { WorkflowTemplatesPanel } from './WorkflowTemplatesPanel'
import { getModules } from '../../shared/modules'
import { MaxAdminPanel } from '../max/MaxAdminPanel'
import { MailAdminPanel } from '../mail/MailAdminPanel'
import { handledSessionError, type SessionHandlers } from './adminShared'
import { canonicalAdminHash, resolveAdminAddress, type AdminNavRole, type AdminSectionSlug } from './adminSections'
import { catalogTabFrom, type CatalogsTabSlug } from './catalogTabs'
import { statusFromQuery } from './employeeFilters'

type AdminScreenProps = SessionHandlers & {
  profile: Me
  slug: string | undefined
  query?: string
}

const noticeKey = (profileId: string) => `rtk-crm:admin-notice-seen:${profileId}`

const seenWithoutStorage = new Set<string>()

const noticeSeen = (profileId: string) => {
  if (seenWithoutStorage.has(profileId)) {
    return true
  }
  try {
    return window.sessionStorage.getItem(noticeKey(profileId)) !== null
  } catch {
    return false
  }
}

const rememberNoticeSeen = (profileId: string) => {
  try {
    window.sessionStorage.setItem(noticeKey(profileId), '1')
  } catch {
    seenWithoutStorage.add(profileId)
  }
}

export const adminNoticeText = 'Администратор управляет доступом и настройками и не видит работу с вузами. Чтобы посмотреть работу с вузами, войдите руководителем команды или менеджером.'

const useAdminSummary = (role: AdminNavRole, refreshKey: string, handlers: SessionHandlers) => {
  const [summary, setSummary] = useState<AdminSummary | null>(null)
  const [pendingRecords, setPendingRecords] = useState<number | undefined>(undefined)
  const { onSessionExpired, onProfileUnavailable } = handlers

  const load = useCallback(async () => {
    const handled = (result: PromiseSettledResult<unknown>) => (
      result.status === 'rejected' && handledSessionError(result.reason, { onSessionExpired, onProfileUnavailable })
    )
    if (role === 'LEADER') {
      const [records] = await Promise.allSettled([apiClient.listPendingSourceRecords()])
      if (!handled(records)) {
        setPendingRecords(records.status === 'fulfilled' ? records.value.length : undefined)
      }
      return
    }
    const results = await Promise.allSettled([
      apiClient.listCrmProfiles({ size: 1 }),
      apiClient.listDataSources(),
      apiClient.listCrmExchangeEvents(),
      apiClient.getBackupStatus(),
      apiClient.getLearningCoverage()
    ])
    if (results.some(handled)) {
      return
    }
    const [profiles, sources, events, backup, coverage] = results
    setSummary({
      pendingProfiles: profiles.status === 'fulfilled' ? profiles.value.pendingTotal : undefined,
      sources: sources.status === 'fulfilled' ? sources.value : undefined,
      crmEvents: events.status === 'fulfilled' ? events.value.counts : undefined,
      backup: backup.status === 'fulfilled' ? backup.value : undefined,
      coverage: coverage.status === 'fulfilled' ? coverage.value : undefined,
      failed: results.some((result) => result.status === 'rejected')
    })
  }, [onProfileUnavailable, onSessionExpired, role])

  useEffect(() => {
    void load()
  }, [load, refreshKey])

  const counts: Partial<Record<AdminSectionSlug, number>> = role === 'LEADER'
    ? { 'pending-source-records': pendingRecords }
    : { employees: summary?.pendingProfiles, sources: problemRecordsOf(summary?.sources) }
  // Н-А5: экран заявок сообщает свежее число после каждого разбора — счётчик на вкладке не ждёт перезагрузки.
  return { summary, counts, reload: load, setPendingRecords }
}

export const AdminScreen = ({ profile, slug, query = '', onSessionExpired, onProfileUnavailable }: AdminScreenProps) => {
  const role: AdminNavRole = profile.role === 'LEADER' ? 'LEADER' : 'ADMIN'
  const address = resolveAdminAddress(slug, query, role)
  const section = address.section
  const [sourcesRevision, setSourcesRevision] = useState(0)
  const [showNotice, setShowNotice] = useState(() => role === 'ADMIN' && !noticeSeen(profile.id))
  const handlers = { onSessionExpired, onProfileUnavailable }
  const { summary, counts, reload, setPendingRecords } = useAdminSummary(role, `${section}:${sourcesRevision}`, handlers)

  useEffect(() => {
    const target = canonicalAdminHash(slug, query, role)
    if (target !== null) {
      window.history.replaceState(null, '', target)
    }
  }, [slug, query, role])

  const changeCatalogTab = (tab: CatalogsTabSlug) => {
    window.location.hash = `#/admin/catalogs?tab=${tab}`
  }

  const dismissNotice = () => {
    rememberNoticeSeen(profile.id)
    setShowNotice(false)
  }

  return (
    <div className={role === 'ADMIN' ? 'admin-layout' : undefined}>
      <AdminSectionNav active={section} role={role} counts={counts} />
      <div className="admin-layout__content">
        {showNotice && (
          <div className="notice admin-role-notice" role="note">
            <p><strong>{adminNoticeText}</strong></p>
            <button type="button" className="button--secondary" onClick={dismissNotice}>Понятно</button>
          </div>
        )}
        {role === 'ADMIN' && (
          <SourceAlerts onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} refreshSignal={sourcesRevision} />
        )}
        {section === 'overview' && <AdminOverview summary={summary} onRetry={() => void reload()} />}
        {section === 'pending-source-records' && <PendingSourceRecords role={profile.role} onCount={setPendingRecords} {...handlers} />}
        {section === 'employees' && (
          <AdminEmployeesScreen
            key={address.query}
            currentProfile={profile}
            initialStatus={statusFromQuery(address.query)}
            {...handlers}
          />
        )}
        {section === 'teams' && <AdminTeamsPanel {...handlers} />}
        {section === 'organizations' && <AdminOrganizationsPanel {...handlers} />}
        {section === 'catalogs' && <AdminCatalogsPanel tab={catalogTabFrom(address.query)} onTabChange={changeCatalogTab} {...handlers} />}
        {section === 'workflow-templates' && <WorkflowTemplatesPanel role={profile.role} profileId={profile.id} {...handlers} />}
        {section === 'workflow-templates' && role === 'LEADER' && <InteractionKindTeamTemplates {...handlers} />}

        {section === 'catalog-import' && <CatalogImportPanel {...handlers} />}
        {section === 'sources' && <SourcesPanel {...handlers} onSynced={() => setSourcesRevision((value) => value + 1)} />}
        {section === 'sources' && getModules().max === true && <MaxAdminPanel {...handlers} />}
        {section === 'mail' && <MailAdminPanel enabled={getModules().mail === true} {...handlers} />}
        {section === 'journal' && <AuditJournalPanel {...handlers} />}
        {section === 'personal-data' && <PersonalDataPanel {...handlers} />}
        {section === 'retention' && <RetentionPanel {...handlers} />}
        {section === 'demand-index' && <DemandIndexPanel {...handlers} />}
        {section === 'people-settings' && <PeopleSettingsPanel {...handlers} />}
        {section === 'agreement-reporting' && <AgreementReportingPanel query={address.query} {...handlers} />}
        {section === 'settings' && <SettingsPanel {...handlers} />}
        {section === 'modules' && <ModulesPanel {...handlers} />}
        {section === 'route-versions' && <RouteVersionsPanel {...handlers} />}
        {section === 'personal-data-requests' && <PersonalDataRequestsPanel profileId={profile.id} {...handlers} />}
      </div>
    </div>
  )
}
