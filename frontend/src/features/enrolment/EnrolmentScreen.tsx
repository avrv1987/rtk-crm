import { useCallback, useEffect, useState } from 'react'
import { EnrolmentStreamView } from './EnrolmentStreamView'
import { EnrolmentStreamsList } from './EnrolmentStreamsList'
import { LearnerCardView } from './LearnerCardView'
import { enrolmentBack, enrolmentHref, enrolmentRoute, type EnrolmentRoute } from './enrolmentRoute'
import { handledEnrolmentAccessError } from './enrolmentShared'
import './enrolment.css'

type EnrolmentScreenProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

export const EnrolmentScreen = ({ onSessionExpired, onProfileUnavailable }: EnrolmentScreenProps) => {
  const [route, setRoute] = useState<EnrolmentRoute>(() => enrolmentRoute(window.location.hash))
  const [notice, setNotice] = useState<{ learnerId: string; text: string } | null>(null)
  const [blocked, setBlocked] = useState<'FORBIDDEN' | 'UNAVAILABLE' | null>(null)

  useEffect(() => {
    const sync = () => setRoute(enrolmentRoute(window.location.hash))
    window.addEventListener('hashchange', sync)
    return () => window.removeEventListener('hashchange', sync)
  }, [])

  const go = (next: EnrolmentRoute) => {
    window.location.hash = enrolmentHref(next)
  }

  const onAccessError = useCallback((error: unknown) => handledEnrolmentAccessError(error, {
    onSessionExpired,
    onProfileUnavailable,
    onForbidden: () => setBlocked('FORBIDDEN'),
    onUnavailable: () => setBlocked('UNAVAILABLE')
  }), [onProfileUnavailable, onSessionExpired])

  if (blocked !== null) {
    return (
      <section className="enrolment" aria-label="Зачисление">
        <p role="alert">
          {blocked === 'FORBIDDEN' ? 'Недостаточно прав: раздел доступен только оператору зачисления.' : 'Раздел недоступен.'}
        </p>
      </section>
    )
  }

  const back = enrolmentBack(route)

  return (
    <>
      {back !== null && <a className="enrolment__back" href={back.href}>{back.label}</a>}
      {route.kind === 'stream' && (
        <EnrolmentStreamView
          key={route.streamId}
          streamId={route.streamId}
          onOpenLearner={(learnerId) => go({ kind: 'learner', learnerId, streamId: route.streamId })}
          onAccessError={onAccessError}
        />
      )}
      {route.kind === 'learner' && (
        <LearnerCardView
          key={route.learnerId}
          learnerId={route.learnerId}
          initialNotice={notice?.learnerId === route.learnerId ? notice.text : undefined}
          onNavigateLearner={(learnerId, text) => {
            setNotice(text === undefined ? null : { learnerId, text })
            go({ kind: 'learner', learnerId, streamId: route.streamId })
          }}
          onAccessError={onAccessError}
        />
      )}
      {route.kind === 'streams' && (
        <EnrolmentStreamsList
          onOpenStream={(streamId) => go({ kind: 'stream', streamId })}
          onOpenLearner={(learnerId) => go({ kind: 'learner', learnerId, streamId: null })}
          onAccessError={onAccessError}
        />
      )}
    </>
  )
}
