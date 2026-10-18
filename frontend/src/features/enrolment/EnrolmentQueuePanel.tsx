import { useCallback, useEffect, useState } from 'react'
import { ApiError, createIdempotencyKey } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import {
  getEnrolmentQueue,
  moveEnrolmentInMoodle,
  reconcileEnrolmentLms,
  type EnrolmentQueue,
  type EnrolmentQueueItem
} from './enrolmentBatchApi'
import { queueKinds, queueOrder } from './enrolmentQueueModel'
import { enrolmentHref } from './enrolmentRoute'
import { type AccessErrorReporter, learnerFullName, responseErrorMessage } from './enrolmentShared'

type EnrolmentQueuePanelProps = {
  onAccessError: AccessErrorReporter
}

type QueueState =
  | { kind: 'loading' }
  | { kind: 'ready'; queue: EnrolmentQueue }
  | { kind: 'failed'; error: unknown }

const itemHref = (item: EnrolmentQueueItem) => {
  const action = queueKinds[item.kind].action
  if (action === 'batch' && item.batchId !== null) {
    return enrolmentHref({ kind: 'batch', batchId: item.batchId })
  }
  if (action === 'stream') {
    return enrolmentHref({ kind: 'stream', streamId: item.streamId })
  }
  return enrolmentHref({ kind: 'learner', learnerId: item.learnerId, streamId: item.streamId })
}

export const EnrolmentQueuePanel = ({ onAccessError }: EnrolmentQueuePanelProps) => {
  const [state, setState] = useState<QueueState>({ kind: 'loading' })
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', queue: await getEnrolmentQueue() })
    } catch (exception) {
      if (!onAccessError(exception)) {
        setState({ kind: 'failed', error: exception })
      }
    }
  }, [onAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const reconcile = async (item: EnrolmentQueueItem, viaMoodle = false) => {
    setBusy(`${item.kind}:${item.enrolmentId}`)
    setError(null)
    try {
      if (viaMoodle) {
        await moveEnrolmentInMoodle(item.enrolmentId, createIdempotencyKey())
      } else {
        await reconcileEnrolmentLms(item.enrolmentId, {
          kind: item.kind === 'CHANGED_AFTER_TRANSFER' ? 'CHANGED_AFTER_TRANSFER' : 'MOVED_AFTER_TRANSFER',
          learnerVersion: item.kind === 'CHANGED_AFTER_TRANSFER' ? item.learnerVersion : null
        }, createIdempotencyKey())
      }
      await load()
    } catch (exception) {
      if (!onAccessError(exception)) {
        setError(exception)
      }
    } finally {
      setBusy(null)
    }
  }

  if (state.kind === 'loading') {
    return <p role="status">Собираем очередь…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="notice notice--error" role="alert">
        <p>{responseErrorMessage(state.error)}</p>
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }

  const groups = queueOrder
    .map((kind) => state.queue.groups.find((group) => group.kind === kind))
    .filter((group) => group !== undefined && group.total > 0)
  return (
    <section className="enrolment__section enrolment-queue" aria-labelledby="enrolment-queue-title">
      <h3 id="enrolment-queue-title">Что обработать</h3>
      {groups.length === 0 && <p>Исключений нет: все слушатели переданы и приняты LMS.</p>}
      {error !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{responseErrorMessage(error)}</p>
          {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
        </div>
      )}
      {groups.map((group) => group !== undefined && (
        <details key={group.kind} className="enrolment-queue__group" open={group.kind === 'MOVED_AFTER_TRANSFER' || group.kind === 'LMS_ERROR'}>
          <summary>
            <strong>{queueKinds[group.kind].title}</strong> — {group.total}
            <span className="enrolment__hint"> · {queueKinds[group.kind].next}</span>
          </summary>
          <ul className="enrolment-queue__list">
            {group.items.map((item) => (
              <li key={`${item.kind}:${item.enrolmentId}`}>
                <a href={itemHref(item)}>
                  {item.lastName === null ? 'Анкета без имени' : learnerFullName({ lastName: item.lastName, firstName: item.firstName, middleName: null })}
                </a>
                <span>{item.streamTitle}{item.orderNumber !== null && <> · заявка {item.orderNumber}</>}</span>
                {item.detail !== null && <span className="enrolment-queue__detail">{item.detail}</span>}
                {item.kind === 'MOVED_AFTER_TRANSFER' && state.queue.moodleWriteAvailable && (
                  <button type="button" disabled={busy === `${item.kind}:${item.enrolmentId}`} onClick={() => void reconcile(item, true)}>
                    Перенести в Moodle
                  </button>
                )}
                {queueKinds[item.kind].action === 'reconcile' && (
                  <button type="button" className="button--secondary" disabled={busy === `${item.kind}:${item.enrolmentId}`}
                    onClick={() => void reconcile(item)}>
                    Сверка с LMS выполнена
                  </button>
                )}
              </li>
            ))}
          </ul>
          {group.total > group.items.length && <p className="enrolment__hint">Показаны первые {group.items.length} из {group.total}.</p>}
        </details>
      ))}
    </section>
  )
}
