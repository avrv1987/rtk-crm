import { type KeyboardEvent, type ReactNode, useEffect, useId, useRef } from 'react'
import './cardUi.css'

type CardDialogProps = {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
}

export const CardDialog = ({ open, title, onClose, children }: CardDialogProps) => {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = dialogRef.current
    if (dialog === null) {
      return
    }
    if (open && !dialog.open) {
      dialog.showModal()
    }
    if (!open && dialog.open) {
      dialog.close()
    }
  }, [open])

  return (
    <dialog
      ref={dialogRef}
      className="card-dialog"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
    >
      <div className="card-dialog__header">
        <h2 id={titleId}>{title}</h2>
        <button type="button" className="button--secondary card-dialog__close" onClick={onClose}>Закрыть</button>
      </div>
      {open && <div className="card-dialog__body">{children}</div>}
    </dialog>
  )
}

export type CardTab<T extends string> = {
  id: T
  label: string
  count?: number
}

type CardTabsProps<T extends string> = {
  label: string
  idPrefix: string
  tabs: CardTab<T>[]
  active: T
  onChange: (id: T) => void
}

export const CardTabs = <T extends string>({ label, idPrefix, tabs, active, onChange }: CardTabsProps<T>) => {
  const move = (event: KeyboardEvent<HTMLDivElement>) => {
    const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
    const edge = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : -1
    if (step === 0 && edge === -1) {
      return
    }
    event.preventDefault()
    const index = tabs.findIndex((tab) => tab.id === active)
    const next = tabs[edge >= 0 ? edge : (index + step + tabs.length) % tabs.length]
    onChange(next.id)
    document.getElementById(`${idPrefix}-tab-${next.id}`)?.focus()
  }

  return (
    <div className="card-tabs" role="tablist" aria-label={label} onKeyDown={move}>
      {tabs.map((tab) => (
        <button
          key={tab.id}
          id={`${idPrefix}-tab-${tab.id}`}
          type="button"
          role="tab"
          className="card-tabs__tab"
          aria-selected={tab.id === active}
          aria-controls={`${idPrefix}-panel`}
          tabIndex={tab.id === active ? 0 : -1}
          onClick={() => onChange(tab.id)}
        >
          {tab.label}
          {tab.count !== undefined && <span className="card-tabs__count">{tab.count}</span>}
        </button>
      ))}
    </div>
  )
}

export const CardTabPanel = ({ idPrefix, active, children }: { idPrefix: string; active: string; children: ReactNode }) => (
  <div id={`${idPrefix}-panel`} role="tabpanel" className="card-tabpanel" aria-labelledby={`${idPrefix}-tab-${active}`}>
    {children}
  </div>
)

export type CardMenuItem = {
  label: string
  onSelect: () => void
  danger?: boolean
}

export const CardMenu = ({ label, items }: { label: string; items: CardMenuItem[] }) => {
  const menuRef = useRef<HTMLDetailsElement>(null)

  useEffect(() => {
    const close = (event: Event) => {
      const menu = menuRef.current
      if (menu === null || !menu.open) {
        return
      }
      if (event instanceof globalThis.KeyboardEvent ? event.key === 'Escape' : !menu.contains(event.target as Node)) {
        menu.open = false
      }
    }
    document.addEventListener('click', close)
    document.addEventListener('keydown', close)
    return () => {
      document.removeEventListener('click', close)
      document.removeEventListener('keydown', close)
    }
  }, [])

  if (items.length === 0) {
    return null
  }

  return (
    <details ref={menuRef} className="card-menu">
      <summary className="card-menu__toggle" aria-label={label} title={label}>⋯</summary>
      <div className="card-menu__list">
        {items.map((item) => (
          <button
            key={item.label}
            type="button"
            className={item.danger ? 'card-menu__item card-menu__item--danger' : 'card-menu__item'}
            onClick={() => {
              if (menuRef.current !== null) {
                menuRef.current.open = false
              }
              item.onSelect()
            }}
          >
            {item.label}
          </button>
        ))}
      </div>
    </details>
  )
}

export const RequiredMark = () => <span className="required-mark" aria-hidden="true"> *</span>
