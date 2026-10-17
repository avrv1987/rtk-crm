import { useState } from 'react'
import type { FilterOption } from '../reports/ReportControls'
import './control.css'

export type ActiveChip = { key: string; label: string; onRemove: () => void }

export const FilterChips = ({ chips, onReset }: { chips: ActiveChip[]; onReset: () => void }) => (
  chips.length === 0 ? null : (
    <ul className="filter-chips" aria-label="Выбранные фильтры">
      {chips.map((chip) => (
        <li key={chip.key}>
          <span className="filter-chips__chip">
            {chip.label}
            <button type="button" className="filter-chips__remove" aria-label={`Убрать: ${chip.label}`} onClick={chip.onRemove}>×</button>
          </span>
        </li>
      ))}
      {chips.length > 1 && (
        <li><button type="button" className="button--secondary filter-chips__reset" onClick={onReset}>Сбросить фильтры</button></li>
      )}
    </ul>
  )
)

type CheckListProps = {
  label: string
  options: FilterOption[]
  selected: string[]
  onChange: (ids: string[]) => void
  extra?: { id: string; label: string }
  note?: string
}

const searchFrom = 8

export const CheckList = ({ label, options, selected, onChange, extra, note }: CheckListProps) => {
  const [search, setSearch] = useState('')
  const needle = search.trim().toLocaleLowerCase('ru-RU')
  const shown = needle === '' ? options : options.filter((option) => option.label.toLocaleLowerCase('ru-RU').includes(needle))
  const toggle = (id: string, checked: boolean) => onChange(checked ? [...selected, id] : selected.filter((value) => value !== id))
  const all = extra === undefined ? shown : [extra, ...shown]
  return (
    <fieldset className="check-list">
      <legend>{label}{selected.length > 0 && <span className="check-list__count">{` · ${selected.length}`}</span>}</legend>
      {options.length >= searchFrom && (
        <input type="search" aria-label={`Найти: ${label}`} placeholder="Найти" value={search} onChange={(event) => setSearch(event.target.value)} />
      )}
      <div className="check-list__options">
        {all.length === 0 && <span className="control-muted">Ничего не найдено</span>}
        {all.map((option) => (
          <label key={option.id} className="checkbox-field">
            <input type="checkbox" checked={selected.includes(option.id)} onChange={(event) => toggle(option.id, event.target.checked)} />
            {option.label}
          </label>
        ))}
      </div>
      {note && <span className="control-muted">{note}</span>}
    </fieldset>
  )
}

export const labelsOf = (ids: string[], options: FilterOption[], fallback: string) => (
  ids.map((id) => options.find((option) => option.id === id)?.label ?? fallback)
)
