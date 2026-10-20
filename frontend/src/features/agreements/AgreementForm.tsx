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
import { useRecordDraft } from '../interactions/formDraft'

type Named = { id: string; name: string }

type AgreementFormProps = {
  organizationId: string
  agreement?: Agreement
  options: AgreementOptions
  defaultInteractionIds?: string[]
  onSaved: (agreement: Agreement) => void
  onCancel: () => void
  // Н-А1: при конфликте версии перечитать договор на месте; введённое остаётся в черновике формы.
  onReload?: () => void
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
  onReload,
  handleAccessError
}: AgreementFormProps) => {
  const creating = agreement === undefined
  const initial = {
    number: agreement?.number ?? '',
    signed: false,
    concludedOn: agreement?.concludedOn ?? '',
    validUntil: agreement?.validUntil ?? '',
    sentOn: agreement?.sentOn ?? '',
    plannedKind: agreement?.plannedKind ?? '',
    plannedOn: agreement?.plannedOn ?? '',
    parties: agreement?.parties ?? '',
    universitySignerId: agreement === undefined ? defaultSignerId(options.contacts) : agreement.universitySigner?.id ?? '',
    schoolSignerId: agreement?.schoolSigner?.id ?? '',
    interactionIds: agreement?.interactions.map((item) => item.id) ?? defaultInteractionIds ?? [],
    directionIds: agreement?.directions.map((item) => item.id) ?? [],
    programIds: agreement?.programs.map((item) => item.id) ?? [],
    productIds: agreement?.products.map((item) => item.id) ?? [],
    fileId: ''
  }
  const draft = useRecordDraft(
    agreement === undefined ? `agreement:new:${organizationId}` : `agreement:${agreement.id}`,
    agreement === undefined ? 'новый договор с вузом' : `реквизиты договора № ${agreement.number}`,
    initial
  )
  const { set } = draft
  const { number, signed, concludedOn, validUntil, sentOn, plannedOn, parties, universitySignerId, schoolSignerId, interactionIds,
    directionIds, programIds, productIds, fileId } = draft.values
  const plannedKind = draft.values.plannedKind as PlanKind | ''
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
      draft.clear()
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
            <input type="radio" name="agreement-initial-status" checked={!signed} onChange={() => set({ signed: false })} />
            <span>Проект — пойдёт на согласование с вузом</span>
          </label>
          <label>
            <input type="radio" name="agreement-initial-status" checked={signed} onChange={() => set({ signed: true })} />
            <span>Уже подписан</span>
          </label>
        </fieldset>
      )}
      <div className="agreement-form__grid">
        <label>
          <span>Номер договора<span className="required-mark" aria-hidden="true"> *</span></span>
          <input value={number} required maxLength={100} onChange={(event) => set({ number: event.target.value })} />
        </label>
        <label>
          <span>Дата подписания{creating && signed && <span className="required-mark" aria-hidden="true"> *</span>}</span>
          <input
            type="date"
            value={concludedOn}
            required={creating && signed}
            max={validUntil !== '' && validUntil < todayInMoscow() ? validUntil : todayInMoscow()}
            onChange={(event) => set({ concludedOn: event.target.value })}
          />
        </label>
        <label>
          Действует до
          <input type="date" value={validUntil} min={concludedOn || undefined} onChange={(event) => set({ validUntil: event.target.value })} />
        </label>
        <label>
          Отправлен на согласование
          <input type="date" value={sentOn} onChange={(event) => set({ sentOn: event.target.value })} />
        </label>
        <label>
          Подписант вуза
          <select value={universitySignerId} onChange={(event) => set({ universitySignerId: event.target.value })}>
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
          <select value={schoolSignerId} onChange={(event) => set({ schoolSignerId: event.target.value })}>
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
              set({ plannedKind: next })
              if (next === '') {
                set({ plannedOn: '' })
              } else if (next === 'RENEWAL' && plannedOn === '' && validUntil !== '') {
                set({ plannedOn: validUntil })
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
            onChange={(event) => set({ plannedOn: event.target.value })}
          />
          {plannedKind === 'RENEWAL' && validUntil !== '' && (
            <span className="agreement-form__hint">{`Договор действует до ${formatDate(validUntil)}; продление планируют от этой даты.`}</span>
          )}
        </label>
        <label className="agreement-form__wide">
          Стороны
          <textarea value={parties} maxLength={2000} rows={2} onChange={(event) => set({ parties: event.target.value })} />
        </label>
        {creating && (
          <label className="agreement-form__wide">
            Файл договора из документов взаимодействий
            <select value={fileId} onChange={(event) => set({ fileId: event.target.value })}>
              <option value="">Не выбран</option>
              {options.attachments.map((item) => (
                <option key={item.id} value={item.id}>{item.originalName} — {item.interactionTitle}, {item.stageName}</option>
              ))}
            </select>
            <span className="agreement-form__hint">Можно выбрать проверенный документ из взаимодействий вуза или загрузить файл после сохранения договора.</span>
          </label>
        )}
      </div>
      <ChoiceList legend="Связанные взаимодействия вуза" items={works} selected={interactionIds} empty="У вуза пока нет взаимодействий." onChange={(ids) => set({ interactionIds: ids })} />
      <fieldset className="agreement-form__subject">
        <legend>Предмет договора</legend>
        <ChoiceList
          legend="Направления"
          items={withSelected(options.directions, agreement?.directions)}
          selected={directionIds}
          empty="Нет направлений."
          onChange={(ids) => set({ directionIds: ids })}
        />
        <ChoiceList
          legend="Программы"
          items={withSelected(options.programs, agreement?.programs)}
          selected={programIds}
          empty="Нет программ."
          onChange={(ids) => set({ programIds: ids })}
        />
        <ChoiceList
          legend="Продукты"
          items={withSelected(options.products, agreement?.products)}
          selected={productIds}
          empty="Нет продуктов."
          onChange={(ids) => set({ productIds: ids })}
        />
      </fieldset>
      {error !== null && <CommandError error={error} fallback="Договор не сохранён." onReload={onReload === undefined ? undefined : () => { setError(null); onReload() }} />}
      <div className="agreement-form__actions">
        <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить договор'}</button>
        <button type="button" className="button--secondary" onClick={() => { draft.clear(); onCancel() }} disabled={saving}>Отмена</button>
      </div>
    </form>
  )
}
