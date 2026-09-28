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
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { CardDialog, CardMenu, type CardMenuItem } from '../interactions/cardUi'
import { InteractionMarkBadges } from '../interactions/InteractionMarkBadges'
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
  onOpenAssignment: () => void
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

  const menuItems: CardMenuItem[] = []
  if (canEdit) {
    menuItems.push({ label: 'Изменить название и реквизиты…', onSelect: () => setEditing(true) })
  }
  if (isLeader) {
    menuItems.push(
      { label: 'Назначить или сменить ответственного…', onSelect: onOpenAssignment },
      { label: 'Заместитель на время отсутствия…', onSelect: onOpenAssignment }
    )
  }
  if (canArchive) {
    menuItems.push({ label: 'Архивировать организацию…', danger: true, onSelect: () => setPending('ARCHIVE') })
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
        <CardMenu label="Ещё действия с вузом" items={menuItems} />
      </div>
      <dl className="org-card__facts">
        <div>
          <dt>Ответственный КАМ</dt>
          <dd className={organization.requiresAssignment ? 'organization-detail__attention' : undefined}>{ownerLabel(organization)}</dd>
        </div>
        <div>
          <dt>Команда</dt>
          <dd>{organization.teamName ?? 'Не указана'}</dd>
        </div>
        {deputy !== null && (
          <div>
            <dt>Заместитель</dt>
            <dd>{deputy}</dd>
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
        <p className="org-card__muted">Город, сайт и ИНН не заполнены. Их можно указать через «⋯» → «Изменить название и реквизиты».</p>
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
          <p>Организация в архиве: она не предлагается для новой работы, история сохранена.</p>
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

type SummaryState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageInteraction; overdue: number }
  | { kind: 'failed'; requestId?: string }

const summaryRows = 5

export const OrganizationSummary = ({
  organizationId,
  workHref,
  onShowWorks,
  onSessionError
}: {
  organizationId: Organization['id']
  workHref: (id: string) => string
  onShowWorks: () => void
  onSessionError: (error: unknown) => boolean
}) => {
  const [state, setState] = useState<SummaryState>({ kind: 'loading' })
  const requestVersion = useRef(0)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    setState({ kind: 'loading' })
    try {
      const [page, overdue] = await Promise.all([
        apiClient.listInteractions({ organizationId, status: 'ACTIVE', size: 100, page: 0, sort: 'nextActionAt,asc' }),
        apiClient.listInteractions({ organizationId, status: 'ACTIVE', due: 'OVERDUE', size: 1, page: 0 })
      ])
      if (version === requestVersion.current) {
        setState({ kind: 'ready', page, overdue: overdue.total })
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
  }, [load])

  if (state.kind === 'loading') {
    return <p className="organizations-message" role="status">Загружаем сводку по работам…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="organizations-message organizations-message--error" role="alert">
        <p>Не удалось загрузить сводку по работам.</p>
        <SupportDetails requestId={state.requestId} />
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }

  const now = Date.now()
  const items = state.page.items
  const dueTimes = items.flatMap((item) => item.nextActionAt === null ? [] : [new Date(item.nextActionAt).getTime()])
  const upcoming = dueTimes.filter((time) => time >= now)
  const nearest = upcoming.length === 0 ? undefined : Math.min(...upcoming)
  const lastContacts = items.flatMap((item) => item.lastContactAt === null ? [] : [new Date(item.lastContactAt).getTime()])
  const lastContact = lastContacts.length === 0 ? undefined : Math.max(...lastContacts)
  const rows = [...items]
    .sort((left, right) => {
      const leftTime = left.nextActionAt === null ? Number.POSITIVE_INFINITY : new Date(left.nextActionAt).getTime()
      const rightTime = right.nextActionAt === null ? Number.POSITIVE_INFINITY : new Date(right.nextActionAt).getTime()
      return leftTime - rightTime
    })
    .slice(0, summaryRows)

  return (
    <section className="org-summary" aria-labelledby="org-summary-title">
      <h4 id="org-summary-title" className="org-summary__title">Сводка по работам</h4>
      <div className="org-summary__stats">
        <div className="org-stat">
          <span className="org-stat__value">{state.page.total}</span>
          <span className="org-stat__label">активных работ</span>
        </div>
        <div className={state.overdue > 0 ? 'org-stat org-stat--bad' : 'org-stat org-stat--quiet'}>
          <span className="org-stat__value">{state.overdue}</span>
          <span className="org-stat__label">просрочено шагов</span>
        </div>
        <div className="org-stat">
          <span className="org-stat__value org-stat__value--text">{nearest === undefined ? 'нет' : formatMoscowDateTime(new Date(nearest).toISOString())}</span>
          <span className="org-stat__label">ближайший срок</span>
        </div>
        <div className="org-stat">
          <span className="org-stat__value org-stat__value--text">{lastContact === undefined ? 'нет данных' : formatMoscowDateTime(new Date(lastContact).toISOString())}</span>
          <span className="org-stat__label">последний контакт</span>
        </div>
      </div>
      {rows.length === 0 ? (
        <p className="org-card__muted">Активных работ нет.</p>
      ) : (
        <ul className="org-summary__works" aria-label="Активные работы по сроку">
          {rows.map((item) => {
            const due = item.nextActionAt === null ? null : new Date(item.nextActionAt).getTime()
            const overdue = due !== null && due < now
            return (
              <li key={item.id}>
                <a className="org-summary__work" href={workHref(item.id)}>
                  <span className="org-summary__work-title">{item.title}</span>
                  <span className="org-summary__work-stage">{item.currentStageName}</span>
                  <span className="org-summary__work-step">{item.nextAction ?? 'Следующий шаг не задан'}</span>
                  <span className={`org-summary__work-due${overdue ? ' interaction-summary__overdue' : due === null ? ' interaction-summary__missing' : ''}`}>
                    {item.nextActionAt === null ? 'срок не задан' : `${overdue ? 'просрочен с ' : ''}${formatMoscowDateTime(item.nextActionAt)}`}
                  </span>
                  <span className="org-summary__work-owner">{item.ownerManagerName ?? 'Ответственный не назначен'}</span>
                  <InteractionMarkBadges marks={item.marks} />
                </a>
              </li>
            )
          })}
        </ul>
      )}
      {state.page.total > rows.length && (
        <button type="button" className="button--secondary org-summary__more" onClick={onShowWorks}>
          Все работы во вкладке «Работы» ({state.page.total} активных)
        </button>
      )}
    </section>
  )
}
