import { useId } from 'react'
import type { ReportColumn, ReportKind } from '../../shared/api/client'
import { columnTitle, moveColumn } from './reportSelection'

type ColumnOrderProps = {
  kind: ReportKind
  columns: ReportColumn[]
  onChange: (columns: ReportColumn[]) => void
}

export const ColumnOrder = ({ kind, columns, onChange }: ColumnOrderProps) => {
  const titleId = useId()
  return (
    <div className="report-column-order">
      <p id={titleId} className="report-column-order__title">Порядок колонок в таблице и файле</p>
      {columns.length === 0 ? (
        <p className="reports__hint">Отметьте колонки, и они появятся здесь.</p>
      ) : (
        <ol aria-labelledby={titleId}>
          {columns.map((column, index) => {
            const title = columnTitle(kind, column)
            return (
              <li key={column}>
                <span>{title}</span>
                <button
                  type="button"
                  className="reports__secondary"
                  aria-label={`Выше: ${title}`}
                  disabled={index === 0}
                  onClick={() => onChange(moveColumn(columns, column, -1))}
                >
                  Выше
                </button>
                <button
                  type="button"
                  className="reports__secondary"
                  aria-label={`Ниже: ${title}`}
                  disabled={index === columns.length - 1}
                  onClick={() => onChange(moveColumn(columns, column, 1))}
                >
                  Ниже
                </button>
              </li>
            )
          })}
        </ol>
      )}
    </div>
  )
}
