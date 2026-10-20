import { useCallback, useEffect, useState } from 'react'
import { apiClient, type RouteVersionsRow } from '../../shared/api/client'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { versionsText } from './adminSettings'
import { handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import './adminUi.css'

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; rows: RouteVersionsRow[] }
  | { kind: 'failed'; requestId?: string }

export const RouteVersionsPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [state, setState] = useState<State>({ kind: 'loading' })

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', rows: await apiClient.getRouteVersions() })
    } catch (error) {
      if (!handledSessionError(error, { onSessionExpired, onProfileUnavailable })) {
        setState({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <section className="admin-page" aria-labelledby="route-versions-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="route-versions-title">Версии маршрута</h2>
        <HintButton label="О разделе">
          Какая команда на какой версии шаблона этапов: незавершённые работы по версиям. Переводят работы на новую версию
          руководители команд («Шаблоны этапов → История версий → Обновить работы до версии»); пока у команды есть работы на
          прежних версиях, руководитель видит напоминание в колокольчике.
        </HintButton>
      </div>
      {state.kind === 'loading' && <p className="organizations-message" role="status">Собираем сводку…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось получить сводку версий маршрута.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && state.rows.length === 0 && (
        <p className="organizations-message">Незавершённых работ, связанных с шаблонами, нет.</p>
      )}
      {state.kind === 'ready' && state.rows.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table" aria-label="Версии маршрута по командам">
            <thead>
              <tr>
                <th scope="col">Команда</th>
                <th scope="col">Шаблон</th>
                <th scope="col">Последняя версия</th>
                <th scope="col">Работы по версиям</th>
                <th scope="col">На прежних версиях</th>
              </tr>
            </thead>
            <tbody>
              {state.rows.map((row) => (
                <tr key={`${row.teamId}:${row.templateId}`}>
                  <td className="admin-table__cell-main"><span className="admin-table__main">{row.teamName}</span></td>
                  <td data-label="Шаблон">{row.templateName}</td>
                  <td data-label="Последняя версия">v{row.latestVersion}</td>
                  <td data-label="Работы по версиям">{versionsText(row)}</td>
                  <td data-label="На прежних версиях">
                    {row.outdatedWorks === 0 ? 'нет' : <span className="status status--missing">{row.outdatedWorks}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
