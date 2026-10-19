import type { AgentPlan, AgentPlanKind } from '../../shared/api/client'

export const kindLabels: Record<AgentPlanKind, string> = {
  NOTE_UPDATE: 'Итог и следующий шаг',
  STAGE_TRANSITION: 'Переход этапа',
  ISSUE_CREATE: 'Проблема или риск',
  ISSUE_RESOLVE: 'Решение проблемы',
  STATUS_CHANGE: 'Пауза или возобновление'
}

const appliedTexts: Record<AgentPlanKind, string> = {
  NOTE_UPDATE: 'Записано: комментарий и новый следующий шаг сохранены во взаимодействии.',
  STAGE_TRANSITION: 'Записано: взаимодействие переведено на новый этап, обоснование сохранено в истории.',
  ISSUE_CREATE: 'Записано: запись добавлена в «Проблемы и риски», ответственный получит уведомление.',
  ISSUE_RESOLVE: 'Записано: запись отмечена решённой.',
  STATUS_CHANGE: 'Записано: статус взаимодействия изменён.'
}

export const warningTexts: Record<string, string> = {
  BACKWARD: 'Возврат на прежний этап: обоснование будет записано как причина возврата.',
  SKIP: 'Переход через этап: промежуточные этапы останутся невыполненными.',
  PAST_DUE: 'Срок в плане уже прошёл.'
}

/** Итог плана после решения; для ожидающего плана — подсказка, что запись делает сам сотрудник. */
export const statusText = (plan: Pick<AgentPlan, 'status' | 'cardChanged'> & { kind?: AgentPlanKind }): string => {
  switch (plan.status) {
    case 'PENDING':
      return plan.cardChanged
        ? 'Карточку изменили после подготовки плана: записать его не получится. Отклоните план и попросите агента подготовить новый.'
        : 'Проверьте, что запишется. Агент сам записать не может — запись делает CRM, когда вы нажмёте «Записать».'
    case 'APPLIED':
      return appliedTexts[plan.kind ?? 'NOTE_UPDATE']
    case 'REJECTED':
      return 'План отклонён, взаимодействие не изменилось.'
    case 'EXPIRED':
      return 'Срок плана истёк, взаимодействие не изменилось. Попросите агента подготовить план заново.'
    case 'STALE':
      return 'Не записано: карточку изменили после подготовки плана. Взаимодействие не изменилось.'
    case 'FAILED':
      return 'Не записано: CRM не приняла запись. Взаимодействие не изменилось.'
  }
}

export const canDecide = (plan: Pick<AgentPlan, 'status'>): boolean => plan.status === 'PENDING'

export const planStatusLabels: Record<AgentPlan['status'], string> = {
  PENDING: 'Ждёт подтверждения',
  APPLIED: 'Записан',
  REJECTED: 'Отклонён',
  EXPIRED: 'Истёк',
  STALE: 'Карточку изменили',
  FAILED: 'Не записан'
}
