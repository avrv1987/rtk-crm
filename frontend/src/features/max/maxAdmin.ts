import type { MaxSettings } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime.ts'

export const tokenStateText: Record<MaxSettings['tokenState'], string> = {
  NONE: 'не задан',
  SCREEN: 'задан на экране',
  UNREADABLE: 'не читается: сменился ключ шифрования, введите токен заново'
}

/** Подписка снимается MAX после 8 часов без ответа; CRM сверяет её раз в час и подписывается заново. */
export const subscriptionText = (settings: Pick<MaxSettings, 'subscribedAt' | 'subscriptionOk' | 'subscriptionCheckedAt' | 'subscriptionError'>) => {
  if (settings.subscribedAt === null || settings.subscribedAt === undefined) {
    return 'не подписан'
  }
  const checked = settings.subscriptionCheckedAt ? `, проверено ${formatMoscowDateTime(settings.subscriptionCheckedAt)}` : ''
  if (settings.subscriptionOk === false) {
    return `ошибка: ${settings.subscriptionError ?? 'нет ответа MAX'}${checked}`
  }
  return `подписан ${formatMoscowDateTime(settings.subscribedAt)}${checked}`
}
