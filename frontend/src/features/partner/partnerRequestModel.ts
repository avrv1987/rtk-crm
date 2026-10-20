export type RequestStatus = 'AWAITING_RESPONSE' | 'CLARIFICATION_NEEDED' | 'RESPONDED' | 'ACCEPTED' | 'RETURNED' | 'CANCELLED'

export const partnerRequestStatusLabels: Record<RequestStatus, string> = {
  AWAITING_RESPONSE: 'Ожидает ответа',
  CLARIFICATION_NEEDED: 'Нужны уточнения',
  RESPONDED: 'Ответ передан',
  ACCEPTED: 'Принято',
  RETURNED: 'Возвращено на доработку',
  CANCELLED: 'Отменено'
}

export const fileStateLabels: Record<'CHECKING' | 'CLEAN' | 'REJECTED', string> = {
  CHECKING: 'файл проверяется антивирусом',
  CLEAN: 'файл проверен',
  REJECTED: 'файл отклонён антивирусом'
}

// Кабинет координатора делит запросы на три блока: что нужно сделать вузу, что вуз уже отправил и ждёт проверки
// школой, и закрытые. «Нужны уточнения» — ход за школой, поэтому запрос лежит среди отправленных.
export const partnerRequestGroups = <T extends { status: RequestStatus }>(requests: T[]) => ({
  required: requests.filter((request) => request.status === 'AWAITING_RESPONSE' || request.status === 'RETURNED'),
  sent: requests.filter((request) => request.status === 'RESPONDED' || request.status === 'CLARIFICATION_NEEDED'),
  closed: requests.filter((request) => request.status === 'ACCEPTED' || request.status === 'CANCELLED')
})

// Н-А3: решение по перечню «На согласовании» тоже ждут от вуза — оно входит в «Требуется от вуза (N)».
export const partnerReviewLists = <T extends { summary: { status: string } }>(lists: T[] | null) => (
  (lists ?? []).filter((list) => list.summary.status === 'REVIEW')
)

export const partnerRequiredCount = (requiredRequests: number, lists: { summary: { status: string } }[] | null) => (
  requiredRequests + partnerReviewLists(lists).length
)

// Для КАМ: какие действия доступны по запросу в его статусе.
export const partnerRequestActions = (status: RequestStatus) => ({
  review: status === 'RESPONDED',
  clarify: status === 'CLARIFICATION_NEEDED',
  cancel: status !== 'ACCEPTED' && status !== 'CANCELLED'
})

// Пока школа не уточнила, координатор может ответить и сам: свой вопрос не запирает готовый ответ.
export const answerable = (status: RequestStatus) =>
  status === 'AWAITING_RESPONSE' || status === 'RETURNED' || status === 'CLARIFICATION_NEEDED'
