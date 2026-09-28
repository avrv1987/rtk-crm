import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { apiClient, type LmsSignal } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type AccessHandlers, commandErrorText, formatDateTime, handledAccessError, requestIdOf } from './workShared'
import './lmsSignals.css'

export const lmsSignalsSectionId = 'desk-lms-signals'

const workHref = (signal: LmsSignal) => `#/organizations/${signal.organizationId}/${signal.interactionId}`

export const LmsSignalItem = ({
  signal,
  showWork = false,
  showOwner = false,
  children
}: {
  signal: LmsSignal
  showWork?: boolean
  showOwner?: boolean
  children?: ReactNode
}) => (
  <article className={`lms-signal lms-signal--${signal.type === 'STUDENTS_APPEARED' ? 'info' : 'warning'}`}>
    <strong className="lms-signal__title">{signal.title}</strong>
    {showWork && (
      <span className="lms-signal__work">
        <a href={workHref(signal)}>{signal.interactionTitle}</a>
        {' · '}
        <a href={`#/organizations/${signal.organizationId}`}>{signal.organizationName}</a>
        {showOwner && ` · ${signal.ownerManagerName ?? 'Требует назначения'}`}
      </span>
    )}
    <p className="lms-signal__message">{signal.message}</p>
    <span className="lms-signal__observed">Данные LMS на {formatDateTime(signal.observedAt)}</span>
    {children}
  </article>
)

export const LmsSignalList = ({ signals, limit, showOwner = false }: { signals: LmsSignal[]; limit: number; showOwner?: boolean }) => {
  const [expanded, setExpanded] = useState(false)
  const shown = expanded ? signals : signals.slice(0, limit)
  return (
    <>
      <ul className="lms-signals__list">
        {shown.map((signal) => (
          <li key={signal.id}>
            <LmsSignalItem signal={signal} showWork showOwner={showOwner} />
          </li>
        ))}
      </ul>
      {signals.length > limit && (
        <button type="button" className="button--secondary" onClick={() => setExpanded((value) => !value)}>
          {expanded ? 'Свернуть' : `Показать все сигналы (${signals.length})`}
        </button>
      )}
    </>
  )
}

type CardState =
  | { kind: 'loading' }
  | { kind: 'ready'; signals: LmsSignal[] }
  | { kind: 'failed'; requestId?: string }

type LmsSignalsCardProps = AccessHandlers & {
  interactionId: string
  version: number
  canEdit: boolean
  onMoveToStage: (stageId: string) => void
}

export const LmsSignalsCard = ({
  interactionId,
  version,
  canEdit,
  onMoveToStage,
  onSessionExpired,
  onProfileUnavailable
}: LmsSignalsCardProps) => {
  const [state, setState] = useState<CardState>({ kind: 'loading' })
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [error, setError] = useState<{ text: string; requestId?: string } | null>(null)

  const handled = useCallback(
    (reason: unknown) => handledAccessError(reason, { onSessionExpired, onProfileUnavailable }),
    [onProfileUnavailable, onSessionExpired]
  )

  useEffect(() => {
    let active = true
    setState({ kind: 'loading' })
    apiClient.listInteractionLmsSignals(interactionId)
      .then((result) => {
        if (active) {
          setState({ kind: 'ready', signals: result.items })
        }
      })
      .catch((reason: unknown) => {
        if (active && !handled(reason)) {
          setState({ kind: 'failed', requestId: requestIdOf(reason) })
        }
      })
    return () => {
      active = false
    }
  }, [handled, interactionId, version])

  useEffect(() => {
    setMessage('')
    setError(null)
  }, [interactionId])

  const dismiss = (signal: LmsSignal) => {
    setBusy(signal.id)
    setMessage('')
    setError(null)
    apiClient.dismissInteractionLmsSignal(interactionId, { type: signal.type, mappingId: signal.mappingId })
      .then((result) => {
        setState({ kind: 'ready', signals: result.items })
        setMessage(`Сигнал «${signal.title}» скрыт. Он появится снова, когда изменятся данные LMS.`)
      })
      .catch((reason: unknown) => {
        if (!handled(reason)) {
          setError({ text: commandErrorText(reason, 'скрыть сигнал'), requestId: requestIdOf(reason) })
        }
      })
      .finally(() => setBusy(null))
  }

  const active = state.kind === 'ready' ? state.signals.filter((signal) => !signal.dismissed) : []
  const dismissed = state.kind === 'ready' ? state.signals.filter((signal) => signal.dismissed) : []

  return (
    <section className="lms-signals" aria-labelledby="lms-signals-title">
      <h6 id="lms-signals-title">Сигналы LMS{active.length > 0 && <span className="desk-feed__count">{active.length}</span>}</h6>
      {state.kind === 'loading' && <p role="status">Проверяем сигналы LMS…</p>}
      {state.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить сигналы LMS.</p>
          <SupportDetails requestId={state.requestId} />
        </div>
      )}
      <p className="lms-signals__status" role="status">{message}</p>
      {error !== null && (
        <div role="alert">
          <p>{error.text}</p>
          <SupportDetails requestId={error.requestId} />
        </div>
      )}
      {state.kind === 'ready' && active.length === 0 && (
        <p className="lms-signals__empty">Сигналов по данным LMS нет.</p>
      )}
      {active.length > 0 && (
        <ul className="lms-signals__list">
          {active.map((signal) => (
            <li key={signal.id}>
              <LmsSignalItem signal={signal}>
                {signal.actionHint && <p className="lms-signal__hint">{signal.actionHint}</p>}
                {canEdit && (
                  <div className="lms-signal__actions">
                    {signal.action && (
                      <button type="button" onClick={() => onMoveToStage(signal.action!.stageId)}>
                        Перейти к этапу «{signal.action.stageName}»…
                      </button>
                    )}
                    <button type="button" className="button--secondary" disabled={busy === signal.id} onClick={() => dismiss(signal)}>
                      {busy === signal.id ? 'Скрываем…' : 'Скрыть до изменения данных'}
                    </button>
                  </div>
                )}
              </LmsSignalItem>
            </li>
          ))}
        </ul>
      )}
      {dismissed.length > 0 && (
        <details className="lms-signals__dismissed">
          <summary>Скрытые сигналы ({dismissed.length})</summary>
          <ul className="lms-signals__list">
            {dismissed.map((signal) => (
              <li key={signal.id}>
                <LmsSignalItem signal={signal} />
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}
