import { useState } from 'react'
import { apiClient, type SourceCode, type SyncRun } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { accessHandled, formatDateTime, requestIdOf } from './sourceFormat'
import './sources.css'

type SourceRunHistoryProps = {
  source: SourceCode
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type HistoryState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; runs: SyncRun[] }
  | { kind: 'failed'; requestId: string | undefined }

const statusLabels: Record<SyncRun['status'], string> = {
  PENDING: 'в очереди',
  RUNNING: 'выполняется',
  SUCCEEDED: 'завершена',
  FAILED: 'ошибка'
}

const triggerLabels: Record<NonNullable<SyncRun['trigger']>, string> = {
  MANUAL: 'вручную',
  SCHEDULE: 'по расписанию',
  CARD: 'из карточки',
  BOOTSTRAP: 'первая загрузка при старте'
}

export const SourceRunHistory = ({ source, onSessionExpired, onProfileUnavailable }: SourceRunHistoryProps) => {
  const [state, setState] = useState<HistoryState>({ kind: 'idle' })

  const load = async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', runs: await apiClient.listSourceSyncRuns(source) })
    } catch (error) {
      if (!accessHandled(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }

  return (
    <details
      onToggle={(event) => {
        if ((event.currentTarget as HTMLDetailsElement).open && state.kind !== 'loading') {
          void load()
        }
      }}
    >
      <summary>История запусков</summary>
      {state.kind === 'loading' && <p role="status">Загружаем историю запусков…</p>}
      {state.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить историю запусков.</p>
          <SupportDetails requestId={state.requestId} />
        </div>
      )}
      {state.kind === 'ready' && state.runs.length === 0 && <p>Запусков ещё не было.</p>}
      {state.kind === 'ready' && state.runs.length > 0 && (
        <div className="data-sources__table-scroll" role="region" aria-label="История запусков синхронизации" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th scope="col">Запуск</th>
                <th scope="col">Кто и как</th>
                <th scope="col">Результат</th>
              </tr>
            </thead>
            <tbody>
              {state.runs.map((run) => (
                <tr key={run.id}>
                  <td>{formatDateTime(run.startedAt ?? run.createdAt)}{run.finishedAt ? ` – ${formatDateTime(run.finishedAt)}` : ''}</td>
                  <td>
                    {run.startedByName ?? 'нет данных'}, {run.trigger ? triggerLabels[run.trigger] : 'вручную'}
                    {run.organizationName ? ` (${run.organizationName})` : ''}
                  </td>
                  <td>
                    {statusLabels[run.status]}: получено {run.fetchedCount}, создано {run.createdCount}, обновлено {run.updatedCount},
                    {' '}к разбору {run.needsMappingCount}, ошибок {run.failedCount}
                    {run.errorMessage && <span className={run.status === 'FAILED' ? 'source-error' : undefined}>. {run.errorMessage}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </details>
  )
}
