import { useEffect, useState } from 'react'
import { ApiError, apiClient, type LearningSnapshot } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'

type LearningSnapshotsProps = {
  interactionId: string
  version: number
  hasProgram: boolean
  canRefresh: boolean
  onRefreshed: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type SnapshotsState =
  | { kind: 'loading' }
  | { kind: 'ready'; snapshots: LearningSnapshot[] }
  | { kind: 'failed'; requestId: string | null }

type RefreshState =
  | { kind: 'idle' }
  | { kind: 'running' }
  | { kind: 'done'; message: string }
  | { kind: 'failed'; message: string; requestId: string | null }

const dateTime = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeStyle: 'short' })

const date = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeZone: 'UTC' })

const runLabel = ({ runStartsOn, runEndsOn }: LearningSnapshot) => {
  if (!runStartsOn || !runEndsOn) {
    return 'даты не подтверждены администратором'
  }
  const lastDay = new Date(`${runEndsOn}T00:00:00Z`)
  lastDay.setUTCDate(lastDay.getUTCDate() - 1)
  return `с ${date.format(new Date(`${runStartsOn}T00:00:00Z`))} по ${date.format(lastDay)}`
}

const completionLabel = (snapshot: LearningSnapshot) => (
  snapshot.completed === null || snapshot.completed === undefined
    ? 'нет данных: завершение курса в Moodle не отслеживается'
    : `${snapshot.completed} из ${snapshot.participants}`
)

export const LearningSnapshots = ({
  interactionId,
  version,
  hasProgram,
  canRefresh,
  onRefreshed,
  onSessionExpired,
  onProfileUnavailable
}: LearningSnapshotsProps) => {
  const [state, setState] = useState<SnapshotsState>({ kind: 'loading' })
  const [refresh, setRefresh] = useState<RefreshState>({ kind: 'idle' })

  useEffect(() => {
    setRefresh({ kind: 'idle' })
  }, [interactionId])

  const refreshFromMoodle = () => {
    setRefresh({ kind: 'running' })
    apiClient.refreshInteractionLearningSnapshots(interactionId)
      .then(({ run, snapshots }) => {
        setState({ kind: 'ready', snapshots })
        const changed = run.createdCount + run.updatedCount
        setRefresh({
          kind: 'done',
          message: changed === 0
            ? 'Данные Moodle проверены, изменений нет.'
            : `Данные Moodle обновлены: изменилось курсов и групп — ${changed}. Событие добавлено в историю.`
        })
        if (changed > 0) {
          onRefreshed()
        }
      })
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
          onSessionExpired()
          return
        }
        if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
          onProfileUnavailable(error.requestId)
          return
        }
        setRefresh({
          kind: 'failed',
          message: error instanceof ApiError ? error.message : 'Не удалось обновить данные Moodle.',
          requestId: error instanceof ApiError ? error.requestId : null
        })
      })
  }

  useEffect(() => {
    if (!hasProgram) {
      return
    }
    let active = true
    setState({ kind: 'loading' })
    apiClient.listInteractionLearningSnapshots(interactionId)
      .then((snapshots) => {
        if (active) {
          setState({ kind: 'ready', snapshots })
        }
      })
      .catch((error: unknown) => {
        if (!active) {
          return
        }
        if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
          onSessionExpired()
          return
        }
        if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
          onProfileUnavailable(error.requestId)
          return
        }
        setState({ kind: 'failed', requestId: error instanceof ApiError ? error.requestId : null })
      })
    return () => {
      active = false
    }
  }, [hasProgram, interactionId, onProfileUnavailable, onSessionExpired, version])

  return (
    <section className="interaction-learning" aria-labelledby="interaction-learning-title">
      <h6 id="interaction-learning-title">Обучение (LMS)</h6>
      {!hasProgram && <p>Программа не указана, поэтому данные Moodle не сопоставляются с этим взаимодействием.</p>}
      {hasProgram && state.kind === 'loading' && <p role="status">Загружаем данные Moodle…</p>}
      {hasProgram && state.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить данные Moodle.</p>
          <SupportDetails requestId={state.requestId ?? undefined} />
        </div>
      )}
      {hasProgram && state.kind === 'ready' && state.snapshots.length === 0 && (
        <p>Курсы и группы Moodle для программы этого вуза ещё не сопоставлены или не синхронизированы.</p>
      )}
      {hasProgram && canRefresh && state.kind === 'ready' && state.snapshots.length > 0 && (
        <div className="interaction-learning__refresh">
          <button type="button" onClick={refreshFromMoodle} disabled={refresh.kind === 'running'}>
            {refresh.kind === 'running' ? 'Обновляем данные LMS…' : 'Обновить данные LMS'}
          </button>
          {refresh.kind === 'done' && <p role="status">{refresh.message}</p>}
          {refresh.kind === 'failed' && (
            <div role="alert">
              <p>{refresh.message}</p>
              <SupportDetails requestId={refresh.requestId ?? undefined} />
            </div>
          )}
        </div>
      )}
      {hasProgram && state.kind === 'ready' && state.snapshots.length > 0 && (
        <ul>
          {state.snapshots.map((snapshot) => (
            <li key={`${snapshot.courseId}:${snapshot.groupId ?? ''}`}>
              <strong>
                {snapshot.courseName}
                {snapshot.groupName && `, группа «${snapshot.groupName}»`}
              </strong>
              <dl>
                <div><dt>Обучающихся</dt><dd>{snapshot.participants}</dd></div>
                <div><dt>Завершили</dt><dd>{completionLabel(snapshot)}</dd></div>
                <div>
                  <dt>Не завершили</dt>
                  <dd>{snapshot.notCompleted ?? 'нет данных'}</dd>
                </div>
                <div><dt>Статус неизвестен</dt><dd>{snapshot.unknown}</dd></div>
                <div><dt>Поток обучения</dt><dd>{runLabel(snapshot)}</dd></div>
                {snapshot.groupId === null || snapshot.groupId === undefined
                  ? <div><dt>Групп с обучающимися</dt><dd>{snapshot.groupsCount}</dd></div>
                  : null}
                <div><dt>Преподавателей</dt><dd>{snapshot.teachers}</dd></div>
                <div><dt>Наблюдение</dt><dd>{dateTime.format(new Date(snapshot.observedAt))}</dd></div>
              </dl>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
