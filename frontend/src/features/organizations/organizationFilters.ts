import type { Me } from '../../shared/api/client'

export type ListStatus = 'CURRENT' | 'PENDING' | 'ARCHIVED'

export type ListFilters = {
  q: string
  requiresAssignment: boolean
  status: ListStatus
  page: number
}

export const listStatuses: Array<{ value: ListStatus; label: string }> = [
  { value: 'CURRENT', label: 'Действующие' },
  { value: 'PENDING', label: 'Ожидают подтверждения' },
  { value: 'ARCHIVED', label: 'Архив' }
]

export const filtersFromQuery = (query: string, role: Me['role']): ListFilters => {
  const params = new URLSearchParams(query)
  const page = Number(params.get('page') ?? '0')
  const status = listStatuses.find((item) => item.value === params.get('status'))?.value ?? 'CURRENT'
  return {
    q: params.get('q') ?? '',
    requiresAssignment: role === 'LEADER' && params.get('requiresAssignment') === 'true',
    status,
    page: Number.isInteger(page) && page > 0 ? page : 0
  }
}

export const queryFromFilters = (filters: ListFilters) => {
  const params = new URLSearchParams()
  if (filters.q.trim().length > 0) {
    params.set('q', filters.q.trim())
  }
  if (filters.requiresAssignment) {
    params.set('requiresAssignment', 'true')
  }
  if (filters.status !== 'CURRENT') {
    params.set('status', filters.status)
  }
  if (filters.page > 0) {
    params.set('page', filters.page.toString())
  }
  return params.toString()
}

// Повтор помогает только при сбое связи или сервера. Неверный адрес (400) и недоступный вуз (404) повтором не
// исправить — кнопка «Повторить» там лишь вводила в заблуждение.
export const organizationLoadFailure = (status: number | undefined): { text: string; retry: boolean } => {
  if (status === 400) {
    return { text: 'Адрес карточки вуза неверный: такого вуза нет. Вернитесь к списку вузов.', retry: false }
  }
  if (status === 404) {
    return { text: 'Вуз не найден или недоступен вам: возможно, его передали другому КАМ или в другую команду. Вернитесь к списку вузов.', retry: false }
  }
  return { text: 'Не удалось открыть карточку вуза. Обновите список или повторите попытку.', retry: true }
}
