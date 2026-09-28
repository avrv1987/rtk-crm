import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { formatCalendarDate, todayInMoscow } from '../../shared/format/datetime'
import { matchingPreset, periodPresets, previousMonthEnd } from './reportPeriods'

export type FilterOption = {
  id: string
  label: string
}

const usePopover = () => {
  const ref = useRef<HTMLDetailsElement>(null)

  useEffect(() => {
    const dismiss = (event: Event) => {
      const element = ref.current
      if (element === null || !element.open) {
        return
      }
      if (event instanceof globalThis.KeyboardEvent) {
        if (event.key === 'Escape') {
          element.open = false
          element.querySelector('summary')?.focus()
        }
        return
      }
      if (!element.contains(event.target as Node)) {
        element.open = false
      }
    }
    document.addEventListener('click', dismiss)
    document.addEventListener('keydown', dismiss)
    return () => {
      document.removeEventListener('click', dismiss)
      document.removeEventListener('keydown', dismiss)
    }
  }, [])

  const place = () => {
    const element = ref.current
    const panel = element?.querySelector<HTMLElement>('.report-popover')
    if (!element?.open || !panel) {
      return
    }
    panel.classList.remove('report-popover--end')
    if (panel.getBoundingClientRect().right > document.documentElement.clientWidth - 8) {
      panel.classList.add('report-popover--end')
    }
  }

  const close = () => {
    if (ref.current !== null) {
      ref.current.open = false
      ref.current.querySelector('summary')?.focus()
    }
  }

  return { ref, place, close }
}

export const InfoTip = ({ label, children }: { label: string; children: ReactNode }) => {
  const { ref, place } = usePopover()
  return (
    <details ref={ref} className="report-tip" onToggle={place}>
      <summary className="report-tip__toggle" aria-label={`Пояснение: ${label}`} title="Пояснение">i</summary>
      <div className="report-popover report-tip__panel" role="note">
        <p className="report-popover__title">{label}</p>
        <div className="report-tip__text">{children}</div>
      </div>
    </details>
  )
}

type ChipProps = {
  label: string
  value: string
  active: boolean
  onClear?: () => void
  children: (close: () => void) => ReactNode
}

export const Chip = ({ label, value, active, onClear, children }: ChipProps) => {
  const { ref, place, close } = usePopover()
  return (
    <details ref={ref} className={active ? 'report-chip report-chip--active' : 'report-chip'} onToggle={place}>
      <summary className="report-chip__toggle">
        <span className="report-chip__label">{label}:</span>
        <span className="report-chip__value">{value}</span>
        <span className="report-chip__caret" aria-hidden="true">▾</span>
      </summary>
      <div className="report-popover report-chip__panel" role="group" aria-label={label}>
        <p className="report-popover__title">{label}</p>
        {children(close)}
        <div className="report-chip__footer">
          {onClear && (
            <button type="button" className="reports__secondary" disabled={!active} onClick={onClear}>Снять выбор</button>
          )}
          <button type="button" onClick={close}>Готово</button>
        </div>
      </div>
    </details>
  )
}

type MultiChipProps = {
  label: string
  options: FilterOption[]
  selected: string[]
  onChange: (ids: string[]) => void
  includeNone?: boolean
  onIncludeNoneChange?: (value: boolean) => void
  note?: string
}

export const MultiChip = ({ label, options, selected, onChange, includeNone, onIncludeNoneChange, note }: MultiChipProps) => {
  const [search, setSearch] = useState('')
  const query = search.trim().toLocaleLowerCase('ru-RU')
  const visible = query.length === 0
    ? options
    : options.filter((option) => option.label.toLocaleLowerCase('ru-RU').includes(query))
  const count = selected.length + (includeNone === true ? 1 : 0)
  const single = count === 1
    ? (includeNone === true ? 'Не указано' : options.find((option) => option.id === selected[0])?.label ?? '1')
    : null
  const value = count === 0 ? 'все' : single ?? `выбрано ${count}`

  const toggle = (id: string, checked: boolean) => {
    onChange(checked ? [...selected, id] : selected.filter((item) => item !== id))
  }

  const clear = () => {
    onChange([])
    onIncludeNoneChange?.(false)
  }

  return (
    <Chip label={label} value={value} active={count > 0} onClear={clear}>
      {() => (
        <>
          {note && <p className="report-popover__note">{note}</p>}
          {options.length > 6 && (
            <input
              type="search"
              className="report-chip__search"
              placeholder="Найти"
              aria-label={`Найти: ${label}`}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          )}
          {onIncludeNoneChange && (
            <label className="report-chip__option report-chip__option--none">
              <input
                type="checkbox"
                checked={includeNone === true}
                onChange={(event) => onIncludeNoneChange(event.target.checked)}
              />
              <span>Не указано</span>
            </label>
          )}
          <ul className="report-chip__options">
            {visible.map((option) => (
              <li key={option.id}>
                <label className="report-chip__option">
                  <input
                    type="checkbox"
                    checked={selected.includes(option.id)}
                    onChange={(event) => toggle(option.id, event.target.checked)}
                  />
                  <span>{option.label}</span>
                </label>
              </li>
            ))}
          </ul>
          {options.length === 0 && <p className="report-popover__note">Нет доступных значений.</p>}
          {options.length > 0 && visible.length === 0 && <p className="report-popover__note">Ничего не найдено.</p>}
        </>
      )}
    </Chip>
  )
}

type ChoiceChipProps<T extends string> = {
  label: string
  options: { id: T; label: string }[]
  value: T
  onChange: (value: T) => void
}

export const ChoiceChip = <T extends string,>({ label, options, value, onChange }: ChoiceChipProps<T>) => {
  const name = useId()
  const current = options.find((option) => option.id === value) ?? options[0]
  return (
    <Chip label={label} value={current.label.toLocaleLowerCase('ru-RU')} active={value !== options[0].id}>
      {(close) => (
        <ul className="report-chip__options">
          {options.map((option) => (
            <li key={option.id}>
              <label className="report-chip__option">
                <input
                  type="radio"
                  name={name}
                  checked={option.id === value}
                  onChange={() => {
                    onChange(option.id)
                    close()
                  }}
                />
                <span>{option.label}</span>
              </label>
            </li>
          ))}
        </ul>
      )}
    </Chip>
  )
}

export type SelectedValue = {
  key: string
  text: string
  onRemove: () => void
}

export const SelectedValues = ({ values }: { values: SelectedValue[] }) => (
  values.length === 0 ? null : (
    <ul className="report-selected" aria-label="Выбранные значения фильтров">
      {values.map((value) => (
        <li key={value.key}>
          <span>{value.text}</span>
          <button type="button" aria-label={`Убрать: ${value.text}`} title="Убрать" onClick={value.onRemove}>×</button>
        </li>
      ))}
    </ul>
  )
)

const rangeText = (from: string, to: string) => {
  if (from === '' && to === '') {
    return 'без ограничения по датам'
  }
  return `${from === '' ? 'с начала' : formatCalendarDate(from)} — ${to === '' ? 'без конца' : formatCalendarDate(to)}`
}

type PeriodPickerProps = {
  from: string
  to: string
  onChange: (from: string, to: string) => void
  required?: boolean
  hint?: ReactNode
}

export const PeriodPicker = ({ from, to, onChange, required = false, hint }: PeriodPickerProps) => {
  const today = todayInMoscow()
  const active = matchingPreset(from, to, today)
  const [custom, setCustom] = useState(active === null)
  const showInputs = custom || active === null
  const fromId = useId()
  const toId = useId()

  return (
    <fieldset className="report-period">
      <legend className="report-period__legend">
        Период <span className="report-period__range">{rangeText(from, to)}, МСК</span>
        <InfoTip label="Период">
          {hint ?? 'Даты включительно, по московскому времени. Пустая дата в своём диапазоне снимает ограничение.'}
        </InfoTip>
      </legend>
      <div className="report-period__presets">
        {periodPresets(today).map((preset) => (
          <button
            key={preset.id}
            type="button"
            className="report-period__preset"
            aria-pressed={!showInputs && active === preset.id}
            onClick={() => {
              setCustom(false)
              onChange(preset.from, preset.to)
            }}
          >
            {preset.label}
          </button>
        ))}
        <button
          type="button"
          className="report-period__preset"
          aria-pressed={showInputs}
          onClick={() => setCustom(true)}
        >
          Свой диапазон
        </button>
      </div>
      {showInputs && (
        <div className="report-period__custom">
          <label htmlFor={fromId}>с</label>
          <input
            id={fromId}
            type="date"
            required={required}
            value={from}
            max={to || undefined}
            onChange={(event) => onChange(event.target.value, to)}
          />
          <label htmlFor={toId}>по</label>
          <input
            id={toId}
            type="date"
            required={required}
            value={to}
            min={from || undefined}
            onChange={(event) => onChange(from, event.target.value)}
          />
        </div>
      )}
    </fieldset>
  )
}

export const AsOfPicker = ({ value, onChange }: { value: string; onChange: (value: string) => void }) => {
  const today = todayInMoscow()
  const monthEnd = previousMonthEnd(today)
  const inputId = useId()
  return (
    <fieldset className="report-period">
      <legend className="report-period__legend">
        Состояние на дату <span className="report-period__range">{formatCalendarDate(value || today)}, конец дня МСК</span>
        <InfoTip label="Состояние на дату">
          Этап и ответственный восстанавливаются на конец выбранного дня по московскому времени; пустая дата — сегодня.
        </InfoTip>
      </legend>
      <div className="report-period__presets">
        <button type="button" className="report-period__preset" aria-pressed={value === today || value === ''} onClick={() => onChange(today)}>
          Сегодня
        </button>
        <button type="button" className="report-period__preset" aria-pressed={value === monthEnd} onClick={() => onChange(monthEnd)}>
          Конец прошлого месяца
        </button>
        <span className="report-period__custom">
          <label htmlFor={inputId}>дата</label>
          <input id={inputId} type="date" value={value} max={today} onChange={(event) => onChange(event.target.value)} />
        </span>
      </div>
    </fieldset>
  )
}
