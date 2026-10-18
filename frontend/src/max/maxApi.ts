import type { components } from '../shared/api/openapi'

export type MaxAppSession = components['schemas']['MaxAppSession']
export type MaxAppWork = components['schemas']['MaxAppWork']
export type MaxAppCard = components['schemas']['MaxAppCard']
export type MaxAppComment = components['schemas']['MaxAppComment']
export type MaxAppStepCompletion = components['schemas']['MaxAppStepCompletion']
export type TeamIndicators = components['schemas']['TeamIndicators']
export type Interaction = components['schemas']['Interaction']
export type Due = MaxAppWork['due']

export class MaxApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

type Fetch = (input: string, init?: RequestInit) => Promise<Response>

/**
 * Клиент мини-приложения. Токен сеанса живёт только в памяти этого объекта и уходит заголовком Bearer; cookie не
 * используются. На 401 клиент один раз берёт новый сеанс по той же initData (она годна час) и повторяет запрос;
 * повтор изменения безопасен, потому что Idempotency-Key остаётся тем же.
 */
export const createMaxApi = (initData: string, fetchImpl: Fetch = (input, init) => fetch(input, init)) => {
  let session: MaxAppSession | null = null

  const parse = async (response: Response) => {
    const text = await response.text()
    const body: unknown = text === '' ? null : JSON.parse(text)
    if (!response.ok) {
      const error = (body ?? {}) as { code?: string; message?: string }
      throw new MaxApiError(response.status, error.code ?? 'INTERNAL_ERROR', error.message ?? 'Сервер CRM не ответил. Повторите позже.')
    }
    return body
  }

  const start = async (): Promise<MaxAppSession> => {
    session = (await parse(await fetchImpl('/api/max/app/session', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ initData })
    }))) as MaxAppSession
    return session
  }

  const call = async <T>(path: string, init: RequestInit = {}, retried = false): Promise<T> => {
    if (session === null) {
      await start()
    }
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    headers.set('Authorization', `Bearer ${session!.token}`)
    const response = await fetchImpl(path, { ...init, headers })
    if (response.status === 401 && !retried) {
      session = null
      return call<T>(path, init, true)
    }
    return (await parse(response)) as T
  }

  const send = <T>(path: string, body: object, idempotencyKey: string) => call<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  })

  return {
    start,
    current: () => session,
    work: (due: Due, page: number) => call<MaxAppWork>(`/api/max/app/work?due=${due}&page=${page}`),
    card: (id: string) => call<MaxAppCard>(`/api/max/app/interactions/${encodeURIComponent(id)}`),
    comment: (id: string, body: MaxAppComment, key: string) => send<Interaction>(`/api/max/app/interactions/${encodeURIComponent(id)}/comments`, body, key),
    completeStep: (id: string, body: MaxAppStepCompletion, key: string) => send<Interaction>(
      `/api/max/app/interactions/${encodeURIComponent(id)}/step-completions`, body, key
    ),
    control: () => call<TeamIndicators>('/api/max/app/control')
  }
}

export type MaxApi = ReturnType<typeof createMaxApi>

/** Что показать вместо приложения, если вход не удался. */
export const startupProblem = (error: unknown): string => {
  if (!(error instanceof MaxApiError)) {
    return 'Не удалось связаться с CRM. Проверьте интернет и откройте приложение снова.'
  }
  switch (error.code) {
    case 'MAX_NOT_LINKED':
      return 'Аккаунт MAX не подключён к CRM. Откройте CRM на компьютере: меню профиля → «Подключить MAX».'
    case 'MAX_ROLE_NOT_ALLOWED':
      return 'Мини-приложение доступно КАМ и руководителю команды. Работайте в основной CRM.'
    case 'MAX_INIT_DATA_EXPIRED':
      return 'Приложение открыто слишком давно. Закройте его и откройте снова из бота CRM.'
    case 'MAX_INIT_DATA_INVALID':
      return 'Откройте мини-приложение из бота CRM в MAX.'
    case 'NOT_FOUND':
      return 'Мини-приложение MAX в этой установке CRM не включено.'
    default:
      return error.message
  }
}
