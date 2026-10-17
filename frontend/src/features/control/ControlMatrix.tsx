import type { WorkControl, WorkControlItem, WorkControlManager, WorkControlTeam, WorkControlTotals } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { changeNotes, dueLabels, dueTones, routeLabel, routeShare } from './controlModel'

const statusLabels: Record<'PAUSED' | 'COMPLETED', string> = {
  PAUSED: 'Приостановлена',
  COMPLETED: 'Завершена'
}

const unassignedName = 'Требует назначения'

const WorkCells = ({ item, withPeriod }: { item: WorkControlItem; withPeriod: boolean }) => {
  const notes = withPeriod ? changeNotes(item) : []
  const extra = [item.program, ...item.products].filter((value): value is string => value !== null && value !== undefined && value !== '')
  return (
    <>
      <td data-label="Вуз и работа">
        <a className="control-work__organization" href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a>
        <a className="control-work__title" href={`#/organizations/${item.organizationId}/${item.interactionId}`}>{item.title}</a>
      </td>
      <td data-label="Программа и продукты">
        {extra.length === 0 ? <span className="control-muted">Не указаны</span> : (
          <>
            {item.direction !== null && item.direction !== undefined && <span className="control-muted">{item.direction}</span>}
            <span>{extra.join(' · ')}</span>
          </>
        )}
      </td>
      <td data-label="Этап на маршруте">
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
      <td data-label="Отметки">
        <span className="control-badges">
          {item.problems > 0 && <span className="control-badge control-badge--danger" title={item.issues ?? undefined}>Проблемы: {item.problems}</span>}
          {item.risks > 0 && <span className="control-badge control-badge--warning" title={item.issues ?? undefined}>Риски: {item.risks}</span>}
          {item.status !== null && item.status !== undefined && item.status !== 'ACTIVE' && (
            <span className="control-badge">{statusLabels[item.status]}</span>
          )}
          {notes.map((note) => <span key={note} className="control-badge control-badge--change">{note}</span>)}
        </span>
      </td>
    </>
  )
}

const TotalsLine = ({ totals, withPeriod }: { totals: WorkControlTotals; withPeriod: boolean }) => (
  <span className="control-totals">
    <span>вузов {totals.organizations}</span>
    <span>работ {totals.works}</span>
    <span className={totals.overdue > 0 ? 'control-totals__overdue' : undefined}>просрочено {totals.overdue}</span>
    <span>на этой неделе {totals.thisWeek}</span>
    <span>без шага {totals.noNextStep}</span>
    {totals.withIssues > 0 && <span className="control-totals__overdue">с проблемами или рисками {totals.withIssues}</span>}
    {withPeriod && <span>перешли этап {totals.moved}, новые {totals.created}, завершённые {totals.completed}</span>}
  </span>
)

const ManagerBlock = ({ manager, withPeriod }: { manager: WorkControlManager; withPeriod: boolean }) => (
  <details className="control-manager" open>
    <summary>
      <span className="control-manager__name">{manager.managerName ?? unassignedName}</span>
      <TotalsLine totals={manager.totals} withPeriod={withPeriod} />
    </summary>
    {manager.works.length === 0 ? <p className="control-manager__empty">Работ нет.</p> : (
      <table className="control-table">
        <thead>
          <tr>
            <th scope="col">Вуз и работа</th>
            <th scope="col">Программа и продукты</th>
            <th scope="col">Этап на маршруте</th>
            <th scope="col">Следующий шаг</th>
            <th scope="col">Отметки</th>
          </tr>
        </thead>
        <tbody>
          {manager.works.map((item) => (
            <tr key={item.interactionId} className={`control-row control-row--${dueTones[item.due]}`}>
              <WorkCells item={item} withPeriod={withPeriod} />
            </tr>
          ))}
        </tbody>
      </table>
    )}
  </details>
)

const TeamBlock = ({ team, withPeriod, named }: { team: WorkControlTeam; withPeriod: boolean; named: boolean }) => (
  <section className="control-team" aria-label={named ? (team.teamName ?? 'Команда без названия') : 'Менеджеры команды'}>
    {named && (
      <div className="control-team__head">
        <h2>{team.teamName ?? 'Команда без названия'}</h2>
        <TotalsLine totals={team.totals} withPeriod={withPeriod} />
      </div>
    )}
    {team.managers.map((manager) => (
      <ManagerBlock key={manager.managerId ?? 'unassigned'} manager={manager} withPeriod={withPeriod} />
    ))}
  </section>
)

export const ControlMatrix = ({ control, withPeriod }: { control: WorkControl; withPeriod: boolean }) => (
  control.teams.length === 0 ? <p className="control-empty">По этим отборам работ нет.</p> : (
    <div className="control-matrix">
      {control.teams.map((team) => (
        <TeamBlock key={team.teamId ?? 'team'} team={team} withPeriod={withPeriod} named={control.byTeams} />
      ))}
    </div>
  )
)
