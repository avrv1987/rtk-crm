import { type FormEvent, useRef, useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type ManagerDeputyResult } from '../../shared/api/client'
import { shiftCalendarDate } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import type { FilterOption } from '../reports/ReportControls'
import { type AccessHandlers, commandErrorText, handledAccessError, todayIso } from '../work/workShared'

type ManagerDeputyFormProps = AccessHandlers & {
  managers: FilterOption[]
  leaderId: string | undefined
  onAssigned: () => void
}

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }
  | { kind: 'done'; result: ManagerDeputyResult }

const managerDeputySummary = (result: ManagerDeputyResult) => [
  `Заместитель назначен по вузам: ${result.assigned.length}.`,
  ...result.skipped.map((item) => `«${item.organizationName}» пропущен: ${item.reason}`)
]

export const ManagerDeputyForm = ({ managers, leaderId, onAssigned, onSessionExpired, onProfileUnavailable }: ManagerDeputyFormProps) => {
  const [managerId, setManagerId] = useState('')
  const [deputyProfileId, setDeputyProfileId] = useState('')
  const [startsOn, setStartsOn] = useState(todayIso)
  const [endsOn, setEndsOn] = useState(() => shiftCalendarDate(todayIso(), 13))
  const [state, setState] = useState<CommandState>({ kind: 'idle' })
  const commandKey = useRef<string | null>(null)
  const saving = state.kind === 'saving'
  // Заместитель — менеджер команды, а не сам руководитель: так же проверяет сервер.
  const deputies = managers.filter((manager) => manager.id !== managerId && manager.id !== leaderId)

  const edit = (change: () => void) => {
    commandKey.current = null
    change()
    if (state.kind !== 'saving') {
      setState({ kind: 'idle' })
    }
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (saving || managerId === '' || deputyProfileId === '') {
      return
    }
    setState({ kind: 'saving' })
    try {
      const result = await apiClient.assignManagerDeputy(
        managerId,
        { deputyProfileId, startsOn, endsOn },
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      commandKey.current = null
      setState({ kind: 'done', result })
      onAssigned()
    } catch (error) {
      if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setState({ kind: 'failed', error })
      }
    }
  }

  return (
    <form className="organization-deputy__form" aria-label="Заместитель на все вузы КАМ" onSubmit={(event) => void submit(event)}>
      <label>
        <span>Отсутствует КАМ<span className="required-mark" aria-hidden="true"> *</span></span>
        <select value={managerId} required disabled={saving} onChange={(event) => edit(() => setManagerId(event.target.value))}>
          <option value="">Выберите КАМ</option>
          {managers.map((manager) => <option key={manager.id} value={manager.id}>{manager.label}</option>)}
        </select>
      </label>
      <label>
        <span>Заместитель<span className="required-mark" aria-hidden="true"> *</span></span>
        <select value={deputyProfileId} required disabled={saving} onChange={(event) => edit(() => setDeputyProfileId(event.target.value))}>
          <option value="">Выберите КАМ</option>
          {deputies.map((manager) => <option key={manager.id} value={manager.id}>{manager.label}</option>)}
        </select>
      </label>
      <label>
        <span>С<span className="required-mark" aria-hidden="true"> *</span></span>
        <input type="date" value={startsOn} required disabled={saving} onChange={(event) => edit(() => setStartsOn(event.target.value))} />
      </label>
      <label>
        <span>По<span className="required-mark" aria-hidden="true"> *</span></span>
        <input type="date" value={endsOn} min={startsOn} required disabled={saving} onChange={(event) => edit(() => setEndsOn(event.target.value))} />
      </label>
      <button type="submit" disabled={saving || managerId === '' || deputyProfileId === ''}>
        {saving ? 'Назначаем…' : 'Назначить на все вузы КАМ'}
      </button>
      {state.kind === 'done' && (
        <ul className="organization-bulk__done" role="status">
          {managerDeputySummary(state.result).map((line) => <li key={line}>{line}</li>)}
        </ul>
      )}
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandErrorText(state.error, 'назначить заместителя')}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
        </div>
      )}
    </form>
  )
}
