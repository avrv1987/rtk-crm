import { useEffect, useRef, type ReactNode } from 'react'

type ToastProps = {
  message: ReactNode | null
  persistent?: boolean
  onClose: () => void
}

const toastLifetimeMs = 6000

export const Toast = ({ message, persistent = false, onClose }: ToastProps) => {
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  useEffect(() => {
    if (message === null || persistent) {
      return
    }
    const timer = window.setTimeout(() => closeRef.current(), toastLifetimeMs)
    return () => window.clearTimeout(timer)
  }, [message, persistent])

  return (
    <div className="toast-region" role="status" aria-live="polite">
      {message !== null && (
        <div className="toast">
          <span>{message}</span>
          <button type="button" className="toast__close" aria-label="Закрыть сообщение" onClick={onClose}>×</button>
        </div>
      )}
    </div>
  )
}
