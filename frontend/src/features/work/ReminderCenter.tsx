import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { apiClient, type ReminderDigest } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessHandlers, formatDate, formatDateTime, handledAccessError, requestIdOf, todayIso } from './workShared'
import './workControl.css'

type ReminderCenterProps = AccessHandlers & {
  profileId: string
  refreshKey: string
}

type DigestState =
  | { kind: 'loading' }
  | { kind: 'ready'; digest: ReminderDigest }
  | { kind: 'failed'; requestId?: string }

type SettingState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; requestId?: string }

const shownKey = (profileId: string) => `rtk-crm:reminders-shown:${profileId}`
const shownWithoutStorage = new Map<string, string>()

const shownToday = (profileId: string) => {
  if (shownWithoutStorage.get(profileId) === todayIso()) {
    return true
  }
  try {
    return window.localStorage.getItem(shownKey(profileId)) === todayIso()
  } catch {
    return false
  }
}

const rememberShown = (profileId: string) => {
  try {
    window.localStorage.setItem(shownKey(profileId), todayIso())
  } catch {
    shownWithoutStorage.set(profileId, todayIso())
  }
}

const reminderCount = (digest: ReminderDigest) => (
  digest.overdueTotal + digest.upcomingTotal + digest.expiringLicenses.length + digest.trainingCycles.length
)

const cardLink = (organizationId: string, interactionId: string) => `#/organizations/${organizationId}/${interactionId}`

export const ReminderCenter = ({ profileId, refreshKey, onSessionExpired, onProfileUnavailable }: ReminderCenterProps) => {
  const [state, setState] = useState<DigestState>({ kind: 'loading' })
  const [settingState, setSettingState] = useState<SettingState>({ kind: 'idle' })
  const [open, setOpen] = useState(false)
  const [fresh, setFresh] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const requestVersion = useRef(0)
  const titleId = useId()

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    try {
      const digest = await apiClient.getReminders()
      if (version !== requestVersion.current) {
        return
      }
      setState({ kind: 'ready', digest })
      setFresh(digest.enabled && reminderCount(digest) > 0 && !shownToday(profileId))
    } catch (error) {
      if (version !== requestVersion.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired, profileId])

  useEffect(() => {
    void load()
  }, [load, refreshKey])

  useEffect(() => {
    const refreshVisible = () => {
      if (document.visibilityState === 'visible') {
        void load()
      }
    }
    document.addEventListener('visibilitychange', refreshVisible)
    return () => document.removeEventListener('visibilitychange', refreshVisible)
  }, [load])

  useEffect(() => () => {
    requestVersion.current += 1
  }, [])

  useEffect(() => {
    if (!open) {
      return
    }
    headingRef.current?.focus()
    const closeOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [open])

  const openPanel = () => {
    rememberShown(profileId)
    setFresh(false)
    setOpen(true)
    void load()
  }

  const closePanel = () => {
    setOpen(false)
    buttonRef.current?.focus()
  }

  const saveEnabled = async (enabled: boolean) => {
    setSettingState({ kind: 'saving' })
    try {
      const saved = await apiClient.saveReminderSettings({ enabled })
      setSettingState({ kind: 'idle' })
      setState((current) => current.kind === 'ready'
        ? { kind: 'ready', digest: { ...current.digest, enabled: saved.enabled } }
        : current)
    } catch (error) {
      if (handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setSettingState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }

  const overdue = state.kind === 'ready' ? state.digest.overdueTotal : null
  const count = state.kind === 'ready' ? reminderCount(state.digest) : null

  return (
    <div className="reminder-center" ref={rootRef}>
      <a
        className={`reminder-center__counter${overdue !== null && overdue > 0 ? ' reminder-center__counter--overdue' : ''}`}
        href="#/work?due=OVERDUE"
        aria-label={overdue === null ? 'Просроченные шаги' : `Просрочено шагов: ${overdue}. Открыть список`}
      >
        Просрочено: {overdue ?? '…'}
      </a>
      <button
        ref={buttonRef}
        type="button"
        className={`button--secondary reminder-center__open${fresh ? ' reminder-center__open--fresh' : ''}`}
        aria-expanded={open}
        aria-controls={`${titleId}-panel`}
        onClick={() => open ? closePanel() : openPanel()}
      >
        Напоминания
        {count !== null && count > 0 && <span className="reminder-center__badge">{count}</span>}
        {fresh && <span className="reminder-center__sr">, новая сводка на сегодня</span>}
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
            closePanel()
          }
        }}
      >
        <div className="reminder-center__dialog-header">
          <h2 id={titleId} ref={headingRef} tabIndex={-1}>Напоминания на {formatDate(todayIso())}</h2>
          <button type="button" className="button--secondary" onClick={closePanel}>Закрыть</button>
        </div>
        {state.kind === 'loading' && <p role="status">Собираем напоминания…</p>}
        {state.kind === 'failed' && (
          <div className="notice notice--error" role="alert">
            <p>Не удалось получить напоминания.</p>
            <SupportDetails requestId={state.requestId} />
            <button type="button" onClick={() => void load()}>Повторить</button>
          </div>
        )}
        {state.kind === 'ready' && (
          <div className="reminder-center__body" onClick={(event) => {
            if (event.target instanceof HTMLAnchorElement) {
              setOpen(false)
            }
          }}>
            <p className="reminder-center__totals">
              Просрочено: {state.digest.overdueTotal}, срок сегодня: {state.digest.dueTodayTotal},
              в ближайшие {state.digest.upcomingDays} дней: {state.digest.upcomingTotal}.
            </p>
            <section aria-labelledby={`${titleId}-overdue`}>
              <h3 id={`${titleId}-overdue`}>Просроченные шаги</h3>
              {state.digest.overdue.length === 0 ? <p>Просроченных шагов нет.</p> : (
                <ul className="reminder-center__list">
                  {state.digest.overdue.map((step) => (
                    <li key={step.interactionId}>
                      <a href={cardLink(step.organizationId, step.interactionId)}>{step.title}</a>
                      <span>{step.organizationName}{step.ownerManagerName === null ? '' : `, ${step.ownerManagerName}`}</span>
                      <span className="status status--overdue">
                        {step.nextAction?.trim() || 'Шаг не задан'}: срок {formatDateTime(step.nextActionAt)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
              {state.digest.overdueTotal > state.digest.overdue.length && (
                <a href="#/work?due=OVERDUE">Все просроченные ({state.digest.overdueTotal})</a>
              )}
            </section>
            <section aria-labelledby={`${titleId}-upcoming`}>
              <h3 id={`${titleId}-upcoming`}>Наступающие сроки</h3>
              {state.digest.upcoming.length === 0 ? <p>В ближайшие дни сроков нет.</p> : (
                <ul className="reminder-center__list">
                  {state.digest.upcoming.map((step) => (
                    <li key={step.interactionId}>
                      <a href={cardLink(step.organizationId, step.interactionId)}>{step.title}</a>
                      <span>{step.organizationName}{step.ownerManagerName === null ? '' : `, ${step.ownerManagerName}`}</span>
                      <span className="status status--planned">
                        {step.nextAction?.trim() || 'Шаг не задан'}: {formatDateTime(step.nextActionAt)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <section aria-labelledby={`${titleId}-licenses`}>
              <h3 id={`${titleId}-licenses`}>Лицензии истекают</h3>
              {state.digest.expiringLicenses.length === 0 ? <p>Истекающих лицензий нет.</p> : (
                <ul className="reminder-center__list">
                  {state.digest.expiringLicenses.map((license) => (
                    <li key={`${license.interactionId}:${license.productName}:${license.contractNumber}:${license.licenseExpiryYear}`}>
                      <a href={cardLink(license.organizationId, license.interactionId)}>{license.title}</a>
                      <span>{license.organizationName}</span>
                      <span className="status status--missing">
                        {license.productName}{license.vendorName === null ? '' : ` (${license.vendorName})`}
                        {license.contractNumber === null ? '' : `, договор ${license.contractNumber}`}: срок {license.licenseExpiryYear}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <section aria-labelledby={`${titleId}-training`}>
              <h3 id={`${titleId}-training`}>Следующий цикл повышения квалификации</h3>
              {state.digest.trainingCycles.length === 0 ? <p>Циклов в ближайшие месяцы нет.</p> : (
                <ul className="reminder-center__list">
                  {state.digest.trainingCycles.map((training) => (
                    <li key={training.interactionId}>
                      <a href={cardLink(training.organizationId, training.interactionId)}>{training.title}</a>
                      <span>{training.organizationName}</span>
                      <span className="status status--missing">
                        {training.stageName}: {formatDate(training.trainedAt.slice(0, 10))}, следующий цикл до {formatDate(training.nextCycleOn)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <label className="checkbox-field reminder-center__setting">
              <input
                type="checkbox"
                checked={state.digest.enabled}
                disabled={settingState.kind === 'saving'}
                onChange={(event) => void saveEnabled(event.target.checked)}
              />
              Отмечать новую сводку на кнопке при первом входе за день
            </label>
            {settingState.kind === 'failed' && (
              <div className="notice notice--error" role="alert">
                <p>Не удалось сохранить настройку напоминаний.</p>
                <SupportDetails requestId={settingState.requestId} />
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
