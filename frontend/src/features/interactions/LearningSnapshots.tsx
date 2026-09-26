import { useCallback, useEffect, useState } from 'react'
import {
  apiClient,
  type InteractionSourceStatus,
  type LearningSnapshot,
  type SourcesRefresh
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { accessHandled, errorText, formatDateTime, requestIdOf, runPeriod } from '../sources/sourceFormat'
import '../sources/sources.css'

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
  | { kind: 'ready'; status: InteractionSourceStatus; snapshots: LearningSnapshot[] }
  | { kind: 'failed'; requestId: string | undefined }

type RefreshState =
  | { kind: 'idle' }
  | { kind: 'running' }
  | { kind: 'done'; result: SourcesRefresh }
  | { kind: 'failed'; message: string; requestId: string | undefined }

type SourceState = InteractionSourceStatus['lms']

const completionLabel = (snapshot: LearningSnapshot) => (
  snapshot.completed === null || snapshot.completed === undefined
    ? 'нет данных: завершение курса в Moodle не отслеживается'
    : `${snapshot.completed} из ${snapshot.participants}`
)

const SourceLine = ({ title, state, lms }: { title: string; state: SourceState; lms: boolean }) => {
  if (!state.configured) {
    return <li><strong>{title}:</strong> не подключён в конфигурации CRM</li>
  }
  return (
    <li>
      <strong>{title}:</strong>
      <span>{state.lastSuccessAt ? `обновлено ${formatDateTime(state.lastSuccessAt)}` : 'успешных обновлений ещё не было'}</span>
      {state.errorAt && (
        <span className="source-error">
          ошибка источника {formatDateTime(state.errorAt)}: {state.errorMessage ?? state.errorCode}
          {lms && state.dataObservedAt ? `; данные на ${formatDateTime(state.dataObservedAt)}` : ''}
        </span>
      )}
      {state.stale && <span className="source-badge">данные устарели</span>}
    </li>
  )
}

const SnapshotItem = ({ snapshot }: { snapshot: LearningSnapshot }) => (
  <li>
    <strong>
      {snapshot.courseName}
      {snapshot.groupName && `, группа «${snapshot.groupName}»`}
    </strong>
    {snapshot.runKind === 'TEACHERS' ? (
      <dl>
        <div><dt>Записано преподавателей</dt><dd>{snapshot.participants}</dd></div>
        <div><dt>Завершили</dt><dd>{completionLabel(snapshot)}</dd></div>
        <div><dt>Поток обучения</dt><dd>{runPeriod(snapshot.runStartsOn, snapshot.runEndsOn)}</dd></div>
        <div><dt>Наблюдение</dt><dd>{formatDateTime(snapshot.observedAt)}</dd></div>
      </dl>
    ) : (
      <dl>
        <div><dt>Обучающихся</dt><dd>{snapshot.participants}</dd></div>
        <div><dt>Завершили</dt><dd>{completionLabel(snapshot)}</dd></div>
        <div><dt>Не завершили</dt><dd>{snapshot.notCompleted ?? 'нет данных'}</dd></div>
        <div><dt>Статус неизвестен</dt><dd>{snapshot.unknown}</dd></div>
        <div><dt>Поток обучения</dt><dd>{runPeriod(snapshot.runStartsOn, snapshot.runEndsOn)}</dd></div>
        {snapshot.groupId === null || snapshot.groupId === undefined
          ? <div><dt>Групп с обучающимися</dt><dd>{snapshot.groupsCount}</dd></div>
          : null}
        <div><dt>Преподавателей</dt><dd>{snapshot.teachers}</dd></div>
        <div><dt>Наблюдение</dt><dd>{formatDateTime(snapshot.observedAt)}</dd></div>
      </dl>
    )}
  </li>
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

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  useEffect(() => {
    setRefresh({ kind: 'idle' })
  }, [interactionId])

  useEffect(() => {
    let active = true
    setState({ kind: 'loading' })
    Promise.all([
      apiClient.getInteractionSourceStatus(interactionId),
      hasProgram ? apiClient.listInteractionLearningSnapshots(interactionId) : Promise.resolve([])
    ])
      .then(([status, snapshots]) => {
        if (active) {
          setState({ kind: 'ready', status, snapshots })
        }
      })
      .catch((error: unknown) => {
        if (active && !handled(error)) {
          setState({ kind: 'failed', requestId: requestIdOf(error) })
        }
      })
    return () => {
      active = false
    }
  }, [handled, hasProgram, interactionId, version])

  const refreshSources = () => {
    setRefresh({ kind: 'running' })
    apiClient.refreshInteractionSources(interactionId)
      .then((result) => {
        setState({ kind: 'ready', status: result.status, snapshots: result.snapshots })
        setRefresh({ kind: 'done', result })
        if (result.lms.status === 'UPDATED' || result.site.status === 'UPDATED') {
          onRefreshed()
        }
      })
      .catch((error: unknown) => {
        if (!handled(error)) {
          setRefresh({
            kind: 'failed',
            message: errorText(error, 'Не удалось обновить данные источников.'),
            requestId: requestIdOf(error)
          })
        }
      })
  }

  const students = state.kind === 'ready' ? state.snapshots.filter((snapshot) => snapshot.runKind !== 'TEACHERS') : []
  const teachers = state.kind === 'ready' ? state.snapshots.filter((snapshot) => snapshot.runKind === 'TEACHERS') : []
  const learning = state.kind === 'ready' ? state.status.learning : undefined

  return (
    <section className="interaction-learning" aria-labelledby="interaction-learning-title">
      <h6 id="interaction-learning-title">Обучение (LMS)</h6>
      {state.kind === 'loading' && <p role="status">Загружаем состояние источников…</p>}
      {state.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить данные источников.</p>
          <SupportDetails requestId={state.requestId} />
        </div>
      )}
      {state.kind === 'ready' && (
        <div className="source-status" aria-label="Состояние источников">
          <ul>
            <SourceLine title="LMS" state={state.status.lms} lms />
            <SourceLine title="Сайт" state={state.status.site} lms={false} />
          </ul>
          {canRefresh && (
            <div className="source-panel__row">
              <button type="button" onClick={refreshSources} disabled={refresh.kind === 'running'}>
                {refresh.kind === 'running' ? 'Обновляем данные источников…' : 'Обновить данные источников'}
              </button>
            </div>
          )}
          {refresh.kind === 'done' && (
            <ul className="source-outcomes" role="status">
              <li>LMS: {refresh.result.lms.message}</li>
              <li>Сайт: {refresh.result.site.message}</li>
            </ul>
          )}
          {refresh.kind === 'failed' && (
            <div role="alert">
              <p>{refresh.message}</p>
              <SupportDetails requestId={refresh.requestId} />
            </div>
          )}
        </div>
      )}
      {learning && learning.state !== 'AVAILABLE' && learning.message && (
        <p className="source-reason">{learning.message}</p>
      )}
      {students.length > 0 && (
        <ul>
          {students.map((snapshot) => <SnapshotItem key={snapshot.mappingId} snapshot={snapshot} />)}
        </ul>
      )}
      {teachers.length > 0 && (
        <>
          <p>
            <strong>Обучение преподавателей (LMS)</strong> — не входит в число обучающихся и в отчёт «Востребованность программ».
          </p>
          <ul>
            {teachers.map((snapshot) => <SnapshotItem key={snapshot.mappingId} snapshot={snapshot} />)}
          </ul>
        </>
      )}
    </section>
  )
}
