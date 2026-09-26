import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  type DataSource,
  type SourceCode,
  type SourceMappingOptions,
  type SourceRecord,
  type SourceRecordApplyResult,
  type SyncRun
} from '../../shared/api/client'

type SourcesPanelProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; sources: DataSource[]; records: SourceRecord[]; options: SourceMappingOptions }
  | { kind: 'failed'; error: unknown }

type CommandState =
  | { kind: 'idle' }
  | { kind: 'running'; target: string }
  | { kind: 'failed'; target: string; error: unknown }
  | { kind: 'applied'; target: string; result: SourceRecordApplyResult }

type Mapping = {
  organizationId: string
  programId: string
  runStartsOn: string
  runLastDay: string
}

const pollIntervalMs = 2000

const runStatusLabels: Record<SyncRun['status'], string> = {
  PENDING: 'В очереди',
  RUNNING: 'Выполняется',
  SUCCEEDED: 'Завершена',
  FAILED: 'Ошибка'
}

const recordTypeLabels: Record<string, string> = {
  partnership_request: 'Заявка вуза на партнёрство',
  learning_application: 'Заявка на обучение',
  moodle_course: 'Курс Moodle',
  moodle_group: 'Группа курса Moodle'
}

const recordStatusLabels: Record<SourceRecord['status'], string> = {
  APPLIED: 'Применена',
  NEEDS_MAPPING: 'Требует сопоставления',
  FAILED: 'Ошибка',
  SKIPPED: 'Пропущена'
}

const dateTime = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeStyle: 'short' })

const formatDateTime = (value: string | null | undefined) => {
  if (value === null || value === undefined) {
    return 'нет'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTime.format(date)
}

const isActive = (run: SyncRun | null | undefined) => run?.status === 'PENDING' || run?.status === 'RUNNING'

const syncBlocker = (source: DataSource) => {
  if (!source.adapterAvailable) {
    return 'Адаптер источника ещё не подключён.'
  }
  if (!source.configured) {
    return source.source === 'MOODLE'
      ? 'Источник не настроен: задайте MOODLE_BASE_URL, MOODLE_TOKEN и MOODLE_COURSE_IDS в конфигурации развёртывания.'
      : 'Источник не настроен: задайте SITE_BASE_URL и SITE_TOKEN в конфигурации развёртывания.'
  }
  if (isActive(source.lastRun)) {
    return 'Синхронизация уже выполняется.'
  }
  return null
}

const needsOrganization = (record: SourceRecord) => record.status === 'NEEDS_MAPPING' && !record.organizationId

const isMoodle = (record: SourceRecord) => record.source === 'MOODLE'

const needsProgram = (record: SourceRecord) => (
  record.status === 'NEEDS_MAPPING' && (isMoodle(record) || Boolean(record.programName)) && !record.programId
)

const needsRun = (record: SourceRecord) => isMoodle(record) && record.status === 'NEEDS_MAPPING'

const nextDay = (date: string) => {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + 1)
  return value.toISOString().slice(0, 10)
}

const ErrorDetails = ({ error, message }: { error: unknown; message: string }) => (
  <div className="interaction-command-error" role="alert">
    <p>{error instanceof ApiError ? error.message : message}</p>
    {error instanceof ApiError && (
      <>
        <p>Код: {error.code}</p>
        {Object.entries(error.fieldErrors ?? {}).map(([field, text]) => <p key={field}>{text}</p>)}
        <p className="request-id">Request ID: {error.requestId}</p>
      </>
    )}
  </div>
)

const RunSummary = ({ run }: { run: SyncRun }) => (
  <div className="data-sources__run">
    <p>
      Последний запуск: {runStatusLabels[run.status]}, {formatDateTime(run.finishedAt ?? run.startedAt ?? run.createdAt)}
    </p>
    <dl className="data-sources__counters">
      <div><dt>Получено</dt><dd>{run.fetchedCount}</dd></div>
      <div><dt>Создано</dt><dd>{run.createdCount}</dd></div>
      <div><dt>Обновлено</dt><dd>{run.updatedCount}</dd></div>
      <div><dt>Пропущено</dt><dd>{run.skippedCount}</dd></div>
      <div><dt>Требуют сопоставления</dt><dd>{run.needsMappingCount}</dd></div>
      <div><dt>Ошибки</dt><dd>{run.failedCount}</dd></div>
    </dl>
    {run.errorMessage && (
      <p className={run.status === 'FAILED' ? 'data-sources__error' : undefined}>
        {run.errorCode ? `${run.errorCode}: ` : ''}{run.errorMessage}
      </p>
    )}
  </div>
)

export const SourcesPanel = ({ onSessionExpired, onProfileUnavailable }: SourcesPanelProps) => {
  const [state, setState] = useState<PanelState>({ kind: 'loading' })
  const [command, setCommand] = useState<CommandState>({ kind: 'idle' })
  const [mappings, setMappings] = useState<Record<string, Mapping>>({})
  const loadVersion = useRef(0)

  const handleAccessError = useCallback((error: unknown) => {
    if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
      onSessionExpired()
      return true
    }
    if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
      onProfileUnavailable(error.requestId)
      return true
    }
    return false
  }, [onProfileUnavailable, onSessionExpired])

  const load = useCallback(async (showLoading: boolean) => {
    const version = ++loadVersion.current
    if (showLoading) {
      setState({ kind: 'loading' })
    }
    try {
      const [sources, records, options] = await Promise.all([
        apiClient.listDataSources(),
        apiClient.listSourceProblemRecords(),
        apiClient.listSourceMappingOptions()
      ])
      if (version === loadVersion.current) {
        setState({ kind: 'ready', sources, records, options })
      }
    } catch (error) {
      if (version === loadVersion.current && !handleAccessError(error)) {
        setState({ kind: 'failed', error })
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void load(true)
    return () => {
      loadVersion.current += 1
    }
  }, [load])

  useEffect(() => {
    if (state.kind !== 'ready' || !state.sources.some((source) => isActive(source.lastRun))) {
      return
    }
    const timer = window.setTimeout(() => void load(false), pollIntervalMs)
    return () => window.clearTimeout(timer)
  }, [load, state])

  const startSync = async (source: SourceCode) => {
    setCommand({ kind: 'running', target: source })
    try {
      await apiClient.startSourceSync(source)
      setCommand({ kind: 'idle' })
    } catch (error) {
      if (!handleAccessError(error)) {
        setCommand({ kind: 'failed', target: source, error })
      }
    }
    await load(false)
  }

  const mappingOf = (record: SourceRecord): Mapping => (
    mappings[record.id] ?? { organizationId: '', programId: '', runStartsOn: '', runLastDay: '' }
  )

  const updateMapping = (record: SourceRecord, patch: Partial<Mapping>) => {
    setMappings((current) => ({ ...current, [record.id]: { ...mappingOf(record), ...patch } }))
  }

  const applyRecord = async (record: SourceRecord) => {
    const mapping = mappingOf(record)
    setCommand({ kind: 'running', target: record.id })
    try {
      const result = await apiClient.applySourceRecord(record.id, {
        organizationId: mapping.organizationId === '' ? null : mapping.organizationId,
        programId: mapping.programId === '' ? null : mapping.programId,
        runStartsOn: needsRun(record) && mapping.runStartsOn !== '' ? mapping.runStartsOn : null,
        runEndsOn: needsRun(record) && mapping.runLastDay !== '' ? nextDay(mapping.runLastDay) : null
      })
      setCommand({ kind: 'applied', target: record.id, result })
    } catch (error) {
      if (!handleAccessError(error)) {
        setCommand({ kind: 'failed', target: record.id, error })
      }
    }
    await load(false)
  }

  const busy = command.kind === 'running'

  return (
    <section className="data-sources" aria-labelledby="data-sources-title" aria-busy={state.kind === 'loading' || busy}>
      <div className="data-sources__header">
        <div>
          <p className="eyebrow">Администрирование</p>
          <h2 id="data-sources-title">Источники данных</h2>
        </div>
        <button type="button" onClick={() => void load(true)} disabled={state.kind === 'loading'}>Обновить</button>
      </div>
      <p className="data-sources__intro">
        Сайт подключён по предложенному контракту: API сайта заказчика ещё не получен, поэтому демонстрационный стенд
        не подтверждает совместимость. Moodle читается через ограниченный веб-сервис по разрешённым курсам: в CRM
        остаются только названия курсов и групп и числа; ФИО, которые Moodle отдаёт вместе со списком участников, CRM
        не сохраняет, оценки не запрашиваются. Курс или его группы сопоставляются здесь с вузом, программой и датами потока
        обучения: параллельные потоки в отчёте считаются только по потокам с датами. Адреса,
        токены и список курсов задаются только в конфигурации развёртывания. Повторная синхронизация не создаёт
        дублей, ошибка источника не меняет уже загруженные данные.
      </p>

      {state.kind === 'loading' && <p role="status">Загружаем источники данных…</p>}
      {state.kind === 'failed' && (
        <>
          <ErrorDetails error={state.error} message="Не удалось загрузить источники данных." />
          <button type="button" onClick={() => void load(true)}>Повторить</button>
        </>
      )}

      {state.kind === 'ready' && (
        <>
          <ul className="data-sources__list">
            {state.sources.map((source) => {
              const blocker = syncBlocker(source)
              return (
                <li key={source.source} className="data-sources__item">
                  <div className="data-sources__title">
                    <h3>{source.title}</h3>
                    {source.proposedContract && <span className="data-sources__badge">Предложенный контракт, DEMO</span>}
                  </div>
                  <p>
                    {source.adapterAvailable ? (source.configured ? 'Настроен' : 'Не настроен') : 'Адаптер не подключён'}
                    {source.adapterAvailable && `. Последняя успешная синхронизация: ${formatDateTime(source.lastSuccessAt)}`}
                    {source.updatedSince && `. Изменения запрашиваются с ${formatDateTime(source.updatedSince)}`}
                  </p>
                  {source.lastRun ? <RunSummary run={source.lastRun} /> : source.adapterAvailable && <p>Синхронизация ещё не запускалась.</p>}
                  {source.problemCount > 0 && <p>Записей для разбора: {source.problemCount}</p>}
                  {source.adapterAvailable && (
                    <div className="data-sources__actions">
                      <button type="button" onClick={() => void startSync(source.source)} disabled={blocker !== null || busy}>
                        {command.kind === 'running' && command.target === source.source ? 'Запускаем…' : 'Запустить синхронизацию'}
                      </button>
                      {blocker !== null && <p className="data-sources__hint">{blocker}</p>}
                    </div>
                  )}
                  {command.kind === 'failed' && command.target === source.source && (
                    <ErrorDetails error={command.error} message="Синхронизация не запущена." />
                  )}
                </li>
              )
            })}
          </ul>

          <section className="data-sources__records" aria-labelledby="data-sources-records-title">
            <h3 id="data-sources-records-title">Записи для разбора</h3>
            {state.records.length === 0 ? (
              <p>Записей, требующих сопоставления или завершившихся ошибкой, нет.</p>
            ) : (
              <div className="data-sources__table-scroll" role="region" aria-label="Записи источников для разбора" tabIndex={0}>
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Запись</th>
                      <th scope="col">Во внешнем источнике</th>
                      <th scope="col">Состояние</th>
                      <th scope="col">Сопоставление</th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.records.map((record) => {
                      const mapping = mappingOf(record)
                      const missingOrganization = needsOrganization(record) && mapping.organizationId === ''
                      const missingProgram = isMoodle(record) && needsProgram(record) && mapping.programId === ''
                      const missingRun = needsRun(record) && (mapping.runStartsOn === '' || mapping.runLastDay === '')
                      const running = command.kind === 'running' && command.target === record.id
                      return (
                        <tr key={record.id}>
                          <td>
                            <p>{recordTypeLabels[record.recordType] ?? record.recordType}</p>
                            <p>Внешний ID: {record.externalId}</p>
                            <p>
                              {isMoodle(record) ? 'Наблюдение' : 'Изменена на сайте'}: {formatDateTime(record.externalUpdatedAt)}
                            </p>
                          </td>
                          <td>
                            <p>
                              {record.organizationName ?? 'Вуз не указан'}
                              {record.organizationExternalId ? ` (ID ${record.organizationExternalId})` : ''}
                            </p>
                            {record.programName && <p>Программа: {record.programName}</p>}
                          </td>
                          <td>
                            <p>{recordStatusLabels[record.status]}</p>
                            {record.error && <p>{record.error}</p>}
                          </td>
                          <td>
                            <div className="data-sources__mapping">
                              {needsOrganization(record) && (
                                <label>
                                  Вуз CRM
                                  <select
                                    value={mapping.organizationId}
                                    onChange={(event) => updateMapping(record, { organizationId: event.target.value })}
                                  >
                                    <option value="">Выберите вуз</option>
                                    {state.options.organizations.map((option) => (
                                      <option key={option.id} value={option.id}>{option.name}</option>
                                    ))}
                                  </select>
                                </label>
                              )}
                              {needsProgram(record) && (
                                <label>
                                  Программа CRM
                                  <select
                                    value={mapping.programId}
                                    onChange={(event) => updateMapping(record, { programId: event.target.value })}
                                  >
                                    <option value="">{isMoodle(record) ? 'Выберите программу' : 'Искать по названию'}</option>
                                    {state.options.programs.map((option) => (
                                      <option key={option.id} value={option.id}>{option.name}</option>
                                    ))}
                                  </select>
                                </label>
                              )}
                              {needsRun(record) && (
                                <>
                                  <label>
                                    Начало потока
                                    <input
                                      type="date"
                                      value={mapping.runStartsOn}
                                      onChange={(event) => updateMapping(record, { runStartsOn: event.target.value })}
                                    />
                                  </label>
                                  <label>
                                    Последний день потока
                                    <input
                                      type="date"
                                      min={mapping.runStartsOn === '' ? undefined : mapping.runStartsOn}
                                      value={mapping.runLastDay}
                                      onChange={(event) => updateMapping(record, { runLastDay: event.target.value })}
                                    />
                                  </label>
                                </>
                              )}
                              <button
                                type="button"
                                onClick={() => void applyRecord(record)}
                                disabled={busy || missingOrganization || missingProgram || missingRun}
                              >
                                {running ? 'Применяем…' : record.status === 'NEEDS_MAPPING' ? 'Сопоставить и применить' : 'Применить повторно'}
                              </button>
                              {(missingOrganization || missingProgram || missingRun) && (
                                <p className="data-sources__hint">
                                  {isMoodle(record)
                                    ? 'Выберите вуз и программу и укажите даты потока, чтобы применить запись.'
                                    : 'Выберите вуз, чтобы применить запись.'}
                                </p>
                              )}
                              {command.kind === 'failed' && command.target === record.id && (
                                <ErrorDetails error={command.error} message="Запись не применена." />
                              )}
                            </div>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
            {command.kind === 'applied' && (
              <p role="status">
                {command.result.record.status === 'APPLIED'
                  ? `Запись ${command.result.record.externalId} применена.`
                  : `Запись ${command.result.record.externalId}: ${recordStatusLabels[command.result.record.status].toLowerCase()}. ${command.result.record.error ?? ''}`}
                {command.result.reappliedCount > 0 && ` Сопоставление применило ещё записей: ${command.result.reappliedCount}.`}
              </p>
            )}
          </section>
        </>
      )}
    </section>
  )
}
