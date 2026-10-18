import { useState } from 'react'
import type { Organization, WorkControlIdleOrganization } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { CardDialog } from '../interactions/cardUi'
import { OrganizationAssignmentPanel } from '../organizations/OrganizationAssignmentPanel'
import type { MatrixOpenState } from './ControlMatrix'

const listLimit = 10

export const IdleOrganizations = ({ organizations, byTeams, open }: {
  organizations: WorkControlIdleOrganization[]
  byTeams: boolean
  open: MatrixOpenState
}) => {
  const [all, setAll] = useState(false)
  const shown = all ? organizations : organizations.slice(0, listLimit)
  return (
    <details
      className="control-manager control-manager--idle"
      open={open.isOpen('idle-organizations', true)}
      onToggle={(event) => open.onToggle('idle-organizations', event.currentTarget.open)}
    >
      <summary>
        <span className="control-manager__name">Без работы</span>
        <span className="control-totals"><span className="control-totals__overdue">вузов {organizations.length}</span></span>
      </summary>
      <p className="control-muted control-idle__note">
        За вузом закреплён КАМ, но идущего взаимодействия нет. Последнее действие — последняя запись сотрудника по
        взаимодействиям вуза, без автоматических данных источников.
      </p>
      <ul className="control-unassigned">
        {shown.map((organization) => (
          <li key={organization.id}>
            <a href={`#/organizations/${organization.id}`}>{organization.name}</a>
            <span className="control-muted">{`КАМ: ${organization.managerName}${byTeams ? ` · ${organization.teamName}` : ''} · последнее действие: ${
              organization.lastActionAt ? formatMoscowDateTime(organization.lastActionAt) : 'не зафиксировано'}`}</span>
          </li>
        ))}
      </ul>
      {organizations.length > listLimit && (
        <p className="control-manager__assign">
          <button type="button" className="button--secondary" onClick={() => setAll((value) => !value)}>
            {all ? 'Свернуть список' : `Показать все ${organizations.length}`}
          </button>
        </p>
      )}
    </details>
  )
}

// Окно назначения КАМ открывается прямо из «Контроля»: то же окно, что в карточке вуза, без перехода в «Вузы».
export const AssignDialog = ({ organization, profileId, onClose, onAssigned, onReload, onSessionExpired, onProfileUnavailable }: {
  organization: Organization | null
  profileId: string
  onClose: () => void
  onAssigned: () => void
  onReload: () => Promise<void>
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}) => (
  <CardDialog open={organization !== null} title="Назначить или сменить ответственного" onClose={onClose}>
    {organization !== null && (
      <OrganizationAssignmentPanel
        key={organization.id}
        part="assignment"
        organization={organization}
        profileId={profileId}
        onOrganizationChanged={onAssigned}
        onReload={onReload}
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />
    )}
  </CardDialog>
)
