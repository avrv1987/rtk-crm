import type { AuditCategory, AuditEntry } from '../../shared/api/client'

export type AuditFilters = {
  from: string
  to: string
  actor: string
  object: string
  category: AuditCategory | ''
}

export const categoryLabels: Record<AuditCategory, string> = {
  PROFILE: 'Профили',
  ASSIGNMENT: 'Назначения КАМ',
  ORGANIZATION: 'Вузы: записи и переносы',
  TEAM: 'Команды',
  SYNC: 'Синхронизация источников',
  DOWNLOAD: 'Скачивания',
  PERSONAL_DATA: 'Действия с ПДн',
  RETENTION: 'Сроки хранения',
  ACCOUNT: 'Учётные записи Keycloak',
  LOGIN: 'Входы и выходы',
  LEARNER: 'Слушатели',
  AGENT: 'ИИ-агенты (MCP)',
  MESSENGER: 'Канал MAX',
  SETTINGS: 'Настройки и справочники',
  MAIL: 'Почта'
}

/** Подписи действий приходят с сервера (`actionLabel`); эти — запасные, если сервер подпись не прислал. */
export const fallbackActionLabels: Partial<Record<AuditEntry['action'], string>> = {
  LOGIN_SUCCEEDED: 'Вход в CRM',
  LOGIN_DENIED: 'Отказ во входе в CRM',
  LOGOUT: 'Выход из CRM',
  ACCOUNT_ROLE_RECONCILED: 'Роль в Keycloak сверена с CRM',
  PROFILES_EXPORTED: 'Выгружен список профилей CRM'
}

export const emptyAuditFilters: AuditFilters = { from: '', to: '', actor: '', object: '', category: '' }

export const actionText = (entry: Pick<AuditEntry, 'action' | 'actionLabel'>) => (
  entry.actionLabel.trim() || fallbackActionLabels[entry.action] || 'Действие'
)

/** Разделы для списка «Раздел»; «Слушатели» — только при включённом модуле зачисления. */
export const categoryOptions = (enrolmentEnabled: boolean): { value: AuditCategory; label: string }[] => (
  (Object.keys(categoryLabels) as AuditCategory[])
    .filter((category) => category !== 'LEARNER' || enrolmentEnabled)
    .map((category) => ({ value: category, label: categoryLabels[category] }))
)

/** Сколько условий задано (для кнопки «Фильтры · N» на телефоне). */
export const appliedFilterCount = (filters: AuditFilters) => (
  [filters.from, filters.to, filters.actor.trim(), filters.object.trim(), filters.category].filter((value) => value !== '').length
)
