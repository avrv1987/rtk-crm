import { useCallback, useEffect, useState } from 'react'
import {
  apiClient,
  type SourceCode,
  type SourceConnectionCheck,
  type SourceSettings,
  type SourceSettingsUpdate
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { accessHandled, errorText, fieldErrors, formatDateTime, requestIdOf, scheduleLabel } from './sourceFormat'
import './sources.css'

type SourceConnectionSettingsProps = {
  onChanged: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Draft = {
  moodleBaseUrl: string
  moodleToken: string
  moodleCourseIds: string
  moodleStudentRoles: string
  moodleTeacherRoles: string
  websiteBaseUrl: string
  websiteToken: string
  syncCron: string
}

type Command =
  | { kind: 'idle' }
  | { kind: 'running'; target: 'save' | 'reset' | SourceCode }
  | { kind: 'saved'; message: string }
  | { kind: 'failed'; error: unknown }

const draftOf = (settings: SourceSettings): Draft => ({
  moodleBaseUrl: settings.moodleBaseUrl ?? '',
  moodleToken: '',
  moodleCourseIds: settings.moodleCourseIds.join(', '),
  moodleStudentRoles: settings.moodleStudentRoles.join(', '),
  moodleTeacherRoles: settings.moodleTeacherRoles.join(', '),
  websiteBaseUrl: settings.websiteBaseUrl ?? '',
  websiteToken: '',
  syncCron: settings.syncCron ?? ''
})

const list = (value: string) => value.split(/[\s,;]+/).filter((item) => item !== '')

const tokenText = (token: SourceSettings['moodleToken']) => {
  switch (token.origin) {
    case 'SCREEN':
      return `задан на экране, изменён ${formatDateTime(token.changedAt)}`
    case 'ENVIRONMENT':
      return 'задан в конфигурации развёртывания'
    case 'UNREADABLE':
      return `сохранён ${formatDateTime(token.changedAt)}, но не расшифровывается текущим ключом — задайте токен заново`
    default:
      return 'не задан'
  }
}

const validate = (draft: Draft, settings: SourceSettings): Record<string, string> => {
  const errors: Record<string, string> = {}
  const url = (value: string, initial: string | null | undefined) => (
    value.trim() === '' || value.trim() === (initial ?? '') || /^https:\/\/[^\s/?#@]+[^\s?#]*$/i.test(value.trim())
  )
  if (!url(draft.moodleBaseUrl, settings.moodleBaseUrl)) {
    errors.moodleBaseUrl = 'Адрес Moodle должен начинаться с https:// и не содержать логина, параметров и якоря.'
  }
  if (!url(draft.websiteBaseUrl, settings.websiteBaseUrl)) {
    errors.websiteBaseUrl = 'Адрес сайта должен начинаться с https:// и не содержать логина, параметров и якоря.'
  }
  if (list(draft.moodleCourseIds).some((item) => !/^[1-9]\d{0,17}$/.test(item))) {
    errors.moodleCourseIds = 'Номера курсов — целые положительные числа через запятую.'
  }
  if (list(draft.moodleStudentRoles).length === 0) {
    errors.moodleStudentRoles = 'Укажите хотя бы одну роль обучающихся.'
  }
  if (/\s/.test(draft.moodleToken.trim()) || /\s/.test(draft.websiteToken.trim())) {
    errors.token = 'Токен не должен содержать пробелов.'
  }
  if (draft.syncCron.trim() !== '' && draft.syncCron.trim().split(/\s+/).length !== 6) {
    errors.syncCron = 'Расписание — шесть полей: секунды, минуты, часы, день, месяц, день недели.'
  }
  return errors
}

const payloadOf = (draft: Draft): SourceSettingsUpdate => ({
  moodleBaseUrl: draft.moodleBaseUrl.trim() === '' ? null : draft.moodleBaseUrl.trim(),
  moodleToken: draft.moodleToken.trim() === '' ? null : draft.moodleToken.trim(),
  moodleCourseIds: list(draft.moodleCourseIds).map(Number),
  moodleStudentRoles: list(draft.moodleStudentRoles),
  moodleTeacherRoles: list(draft.moodleTeacherRoles),
  websiteBaseUrl: draft.websiteBaseUrl.trim() === '' ? null : draft.websiteBaseUrl.trim(),
  websiteToken: draft.websiteToken.trim() === '' ? null : draft.websiteToken.trim(),
  syncCron: draft.syncCron.trim() === '' ? null : draft.syncCron.trim()
})

export const SourceConnectionSettings = ({ onChanged, onSessionExpired, onProfileUnavailable }: SourceConnectionSettingsProps) => {
  const [settings, setSettings] = useState<SourceSettings | null>(null)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [command, setCommand] = useState<Command>({ kind: 'idle' })
  const [checks, setChecks] = useState<Partial<Record<SourceCode, SourceConnectionCheck>>>({})
  const [confirmReset, setConfirmReset] = useState(false)

  const load = useCallback(async () => {
    try {
      const loaded = await apiClient.getSourceSettings()
      setSettings(loaded)
      setDraft(draftOf(loaded))
      setLoadError(null)
    } catch (error) {
      if (!accessHandled(error, onSessionExpired, onProfileUnavailable)) {
        setLoadError(error)
      }
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load()
  }, [load])

  if (loadError !== null) {
    return (
      <section className="data-sources__records" aria-labelledby="source-connection-title">
        <h3 id="source-connection-title">Подключение</h3>
        <p className="source-error" role="alert">{errorText(loadError, 'Не удалось загрузить настройки подключения.')}</p>
        <SupportDetails requestId={requestIdOf(loadError)} />
        <button type="button" onClick={() => void load()}>Повторить</button>
      </section>
    )
  }
  if (settings === null || draft === null) {
    return <p role="status">Загружаем настройки подключения…</p>
  }

  const errors = validate(draft, settings)
  const invalid = Object.keys(errors).length > 0
  const busy = command.kind === 'running'
  const update = (patch: Partial<Draft>) => {
    setDraft({ ...draft, ...patch })
    setChecks({})
  }

  const save = async () => {
    setCommand({ kind: 'running', target: 'save' })
    try {
      const saved = await apiClient.updateSourceSettings(payloadOf(draft))
      setSettings(saved)
      setDraft(draftOf(saved))
      setCommand({ kind: 'saved', message: 'Настройки сохранены и уже действуют.' })
      onChanged()
    } catch (error) {
      if (!accessHandled(error, onSessionExpired, onProfileUnavailable)) {
        setCommand({ kind: 'failed', error })
      }
    }
  }

  const reset = async () => {
    setConfirmReset(false)
    setCommand({ kind: 'running', target: 'reset' })
    try {
      await apiClient.resetSourceSettings()
      await load()
      setChecks({})
      setCommand({ kind: 'saved', message: 'Снова действуют значения из конфигурации развёртывания.' })
      onChanged()
    } catch (error) {
      if (!accessHandled(error, onSessionExpired, onProfileUnavailable)) {
        setCommand({ kind: 'failed', error })
      }
    }
  }

  const check = async (source: SourceCode) => {
    setCommand({ kind: 'running', target: source })
    try {
      const result = await apiClient.checkSourceConnection(source, payloadOf(draft))
      setChecks((current) => ({ ...current, [source]: result }))
      setCommand({ kind: 'idle' })
    } catch (error) {
      if (!accessHandled(error, onSessionExpired, onProfileUnavailable)) {
        setCommand({ kind: 'failed', error })
      }
    }
  }

  const checkResult = (source: SourceCode) => {
    const result = checks[source]
    if (!result) {
      return null
    }
    return (
      <div className="source-form__wide" role="status">
        <p className={result.ok ? undefined : 'source-error'}>{result.ok ? '' : 'Проверка не прошла: '}{result.message}.</p>
        {result.details.length > 0 && <p>Курсы: {result.details.join('; ')}</p>}
      </div>
    )
  }

  return (
    <section className="data-sources__records" aria-labelledby="source-connection-title">
      <h3 id="source-connection-title">Подключение</h3>
      <p className="data-sources__hint">
        {settings.saved
          ? `Действуют значения, сохранённые на экране ${formatDateTime(settings.updatedAt)}${settings.updatedByName ? ` (${settings.updatedByName})` : ''}.`
          : 'Действуют начальные значения из конфигурации развёртывания (.env.local).'}
        {' '}Изменения применяются без перезапуска сервера. Токены хранятся зашифрованными и не показываются:
        оставьте поле токена пустым, чтобы не менять его.
      </p>
      {!settings.encryptionAvailable && (
        <p className="source-error">
          Ключ шифрования SOURCES_SETTINGS_KEY не задан в конфигурации развёртывания: токены на экране сохранить нельзя,
          действуют токены из конфигурации развёртывания. Адреса, курсы и расписание сохранить можно.
        </p>
      )}

      <form className="source-form" noValidate onSubmit={(event) => { event.preventDefault(); void save() }}>
        <h4 className="source-form__wide">LMS Moodle</h4>
        <label className="source-form__wide">
          Адрес Moodle (совпадает с wwwroot)
          <input
            type="url"
            inputMode="url"
            placeholder="https://lms.example.ru"
            value={draft.moodleBaseUrl}
            aria-invalid={errors.moodleBaseUrl ? true : undefined}
            onChange={(event) => update({ moodleBaseUrl: event.target.value })}
          />
          {errors.moodleBaseUrl && <span className="source-error">{errors.moodleBaseUrl}</span>}
        </label>
        <label className="source-form__wide">
          Токен внешнего сервиса Moodle — {tokenText(settings.moodleToken)}
          <input
            type="password"
            autoComplete="new-password"
            placeholder={settings.moodleToken.origin === 'NONE' ? 'Вставьте токен' : 'Новый токен'}
            value={draft.moodleToken}
            onChange={(event) => update({ moodleToken: event.target.value })}
          />
        </label>
        <label className="source-form__wide">
          Номера курсов Moodle через запятую
          <input
            inputMode="numeric"
            placeholder="2, 3"
            value={draft.moodleCourseIds}
            aria-invalid={errors.moodleCourseIds ? true : undefined}
            onChange={(event) => update({ moodleCourseIds: event.target.value })}
          />
          {errors.moodleCourseIds && <span className="source-error">{errors.moodleCourseIds}</span>}
        </label>
        <label>
          Роли обучающихся (краткие имена)
          <input
            value={draft.moodleStudentRoles}
            aria-invalid={errors.moodleStudentRoles ? true : undefined}
            onChange={(event) => update({ moodleStudentRoles: event.target.value })}
          />
          {errors.moodleStudentRoles && <span className="source-error">{errors.moodleStudentRoles}</span>}
        </label>
        <label>
          Роли преподавателей (краткие имена)
          <input value={draft.moodleTeacherRoles} onChange={(event) => update({ moodleTeacherRoles: event.target.value })} />
        </label>
        <div className="source-form__actions source-form__wide">
          <button type="button" className="button--secondary" disabled={busy || invalid} onClick={() => void check('MOODLE')}>
            {command.kind === 'running' && command.target === 'MOODLE' ? 'Проверяем…' : 'Проверить связь с Moodle'}
          </button>
        </div>
        {checkResult('MOODLE')}

        <h4 className="source-form__wide">Сайт ИТ Школы</h4>
        <label className="source-form__wide">
          Адрес API сайта
          <input
            type="url"
            inputMode="url"
            placeholder="https://site.example.ru"
            value={draft.websiteBaseUrl}
            aria-invalid={errors.websiteBaseUrl ? true : undefined}
            onChange={(event) => update({ websiteBaseUrl: event.target.value })}
          />
          {errors.websiteBaseUrl && <span className="source-error">{errors.websiteBaseUrl}</span>}
        </label>
        <label className="source-form__wide">
          Токен сайта — {tokenText(settings.websiteToken)}
          <input
            type="password"
            autoComplete="new-password"
            placeholder={settings.websiteToken.origin === 'NONE' ? 'Вставьте токен' : 'Новый токен'}
            value={draft.websiteToken}
            onChange={(event) => update({ websiteToken: event.target.value })}
          />
        </label>
        {errors.token && <p className="source-error source-form__wide">{errors.token}</p>}
        <div className="source-form__actions source-form__wide">
          <button type="button" className="button--secondary" disabled={busy || invalid} onClick={() => void check('WEBSITE')}>
            {command.kind === 'running' && command.target === 'WEBSITE' ? 'Проверяем…' : 'Проверить связь с сайтом'}
          </button>
        </div>
        {checkResult('WEBSITE')}

        <h4 className="source-form__wide">Плановая синхронизация</h4>
        <label className="source-form__wide">
          Расписание cron по Москве (МСК): секунды, минуты, часы, день, месяц, день недели
          <input
            placeholder="0 0 * * * *"
            value={draft.syncCron}
            aria-invalid={errors.syncCron ? true : undefined}
            onChange={(event) => update({ syncCron: event.target.value })}
          />
          <span>
            {errors.syncCron ?? (draft.syncCron.trim() === ''
              ? 'Пусто — плановая синхронизация выключена.'
              : `Сейчас: ${scheduleLabel(draft.syncCron.trim())} (МСК). Не чаще раза в 5 минут. Примеры: 0 0 * * * * — каждый час, 0 30 6 * * * — ежедневно в 06:30.`)}
          </span>
        </label>

        <div className="source-form__actions source-form__wide">
          <button type="submit" disabled={busy || invalid}>
            {command.kind === 'running' && command.target === 'save' ? 'Сохраняем…' : 'Сохранить'}
          </button>
          {settings.saved && (
            <button type="button" className="button--secondary" disabled={busy} onClick={() => setConfirmReset(true)}>
              Вернуть значения конфигурации развёртывания
            </button>
          )}
        </div>
        {command.kind === 'saved' && <p className="source-form__wide" role="status">{command.message}</p>}
        {command.kind === 'failed' && (
          <div className="source-form__wide" role="alert">
            <p className="source-error">{errorText(command.error, 'Действие не выполнено.')}</p>
            {fieldErrors(command.error).map((text) => <p key={text} className="source-error">{text}</p>)}
            <SupportDetails requestId={requestIdOf(command.error)} />
          </div>
        )}
      </form>

      <ConfirmDialog
        open={confirmReset}
        title="Вернуть значения конфигурации развёртывания?"
        description="Сохранённые на экране адреса, курсы, роли, расписание и токены будут удалены; снова начнут действовать значения из .env.local."
        confirmLabel="Вернуть"
        onConfirm={() => void reset()}
        onCancel={() => setConfirmReset(false)}
      />
    </section>
  )
}
