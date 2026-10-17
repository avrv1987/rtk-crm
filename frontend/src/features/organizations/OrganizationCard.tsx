import { useCallback, useEffect, useRef, useState, type Ref } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Me,
  type Organization,
  type OrganizationStatusChange,
  type PageInteraction
} from '../../shared/api/client'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { agreementStatusLabels } from '../agreements/agreementModel'
import { agreementsApi, formatDate as formatAgreementDate, type AgreementSummary } from '../agreements/agreementsApi'
import { CardDialog } from '../interactions/cardUi'
import { formatMoscowDateTime } from '../interactions/moscowTime'
import { formatDate } from '../work/workShared'
import { actionTexts } from './OrganizationCatalogPanel'
import { OrganizationForm, organizationErrorMessage, organizationStatusLabels, organizationTypeLabels } from './OrganizationForm'
import './organizationCard.css'

type StatusAction = OrganizationStatusChange['action']

type OrganizationCardHeaderProps = {
  organization: Organization
  role: Me['role']
  profileId: string
  headingRef: Ref<HTMLHeadingElement>
  onChanged: (organization: Organization, message: string) => void
  onOpenAssignment: (part: 'assignment' | 'deputy') => void
  onSessionError: (error: unknown) => boolean
}

const ownerLabel = (organization: Organization) => {
  if (!organization.requiresAssignment) {
    return organization.ownerManagerName ?? 'Назначен'
  }
  return organization.ownerManagerName === null
    ? 'Требует назначения: ответственный не назначен'
    : `Требует назначения: ${organization.ownerManagerName} больше не активный КАМ команды`
}

export const deputyLabel = (organization: Organization) => (
  organization.deputyManagerName === null
    ? null
    : `${organization.deputyManagerName}${organization.deputyEndsOn === null ? '' : ` до ${formatDate(organization.deputyEndsOn)}`}`
)

const statusChipClass: Record<Organization['status'], string> = {
  ACTIVE: 'status status--planned',
  PENDING: 'status status--missing',
  ARCHIVED: 'status status--overdue'
}

export const OrganizationCardHeader = ({
  organization,
  role,
  profileId,
  headingRef,
  onChanged,
  onOpenAssignment,
  onSessionError
}: OrganizationCardHeaderProps) => {
  const [editing, setEditing] = useState(false)
  const [pending, setPending] = useState<StatusAction | null>(null)
  const [saving, setSaving] = useState<StatusAction | null>(null)
  const [error, setError] = useState<unknown>(undefined)
  const statusKey = useRef<{ action: StatusAction; key: string } | null>(null)
  const isLeader = role === 'LEADER'
  const canArchive = organization.status !== 'ARCHIVED' && (isLeader || organization.ownerManagerId === profileId)
  const canEdit = isLeader && organization.status !== 'ARCHIVED' && organization.type !== 'OPEN_ENROLLMENT'
  const deputy = deputyLabel(organization)
  const requisites = [
    { label: 'Город или регион', value: organization.city },
    { label: 'Сайт', value: organization.website, link: true },
    { label: 'ИНН', value: organization.inn }
  ].filter((item) => item.value !== null && item.value.trim().length > 0)

  const runStatus = async (action: StatusAction) => {
    const key = statusKey.current?.action === action ? statusKey.current.key : createIdempotencyKey()
    statusKey.current = { action, key }
    setPending(null)
    setSaving(action)
    setError(undefined)
    try {
      const changed = await apiClient.changeOrganizationStatus(organization.id, { action, version: organization.version }, key)
      statusKey.current = null
      onChanged(changed, actionTexts[action].done)
    } catch (failure) {
      if (!onSessionError(failure)) {
        setError(failure)
      }
    } finally {
      setSaving(null)
    }
  }

  const statusButton = (action: StatusAction, primary: boolean) => (
    <button
      type="button"
      className={primary ? undefined : 'button--secondary'}
      disabled={saving !== null}
      onClick={() => {
        setError(undefined)
        setPending(action)
      }}
    >
      {saving === action ? 'Сохраняем…' : actionTexts[action].button}
    </button>
  )

  const menuItems: ActionMenuItem[] = []
  if (isLeader) {
    menuItems.push(
      { label: 'Назначить или сменить ответственного…', onSelect: () => onOpenAssignment('assignment') },
      { label: 'Заместитель на время отсутствия…', onSelect: () => onOpenAssignment('deputy') }
    )
  }
  if (canEdit) {
    menuItems.push({ label: 'Изменить название и реквизиты…', onSelect: () => setEditing(true) })
  }
  if (canArchive) {
    menuItems.push({ label: 'Архивировать вуз…', danger: true, separated: menuItems.length > 0, onSelect: () => setPending('ARCHIVE') })
  }

  return (
    <div className="org-card__header">
      <div className="org-card__top">
        <div className="org-card__heading">
          <h3 id="organization-detail-title" ref={headingRef} tabIndex={-1}>{organization.name}</h3>
          <div className="org-card__chips">
            <span className="organization-list-item__type">{organizationTypeLabels[organization.type]}</span>
            <span className={statusChipClass[organization.status]}>{organizationStatusLabels[organization.status]}</span>
            {organization.inherited && !organization.requiresAssignment && (
              <span className="status status--missing" title="Контакты ещё не подтверждены новым ответственным">Унаследован</span>
            )}
            {deputy !== null && <span className="status status--planned">Замещение: {deputy}</span>}
          </div>
        </div>
        <ActionMenu label="Ещё действия с вузом" items={menuItems} />
      </div>
      <dl className="org-card__facts">
        <div>
          <dt>КАМ вуза</dt>
          <dd className={organization.requiresAssignment ? 'organization-detail__attention' : undefined}>{ownerLabel(organization)}</dd>
        </div>
        {organization.teamName !== null && (
          <div>
            <dt>Команда</dt>
            <dd>{organization.teamName}</dd>
          </div>
        )}
        <div>
          <dt>Обновлено</dt>
          <dd>{formatMoscowDateTime(organization.updatedAt)}</dd>
        </div>
        {requisites.map((item) => (
          <div key={item.label}>
            <dt>{item.label}</dt>
            <dd>{item.link ? <a href={item.value ?? ''} target="_blank" rel="noopener noreferrer">{item.value}</a> : item.value}</dd>
          </div>
        ))}
      </dl>
      {requisites.length === 0 && canEdit && (
        <p className="org-card__muted">Город, сайт и ИНН не заполнены: «⋯» → «Изменить название и реквизиты».</p>
      )}
      {organization.inherited && !organization.requiresAssignment && (
        <p className="org-card__banner" role="note">
          <strong>После передачи. </strong>
          {role === 'USER'
            ? 'Унаследованный вуз: вы ещё не подтвердили ни одного контакта. Свяжитесь с контактом и отметьте у него «Контакт подтверждён».'
            : `Унаследованный вуз: ${organization.ownerManagerName ?? 'новый ответственный'} ещё не подтвердил ни одного контакта после передачи.`}
        </p>
      )}
      {organization.status === 'PENDING' && (
        <div className="org-card__banner" role="note">
          <p>
            {isLeader
              ? 'КАМ завёл организацию и ждёт подтверждения. Проверьте название и реквизиты, затем подтвердите или отклоните заявку.'
              : 'Организация ждёт подтверждения руководителем команды. Работу с ней можно вести уже сейчас.'}
          </p>
          {isLeader && (
            <div className="org-card__banner-actions">
              {statusButton('APPROVE', true)}
              {statusButton('REJECT', false)}
            </div>
          )}
        </div>
      )}
      {organization.status === 'ARCHIVED' && (
        <div className="org-card__banner org-card__banner--archived" role="note">
          <p>Вуз в архиве: он не предлагается для новых взаимодействий, история сохранена.</p>
          {isLeader && <div className="org-card__banner-actions">{statusButton('RESTORE', true)}</div>}
        </div>
      )}
      {error !== undefined && (
        <div className="interaction-command-error" role="alert">
          <p>{organizationErrorMessage(error)}</p>
          {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
        </div>
      )}
      {canEdit && (
        <CardDialog open={editing} title="Название и реквизиты" onClose={() => setEditing(false)}>
          <OrganizationForm
            title={`Реквизиты: ${organization.name}`}
            submitLabel="Сохранить реквизиты"
            initial={{
              name: organization.name,
              ...(organization.type === 'OPEN_ENROLLMENT' ? {} : { type: organization.type }),
              city: organization.city ?? '',
              website: organization.website ?? '',
              inn: organization.inn ?? ''
            }}
            exceptId={organization.id}
            linkDuplicates
            onSubmit={async (payload, key) => {
              const changed = await apiClient.updateOrganization(organization.id, { ...payload, version: organization.version }, key)
              setEditing(false)
              onChanged(changed, 'Реквизиты сохранены.')
            }}
            onCancel={() => setEditing(false)}
            onSessionError={onSessionError}
          />
        </CardDialog>
      )}
      <ConfirmDialog
        open={pending !== null}
        title={pending === null ? '' : `${actionTexts[pending].title} «${organization.name}»?`}
        description={pending === null ? '' : `${organizationTypeLabels[organization.type]}. ${actionTexts[pending].description}`}
        confirmLabel={pending === null ? '' : actionTexts[pending].button}
        onConfirm={() => pending !== null && void runStatus(pending)}
        onCancel={() => setPending(null)}
      />
    </div>
  )
}

export type OrganizationCounts = {
  works: number
  contacts: number
  agreements: number
}

type SummaryData = {
  page: PageInteraction
  overdue: number
  agreements: AgreementSummary[]
}

type SummaryState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: SummaryData }
  | { kind: 'failed'; requestId?: string }

const agreementLine = (agreements: AgreementSummary[]) => {
  const active = agreements
    .filter((agreement) => agreement.status === 'ACTIVE')
    .sort((left, right) => (right.validUntil ?? '9999').localeCompare(left.validUntil ?? '9999'))[0]
  if (active !== undefined) {
    return `договор № ${active.number}${active.validUntil ? ` действует до ${formatAgreementDate(active.validUntil)}` : ' действует'}`
  }
  const open = agreements.find((agreement) => agreement.status !== 'TERMINATED' && agreement.status !== 'COMPLETED')
  return open === undefined ? 'действующего договора нет' : `договор № ${open.number}: ${agreementStatusLabels[open.status].toLowerCase()}`
}

const worksLabel = (count: number) => {
  const lastTwo = count % 100
  const last = count % 10
  if (last === 1 && lastTwo !== 11) {
    return `${count} активное взаимодействие`
  }
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? `${count} активных взаимодействия` : `${count} активных взаимодействий`
}

export const OrganizationSummary = ({
  organizationId,
  revision,
  onCounts,
  onSessionError
}: {
  organizationId: Organization['id']
  revision: number
  onCounts: (counts: OrganizationCounts) => void
  onSessionError: (error: unknown) => boolean
}) => {
  const [state, setState] = useState<SummaryState>({ kind: 'loading' })
  const requestVersion = useRef(0)
  const countsRef = useRef(onCounts)
  countsRef.current = onCounts

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    try {
      const [page, overdue, contacts, agreements] = await Promise.all([
        apiClient.listInteractions({ organizationId, status: 'ACTIVE', size: 100, page: 0, sort: 'nextActionAt,asc' }),
        apiClient.listInteractions({ organizationId, status: 'ACTIVE', due: 'OVERDUE', size: 1, page: 0 }),
        apiClient.listOrganizationContacts(organizationId),
        agreementsApi.list(organizationId)
      ])
      if (version === requestVersion.current) {
        setState({ kind: 'ready', data: { page, overdue: overdue.total, agreements } })
        countsRef.current({ works: page.total, contacts: contacts.length, agreements: agreements.length })
      }
    } catch (error) {
      if (version !== requestVersion.current || onSessionError(error)) {
        return
      }
      setState({ kind: 'failed', requestId: error instanceof ApiError ? error.requestId : undefined })
    }
  }, [onSessionError, organizationId])

  useEffect(() => {
    void load()
    return () => {
      requestVersion.current += 1
    }
  }, [load, revision])

  if (state.kind === 'loading') {
    return <p className="org-summary org-summary--loading" role="status">Загружаем сводку…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="organizations-message organizations-message--error" role="alert">
        <p>Не удалось загрузить сводку по вузу.</p>
        <SupportDetails requestId={state.requestId} />
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }

  const { page, overdue, agreements } = state.data
  const now = Date.now()
  const upcoming = page.items.flatMap((item) => item.nextActionAt === null ? [] : [new Date(item.nextActionAt).getTime()])
    .filter((time) => time >= now)
  const nearest = upcoming.length === 0 ? undefined : Math.min(...upcoming)
  const lastContacts = page.items.flatMap((item) => item.lastContactAt === null ? [] : [new Date(item.lastContactAt).getTime()])
  const lastContact = lastContacts.length === 0 ? undefined : Math.max(...lastContacts)

  return (
    <p className="org-summary" aria-label="Сводка по вузу">
      <span>{worksLabel(page.total)}</span>
      <span className={overdue > 0 ? 'org-summary__bad' : undefined}>просрочено шагов: {overdue}</span>
      <span>ближайший срок: {nearest === undefined ? 'нет' : formatMoscowDateTime(new Date(nearest).toISOString())}</span>
      <span>последний контакт: {lastContact === undefined ? 'нет данных' : formatMoscowDateTime(new Date(lastContact).toISOString())}</span>
      <span>{agreementLine(agreements)}</span>
    </p>
  )
}
