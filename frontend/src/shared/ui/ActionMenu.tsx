import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'

export type ActionMenuItem = {
  label: string
  onSelect?: () => void
  href?: string
  external?: boolean
  danger?: boolean
  separated?: boolean
  phoneOnly?: boolean
}

type ActionMenuProps = {
  label: string
  items: ActionMenuItem[]
  toggle?: ReactNode
  header?: ReactNode
  className?: string
}

export const ActionMenu = ({ label, items, toggle = '⋯', header, className }: ActionMenuProps) => {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLButtonElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const menuId = useId()

  const menuItems = () => [...(listRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])]
    .filter((item) => item.offsetParent !== null)

  useEffect(() => {
    if (!open) {
      return
    }
    menuItems()[0]?.focus()
    const closeOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [open])

  const close = (returnFocus: boolean) => {
    setOpen(false)
    if (returnFocus) {
      toggleRef.current?.focus()
    }
  }

  const moveFocus = (event: KeyboardEvent<HTMLDivElement>) => {
    const list = menuItems()
    const index = list.indexOf(document.activeElement as HTMLElement)
    const next: Record<string, number> = {
      ArrowDown: (index + 1) % list.length,
      ArrowUp: (index - 1 + list.length) % list.length,
      Home: 0,
      End: list.length - 1
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      close(true)
    } else if (event.key === 'Tab') {
      close(false)
    } else if (event.key in next && list.length > 0) {
      event.preventDefault()
      list[next[event.key]].focus()
    }
  }

  if (items.length === 0) {
    return null
  }

  return (
    <div ref={rootRef} className={`action-menu${className ? ` ${className}` : ''}`}>
      <button
        ref={toggleRef}
        type="button"
        className="action-menu__toggle"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={menuId}
        aria-label={label}
        title={label}
        onClick={() => setOpen((value) => !value)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' && !open) {
            event.preventDefault()
            setOpen(true)
          }
        }}
      >
        {toggle}
      </button>
      <div className="action-menu__popup" hidden={!open} onKeyDown={moveFocus}>
        {header && <div className="action-menu__header">{header}</div>}
        <div ref={listRef} id={menuId} role="menu" aria-label={label}>
          {items.map((item) => {
            const itemClass = [
              'action-menu__item',
              item.danger ? 'action-menu__item--danger' : '',
              item.separated ? 'action-menu__item--separated' : '',
              item.phoneOnly ? 'action-menu__item--phone' : ''
            ].filter(Boolean).join(' ')
            return item.href === undefined ? (
              <button
                key={item.label}
                type="button"
                role="menuitem"
                tabIndex={-1}
                className={itemClass}
                onClick={() => {
                  close(true)
                  item.onSelect?.()
                }}
              >
                {item.label}
              </button>
            ) : (
              <a
                key={item.label}
                role="menuitem"
                tabIndex={-1}
                className={itemClass}
                href={item.href}
                target={item.external ? '_blank' : undefined}
                rel={item.external ? 'noopener noreferrer' : undefined}
                onClick={() => close(false)}
              >
                {item.label}
              </a>
            )
          })}
        </div>
      </div>
    </div>
  )
}
