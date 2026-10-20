import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { EmptyState } from '../../shared/ui/EmptyState'
import { Segmented } from '../../shared/ui/Segmented'
import { CardDialog, RequiredMark } from '../interactions/cardUi'
import { agreementsApi, formatDate, type ActivityKind } from '../agreements/agreementsApi'
import { CommandError, useAccessErrorHandler, useCommandKey } from '../agreements/agreementUi'
import { reportingApi, type AgreementRate, type AgreementRateInput } from '../agreements/reportingApi'
import {
  formatMoney,
  parseMoney,
  referenceCategories,
  referenceCategoryLabels,
  referenceOptions,
  type ReferenceCategory,
  type ReferenceItem
} from '../agreements/reportingModel'
import type { SessionHandlers } from './adminShared'

type Load<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T }
  | { kind: 'failed'; error: unknown }

type ReferenceEditor = { item: ReferenceItem | null; name: string; baseAmount: string }

const categoryOptions = referenceCategories.map((value) => ({ value, label: referenceCategoryLabels[value] }))

const categoryHints: Record<ReferenceCategory, string> = {
  EXECUTOR: 'Организации группы компаний, которые проводят мероприятия. База — сумма, от которой свод отчёта считает порог (например, выручка); без базы исполнитель в своде помечен «нет базы».',
  EDUCATION_LEVEL: 'Уровни образования участников мероприятий: по ним идёт свод отчёта и могут различаться ставки.',
  UNIT: 'Единицы количества мероприятия (человек, часов, программ): по единице подбирается ставка оценки.'
}

export const ReportingReferencesTab = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [category, setCategory] = useState<ReferenceCategory>('EXECUTOR')
  const [state, setState] = useState<Load<ReferenceItem[]>>({ kind: 'loading' })
  const [editor, setEditor] = useState<ReferenceEditor | null>(null)
  const [archiving, setArchiving] = useState<ReferenceItem | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', value: await reportingApi.references(true) })
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setState({ kind: 'failed', error: caught })
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const run = async (command: () => Promise<unknown>) => {
    setSaving(true)
    setError(null)
    try {
      await command()
      commandKey.settle()
      setEditor(null)
      await load()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (editor === null) {
      return
    }
    const baseAmount = category === 'EXECUTOR' ? parseMoney(editor.baseAmount) : null
    if (editor.item === null) {
      const payload = { category, name: editor.name.trim(), baseAmount }
      void run(() => reportingApi.createReference(payload, commandKey.keyFor(payload)))
    } else {
      const item = editor.item
      const payload = { version: item.version, name: editor.name.trim(), archived: item.archived, baseAmount }
      void run(() => reportingApi.updateReference(item.id, payload, commandKey.keyFor({ id: item.id, ...payload })))
    }
  }

  const toggleArchive = () => {
    const item = archiving
    setArchiving(null)
    if (item === null) {
      return
    }
    const payload = { version: item.version, name: item.name, archived: !item.archived, baseAmount: item.baseAmount }
    void run(() => reportingApi.updateReference(item.id, payload, commandKey.keyFor({ id: item.id, ...payload })))
  }

  const items = state.kind === 'ready' ? state.value.filter((item) => item.category === category) : []

  return (
    <>
      <div className="admin-toolbar">
        <Segmented label="Справочник" options={categoryOptions} value={category} onChange={(value) => { setCategory(value); setError(null) }} />
        <button type="button" onClick={() => { setError(null); setEditor({ item: null, name: '', baseAmount: '' }) }}>Добавить</button>
      </div>
      <p className="admin-form__hint">{categoryHints[category]} Значение в архиве остаётся у мероприятий, где уже выбрано, но не предлагается для новых.</p>
      {state.kind === 'loading' && <p role="status">Загружаем справочники…</p>}
      {state.kind === 'failed' && <CommandError error={state.error} fallback="Не удалось загрузить справочники." onRetry={() => void load()} />}
      {error !== null && editor === null && <CommandError error={error} fallback="Изменение не сохранено." />}
      {state.kind === 'ready' && items.length === 0 && <EmptyState text="Значений пока нет." />}
      {items.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table" aria-label={referenceCategoryLabels[category]}>
            <thead>
              <tr>
                <th scope="col">Название</th>
                {category === 'EXECUTOR' && <th scope="col">База, ₽</th>}
                <th scope="col" className="admin-table__actions"><span className="catalogs-sr">Действия</span></th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td>
                    <span className="admin-table__main">{item.name}</span>
                    {item.archived && <> <span className="admin-chip admin-chip--warn">В архиве</span></>}
                  </td>
                  {category === 'EXECUTOR' && <td>{item.baseAmount === null ? 'не задана' : formatMoney(item.baseAmount)}</td>}
                  <td className="admin-table__actions">
                    <ActionMenu
                      label={`Действия: «${item.name}»`}
                      items={[
                        ...(item.archived ? [] : [{
                          label: category === 'EXECUTOR' ? 'Изменить название и базу' : 'Переименовать',
                          onSelect: () => { setError(null); setEditor({ item, name: item.name, baseAmount: item.baseAmount?.toString() ?? '' }) }
                        }]),
                        { label: item.archived ? 'Восстановить' : 'В архив', onSelect: () => setArchiving(item) }
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <CardDialog
        open={editor !== null}
        title={editor?.item ? `Изменить «${editor.item.name}»` : `Добавить: ${referenceCategoryLabels[category].toLowerCase()}`}
        onClose={() => (saving ? undefined : setEditor(null))}
      >
        {editor !== null && (
          <form className="admin-form" onSubmit={submit}>
            <label>
              <span>Название<RequiredMark /></span>
              <input value={editor.name} required maxLength={200} disabled={saving} onChange={(event) => setEditor({ ...editor, name: event.target.value })} />
            </label>
            {category === 'EXECUTOR' && (
              <label>
                База для свода против порога, ₽
                <input type="number" min={0} step={0.01} inputMode="decimal" value={editor.baseAmount} disabled={saving}
                  onChange={(event) => setEditor({ ...editor, baseAmount: event.target.value })} />
              </label>
            )}
            {error !== null && <CommandError error={error} fallback="Изменение не сохранено." />}
            <div className="admin-form__actions">
              <button type="button" className="button--secondary" disabled={saving} onClick={() => setEditor(null)}>Отмена</button>
              <button type="submit" disabled={saving || editor.name.trim() === ''}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
            </div>
          </form>
        )}
      </CardDialog>
      <ConfirmDialog
        open={archiving !== null}
        title={archiving === null ? '' : `${archiving.archived ? 'Восстановить' : 'Перенести в архив'} «${archiving.name}»?`}
        description={archiving?.archived === true
          ? 'Значение снова можно будет выбирать у мероприятий.'
          : 'Значение не будет предлагаться для новых мероприятий; у существующих оно останется.'}
        confirmLabel={archiving?.archived === true ? 'Восстановить' : 'В архив'}
        onConfirm={toggleArchive}
        onCancel={() => setArchiving(null)}
      />
    </>
  )
}

type RateCatalogs = { rates: AgreementRate[]; kinds: ActivityKind[]; references: ReferenceItem[] }

type RateDraft = { rate: AgreementRate | null; kindId: string; unitId: string; educationLevelId: string; effectiveFrom: string; value: string }

const rateDraft = (rate: AgreementRate | null): RateDraft => ({
  rate,
  kindId: rate?.kindId ?? '',
  unitId: rate?.unitId ?? '',
  educationLevelId: rate?.educationLevelId ?? '',
  effectiveFrom: rate?.effectiveFrom ?? '',
  value: rate?.rate.toString() ?? ''
})

export const ReportingRatesTab = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [state, setState] = useState<Load<RateCatalogs>>({ kind: 'loading' })
  const [draft, setDraft] = useState<RateDraft | null>(null)
  const [deleting, setDeleting] = useState<AgreementRate | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()

  const load = useCallback(async () => {
    try {
      const [rates, kinds, references] = await Promise.all([reportingApi.rates(), agreementsApi.listKinds(true), reportingApi.references(true)])
      setState({ kind: 'ready', value: { rates, kinds, references } })
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setState({ kind: 'failed', error: caught })
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const run = async (command: () => Promise<unknown>) => {
    setSaving(true)
    setError(null)
    try {
      await command()
      commandKey.settle()
      setDraft(null)
      await load()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (draft === null) {
      return
    }
    const payload: AgreementRateInput = {
      version: draft.rate?.version ?? null,
      kindId: draft.kindId,
      unitId: draft.unitId,
      educationLevelId: draft.educationLevelId === '' ? null : draft.educationLevelId,
      effectiveFrom: draft.effectiveFrom,
      rate: parseMoney(draft.value) ?? 0
    }
    const current = draft.rate
    void run(() => (current === null
      ? reportingApi.createRate(payload, commandKey.keyFor(payload))
      : reportingApi.updateRate(current.id, payload, commandKey.keyFor({ id: current.id, ...payload }))))
  }

  const confirmDelete = () => {
    const rate = deleting
    setDeleting(null)
    if (rate !== null) {
      void run(() => reportingApi.deleteRate(rate.id, rate.version, commandKey.keyFor({ id: rate.id, version: rate.version, delete: true })))
    }
  }

  if (state.kind === 'loading') {
    return <p role="status">Загружаем ставки…</p>
  }
  if (state.kind === 'failed') {
    return <CommandError error={state.error} fallback="Не удалось загрузить ставки." onRetry={() => void load()} />
  }
  const { rates, kinds, references } = state.value
  const kindOptions = kinds.filter((kind) => !kind.archived || kind.id === draft?.rate?.kindId)

  return (
    <>
      <div className="admin-toolbar">
        <p className="admin-form__hint">
          Ставка — рублей за единицу для вида мероприятия и единицы (и, если нужно, уровня образования) с даты начала действия.
          По ставке отчёт оценивает мероприятия: количество × ставка, действующая на дату мероприятия; ставка без уровня
          подходит любому уровню. По умолчанию ставок нет.
        </p>
        <button type="button" onClick={() => { setError(null); setDraft(rateDraft(null)) }}>Добавить ставку</button>
      </div>
      {error !== null && draft === null && <CommandError error={error} fallback="Изменение не сохранено." />}
      {rates.length === 0 ? <EmptyState text="Ставок пока нет." /> : (
        <div className="admin-table-wrap">
          <table className="admin-table" aria-label="Ставки оценки">
            <thead>
              <tr>
                <th scope="col">Вид мероприятия</th>
                <th scope="col">Единица</th>
                <th scope="col">Уровень</th>
                <th scope="col">Действует с</th>
                <th scope="col">Ставка, ₽ за единицу</th>
                <th scope="col" className="admin-table__actions"><span className="catalogs-sr">Действия</span></th>
              </tr>
            </thead>
            <tbody>
              {rates.map((rate) => (
                <tr key={rate.id}>
                  <td className="admin-table__main">{rate.kindName}</td>
                  <td>{rate.unitName}</td>
                  <td>{rate.educationLevelName ?? 'любой'}</td>
                  <td>{formatDate(rate.effectiveFrom)}</td>
                  <td>{formatMoney(rate.rate)}</td>
                  <td className="admin-table__actions">
                    <ActionMenu
                      label={`Действия: ставка «${rate.kindName}», ${rate.unitName}`}
                      items={[
                        { label: 'Изменить', onSelect: () => { setError(null); setDraft(rateDraft(rate)) } },
                        { label: 'Удалить', onSelect: () => setDeleting(rate) }
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <CardDialog open={draft !== null} title={draft?.rate ? 'Изменить ставку' : 'Новая ставка'} onClose={() => (saving ? undefined : setDraft(null))}>
        {draft !== null && (
          <form className="admin-form" onSubmit={submit} aria-label="Ставка оценки">
            <label>
              <span>Вид мероприятия<RequiredMark /></span>
              <select value={draft.kindId} required disabled={saving} onChange={(event) => setDraft({ ...draft, kindId: event.target.value })}>
                <option value="">Выберите вид</option>
                {kindOptions.map((kind) => <option key={kind.id} value={kind.id}>{kind.name}{kind.archived ? ' (в архиве)' : ''}</option>)}
              </select>
            </label>
            <label>
              <span>Единица<RequiredMark /></span>
              <select value={draft.unitId} required disabled={saving} onChange={(event) => setDraft({ ...draft, unitId: event.target.value })}>
                <option value="">Выберите единицу</option>
                {referenceOptions(references, 'UNIT', draft.rate?.unitId).map((item) => (
                  <option key={item.id} value={item.id}>{item.name}{item.archived ? ' (в архиве)' : ''}</option>
                ))}
              </select>
            </label>
            <label>
              Уровень образования (необязательно)
              <select value={draft.educationLevelId} disabled={saving} onChange={(event) => setDraft({ ...draft, educationLevelId: event.target.value })}>
                <option value="">Любой уровень</option>
                {referenceOptions(references, 'EDUCATION_LEVEL', draft.rate?.educationLevelId).map((item) => (
                  <option key={item.id} value={item.id}>{item.name}{item.archived ? ' (в архиве)' : ''}</option>
                ))}
              </select>
            </label>
            <label>
              <span>Действует с<RequiredMark /></span>
              <input type="date" value={draft.effectiveFrom} required disabled={saving} onChange={(event) => setDraft({ ...draft, effectiveFrom: event.target.value })} />
            </label>
            <label>
              <span>Ставка, ₽ за единицу<RequiredMark /></span>
              <input type="number" min={0} step={0.01} inputMode="decimal" value={draft.value} required disabled={saving}
                onChange={(event) => setDraft({ ...draft, value: event.target.value })} />
            </label>
            {error !== null && <CommandError error={error} fallback="Ставка не сохранена." />}
            <div className="admin-form__actions">
              <button type="button" className="button--secondary" disabled={saving} onClick={() => setDraft(null)}>Отмена</button>
              <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
            </div>
          </form>
        )}
      </CardDialog>
      <ConfirmDialog
        open={deleting !== null}
        title="Удалить ставку?"
        description={deleting === null ? '' : `Ставка «${deleting.kindName}», ${deleting.unitName}, с ${formatDate(deleting.effectiveFrom)} будет удалена; мероприятия без подходящей ставки отчёт покажет без оценки.`}
        confirmLabel="Удалить"
        onConfirm={confirmDelete}
        onCancel={() => setDeleting(null)}
      />
    </>
  )
}
