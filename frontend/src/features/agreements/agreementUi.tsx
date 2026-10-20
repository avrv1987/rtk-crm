import { useCallback, useRef } from 'react'
import { ApiError, createIdempotencyKey } from '../../shared/api/client'
import { todayInMoscow } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import {
  agreementTerm,
  approvalColorClass,
  approvalColorLabels,
  approvalText,
  type AgreementApproval,
  type AgreementStatus
} from './agreementModel'
import { formatDate } from './agreementsApi'

export const useAccessErrorHandler = (
  onSessionExpired: () => void,
  onProfileUnavailable: (requestId: string) => void
) => useCallback((error: unknown) => {
  if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
    onSessionExpired()
    return true
  }
  if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
    onProfileUnavailable(error.requestId)
    return true
  }
  return false
}, [onProfileUnavailable, onSessionExpired])

export const useCommandKey = () => {
  const current = useRef<{ payload: string; key: string } | null>(null)
  return {
    keyFor: (payload: unknown) => {
      const serialized = JSON.stringify(payload)
      if (current.current?.payload !== serialized) {
        current.current = { payload: serialized, key: createIdempotencyKey() }
      }
      return current.current.key
    },
    settle: (error?: unknown) => {
      if (error === undefined || error instanceof ApiError) {
        current.current = null
      }
    }
  }
}

const fieldLabels: Record<string, string> = {
  number: 'Номер',
  validUntil: 'Действует до',
  concludedOn: 'Дата подписания',
  signedOn: 'Дата подписания',
  sentOn: 'Отправлен на согласование',
  status: 'Статус',
  toStatus: 'Новый статус',
  comment: 'Комментарий',
  agreement: 'Договор',
  universitySignerContactId: 'Подписант вуза',
  schoolSignerProfileId: 'Подписант школы',
  interactionId: 'Взаимодействие',
  programIds: 'Программы',
  productIds: 'Продукты',
  directionIds: 'Направления',
  subject: 'Предмет',
  file: 'Файл',
  supplementId: 'Допсоглашение',
  plannedKind: 'Вид плана',
  plannedOn: 'Плановая дата',
  fileAttachmentId: 'Документ из взаимодействия',
  kindId: 'Вид мероприятия',
  title: 'Мероприятие',
  plannedEnd: 'Плановое окончание',
  actualEnd: 'Фактическое окончание',
  responsibleProfileId: 'Ответственный',
  interactionIds: 'Взаимодействия',
  attachmentIds: 'Подтверждения',
  educationLevelId: 'Уровень образования',
  participants: 'Охват',
  unitId: 'Единица',
  plannedVolume: 'Количество по плану',
  actualVolume: 'Количество факт',
  cost: 'Стоимость',
  executorId: 'Исполнитель',
  documentNote: 'Подтверждающий документ',
  activityIds: 'Состав перечня',
  year: 'Год',
  respondedOn: 'Дата ответа вуза',
  rate: 'Ставка',
  effectiveFrom: 'Действует с',
  baseAmount: 'База',
  columns: 'Колонки',
  deadlines: 'Сроки',
  name: 'Название',
  to: 'Период',
  version: 'Версия'
}

export const errorMessage = (error: unknown, fallback: string) => {
  if (!(error instanceof ApiError)) {
    return 'Не удалось связаться с сервисом. Проверьте соединение и повторите попытку.'
  }
  if (error.code === 'VERSION_CONFLICT') {
    return `${error.message}. Обновите данные и повторите изменение.`
  }
  if (error.code === 'NOT_FOUND' || error.code === 'FORBIDDEN' || error.code === 'CONFIRMATION_LIMIT' || error.code === 'PAYLOAD_TOO_LARGE') {
    return error.message
  }
  if (error.code === 'VALIDATION_ERROR') {
    return 'Проверьте введённые данные.'
  }
  return fallback
}

type CommandErrorProps = {
  error: unknown
  fallback: string
  onRetry?: () => void
  retryLabel?: string
}

export const CommandError = ({ error, fallback, onRetry, retryLabel = 'Повторить' }: CommandErrorProps) => {
  const fieldErrors = error instanceof ApiError ? Object.entries(error.fieldErrors ?? {}) : []
  return (
    <div className="interaction-command-error" role="alert">
      <p>{errorMessage(error, fallback)}</p>
      {fieldErrors.length > 0 && (
        <ul>
          {/* Техническое имя поля (priority, body) пользователю ничего не говорит: без подписи показываем только текст. */}
          {fieldErrors.map(([field, text]) => <li key={field}>{fieldLabels[field] === undefined ? text : `${fieldLabels[field]}: ${text}`}</li>)}
        </ul>
      )}
      {error instanceof ApiError && <SupportDetails requestId={error.requestId} code={error.code} />}
      {onRetry && <button type="button" className="button--secondary" onClick={onRetry}>{retryLabel}</button>}
    </div>
  )
}

// Цвет согласования по порогам: норма, жёлтый или красный; подсказка — сторона, круг и дни.
export const ApprovalMark = ({ approval, prefix = '' }: { approval?: AgreementApproval | null; prefix?: string }) => (
  approval ? (
    <span className={approvalColorClass[approval.color]} title={approvalText(approval)}>
      {prefix}{approvalColorLabels[approval.color]}
    </span>
  ) : null
)

type TermMarkProps = {
  agreement: { status: AgreementStatus; validUntil?: string | null }
  noticeDays?: number
}

export const AgreementTermMark = ({ agreement, noticeDays }: TermMarkProps) => {
  const term = agreementTerm(agreement, todayInMoscow(), noticeDays)
  if (term === 'expired') {
    return <span className="status status--overdue agreement-term">срок истёк</span>
  }
  return term === 'expiring' ? <span className="status status--missing agreement-term">до {formatDate(agreement.validUntil)}</span> : null
}
