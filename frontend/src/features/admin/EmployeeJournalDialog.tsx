import { useCallback, useEffect, useState } from 'react'
import { apiClient, type CrmProfile, type CrmProfileEvent } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { CardDialog } from '../interactions/cardUi'
import { LoadFailure } from './AdminErrors'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import { eventChanges, profileName } from './employeeEdit'
import './adminUi.css'

type JournalState =
  | { kind: 'loading' }
  | { kind: 'ready'; events: CrmProfileEvent[] }
  | { kind: 'failed'; requestId?: string }

type JournalBodyProps = SessionHandlers & { profile: CrmProfile }

const JournalBody = ({ profile, onSessionExpired, onProfileUnavailable }: JournalBodyProps) => {
  const [state, setState] = useState<JournalState>({ kind: 'loading' })

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', events: await apiClient.listCrmProfileEvents(profile.id) })
    } catch (error) {
      if (!handledSessionError(error, { onSessionExpired, onProfileUnavailable })) {
        setState({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }, [onProfileUnavailable, onSessionExpired, profile.id])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <section className="admin-profile-journal" aria-label={`Журнал изменений профиля ${profileName(profile)}`}>
      {state.kind === 'loading' && <p role="status">Загружаем журнал…</p>}
      {state.kind === 'failed' && (
        <LoadFailure text="Не удалось загрузить журнал." requestId={state.requestId} onRetry={() => void load()} />
      )}
      {state.kind === 'ready' && state.events.length === 0 && <p>Изменений профиля через администрирование ещё не было.</p>}
      {state.kind === 'ready' && state.events.length > 0 && (
        <ol>
          {state.events.map((event) => (
            <li key={event.id}>
              <p>
                <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
                {' — '}
                {event.actorDisplayName}
              </p>
              <ul>
                {eventChanges(event).map((line) => <li key={line}>{line}</li>)}
              </ul>
              <SupportDetails requestId={event.requestId} />
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

type EmployeeJournalDialogProps = SessionHandlers & {
  profile: CrmProfile | null
  onClose: () => void
}

export const EmployeeJournalDialog = ({ profile, onClose, ...handlers }: EmployeeJournalDialogProps) => (
  <CardDialog open={profile !== null} title={profile === null ? '' : `Журнал изменений: ${profileName(profile)}`} onClose={onClose}>
    {profile !== null && <JournalBody key={profile.id} profile={profile} {...handlers} />}
  </CardDialog>
)
