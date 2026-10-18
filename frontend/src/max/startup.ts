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
  BackButton?: BackButton
  enableClosingConfirmation?: () => void
  disableClosingConfirmation?: () => void
  openLink?: (url: string) => void
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
  | { screen: 'control' }

const interactionParam = /^i_([0-9a-f]{32})$/

const uuidOf = (hex: string) => `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`

/**
 * Первый экран по payload кнопки (start_param, уже проверенный сервером): «i_<uuid без дефисов>» — карточка,
 * «control» — «Контроль» руководителя, иначе «Моя работа». Карточка открывается поверх ленты, чтобы «Назад» вёл в неё.
 */
export const initialStack = (startParam: string | null | undefined, canControl: boolean): Route[] => {
  const match = startParam ? interactionParam.exec(startParam) : null
  if (match) {
    return [{ screen: 'work' }, { screen: 'card', id: uuidOf(match[1]) }]
  }
  if (startParam === 'control' && canControl) {
    return [{ screen: 'work' }, { screen: 'control' }]
  }
  return [{ screen: 'work' }]
}

export const push = (stack: Route[], route: Route): Route[] => [...stack, route]

export const pop = (stack: Route[]): Route[] => (stack.length > 1 ? stack.slice(0, -1) : stack)

/** Срок шага из поля datetime-local: время московское, как во всей CRM. */
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
