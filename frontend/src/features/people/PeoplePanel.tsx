import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, isConflict } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { saveFile } from '../admin/saveFile'
import { CardDialog, CardSection, RequiredMark } from '../interactions/cardUi'
import { useRecordDraft } from '../interactions/formDraft'
import { type AccessHandlers, formatDate, handledAccessError } from '../work/workShared'
import {
  confirmationLabels,
  internKindLabels,
  internshipResultLabels,
  peopleApi,
  qualificationResultLabels,
  type InternshipRecord,
  type PeopleImportResult,
  type PeopleKind,
  type PeopleOverview,
  type QualificationRecord,
  type TeachingRecord
} from './peopleApi'
import { draftFrom, frdoText, hoursText, payloadFrom, peopleFields, type Draft } from './peopleForms'
import '../sources/sources.css'
import './people.css'

type Props = AccessHandlers & {
  organizationId: string
  interactionId?: string
}

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; overview: PeopleOverview }
  | { kind: 'failed'; requestId?: string }

type AnyRecord = TeachingRecord | InternshipRecord | QualificationRecord

type Editing = { kind: PeopleKind; record: AnyRecord | null }

const titles: Record<PeopleKind, string> = {
  teaching: 'Преподавание сотрудниками',
  internships: 'Стажировки',
  qualifications: 'Повышение квалификации преподавателей'
}

const period = (record: { startsOn: string; endsOn: string }) => `${formatDate(record.startsOn)} – ${formatDate(record.endsOn)}`

export const PeoplePanel = ({ organizationId, interactionId, onSessionExpired, onProfileUnavailable }: Props) => {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [editing, setEditing] = useState<Editing | null>(null)
  const [removing, setRemoving] = useState<Editing | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const version = useRef(0)

  const handled = useCallback((value: unknown) => handledAccessError(value, { onSessionExpired, onProfileUnavailable }),
    [onProfileUnavailable, onSessionExpired])

  const load = useCallback(async () => {
    const current = ++version.current
    try {
      const overview = await peopleApi.overview(organizationId, interactionId)
      if (current === version.current) {
        setState({ kind: 'ready', overview })
      }
    } catch (value) {
      if (current === version.current && !handled(value)) {
        setState({ kind: 'failed', requestId: value instanceof ApiError ? value.requestId : undefined })
      }
    }
  }, [handled, interactionId, organizationId])

  useEffect(() => {
    void load()
  }, [load])

  if (state.kind === 'loading') {
    return <p role="status">Загружаем учёт людей и часов…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="organizations-message organizations-message--error" role="alert">
        <p>Не удалось загрузить учёт людей и часов.</p>
        <SupportDetails requestId={state.requestId} />
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }

  const { overview } = state
  const canEdit = overview.canEdit
  const units = overview.settings
  const remove = async () => {
    if (removing === null || removing.record === null) {
      return
    }
    const target = removing
    setRemoving(null)
    setError(null)
    try {
      await peopleApi.remove(target.kind, target.record!.id, target.record!.version)
      setNotice('Запись удалена.')
      await load()
    } catch (value) {
      if (!handled(value)) {
        setError(value)
      }
    }
  }

  const actions = (kind: PeopleKind, record: AnyRecord) => canEdit && (
    <td className="people__actions">
      {!('anonymized' in record && record.anonymized) && (
        <button type="button" className="button--secondary" onClick={() => setEditing({ kind, record })}>Изменить</button>
      )}
      <button type="button" className="button--secondary" onClick={() => setRemoving({ kind, record })}>Удалить</button>
    </td>
  )

  const addButton = (kind: PeopleKind) => canEdit && (
    <button type="button" className="button--secondary" onClick={() => { setNotice(null); setEditing({ kind, record: null }) }}>
      Добавить запись
    </button>
  )

  return (
    <div className="people">
      <p className="people__intro">
        Учёт людей и часов по вузу: преподавание сотрудниками, стажировки, повышение квалификации преподавателей.
        Единицы часа по умолчанию задаёт администратор; в каждой записи хранится своя.
        {interactionId === undefined ? '' : ' Показаны записи этого взаимодействия; все записи вуза — в карточке вуза.'}
      </p>
      {notice !== null && <p className="notice" role="status">{notice}</p>}
      {error !== null && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>{error instanceof ApiError ? error.message : 'Не удалось выполнить действие.'}</p>
          <SupportDetails requestId={error instanceof ApiError ? error.requestId : undefined} />
        </div>
      )}

      <CardSection title={titles.teaching} count={overview.teaching.length} defaultOpen>
        {addButton('teaching')}
        {overview.teaching.length === 0 ? <p>Записей нет.</p> : (
          <div className="reports__table-scroll" role="region" aria-label={titles.teaching} tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Исполнитель</th>
                  <th scope="col">Дисциплина</th>
                  <th scope="col">Период</th>
                  <th scope="col">Часы</th>
                  <th scope="col">Группа</th>
                  <th scope="col">Подтверждение</th>
                  <th scope="col">Договор</th>
                  {canEdit && <th scope="col"><span className="visually-hidden">Действия</span></th>}
                </tr>
              </thead>
              <tbody>
                {overview.teaching.map((record) => (
                  <tr key={record.id}>
                    <th scope="row">{record.performerName}{record.performerCompany === null ? '' : ` (${record.performerCompany})`}</th>
                    <td>{record.discipline}</td>
                    <td>{period(record)}</td>
                    <td>{hoursText(record.hours, record.hourUnit)}</td>
                    <td>{record.studentGroup ?? '—'}</td>
                    <td>
                      {confirmationLabels[record.confirmationStatus]}
                      {record.confirmationDocument === null ? '' : `: ${record.confirmationDocument}`}
                    </td>
                    <td>
                      {record.agreementNumber === null ? '—' : `№ ${record.agreementNumber}`}
                      {record.agreementActivityTitle === null ? '' : `, ${record.agreementActivityTitle}`}
                    </td>
                    {actions('teaching', record)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardSection>

      <CardSection title={titles.internships} count={overview.internships.length} defaultOpen={overview.internships.length > 0}>
        {addButton('internships')}
        {overview.internships.length === 0 ? <p>Записей нет.</p> : (
          <div className="reports__table-scroll" role="region" aria-label={titles.internships} tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Стажёр</th>
                  <th scope="col">Место</th>
                  <th scope="col">Наставник</th>
                  <th scope="col">Период</th>
                  <th scope="col">Часы стажёра</th>
                  <th scope="col">Часы наставника</th>
                  <th scope="col">Итог</th>
                  {canEdit && <th scope="col"><span className="visually-hidden">Действия</span></th>}
                </tr>
              </thead>
              <tbody>
                {overview.internships.map((record) => (
                  <tr key={record.id}>
                    <th scope="row">{record.internName}, {internKindLabels[record.internKind]}</th>
                    <td>{record.place}</td>
                    <td>{record.mentorName ?? '—'}</td>
                    <td>{period(record)}</td>
                    <td>{hoursText(record.internHours, record.hourUnit)}</td>
                    <td>{hoursText(record.mentorHours, record.hourUnit)}</td>
                    <td>
                      {internshipResultLabels[record.result]}{record.employed ? ', трудоустроен' : ''}
                      {record.document === null ? '' : `; ${record.document}`}
                    </td>
                    {actions('internships', record)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardSection>

      <CardSection title={titles.qualifications} count={overview.qualifications.length} defaultOpen={overview.qualifications.length > 0}>
        {addButton('qualifications')}
        {overview.qualifications.length === 0 ? <p>Записей нет.</p> : (
          <div className="reports__table-scroll" role="region" aria-label={titles.qualifications} tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Преподаватель</th>
                  <th scope="col">Программа</th>
                  <th scope="col">Период</th>
                  <th scope="col">Часы</th>
                  <th scope="col">Итог</th>
                  <th scope="col">Удостоверение</th>
                  <th scope="col">ФРДО</th>
                  <th scope="col">Поток Moodle</th>
                  {canEdit && <th scope="col"><span className="visually-hidden">Действия</span></th>}
                </tr>
              </thead>
              <tbody>
                {overview.qualifications.map((record) => (
                  <tr key={record.id}>
                    <th scope="row">{record.personName}</th>
                    <td>{record.program}</td>
                    <td>{period(record)}</td>
                    <td>{hoursText(record.hours, record.hourUnit)}</td>
                    <td>{qualificationResultLabels[record.result]}</td>
                    <td>
                      {record.certificateNumber ?? '—'}
                      {record.certificateDate === null ? '' : ` от ${formatDate(record.certificateDate)}`}
                    </td>
                    <td className={record.frdoOverdue ? 'people__overdue' : undefined}>{frdoText(record, formatDate)}</td>
                    <td>{record.teacherRosterTitle ?? '—'}</td>
                    {actions('qualifications', record)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {canEdit && (
          <QualificationImport
            overview={overview}
            interactionId={interactionId}
            onImported={(created) => {
              setNotice(`Загружено записей: ${created}.`)
              void load()
            }}
            handled={handled}
          />
        )}
      </CardSection>

      {editing !== null && (
        <CardDialog open title={`${titles[editing.kind]}: ${editing.record === null ? 'новая запись' : 'изменить запись'}`}
          onClose={() => setEditing(null)}>
          <PeopleForm
            kind={editing.kind}
            record={editing.record === null
              ? null
              : (overview[editing.kind] as AnyRecord[]).find((item) => item.id === editing.record?.id) ?? editing.record}
            overview={overview}
            defaults={{
              hourUnit: editing.kind === 'teaching' ? units.teachingUnit : editing.kind === 'internships' ? units.internshipUnit : units.qualificationUnit,
              interactionId
            }}
            handled={handled}
            onSaved={() => {
              setEditing(null)
              setNotice('Запись сохранена.')
              void load()
            }}
            onCancel={() => setEditing(null)}
            onReload={() => void load()}
          />
        </CardDialog>
      )}
      <ConfirmDialog
        open={removing !== null}
        title="Удалить запись?"
        description="Запись исчезнет из учёта и отчётов. Удаление записывается в журнал."
        confirmLabel="Удалить"
        onConfirm={() => void remove()}
        onCancel={() => setRemoving(null)}
      />
    </div>
  )
}

type FormProps = {
  kind: PeopleKind
  record: AnyRecord | null
  overview: PeopleOverview
  defaults: { hourUnit: string; interactionId?: string }
  handled: (error: unknown) => boolean
  onSaved: () => void
  onCancel: () => void
  onReload: () => void
}

const PeopleForm = ({ kind, record, overview, defaults, handled, onSaved, onCancel, onReload }: FormProps) => {
  const recordDraft = useRecordDraft<Draft>(
    record === null ? `people:${kind}:new:${overview.organizationId}:${defaults.interactionId ?? ''}` : `people:${kind}:${record.id}`,
    `${record === null ? 'новая запись' : 'запись'} «${titles[kind]}» в «Людях и часах»`,
    draftFrom(kind, record as Record<string, unknown> | null, defaults)
  )
  const draft = recordDraft.values
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError(null)
    try {
      const payload = payloadFrom(kind, draft, record === null ? null : record.version)
      if (record === null) {
        await peopleApi.create(kind, overview.organizationId, payload)
      } else {
        await peopleApi.update(kind, record.id, payload)
      }
      recordDraft.clear()
      onSaved()
    } catch (value) {
      if (!handled(value)) {
        setError(value)
      }
    } finally {
      setSaving(false)
    }
  }

  const errors = error instanceof ApiError ? error.fieldErrors ?? {} : {}

  return (
    <form className="source-form people__form" onSubmit={(event) => void submit(event)}>
      {peopleFields[kind].map((field) => {
        const value = draft[field.key]
        const fieldError = errors[field.key]
        const update = (next: string | boolean) => recordDraft.set({ [field.key]: next })
        if (field.type === 'checkbox') {
          return (
            <label key={field.key} className="checkbox-field">
              <input type="checkbox" checked={value === true} disabled={saving} onChange={(event) => update(event.target.checked)} />
              {field.label}
            </label>
          )
        }
        return (
          <label key={field.key}>
            <span>{field.label}{field.required && <RequiredMark />}</span>
            {field.type === 'select' ? (
              <select value={String(value)} required={field.required} disabled={saving} onChange={(event) => update(event.target.value)}>
                {(field.empty !== undefined || !field.required) && <option value="">{field.empty ?? '—'}</option>}
                {field.choices?.(overview.options).map((choice) => <option key={choice.value} value={choice.value}>{choice.label}</option>)}
              </select>
            ) : (
              <input
                type={field.type === 'hours' ? 'number' : field.type}
                step={field.type === 'hours' ? '0.1' : undefined}
                min={field.type === 'hours' ? '0' : undefined}
                maxLength={field.type === 'text' || field.type === 'email' ? 300 : undefined}
                required={field.required}
                value={String(value)}
                disabled={saving}
                onChange={(event) => update(event.target.value)}
              />
            )}
            {field.hint !== undefined && <span className="data-sources__hint">{field.hint}</span>}
            {fieldError !== undefined && <span className="field-error" role="alert">{fieldError}</span>}
          </label>
        )
      })}
      {error !== null && (
        <div className="organizations-message organizations-message--error source-form__wide" role="alert">
          <p>{error instanceof ApiError ? error.message : 'Не удалось сохранить запись.'}</p>
          <SupportDetails requestId={error instanceof ApiError ? error.requestId : undefined} />
          {isConflict(error) && (
            <button type="button" onClick={() => { setError(null); onReload() }}>Обновить карточку</button>
          )}
        </div>
      )}
      <div className="source-form__actions">
        <button type="button" className="button--secondary" disabled={saving} onClick={() => { recordDraft.clear(); onCancel() }}>Отмена</button>
        <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
      </div>
    </form>
  )
}

type ImportProps = {
  overview: PeopleOverview
  interactionId?: string
  onImported: (created: number) => void
  handled: (error: unknown) => boolean
}

const QualificationImport = ({ overview, interactionId, onImported, handled }: ImportProps) => {
  const [file, setFile] = useState<File | null>(null)
  const [roster, setRoster] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<PeopleImportResult | null>(null)
  const [error, setError] = useState<unknown>(null)

  const run = async (dryRun: boolean) => {
    if (file === null) {
      return
    }
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      const outcome = await peopleApi.importQualifications(overview.organizationId, file, { dryRun, interactionId, teacherRosterId: roster })
      setResult(outcome)
      if (outcome.applied) {
        onImported(outcome.created)
      }
    } catch (value) {
      if (!handled(value)) {
        setError(value)
      }
    } finally {
      setBusy(false)
    }
  }

  const template = async () => {
    try {
      saveFile(await peopleApi.template(), 'Шаблон_повышение_квалификации.xlsx')
    } catch (value) {
      if (!handled(value)) {
        setError(value)
      }
    }
  }

  return (
    <section className="people__import" aria-labelledby="people-import-title">
      <h6 id="people-import-title">Загрузить список из XLSX</h6>
      <p className="data-sources__hint">
        Заполните шаблон: одна строка — один преподаватель. Сначала «Проверить», затем «Загрузить». При ошибке в любой строке
        ничего не загружается.
      </p>
      <div className="source-panel__row">
        <button type="button" className="button--secondary" onClick={() => void template()}>Скачать шаблон</button>
        <input type="file" accept=".xlsx" aria-label="Файл XLSX со списком" disabled={busy}
          onChange={(event) => { setFile(event.target.files?.[0] ?? null); setResult(null) }} />
        <select value={roster} aria-label="Поток Moodle для всех строк" disabled={busy} onChange={(event) => setRoster(event.target.value)}>
          <option value="">Без потока Moodle</option>
          {overview.options.teacherRosters.map((item) => <option key={item.id} value={item.id}>{item.title}</option>)}
        </select>
        <button type="button" className="button--secondary" disabled={busy || file === null} onClick={() => void run(true)}>Проверить</button>
        <button type="button" disabled={busy || file === null} onClick={() => void run(false)}>Загрузить</button>
      </div>
      {result !== null && (
        <div role="status">
          <p>
            {result.applied
              ? `Загружено записей: ${result.created} из ${result.rows}.`
              : result.errors.length === 0
                ? `Ошибок нет, строк: ${result.rows}. Можно загружать.`
                : `Найдены ошибки (${result.errors.length}); ничего не загружено.`}
          </p>
          {result.errors.length > 0 && (
            <ul className="people__errors">
              {result.errors.map((item, index) => <li key={`${item.row}:${index}`}>Строка {item.row}: {item.message}</li>)}
            </ul>
          )}
        </div>
      )}
      {error !== null && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>{error instanceof ApiError ? error.message : 'Не удалось загрузить файл.'}</p>
          <SupportDetails requestId={error instanceof ApiError ? error.requestId : undefined} />
        </div>
      )}
    </section>
  )
}
