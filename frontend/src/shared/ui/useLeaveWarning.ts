import { useEffect } from 'react'

// Предупреждение об уходе с несохранённой формой, введённое которой нельзя хранить в черновике (персональные данные
// анкеты): закрытие или перезагрузка вкладки и переход по ссылке CRM спрашивают подтверждение. Кнопку «Назад» браузера
// при адресах с # отменить нельзя — для неё остаётся вопрос при закрытии вкладки.
export const useLeaveWarning = (active: boolean, message: string) => {
  useEffect(() => {
    if (!active) {
      return
    }
    const beforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    const click = (event: MouseEvent) => {
      const link = event.target instanceof Element ? event.target.closest('a[href^="#"]') : null
      if (link !== null && !window.confirm(message)) {
        event.preventDefault()
        event.stopPropagation()
      }
    }
    window.addEventListener('beforeunload', beforeUnload)
    document.addEventListener('click', click, true)
    return () => {
      window.removeEventListener('beforeunload', beforeUnload)
      document.removeEventListener('click', click, true)
    }
  }, [active, message])
}
