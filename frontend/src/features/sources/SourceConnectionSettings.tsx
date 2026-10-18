import { useCallback, useEffect, useState } from 'react'
import {
  apiClient,
  type SourceConnectionCheck,
  type SourceSettings
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { SourceField } from './SourceField'
import { draftOf, payloadOf, validate, type ConnectionSource, type Draft } from './sourceConnection'
import { accessHandled, errorText, fieldErrors, formatDateTime, requestIdOf, scheduleLabel, tokenText } from './sourceFormat'
import { tokenMustBeRetyped, tokenRule } from './sourceTabs'
import './sourcePage.css'

type SourceConnectionSettingsProps = {
  source: ConnectionSource
  onChanged: () => void
  onNotice: (message: string) => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Command =
  | { kind: 'idle' }
  | { kind: 'running'; target: 'save' | 'reset' | 'check' }
  | { kind: 'failed'; error: unknown }

const sourceNames: Record<ConnectionSource, string> = {
  MOODLE: 'Moodle',
  WEBSITE: 'сайтом',
  CUSTOMER_CRM: 'CRM заказчика'
}

export const SourceConnectionSettings = ({
  source,
  onChanged,
  onNotice,
  onSessionExpired,
  onProfileUnavailable
}: SourceConnectionSettingsProps) => {
  const [settings, setSettings] = useState<SourceSettings | null>(null)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [command, setCommand] = useState<Command>({ kind: 'idle' })
  const [check, setCheck] = useState<SourceConnectionCheck | null>(null)
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

  // Результат проверки относится к источнику, у которого её запустили, и не должен переходить на соседнюю вкладку.
  useEffect(() => {
    setCheck(null)
  }, [source])

  if (loadError !== null) {
    return (
      <div role="alert">
        <p className="src-error">{errorText(loadError, 'Не удалось загрузить настройки подключения.')}</p>
        <SupportDetails requestId={requestIdOf(loadError)} />
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }
  if (settings === null || draft === null) {
    return <p role="status">Загружаем настройки подключения…</p>
  }

  const errors = validate(draft, settings, source)
  const invalid = Object.keys(errors).length > 0
  const busy = command.kind === 'running'
  const update = (patch: Partial<Draft>) => {
    setDraft({ ...draft, ...patch })
    setCheck(null)
  }
  const fail = (error: unknown) => {
    if (!accessHandled(error, onSessionExpired, onProfileUnavailable)) {
      setCommand({ kind: 'failed', error })
    }
  }

  const save = async () => {
    setCommand({ kind: 'running', target: 'save' })
    try {
      const saved = await apiClient.updateSourceSettings(payloadOf(draft, settings, source))
      setSettings(saved)
      setDraft(draftOf(saved))
      setCommand({ kind: 'idle' })
      onNotice('Настройки сохранены и уже действуют.')
      onChanged()
    } catch (error) {
      fail(error)
    }
  }

  const reset = async () => {
    setConfirmReset(false)
    setCommand({ kind: 'running', target: 'reset' })
    try {
      await apiClient.resetSourceSettings()
      await load()
      setCheck(null)
      setCommand({ kind: 'idle' })
      onNotice('Снова действуют значения из конфигурации развёртывания.')
      onChanged()
    } catch (error) {
      fail(error)
    }
  }

  const runCheck = async () => {
    setCommand({ kind: 'running', target: 'check' })
    try {
      setCheck(await apiClient.checkSourceConnection(source, payloadOf(draft, settings, source)))
      setCommand({ kind: 'idle' })
    } catch (error) {
      fail(error)
    }
  }

  const tokenField = (
    label: string,
    token: SourceSettings['moodleToken'],
    value: string,
    onChange: (value: string) => void,
    retype: boolean
  ) => (
    <SourceField
      label={`${label} — ${tokenText(token)}`}
      hint={tokenRule}
      error={errors.token}
      note={retype ? 'Адрес изменён: введите токен для нового адреса.' : undefined}
      wide
    >
      {(id, describedBy) => (
        <input
          id={id}
          type="password"
          autoComplete="new-password"
          aria-describedby={describedBy}
          aria-invalid={errors.token ? true : undefined}
          placeholder={token.origin === 'NONE' ? 'Вставьте токен' : 'Новый токен'}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </SourceField>
  )

  const urlField = (label: string, placeholder: string, value: string, error: string | undefined, onChange: (value: string) => void, hint?: string) => (
    <SourceField label={label} error={error} hint={hint} wide>
      {(id, describedBy) => (
        <input
          id={id}
          type="url"
          inputMode="url"
          placeholder={placeholder}
          aria-describedby={describedBy}
          aria-invalid={error ? true : undefined}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </SourceField>
  )

  return (
    <form className="src-connection__form" noValidate onSubmit={(event) => { event.preventDefault(); void save() }}>
      <p className="src-muted src-connection__wide">
        {settings.saved
          ? `Действуют значения, сохранённые на экране ${formatDateTime(settings.updatedAt)}${settings.updatedByName ? ` (${settings.updatedByName})` : ''}.`
          : 'Действуют начальные значения, заданные при развёртывании.'}
        {' '}Изменения применяются без перезапуска сервера.
      </p>
      {!settings.encryptionAvailable && (
        <p className="src-error src-connection__wide">
          Ключ шифрования токенов не задан в конфигурации развёртывания: токены на экране сохранить нельзя, действуют токены из
          конфигурации развёртывания. Адреса, курсы и расписание сохранить можно.
        </p>
      )}

      {source === 'MOODLE' && (
        <>
          {urlField(
            'Адрес Moodle',
            'https://lms.example.ru',
            draft.moodleBaseUrl,
            errors.moodleBaseUrl,
            (value) => update({ moodleBaseUrl: value }),
            'Основной адрес Moodle ровно в том виде, как он задан в самой системе Moodle, без логина и параметров.'
          )}
          {tokenField(
            'Токен внешнего сервиса Moodle',
            settings.moodleToken,
            draft.moodleToken,
            (value) => update({ moodleToken: value }),
            tokenMustBeRetyped(draft.moodleBaseUrl, settings.moodleBaseUrl, draft.moodleToken)
          )}
          <SourceField label="Номера курсов Moodle через запятую" error={errors.moodleCourseIds} wide>
            {(id, describedBy) => (
              <input
                id={id}
                inputMode="numeric"
                placeholder="2, 3"
                aria-describedby={describedBy}
                aria-invalid={errors.moodleCourseIds ? true : undefined}
                value={draft.moodleCourseIds}
                onChange={(event) => update({ moodleCourseIds: event.target.value })}
              />
            )}
          </SourceField>
          <SourceField label="Роли обучающихся (краткие имена)" error={errors.moodleStudentRoles}>
            {(id, describedBy) => (
              <input
                id={id}
                aria-describedby={describedBy}
                aria-invalid={errors.moodleStudentRoles ? true : undefined}
                value={draft.moodleStudentRoles}
                onChange={(event) => update({ moodleStudentRoles: event.target.value })}
              />
            )}
          </SourceField>
          <SourceField label="Роли преподавателей (краткие имена)">
            {(id) => (
              <input id={id} value={draft.moodleTeacherRoles} onChange={(event) => update({ moodleTeacherRoles: event.target.value })} />
            )}
          </SourceField>
        </>
      )}

      {source === 'WEBSITE' && (
        <>
          {urlField('Адрес API сайта', 'https://site.example.ru', draft.websiteBaseUrl, errors.websiteBaseUrl, (value) => update({ websiteBaseUrl: value }))}
          {tokenField(
            'Токен сайта',
            settings.websiteToken,
            draft.websiteToken,
            (value) => update({ websiteToken: value }),
            tokenMustBeRetyped(draft.websiteBaseUrl, settings.websiteBaseUrl, draft.websiteToken)
          )}
          <SourceField
            label="Расписание (МСК)"
            hint="Шесть чисел через пробел: секунды, минуты, часы, день, месяц, день недели; звёздочка — любое значение. Не чаще раза в 5 минут. Примеры: «0 0 * * * *» — каждый час, «0 30 6 * * *» — ежедневно в 06:30."
            error={errors.syncCron}
            note={draft.syncCron.trim() === ''
              ? 'Пусто — плановая синхронизация выключена. Одно расписание для всех источников.'
              : `Сейчас: ${scheduleLabel(draft.syncCron.trim())} (МСК). Одно расписание для всех источников.`}
            wide
          >
            {(id, describedBy) => (
              <input
                id={id}
                placeholder="0 0 * * * *"
                aria-describedby={describedBy}
                aria-invalid={errors.syncCron ? true : undefined}
                value={draft.syncCron}
                onChange={(event) => update({ syncCron: event.target.value })}
              />
            )}
          </SourceField>
        </>
      )}

      {source === 'CUSTOMER_CRM' && (
        <>
          {urlField('Адрес API CRM заказчика', 'https://crm.example.ru', draft.crmBaseUrl, errors.crmBaseUrl, (value) => update({ crmBaseUrl: value }))}
          {tokenField(
            'Токен CRM заказчика',
            settings.crmToken,
            draft.crmToken,
            (value) => update({ crmToken: value }),
            tokenMustBeRetyped(draft.crmBaseUrl, settings.crmBaseUrl, draft.crmToken)
          )}
        </>
      )}

      <div className="src-actions src-connection__wide">
        <button type="submit" disabled={busy || invalid}>
          {command.kind === 'running' && command.target === 'save' ? 'Сохраняем…' : 'Сохранить'}
        </button>
        <button type="button" className="button--secondary" disabled={busy || invalid} onClick={() => void runCheck()}>
          {command.kind === 'running' && command.target === 'check' ? 'Проверяем…' : `Проверить связь с ${sourceNames[source]}`}
        </button>
        {settings.saved && (
          <button type="button" className="button--secondary" disabled={busy} onClick={() => setConfirmReset(true)}>
            Вернуть значения конфигурации
          </button>
        )}
        <HintButton label="Что делает «Вернуть значения конфигурации»">
          Сохранённые на экране адреса, токены, курсы, роли и расписание всех источников (сайта, Moodle, CRM заказчика) удаляются,
          снова действуют начальные значения, заданные при развёртывании.
        </HintButton>
      </div>
      {check && (
        <div className="src-connection__wide" role="status">
          <p className={check.ok ? undefined : 'src-error'}>{check.ok ? '' : 'Проверка не прошла: '}{check.message}.</p>
          {check.details.length > 0 && <p>{source === 'MOODLE' ? 'Курсы: ' : ''}{check.details.join('; ')}</p>}
        </div>
      )}
      {command.kind === 'failed' && (
        <div className="src-connection__wide" role="alert">
          <p className="src-error">{errorText(command.error, 'Действие не выполнено.')}</p>
          {fieldErrors(command.error).map((text) => <p key={text} className="src-error">{text}</p>)}
          <SupportDetails requestId={requestIdOf(command.error)} />
        </div>
      )}

      <ConfirmDialog
        open={confirmReset}
        title="Вернуть значения конфигурации?"
        description="Сохранённые на экране адреса, курсы, роли, расписание и токены всех источников (сайта, Moodle, CRM заказчика) будут удалены; снова начнут действовать начальные значения, заданные при развёртывании."
        confirmLabel="Вернуть"
        onConfirm={() => void reset()}
        onCancel={() => setConfirmReset(false)}
      />
    </form>
  )
}
