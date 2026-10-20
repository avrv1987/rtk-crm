import { formatDate } from './agreementsApi'
import type { ReportingReminders } from './reportingApi'
import { activityListStatusLabels, daysLeftLabel } from './reportingModel'

export const hasReportingReminders = (reminders: ReportingReminders | null | undefined) => (
  reminders !== null && reminders !== undefined && (reminders.deadlines.length > 0 || reminders.lists.length > 0)
)

// Сроки отчётного цикла и перечни на согласовании или возвращённые — общий список колокольчика и «Контроля».
export const ReportingReminderList = ({ reminders, listClassName }: { reminders: ReportingReminders; listClassName: string }) => (
  <ul className={listClassName}>
    {reminders.deadlines.map((deadline) => (
      <li key={`deadline:${deadline.id}`}>
        <strong>{deadline.name}</strong>
        <span>{formatDate(deadline.dueOn)}{deadline.deliverable ? ` — ${deadline.deliverable}` : ''}</span>
        <span className={`status status--${deadline.daysLeft <= 0 ? 'overdue' : 'missing'}`}>{daysLeftLabel(deadline.daysLeft)}</span>
      </li>
    ))}
    {reminders.lists.map((list) => (
      <li key={`list:${list.id}`}>
        <a href={`#/organizations/${list.organizationId}?activityList=${list.id}`}>{list.title}</a>
        <span>{list.organizationName}</span>
        <span className={`status status--${list.status === 'RETURNED' ? 'overdue' : 'planned'}`}>
          {activityListStatusLabels[list.status]}{list.status === 'REVIEW' && list.reviewDueOn ? `, ответ вуза до ${formatDate(list.reviewDueOn)}` : ''}
        </span>
      </li>
    ))}
  </ul>
)
