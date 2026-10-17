import { useState } from 'react'
import type { ReportColumn, ReportKind } from '../../shared/api/client'
import { terms } from '../../shared/ui/terms'
import { CardDialog } from '../interactions/cardUi'
import { columnRows, moveColumnTo, setColumnChecked } from './reportFilterModel'
import { availableColumns, columnTitle, moveColumn } from './reportSelection'

type ReportColumnsDialogProps = {
  open: boolean
  onClose: () => void
  kind: ReportKind
  selected: ReportColumn[]
  onChange: (columns: ReportColumn[]) => void
}

// Один список вместо двух: флажки включают колонку, порядок — перетаскиванием или кнопками «Выше»/«Ниже» (для клавиатуры).
export const ReportColumnsDialog = ({ open, onClose, kind, selected, onChange }: ReportColumnsDialogProps) => {
  const [dragged, setDragged] = useState<ReportColumn | null>(null)
  const rows = columnRows(availableColumns(kind), selected)
  const chosenCount = selected.length

  return (
    <CardDialog open={open} title="Колонки и порядок" onClose={onClose}>
      <p className="reports__hint">Отметьте колонки таблицы и файла. Порядок отмеченных колонок меняется перетаскиванием или кнопками «Выше» и «Ниже».</p>
      {chosenCount === 0 && <p className="reports__problem" role="alert">Выберите хотя бы одну колонку.</p>}
      <ul className="report-columns-list">
        {rows.map(({ column, checked }, index) => {
          const title = columnTitle(kind, column)
          return (
            <li
              key={column}
              className={checked ? 'report-columns-list__row report-columns-list__row--chosen' : 'report-columns-list__row'}
              draggable={checked}
              onDragStart={() => setDragged(column)}
              onDragEnd={() => setDragged(null)}
              onDragOver={(event) => {
                if (dragged !== null && checked) {
                  event.preventDefault()
                }
              }}
              onDrop={(event) => {
                event.preventDefault()
                if (dragged !== null && checked) {
                  onChange(moveColumnTo(selected, dragged, column))
                }
                setDragged(null)
              }}
            >
              {index === chosenCount && chosenCount > 0 && <span className="report-columns-list__divider">Не выбраны</span>}
              <label className="report-filter-group__option">
                <input type="checkbox" checked={checked} onChange={(event) => onChange(setColumnChecked(selected, column, event.target.checked))} />
                <span>{title}</span>
              </label>
              {checked && (
                <span className="report-columns-list__move">
                  <button type="button" className="button--secondary" aria-label={`Выше: ${title}`} disabled={index === 0} onClick={() => onChange(moveColumn(selected, column, -1))}>Выше</button>
                  <button type="button" className="button--secondary" aria-label={`Ниже: ${title}`} disabled={index === chosenCount - 1} onClick={() => onChange(moveColumn(selected, column, 1))}>Ниже</button>
                </span>
              )}
            </li>
          )
        })}
      </ul>
      <div className="report-dialog-actions">
        <button type="button" disabled={chosenCount === 0} onClick={onClose}>{terms.done}</button>
      </div>
    </CardDialog>
  )
}
