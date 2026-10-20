import { useEffect, useRef } from 'react'

const focusable = 'a[href], button:not([disabled]), summary, input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

// Н-А8: нативный <dialog> возвращает фокус на кнопку-источник, но после сохранения её строка часто исчезает
// (вуз получил КАМ — кнопки «Назначить КАМ» больше нет), и фокус падает в body. Тогда фокус ставится на первое
// доступное место ближайшего уцелевшего предка источника, в крайнем случае — на начало содержимого экрана.
// Следим недолго: строка исчезает после ответа сервера, а не в момент закрытия окна.
export const guardFocusAfterClose = (opener: HTMLElement, watchMs = 5000) => {
  const ancestors: HTMLElement[] = []
  for (let node = opener.parentElement; node !== null; node = node.parentElement) {
    ancestors.push(node)
  }
  const rescue = () => {
    if (opener.isConnected || (document.activeElement !== null && document.activeElement !== document.body)) {
      return false
    }
    const container = ancestors.find((node) => node.isConnected && node !== document.body && node !== document.documentElement)
    const target = container?.querySelector<HTMLElement>(focusable) ?? document.getElementById('app-main')
    target?.focus()
    return true
  }
  const observer = new MutationObserver(() => {
    if (rescue()) {
      observer.disconnect()
    }
  })
  observer.observe(document.body, { childList: true, subtree: true })
  const timer = window.setTimeout(() => observer.disconnect(), watchMs)
  return () => {
    window.clearTimeout(timer)
    observer.disconnect()
  }
}

// Вызывать до эффекта, который открывает окно: источник запоминается, пока фокус ещё на нём.
export const useFocusReturn = (open: boolean) => {
  const opener = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (open) {
      opener.current = document.activeElement instanceof HTMLElement && document.activeElement !== document.body
        ? document.activeElement
        : null
      return undefined
    }
    const source = opener.current
    opener.current = null
    return source === null ? undefined : guardFocusAfterClose(source)
  }, [open])
}
