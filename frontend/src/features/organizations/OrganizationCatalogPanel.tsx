import { useRef, useState } from 'react'
import {
  ApiError,
  createIdempotencyKey,
  type OrganizationDetails,
  type OrganizationStatus,
  type OrganizationStatusChange
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import {
  OrganizationForm,
  organizationErrorMessage,
  organizationStatusLabels,
  organizationTypeLabels,
  type OrganizationType
} from './OrganizationForm'

type CatalogOrganization = {
  id: string
  name: string
  type: OrganizationType
  status: OrganizationStatus
  city: string | null
  website: string | null
  inn: string | null
  version: number
}

type StatusAction = OrganizationStatusChange['action']

type OrganizationCatalogPanelProps<T extends CatalogOrganization> = {
  organization: T
  canEdit: boolean
  canApprove: boolean
  canArchive: boolean
  canRestore: boolean
  linkDuplicates: boolean
  update: (payload: OrganizationDetails, idempotencyKey: string) => Promise<T>
  changeStatus: (payload: OrganizationStatusChange, idempotencyKey: string) => Promise<T>
  onChanged: (organization: T) => void
  onSessionError: (error: unknown) => boolean
}

export const actionTexts: Record<StatusAction, { button: string; title: string; description: string; done: string }> = {
  APPROVE: {
    button: 'Подтвердить организацию',
    title: 'Подтвердить организацию',
    description: 'Организация станет действующей в каталоге команды.',
    done: 'Организация подтверждена.'
  },
  REJECT: {
    button: 'Отклонить заявку',
    title: 'Отклонить заявку на организацию',
    description: 'Организация уйдёт в архив: она не будет предлагаться для новой работы, история сохранится.',
    done: 'Заявка отклонена, организация в архиве.'
  },
  ARCHIVE: {
    button: 'Архивировать организацию',
    title: 'Архивировать организацию',
    description: 'Организация скроется из списков и не будет предлагаться для новой работы. Взаимодействия, контакты и история сохранятся; руководитель может восстановить её.',
    done: 'Организация в архиве.'
  },
  RESTORE: {
    button: 'Восстановить организацию',
    title: 'Восстановить организацию',
    description: 'Организация снова появится в списках команды и будет доступна для новой работы.',
    done: 'Организация восстановлена.'
  }
}

const statusClass: Record<OrganizationStatus, string> = {
  ACTIVE: 'admin-profiles__status--active',
  PENDING: 'admin-profiles__status--pending',
  ARCHIVED: 'admin-profiles__status--blocked'
}

export const OrganizationStatusBadge = ({ status }: { status: OrganizationStatus }) => (
  status === 'ACTIVE' ? null : <span className={`admin-profiles__status ${statusClass[status]}`}>{organizationStatusLabels[status]}</span>
)

export const OrganizationCatalogPanel = <T extends CatalogOrganization>({
  organization,
  canEdit,
  canApprove,
  canArchive,
  canRestore,
  linkDuplicates,
  update,
  changeStatus,
  onChanged,
  onSessionError
}: OrganizationCatalogPanelProps<T>) => {
  const [editing, setEditing] = useState(false)
  const [pending, setPending] = useState<StatusAction | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(undefined)
  const [message, setMessage] = useState<string | null>(null)
  const statusKey = useRef<{ action: StatusAction; key: string } | null>(null)

  const actions: StatusAction[] = []
  if (organization.status === 'PENDING' && canApprove) {
    actions.push('APPROVE', 'REJECT')
  }
  if (organization.status !== 'ARCHIVED' && canArchive) {
    actions.push('ARCHIVE')
  }
  if (organization.status === 'ARCHIVED' && canRestore) {
    actions.push('RESTORE')
  }

  const runStatus = async (action: StatusAction) => {
    const key = statusKey.current?.action === action ? statusKey.current.key : createIdempotencyKey()
    statusKey.current = { action, key }
    setPending(null)
    setSaving(true)
    setError(undefined)
    try {
      const changed = await changeStatus({ action, version: organization.version }, key)
      statusKey.current = null
      setMessage(actionTexts[action].done)
      onChanged(changed)
    } catch (failure) {
      if (!onSessionError(failure)) {
        setError(failure)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="organization-catalog" aria-label="Реквизиты и состояние организации">
      <dl className="organization-detail__fields">
        <div>
          <dt>Состояние</dt>
          <dd>{organizationStatusLabels[organization.status]}</dd>
        </div>
        <div>
          <dt>Город или регион</dt>
          <dd>{organization.city ?? 'Не указан'}</dd>
        </div>
        <div>
          <dt>Сайт</dt>
          <dd>
            {organization.website === null
              ? 'Не указан'
              : <a href={organization.website} target="_blank" rel="noopener noreferrer">{organization.website}</a>}
          </dd>
        </div>
        <div>
          <dt>ИНН</dt>
          <dd>{organization.inn ?? 'Не указан'}</dd>
        </div>
      </dl>
      {organization.status === 'PENDING' && (
        <p className="admin-profile-form__hint">
          {canApprove
            ? 'КАМ завёл организацию и ждёт подтверждения. Проверьте название и реквизиты, затем подтвердите или отклоните заявку.'
            : 'Организация ждёт подтверждения руководителем команды. Работу с ней можно вести уже сейчас.'}
        </p>
      )}
      {message !== null && <p className="organization-catalog__message" role="status">{message}</p>}
      {editing ? (
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
          linkDuplicates={linkDuplicates}
          onSubmit={async (payload, key) => {
            const changed = await update({ ...payload, version: organization.version }, key)
            setEditing(false)
            setMessage('Реквизиты сохранены.')
            onChanged(changed)
          }}
          onCancel={() => setEditing(false)}
          onSessionError={onSessionError}
        />
      ) : (
        (canEdit || actions.length > 0) && (
          <div className="admin-profiles__confirmation-actions">
            {canEdit && organization.status !== 'ARCHIVED' && organization.type !== 'OPEN_ENROLLMENT' && (
              <button
                type="button"
                className="button--secondary"
                disabled={saving}
                onClick={() => {
                  setMessage(null)
                  setEditing(true)
                }}
              >
                Изменить название и реквизиты
              </button>
            )}
            {actions.map((action) => (
              <button
                key={action}
                type="button"
                className={action === 'APPROVE' || action === 'RESTORE' ? undefined : 'button--secondary'}
                disabled={saving}
                onClick={() => {
                  setMessage(null)
                  setPending(action)
                }}
              >
                {saving && statusKey.current?.action === action ? 'Сохраняем…' : actionTexts[action].button}
              </button>
            ))}
          </div>
        )
      )}
      {error !== undefined && (
        <div className="interaction-command-error" role="alert">
          <p>{organizationErrorMessage(error)}</p>
          {error instanceof ApiError && <p className="request-id">Request ID: {error.requestId}</p>}
        </div>
      )}
      <ConfirmDialog
        open={pending !== null}
        title={pending === null ? '' : `${actionTexts[pending].title} «${organization.name}»?`}
        description={pending === null ? '' : `${organizationTypeLabels[organization.type]}. ${actionTexts[pending].description}`}
        confirmLabel={pending === null ? '' : actionTexts[pending].button}
        onConfirm={() => pending !== null && void runStatus(pending)}
        onCancel={() => setPending(null)}
      />
    </section>
  )
}
