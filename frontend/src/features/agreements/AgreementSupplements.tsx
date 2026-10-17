import { useState, type FormEvent } from 'react'
import { agreementsApi, formatDate, type AgreementSupplement, type AgreementSupplementInput } from './agreementsApi'
import { supplementStatusLabels, type SupplementStatus } from './agreementModel'
import { AgreementFiles } from './AgreementFiles'
import { CommandError, useCommandKey } from './agreementUi'

type SupplementFormProps = {
  agreementId: string
  supplement?: AgreementSupplement
  onSaved: () => void
  onCancel: () => void
  handleAccessError: (error: unknown) => boolean
}

const orNull = (value: string) => (value.trim() === '' ? null : value.trim())

const SupplementForm = ({ agreementId, supplement, onSaved, onCancel, handleAccessError }: SupplementFormProps) => {
  const [number, setNumber] = useState(supplement?.number ?? '')
  const [concludedOn, setConcludedOn] = useState(supplement?.concludedOn ?? '')
  const [subject, setSubject] = useState(supplement?.subject ?? '')
  const [status, setStatus] = useState<SupplementStatus>(supplement?.status ?? 'DRAFT')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload: AgreementSupplementInput = {
      version: supplement?.version ?? null,
      number: number.trim(),
      concludedOn: orNull(concludedOn),
      subject: orNull(subject),
      status
    }
    setSaving(true)
    setError(null)
    try {
      const key = commandKey.keyFor(payload)
      if (supplement === undefined) {
        await agreementsApi.createSupplement(agreementId, payload, key)
      } else {
        await agreementsApi.updateSupplement(supplement.id, payload, key)
      }
      commandKey.settle()
      onSaved()
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
    <form className="agreement-form" onSubmit={(event) => void submit(event)} aria-label={supplement ? 'Изменение допсоглашения' : 'Новое допсоглашение'}>
      <div className="agreement-form__grid">
        <label>
          <span>Номер<span className="required-mark" aria-hidden="true"> *</span></span>
          <input value={number} required maxLength={100} onChange={(event) => setNumber(event.target.value)} />
        </label>
        <label>
          Дата подписания
          <input type="date" value={concludedOn} onChange={(event) => setConcludedOn(event.target.value)} />
        </label>
        <label>
          Статус
          <select value={status} onChange={(event) => setStatus(event.target.value as SupplementStatus)}>
            {(Object.keys(supplementStatusLabels) as SupplementStatus[]).map((item) => (
              <option key={item} value={item}>{supplementStatusLabels[item]}</option>
            ))}
          </select>
        </label>
        <label className="agreement-form__wide">
          Предмет
          <textarea value={subject} rows={2} maxLength={2000} onChange={(event) => setSubject(event.target.value)} />
        </label>
      </div>
      {error !== null && <CommandError error={error} fallback="Допсоглашение не сохранено." />}
      <div className="agreement-form__actions">
        <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить допсоглашение'}</button>
        <button type="button" className="button--secondary" onClick={onCancel} disabled={saving}>Отмена</button>
      </div>
    </form>
  )
}

type AgreementSupplementsProps = {
  agreementId: string
  supplements: AgreementSupplement[]
  canEdit: boolean
  onChanged: () => void
  handleAccessError: (error: unknown) => boolean
}

export const AgreementSupplements = ({ agreementId, supplements, canEdit, onChanged, handleAccessError }: AgreementSupplementsProps) => {
  const [editing, setEditing] = useState<string | 'new' | null>(null)
  const saved = () => {
    setEditing(null)
    onChanged()
  }

  return (
    <section className="agreement-plan" aria-label="Дополнительные соглашения">
      <div className="agreement-plan__header">
        <h6>Дополнительные соглашения</h6>
        {canEdit && editing === null && (
          <button type="button" className="button--secondary" onClick={() => setEditing('new')}>Добавить допсоглашение</button>
        )}
      </div>
      {editing === 'new' && (
        <SupplementForm agreementId={agreementId} onSaved={saved} onCancel={() => setEditing(null)} handleAccessError={handleAccessError} />
      )}
      {supplements.length === 0 && editing !== 'new' && <p>Допсоглашений нет.</p>}
      <ul className="agreement-plan__list">
        {supplements.map((supplement) => (
          <li key={supplement.id} className="agreement-activity">
            {editing === supplement.id ? (
              <SupplementForm
                agreementId={agreementId}
                supplement={supplement}
                onSaved={saved}
                onCancel={() => setEditing(null)}
                handleAccessError={handleAccessError}
              />
            ) : (
              <>
                <div className="agreement-activity__header">
                  <span className="agreement-activity__title">
                    № {supplement.number}{supplement.concludedOn ? ` от ${formatDate(supplement.concludedOn)}` : ''}
                  </span>
                  <span className={`agreements__status agreements__status--${supplement.status === 'SIGNED' ? 'active' : 'draft'}`}>
                    {supplementStatusLabels[supplement.status]}
                  </span>
                </div>
                {supplement.subject && <p className="agreement-files__comment">{supplement.subject}</p>}
                <AgreementFiles
                  title="Файл допсоглашения"
                  agreementId={agreementId}
                  supplementId={supplement.id}
                  currentFile={supplement.currentFile ?? null}
                  files={supplement.files}
                  canEdit={canEdit}
                  onUploaded={onChanged}
                  handleAccessError={handleAccessError}
                >
                  {editing === null && (
                    <button type="button" className="button--secondary" onClick={() => setEditing(supplement.id)}>Изменить допсоглашение</button>
                  )}
                </AgreementFiles>
              </>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}
