import { useEffect, useState } from 'react'
import { apiClient, isAccessDenied, type DataSource } from '../../shared/api/client'
import { startPolling } from '../../shared/api/polling'
import { accessHandled } from './sourceFormat'
import { sourceAlertOf } from './sourceTabs'
import './sourcePage.css'

type SourceAlertsProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
  refreshSignal?: number
}

const refreshIntervalMs = 60_000

export const SourceAlerts = ({ onSessionExpired, onProfileUnavailable, refreshSignal }: SourceAlertsProps) => {
  const [sources, setSources] = useState<DataSource[]>([])
  const [unavailable, setUnavailable] = useState(false)

  useEffect(() => startPolling(async () => {
    try {
      setSources(await apiClient.listDataSources())
      setUnavailable(false)
      return true
    } catch (error) {
      if (!accessHandled(error, onSessionExpired, onProfileUnavailable) && !isAccessDenied(error)) {
        setUnavailable(true)
      }
      return !isAccessDenied(error)
    }
  }, refreshIntervalMs), [onProfileUnavailable, onSessionExpired, refreshSignal])

  const alert = sourceAlertOf(sources, unavailable)
  if (alert === null) {
    return null
  }

  return (
    <div className={alert.kind === 'stale' ? 'src-alert-line src-alert-line--stale' : 'src-alert-line'} role="alert">
      <p><a href={alert.href}>{alert.text}</a>{alert.more > 0 && ` (и ещё ${alert.more})`}</p>
    </div>
  )
}
