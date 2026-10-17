import { useState } from 'react'
import { ConfirmDialog } from './ConfirmDialog'

type ConfirmButtonProps = {
  label: string
  title: string
  description: string
  confirmLabel?: string
  disabled?: boolean
  onConfirm: () => void
}

export const ConfirmButton = ({ label, title, description, confirmLabel = label, disabled, onConfirm }: ConfirmButtonProps) => {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button type="button" className="button--danger" disabled={disabled} onClick={() => setOpen(true)}>{label}</button>
      <ConfirmDialog
        open={open}
        title={title}
        description={description}
        confirmLabel={confirmLabel}
        onCancel={() => setOpen(false)}
        onConfirm={() => {
          setOpen(false)
          onConfirm()
        }}
      />
    </>
  )
}
