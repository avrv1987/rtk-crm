import { useEffect, useState } from 'react'
import { apiClient, type PageInteraction } from '../../shared/api/client'
import { readNoContactDays, saveNoContactDays } from '../work/contactMark'
import { browserStorage } from '../work/workView'
import { type AccessHandlers, daysLabel, formatDate, handledAccessError, todayIso } from '../work/workShared'

const dayOptions = [14, 30, 45, 60, 90]
const listLimit = 10

type State = { kind: 'loading' } | { kind: 'ready'; page: PageInteraction } | { kind: 'failed' }

// Сигнал «нет контакта больше N дней» по идущим взаимодействиям в области руководителя. Порог тот же, что
// в «Моей работе», и хранится в браузере сотрудника; по умолчанию 30 дней.
export const ControlNoContact = ({ refreshKey, onSessionExpired, onProfileUnavailable }: AccessHandlers & { refreshKey: unknown }) => {
  const [days, setDays] = useState(() => readNoContactDays(browserStorage()))
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    let active = true
    setState({ kind: 'loading' })
    apiClient.listInteractions({ noContactDays: days, size: listLimit, sort: 'updatedAt,asc' }).then(
      (page) => active && setState({ kind: 'ready', page }),
      (error: unknown) => {
        if (active && !handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
          setState({ kind: 'failed' })
        }
      }
    )
    return () => {
      active = false
    }
  }, [days, onProfileUnavailable, onSessionExpired, refreshKey])

  const options = dayOptions.includes(days) ? dayOptions : [...dayOptions, days].sort((a, b) => a - b)
  return (
    <details className="control-manager control-manager--idle control-no-contact">
      <summary>
        <span className="control-manager__name">Нет контакта больше {daysLabel(days)}</span>
        <span className="control-totals">
          <span className="control-totals__overdue">
            {state.kind === 'ready' ? `взаимодействий ${state.page.total}` : state.kind === 'loading' ? 'считаем…' : 'недоступно'}
          </span>
        </span>
      </summary>
      <p className="control-muted control-idle__note">
        Идущие взаимодействия, где последний деловой контакт (отметка «был контакт» или дата в данных взаимодействия) был
        раньше порога; у нового взаимодействия срок считается от создания. Записи сайта и LMS контактом не считаются.
      </p>
      <label className="control-muted">
        Порог{' '}
        <select value={days} onChange={(event) => {
          const value = Number(event.target.value)
          saveNoContactDays(browserStorage(), value)
          setDays(value)
        }}>
          {options.map((value) => <option key={value} value={value}>{daysLabel(value)}</option>)}
        </select>
      </label>
      {state.kind === 'failed' && <p className="control-muted" role="alert">Не удалось загрузить список. Обновите страницу.</p>}
      {state.kind === 'ready' && (
        <ul className="control-unassigned">
          {state.page.items.map((item) => (
            <li key={item.id}>
              <a href={`#/organizations/${item.organizationId}/${item.id}`}>{item.title}</a>
              <span className="control-muted">
                {`${item.organizationName} · КАМ: ${item.ownerManagerName ?? 'требует назначения'} · ${
                  item.lastContactAt === null ? 'контакт не записан' : `последний контакт ${formatDate(todayIso(new Date(item.lastContactAt)))}`}`}
              </span>
            </li>
          ))}
        </ul>
      )}
      {state.kind === 'ready' && state.page.total > state.page.items.length && (
        <a href={`#/work?noContactDays=${days}`}>Все {state.page.total} в списке работы</a>
      )}
    </details>
  )
}
