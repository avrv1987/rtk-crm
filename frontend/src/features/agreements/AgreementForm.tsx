import { useState, type FormEvent } from 'react'
import { todayInMoscow } from '../../shared/format/datetime'
import {
  agreementsApi,
  formatDate,
  type Agreement,
  type AgreementInput,
  type AgreementOptions
} from './agreementsApi'
import { defaultSignerId } from './agreementModel'
import { planKindLabels, planKinds, type PlanKind } from '../reports/signingPlan'
import { CommandError, useCommandKey } from './agreementUi'

type Named = { id: string; name: string }

type AgreementFormProps = {
  organizationId: string
  agreement?: Agreement
  options: AgreementOptions
  defaultInteractionIds?: string[]
  onSaved: (agreement: Agreement) => void
  onCancel: () => void
  handleAccessError: (error: unknown) => boolean
}

const orNull = (value: string) => (value.trim() === '' ? null : value.trim())

const withSelected = (options: Named[], selected: Named[] | undefined) => [
  ...options,
  ...(selected ?? []).filter((item) => !options.some((option) => option.id === item.id))
]

type ChoiceListProps = {
  legend: string
  items: Named[]
  selected: string[]
  empty: string
  onChange: (ids: string[]) => void
}

const ChoiceList = ({ legend, items, selected, empty, onChange }: ChoiceListProps) => (
  <fieldset className="agreement-form__choices">
    <legend>{legend}{selected.length > 0 ? ` (${selected.length})` : ''}</legend>
    {items.length === 0 && <p className="agreement-form__hint">{empty}</p>}
    <ul>
      {items.map((item) => (
        <li key={item.id}>
          <label>
            <input
              type="checkbox"
              checked={selected.includes(item.id)}
              onChange={(event) => onChange(event.target.checked ? [...selected, item.id] : selected.filter((id) => id !== item.id))}
            />
            <span>{item.name}</span>
          </label>
        </li>
      ))}
    </ul>
  </fieldset>
)

export const AgreementForm = ({
  organizationId,
  agreement,
  options,
  defaultInteractionIds,
  onSaved,
  onCancel,
  handleAccessError
}: AgreementFormProps) => {
  const creating = agreement === undefined
  const [number, setNumber] = useState(agreement?.number ?? '')
  const [signed, setSigned] = useState(false)
  const [concludedOn, setConcludedOn] = useState(agreement?.concludedOn ?? '')
  const [validUntil, setValidUntil] = useState(agreement?.validUntil ?? '')
  const [sentOn, setSentOn] = useState(agreement?.sentOn ?? '')
  const [plannedKind, setPlannedKind] = useState<PlanKind | ''>(agreement?.plannedKind ?? '')
  const [plannedOn, setPlannedOn] = useState(agreement?.plannedOn ?? '')
  const [parties, setParties] = useState(agreement?.parties ?? '')
  const [universitySignerId, setUniversitySignerId] = useState(
    agreement === undefined ? defaultSignerId(options.contacts) : agreement.universitySigner?.id ?? ''
  )
  const [schoolSignerId, setSchoolSignerId] = useState(agreement?.schoolSigner?.id ?? '')
  const [interactionIds, setInteractionIds] = useState<string[]>(agreement?.interactions.map((item) => item.id) ?? defaultInteractionIds ?? [])
  const [directionIds, setDirectionIds] = useState<string[]>(agreement?.directions.map((item) => item.id) ?? [])
  const [programIds, setProgramIds] = useState<string[]>(agreement?.programs.map((item) => item.id) ?? [])
  const [productIds, setProductIds] = useState<string[]>(agreement?.products.map((item) => item.id) ?? [])
  const [fileId, setFileId] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()

  const contacts = [
    ...options.contacts,
    ...(agreement?.universitySigner && !options.contacts.some((item) => item.id === agreement.universitySigner?.id)
      ? [{ id: agreement.universitySigner.id, name: agreement.universitySigner.name, position: null, signatory: false }]
      : [])
  ]
  const schoolSigners = [
    ...options.schoolSigners,
    ...(agreement?.schoolSigner && !options.schoolSigners.some((item) => item.id === agreement.schoolSigner?.id)
      ? [{ id: agreement.schoolSigner.id, displayName: agreement.schoolSigner.name }]
      : [])
  ]
  const works = withSelected(options.interactions.map((item) => ({ id: item.id, name: item.title })), agreement?.interactions.map((item) => ({ id: item.id, name: item.title })))

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload: AgreementInput = {
      version: agreement?.version ?? null,
      number: number.trim(),
      concludedOn: orNull(concludedOn),
      validUntil: orNull(validUntil),
      sentOn: orNull(sentOn),
      parties: orNull(parties),
      status: creating ? (signed ? 'ACTIVE' : 'DRAFT') : null,
      fileAttachmentId: creating ? orNull(fileId) : null,
      universitySignerContactId: orNull(universitySignerId),
      schoolSignerProfileId: orNull(schoolSignerId),
      interactionIds,
      directionIds,
      programIds,
      productIds,
      plannedKind: plannedKind === '' ? null : plannedKind,
      plannedOn: plannedKind === '' ? null : orNull(plannedOn)
    }
    setSaving(true)
    setError(null)
    try {
      const key = commandKey.keyFor(payload)
      const result = agreement === undefined
        ? await agreementsApi.create(organizationId, payload, key)
        : await agreementsApi.update(agreement.id, payload, key)
      commandKey.settle()
      onSaved(result)
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
    <form className="agreement-form" onSubmit={(event) => void submit(event)} aria-label={creating ? 'Новый договор' : 'Реквизиты договора'}>
      {creating && (
        <fieldset className="agreement-form__status">
          <legend>Договор</legend>
          <label>
            <input type="radio" name="agreement-initial-status" checked={!signed} onChange={() => setSigned(false)} />
            <span>Проект — пойдёт на согласование с вузом</span>
          </label>
          <label>
            <input type="radio" name="agreement-initial-status" checked={signed} onChange={() => setSigned(true)} />
            <span>Уже подписан</span>
          </label>
        </fieldset>
      )}
      <div className="agreement-form__grid">
        <label>
          <span>Номер договора<span className="required-mark" aria-hidden="true"> *</span></span>
          <input value={number} required maxLength={100} onChange={(event) => setNumber(event.target.value)} />
        </label>
        <label>
          <span>Дата подписания{creating && signed && <span className="required-mark" aria-hidden="true"> *</span>}</span>
          <input
            type="date"
            value={concludedOn}
            required={creating && signed}
            max={validUntil !== '' && validUntil < todayInMoscow() ? validUntil : todayInMoscow()}
            onChange={(event) => setConcludedOn(event.target.value)}
          />
        </label>
        <label>
          Действует до
          <input type="date" value={validUntil} min={concludedOn || undefined} onChange={(event) => setValidUntil(event.target.value)} />
        </label>
        <label>
          Отправлен на согласование
          <input type="date" value={sentOn} onChange={(event) => setSentOn(event.target.value)} />
        </label>
        <label>
          Подписант вуза
          <select value={universitySignerId} onChange={(event) => setUniversitySignerId(event.target.value)}>
            <option value="">Не выбран</option>
            {contacts.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}{item.position ? `, ${item.position}` : ''}{item.signatory ? ' — ЛПР' : ''}
              </option>
            ))}
          </select>
          {contacts.length === 0 && <span className="agreement-form__hint">Добавьте контакт вуза на вкладке «Контакты».</span>}
        </label>
        <label>
          Подписант школы
          <select value={schoolSignerId} onChange={(event) => setSchoolSignerId(event.target.value)}>
            <option value="">Не выбран</option>
            {schoolSigners.map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}
          </select>
        </label>
        <label>
          План
          <select
            value={plannedKind}
            onChange={(event) => {
              const next = event.target.value as PlanKind | ''
              setPlannedKind(next)
              if (next === '') {
                setPlannedOn('')
              } else if (next === 'RENEWAL' && plannedOn === '' && validUntil !== '') {
                setPlannedOn(validUntil)
              }
            }}
          >
            <option value="">Не запланировано</option>
            {planKinds.map((item) => <option key={item} value={item}>{planKindLabels[item]}</option>)}
          </select>
        </label>
        <label>
          {plannedKind === 'RENEWAL' ? 'Плановая дата продления' : 'Плановая дата подписания'}
          <input
            type="date"
            value={plannedOn}
            required={plannedKind !== ''}
            disabled={plannedKind === ''}
            min={plannedKind === 'RENEWAL' ? concludedOn || undefined : undefined}
            onChange={(event) => setPlannedOn(event.target.value)}
          />
          {plannedKind === 'RENEWAL' && validUntil !== '' && (
            <span className="agreement-form__hint">{`Договор действует до ${formatDate(validUntil)}; продление планируют от этой даты.`}</span>
          )}
        </label>
        <label className="agreement-form__wide">
          Стороны
          <textarea value={parties} maxLength={2000} rows={2} onChange={(event) => setParties(event.target.value)} />
        </label>
        {creating && (
          <label className="agreement-form__wide">
            Файл договора из документов взаимодействий
            <select value={fileId} onChange={(event) => setFileId(event.target.value)}>
              <option value="">Не выбран</option>
              {options.attachments.map((item) => (
                <option key={item.id} value={item.id}>{item.originalName} — {item.interactionTitle}, {item.stageName}</option>
              ))}
            </select>
            <span className="agreement-form__hint">Можно выбрать проверенный документ из взаимодействий вуза или загрузить файл после сохранения договора.</span>
          </label>
        )}
      </div>
      <ChoiceList legend="Связанные взаимодействия вуза" items={works} selected={interactionIds} empty="У вуза пока нет взаимодействий." onChange={setInteractionIds} />
      <fieldset className="agreement-form__subject">
        <legend>Предмет договора</legend>
        <ChoiceList
          legend="Направления"
          items={withSelected(options.directions, agreement?.directions)}
          selected={directionIds}
          empty="Нет направлений."
          onChange={setDirectionIds}
        />
        <ChoiceList
          legend="Программы"
          items={withSelected(options.programs, agreement?.programs)}
          selected={programIds}
          empty="Нет программ."
          onChange={setProgramIds}
        />
        <ChoiceList
          legend="Продукты"
          items={withSelected(options.products, agreement?.products)}
          selected={productIds}
          empty="Нет продуктов."
          onChange={setProductIds}
        />
      </fieldset>
      {error !== null && <CommandError error={error} fallback="Договор не сохранён." />}
      <div className="agreement-form__actions">
        <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить договор'}</button>
        <button type="button" className="button--secondary" onClick={onCancel} disabled={saving}>Отмена</button>
      </div>
    </form>
  )
}
