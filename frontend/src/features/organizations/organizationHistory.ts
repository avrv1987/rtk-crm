import type { OrganizationHistoryItem, OrganizationHistoryKind } from '../../shared/api/client'

export type HistoryGroup = 'ALL' | 'CREATED' | 'STAGES' | 'COMMENTS' | 'PLANS' | 'MARKS' | 'DOCUMENTS' | 'ASSIGNMENT' | 'CONTACTS'

export const historyGroups: Array<{ id: HistoryGroup; label: string; kinds: OrganizationHistoryKind[] }> = [
  { id: 'ALL', label: 'Все события', kinds: [] },
  { id: 'CREATED', label: 'Новые взаимодействия', kinds: ['CREATED'] },
  { id: 'STAGES', label: 'Этапы', kinds: ['TRANSITIONED', 'STAGES_EDITED', 'STAGE_COMPLETED', 'STAGE_COMPLETION_CLEARED'] },
  { id: 'COMMENTS', label: 'Комментарии', kinds: ['COMMENTED'] },
  { id: 'PLANS', label: 'Планы и шаги', kinds: ['PLAN_UPDATED'] },
  { id: 'MARKS', label: 'Статус и данные взаимодействия', kinds: ['STATUS_CHANGED', 'DETAILS_UPDATED'] },
  { id: 'DOCUMENTS', label: 'Договоры и документы', kinds: ['AGREEMENT_STATUS', 'AGREEMENT_UPDATED', 'ATTACHMENT_DELETED'] },
  { id: 'ASSIGNMENT', label: 'Назначения и передачи', kinds: ['ASSIGNMENT'] },
  { id: 'CONTACTS', label: 'Контакты', kinds: ['CONTACT'] }
]

export const kindsOfGroup = (group: HistoryGroup): OrganizationHistoryKind[] => (
  historyGroups.find((entry) => entry.id === group)?.kinds ?? []
)

const kindTitles: Record<OrganizationHistoryKind, string> = {
  CREATED: 'Взаимодействие создано',
  TRANSITIONED: 'Этап изменён',
  COMMENTED: 'Добавлен комментарий',
  STAGES_EDITED: 'Изменены этапы карточки',
  PLAN_UPDATED: 'Изменён план',
  DETAILS_UPDATED: 'Изменены данные взаимодействия',
  STATUS_CHANGED: 'Изменён статус взаимодействия',
  AGREEMENT_UPDATED: 'Изменены договор и передача',
  ATTACHMENT_DELETED: 'Удалён документ',
  STAGE_COMPLETED: 'Этап отмечен выполненным',
  STAGE_COMPLETION_CLEARED: 'Снята отметка выполнения этапа',
  ASSIGNMENT: 'Назначение ответственного',
  CONTACT: 'Изменён контакт',
  AGREEMENT_STATUS: 'Изменён статус договора с вузом'
}

export const historyTitle = (item: OrganizationHistoryItem): string => {
  switch (item.kind) {
    case 'TRANSITIONED':
      return item.fromStageName && item.stageName
        ? `${kindTitles.TRANSITIONED}: ${item.fromStageName} → ${item.stageName}`
        : kindTitles.TRANSITIONED
    case 'STAGE_COMPLETED':
    case 'STAGE_COMPLETION_CLEARED':
      return item.stageName ? `${kindTitles[item.kind]}: ${item.stageName}` : kindTitles[item.kind]
    case 'ASSIGNMENT':
    case 'AGREEMENT_STATUS':
      return item.description ?? kindTitles[item.kind]
    case 'CONTACT':
      return item.contactName ? `${kindTitles.CONTACT}: ${item.contactName}` : kindTitles.CONTACT
    default:
      return kindTitles[item.kind]
  }
}
