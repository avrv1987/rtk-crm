import { useState, type ReactNode } from 'react'
import type { SyncRun } from '../../shared/api/client'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { formatDateTime } from './sourceFormat'
import { sourceStateLabels, type SourceState } from './sourceTabs'
import './sourcePage.css'

const runStatusLabels: Record<SyncRun['status'], string> = {
  PENDING: 'В очереди',
  RUNNING: 'Выполняется',
  SUCCEEDED: 'Завершена',
  FAILED: 'Ошибка'
}

const runStatusLabel = (run: SyncRun) => (
  run.status === 'SUCCEEDED' && run.failedCount > 0 ? 'Завершена частично' : runStatusLabels[run.status]
)

export const SourceRunSummary = ({ run }: { run: SyncRun }) => (
  <div className="src-run">
    <p>
      <strong>Последний запуск:</strong> {runStatusLabel(run)}, {formatDateTime(run.finishedAt ?? run.startedAt ?? run.createdAt)}
    </p>
    <dl className="src-run__counters">
      <div><dt>Получено</dt><dd>{run.fetchedCount}</dd></div>
      <div><dt>Создано</dt><dd>{run.createdCount}</dd></div>
      <div><dt>Обновлено</dt><dd>{run.updatedCount}</dd></div>
      <div><dt>Пропущено</dt><dd>{run.skippedCount}</dd></div>
      <div><dt>Требуют сопоставления</dt><dd>{run.needsMappingCount}</dd></div>
      <div><dt>Ошибки</dt><dd>{run.failedCount}</dd></div>
    </dl>
    {run.errorMessage && (
      <>
        <p className={run.status === 'FAILED' ? 'src-error' : undefined}>{run.errorMessage}</p>
        <SupportDetails code={run.errorCode ?? undefined} />
      </>
    )}
  </div>
)

type SourceCardProps = {
  title: string
  state: SourceState
  demoContract?: boolean
  hint?: ReactNode
  facts?: ReactNode
  reason?: string | null
  lastRun?: SyncRun | null
  runnable: boolean
  syncDisabled: boolean
  syncing: boolean
  onSync: () => void
  syncError?: ReactNode
  links?: ReactNode
  connection?: ReactNode
  children?: ReactNode
  history?: ReactNode
}

// Единая оболочка вкладки источника: источники отличаются только содержимым слотов.
export const SourceCard = ({
  title,
  state,
  demoContract,
  hint,
  facts,
  reason,
  lastRun,
  runnable,
  syncDisabled,
  syncing,
  onSync,
  syncError,
  links,
  connection,
  children,
  history
}: SourceCardProps) => {
  const [settingsOpen, setSettingsOpen] = useState(false)
  return (
    <section className="src-card" aria-label={title}>
      <div className="src-card__head">
        <h3>{title}</h3>
        <span className={`src-state src-state--${state}`}>{sourceStateLabels[state]}</span>
        {demoContract && (
          <>
            <span className="src-badge">Демо-контракт</span>
            <HintButton label="Что значит «Демо-контракт»">
              Источник подключён по предложенному контракту: API заказчика ещё не получен, поэтому демонстрационный стенд не
              подтверждает совместимость.
            </HintButton>
          </>
        )}
        {hint && <HintButton label={`О источнике «${title}»`}>{hint}</HintButton>}
      </div>
      {reason && <p className="src-reason">{reason}</p>}
      {facts}
      {runnable && (lastRun ? <SourceRunSummary run={lastRun} /> : <p className="src-muted">Синхронизация ещё не запускалась.</p>)}
      <div className="src-actions">
        {runnable && (
          <button
            type="button"
            className={settingsOpen ? 'button--secondary' : undefined}
            onClick={onSync}
            disabled={syncDisabled}
          >
            {syncing ? 'Запускаем…' : 'Запустить синхронизацию'}
          </button>
        )}
        {connection && (
          <button
            type="button"
            className="button--secondary"
            aria-expanded={settingsOpen}
            onClick={() => setSettingsOpen((value) => !value)}
          >
            Настройки подключения
          </button>
        )}
        {links}
      </div>
      {syncError}
      {settingsOpen && connection && <div className="src-connection">{connection}</div>}
      {children}
      {history}
    </section>
  )
}
