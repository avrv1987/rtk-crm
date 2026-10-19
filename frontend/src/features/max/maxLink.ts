import type { MaxLinkStatus, MaxPreferences } from '../../shared/api/client'
import type { Modules } from '../../shared/modules'

type Profile = { role: string; enrolmentOperator: boolean }

/** Пункт «Подключить MAX» виден только при включённом модуле КАМ и руководителю без флага менеджера учебного процесса. */
export const maxLinkAvailable = (profile: Profile, modules: Modules): boolean => (
  modules.max === true && (profile.role === 'USER' || profile.role === 'LEADER') && !profile.enrolmentOperator
)

/** Пока ссылка выдана и аккаунт не найден, экран раз в 3 секунды спрашивает состояние. */
export const pollNeeded = (state: MaxLinkStatus['state']): boolean => state === 'WAITING'

export const stateText: Record<MaxLinkStatus['state'], string> = {
  NONE: 'MAX не подключён.',
  WAITING: 'Откройте ссылку на телефоне с MAX и нажмите «Начать» в боте.',
  FOUND: 'Найден аккаунт MAX. Подключите его, только если это ваш аккаунт.',
  ACTIVE: 'MAX подключён: уведомления приходят в бот CRM.',
  STOPPED: 'Бот остановлен в MAX: уведомления не приходят. Снова нажмите «Начать» в боте, чтобы их включить.'
}

export const preferenceLabels: Record<keyof MaxPreferences, string> = {
  digest: 'Утренняя сводка в 9:00 по рабочим дням',
  assignment: 'Мне назначили или передали вуз',
  agreement: 'Договор вернули на доработку, срок договора истекает',
  siteRequest: 'Новая заявка с сайта (для руководителя команды)'
}

export const defaultPreferences: MaxPreferences = { digest: true, assignment: true, agreement: true, siteRequest: true }
