import type { CrmEventCounts, CrmEventKind, CrmEventStatus, CrmPreviewOutcome } from '../../shared/api/client'

export const crmRecordTypeLabels: Record<string, string> = {
  crm_organization: 'Организация из CRM заказчика',
  crm_contact: 'Контакт из CRM заказчика'
}

export const crmEventKindLabels: Record<CrmEventKind, string> = {
  STAGE_CHANGED: 'Смена этапа',
  NEXT_STEP_CHANGED: 'Следующий шаг',
  MANAGER_CHANGED: 'Ответственный менеджер',
  AGREEMENT_STATUS: 'Статус договора'
}

export const crmEventStatusLabels: Record<CrmEventStatus, string> = {
  QUEUED: 'В очереди',
  SENT: 'Отправлено',
  ERROR: 'Ошибка'
}

export const crmPreviewOutcomeLabels: Record<CrmPreviewOutcome, string> = {
  FOUND: 'Найдена в CRM',
  NOT_FOUND: 'Нет в CRM',
  CREATE: 'Будет создан',
  UPDATE: 'Будет дополнен',
  AMBIGUOUS: 'Совпадений несколько',
  ORGANIZATION_UNRESOLVED: 'Организация не сопоставлена',
  UNCHANGED: 'Уже загружена',
  RESTRICTED: 'Данные ограничены',
  INVALID: 'Ошибка в записи'
}

const needsAttention: CrmPreviewOutcome[] = ['NOT_FOUND', 'AMBIGUOUS', 'ORGANIZATION_UNRESOLVED', 'RESTRICTED', 'INVALID']

export const previewNeedsAttention = (outcome: CrmPreviewOutcome) => needsAttention.includes(outcome)

export const queueSummary = (counts: Pick<CrmEventCounts, 'queued' | 'sent' | 'error'>) => (
  `В очереди: ${counts.queued}, отправлено: ${counts.sent}, с ошибкой: ${counts.error}`
)

export const retryAvailable = (counts: Pick<CrmEventCounts, 'error'>) => counts.error > 0

export const retryHint = (counts: Pick<CrmEventCounts, 'error'>) => (
  counts.error > 0
    ? 'События с ошибкой не отправляются сами. Верните их в очередь: они уйдут при ближайшем запуске обмена.'
    : null
)
