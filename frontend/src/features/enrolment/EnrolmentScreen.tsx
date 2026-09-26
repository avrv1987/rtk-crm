import { useCallback, useState } from 'react'
import { EnrolmentStreamView } from './EnrolmentStreamView'
import { EnrolmentStreamsList } from './EnrolmentStreamsList'
import { LearnerCardView } from './LearnerCardView'
import { handledEnrolmentAccessError } from './enrolmentShared'
import './enrolment.css'

type EnrolmentScreenProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type BackTarget = { kind: 'streams' } | { kind: 'stream'; streamId: string }

type EnrolmentView =
  | { kind: 'streams' }
  | { kind: 'stream'; streamId: string }
  | { kind: 'learner'; learnerId: string; back: BackTarget; notice?: string }

export const EnrolmentScreen = ({ onSessionExpired, onProfileUnavailable }: EnrolmentScreenProps) => {
  const [view, setView] = useState<EnrolmentView>({ kind: 'streams' })
  const [blocked, setBlocked] = useState<'FORBIDDEN' | 'UNAVAILABLE' | null>(null)

  const onAccessError = useCallback((error: unknown) => handledEnrolmentAccessError(error, {
    onSessionExpired,
    onProfileUnavailable,
    onForbidden: () => setBlocked('FORBIDDEN'),
    onUnavailable: () => setBlocked('UNAVAILABLE')
  }), [onProfileUnavailable, onSessionExpired])

  if (blocked !== null) {
    return (
      <section className="enrolment" aria-labelledby="enrolment-blocked-title">
        <h2 id="enrolment-blocked-title">Зачисление</h2>
        <p role="alert">
          {blocked === 'FORBIDDEN' ? 'Недостаточно прав: раздел доступен только оператору зачисления.' : 'Раздел недоступен.'}
        </p>
      </section>
    )
  }

  if (view.kind === 'stream') {
    return (
      <EnrolmentStreamView
        key={view.streamId}
        streamId={view.streamId}
        onBack={() => setView({ kind: 'streams' })}
        onOpenLearner={(learnerId) => setView({ kind: 'learner', learnerId, back: { kind: 'stream', streamId: view.streamId } })}
        onAccessError={onAccessError}
      />
    )
  }

  if (view.kind === 'learner') {
    return (
      <LearnerCardView
        key={view.learnerId}
        learnerId={view.learnerId}
        initialNotice={view.notice}
        onBack={() => setView(view.back)}
        onNavigateLearner={(learnerId, notice) => setView({ kind: 'learner', learnerId, back: view.back, notice })}
        onAccessError={onAccessError}
      />
    )
  }

  return (
    <EnrolmentStreamsList
      onOpenStream={(streamId) => setView({ kind: 'stream', streamId })}
      onOpenLearner={(learnerId) => setView({ kind: 'learner', learnerId, back: { kind: 'streams' } })}
      onAccessError={onAccessError}
    />
  )
}
