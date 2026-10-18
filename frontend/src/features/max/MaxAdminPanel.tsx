import { useCallback, useEffect, useState } from 'react'
import { ApiError, apiClient, type MaxAdminLink, type MaxSettings } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { handledSessionError, roleLabels, type SessionHandlers } from '../admin/adminShared'
import { subscriptionText, tokenStateText } from './maxAdmin'
import './max.css'

const errorMessage = (error: unknown) => (error instanceof ApiError ? error.message : 'Не удалось выполнить действие. Повторите позже.')
const dateTime = (value: string | null | undefined) => (value ? formatMoscowDateTime(value) : 'нет')

// Карточка «Бот MAX» в «Источниках данных»: токен, проверка связи, подписка вебхука, очередь и подключения
// сотрудников. Видна только при включённом модуле.
export const MaxAdminPanel = (handlers: SessionHandlers) => {
  const [settings, setSettings] = useState<MaxSettings | null>(null)
  const [links, setLinks] = useState<MaxAdminLink[]>([])
  const [token, setToken] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      const [loadedSettings, loadedLinks] = await Promise.all([apiClient.getMaxSettings(), apiClient.listMaxLinks()])
      setSettings(loadedSettings)
      setLinks(loadedLinks)
    } catch (loadError) {
      if (!handledSessionError(loadError, handlers)) {
        setError(errorMessage(loadError))
      }
    }
  }, [handlers.onSessionExpired, handlers.onProfileUnavailable])

  useEffect(() => {
    void load()
  }, [load])

  const run = async (action: () => Promise<string>) => {
    setBusy(true)
    setError(null)
    try {
      setNotice(await action())
      await load()
    } catch (actionError) {
      if (!handledSessionError(actionError, handlers)) {
        setError(errorMessage(actionError))
      }
    } finally {
      setBusy(false)
    }
  }

  if (settings === null) {
    return error === null ? <p role="status">Загружаем настройки бота MAX…</p> : <p role="alert">{error}</p>
  }

  return (
    <section className="max-admin" aria-labelledby="max-admin-title">
      <h2 id="max-admin-title">Бот MAX</h2>
      <dl className="max-admin__facts">
        <dt>Токен бота</dt>
        <dd>{tokenStateText[settings.tokenState]}</dd>
        <dt>Имя бота</dt>
        <dd>{settings.botName ?? 'не проверено'}</dd>
        <dt>Адрес вебхука</dt>
        <dd>{settings.webhookUrl ?? 'не задан публичный адрес CRM'}{settings.webhookUrl && !settings.webhookUrlValid ? ' — нужен HTTPS' : ''}</dd>
        <dt>Подписка</dt>
        <dd>{subscriptionText(settings)}</dd>
        <dt>Последнее событие</dt>
        <dd>{dateTime(settings.lastEventAt)}</dd>
        <dt>Очередь сообщений</dt>
        <dd>
          ждут {settings.queue.queued}, ошибки {settings.queue.errors}; за сутки отправлено {settings.queue.sent},
          не отправлено {settings.queue.dropped}; входящих в разборе {settings.queue.inboxWaiting}, с ошибкой {settings.queue.inboxErrors}
        </dd>
      </dl>
      {!settings.encryptionAvailable && (
        <p role="alert">Ключ шифрования SOURCES_SETTINGS_KEY не задан: токен бота сохранить нельзя.</p>
      )}
      <form
        className="max-admin__actions"
        onSubmit={(event) => {
          event.preventDefault()
          void run(async () => {
            await apiClient.updateMaxSettings({ token })
            setToken('')
            return 'Токен сохранён. Проверьте связь и подпишите вебхук.'
          })
        }}
      >
        <label>
          Новый токен бота{' '}
          <input type="password" autoComplete="new-password" value={token} onChange={(event) => setToken(event.target.value)} />
        </label>
        <button type="submit" disabled={busy || token.trim() === '' || !settings.encryptionAvailable}>Сохранить токен</button>
      </form>
      <label>
        <input
          type="checkbox"
          checked={settings.miniAppEnabled}
          disabled={busy}
          onChange={() => void run(async () => {
            await apiClient.updateMaxSettings({ miniAppEnabled: !settings.miniAppEnabled })
            return 'Сохранено.'
          })}
        />
        {' '}Мини-приложение подключено к боту на платформе MAX (кнопки сообщений открывают его)
      </label>
      <div className="max-admin__actions">
        <button type="button" disabled={busy || settings.tokenState !== 'SCREEN'} onClick={() => void run(async () => (await apiClient.checkMax()).message)}>
          Проверить связь
        </button>
        <button type="button" disabled={busy || settings.botName === null || !settings.webhookUrlValid} onClick={() => void run(async () => {
          await apiClient.subscribeMax()
          return 'Вебхук подписан.'
        })}>
          Подписать вебхук
        </button>
        <button type="button" className="button--secondary" disabled={busy || settings.queue.errors === 0} onClick={() => void run(async () => (
          `Возвращено в очередь: ${(await apiClient.requeueMax()).requeued}`
        ))}>
          Вернуть ошибки в очередь
        </button>
      </div>
      {notice !== null && <p role="status">{notice}</p>}
      {error !== null && <p role="alert">{error}</p>}
      <h3>Подключения сотрудников</h3>
      {links.length === 0 ? <p>Пока никто не подключил MAX.</p> : (
        <table>
          <thead>
            <tr><th>Сотрудник</th><th>Роль</th><th>Аккаунт MAX</th><th>Состояние</th><th aria-label="Действия" /></tr>
          </thead>
          <tbody>
            {links.map((link) => (
              <tr key={link.profileId}>
                <td>{link.displayName}</td>
                <td>{roleLabels[link.role as keyof typeof roleLabels] ?? link.role}</td>
                <td>{link.maxName ?? '—'}</td>
                <td>{link.status === 'ACTIVE' ? `подключён ${dateTime(link.confirmedAt)}` : `бот остановлен ${dateTime(link.statusChangedAt)}`}</td>
                <td>
                  <button type="button" className="button--secondary" disabled={busy} onClick={() => void run(async () => {
                    await apiClient.deleteMaxLinkByAdmin(link.profileId)
                    return `MAX отключён: ${link.displayName}.`
                  })}>
                    Отключить
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
