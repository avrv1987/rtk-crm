import { useEffect, useState } from 'react'
import { reportingApi, type ReportingReminders } from '../agreements/reportingApi'
import { hasReportingReminders, ReportingReminderList } from '../agreements/ReportingReminderList'
import { type AccessHandlers, handledAccessError } from '../work/workShared'

// Сроки отчётного цикла и перечни вузов на согласовании или возвращённые; пустой блок не показывается, сбой тоже:
// это подсказка, а не данные «Контроля».
export const ControlReporting = ({ refreshKey, onSessionExpired, onProfileUnavailable }: AccessHandlers & { refreshKey: unknown }) => {
  const [reminders, setReminders] = useState<ReportingReminders | null>(null)

  useEffect(() => {
    let active = true
    reportingApi.reminders().then(
      (value) => active && setReminders(value),
      (error: unknown) => {
        if (active && !handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
          setReminders(null)
        }
      }
    )
    return () => {
      active = false
    }
  }, [onProfileUnavailable, onSessionExpired, refreshKey])

  if (reminders === null || !hasReportingReminders(reminders)) {
    return null
  }
  return (
    <details className="control-manager control-manager--idle control-no-contact">
      <summary>
        <span className="control-manager__name">Отчётность по соглашениям</span>
        <span className="control-totals">
          <span className="control-totals__overdue">
            {`сроков ${reminders.deadlines.length} · перечней ${reminders.lists.length}`}
          </span>
        </span>
      </summary>
      <p className="control-muted control-idle__note">
        Сроки отчётного цикла в ближайшие {reminders.reminderDays} дн. (их задаёт администратор) и перечни мероприятий вузов,
        которые на согласовании у вуза или возвращены на доработку.
      </p>
      <ReportingReminderList reminders={reminders} listClassName="control-unassigned" />
    </details>
  )
}
