import { useCallback, useEffect, useState } from 'react'
import { ApiError, apiClient, type SystemSetting } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { landingOptions, savedOnChange, settingDraftProblem, settingGroups, settingValueText, unitLabel } from './adminSettings'
import { productDemoHref } from '../landing/landingMode'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import './adminUi.css'

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; settings: SystemSetting[] }
  | { kind: 'failed'; requestId?: string }

const draftsOf = (settings: SystemSetting[]) => Object.fromEntries(settings.map((setting) => [setting.key, String(setting.value)]))

export const SettingsPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [resetting, setResetting] = useState<SystemSetting | null>(null)

  const handleError = useCallback((value: unknown) => (
    handledSessionError(value, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const show = (settings: SystemSetting[]) => {
    setState({ kind: 'ready', settings })
    setDrafts(draftsOf(settings))
  }

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      const settings = await apiClient.getSystemSettings()
      setState({ kind: 'ready', settings })
      setDrafts(draftsOf(settings))
    } catch (value) {
      if (!handleError(value)) {
        setState({ kind: 'failed', requestId: requestIdOf(value) })
      }
    }
  }, [handleError])

  useEffect(() => {
    void load()
  }, [load])

  const run = async (key: string, action: () => Promise<SystemSetting[]>, message: string) => {
    setBusy(key)
    setError(null)
    setNotice(null)
    try {
      show(await action())
      setNotice(message)
    } catch (value) {
      if (!handleError(value)) {
        setError(value)
      }
    } finally {
      setBusy(null)
    }
  }

  const save = (setting: SystemSetting, value: number) => void run(
    setting.key,
    () => apiClient.updateSystemSetting(setting.key, value),
    `«${setting.title}»: ${settingValueText(setting.unit, value)}. Изменение записано в журнал и уже действует.`
  )

  const reset = () => {
    const setting = resetting
    setResetting(null)
    if (setting !== null) {
      void run(setting.key, () => apiClient.resetSystemSetting(setting.key),
        `«${setting.title}»: снова действует значение конфигурации ${settingValueText(setting.unit, setting.defaultValue)}.`)
    }
  }

  return (
    <section className="admin-page" aria-labelledby="settings-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="settings-title">Настройки</h2>
        <HintButton label="О разделе">
          Значения по умолчанию задаёт конфигурация сервера. Здесь их меняет администратор: новое значение действует сразу,
          без перезапуска, а каждое изменение и возврат к конфигурации записываются в журнал «было → стало». Модули
          по-прежнему включает администратор сервера — их состояние показано в разделе «Состояние модулей».
        </HintButton>
      </div>
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем настройки…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить настройки.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {error !== null && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>{error instanceof ApiError ? error.message : 'Не удалось сохранить настройку. Повторите попытку позже.'}</p>
          <SupportDetails requestId={requestIdOf(error)} />
        </div>
      )}
      {notice !== null && <p className="organizations-message" role="status">{notice}</p>}
      {state.kind === 'ready' && settingGroups.map(({ group, title, hint }) => (
        <section key={group} className="admin-settings-group" aria-labelledby={`settings-${group}`}>
          <h3 id={`settings-${group}`}>{title}</h3>
          <p className="admin-form__hint">{hint}</p>
          <div className="admin-table-wrap">
            <table className="admin-table" aria-label={title}>
              <thead>
                <tr>
                  <th scope="col">Настройка</th>
                  <th scope="col">Значение</th>
                  <th scope="col">По конфигурации</th>
                  <th scope="col">Изменено</th>
                  <th scope="col"><span className="admin-visually-hidden">Действия</span></th>
                </tr>
              </thead>
              <tbody>
                {state.settings.filter((setting) => setting.group === group).map((setting) => {
                  const draft = drafts[setting.key] ?? ''
                  const problem = savedOnChange(setting.unit) ? null : settingDraftProblem(setting, draft)
                  const changed = draft !== String(setting.value)
                  return (
                    <tr key={setting.key}>
                      <td className="admin-table__cell-main"><span className="admin-table__main">{setting.title}</span></td>
                      <td data-label="Значение">
                        {setting.unit === 'LANDING' ? (
                          <label>
                            <span className="admin-visually-hidden">{setting.title}</span>
                            <select
                              value={setting.value}
                              disabled={busy !== null}
                              onChange={(event) => save(setting, Number(event.target.value))}
                            >
                              {landingOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                            </select>
                          </label>
                        ) : setting.unit === 'FLAG' ? (
                          <label className="admin-form__check">
                            <input
                              type="checkbox"
                              checked={setting.value !== 0}
                              disabled={busy !== null}
                              onChange={(event) => save(setting, event.target.checked ? 1 : 0)}
                            />
                            {setting.value === 0 ? 'Выключено' : 'Включено'}
                          </label>
                        ) : (
                          <label>
                            <span className="admin-visually-hidden">{setting.title}, {unitLabel(setting.unit)}</span>
                            <input
                              type="number"
                              inputMode="numeric"
                              min={setting.min}
                              max={setting.max}
                              value={draft}
                              aria-invalid={problem !== null}
                              disabled={busy !== null}
                              onChange={(event) => setDrafts({ ...drafts, [setting.key]: event.target.value })}
                            />
                            {' '}{unitLabel(setting.unit)}
                            {problem !== null && <span className="admin-form__hint">{problem}</span>}
                          </label>
                        )}
                      </td>
                      <td data-label="По конфигурации">{settingValueText(setting.unit, setting.defaultValue)}</td>
                      <td data-label="Изменено">
                        {setting.saved ? `${formatDateTime(setting.updatedAt ?? '')}, ${setting.updatedByName ?? ''}` : 'нет'}
                      </td>
                      <td>
                        <div className="admin-form__actions">
                          {!savedOnChange(setting.unit) && (
                            <button type="button" disabled={busy !== null || !changed || problem !== null}
                              onClick={() => save(setting, Number(draft))}>
                              {busy === setting.key ? 'Сохраняем…' : 'Сохранить'}
                            </button>
                          )}
                          <button type="button" className="button--secondary" disabled={busy !== null || !setting.saved}
                            onClick={() => setResetting(setting)}>
                            По конфигурации
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          {group === 'SITE' && (
            <p className="admin-form__hint">
              <a href={productDemoHref} target="_blank" rel="noopener">Открыть продуктовую главную (демонстрация)</a>
            </p>
          )}
          {group === 'SIGNALS' && (
            <p className="admin-form__hint">
              Порог покрытия и малая база индекса востребованности задаются в разделе{' '}
              <a href="#/admin/demand-index">«Индекс востребованности»</a>, нормативы этапов — в{' '}
              <a href="#/admin/workflow-templates">«Шаблонах этапов»</a>.
            </p>
          )}
        </section>
      ))}
      <ConfirmDialog
        open={resetting !== null}
        title="Вернуть значение конфигурации?"
        description={resetting === null ? '' : `«${resetting.title}» снова будет ${settingValueText(resetting.unit, resetting.defaultValue)}.`}
        confirmLabel="Вернуть"
        onConfirm={reset}
        onCancel={() => setResetting(null)}
      />
    </section>
  )
}
