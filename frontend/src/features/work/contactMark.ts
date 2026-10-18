import type { components } from '../../shared/api/openapi'

type ContactMark = components['schemas']['InteractionContactMark']
export type ContactChannel = ContactMark['channel']

export const contactChannels: readonly ContactChannel[] = ['MEETING', 'CALL', 'EMAIL', 'MESSENGER', 'EVENT']

export const contactChannelLabels: Record<ContactChannel, string> = {
  MEETING: 'Встреча',
  CALL: 'Звонок',
  EMAIL: 'Письмо',
  MESSENGER: 'Мессенджер',
  EVENT: 'Мероприятие'
}

// Черновик отметки: null — контакта не было; channel '' — галочка стоит, канал ещё не выбран.
export type ContactMarkDraft = { channel: ContactChannel | ''; participantIds: string[] } | null

export const contactMarkMissingChannel = (draft: ContactMarkDraft) => draft !== null && draft.channel === ''

export const contactMarkPayload = (draft: ContactMarkDraft): ContactMark | null => (
  draft === null || draft.channel === '' ? null : { channel: draft.channel, participantIds: draft.participantIds }
)

export const toggleParticipant = (draft: ContactMarkDraft, contactId: string): ContactMarkDraft => {
  if (draft === null) {
    return draft
  }
  return {
    ...draft,
    participantIds: draft.participantIds.includes(contactId)
      ? draft.participantIds.filter((id) => id !== contactId)
      : [...draft.participantIds, contactId]
  }
}

// Порог «нет контакта больше N дней» задаёт сам сотрудник; хранится только в этом браузере.
export const defaultNoContactDays = 30
const noContactDaysKey = 'rtk-crm.work.no-contact-days'

export const readNoContactDays = (storage: Pick<Storage, 'getItem'> | null): number => {
  try {
    const value = Number(storage?.getItem(noContactDaysKey))
    return Number.isInteger(value) && value >= 1 && value <= 3650 ? value : defaultNoContactDays
  } catch {
    return defaultNoContactDays
  }
}

// false — хранилище недоступно (приватный режим): порог действует до перезагрузки страницы.
export const saveNoContactDays = (storage: Pick<Storage, 'setItem'> | null, days: number): boolean => {
  try {
    storage?.setItem(noContactDaysKey, String(days))
    return storage !== null
  } catch {
    return false
  }
}
