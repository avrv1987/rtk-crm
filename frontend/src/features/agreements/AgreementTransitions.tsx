import { useState, type FormEvent } from 'react'
import { todayInMoscow } from '../../shared/format/datetime'
import { CardDialog } from '../interactions/cardUi'
import { agreementsApi, type Agreement, type AgreementTransitionInput, type AgreementTransitionOption } from './agreementsApi'
import { agreementStatusLabels, transitionFields, type AgreementStatus } from './agreementModel'
import { CommandError, useCommandKey } from './agreementUi'
import { useRecordDraft } from '../interactions/formDraft'

type TransitionTarget = {
  id: string
  version: number
  number: string
  status: AgreementStatus
  concludedOn?: string | null
  validUntil?: string | null
  transitions: AgreementTransitionOption[]
}

type AgreementTransitionsProps = {
  agreement: TransitionTarget
  interactionId?: string
  onDone: (agreement: Agreement) => void
  // Н-А1: перечитать договор после конфликта версии; окно закрывается, комментарий остаётся в черновике.
  onReload?: () => void
  handleAccessError: (error: unknown) => boolean
}

const orNull = (value: string) => (value.trim() === '' ? null : value.trim())

const TransitionForm = ({
  agreement,
  option,
  interactionId,
  onDone,
  onCancel,
  onReload,
  handleAccessError
}: AgreementTransitionsProps & { option: AgreementTransitionOption; onCancel: () => void }) => {
  const fields = transitionFields(option.toStatus, agreement)
  const today = todayInMoscow()
  const draft = useRecordDraft(
    `agreement-transition:${agreement.id}:${option.toStatus}`,
    `«${option.title}» по договору № ${agreement.number}`,
    { comment: '', signedOn: today, validUntil: agreement.validUntil ?? '', sentOn: today }
  )
  const { comment, signedOn, validUntil, sentOn } = draft.values
  const { set } = draft
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload: AgreementTransitionInput = {
      version: agreement.version,
      toStatus: option.toStatus,
      comment: orNull(comment),
      signedOn: fields.signedOn ? orNull(signedOn) : null,
      validUntil: fields.validUntil ? orNull(validUntil) : null,
      sentOn: fields.sentOn ? orNull(sentOn) : null,
      interactionId: interactionId ?? null
    }
    setSaving(true)
    setError(null)
    try {
      const result = await agreementsApi.transition(agreement.id, payload, commandKey.keyFor(payload))
      commandKey.settle()
      draft.clear()
      onDone(result)
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="agreement-form agreement-transition" onSubmit={(event) => void submit(event)}>
      <p>
        Договор № {agreement.number}: {agreementStatusLabels[agreement.status]} → <strong>{agreementStatusLabels[option.toStatus]}</strong>
      </p>
      <div className="agreement-form__grid">
        {fields.signedOn && (
          <label>
            <span>Дата подписания<span className="required-mark" aria-hidden="true"> *</span></span>
            <input type="date" value={signedOn} required max={today} onChange={(event) => set({ signedOn: event.target.value })} />
          </label>
        )}
        {fields.validUntil && (
          <label>
            Действует до
            <input type="date" value={validUntil} min={signedOn || undefined} onChange={(event) => set({ validUntil: event.target.value })} />
          </label>
        )}
        {fields.sentOn && (
          <label>
            Дата отправки на согласование
            <input type="date" value={sentOn} max={today} onChange={(event) => set({ sentOn: event.target.value })} />
          </label>
        )}
        <label className="agreement-form__wide">
          <span>Комментарий{option.commentRequired && <span className="required-mark" aria-hidden="true"> *</span>}</span>
          <textarea value={comment} rows={3} maxLength={2000} required={option.commentRequired} onChange={(event) => set({ comment: event.target.value })} />
        </label>
      </div>
      {error !== null && <CommandError error={error} fallback="Статус договора не изменён." onReload={onReload} />}
      <div className="agreement-form__actions">
        <button type="submit" disabled={saving || (option.commentRequired && comment.trim() === '')}>
          {saving ? 'Сохраняем…' : option.title}
        </button>
        <button type="button" className="button--secondary" onClick={() => { draft.clear(); onCancel() }} disabled={saving}>Отмена</button>
      </div>
    </form>
  )
}

export const AgreementTransitions = (props: AgreementTransitionsProps) => {
  const [option, setOption] = useState<AgreementTransitionOption | null>(null)
  if (props.agreement.transitions.length === 0) {
    return null
  }
  return (
    <div className="agreement-transitions">
      {props.agreement.transitions.map((item) => (
        <button
          key={item.toStatus}
          type="button"
          className={item.toStatus === 'TERMINATED' ? 'button--danger' : item.commentRequired ? 'button--secondary' : undefined}
          onClick={() => setOption(item)}
        >
          {item.title}
        </button>
      ))}
      <CardDialog open={option !== null} title={option?.title ?? ''} onClose={() => setOption(null)}>
        {option !== null && (
          <TransitionForm
            {...props}
            option={option}
            onCancel={() => setOption(null)}
            onReload={props.onReload === undefined ? undefined : () => { setOption(null); props.onReload?.() }}
            onDone={(agreement) => { setOption(null); props.onDone(agreement) }}
          />
        )}
      </CardDialog>
    </div>
  )
}
