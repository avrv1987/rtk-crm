import { useEffect, useId, useRef, type ReactNode } from 'react'
import { terms } from './terms'

type FilterPanelProps = {
  open: boolean
  title?: string
  onClose: () => void
  onReset: () => void
  children: ReactNode
}

export const FilterButton = ({ count, onClick }: { count: number; onClick: () => void }) => (
  <button type="button" className="button--secondary filter-button" onClick={onClick}>
    {count > 0 ? `${terms.filters} · ${count}` : terms.filters}
  </button>
)

export const FilterPanel = ({ open, title = terms.filters, onClose, onReset, children }: FilterPanelProps) => {
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
      className="filter-panel"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
    >
      <div className="filter-panel__header">
        <h2 id={titleId}>{title}</h2>
        <button type="button" className="button--secondary filter-panel__close" aria-label={terms.close} onClick={onClose}>×</button>
      </div>
      <div className="filter-panel__body">{children}</div>
      <div className="filter-panel__actions">
        <button type="button" className="button--secondary" onClick={onReset}>{terms.resetFilters}</button>
        <button type="button" onClick={onClose}>{terms.done}</button>
      </div>
    </dialog>
  )
}
