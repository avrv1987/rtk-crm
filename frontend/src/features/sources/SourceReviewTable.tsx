import { useState, type ReactNode } from 'react'
import './sourcePage.css'

export type ReviewRow = {
  id: string
  title: string
  subtitle: string | null
  program: string | null
  stream: string | null
  state: string
  stateNote: string | null
  danger: boolean
  actionable?: boolean
}

type SourceReviewTableProps = {
  rows: ReviewRow[]
  ariaLabel: string
  titleHeader: string
  streamHeader: string
  expandedId: string | null
  onToggle: (id: string) => void
  renderForm: (row: ReviewRow) => ReactNode
}

const pageSize = 50

// Таблица записей для разбора: одна на администратора («Записи для разбора») и руководителя («Заявки с сайта»).
export const SourceReviewTable = ({ rows, ariaLabel, titleHeader, streamHeader, expandedId, onToggle, renderForm }: SourceReviewTableProps) => {
  const [shown, setShown] = useState(pageSize)
  return (
    <>
      <table className="src-table" aria-label={ariaLabel}>
        <thead>
          <tr>
            <th scope="col">{titleHeader}</th>
            <th scope="col">Программа</th>
            <th scope="col">{streamHeader}</th>
            <th scope="col">Состояние</th>
            <th scope="col"><span className="sr-only">Действие</span></th>
          </tr>
        </thead>
        <tbody>
          {rows.slice(0, shown).map((row) => {
            const open = expandedId === row.id
            return (
              <RowPair key={row.id} row={row} open={open} onToggle={onToggle} streamHeader={streamHeader}>
                {open && renderForm(row)}
              </RowPair>
            )
          })}
        </tbody>
      </table>
      {rows.length > shown && (
        <button type="button" className="button--secondary src-more" onClick={() => setShown(shown + pageSize)}>
          Показать ещё
        </button>
      )}
    </>
  )
}

const RowPair = ({ row, open, onToggle, streamHeader, children }: {
  row: ReviewRow
  open: boolean
  onToggle: (id: string) => void
  streamHeader: string
  children: ReactNode
}) => (
  <>
    <tr>
      <td className="src-cell__main">
        <strong>{row.title}</strong>
        {row.subtitle && <span className="src-cell__sub">{row.subtitle}</span>}
      </td>
      <td data-label="Программа">{row.program ?? '—'}</td>
      <td data-label={streamHeader}>{row.stream ?? '—'}</td>
      <td data-label="Состояние">
        <span className={row.danger ? 'src-error' : undefined}>{row.state}</span>
        {row.stateNote && <span className="src-cell__sub">{row.stateNote}</span>}
      </td>
      <td className="src-cell__actions">
        {row.actionable !== false && (
          <button type="button" className="button--secondary" aria-expanded={open} onClick={() => onToggle(row.id)}>
            {open ? 'Свернуть' : 'Разобрать'}
          </button>
        )}
      </td>
    </tr>
    {open && (
      <tr className="src-form-row">
        <td colSpan={5}>{children}</td>
      </tr>
    )}
  </>
)
