import type { ReactNode } from 'react'
import type { DataSource } from '../../shared/api/client'
import { SourceCard } from './SourceCard'
import { SourceRunHistory } from './SourceRunHistory'
import { formatDateTime, scheduleLabel } from './sourceFormat'
import { sourceState, sourcesHash, syncBlockReason, type MappingsSource } from './sourceTabs'
import './sourcePage.css'

export type SyncControl = {
  syncing: boolean
  busy: boolean
  error: ReactNode
  start: () => void
}

type SourceSyncCardProps = {
  source: DataSource
  sync: SyncControl
  mappingsSource?: MappingsSource
  hint?: ReactNode
  connection?: ReactNode
  children?: ReactNode
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

export const SourceSyncCard = ({
  source,
  sync,
  mappingsSource,
  hint,
  connection,
  children,
  onSessionExpired,
  onProfileUnavailable
}: SourceSyncCardProps) => {
  const reason = syncBlockReason(source)
  return (
    <SourceCard
      title={source.title}
      state={sourceState(source)}
      demoContract={source.proposedContract}
      hint={hint}
      reason={reason}
      facts={source.adapterAvailable && (
        <>
          <p className="src-muted">
            Последняя успешная синхронизация: {formatDateTime(source.lastSuccessAt)}
            {source.updatedSince && `. Изменения запрашиваются с ${formatDateTime(source.updatedSince)}`}
          </p>
          {source.configured && (
            <p className="src-muted">
              {source.schedule ? `Плановая синхронизация: ${scheduleLabel(source.schedule)} (МСК)` : 'Плановая синхронизация выключена'}
              {source.schedule && (source.nextRunAt
                ? `; следующий запуск — ${formatDateTime(source.nextRunAt)}`
                : '; не начнётся, пока в CRM нет действующего администратора')}
            </p>
          )}
        </>
      )}
      lastRun={source.lastRun}
      runnable={source.adapterAvailable}
      syncDisabled={reason !== null || sync.busy}
      syncing={sync.syncing}
      onSync={sync.start}
      syncError={sync.error}
      connection={connection}
      links={(
        <>
          {source.problemCount > 0 && (
            <a className="src-link" href={sourcesHash('review')}>Записей для разбора: {source.problemCount} — разобрать</a>
          )}
          {mappingsSource && <a className="src-link" href={sourcesHash('mappings', mappingsSource)}>Сопоставления источника</a>}
        </>
      )}
      history={source.adapterAvailable && (
        <SourceRunHistory source={source.source} onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} />
      )}
    >
      {children}
    </SourceCard>
  )
}
