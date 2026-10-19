import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { apiClient, isAccessDenied, type AgentPlanListItem, type ReminderDigest } from '../../shared/api/client'
import { getModules } from '../../shared/modules'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { kindLabels } from '../agentPlans/agentPlan'
import { listPartnerReviewQueue, type PartnerReviewItem } from '../partner/partnerApi'
import { partnerRequestStatusLabels } from '../partner/partnerRequestModel'
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
  + digest.qualificationDue.length + digest.expiringAgreements.length + digest.unassignedAfterBlock.length
)

const cardLink = (organizationId: string, interactionId: string) => `#/organizations/${organizationId}/${interactionId}`

export const ReminderAgreements = ({ agreements }: { agreements: ReminderDigest['expiringAgreements'] }) => {
  const today = todayIso()
  return (
    <ul className="reminder-center__list">
      {agreements.map((agreement) => (
        <li key={agreement.agreementId}>
          <a href={`#/organizations/${agreement.organizationId}?agreement=${agreement.agreementId}`}>Договор № {agreement.number}</a>
          <span>{agreement.organizationName}{agreement.ownerManagerName === null ? '' : `, ${agreement.ownerManagerName}`}</span>
          <span className={`status status--${agreement.validUntil < today ? 'overdue' : 'missing'}`}>
            {agreement.validUntil < today ? 'Срок истёк' : 'Действует до'} {formatDate(agreement.validUntil)}
            {agreement.plannedKind === 'RENEWAL' && agreement.plannedOn ? `; продление запланировано на ${formatDate(agreement.plannedOn)}` : ''}
          </span>
        </li>
      ))}
    </ul>
  )
}

export const ReminderCenter = ({ profileId, refreshKey, onSessionExpired, onProfileUnavailable }: ReminderCenterProps) => {
  const [state, setState] = useState<DigestState>({ kind: 'loading' })
  const [settingState, setSettingState] = useState<SettingState>({ kind: 'idle' })
  const [open, setOpen] = useState(false)
  const [fresh, setFresh] = useState(false)
  const [partnerReview, setPartnerReview] = useState<PartnerReviewItem[]>([])
  const [agentPlans, setAgentPlans] = useState<AgentPlanListItem[]>([])
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
      // Ответы вузов загружаются отдельно: сбой этого списка не должен скрывать сроки и просрочки.
      if (getModules().partner) {
        listPartnerReviewQueue()
          .then((items) => {
            if (version === requestVersion.current) {
              setPartnerReview(items)
            }
          })
          .catch(() => setPartnerReview([]))
      }
      // Планы ИИ-агента, ждущие подтверждения, — тоже отдельно и только при включённом MCP.
      if (getModules().mcp) {
        apiClient.listAgentPlans()
          .then((items) => {
            if (version === requestVersion.current) {
              setAgentPlans(items)
            }
          })
          .catch(() => setAgentPlans([]))
      }
    } catch (error) {
      if (version !== requestVersion.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable }) || isAccessDenied(error)) {
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

  useEffect(() => {
    const closeOnNavigation = () => setOpen(false)
    window.addEventListener('hashchange', closeOnNavigation)
    return () => window.removeEventListener('hashchange', closeOnNavigation)
  }, [])

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
  const partnerWaiting = partnerReview.length
  const plansWaiting = agentPlans.length
  // Сигнал руководителю: вузы остались без КАМ после блокировки, их нужно назначить.
  const unassigned = state.kind === 'ready' ? state.digest.unassignedAfterBlock.length : 0

  return (
    <div className="reminder-center" ref={rootRef}>
      <button
        ref={buttonRef}
        type="button"
        className={`reminder-center__open${fresh ? ' reminder-center__open--fresh' : ''}`}
        aria-expanded={open}
        aria-controls={`${titleId}-panel`}
        aria-label={`Напоминания${overdue === null ? '' : `, просрочено шагов: ${overdue}`}${partnerWaiting > 0 ? `, ответов вузов ждут проверки: ${partnerWaiting}` : ''}${plansWaiting > 0 ? `, планов ИИ-агента ждут подтверждения: ${plansWaiting}` : ''}${unassigned > 0 ? `, вузов без КАМ после блокировки: ${unassigned}` : ''}${fresh ? ', новая сводка на сегодня' : ''}`}
        title="Напоминания"
        data-overdue={overdue ?? undefined}
        onClick={() => open ? closePanel() : openPanel()}
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10 21h4" />
        </svg>
        {overdue !== null && overdue > 0 && <span className="reminder-center__badge" aria-hidden="true">{overdue}</span>}
        {(fresh || partnerWaiting > 0 || plansWaiting > 0 || unassigned > 0) && overdue === 0 && <span className="reminder-center__dot" aria-hidden="true" />}
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
            {partnerWaiting > 0 && (
              <section aria-labelledby={`${titleId}-partner`}>
                <h3 id={`${titleId}-partner`}>Ответы вузов ждут проверки</h3>
                <ul className="reminder-center__list">
                  {partnerReview.map((item) => (
                    <li key={item.requestId}>
                      <a href={cardLink(item.organizationId, item.interactionId)}>{item.action}</a>
                      <span>{item.organizationName} · {item.workTitle}</span>
                      <span className="status status--planned">
                        {partnerRequestStatusLabels[item.status]} {formatDateTime(item.updatedAt)}
                      </span>
                    </li>
                  ))}
                </ul>
              </section>
            )}
            {plansWaiting > 0 && (
              <section aria-labelledby={`${titleId}-agent-plans`}>
                <h3 id={`${titleId}-agent-plans`}>Планы ИИ-агента ждут подтверждения</h3>
                <ul className="reminder-center__list">
                  {agentPlans.map((plan) => (
                    <li key={plan.id}>
                      <a href={`#/agent-plans/${plan.id}`}>{kindLabels[plan.kind]}</a>
                      <span>{plan.organizationName} · {plan.interactionTitle}</span>
                      <span className="status status--planned">Действует до {formatDateTime(plan.expiresAt)}</span>
                    </li>
                  ))}
                </ul>
                <a href="#/agent-plans">Все планы</a>
              </section>
            )}
            {state.digest.unassignedAfterBlock.length > 0 && (
              <section aria-labelledby={`${titleId}-unassigned`}>
                <h3 id={`${titleId}-unassigned`}>Вузы без КАМ после блокировки</h3>
                <ul className="reminder-center__list">
                  {state.digest.unassignedAfterBlock.map((item) => (
                    <li key={item.organizationId}>
                      <a href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a>
                      <span className="status status--overdue">
                        {item.previousOwnerName === null ? 'КАМ заблокирован' : `КАМ «${item.previousOwnerName}» заблокирован`}
                        {' '}{formatDateTime(item.unassignedAt)}, вузы не переданы — назначьте КАМ
                      </span>
                    </li>
                  ))}
                </ul>
              </section>
            )}
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
              {state.digest.overdueTotal > 0 && (
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
            <section aria-labelledby={`${titleId}-agreements`}>
              <h3 id={`${titleId}-agreements`}>Договоры истекают</h3>
              {state.digest.expiringAgreements.length === 0
                ? <p>Договоров со сроком в ближайшие {state.digest.agreementNoticeDays} дней нет.</p>
                : <ReminderAgreements agreements={state.digest.expiringAgreements} />}
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
            <section aria-labelledby={`${titleId}-qualification`}>
              <h3 id={`${titleId}-qualification`}>Пора планировать повышение квалификации</h3>
              {state.digest.qualificationDue.length === 0 ? <p>Таких взаимодействий нет.</p> : (
                <ul className="reminder-center__list">
                  {state.digest.qualificationDue.map((item) => (
                    <li key={item.interactionId}>
                      <a href={cardLink(item.organizationId, item.interactionId)}>{item.title}</a>
                      <span>{item.organizationName}</span>
                      <span className="status status--missing">
                        Обучение преподавателей {formatDate(item.trainedOn)}; этап «{item.stageName}» не отмечен
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
