import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  type DataSource,
  type SourceCode,
  type SourceMappingOptions,
  type SourceRecord,
  type Team
} from '../../shared/api/client'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { Toast } from '../../shared/ui/Toast'
import { CrmExchangePanel } from '../sources/CrmExchangePanel'
import { LaborMarketBlock } from '../sources/LaborMarketBlock'
import { SourceConnectionSettings } from '../sources/SourceConnectionSettings'
import { SourceMappingsList } from '../sources/SourceMappingsList'
import { SourceReviewTab } from '../sources/SourceReviewTab'
import { SourceSyncCard, type SyncControl } from '../sources/SourceSyncCard'
import { SourceTabBar, tabId, tabPanelId, type SourceTabLabel } from '../sources/SourceTabBar'
import { errorText, requestIdOf } from '../sources/sourceFormat'
import { isRunActive, sourceState, sourceTabs, type SourcesTab } from '../sources/sourceTabs'
import { useSourcesTab } from '../sources/useSourcesTab'
import '../sources/sources.css'
import '../sources/sourcePage.css'

type SourcesPanelProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
  onSynced?: () => void
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; sources: DataSource[]; records: SourceRecord[]; options: SourceMappingOptions; teams: Team[] }
  | { kind: 'failed'; error: unknown }

type CommandState =
  | { kind: 'idle' }
  | { kind: 'running'; target: SourceCode }
  | { kind: 'failed'; target: SourceCode; error: unknown }

const pollIntervalMs = 2000

const pageHint = 'Курс или его группы Moodle сопоставляются с вузом, программой и датами потока обучения: параллельные потоки в отчёте '
  + 'считаются только по потокам с датами. Повторная синхронизация не создаёт дублей, ошибка источника не меняет уже загруженные данные. '
  + 'Начальные значения подключения берутся из конфигурации развёртывания.'

const sourceHints: Partial<Record<SourcesTab, string>> = {
  website: 'Заявки сайта сопоставляются с вузами и программами CRM; то, что не сопоставилось само, попадает на вкладку «Записи для разбора».',
  moodle: 'Moodle читается через ограниченный веб-сервис по разрешённым курсам: в CRM остаются только названия курсов и групп и числа; '
    + 'ФИО, которые Moodle отдаёт вместе со списком участников, CRM не сохраняет, оценки не запрашиваются.',
  crm: 'Из CRM заказчика приходят организации и контакты: их сопоставляют по внешнему ID, затем по ИНН и по названию, неоднозначные записи '
    + 'разбираются на вкладке «Записи для разбора». В CRM заказчика уходят события работы с вузом: смена этапа, следующий шаг, '
    + 'ответственный менеджер, смена статуса договора с вузом.'
}

const tabLabelsOf = (state: PanelState): Record<SourcesTab, SourceTabLabel> => {
  const labels = Object.fromEntries(sourceTabs.map((tab) => [tab.key, { label: tab.label }])) as Record<SourcesTab, SourceTabLabel>
  if (state.kind === 'ready') {
    labels.review = { label: `Записи для разбора (${state.records.length})` }
    for (const tab of sourceTabs) {
      const source = state.sources.find((item) => item.source === tab.source)
      const current = source && sourceState(source)
      if (current === 'failed') {
        labels[tab.key] = { label: tab.label, flag: 'ошибка' }
      } else if (current === 'stale') {
        labels[tab.key] = { label: tab.label, flag: 'устарели', flagWarning: true }
      }
    }
  }
  return labels
}

export const SourcesPanel = ({ onSessionExpired, onProfileUnavailable, onSynced }: SourcesPanelProps) => {
  const [state, setState] = useState<PanelState>({ kind: 'loading' })
  const [command, setCommand] = useState<CommandState>({ kind: 'idle' })
  const [notice, setNotice] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const loadVersion = useRef(0)
  const { tab, mappingsSource, select } = useSourcesTab(state.kind === 'ready' ? state.sources : null)

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
      const [sources, records, options, teams] = await Promise.all([
        apiClient.listDataSources(),
        apiClient.listSourceProblemRecords(),
        apiClient.listSourceMappingOptions(),
        apiClient.listTeams()
      ])
      if (version === loadVersion.current) {
        setState({ kind: 'ready', sources, records, options, teams })
        setReloadToken((token) => token + 1)
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
    if (state.kind !== 'ready' || !state.sources.some((source) => isRunActive(source.lastRun))) {
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
    onSynced?.()
  }

  const syncOf = (source: SourceCode): SyncControl => ({
    syncing: command.kind === 'running' && command.target === source,
    busy: command.kind === 'running',
    error: command.kind === 'failed' && command.target === source && (
      <div role="alert">
        <p className="src-error">{errorText(command.error, 'Синхронизация не запущена.')}</p>
        <SupportDetails requestId={requestIdOf(command.error)} code={command.error instanceof ApiError ? command.error.code : undefined} />
      </div>
    ),
    start: () => void startSync(source)
  })

  const reload = useCallback(() => load(false), [load])
  const notify = useCallback((message: string) => setNotice(message), [])
  const handlers = { onSessionExpired, onProfileUnavailable }

  const renderTab = (current: SourcesTab, ready: Extract<PanelState, { kind: 'ready' }>) => {
    if (current === 'review') {
      return (
        <SourceReviewTab
          records={ready.records}
          options={ready.options}
          teams={ready.teams}
          onReload={reload}
          onNotice={notify}
          {...handlers}
        />
      )
    }
    if (current === 'mappings') {
      return (
        <SourceMappingsList
          key={mappingsSource ?? 'all'}
          options={ready.options}
          reloadToken={reloadToken}
          initialSource={mappingsSource}
          onChanged={() => void load(false)}
          onNotice={notify}
          {...handlers}
        />
      )
    }
    const code = sourceTabs.find((item) => item.key === current)?.source
    const source = ready.sources.find((item) => item.source === code)
    if (source === undefined || code === undefined) {
      return <EmptyState text="Источник недоступен." />
    }
    if (current === 'labor-market') {
      return (
        <LaborMarketBlock
          source={source}
          sync={syncOf(code)}
          reloadToken={reloadToken}
          onChanged={() => void load(false)}
          onNotice={notify}
          {...handlers}
        />
      )
    }
    const connectionSource = code === 'MOODLE' || code === 'WEBSITE' || code === 'CUSTOMER_CRM' ? code : null
    return (
      <SourceSyncCard
        source={source}
        sync={syncOf(code)}
        mappingsSource={current === 'website' ? 'website' : current === 'moodle' ? 'moodle' : undefined}
        hint={sourceHints[current]}
        connection={connectionSource && (
          <SourceConnectionSettings
            source={connectionSource}
            onChanged={() => void load(false)}
            onNotice={notify}
            {...handlers}
          />
        )}
        {...handlers}
      >
        {current === 'crm' && (
          <CrmExchangePanel configured={source.configured} reloadToken={reloadToken} onNotice={notify} {...handlers} />
        )}
      </SourceSyncCard>
    )
  }

  return (
    <section className="data-sources src-page" aria-labelledby="data-sources-title" aria-busy={state.kind === 'loading' || command.kind === 'running'}>
      <div className="src-page__header">
        <h2 id="data-sources-title">Источники данных</h2>
        <HintButton label="Об источниках данных">{pageHint}</HintButton>
        <button type="button" className="button--secondary src-page__refresh" onClick={() => void load(true)} disabled={state.kind === 'loading'}>
          Обновить
        </button>
      </div>

      <SourceTabBar
        active={tab ?? 'website'}
        labels={tabLabelsOf(state)}
        onSelect={(next) => select(next)}
      />

      <div id={tabPanelId} role="tabpanel" aria-labelledby={tabId(tab ?? 'website')}>
        {state.kind === 'loading' && <p role="status">Загружаем источники данных…</p>}
        {state.kind === 'failed' && (
          <div role="alert">
            <p className="src-error">{errorText(state.error, 'Не удалось загрузить источники данных.')}</p>
            <SupportDetails requestId={requestIdOf(state.error)} />
            <button type="button" onClick={() => void load(true)}>Повторить</button>
          </div>
        )}
        {state.kind === 'ready' && tab !== null && renderTab(tab, state)}
      </div>
      <Toast message={notice} onClose={() => setNotice(null)} />
    </section>
  )
}
