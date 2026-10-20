import { useCallback, useEffect, useRef, useState } from 'react'
import { isAbort, problemOf, type Problem } from './maxApi'

export type Load<T> =
  | { status: 'loading'; data: null }
  | { status: 'ready'; data: T }
  | { status: 'error'; data: null; error: unknown }

/**
 * Одна загрузка для всех экранов. Смена ключа отменяет прошлый запрос (AbortController), и поздний ответ старой группы
 * не ляжет под новую. Пока идёт запрос — только «loading», старые данные как данные новой группы не показываются.
 * Фатальные ошибки сеанса уходят наверх (onFatal), остальные — состояние «error» для плашки с «Повторить».
 */
export const useLoad = <T>(
  key: string | null,
  load: (signal: AbortSignal) => Promise<T>,
  onFatal: (problem: Problem) => void
): Load<T> & { reload: () => void } => {
  const [state, setState] = useState<Load<T>>({ status: 'loading', data: null })
  const [attempt, setAttempt] = useState(0)
  // Загрузчик и обработчик — в ref: новые функции на каждом рендере не должны перезапускать запрос.
  const loadRef = useRef(load)
  const fatalRef = useRef(onFatal)
  loadRef.current = load
  fatalRef.current = onFatal

  useEffect(() => {
    if (key === null) {
      return undefined
    }
    const controller = new AbortController()
    setState({ status: 'loading', data: null })
    loadRef.current(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) {
          setState({ status: 'ready', data })
        }
      },
      (error: unknown) => {
        if (controller.signal.aborted || isAbort(error)) {
          return
        }
        const problem = problemOf(error, false)
        if (problem !== null) {
          fatalRef.current(problem)
        }
        setState({ status: 'error', data: null, error })
      }
    )
    return () => controller.abort()
  }, [key, attempt])

  const reload = useCallback(() => setAttempt((value) => value + 1), [])
  return { ...state, reload }
}
