import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { apiClient, isAccessDenied, type AdminReminders } from '../../shared/api/client'
import { formatCalendarDate } from '../../shared/format/datetime'
import { dueText, requestKindLabels } from './adminSettings'
import { handledSessionError, type SessionHandlers } from './adminShared'
import '../work/workControl.css'

type Props = SessionHandlers & { refreshKey: string }

// Колокольчик администратора: обращения субъектов ПДн, срок ответа которых истёк или подходит
// (настройка «Напоминать о сроке ответа за»). Исполнение — в «Обращениях субъектов».
export const AdminReminderCenter = ({ refreshKey, onSessionExpired, onProfileUnavailable }: Props) => {
  const [reminders, setReminders] = useState<AdminReminders | null>(null)
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const titleId = useId()

  const load = useCallback(async () => {
    try {
      setReminders(await apiClient.getAdminReminders())
    } catch (error) {
      if (!handledSessionError(error, { onSessionExpired, onProfileUnavailable }) && !isAccessDenied(error)) {
        setReminders(null)
      }
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load()
  }, [load, refreshKey])

  useEffect(() => {
    if (!open) {
      return
    }
    const closeOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [open])

  const items = reminders?.personalDataRequests ?? []
  const overdue = items.filter((item) => item.dueState === 'OVERDUE').length

  return (
    <div className="reminder-center" ref={rootRef}>
      <button
        type="button"
        className="reminder-center__open"
        aria-expanded={open}
        aria-controls={`${titleId}-panel`}
        aria-label={`Напоминания администратора${items.length > 0 ? `, обращений субъектов ПДн с близким или истёкшим сроком: ${items.length}` : ''}`}
        title="Напоминания"
        onClick={() => setOpen((value) => !value)}
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10 21h4" />
        </svg>
        {items.length > 0 && <span className="reminder-center__badge" aria-hidden="true">{items.length}</span>}
      </button>
      <div
        id={`${titleId}-panel`}
        className="reminder-center__panel"
        role="dialog"
        aria-modal="false"
        aria-labelledby={titleId}
        hidden={!open}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            setOpen(false)
          }
        }}
      >
        <div className="reminder-center__dialog-header">
          <h2 id={titleId}>Обращения субъектов ПДн</h2>
          <button type="button" className="button--secondary" onClick={() => setOpen(false)}>Закрыть</button>
        </div>
        <div className="reminder-center__body" onClick={(event) => {
          if (event.target instanceof HTMLAnchorElement) {
            setOpen(false)
          }
        }}>
          {items.length === 0
            ? <p>Обращений с близким или истёкшим сроком ответа нет.</p>
            : (
              <>
                <p className="reminder-center__totals">
                  Срок истёк: {overdue}, срок в ближайшие {reminders?.remindDays ?? 0} дн.: {items.length - overdue}.
                </p>
                <ul className="reminder-center__list">
                  {items.map((item) => (
                    <li key={item.id}>
                      <a href="#/admin/personal-data-requests">{requestKindLabels[item.kind]}</a>
                      <span>Поступило {formatCalendarDate(item.receivedOn)}{item.assigneeName === null ? '' : `, ${item.assigneeName}`}</span>
                      <span className={`status status--${item.dueState === 'OVERDUE' ? 'overdue' : 'missing'}`}>
                        {dueText(item, formatCalendarDate)}
                      </span>
                    </li>
                  ))}
                </ul>
              </>
            )}
        </div>
      </div>
    </div>
  )
}
