import type { MailJournalRow, MailSettings, MailSettingsUpdate } from '../../shared/api/client'
import type { Modules } from '../../shared/modules'

type Profile = { role: string; enrolmentOperator: boolean }

/** Пункт «Уведомления на почту» — при включённом модуле всем сотрудникам, кроме представителя вуза и оператора зачисления. */
export const mailPreferencesAvailable = (profile: Profile, modules: Modules): boolean => (
  modules.mail === true && profile.role !== 'PARTNER' && !profile.enrolmentOperator
)

export const statusText: Record<MailJournalRow['status'], string> = {
  QUEUED: 'в очереди',
  SENT: 'отправлено',
  ERROR: 'ошибка',
  DROPPED: 'не отправлено',
  CANCELLED: 'отменено'
}

/** «Отправлено (заглушка)»: письмо собрано целиком и лежит в журнале, наружу не уходило. */
export const rowStatusText = (row: Pick<MailJournalRow, 'status' | 'transport'>): string => (
  row.status === 'SENT' && row.transport === 'STUB' ? 'отправлено (заглушка)' : statusText[row.status]
)

export const passwordStateText: Record<MailSettings['passwordState'], string> = {
  NONE: 'не задан',
  SET: 'задан',
  UNREADABLE: 'не читается: сменился ключ шифрования, введите пароль заново'
}

export const securityText: Record<MailSettings['security'], string> = {
  NONE: 'без шифрования',
  STARTTLS: 'STARTTLS',
  TLS: 'TLS (SSL)'
}

/** Обычный порт для выбранного шифрования: подставляется, пока администратор не ввёл свой. */
export const defaultPort = (security: MailSettings['security']): number => (
  security === 'TLS' ? 465 : security === 'STARTTLS' ? 587 : 25
)

export type MailDraft = {
  mode: MailSettings['mode']
  host: string
  port: string
  security: MailSettings['security']
  username: string
  password: string
  clearPassword: boolean
  fromAddress: string
  fromName: string
}

export const draftOf = (settings: MailSettings): MailDraft => ({
  mode: settings.mode,
  host: settings.host ?? '',
  port: settings.port === null || settings.port === undefined ? '' : String(settings.port),
  security: settings.security,
  username: settings.username ?? '',
  password: '',
  clearPassword: false,
  fromAddress: settings.fromAddress,
  fromName: settings.fromName
})

/** Пустой пароль не отправляется: сервер оставляет прежний. */
export const updateOf = (draft: MailDraft): MailSettingsUpdate => {
  const port = draft.port.trim() === '' ? null : Number(draft.port)
  return {
    mode: draft.mode,
    host: draft.host.trim() === '' ? null : draft.host.trim(),
    port: port === null || Number.isNaN(port) ? null : port,
    security: draft.security,
    username: draft.username.trim() === '' ? null : draft.username.trim(),
    password: draft.password === '' ? null : draft.password,
    clearPassword: draft.clearPassword,
    fromAddress: draft.fromAddress.trim(),
    fromName: draft.fromName.trim()
  }
}

/** Токен приглашения из адреса #/invite/<токен>; null — это не адрес приглашения. */
export const invitationToken = (hash: string): string | null => {
  const match = /^#\/invite\/([A-Za-z0-9_-]{20,100})$/.exec(hash)
  return match === null ? null : match[1]
}
