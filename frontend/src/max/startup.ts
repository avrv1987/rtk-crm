// Запуск мини-приложения MAX. MAX кладёт данные запуска во фрагмент адреса (#WebAppData=…), поэтому initData
// читается до любой маршрутизации, а сама маршрутизация живёт в памяти и адрес не трогает.

export type BackButton = {
  show: () => void
  hide: () => void
  onClick: (callback: () => void) => void
  offClick: (callback: () => void) => void
}

export type Bridge = {
  initData?: string
  platform?: string
  BackButton?: BackButton
  enableClosingConfirmation?: () => void
  disableClosingConfirmation?: () => void
  openLink?: (url: string) => void
  // ready и close есть в скрипте моста, но не описаны в документации MAX: вызываются, только если есть.
  ready?: () => void
  close?: () => void
  HapticFeedback?: { notificationOccurred?: (type: 'success' | 'error' | 'warning') => void }
}

/** initData из моста MAX, а если мост не успел или недоступен (веб-версия) — из фрагмента адреса. */
export const readInitData = (bridge: Bridge | undefined, hash: string): string | null => {
  const fromBridge = bridge?.initData?.trim()
  if (fromBridge) {
    return fromBridge
  }
  const fromHash = new URLSearchParams(hash.replace(/^#/, '')).get('WebAppData')
  return fromHash === null || fromHash.trim() === '' ? null : fromHash
}

export type Route =
  | { screen: 'work' }
  | { screen: 'card'; id: string }
  | { screen: 'comment'; id: string }
  | { screen: 'step'; id: string }

const interactionParam = /^i_([0-9a-f]{32})$/

const uuidOf = (hex: string) => `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`

/**
 * Первый экран по payload кнопки (start_param, уже проверенный сервером): «i_<uuid без дефисов>» — карточка поверх
 * «Моей работы», чтобы «Назад» вёл в неё; иначе «Моя работа».
 */
export const initialStack = (startParam: string | null | undefined): Route[] => {
  const match = startParam ? interactionParam.exec(startParam) : null
  return match ? [{ screen: 'work' }, { screen: 'card', id: uuidOf(match[1]) }] : [{ screen: 'work' }]
}

export type Section = 'work' | 'team'

/** «control» у руководителя открывает раздел «Команда» — без стека, «Назад» там не нужен. */
export const initialSection = (startParam: string | null | undefined, leader: boolean): Section => (
  startParam === 'control' && leader ? 'team' : 'work'
)

export const push = (stack: Route[], route: Route): Route[] => [...stack, route]

export const pop = (stack: Route[]): Route[] => (stack.length > 1 ? stack.slice(0, -1) : stack)

/** Своя кнопка «Назад» — только когда системной BackButton MAX нет (веб без моста), иначе их было бы две. */
export const ownBackButton = (bridge: Bridge | undefined, depth: number) => depth > 1 && bridge?.BackButton === undefined

/** Срок шага из полей даты и времени (вид YYYY-MM-DDTHH:mm): время московское, как во всей CRM. */
export const moscowDateTime = (local: string): string | null => (
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(local) ? `${local}:00+03:00` : null
)

export type NextStepErrors = { nextAction?: string; nextActionAt?: string }

/** Шаг обязателен целиком: сервер стирает поле шага, которого нет в запросе. */
export const nextStepErrors = (nextAction: string, nextActionAt: string): NextStepErrors => {
  const errors: NextStepErrors = {}
  if (nextAction.trim() === '') {
    errors.nextAction = 'Укажите следующий шаг'
  } else if (nextAction.trim().length > 500) {
    errors.nextAction = 'Не длиннее 500 символов'
  }
  if (moscowDateTime(nextActionAt) === null) {
    errors.nextActionAt = 'Укажите срок'
  }
  return errors
}
