import { useId, type ReactNode } from 'react'
import { HintButton } from '../../shared/ui/HintButton'
import './sourcePage.css'

type SourceFieldProps = {
  label: string
  hint?: ReactNode
  note?: ReactNode
  error?: string
  wide?: boolean
  children: (id: string, describedBy: string | undefined) => ReactNode
}

// Знак «?» стоит рядом с подписью, а не внутри неё: иначе нажатие на подпись нажимало бы кнопку, а не поле.
export const SourceField = ({ label, hint, note, error, wide, children }: SourceFieldProps) => {
  const id = useId()
  const noteId = `${id}-note`
  const hasNote = Boolean(note) || Boolean(error)
  return (
    <div className={`src-field${wide ? ' src-connection__wide' : ''}`}>
      <div className="src-field__label">
        <label htmlFor={id}>{label}</label>
        {hint && <HintButton label={`Подсказка: ${label}`}>{hint}</HintButton>}
      </div>
      {children(id, hasNote ? noteId : undefined)}
      {error ? (
        <span id={noteId} className="src-error src-field__note">{error}</span>
      ) : (
        note && <span id={noteId} className="src-field__note">{note}</span>
      )}
    </div>
  )
}
