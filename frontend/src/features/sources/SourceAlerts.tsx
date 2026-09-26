import { useCallback, useEffect, useState } from 'react'
import { apiClient, type DataSource } from '../../shared/api/client'
import { accessHandled, formatDateTime } from './sourceFormat'
import './sources.css'

type SourceAlertsProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
  refreshSignal?: number
}

const refreshIntervalMs = 60_000

const failed = (source: DataSource) => source.configured && source.lastRun?.status === 'FAILED'

const stale = (source: DataSource) => source.configured && source.stale === true && !failed(source)

export const SourceAlerts = ({ onSessionExpired, onProfileUnavailable, refreshSignal }: SourceAlertsProps) => {
  const [sources, setSources] = useState<DataSource[]>([])
  const [unavailable, setUnavailable] = useState(false)

  const load = useCallback(async () => {
    try {
      setSources(await apiClient.listDataSources())
      setUnavailable(false)
    } catch (error) {
      if (!accessHandled(error, onSessionExpired, onProfileUnavailable)) {
        setUnavailable(true)
      }
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load()
    const timer = window.setInterval(() => void load(), refreshIntervalMs)
    return () => window.clearInterval(timer)
  }, [load, refreshSignal])

  const failures = sources.filter(failed)
  const staleSources = sources.filter(stale)
  if (failures.length === 0 && staleSources.length === 0 && !unavailable) {
    return null
  }
  const showSources = () => document.getElementById('data-sources-title')?.scrollIntoView({ behavior: 'smooth' })

  return (
    <div className={failures.length > 0 || unavailable ? 'source-alerts' : 'source-alerts source-alerts--stale'} role="alert">
      {unavailable && <p><strong>Состояние источников данных не загрузилось.</strong> Проверьте раздел «Источники данных».</p>}
      {failures.map((source) => (
        <p key={source.source}>
          <strong>Синхронизация «{source.title}» завершилась ошибкой</strong>{' '}
          {formatDateTime(source.lastRun?.finishedAt ?? source.lastRun?.createdAt)}: {source.lastRun?.errorMessage ?? source.lastRun?.errorCode}.
          {' '}Данные в CRM не изменены; последняя успешная синхронизация — {formatDateTime(source.lastSuccessAt)}.
        </p>
      ))}
      {staleSources.map((source) => (
        <p key={source.source}>
          <strong>Данные «{source.title}» устарели:</strong> последняя успешная синхронизация — {formatDateTime(source.lastSuccessAt)}.
        </p>
      ))}
      <div className="source-panel__row">
        <button type="button" className="button--secondary" onClick={showSources}>Перейти к источникам данных</button>
      </div>
    </div>
  )
}
