import type { AgentPlan } from '../../shared/api/client'

/** Итог плана после решения; для ожидающего плана — подсказка, что запись делает сам сотрудник. */
export const statusText = (plan: Pick<AgentPlan, 'status' | 'cardChanged'>): string => {
  switch (plan.status) {
    case 'PENDING':
      return plan.cardChanged
        ? 'Карточку изменили после подготовки плана: записать его не получится. Отклоните план и попросите агента подготовить новый.'
        : 'Проверьте, что запишется. Агент сам записать не может — запись делает CRM, когда вы нажмёте «Записать».'
    case 'APPLIED':
      return 'Записано: комментарий и новый следующий шаг сохранены во взаимодействии.'
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
