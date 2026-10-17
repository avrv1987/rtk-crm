import { useEffect, useId, useRef, useState, type ReactNode } from 'react'

type HintButtonProps = {
  label: string
  children: ReactNode
}

export const HintButton = ({ label, children }: HintButtonProps) => {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLSpanElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const panelId = useId()

  useEffect(() => {
    if (!open) {
      return
    }
    const closeOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [open])

  return (
    <span ref={rootRef} className="hint">
      <button
        ref={buttonRef}
        type="button"
        className="hint__toggle"
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={label}
        title={label}
        onClick={() => setOpen((value) => !value)}
      >
        ?
      </button>
      <span
        id={panelId}
        className="hint__panel"
        role="note"
        hidden={!open}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            setOpen(false)
            buttonRef.current?.focus()
          }
        }}
      >
        {children}
      </span>
    </span>
  )
}
