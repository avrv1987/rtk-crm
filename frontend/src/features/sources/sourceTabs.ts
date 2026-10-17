import type { DataSource, SourceCode, SyncRun } from '../../shared/api/client'

export type SourcesTab = 'website' | 'moodle' | 'crm' | 'labor-market' | 'review' | 'mappings'

export type SourceState = 'ok' | 'stale' | 'failed' | 'running' | 'unconfigured' | 'disabled'

export const sourcesBaseHash = '#/admin/sources'

export const sourceTabs: { key: SourcesTab; label: string; source?: SourceCode }[] = [
  { key: 'website', label: 'Сайт ИТ Школы', source: 'WEBSITE' },
  { key: 'moodle', label: 'LMS Moodle', source: 'MOODLE' },
  { key: 'crm', label: 'CRM заказчика', source: 'CUSTOMER_CRM' },
  { key: 'labor-market', label: 'Рынок труда', source: 'LABOR_MARKET' },
  { key: 'review', label: 'Записи для разбора' },
  { key: 'mappings', label: 'Сопоставления' }
]

export type MappingsSource = 'website' | 'moodle'

export type SourcesHash = { tab: SourcesTab | null; mappingsSource: MappingsSource | null }

const isTab = (value: string | null): value is SourcesTab => sourceTabs.some((tab) => tab.key === value)

export const parseSourcesHash = (hash: string): SourcesHash => {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  if (path !== 'admin/sources') {
    return { tab: null, mappingsSource: null }
  }
  const params = new URLSearchParams(query)
  const tab = params.get('tab')
  const source = params.get('source')
  return {
    tab: isTab(tab) ? tab : null,
    mappingsSource: source === 'website' || source === 'moodle' ? source : null
  }
}

export const sourcesHash = (tab: SourcesTab, mappingsSource?: MappingsSource | null) => (
  `${sourcesBaseHash}?tab=${tab}${tab === 'mappings' && mappingsSource ? `&source=${mappingsSource}` : ''}`
)

export const tabOfSource = (source: SourceCode): SourcesTab => (
  sourceTabs.find((tab) => tab.source === source)?.key ?? 'website'
)

export const sourceTitleOf = (source: SourceCode) => sourceTabs.find((tab) => tab.source === source)?.label ?? source

export const isRunActive = (run: SyncRun | null | undefined) => run?.status === 'PENDING' || run?.status === 'RUNNING'

export const sourceState = (source: DataSource): SourceState => {
  if (!source.adapterAvailable) {
    return 'disabled'
  }
  if (!source.configured) {
    return 'unconfigured'
  }
  if (isRunActive(source.lastRun)) {
    return 'running'
  }
  if (source.lastRun?.status === 'FAILED') {
    return 'failed'
  }
  return source.stale === true ? 'stale' : 'ok'
}

export const sourceStateLabels: Record<SourceState, string> = {
  ok: 'Норма',
  stale: 'Устарели',
  failed: 'Ошибка',
  running: 'Выполняется',
  unconfigured: 'Не настроен',
  disabled: 'Выключен'
}

export const sourceNeedsAttention = (source: DataSource) => {
  const state = sourceState(source)
  return state === 'failed' || state === 'stale' || source.problemCount > 0
}

// Без параметра открываем первый источник с проблемой: ошибка, устаревшие данные или записи для разбора.
export const initialSourcesTab = (sources: DataSource[]): SourcesTab => {
  const ordered = sourceTabs.flatMap((tab) => sources.filter((source) => source.source === tab.source))
  const problem = ordered.find(sourceNeedsAttention)
  return problem === undefined ? 'website' : tabOfSource(problem.source)
}

export const syncBlockReason = (source: DataSource): string | null => {
  if (!source.adapterAvailable) {
    return 'Адаптер источника ещё не подключён.'
  }
  if (!source.configured) {
    const what: Record<SourceCode, string> = {
      MOODLE: 'адрес, токен и курсы Moodle',
      WEBSITE: 'адрес сайта',
      CUSTOMER_CRM: 'адрес CRM заказчика',
      LABOR_MARKET: 'адрес источника'
    }
    return `Источник не настроен: задайте ${what[source.source]} в «Настройках подключения».`
  }
  return isRunActive(source.lastRun) ? 'Синхронизация уже выполняется.' : null
}

// Токен привязан к адресу: при смене адреса сохранённый токен на новый адрес не отправляется.
export const tokenMustBeRetyped = (draftUrl: string, savedUrl: string | null | undefined, draftToken: string) => (
  draftUrl.trim() !== '' && draftUrl.trim() !== (savedUrl ?? '') && draftToken.trim() === ''
)

export const tokenRule = 'Токен хранится зашифрованным и не показывается: оставьте поле пустым, чтобы не менять его. '
  + 'При смене адреса токен нужно ввести заново: сохранённый токен на новый адрес не отправляется.'

export type SourceAlert = { kind: 'failed' | 'stale' | 'unavailable'; text: string; href: string; more: number }

// Одна строка вместо баннера: первый сбой, иначе первый устаревший источник, иначе недоступность состояния.
export const sourceAlertOf = (sources: DataSource[], unavailable: boolean): SourceAlert | null => {
  const failed = sources.filter((source) => source.configured && source.lastRun?.status === 'FAILED')
  const stale = sources.filter((source) => source.configured && source.stale === true && source.lastRun?.status !== 'FAILED')
  const total = failed.length + stale.length
  if (failed.length > 0) {
    return { kind: 'failed', text: `Сбой синхронизации «${failed[0].title}» — подробнее`, href: sourcesHash(tabOfSource(failed[0].source)), more: total - 1 }
  }
  if (stale.length > 0) {
    return { kind: 'stale', text: `Данные «${stale[0].title}» устарели — подробнее`, href: sourcesHash(tabOfSource(stale[0].source)), more: total - 1 }
  }
  return unavailable
    ? { kind: 'unavailable', text: 'Состояние источников данных не загрузилось — проверить', href: sourcesBaseHash, more: 0 }
    : null
}
