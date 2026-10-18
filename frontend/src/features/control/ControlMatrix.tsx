import { useState } from 'react'
import type {
  Organization,
  TeamSummary,
  TeamsSummary,
  WorkControl,
  WorkControlItem,
  WorkControlManager,
  WorkControlTeam,
  WorkControlTotals
} from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { agreementNoticeDays, agreementStatusLabels, approvalDelayed } from '../agreements/agreementModel'
import { AgreementTermMark, ApprovalMark } from '../agreements/agreementUi'
import {
  changeNotes,
  type ControlTile,
  dueLabels,
  dueTones,
  isHotManager,
  learningText,
  managerCounts,
  managerKey,
  routeLabel,
  routeShare,
  sortedManagers,
  teamKey
} from './controlModel'

const statusLabels: Record<'PAUSED' | 'COMPLETED', string> = {
  PAUSED: 'Приостановлена',
  COMPLETED: 'Завершена'
}

const unassignedName = 'Требует назначения'
const unassignedListLimit = 10

export type MatrixOpenState = {
  isOpen: (key: string, fallback: boolean) => boolean
  onToggle: (key: string, open: boolean) => void
}

type PickHandler = (managerId: string | null, tile: ControlTile | null) => void

type AssignHandler = (organizationId: string) => void

const WorkRow = ({ item, withPeriod }: { item: WorkControlItem; withPeriod: boolean }) => {
  const notes = withPeriod ? changeNotes(item) : []
  const extra = [item.direction, item.program, ...item.products].filter((value): value is string => value !== null && value !== undefined && value !== '')
  const quiet = item.problems + item.risks === 0 && notes.length === 0 && (item.status ?? 'ACTIVE') === 'ACTIVE'
  return (
    <tr className={`control-row control-row--${dueTones[item.due]}`}>
      <td data-label="Вуз и взаимодействие">
        <a className="control-work__organization" href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a>
        <a className="control-work__title" href={`#/organizations/${item.organizationId}/${item.interactionId}`}>{item.title}</a>
        <span className="control-muted">{extra.length === 0 ? 'Программа и продукты не указаны' : extra.join(' · ')}</span>
      </td>
      <td data-label="Этап">
        <span className="control-route__head">
          <strong>{routeLabel(item)}</strong>
          <span>{item.stage}</span>
        </span>
        <span className="control-route__bar" aria-hidden="true"><span style={{ width: `${routeShare(item)}%` }} /></span>
        {item.daysOnStage !== null && item.daysOnStage !== undefined && (
          <span className="control-muted">на этапе {item.daysOnStage} дн.</span>
        )}
      </td>
      <td data-label="Следующий шаг">
        <span className={`control-due control-due--${dueTones[item.due]}`}>{dueLabels[item.due]}</span>
        {item.nextActionAt !== null && item.nextActionAt !== undefined && (
          <span className="control-muted">{formatMoscowDateTime(item.nextActionAt)}</span>
        )}
        {item.nextAction !== null && item.nextAction !== undefined && item.nextAction !== '' && <span>{item.nextAction}</span>}
      </td>
      <td data-label="Договор с вузом">
        {item.agreementStatus === null || item.agreementStatus === undefined ? <span className="control-muted">Нет</span> : (
          <>
            <span className={`control-agreement control-agreement--${item.agreementStatus.toLowerCase()}`}>{agreementStatusLabels[item.agreementStatus]}</span>
            {item.agreementNumber && <span className="control-muted">№ {item.agreementNumber}</span>}
            <AgreementTermMark agreement={{ status: item.agreementStatus, validUntil: item.agreementValidUntil }} noticeDays={agreementNoticeDays} />
            {approvalDelayed(item.agreementApproval) && <ApprovalMark approval={item.agreementApproval} prefix="согласование: " />}
          </>
        )}
      </td>
      <td data-label="Обучение (LMS)" className="control-learning">
        <span className={item.learning ? undefined : 'control-muted'}>{learningText(item.learning)}</span>
        {item.learning && item.learning.signals > 0 && (
          <span className="control-badges">
            <span className="control-badge control-badge--warning">Сигналы LMS: {item.learning.signals}</span>
          </span>
        )}
        {item.learning?.signalTitles.map((title) => <span key={title} className="control-muted">{title}</span>)}
        {item.learning?.mismatch && (
          <span className="control-badges">
            <span className="control-badge control-badge--danger control-badge--wrap">Этап не подтверждён LMS: {item.learning.mismatch}</span>
          </span>
        )}
      </td>
      <td data-label="Проблемы и отметки">
        {quiet ? <span className="control-muted">Нет</span> : (
          <span className="control-badges">
            {item.problems > 0 && <span className="control-badge control-badge--danger" title={item.issues ?? undefined}>Проблемы: {item.problems}</span>}
            {item.risks > 0 && <span className="control-badge control-badge--warning" title={item.issues ?? undefined}>Риски: {item.risks}</span>}
            {item.status !== null && item.status !== undefined && item.status !== 'ACTIVE' && (
              <span className="control-badge">{statusLabels[item.status]}</span>
            )}
            {notes.map((note) => <span key={note} className="control-badge control-badge--change">{note}</span>)}
          </span>
        )}
      </td>
    </tr>
  )
}

const Count = ({ label, value, tone, onPick }: { label: string; value: number; tone?: 'danger'; onPick?: () => void }) => {
  const text = `${label} ${value}`
  const className = tone !== undefined && value > 0 ? 'control-totals__overdue' : undefined
  if (onPick === undefined || value === 0) {
    return <span className={className}>{text}</span>
  }
  return (
    <button
      type="button"
      className={`control-totals__pick${className === undefined ? '' : ` ${className}`}`}
      onClick={(event) => {
        // Кнопка стоит внутри <summary>: без этого нажатие на число ещё и сворачивало бы группу.
        event.preventDefault()
        event.stopPropagation()
        onPick()
      }}
    >
      {text}
    </button>
  )
}

const TotalsLine = ({ totals, withPeriod, stuck, lms, stuckDays, onPick }: {
  totals: WorkControlTotals
  withPeriod: boolean
  stuck?: number
  lms?: number
  stuckDays: number
  onPick?: (tile: ControlTile | null) => void
}) => (
  <span className="control-totals">
    <span>вузов {totals.organizations}</span>
    <Count label="взаимодействий" value={totals.works} onPick={onPick && (() => onPick(null))} />
    <Count label="просрочено" value={totals.overdue} tone="danger" onPick={onPick && (() => onPick('OVERDUE'))} />
    <Count label="на этой неделе" value={totals.thisWeek} onPick={onPick && (() => onPick('THIS_WEEK'))} />
    <Count label="без шага или срока" value={totals.noNextStep} onPick={onPick && (() => onPick('NO_NEXT_STEP'))} />
    {stuck !== undefined && stuck > 0 && <Count label={`на этапе дольше ${stuckDays} дн.`} value={stuck} onPick={onPick && (() => onPick('STUCK'))} />}
    {totals.withIssues > 0 && <Count label="проблемы и риски" value={totals.withIssues} tone="danger" onPick={onPick && (() => onPick('ISSUES'))} />}
    {lms !== undefined && lms > 0 && <Count label="с сигналами LMS" value={lms} onPick={onPick && (() => onPick('LMS'))} />}
    {totals.idle > 0 && <Count label="без работы" value={totals.idle} tone="danger" onPick={onPick && (() => onPick('IDLE'))} />}
    {withPeriod && <span>перешли этап {totals.moved}, новые {totals.created}, завершённые {totals.completed}</span>}
  </span>
)

const ManagerBlock = ({ teamId, manager, withPeriod, stuckDays, open, onPick, assignHref, onAssign }: {
  teamId: string | null | undefined
  manager: WorkControlManager
  withPeriod: boolean
  stuckDays: number
  open: MatrixOpenState
  onPick: PickHandler
  assignHref: string | null
  onAssign?: AssignHandler
}) => {
  const key = managerKey(teamId, manager.managerId)
  const counts = managerCounts(manager, stuckDays)
  return (
    <details
      className={`control-manager${manager.managerId === null ? ' control-manager--unassigned' : ''}`}
      open={open.isOpen(key, isHotManager(manager))}
      onToggle={(event) => open.onToggle(key, event.currentTarget.open)}
    >
      <summary>
        <span className="control-manager__name">{manager.managerName ?? unassignedName}</span>
        <TotalsLine
          totals={manager.totals}
          withPeriod={withPeriod}
          stuck={counts.stuck}
          lms={counts.lms}
          stuckDays={stuckDays}
          onPick={(tile) => onPick(manager.managerId, tile)}
        />
      </summary>
      {manager.managerId === null && onAssign !== undefined && (
        <p className="control-manager__assign">
          {[...new Map(manager.works.map((item) => [item.organizationId, item.organizationName])).entries()].map(([id, name]) => (
            <button key={id} type="button" className="button--secondary" onClick={() => onAssign(id)}>{`Назначить КАМ: ${name}`}</button>
          ))}
        </p>
      )}
      {manager.managerId === null && onAssign === undefined && assignHref !== null && (
        <p className="control-manager__assign"><a className="button-link" href={assignHref}>Назначить КАМ</a></p>
      )}
      {manager.works.length === 0 ? <p className="control-manager__empty">Взаимодействий нет.</p> : (
        <table className="control-table">
          <thead>
            <tr>
              <th scope="col">Вуз и взаимодействие</th>
              <th scope="col">Этап</th>
              <th scope="col">Следующий шаг</th>
              <th scope="col">Договор</th>
              <th scope="col">Обучение (LMS)</th>
              <th scope="col">Проблемы и отметки</th>
            </tr>
          </thead>
          <tbody>
            {manager.works.map((item) => <WorkRow key={item.interactionId} item={item} withPeriod={withPeriod} />)}
          </tbody>
        </table>
      )}
    </details>
  )
}

const TeamStats = ({ row, stuckDays }: { row: TeamSummary; stuckDays: number }) => (
  <span className="control-totals">
    <span>вузов {row.organizations}</span>
    <span className={row.unassignedOrganizations > 0 ? 'control-totals__overdue' : undefined}>без КАМ {row.unassignedOrganizations}</span>
    <span>взаимодействий {row.interactions}</span>
    <span className={row.overdue > 0 ? 'control-totals__overdue' : undefined}>просрочено {row.overdue}</span>
    <span>без шага или срока {row.withoutNextStep}</span>
    <span>на этапе дольше {stuckDays} дн. {row.stuck}</span>
    <span className={row.atRisk > 0 ? 'control-totals__overdue' : undefined}>проблемы и риски {row.atRisk}</span>
    <span>вузов с обучением {row.organizationsWithLearning}</span>
    <span>обучающихся во всех потоках {row.participants}</span>
    <span>учатся сейчас {row.learningNow}</span>
    <span>преподавателей {row.teachers}</span>
  </span>
)

type MatrixProps = {
  control: WorkControl
  withPeriod: boolean
  stuckDays: number
  summary: TeamsSummary | null
  hotTeamId: string | null | undefined
  open: MatrixOpenState
  onPick: PickHandler
  assignHref: string | null
  onAssign?: AssignHandler
}

const TeamBlock = ({ team, props }: { team: WorkControlTeam; props: MatrixProps }) => {
  const managers = sortedManagers(team.managers).map((manager) => (
    <ManagerBlock
      key={manager.managerId ?? 'unassigned'}
      teamId={team.teamId}
      manager={manager}
      withPeriod={props.withPeriod}
      stuckDays={props.stuckDays}
      open={props.open}
      onPick={props.onPick}
      assignHref={props.assignHref}
      onAssign={props.onAssign}
    />
  ))
  if (!props.control.byTeams) {
    return <section className="control-team" aria-label="Менеджеры команды">{managers}</section>
  }
  const row = props.summary?.teams.find((item) => item.teamId === team.teamId)
  const key = teamKey(team.teamId)
  return (
    <details
      className="control-team control-team--group"
      open={props.open.isOpen(key, team.teamId === props.hotTeamId)}
      onToggle={(event) => props.open.onToggle(key, event.currentTarget.open)}
    >
      <summary className="control-team__head">
        <h2>{team.teamName ?? 'Команда без названия'}</h2>
        {row === undefined
          ? <TotalsLine totals={team.totals} withPeriod={props.withPeriod} stuckDays={props.stuckDays} />
          : <TeamStats row={row} stuckDays={props.stuckDays} />}
      </summary>
      <div className="control-team__managers">{managers}</div>
    </details>
  )
}

export const ControlMatrix = (props: MatrixProps) => (
  props.control.teams.length === 0 ? <p className="control-empty">По этим фильтрам взаимодействий нет.</p> : (
    <div className="control-matrix">
      {props.control.teams.map((team) => <TeamBlock key={team.teamId ?? 'team'} team={team} props={props} />)}
      {props.control.byTeams && props.summary !== null && (
        <div className="control-team__all">
          <strong>Все команды</strong>
          <TeamStats row={props.summary.total} stuckDays={props.stuckDays} />
        </div>
      )}
    </div>
  )
)

const unassignedReason = (organization: Organization) => (
  `${organization.ownerManagerName === null
    ? 'КАМ не назначен'
    : `${organization.ownerManagerName} больше не может вести вуз`}${organization.status === 'PENDING' ? ', ожидает подтверждения' : ''}`
)

export const UnassignedOrganizations = ({ organizations, count, assignHref, open, onAssign }: {
  organizations: Organization[]
  count: number
  assignHref: string | null
  open: MatrixOpenState
  onAssign?: AssignHandler
}) => {
  const [all, setAll] = useState(false)
  return (
    <details
      className="control-manager control-manager--unassigned"
      open={open.isOpen('unassigned-organizations', true)}
      onToggle={(event) => open.onToggle('unassigned-organizations', event.currentTarget.open)}
    >
      <summary>
        <span className="control-manager__name">Вузы без КАМ</span>
        <span className="control-totals"><span className="control-totals__overdue">вузов {count}</span></span>
      </summary>
      <ul className="control-unassigned">
        {(all ? organizations : organizations.slice(0, unassignedListLimit)).map((organization) => (
          <li key={organization.id}>
            <a href={`#/organizations/${organization.id}`}>{organization.name}</a>
            <span className="control-muted">{unassignedReason(organization)}</span>
            {onAssign !== undefined && (
              <button type="button" className="control-totals__pick" onClick={() => onAssign(organization.id)}>Назначить КАМ</button>
            )}
          </li>
        ))}
      </ul>
      <p className="control-manager__assign">
        {onAssign === undefined && assignHref !== null && <a className="button-link" href={assignHref}>Назначить КАМ</a>}
        {organizations.length > unassignedListLimit && (assignHref === null
          // Отбор «требуют назначения» в разделе «Вузы» есть только у руководителя, поэтому «Руководство» раскрывает список здесь.
          ? <button type="button" className="button--secondary" onClick={() => setAll((value) => !value)}>{all ? 'Свернуть список' : `Все ${organizations.length}`}</button>
          : <a href="#/organizations?requiresAssignment=true">{`Все ${organizations.length} и массовая передача`}</a>)}
      </p>
    </details>
  )
}
