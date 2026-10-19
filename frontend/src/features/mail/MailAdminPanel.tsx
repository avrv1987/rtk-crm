import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { ApiError, apiClient, type MailJournalPage, type MailJournalRow, type MailLetter, type MailSettings } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { Pagination } from '../../shared/ui/Pagination'
import { handledSessionError, type SessionHandlers } from '../admin/adminShared'
import { defaultPort, draftOf, passwordStateText, rowStatusText, securityText, statusText, updateOf, type MailDraft } from './mailAdmin'
import './mail.css'

const PAGE_SIZE = 20
const errorMessage = (error: unknown) => (error instanceof ApiError ? error.message : 'Не удалось выполнить действие. Повторите позже.')
const dateTime = (value: string | null | undefined) => (value ? formatMoscowDateTime(value) : '—')

type Props = SessionHandlers & { enabled: boolean }

// «Администрирование → Почта»: подключение почтового сервиса, проверка, тестовое письмо, журнал отправки с просмотром
// письма, очередь с повтором и отменой. При выключенном модуле — только пояснение, как его включить.
export const MailAdminPanel = ({ enabled, ...handlers }: Props) => {
  if (!enabled) {
    return (
      <section className="mail-admin" aria-labelledby="mail-admin-title">
        <h2 id="mail-admin-title">Почта</h2>
        <p role="status">Модуль выключен: письма не собираются и не отправляются.</p>
        <p className="mail-admin__muted">Чтобы включить, администратор сервера задаёт MAIL_ENABLED=true и перезапускает CRM.</p>
      </section>
    )
  }
  return <MailAdminContent {...handlers} />
}

const MailAdminContent = (handlers: SessionHandlers) => {
  const [settings, setSettings] = useState<MailSettings | null>(null)
  const [draft, setDraft] = useState<MailDraft | null>(null)
  const [journal, setJournal] = useState<MailJournalPage | null>(null)
  const [status, setStatus] = useState<MailJournalRow['status'] | ''>('')
  const [page, setPage] = useState(0)
  const [letter, setLetter] = useState<MailLetter | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const fail = useCallback((failure: unknown) => {
    if (!handledSessionError(failure, handlers)) {
      setError(errorMessage(failure))
    }
  }, [handlers.onSessionExpired, handlers.onProfileUnavailable])

  const loadSettings = useCallback(async () => {
    try {
      const loaded = await apiClient.getMailSettings()
      setSettings(loaded)
      setDraft(draftOf(loaded))
    } catch (failure) {
      fail(failure)
    }
  }, [fail])

  const loadJournal = useCallback(async () => {
    try {
      setJournal(await apiClient.listMailOutbox(status, page, PAGE_SIZE))
    } catch (failure) {
      fail(failure)
    }
  }, [fail, status, page])

  useEffect(() => {
    void loadSettings()
  }, [loadSettings])

  useEffect(() => {
    void loadJournal()
  }, [loadJournal])

  const run = async (action: () => Promise<string>) => {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      setNotice(await action())
      await Promise.all([loadSettings(), loadJournal()])
    } catch (failure) {
      fail(failure)
    } finally {
      setBusy(false)
    }
  }

  if (settings === null || draft === null) {
    return error === null ? <p role="status">Загружаем настройки почты…</p> : <p role="alert">{error}</p>
  }

  const smtp = draft.mode === 'SMTP'
  const set = (patch: Partial<MailDraft>) => setDraft({ ...draft, ...patch })
  // SMTP сохраняется без проверки подключения (проверка — отдельной кнопкой): при опечатке все письма, в том числе
  // приглашения, уйдут в ошибку. Предупреждаем до сохранения (Н-6).
  const smtpUnchecked = smtp && settings?.mode !== 'SMTP'

  return (
    <section className="mail-admin" aria-labelledby="mail-admin-title">
      <h2 id="mail-admin-title">Почта</h2>
      <dl className="mail-admin__facts">
        <dt>Режим</dt>
        <dd>{settings.mode === 'STUB' ? 'заглушка: письма собираются и сохраняются в журнал, наружу не уходят' : `SMTP ${settings.host ?? ''}:${settings.port ?? ''}`}</dd>
        <dt>Проверка</dt>
        <dd>{settings.checkedAt ? `${settings.checkOk ? 'успешно' : 'ошибка'} ${dateTime(settings.checkedAt)}${settings.checkMessage ? ` — ${settings.checkMessage}` : ''}` : 'не проверялось'}</dd>
        <dt>Очередь</dt>
        <dd>
          ждут {settings.queue.queued}, с ошибкой {settings.queue.errors}; за сутки отправлено {settings.queue.sent},
          не отправлено {settings.queue.dropped}, отменено {settings.queue.cancelled}
        </dd>
      </dl>

      <form
        className="mail-admin__form"
        aria-label="Подключение почтового сервиса"
        onSubmit={(event) => {
          event.preventDefault()
          void run(async () => {
            await apiClient.updateMailSettings(updateOf(draft))
            return 'Настройки сохранены. Проверьте подключение.'
          })
        }}
      >
        <fieldset className="mail-admin__mode" disabled={busy}>
          <legend>Режим</legend>
          <label>
            <input type="radio" name="mail-mode" checked={!smtp} onChange={() => set({ mode: 'STUB' })} /> Заглушка
          </label>
          <label>
            <input type="radio" name="mail-mode" checked={smtp} onChange={() => set({ mode: 'SMTP' })} /> SMTP-сервер
          </label>
        </fieldset>
        <div className="mail-admin__grid">
          <label>
            Сервер
            <input value={draft.host} maxLength={255} placeholder="smtp.example.ru" required={smtp} onChange={(event) => set({ host: event.target.value })} />
          </label>
          <label>
            Шифрование
            <select
              value={draft.security}
              onChange={(event) => {
                const security = event.target.value as MailSettings['security']
                set({ security, port: draft.port === '' || draft.port === String(defaultPort(draft.security)) ? String(defaultPort(security)) : draft.port })
              }}
            >
              {(Object.keys(securityText) as MailSettings['security'][]).map((key) => (
                <option key={key} value={key}>{securityText[key]}</option>
              ))}
            </select>
          </label>
          <label>
            Порт
            <input inputMode="numeric" value={draft.port} placeholder={String(defaultPort(draft.security))} required={smtp} onChange={(event) => set({ port: event.target.value.replace(/\D/g, '') })} />
          </label>
          <label>
            Логин
            <input value={draft.username} maxLength={255} autoComplete="off" onChange={(event) => set({ username: event.target.value })} />
          </label>
          <label>
            Пароль <span className="mail-admin__muted">({passwordStateText[settings.passwordState]})</span>
            <input
              type="password"
              autoComplete="new-password"
              value={draft.password}
              maxLength={500}
              disabled={!settings.encryptionAvailable || draft.clearPassword}
              placeholder={settings.passwordState === 'SET' ? 'оставить прежний' : ''}
              onChange={(event) => set({ password: event.target.value })}
            />
          </label>
          <label>
            Адрес отправителя
            <input type="email" value={draft.fromAddress} maxLength={254} required onChange={(event) => set({ fromAddress: event.target.value })} />
          </label>
          <label>
            Имя отправителя
            <input value={draft.fromName} maxLength={200} required onChange={(event) => set({ fromName: event.target.value })} />
          </label>
        </div>
        {settings.passwordState !== 'NONE' && (
          <label className="mail-admin__inline">
            <input type="checkbox" checked={draft.clearPassword} onChange={(event) => set({ clearPassword: event.target.checked, password: '' })} />
            Удалить сохранённый пароль
          </label>
        )}
        {!settings.encryptionAvailable && (
          <p role="alert">Ключ шифрования SOURCES_SETTINGS_KEY не задан: пароль SMTP сохранить нельзя.</p>
        )}
        {smtpUnchecked && (
          <p className="notice" role="note">
            Подключение к SMTP проверяется отдельно: после сохранения сразу нажмите «Проверить подключение». При ошибке в настройках
            все письма, в том числе приглашения сотрудникам, уйдут в «ошибку».
          </p>
        )}
        <div className="mail-admin__actions">
          <button type="submit" disabled={busy}>Сохранить</button>
          <button type="button" className="button--secondary" disabled={busy} onClick={() => void run(async () => (await apiClient.checkMail()).message)}>
            Проверить подключение
          </button>
          <button type="button" className="button--secondary" disabled={busy} onClick={() => void run(async () => {
            const row = await apiClient.sendMailTest()
            return `Тестовое письмо: ${rowStatusText(row)}${row.recipient ? `, ${row.recipient}` : ''}${row.error ? ` — ${row.error}` : ''}`
          })}>
            Отправить тестовое письмо себе
          </button>
        </div>
      </form>
      {notice !== null && <p role="status">{notice}</p>}
      {error !== null && <p role="alert">{error}</p>}

      <div className="mail-admin__journal-head">
        <h3>Журнал отправки</h3>
        <label>
          Статус{' '}
          <select value={status} onChange={(event) => { setStatus(event.target.value as MailJournalRow['status'] | ''); setPage(0) }}>
            <option value="">все</option>
            {(Object.keys(statusText) as MailJournalRow['status'][]).map((key) => <option key={key} value={key}>{statusText[key]}</option>)}
          </select>
        </label>
        <button type="button" className="button--secondary" disabled={busy || settings.queue.errors === 0} onClick={() => void run(async () => (
          `Возвращено в очередь: ${(await apiClient.requeueMail()).requeued}`
        ))}>
          Вернуть ошибки в очередь
        </button>
      </div>
      {journal === null ? <p role="status">Загружаем журнал…</p> : journal.items.length === 0 ? <p>Писем пока нет.</p> : (
        <div className="mail-admin__table">
          <table>
            <thead>
              <tr><th>Время, МСК</th><th>Кому</th><th>Письмо</th><th>Статус</th><th>Ошибка</th><th aria-label="Действия" /></tr>
            </thead>
            <tbody>
              {journal.items.map((row) => (
                <tr key={row.id}>
                  <td data-label="Время">{dateTime(row.sentAt ?? row.createdAt)}</td>
                  <td data-label="Кому">{row.recipient ?? (row.employee ? 'сотрудник (адрес при отправке)' : '—')}</td>
                  <td data-label="Письмо">{row.kindTitle}</td>
                  <td data-label="Статус">{rowStatusText(row)}{row.attempts > 1 ? `, попыток ${row.attempts}` : ''}</td>
                  <td data-label={row.error ? 'Ошибка' : undefined}>{row.error ?? ''}</td>
                  <td className="mail-admin__row-actions">
                    <button type="button" className="button--secondary" disabled={busy} onClick={() => void (async () => {
                      try {
                        setLetter(await apiClient.getMailLetter(row.id))
                      } catch (failure) {
                        fail(failure)
                      }
                    })()}>
                      Открыть
                    </button>
                    {row.status === 'ERROR' && (
                      <button type="button" className="button--secondary" disabled={busy} onClick={() => void run(async () => {
                        await apiClient.retryMailLetter(row.id)
                        return 'Письмо возвращено в очередь.'
                      })}>
                        Повторить
                      </button>
                    )}
                    {(row.status === 'QUEUED' || row.status === 'ERROR') && (
                      <button type="button" className="button--secondary" disabled={busy} onClick={() => void run(async () => {
                        await apiClient.cancelMailLetter(row.id)
                        return 'Письмо отменено.'
                      })}>
                        Отменить
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {journal !== null && <Pagination label="Страницы журнала писем" page={page} size={PAGE_SIZE} total={journal.total} disabled={busy} onChange={setPage} />}
      <LetterDialog letter={letter} onClose={() => setLetter(null)} />
    </section>
  )
}

// Письмо показывается в iframe с пустым sandbox: ни скриптов, ни форм, ни переходов внутри окна CRM.
const LetterDialog = ({ letter, onClose }: { letter: MailLetter | null; onClose: () => void }) => {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const [view, setView] = useState<'html' | 'text'>('html')

  useEffect(() => {
    const dialog = dialogRef.current
    if (dialog === null) {
      return
    }
    if (letter !== null && !dialog.open) {
      setView(letter.html ? 'html' : 'text')
      dialog.showModal()
    }
    if (letter === null && dialog.open) {
      dialog.close()
    }
  }, [letter])

  return (
    <dialog
      ref={dialogRef}
      className="confirm-dialog mail-letter"
      aria-modal="true"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
    >
      {letter !== null && (
        <>
          <h2 id={titleId}>{letter.subject}</h2>
          <dl className="mail-admin__facts">
            <dt>Вид</dt>
            <dd>{letter.kindTitle}</dd>
            <dt>От</dt>
            <dd>{letter.from ?? '—'}</dd>
            <dt>Кому</dt>
            <dd>{letter.recipient ?? '—'}</dd>
            <dt>Статус</dt>
            <dd>{rowStatusText(letter)}{letter.sentAt ? `, ${dateTime(letter.sentAt)}` : ''}</dd>
            {letter.attachments.length > 0 && (
              <>
                <dt>Вложения</dt>
                <dd>{letter.attachments.join(', ')}</dd>
              </>
            )}
            {letter.error && (
              <>
                <dt>Причина</dt>
                <dd>{letter.error}</dd>
              </>
            )}
          </dl>
          {letter.html && letter.text && (
            <div className="mail-letter__switch" role="group" aria-label="Вид письма">
              <button type="button" className={view === 'html' ? undefined : 'button--secondary'} onClick={() => setView('html')}>Оформленное</button>
              <button type="button" className={view === 'text' ? undefined : 'button--secondary'} onClick={() => setView('text')}>Текст</button>
            </div>
          )}
          {view === 'html' && letter.html
            ? <iframe className="mail-letter__frame" title="Письмо" sandbox="" srcDoc={letter.html} />
            : <pre className="mail-letter__text">{letter.text}</pre>}
        </>
      )}
      <div className="confirm-dialog__actions">
        <button type="button" className="button--secondary" onClick={onClose}>Закрыть</button>
      </div>
    </dialog>
  )
}
