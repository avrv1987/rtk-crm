import { useId, useState, type ReactNode } from 'react'
import type { FilterOption } from './ReportControls'

type FilterGroupProps = {
  label: string
  summary: string
  active: boolean
  children: ReactNode
}

// Группа панели «Фильтры»: заголовок с выбранным значением, список раскрывается по нажатию и в страницу не попадает, пока закрыт.
export const FilterGroup = ({ label, summary, active, children }: FilterGroupProps) => {
  const [open, setOpen] = useState(false)
  const bodyId = useId()
  return (
    <section className={active ? 'report-filter-group report-filter-group--active' : 'report-filter-group'}>
      <h3>
        <button type="button" className="report-filter-group__toggle" aria-expanded={open} aria-controls={bodyId} onClick={() => setOpen((value) => !value)}>
          <span className="report-filter-group__label">{label}</span>
          <span className="report-filter-group__value">{summary}</span>
          <span className="report-filter-group__caret" aria-hidden="true">{open ? '▴' : '▾'}</span>
        </button>
      </h3>
      {open && <div id={bodyId} className="report-filter-group__body">{children}</div>}
    </section>
  )
}

type MultiSelectGroupProps = {
  label: string
  options: FilterOption[]
  selected: string[]
  onChange: (ids: string[]) => void
  includeNone?: boolean
  onIncludeNoneChange?: (value: boolean) => void
  note?: string
}

export const selectionSummary = (options: FilterOption[], selected: string[], includeNone: boolean) => {
  const count = selected.length + (includeNone ? 1 : 0)
  if (count === 0) {
    return 'все'
  }
  if (count === 1) {
    return includeNone ? 'Не указано' : options.find((option) => option.id === selected[0])?.label ?? '1'
  }
  return `выбрано ${count}`
}

export const MultiSelectGroup = ({ label, options, selected, onChange, includeNone, onIncludeNoneChange, note }: MultiSelectGroupProps) => {
  const [search, setSearch] = useState('')
  const query = search.trim().toLocaleLowerCase('ru-RU')
  const visible = query.length === 0 ? options : options.filter((option) => option.label.toLocaleLowerCase('ru-RU').includes(query))
  const active = selected.length > 0 || includeNone === true

  return (
    <FilterGroup label={label} summary={selectionSummary(options, selected, includeNone === true)} active={active}>
      {note && <p className="report-filter-group__note">{note}</p>}
      {options.length > 6 && (
        <input
          type="search"
          className="report-filter-group__search"
          placeholder="Найти"
          aria-label={`Найти: ${label}`}
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      )}
      {onIncludeNoneChange && (
        <label className="report-filter-group__option">
          <input type="checkbox" checked={includeNone === true} onChange={(event) => onIncludeNoneChange(event.target.checked)} />
          <span>Не указано</span>
        </label>
      )}
      <ul className="report-filter-group__options">
        {visible.map((option) => (
          <li key={option.id}>
            <label className="report-filter-group__option">
              <input
                type="checkbox"
                checked={selected.includes(option.id)}
                onChange={(event) => onChange(event.target.checked ? [...selected, option.id] : selected.filter((item) => item !== option.id))}
              />
              <span>{option.label}</span>
            </label>
          </li>
        ))}
      </ul>
      {options.length === 0 && <p className="report-filter-group__note">Нет доступных значений.</p>}
      {options.length > 0 && visible.length === 0 && <p className="report-filter-group__note">Ничего не найдено.</p>}
      {active && (
        <button
          type="button"
          className="button--secondary report-filter-group__clear"
          onClick={() => {
            onChange([])
            onIncludeNoneChange?.(false)
          }}
        >
          Снять выбор
        </button>
      )}
    </FilterGroup>
  )
}

type ChoiceGroupProps<T extends string> = {
  label: string
  options: { id: T; label: string }[]
  value: T
  onChange: (value: T) => void
}

export const ChoiceGroup = <T extends string,>({ label, options, value, onChange }: ChoiceGroupProps<T>) => {
  const name = useId()
  const current = options.find((option) => option.id === value) ?? options[0]
  return (
    <FilterGroup label={label} summary={current.label.toLocaleLowerCase('ru-RU')} active={value !== options[0].id}>
      <ul className="report-filter-group__options">
        {options.map((option) => (
          <li key={option.id}>
            <label className="report-filter-group__option">
              <input type="radio" name={name} checked={option.id === value} onChange={() => onChange(option.id)} />
              <span>{option.label}</span>
            </label>
          </li>
        ))}
      </ul>
    </FilterGroup>
  )
}
