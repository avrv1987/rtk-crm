import { useCallback, useEffect, useId, useState, type FormEvent } from 'react'
import { apiClient, type Me, type Organization } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import {
  activityStatusLabels,
  agreementStatusLabels,
  agreementsApi,
  formatDate,
  formatInstant,
  formatPeriod,
  saveBlob,
  type ActivityKind,
  type ActivityStatus,
  type Agreement,
  type AgreementActivity,
  type AgreementActivityInput,
  type AgreementInput,
  type AgreementOptions,
  type AgreementStatus,
  type AgreementSummary
} from './agreementsApi'
import { CommandError, useAccessErrorHandler, useCommandKey } from './agreementUi'
import './agreements.css'

type AgreementsPanelProps = {
  organizationId: Organization['id']
  role: Me['role']
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Load<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T }
  | { kind: 'failed'; error: unknown }

type Catalogs = {
  options: AgreementOptions
  kinds: ActivityKind[]
}

const agreementStatuses: readonly AgreementStatus[] = ['DRAFT', 'ACTIVE', 'COMPLETED', 'TERMINATED']
const activityStatuses: readonly ActivityStatus[] = ['PLANNED', 'IN_PROGRESS', 'DONE', 'CANCELLED']

const agreementTitle = (agreement: { number: string; concludedOn?: string | null }) => (
  `№ ${agreement.number}${agreement.concludedOn ? ` от ${formatDate(agreement.concludedOn)}` : ''}`
)

const volumeAndPeriod = (
  value: number | null | undefined,
  unit: string | null | undefined,
  start: string | null | undefined,
  end: string | null | undefined
) => {
  const parts = [
    value === null || value === undefined ? null : `${value}${unit ? ` ${unit}` : ''}`,
    start || end ? formatPeriod(start, end) : null
  ].filter((part) => part !== null)
  return parts.length === 0 ? 'Не указан' : parts.join('; ')
}

const orNull = (value: string) => (value.trim() === '' ? null : value.trim())
const numberOrNull = (value: string) => (value.trim() === '' ? null : Number(value))

export const AgreementsPanel = ({ organizationId, role, onSessionExpired, onProfileUnavailable }: AgreementsPanelProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [list, setList] = useState<Load<AgreementSummary[]>>({ kind: 'loading' })
  const [catalogs, setCatalogs] = useState<Load<Catalogs> | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const canEdit = role === 'USER' || role === 'LEADER'
  const titleId = useId()

  const loadList = useCallback(async () => {
    setList({ kind: 'loading' })
    try {
      setList({ kind: 'ready', value: await agreementsApi.list(organizationId) })
    } catch (error) {
      if (!handleAccessError(error)) {
        setList({ kind: 'failed', error })
      }
    }
  }, [handleAccessError, organizationId])

  const loadCatalogs = useCallback(async () => {
    setCatalogs((current) => (current?.kind === 'ready' ? current : { kind: 'loading' }))
    try {
      const [options, kinds] = await Promise.all([agreementsApi.options(organizationId), agreementsApi.listKinds(true)])
      setCatalogs({ kind: 'ready', value: { options, kinds } })
    } catch (error) {
      if (!handleAccessError(error)) {
        setCatalogs({ kind: 'failed', error })
      }
    }
  }, [handleAccessError, organizationId])

  useEffect(() => {
    void loadList()
  }, [loadList])

  useEffect(() => {
    if ((selectedId !== null || creating) && catalogs === null) {
      void loadCatalogs()
    }
  }, [catalogs, creating, loadCatalogs, selectedId])

  const saved = (agreement: Agreement) => {
    setCreating(false)
    setSelectedId(agreement.id)
    void loadList()
  }

  return (
    <section className="agreements" aria-labelledby={titleId}>
      <div className="agreements__header">
        <h4 id={titleId}>Соглашения</h4>
        {canEdit && !creating && (
          <button type="button" className="button--secondary" onClick={() => { setCreating(true); setSelectedId(null); void loadCatalogs() }}>
            Добавить соглашение
          </button>
        )}
      </div>
      <p className="agreements__hint">
        Соглашение о сотрудничестве с планом мероприятий. К мероприятиям привязываются работы вуза и подтверждающие документы из них; итоги собираются в отчёте «Реализация соглашений».
      </p>
      {list.kind === 'loading' && <p role="status">Загружаем соглашения…</p>}
      {list.kind === 'failed' && (
        <CommandError error={list.error} fallback="Не удалось загрузить соглашения." onRetry={() => void loadList()} />
      )}
      {list.kind === 'ready' && list.value.length === 0 && !creating && <p>Соглашений пока нет.</p>}
      {list.kind === 'ready' && list.value.length > 0 && (
        <ul className="agreements__list">
          {list.value.map((agreement) => (
            <li key={agreement.id}>
              <button
                type="button"
                className={`agreements__item${agreement.id === selectedId ? ' agreements__item--selected' : ''}`}
                aria-expanded={agreement.id === selectedId}
                onClick={() => { setSelectedId(agreement.id === selectedId ? null : agreement.id); setCreating(false) }}
              >
                <span className="agreements__item-title">{agreementTitle(agreement)}</span>
                <span className={`agreements__status agreements__status--${agreement.status.toLowerCase()}`}>
                  {agreementStatusLabels[agreement.status]}
                </span>
                <span className="agreements__item-meta">
                  {agreement.validUntil ? `до ${formatDate(agreement.validUntil)} · ` : ''}
                  мероприятий: {agreement.activityCount} · подтверждений: {agreement.confirmationCount}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {catalogs?.kind === 'loading' && (creating || selectedId !== null) && <p role="status">Загружаем справочники…</p>}
      {catalogs?.kind === 'failed' && (
        <CommandError error={catalogs.error} fallback="Не удалось загрузить справочники." onRetry={() => void loadCatalogs()} />
      )}
      {creating && catalogs?.kind === 'ready' && (
        <AgreementForm
          organizationId={organizationId}
          options={catalogs.value.options}
          onSaved={saved}
          onCancel={() => setCreating(false)}
          handleAccessError={handleAccessError}
        />
      )}
      {selectedId !== null && catalogs?.kind === 'ready' && (
        <AgreementDetail
          key={selectedId}
          agreementId={selectedId}
          organizationId={organizationId}
          catalogs={catalogs.value}
          canEdit={canEdit}
          onChanged={() => void loadList()}
          onRefreshCatalogs={() => void loadCatalogs()}
          handleAccessError={handleAccessError}
        />
      )}
    </section>
  )
}

type AgreementFormProps = {
  organizationId: string
  agreement?: Agreement
  options: AgreementOptions
  onSaved: (agreement: Agreement) => void
  onCancel: () => void
  handleAccessError: (error: unknown) => boolean
}

const AgreementForm = ({ organizationId, agreement, options, onSaved, onCancel, handleAccessError }: AgreementFormProps) => {
  const [number, setNumber] = useState(agreement?.number ?? '')
  const [concludedOn, setConcludedOn] = useState(agreement?.concludedOn ?? '')
  const [validUntil, setValidUntil] = useState(agreement?.validUntil ?? '')
  const [parties, setParties] = useState(agreement?.parties ?? '')
  const [status, setStatus] = useState<AgreementStatus>(agreement?.status ?? 'ACTIVE')
  const [fileId, setFileId] = useState(agreement?.file?.id ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload: AgreementInput = {
      version: agreement?.version ?? null,
      number: number.trim(),
      concludedOn: orNull(concludedOn),
      validUntil: orNull(validUntil),
      parties: orNull(parties),
      status,
      fileAttachmentId: orNull(fileId)
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
    <form className="agreement-form" onSubmit={(event) => void submit(event)} aria-label={agreement ? 'Реквизиты соглашения' : 'Новое соглашение'}>
      <div className="agreement-form__grid">
        <label>
          Номер соглашения
          <input value={number} required maxLength={100} onChange={(event) => setNumber(event.target.value)} />
        </label>
        <label>
          Статус
          <select value={status} onChange={(event) => setStatus(event.target.value as AgreementStatus)}>
            {agreementStatuses.map((item) => <option key={item} value={item}>{agreementStatusLabels[item]}</option>)}
          </select>
        </label>
        <label>
          Дата заключения
          <input type="date" value={concludedOn} max={validUntil || undefined} onChange={(event) => setConcludedOn(event.target.value)} />
        </label>
        <label>
          Действует до
          <input type="date" value={validUntil} min={concludedOn || undefined} onChange={(event) => setValidUntil(event.target.value)} />
        </label>
        <label className="agreement-form__wide">
          Стороны и подписанты
          <textarea value={parties} maxLength={2000} rows={2} onChange={(event) => setParties(event.target.value)} />
        </label>
        <label className="agreement-form__wide">
          Файл соглашения
          <select value={fileId} onChange={(event) => setFileId(event.target.value)}>
            <option value="">Не выбран</option>
            {options.attachments.map((item) => (
              <option key={item.id} value={item.id}>{item.originalName} — {item.interactionTitle}, {item.stageName}</option>
            ))}
          </select>
          <span className="agreement-form__hint">Подписанный файл загружают в работу вуза на нужном этапе; здесь выбирается уже проверенный документ.</span>
        </label>
      </div>
      {error !== null && <CommandError error={error} fallback="Соглашение не сохранено." />}
      <div className="agreement-form__actions">
        <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить соглашение'}</button>
        <button type="button" className="button--secondary" onClick={onCancel} disabled={saving}>Отмена</button>
      </div>
    </form>
  )
}

type AgreementDetailProps = {
  agreementId: string
  organizationId: string
  catalogs: Catalogs
  canEdit: boolean
  onChanged: () => void
  onRefreshCatalogs: () => void
  handleAccessError: (error: unknown) => boolean
}

type ActivityEditor = { mode: 'create' } | { mode: 'edit'; activity: AgreementActivity } | null

const AgreementDetail = ({
  agreementId,
  organizationId,
  catalogs,
  canEdit,
  onChanged,
  onRefreshCatalogs,
  handleAccessError
}: AgreementDetailProps) => {
  const [state, setState] = useState<Load<Agreement>>({ kind: 'loading' })
  const [editingAgreement, setEditingAgreement] = useState(false)
  const [editor, setEditor] = useState<ActivityEditor>(null)
  const [deleting, setDeleting] = useState<AgreementActivity | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  const [archiving, setArchiving] = useState(false)
  const deleteKey = useCommandKey()

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', value: await agreementsApi.get(agreementId) })
    } catch (error) {
      if (!handleAccessError(error)) {
        setState({ kind: 'failed', error })
      }
    }
  }, [agreementId, handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const openEditor = (next: ActivityEditor) => {
    onRefreshCatalogs()
    setEditor(next)
  }

  const refresh = () => {
    void load()
    onChanged()
  }

  const confirmDelete = async () => {
    if (deleting === null) {
      return
    }
    const target = deleting
    setDeleting(null)
    setActionError(null)
    try {
      await agreementsApi.deleteActivity(target.id, target.version, deleteKey.keyFor({ id: target.id, version: target.version }))
      deleteKey.settle()
      refresh()
    } catch (error) {
      deleteKey.settle(error)
      if (!handleAccessError(error)) {
        setActionError(error)
      }
    }
  }

  const downloadArchive = async (agreement: Agreement) => {
    setArchiving(true)
    setActionError(null)
    try {
      saveBlob(await agreementsApi.archive({ agreementId: agreement.id }), `Подтверждения_${agreement.number.replace(/[\\/:*?"<>|]/g, '_')}.zip`)
    } catch (error) {
      if (!handleAccessError(error)) {
        setActionError(error)
      }
    } finally {
      setArchiving(false)
    }
  }

  const downloadDocument = async (id: string, name: string) => {
    setActionError(null)
    try {
      saveBlob(await apiClient.downloadAttachment(id), name)
    } catch (error) {
      if (!handleAccessError(error)) {
        setActionError(error)
      }
    }
  }

  if (state.kind === 'loading') {
    return <p role="status">Загружаем соглашение…</p>
  }
  if (state.kind === 'failed') {
    return <CommandError error={state.error} fallback="Не удалось открыть соглашение." onRetry={() => void load()} />
  }
  const agreement = state.value
  const confirmationCount = agreement.activities.reduce((sum, activity) => sum + activity.attachments.length, 0)

  return (
    <article className="agreement-detail" aria-label={`Соглашение ${agreementTitle(agreement)}`}>
      <div className="agreement-detail__header">
        <h5>Соглашение {agreementTitle(agreement)}</h5>
        <span className={`agreements__status agreements__status--${agreement.status.toLowerCase()}`}>
          {agreementStatusLabels[agreement.status]}
        </span>
      </div>
      {editingAgreement ? (
        <AgreementForm
          organizationId={organizationId}
          agreement={agreement}
          options={catalogs.options}
          onSaved={(updated) => { setEditingAgreement(false); setState({ kind: 'ready', value: updated }); onChanged() }}
          onCancel={() => setEditingAgreement(false)}
          handleAccessError={handleAccessError}
        />
      ) : (
        <>
          <dl className="agreement-detail__fields">
            <div>
              <dt>Вуз</dt>
              <dd>{agreement.organizationName}</dd>
            </div>
            <div>
              <dt>Срок действия</dt>
              <dd>{formatPeriod(agreement.concludedOn, agreement.validUntil)}</dd>
            </div>
            <div>
              <dt>Стороны и подписанты</dt>
              <dd>{agreement.parties ?? 'Не указаны'}</dd>
            </div>
            <div>
              <dt>Файл соглашения</dt>
              <dd>
                {agreement.file ? (
                  <button type="button" className="agreements-link" onClick={() => void downloadDocument(agreement.file!.id, agreement.file!.originalName)}>
                    {agreement.file.originalName}
                  </button>
                ) : 'Не выбран'}
              </dd>
            </div>
          </dl>
          <div className="agreement-detail__actions">
            {canEdit && (
              <button type="button" className="button--secondary" onClick={() => { onRefreshCatalogs(); setEditingAgreement(true) }}>Изменить реквизиты</button>
            )}
            <button type="button" className="button--secondary" disabled={archiving || confirmationCount === 0} onClick={() => void downloadArchive(agreement)}>
              {archiving ? 'Готовим архив…' : `Скачать подтверждения архивом (${confirmationCount})`}
            </button>
          </div>
        </>
      )}

      <section className="agreement-plan" aria-label="План мероприятий">
        <div className="agreement-plan__header">
          <h6>План мероприятий</h6>
          {canEdit && editor === null && (
            <button type="button" onClick={() => openEditor({ mode: 'create' })}>Добавить мероприятие</button>
          )}
        </div>
        {actionError !== null && <CommandError error={actionError} fallback="Действие не выполнено." />}
        {editor?.mode === 'create' && (
          <ActivityForm
            agreementId={agreement.id}
            catalogs={catalogs}
            onSaved={() => { setEditor(null); refresh() }}
            onCancel={() => setEditor(null)}
            handleAccessError={handleAccessError}
          />
        )}
        {agreement.activities.length === 0 && editor === null && <p>Мероприятий пока нет.</p>}
        <ul className="agreement-plan__list">
          {agreement.activities.map((activity) => (
            <li key={activity.id} className="agreement-activity">
              {editor?.mode === 'edit' && editor.activity.id === activity.id ? (
                <ActivityForm
                  agreementId={agreement.id}
                  activity={activity}
                  catalogs={catalogs}
                  onSaved={() => { setEditor(null); refresh() }}
                  onCancel={() => setEditor(null)}
                  handleAccessError={handleAccessError}
                />
              ) : (
                <>
                  <div className="agreement-activity__header">
                    <span className="agreement-activity__kind">{activity.kindName}</span>
                    <span className={`agreement-activity__status agreement-activity__status--${activity.status.toLowerCase()}`}>
                      {activityStatusLabels[activity.status]}
                    </span>
                  </div>
                  <p className="agreement-activity__title">{activity.title}</p>
                  <dl className="agreement-activity__facts">
                    <div>
                      <dt>План</dt>
                      <dd>{volumeAndPeriod(activity.plannedVolume, activity.unit, activity.plannedStart, activity.plannedEnd)}</dd>
                    </div>
                    <div>
                      <dt>Факт</dt>
                      <dd>{volumeAndPeriod(activity.actualVolume, activity.unit, activity.actualStart, activity.actualEnd)}</dd>
                    </div>
                    <div>
                      <dt>Ответственный</dt>
                      <dd>{activity.responsibleName ?? 'Не назначен'}</dd>
                    </div>
                    <div>
                      <dt>Работы</dt>
                      <dd>{activity.interactions.length === 0 ? 'Не связаны' : activity.interactions.map((item) => item.title).join('; ')}</dd>
                    </div>
                  </dl>
                  <div className="agreement-activity__documents">
                    <span>Подтверждения:</span>
                    {activity.attachments.length === 0 ? (
                      <span className="agreement-activity__missing">нет</span>
                    ) : (
                      <ul>
                        {activity.attachments.map((item) => (
                          <li key={item.id}>
                            <button type="button" className="agreements-link" onClick={() => void downloadDocument(item.id, item.originalName)}>
                              {item.originalName}
                            </button>
                            <span className="agreement-activity__document-meta"> {formatInstant(item.createdAt)}, {item.stageName}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                  {canEdit && editor === null && (
                    <div className="agreement-activity__actions">
                      <button type="button" className="button--secondary" onClick={() => openEditor({ mode: 'edit', activity })}>Изменить</button>
                      <button type="button" className="button--danger" onClick={() => setDeleting(activity)}>Удалить</button>
                    </div>
                  )}
                </>
              )}
            </li>
          ))}
        </ul>
      </section>
      <ConfirmDialog
        open={deleting !== null}
        title="Удалить мероприятие?"
        description={`«${deleting?.title ?? ''}» будет удалено из плана вместе со связями. Сами документы останутся в работах вуза.`}
        confirmLabel="Удалить"
        onConfirm={() => void confirmDelete()}
        onCancel={() => setDeleting(null)}
      />
    </article>
  )
}

type ActivityFormProps = {
  agreementId: string
  activity?: AgreementActivity
  catalogs: Catalogs
  onSaved: () => void
  onCancel: () => void
  handleAccessError: (error: unknown) => boolean
}

const ActivityForm = ({ agreementId, activity, catalogs, onSaved, onCancel, handleAccessError }: ActivityFormProps) => {
  const kinds = catalogs.kinds.filter((kind) => !kind.archived || kind.id === activity?.kindId)
  const [kindId, setKindId] = useState(activity?.kindId ?? kinds[0]?.id ?? '')
  const [title, setTitle] = useState(activity?.title ?? '')
  const [unit, setUnit] = useState(activity?.unit ?? '')
  const [plannedVolume, setPlannedVolume] = useState(activity?.plannedVolume?.toString() ?? '')
  const [actualVolume, setActualVolume] = useState(activity?.actualVolume?.toString() ?? '')
  const [plannedStart, setPlannedStart] = useState(activity?.plannedStart ?? '')
  const [plannedEnd, setPlannedEnd] = useState(activity?.plannedEnd ?? '')
  const [actualStart, setActualStart] = useState(activity?.actualStart ?? '')
  const [actualEnd, setActualEnd] = useState(activity?.actualEnd ?? '')
  const [responsibleId, setResponsibleId] = useState(activity?.responsibleProfileId ?? '')
  const [status, setStatus] = useState<ActivityStatus>(activity?.status ?? 'PLANNED')
  const [interactionIds, setInteractionIds] = useState<string[]>(activity?.interactions.map((item) => item.id) ?? [])
  const [attachmentIds, setAttachmentIds] = useState<string[]>(activity?.attachments.map((item) => item.id) ?? [])
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()
  const formId = useId()

  const toggle = (values: string[], id: string, checked: boolean) => (
    checked ? [...values, id] : values.filter((item) => item !== id)
  )

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload: AgreementActivityInput = {
      version: activity?.version ?? null,
      kindId,
      title: title.trim(),
      unit: orNull(unit),
      plannedVolume: numberOrNull(plannedVolume),
      actualVolume: numberOrNull(actualVolume),
      plannedStart: orNull(plannedStart),
      plannedEnd: orNull(plannedEnd),
      actualStart: orNull(actualStart),
      actualEnd: orNull(actualEnd),
      responsibleProfileId: orNull(responsibleId),
      status,
      interactionIds,
      attachmentIds
    }
    setSaving(true)
    setError(null)
    try {
      const key = commandKey.keyFor(payload)
      if (activity === undefined) {
        await agreementsApi.createActivity(agreementId, payload, key)
      } else {
        await agreementsApi.updateActivity(activity.id, payload, key)
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
    <form className="agreement-form" onSubmit={(event) => void submit(event)} aria-label={activity ? 'Изменение мероприятия' : 'Новое мероприятие'}>
      <div className="agreement-form__grid">
        <label className="agreement-form__wide">
          Вид мероприятия
          <select value={kindId} required onChange={(event) => setKindId(event.target.value)}>
            {kinds.map((kind) => <option key={kind.id} value={kind.id}>{kind.name}{kind.archived ? ' (в архиве)' : ''}</option>)}
          </select>
        </label>
        <label className="agreement-form__wide">
          Мероприятие
          <input value={title} required maxLength={300} onChange={(event) => setTitle(event.target.value)} />
        </label>
        <label>
          Объём по плану
          <input type="number" min={0} step={1} inputMode="numeric" value={plannedVolume} onChange={(event) => setPlannedVolume(event.target.value)} />
        </label>
        <label>
          Объём факт
          <input type="number" min={0} step={1} inputMode="numeric" value={actualVolume} onChange={(event) => setActualVolume(event.target.value)} />
        </label>
        <label>
          Единица
          <input value={unit} maxLength={50} placeholder="чел., ч, программ" onChange={(event) => setUnit(event.target.value)} />
        </label>
        <label>
          Статус
          <select value={status} onChange={(event) => setStatus(event.target.value as ActivityStatus)}>
            {activityStatuses.map((item) => <option key={item} value={item}>{activityStatusLabels[item]}</option>)}
          </select>
        </label>
        <label>
          План: начало
          <input type="date" value={plannedStart} max={plannedEnd || undefined} onChange={(event) => setPlannedStart(event.target.value)} />
        </label>
        <label>
          План: окончание
          <input type="date" value={plannedEnd} min={plannedStart || undefined} onChange={(event) => setPlannedEnd(event.target.value)} />
        </label>
        <label>
          Факт: начало
          <input type="date" value={actualStart} max={actualEnd || undefined} onChange={(event) => setActualStart(event.target.value)} />
        </label>
        <label>
          Факт: окончание
          <input type="date" value={actualEnd} min={actualStart || undefined} onChange={(event) => setActualEnd(event.target.value)} />
        </label>
        <label className="agreement-form__wide">
          Ответственный
          <select value={responsibleId} onChange={(event) => setResponsibleId(event.target.value)}>
            <option value="">Не назначен</option>
            {catalogs.options.responsibles.map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}
          </select>
        </label>
      </div>
      <fieldset className="agreement-form__choices">
        <legend>Связанные работы вуза</legend>
        {catalogs.options.interactions.length === 0 && <p>У вуза пока нет работ.</p>}
        <ul>
          {catalogs.options.interactions.map((item) => (
            <li key={item.id}>
              <label>
                <input
                  type="checkbox"
                  checked={interactionIds.includes(item.id)}
                  onChange={(event) => setInteractionIds((current) => toggle(current, item.id, event.target.checked))}
                />
                <span>{item.title}</span>
              </label>
            </li>
          ))}
        </ul>
      </fieldset>
      <fieldset className="agreement-form__choices" aria-describedby={`${formId}-documents`}>
        <legend>Подтверждающие документы</legend>
        <p id={`${formId}-documents`} className="agreement-form__hint">
          Документы загружаются в работу вуза на нужном этапе и после проверки выбираются здесь. Списки обучившихся с ФИО не прикладывайте без решения оператора персональных данных: число обученных указывается объёмом.
        </p>
        {catalogs.options.attachments.length === 0 && <p>Проверенных документов в работах вуза пока нет.</p>}
        <ul>
          {catalogs.options.attachments.map((item) => (
            <li key={item.id}>
              <label>
                <input
                  type="checkbox"
                  checked={attachmentIds.includes(item.id)}
                  onChange={(event) => setAttachmentIds((current) => toggle(current, item.id, event.target.checked))}
                />
                <span>{item.originalName}<span className="agreement-activity__document-meta"> — {item.interactionTitle}, {item.stageName}, {formatInstant(item.createdAt)}</span></span>
              </label>
            </li>
          ))}
        </ul>
      </fieldset>
      {error !== null && <CommandError error={error} fallback="Мероприятие не сохранено." />}
      <div className="agreement-form__actions">
        <button type="submit" disabled={saving || kindId === ''}>{saving ? 'Сохраняем…' : 'Сохранить мероприятие'}</button>
        <button type="button" className="button--secondary" onClick={onCancel} disabled={saving}>Отмена</button>
      </div>
    </form>
  )
}
