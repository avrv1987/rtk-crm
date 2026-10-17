import { useCallback, useEffect, useState } from 'react'
import {
  apiClient,
  type DataSource,
  type LaborMarketDirection,
  type LaborMarketMappings,
  type LaborMarketSettings,
  type SourceConnectionCheck
} from '../../shared/api/client'
import { formatCalendarDate } from '../../shared/format/datetime'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { SourceField } from './SourceField'
import { SourceSyncCard, type SyncControl } from './SourceSyncCard'
import { accessHandled, errorText, fieldErrors, formatDateTime, requestIdOf, tokenText } from './sourceFormat'
import { tokenMustBeRetyped, tokenRule } from './sourceTabs'
import './sourcePage.css'

type Handlers = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type LaborMarketBlockProps = Handlers & {
  source: DataSource
  sync: SyncControl
  reloadToken: number
  onChanged: () => void
  onNotice: (message: string) => void
}

type Draft = { baseUrl: string; token: string }

type Command =
  | { kind: 'idle' }
  | { kind: 'running'; target: 'save' | 'reset' | 'check' | string }
  | { kind: 'failed'; error: unknown }

const draftOf = (settings: LaborMarketSettings): Draft => ({ baseUrl: settings.baseUrl ?? '', token: '' })

const monthText = (month: string | null | undefined) => (
  month ? `${month.slice(5, 7)}.${month.slice(0, 4)}` : ''
)

const originText = (direction: LaborMarketDirection) => {
  if (direction.externalCode === null || direction.externalCode === undefined) {
    return 'Не сопоставлено: при следующей синхронизации направление подберётся по названию, либо выберите его вручную.'
  }
  return direction.origin === 'MANUAL' ? 'Задано администратором.' : 'Подобрано по названию.'
}

const LaborMarketConnection = ({ onChanged, onNotice, onSessionExpired, onProfileUnavailable }: Handlers & {
  onChanged: () => void
  onNotice: (message: string) => void
}) => {
  const [settings, setSettings] = useState<LaborMarketSettings | null>(null)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [command, setCommand] = useState<Command>({ kind: 'idle' })
  const [check, setCheck] = useState<SourceConnectionCheck | null>(null)
  const [confirmReset, setConfirmReset] = useState(false)

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  const load = useCallback(async () => {
    try {
      const loaded = await apiClient.getLaborMarketSettings()
      setSettings(loaded)
      setDraft(draftOf(loaded))
      setLoadError(null)
    } catch (error) {
      if (!handled(error)) {
        setLoadError(error)
      }
    }
  }, [handled])

  useEffect(() => {
    void load()
  }, [load])

  if (loadError !== null) {
    return (
      <div role="alert">
        <p className="src-error">{errorText(loadError, 'Не удалось загрузить настройки источника «Рынок труда».')}</p>
        <SupportDetails requestId={requestIdOf(loadError)} />
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }
  if (settings === null || draft === null) {
    return <p role="status">Загружаем настройки источника «Рынок труда»…</p>
  }

  const busy = command.kind === 'running'
  const urlInvalid = draft.baseUrl.trim() !== ''
    && draft.baseUrl.trim() !== (settings.baseUrl ?? '')
    && !/^https:\/\/[^\s/?#@]+[^\s?#]*$/i.test(draft.baseUrl.trim())
  const tokenInvalid = /\s/.test(draft.token.trim())
  const invalid = urlInvalid || tokenInvalid
  const payload = {
    baseUrl: draft.baseUrl.trim() === '' ? null : draft.baseUrl.trim(),
    token: draft.token.trim() === '' ? null : draft.token.trim()
  }
  const update = (patch: Partial<Draft>) => {
    setDraft({ ...draft, ...patch })
    setCheck(null)
  }
  const fail = (error: unknown) => {
    if (!handled(error)) {
      setCommand({ kind: 'failed', error })
    }
  }

  const save = async () => {
    setCommand({ kind: 'running', target: 'save' })
    try {
      const saved = await apiClient.updateLaborMarketSettings(payload)
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
      await apiClient.resetLaborMarketSettings()
      await load()
      setCheck(null)
      setCommand({ kind: 'idle' })
      onNotice('Снова действуют значения из конфигурации развёртывания.')
      onChanged()
    } catch (error) {
      fail(error)
    }
  }

  const checkConnection = async () => {
    setCommand({ kind: 'running', target: 'check' })
    try {
      setCheck(await apiClient.checkLaborMarketConnection(payload))
      setCommand({ kind: 'idle' })
    } catch (error) {
      fail(error)
    }
  }

  const retype = tokenMustBeRetyped(draft.baseUrl, settings.baseUrl, draft.token)

  return (
    <form className="src-connection__form" noValidate onSubmit={(event) => { event.preventDefault(); void save() }}>
      <p className="src-muted src-connection__wide">
        {settings.saved
          ? `Действуют значения, сохранённые на экране ${formatDateTime(settings.updatedAt)}${settings.updatedByName ? ` (${settings.updatedByName})` : ''}.`
          : 'Действуют начальные значения, заданные при развёртывании.'}
      </p>
      {!settings.encryptionAvailable && (
        <p className="src-error src-connection__wide">
          Ключ шифрования токенов не задан в конфигурации развёртывания: токен на экране сохранить нельзя, действует токен из
          конфигурации. Адрес сохранить можно.
        </p>
      )}
      <SourceField
        label="Адрес источника"
        error={urlInvalid ? 'Адрес должен начинаться с https:// и не содержать логина, параметров и якоря.' : undefined}
        wide
      >
        {(id, describedBy) => (
          <input
            id={id}
            type="url"
            inputMode="url"
            placeholder="https://labor-market.example.ru"
            aria-describedby={describedBy}
            aria-invalid={urlInvalid ? true : undefined}
            value={draft.baseUrl}
            onChange={(event) => update({ baseUrl: event.target.value })}
          />
        )}
      </SourceField>
      <SourceField
        label={`Токен источника — ${tokenText(settings.token)}`}
        hint={tokenRule}
        error={tokenInvalid ? 'Токен не должен содержать пробелов.' : undefined}
        note={retype ? 'Адрес изменён: введите токен для нового адреса.' : undefined}
        wide
      >
        {(id, describedBy) => (
          <input
            id={id}
            type="password"
            autoComplete="new-password"
            placeholder={settings.token.origin === 'NONE' ? 'Вставьте токен' : 'Новый токен'}
            aria-describedby={describedBy}
            aria-invalid={tokenInvalid ? true : undefined}
            value={draft.token}
            onChange={(event) => update({ token: event.target.value })}
          />
        )}
      </SourceField>
      <div className="src-actions src-connection__wide">
        <button type="submit" disabled={busy || invalid}>
          {command.kind === 'running' && command.target === 'save' ? 'Сохраняем…' : 'Сохранить'}
        </button>
        <button type="button" className="button--secondary" disabled={busy || invalid} onClick={() => void checkConnection()}>
          {command.kind === 'running' && command.target === 'check' ? 'Проверяем…' : 'Проверить связь'}
        </button>
        {settings.saved && (
          <button type="button" className="button--secondary" disabled={busy} onClick={() => setConfirmReset(true)}>
            Вернуть значения конфигурации
          </button>
        )}
      </div>
      {check && (
        <div className="src-connection__wide" role="status">
          <p className={check.ok ? undefined : 'src-error'}>{check.ok ? '' : 'Проверка не прошла: '}{check.message}.</p>
          {check.details.length > 0 && <p>{check.details.join('; ')}</p>}
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
        description="Сохранённые на экране адрес и токен источника «Рынок труда» будут удалены; снова начнут действовать начальные значения, заданные при развёртывании."
        confirmLabel="Вернуть"
        onConfirm={() => void reset()}
        onCancel={() => setConfirmReset(false)}
      />
    </form>
  )
}

const LaborMarketDirections = ({ reloadToken, onChanged, onNotice, onSessionExpired, onProfileUnavailable }: Handlers & {
  reloadToken: number
  onChanged: () => void
  onNotice: (message: string) => void
}) => {
  const [mappings, setMappings] = useState<LaborMarketMappings | null>(null)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [choices, setChoices] = useState<Record<string, string>>({})
  const [command, setCommand] = useState<Command>({ kind: 'idle' })

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  const load = useCallback(async () => {
    try {
      setMappings(await apiClient.listLaborMarketMappings())
      setChoices({})
      setLoadError(null)
    } catch (error) {
      if (!handled(error)) {
        setLoadError(error)
      }
    }
  }, [handled])

  useEffect(() => {
    void load()
  }, [load, reloadToken])

  const saveMapping = async (direction: LaborMarketDirection) => {
    const code = choices[direction.directionId] ?? direction.externalCode ?? ''
    setCommand({ kind: 'running', target: direction.directionId })
    try {
      await apiClient.updateLaborMarketMapping(direction.directionId, {
        externalCode: code === '' ? null : code,
        version: direction.version ?? null
      })
      await load()
      setCommand({ kind: 'idle' })
      onNotice(`Сопоставление направления «${direction.directionName}» сохранено, отчёт уже его учитывает.`)
      onChanged()
    } catch (error) {
      if (!handled(error)) {
        setCommand({ kind: 'failed', error })
      }
    }
  }

  if (loadError !== null) {
    return (
      <div role="alert">
        <p className="src-error">{errorText(loadError, 'Не удалось загрузить направления с признаком.')}</p>
        <SupportDetails requestId={requestIdOf(loadError)} />
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }
  if (mappings === null) {
    return <p role="status">Загружаем направления…</p>
  }
  const busy = command.kind === 'running'

  return (
    <div className="src-section">
      <div className="src-section__head">
        <h4>Направления с признаком ({mappings.directions.length})</h4>
        <HintButton label="Что за признак">
          Источник присылает по месяцам число открытых вакансий по ИТ-направлениям. В отчёте «Востребованность программ» оно
          показано рядом с показателями школы только для направлений с признаком «Учитывать спрос рынка труда» (раздел
          «Справочники», вкладка «ИТ-направления»).
        </HintButton>
        <a className="src-link" href="#/admin/catalogs">Справочники → ИТ-направления</a>
      </div>
      <p className="src-muted">
        {mappings.asOf
          ? `Последний срез на ${formatCalendarDate(mappings.asOf)}, источник данных: ${mappings.dataSource ?? 'не указан'}.`
          : 'Срезов от источника пока нет: запустите синхронизацию источника «Рынок труда».'}
      </p>
      {mappings.directions.length === 0 ? (
        <p>Ни у одного ИТ-направления нет признака «Учитывать спрос рынка труда».</p>
      ) : (
        <details>
          <summary>Показать направления</summary>
          <table className="src-table" aria-label="Направления с признаком «Учитывать спрос рынка труда»">
            <thead>
              <tr>
                <th scope="col">Направление</th>
                <th scope="col">Направление источника</th>
                <th scope="col"><span className="sr-only">Действие</span></th>
              </tr>
            </thead>
            <tbody>
              {mappings.directions.map((direction) => {
                const choice = choices[direction.directionId] ?? direction.externalCode ?? ''
                const changed = choice !== (direction.externalCode ?? '')
                return (
                  <tr key={direction.directionId}>
                    <td className="src-cell__main">
                      <strong>{direction.directionName}</strong>
                      <span className="src-cell__sub">
                        {originText(direction)}
                        {direction.latestVacancies !== null && direction.latestVacancies !== undefined
                          && ` Вакансий в последнем срезе (${monthText(direction.latestMonth)}): ${direction.latestVacancies}.`}
                      </span>
                    </td>
                    <td data-label="Направление источника">
                      <select
                        aria-label={`Направление источника для «${direction.directionName}»`}
                        value={choice}
                        disabled={busy || mappings.sourceDirections.length === 0}
                        onChange={(event) => setChoices({ ...choices, [direction.directionId]: event.target.value })}
                      >
                        <option value="">Не сопоставлено</option>
                        {mappings.sourceDirections.map((item) => <option key={item.code} value={item.code}>{item.name}</option>)}
                      </select>
                    </td>
                    <td className="src-cell__actions">
                      <button type="button" className="button--secondary" disabled={busy || !changed} onClick={() => void saveMapping(direction)}>
                        {command.kind === 'running' && command.target === direction.directionId ? 'Сохраняем…' : 'Сохранить'}
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </details>
      )}
      {command.kind === 'failed' && (
        <div role="alert">
          <p className="src-error">{errorText(command.error, 'Действие не выполнено.')}</p>
          {fieldErrors(command.error).map((text) => <p key={text} className="src-error">{text}</p>)}
          <SupportDetails requestId={requestIdOf(command.error)} />
        </div>
      )}
    </div>
  )
}

export const LaborMarketBlock = ({ source, sync, reloadToken, onChanged, onNotice, onSessionExpired, onProfileUnavailable }: LaborMarketBlockProps) => (
  <SourceSyncCard
    source={source}
    sync={sync}
    hint="Источник присылает месячные срезы числа вакансий по ИТ-направлениям. Его адрес и токен задаются в «Настройках подключения»."
    onSessionExpired={onSessionExpired}
    onProfileUnavailable={onProfileUnavailable}
    connection={(
      <LaborMarketConnection
        onChanged={onChanged}
        onNotice={onNotice}
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />
    )}
  >
    <LaborMarketDirections
      reloadToken={reloadToken}
      onChanged={onChanged}
      onNotice={onNotice}
      onSessionExpired={onSessionExpired}
      onProfileUnavailable={onProfileUnavailable}
    />
  </SourceSyncCard>
)
