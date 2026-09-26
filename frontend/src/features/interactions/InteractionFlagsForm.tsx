import { type FormEvent, useRef, useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type Interaction, type InteractionMarks } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { changedDraft, conflictKeys, keepDraftValues, mergedValues, parseBasedDraft, takeCurrentValues } from './draftBase'
import { DraftConflictNotice } from './DraftConflictNotice'
import { recordOf, textOf, useFormDraft } from './formDraft'
import { useUnsavedDraft } from './unsavedDrafts'
import {
  commandFailureMessage,
  handledAccessError,
  type RiskLevel,
  riskLabels,
  type WaitingOn,
  waitingLabels
} from './workMarks'
import './workCard.css'

type InteractionFlagsFormProps = {
  interaction: Interaction
  profileId: string
  onChanged: (interaction: Interaction) => void
  onRefresh: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type FlagsValues = {
  waiting: { on: WaitingOn | ''; note: string }
  problem: { on: boolean; text: string }
  risk: { level: RiskLevel | ''; reason: string }
}

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

const waitingOptions: WaitingOn[] = ['UNIVERSITY', 'RTK']
const riskOptions: RiskLevel[] = ['MEDIUM', 'HIGH']

const groupLabels: Record<keyof FlagsValues, string> = {
  waiting: 'Чего ждём',
  problem: 'Проблема',
  risk: 'Риск'
}

const fromMarks = (marks: InteractionMarks): FlagsValues => ({
  waiting: { on: marks.waitingOn ?? '', note: marks.waitingNote ?? '' },
  problem: { on: marks.problem !== null, text: marks.problem ?? '' },
  risk: { level: marks.riskLevel ?? '', reason: marks.riskReason ?? '' }
})

const parseValues = (value: unknown): FlagsValues | null => {
  const record = recordOf(value)
  const waiting = recordOf(record?.waiting)
  const problem = recordOf(record?.problem)
  const risk = recordOf(record?.risk)
  if (waiting === null || problem === null || risk === null) {
    return null
  }
  return {
    waiting: { on: waitingOptions.find((item) => item === waiting.on) ?? '', note: textOf(waiting.note) },
    problem: { on: problem.on === true, text: textOf(problem.text) },
    risk: { level: riskOptions.find((item) => item === risk.level) ?? '', reason: textOf(risk.reason) }
  }
}

const parseDraft = (value: unknown) => parseBasedDraft(value, parseValues)

const payloadOf = (values: FlagsValues) => ({
  waitingOn: values.waiting.on === '' ? null : values.waiting.on,
  waitingNote: values.waiting.on === '' ? null : values.waiting.note.trim() || null,
  problem: values.problem.on ? values.problem.text.trim() || null : null,
  riskLevel: values.risk.level === '' ? null : values.risk.level,
  riskReason: values.risk.level === '' ? null : values.risk.reason.trim() || null
})

const shownGroup = (group: keyof FlagsValues, values: FlagsValues) => {
  if (group === 'waiting') {
    const note = values.waiting.note.trim()
    return values.waiting.on === '' ? 'ничего не ждём' : `${waitingLabels[values.waiting.on]}${note ? `: ${note}` : ''}`
  }
  if (group === 'problem') {
    return values.problem.on ? values.problem.text.trim() || 'есть' : 'нет'
  }
  return values.risk.level === '' ? 'нет риска' : `${riskLabels[values.risk.level]}: ${values.risk.reason.trim()}`
}

export const InteractionFlagsForm = ({
  interaction,
  profileId,
  onChanged,
  onRefresh,
  onSessionExpired,
  onProfileUnavailable
}: InteractionFlagsFormProps) => {
  const [draft, setDraft] = useFormDraft(profileId, `card:${interaction.id}:flags`, parseDraft)
  const [state, setState] = useState<CommandState>({ kind: 'idle' })
  const commandKey = useRef<string | null>(null)
  const currentValues = fromMarks(interaction.marks)
  const values = mergedValues(draft, currentValues)
  const conflicts = conflictKeys(draft, currentValues)
  const payload = payloadOf(values)
  const current = payloadOf(currentValues)
  const changed = JSON.stringify(payload) !== JSON.stringify(current)
  const missingProblem = values.problem.on && payload.problem === null
  const missingRiskReason = payload.riskLevel !== null && payload.riskReason === null
  useUnsavedDraft(`card:${interaction.id}:flags`, `отметки ожидания и риска в карточке «${interaction.title}»`, draft !== null && changed)

  const change = (next: Partial<FlagsValues>) => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setDraft(changedDraft(draft, currentValues, next))
  }

  const resolveConflicts = (keep: boolean) => {
    if (draft !== null) {
      commandKey.current = null
      setState({ kind: 'idle' })
      setDraft(keep ? keepDraftValues(draft, currentValues) : takeCurrentValues(draft, currentValues))
    }
  }

  const reset = () => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setDraft(null)
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!changed || conflicts.length > 0 || missingProblem || missingRiskReason) {
      return
    }
    setState({ kind: 'saving' })
    try {
      const updated = await apiClient.updateInteractionFlags(
        interaction.id,
        { version: interaction.version, ...payload },
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      commandKey.current = null
      setDraft(null)
      setState({ kind: 'idle' })
      onChanged(updated)
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }

  return (
    <form className="interaction-plan-form work-flags" onSubmit={(event) => void submit(event)}>
      <h6>Ожидание, проблема и риск</h6>
      <p>Отметки видны в «Моей работе» и отбираются фильтром. Каждое изменение попадает в историю.</p>
      {draft !== null && (
        <DraftConflictNotice
          conflicts={conflicts.map((group) => ({
            key: group,
            label: groupLabels[group],
            current: shownGroup(group, currentValues),
            draft: shownGroup(group, draft.values)
          }))}
          onKeepDraft={() => resolveConflicts(true)}
          onTakeCurrent={() => resolveConflicts(false)}
        />
      )}
      <fieldset className="work-flags__waiting">
        <legend>Чего ждём</legend>
        <label className="checkbox-field">
          <input type="radio" name={`waiting-${interaction.id}`} checked={values.waiting.on === ''} onChange={() => change({ waiting: { ...values.waiting, on: '' } })} />
          Ничего не ждём
        </label>
        {waitingOptions.map((option) => (
          <label key={option} className="checkbox-field">
            <input
              type="radio"
              name={`waiting-${interaction.id}`}
              checked={values.waiting.on === option}
              onChange={() => change({ waiting: { ...values.waiting, on: option } })}
            />
            {waitingLabels[option]}
          </label>
        ))}
      </fieldset>
      {values.waiting.on !== '' && (
        <label>
          Чего именно ждём
          <input
            value={values.waiting.note}
            maxLength={500}
            onChange={(event) => change({ waiting: { ...values.waiting, note: event.target.value } })}
          />
        </label>
      )}
      <label className="checkbox-field">
        <input
          type="checkbox"
          checked={values.problem.on}
          onChange={(event) => change({ problem: { ...values.problem, on: event.target.checked } })}
        />
        Есть проблема
      </label>
      {values.problem.on && (
        <label>
          Описание проблемы
          <textarea
            value={values.problem.text}
            maxLength={1000}
            required
            onChange={(event) => change({ problem: { ...values.problem, text: event.target.value } })}
          />
        </label>
      )}
      <label>
        Риск
        <select
          value={values.risk.level}
          onChange={(event) => change({ risk: { ...values.risk, level: riskOptions.find((item) => item === event.target.value) ?? '' } })}
        >
          <option value="">Нет риска</option>
          {riskOptions.map((option) => <option key={option} value={option}>{riskLabels[option]}</option>)}
        </select>
      </label>
      {values.risk.level !== '' && (
        <label>
          Причина риска
          <textarea
            value={values.risk.reason}
            maxLength={1000}
            required
            onChange={(event) => change({ risk: { ...values.risk, reason: event.target.value } })}
          />
        </label>
      )}
      <div className="interaction-plan-form__actions">
        <button type="submit" disabled={state.kind === 'saving' || !changed || conflicts.length > 0 || missingProblem || missingRiskReason}>
          {state.kind === 'saving' ? 'Сохраняем…' : 'Сохранить отметки'}
        </button>
        {draft !== null && (
          <button type="button" className="button--secondary" disabled={state.kind === 'saving'} onClick={reset}>Отменить изменения</button>
        )}
      </div>
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandFailureMessage(state.error)}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
          {state.error instanceof ApiError && state.error.status === 409 && (
            <button type="button" onClick={onRefresh}>Обновить карточку</button>
          )}
        </div>
      )}
    </form>
  )
}
