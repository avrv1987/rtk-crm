import type { OrganizationHistoryItem, OrganizationHistoryKind } from '../../shared/api/client'

export type HistoryGroup = 'ALL' | 'CREATED' | 'STAGES' | 'COMMENTS' | 'PLANS' | 'MARKS' | 'DOCUMENTS' | 'ASSIGNMENT' | 'CONTACTS'

export const historyGroups: Array<{ id: HistoryGroup; label: string; kinds: OrganizationHistoryKind[] }> = [
  { id: 'ALL', label: 'Все события', kinds: [] },
  { id: 'CREATED', label: 'Новые работы', kinds: ['CREATED'] },
  { id: 'STAGES', label: 'Этапы', kinds: ['TRANSITIONED', 'STAGES_EDITED', 'STAGE_COMPLETED', 'STAGE_COMPLETION_CLEARED'] },
  { id: 'COMMENTS', label: 'Комментарии', kinds: ['COMMENTED'] },
  { id: 'PLANS', label: 'Планы и шаги', kinds: ['PLAN_UPDATED'] },
  { id: 'MARKS', label: 'Статус и данные работы', kinds: ['STATUS_CHANGED', 'DETAILS_UPDATED'] },
  { id: 'DOCUMENTS', label: 'Договоры и документы', kinds: ['AGREEMENT_UPDATED', 'ATTACHMENT_DELETED'] },
  { id: 'ASSIGNMENT', label: 'Назначения и передачи', kinds: ['ASSIGNMENT'] },
  { id: 'CONTACTS', label: 'Контакты', kinds: ['CONTACT'] }
]

export const kindsOfGroup = (group: HistoryGroup): OrganizationHistoryKind[] => (
  historyGroups.find((entry) => entry.id === group)?.kinds ?? []
)

const kindTitles: Record<OrganizationHistoryKind, string> = {
  CREATED: 'Работа создана',
  TRANSITIONED: 'Этап изменён',
  COMMENTED: 'Добавлен комментарий',
  STAGES_EDITED: 'Изменены этапы карточки',
  PLAN_UPDATED: 'Изменён план',
  DETAILS_UPDATED: 'Изменены данные работы',
  STATUS_CHANGED: 'Изменён статус работы',
  AGREEMENT_UPDATED: 'Изменены договор и передача',
  ATTACHMENT_DELETED: 'Удалён документ',
  STAGE_COMPLETED: 'Этап отмечен выполненным',
  STAGE_COMPLETION_CLEARED: 'Снята отметка выполнения этапа',
  ASSIGNMENT: 'Назначение ответственного',
  CONTACT: 'Изменён контакт'
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
      return item.description ?? kindTitles.ASSIGNMENT
    case 'CONTACT':
      return item.contactName ? `${kindTitles.CONTACT}: ${item.contactName}` : kindTitles.CONTACT
    default:
      return kindTitles[item.kind]
  }
}
