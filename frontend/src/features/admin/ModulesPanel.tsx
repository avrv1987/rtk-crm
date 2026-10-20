import { useCallback, useEffect, useState } from 'react'
import { apiClient, type ModuleStatus } from '../../shared/api/client'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { moduleHealth } from './adminSettings'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import './adminUi.css'

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; modules: ModuleStatus[] }
  | { kind: 'failed'; requestId?: string }

const toneClass = { off: 'status status--planned', ok: 'status', warning: 'status status--missing', error: 'status status--overdue' }

export const ModulesPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [state, setState] = useState<State>({ kind: 'loading' })

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', modules: await apiClient.getModuleStatuses() })
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
    <section className="admin-page" aria-labelledby="modules-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="modules-title">Состояние модулей</h2>
        <HintButton label="О разделе">
          Какие модули включены и исправны ли они: последняя успешная операция и ошибки за 7 дней. Включает и выключает
          модули администратор сервера переменной окружения из столбца «Переменная» — экран её только показывает.
        </HintButton>
        <span className="page-title__spacer" />
        <button type="button" className="button--secondary" onClick={() => void load()}>Обновить</button>
      </div>
      {state.kind === 'loading' && <p className="organizations-message" role="status">Проверяем модули…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось получить состояние модулей.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && (
        <div className="admin-table-wrap">
          <table className="admin-table" aria-label="Модули">
            <thead>
              <tr>
                <th scope="col">Модуль</th>
                <th scope="col">Состояние</th>
                <th scope="col">Последняя успешная операция</th>
                <th scope="col">Последняя ошибка</th>
                <th scope="col">Переменная</th>
              </tr>
            </thead>
            <tbody>
              {state.modules.map((module) => {
                const health = moduleHealth(module)
                return (
                  <tr key={module.code}>
                    <td className="admin-table__cell-main"><span className="admin-table__main">{module.title}</span></td>
                    <td data-label="Состояние"><span className={toneClass[health.tone]}>{health.text}</span></td>
                    <td data-label="Последняя успешная операция">
                      {module.lastSuccessAt === null
                        ? '—'
                        : `${formatDateTime(module.lastSuccessAt)}${module.lastSuccess === null ? '' : `: ${module.lastSuccess}`}`}
                    </td>
                    <td data-label="Последняя ошибка">{module.lastError ?? '—'}</td>
                    <td data-label="Переменная"><code>{module.variable}</code></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
