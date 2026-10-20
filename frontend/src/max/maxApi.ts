import type { components } from '../shared/api/openapi'

export type MaxAppSession = components['schemas']['MaxAppSession']
export type MaxAppWork = components['schemas']['MaxAppWork']
export type MaxAppCard = components['schemas']['MaxAppCard']
export type MaxAppComment = components['schemas']['MaxAppComment']
export type MaxAppStepCompletion = components['schemas']['MaxAppStepCompletion']
export type TeamIndicators = components['schemas']['TeamIndicators']
export type Interaction = components['schemas']['Interaction']
export type Due = MaxAppWork['due']

// Н6 (2.5.3): завершённую работу сервер не меняет — «Шаг выполнен» и комментарий отвечают 409. Кнопки скрываем,
// пока работу не вернут в работу в CRM.
export const cardActions = (card: Pick<MaxAppCard, 'interaction'>) => {
  const open = card.interaction.marks.status !== 'COMPLETED'
  const hasStep = card.interaction.nextAction !== null || card.interaction.nextActionAt !== null
  return { step: open && hasStep, comment: open }
}

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
    // Без due сервер сам выбирает первую непустую группу и называет её в ответе (поле due).
    work: (due: Due | null, page: number, signal?: AbortSignal) => call<MaxAppWork>(
      `/api/max/app/work?${due === null ? '' : `due=${due}&`}page=${page}`, { signal }
    ),
    card: (id: string, signal?: AbortSignal) => call<MaxAppCard>(`/api/max/app/interactions/${encodeURIComponent(id)}`, { signal }),
    comment: (id: string, body: MaxAppComment, key: string) => send<Interaction>(`/api/max/app/interactions/${encodeURIComponent(id)}/comments`, body, key),
    completeStep: (id: string, body: MaxAppStepCompletion, key: string) => send<Interaction>(
      `/api/max/app/interactions/${encodeURIComponent(id)}/step-completions`, body, key
    ),
    control: (signal?: AbortSignal) => call<TeamIndicators>('/api/max/app/control', { signal })
  }
}

export type MaxApi = ReturnType<typeof createMaxApi>

export type ProblemAction = 'close' | 'crm' | 'retry'
export type Problem = { title: string; text: string; action: ProblemAction }

/**
 * Экран вместо приложения, если сеанс не открылся или пропал посреди работы (повторный вход после 401 не прошёл).
 * null — ошибка не фатальная: её показывает плашка с «Повторить» на месте списка.
 */
export const problemOf = (error: unknown, atStart: boolean): Problem | null => {
  if (!(error instanceof MaxApiError)) {
    return atStart ? { title: 'Нет связи с CRM', text: 'Проверьте интернет и попробуйте снова.', action: 'retry' } : null
  }
  switch (error.code) {
    case 'MAX_INIT_DATA_INVALID':
      return { title: 'Откройте из бота', text: 'Откройте мини-приложение кнопкой «Открыть» в сообщении бота CRM в MAX.', action: 'close' }
    case 'MAX_INIT_DATA_EXPIRED':
      return {
        title: 'Сеанс закончился',
        text: 'Так бывает после 30 минут без действий. Закройте приложение и откройте снова из бота CRM.',
        action: 'close'
      }
    case 'MAX_NOT_LINKED':
      return {
        title: 'MAX не подключён к CRM',
        text: 'Откройте CRM на компьютере: меню профиля → «Подключить MAX». Потом вернитесь сюда.',
        action: 'crm'
      }
    case 'MAX_ROLE_NOT_ALLOWED':
      return { title: 'Нет доступа', text: 'Мини-приложение доступно КАМ и руководителю команды. Работайте в основной CRM.', action: 'crm' }
    default:
      if (!atStart) {
        return null
      }
      return error.code === 'NOT_FOUND'
        ? { title: 'Недоступно', text: 'Мини-приложение MAX в этой установке CRM не включено.', action: 'close' }
        : { title: 'Не удалось войти', text: error.message, action: 'retry' }
  }
}

/** Текст плашки для нефатальной ошибки. */
export const errorText = (error: unknown) => (
  error instanceof MaxApiError ? error.message : 'Не удалось связаться с CRM. Проверьте интернет.'
)

export const isAbort = (error: unknown) => error instanceof DOMException && error.name === 'AbortError'
